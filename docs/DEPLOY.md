# 개발 서버 배포 가이드

VM 한 대에서 `git pull` 후 Docker Compose로 직접 빌드·실행한다. 자동 배포(CI/CD)는 이 방식이 안정된 뒤 붙인다.

| 항목 | 값 |
|---|---|
| API 주소 | `https://api-dev.washon.com` |
| 프론트 주소 (CORS 허용 출처, QR 링크) | `https://dev.washon.com` |
| 카메라 MQTT | `wss://api-dev.washon.com/mqtt` |
| 구성 파일 | `docker-compose.dev.yml`, `deploy/dev/`, `.env` |
| 배포 스크립트 | `./deploy-dev.sh` |

## 구성

```
인터넷 ──80/443──▶ caddy ──▶ backend:8081 ──▶ mariadb:3306, redis:6379
                     └─/mqtt (WebSocket)─▶ mosquitto:9001
                                          mosquitto:1883 ◀── backend (내부망)
```

- 외부에 여는 포트는 Caddy의 **80, 443뿐**이다. MariaDB·Redis·mosquitto·백엔드는 compose 내부망에서만 접근된다.
- Caddy가 Let's Encrypt 인증서를 자동으로 발급·갱신한다.
- 시간은 MariaDB·JVM 모두 **UTC로 저장**하고, 한국 시간 표시는 화면(프론트)에서 한다.
- 문자셋은 `utf8mb4` / `utf8mb4_unicode_ci`.
- 백엔드 JVM 힙 상한은 `JAVA_TOOL_OPTIONS=-Xmx512m` (`.env`에서 조정).
- 백엔드는 `dev` 프로필(`application-dev.yml`)로 실행된다: 프록시 헤더 신뢰, 관리자 세션 쿠키 `Secure`.

## 1. VM 준비 (최초 1회)

**권장 사양**: 2 vCPU, 메모리 2GB 이상 (VM 안에서 Gradle 빌드를 하므로 1GB면 빌드가 실패할 수 있다 — 그 경우 스왑 2GB 추가).

1. **DNS**: `api-dev.washon.com`의 A 레코드를 VM 공인 IP로 지정한다. 인증서 발급 전에 반영돼 있어야 한다.
   ```bash
   dig +short api-dev.washon.com   # VM IP가 나와야 함
   ```
2. **방화벽 / 보안 그룹**: 인바운드 TCP 22(SSH), 80, 443과 UDP 443을 연다. 그 외 포트는 열지 않는다.
3. **Docker 설치** (Ubuntu 기준):
   ```bash
   curl -fsSL https://get.docker.com | sudo sh
   sudo usermod -aG docker $USER   # 로그아웃 후 다시 로그인
   docker compose version          # v2 확인
   ```
4. **저장소 클론**: GitHub 접근용 deploy key(읽기 전용) 또는 HTTPS 토큰을 준비한다.
   ```bash
   git clone https://github.com/kayoung-dev/laundry-status-backend.git ~/washon-backend
   cd ~/washon-backend
   ```
5. **`.env` 작성**:
   ```bash
   cp .env.example .env
   chmod 600 .env
   # 비밀값 생성: openssl rand -hex 32
   vi .env
   ```
   모든 값을 채운다. 값에 공백·따옴표를 넣지 않는다. `.env`는 git에 올라가지 않는다.

## 2. 배포

```bash
cd ~/washon-backend
./deploy-dev.sh
```

스크립트가 하는 일:
1. `.env` 필수 값 확인
2. `git pull --ff-only`
3. `.env`의 MQTT 비밀번호로 mosquitto 계정 파일(`deploy/dev/mosquitto/passwd`, git 미추적) 생성
4. `docker compose -f docker-compose.dev.yml up -d --build`
5. 백엔드 `/actuator/health`가 UP이 될 때까지 최대 180초 대기 → 실패 시 백엔드 로그 출력

확인:
```bash
curl https://api-dev.washon.com/actuator/health
```

## 3. 시드 데이터 (선택, 개발 서버 전용)

테스트용 세탁실·기기 3대·관리자 계정을 만든다. 실행할 때마다 API 키와 관리자 비밀번호가 새로 발급된다. **운영 환경에서는 절대 실행하지 않는다.**

```bash
mkdir -p .seed-out
docker compose -f docker-compose.dev.yml run --rm --no-deps \
  -u "$(id -u):$(id -g)" -e SPRING_PROFILES_ACTIVE=seed \
  -v "$PWD/.seed-out:/seed" -w /seed backend
cat .seed-out/.seed-admin-credentials   # 관리자 이메일/비밀번호
cat .seed-out/.seed-api-keys.txt        # 카메라 device_id=API 키
```

`.seed-out/`은 git에 올라가지 않는다. 확인 후 안전한 곳에 옮기고 VM에서는 지우는 것을 권장한다.

## 4. 카메라 연결 (MQTT)

카메라는 HTTP(`POST https://api-dev.washon.com/ingest/ocr`, `X-Api-Key` 헤더) 또는 MQTT로 OCR 프레임을 보낸다. 둘 다 백엔드에서 같은 경로(인증·rate limit·중복 제거·상태 머신)로 처리된다.

| 항목 | 값 |
|---|---|
| 브로커 | `wss://api-dev.washon.com/mqtt` (MQTT over WebSocket, TLS는 Caddy) |
| 계정 | `washon-camera` / `.env`의 `MQTT_CAMERA_PASSWORD` (모든 카메라 공용, 발행 전용) |
| 토픽 | `washon/devices/{device_id}/ocr` |
| QoS | 1 |
| 페이로드 | `POST /ingest/ocr` 본문 + `api_key` (카메라별 API 키) |

```json
{"api_key":"<카메라 API 키>","captured_at":"2026-10-26T01:00:00Z","confidence":0.93,"raw_text":"W 12:34","frame_hash":"a1b2c3d4"}
```

- 카메라 구분과 인증은 **토픽의 device_id + 페이로드의 api_key**로 한다 (HTTP와 같은 키). 공용 MQTT 계정만으로는 다른 카메라 행세를 할 수 없다.
- MQTT는 응답이 없다. 키가 틀렸거나 rate limit(5초에 1건, 순간 5건)에 걸린 프레임은 백엔드 로그에만 남는다:
  ```bash
  $C logs backend | grep "Rejected MQTT"
  ```
- 백엔드가 재시작하는 동안 들어온 QoS 1 메시지는 브로커가 보관했다가 다시 전달한다.

시뮬레이터로 확인 (로컬 PC에서, 시드의 API 키 사용):
```bash
npm --prefix tools/camera-simulator install
MQTT_PASSWORD=<MQTT_CAMERA_PASSWORD> npm --prefix tools/camera-simulator run simulate -- wash-cycle \
  --mqtt-url wss://api-dev.washon.com/mqtt --device-id seed-device-1 --api-key <seed-device-1 키>
```

## 5. 운영 명령

```bash
C="docker compose -f docker-compose.dev.yml"

$C ps                          # 상태
$C logs -f --tail 100 backend  # 백엔드 로그 (caddy, mosquitto, mariadb도 같은 방식)
$C restart backend             # 재시작
$C down                        # 중지 (데이터 볼륨은 유지)

# DB 접속
$C exec mariadb mariadb -u washon -p washon

# DB 백업
$C exec -T mariadb sh -c 'mariadb-dump -u root -p"$MARIADB_ROOT_PASSWORD" washon' > backup-$(date +%F).sql
```

`down -v`는 **DB·Redis·인증서 볼륨까지 삭제**하므로 개발 서버를 완전히 초기화할 때만 쓴다.

## 6. 문제 해결

| 증상 | 확인 |
|---|---|
| 인증서 발급 실패 (`$C logs caddy`) | DNS가 VM IP를 가리키는지, 80/443이 열려 있는지. 고친 뒤 `$C restart caddy` |
| 헬스 체크 180초 초과 | `$C logs backend`. DB 비밀번호 불일치라면: MariaDB는 **최초 기동 때만** `.env` 비밀번호로 계정을 만든다. 이후 `.env`의 `DB_PASSWORD`를 바꾸면 DB 안의 비밀번호도 직접 바꾸거나 볼륨을 초기화해야 한다 |
| 빌드 중 멈춤/강제 종료 | 메모리 부족. 스왑 추가 또는 VM 사양 상향 |
| 웹에서 API 호출이 CORS로 막힘 | `.env`의 `FRONTEND_ORIGIN`이 브라우저 주소와 정확히 같은지 (`https://`, 끝 `/` 없음) |
| 관리자 로그인 후 바로 로그아웃됨 | API를 HTTPS로 호출하는지 (`Secure` 쿠키는 HTTP에서 저장되지 않음) |
| SSE가 1분쯤 뒤 끊김 | 백엔드가 15초마다 `:ping`을 보내는 버전인지 확인 |
| MQTT 접속 거부 | `.env`의 MQTT 비밀번호를 바꿨다면 `./deploy-dev.sh` 재실행 (계정 파일 재생성 + mosquitto 재로드) |
| MQTT 발행은 되는데 상태가 안 바뀜 | `$C logs backend \| grep -E "MQTT\|Rejected"` — 구독 연결 여부, API 키·device_id 거부 여부 |
