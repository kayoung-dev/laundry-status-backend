# 개발 서버 배포

개발 서버는 GCP VM(`washon-vm`, asia-northeast3-a)의 `/opt/washon` 구성으로 운영한다. 배포 인프라(Caddy, Mosquitto, compose 파일, 배포 스크립트)는 **이 저장소 밖**의 서버 세팅 묶음(`washon-server`)에서 관리하고, 이 저장소는 `Dockerfile`과 애플리케이션 설정만 제공한다.

| 항목 | 값 |
|---|---|
| API | `https://api-dev.washon.site` |
| 프론트 (CORS 허용 출처, QR 링크) | `https://dev.washon.site` |
| 카메라 MQTT | `mqtts://mqtt.washon.site:8883` |
| 배포 브랜치 | `dev` (`/opt/washon/deploy-dev.sh`가 받아서 빌드) |

## 구성

```
/opt/washon/
 ├─ edge/      공용: Caddy(80/443, HTTPS) + Mosquitto(8883 TLS 외부 / 1883 내부)
 ├─ dev/       개발 스택: dev-app(이 저장소) + dev-mariadb + dev-redis   ← compose.yml, .env
 ├─ src/backend/  이 저장소 dev 브랜치 (배포 전용, 직접 수정 금지)
 └─ deploy-dev.sh

인터넷 ─443─▶ caddy ─▶ dev-app:8080 ─▶ dev-mariadb:3306, dev-redis:6379
카메라 ─8883(TLS)─▶ mosquitto ◀─1883─ dev-app   (Docker 네트워크 washon-net)
```

## 배포 방법

```bash
# 기능 브랜치 → main 머지 후, dev 브랜치에 반영
git switch dev && git merge --ff-only main && git push    # 또는 main → dev PR

# VM에서
/opt/washon/deploy-dev.sh
```

## 백엔드가 기대하는 환경변수 (`/opt/washon/dev/compose.yml`)

| 환경변수 | 예시 | 설명 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` | 프록시 헤더 신뢰, 세션 쿠키 `Secure`, MQTT 수신 켜기 (`application-dev.yml`) |
| `PORT` | `8080` | 앱 포트 (기본 8081). Caddy가 `dev-app:8080`으로 보냄 |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | `jdbc:mariadb://dev-mariadb:3306/washon` | DB |
| `REDIS_URL` | `redis://:<비밀번호>@dev-redis:6379` | Redis. **`SPRING_DATA_REDIS_HOST` 등은 쓰지 않는다** — `application.yml`의 `spring.data.redis.url`이 우선해서 무시된다 |
| `WASHON_ADMIN_SESSION_SECRET` | `openssl rand -hex 32` | 관리자 세션 서명 키. 없으면 코드의 개발용 기본값이 쓰이므로 반드시 설정 |
| `WASHON_CORS_ALLOWED_ORIGINS` | `https://dev.washon.site` | 쉼표로 여러 개 가능. `*` 불가 |
| `WASHON_FRONTEND_BASE_URL` | `https://dev.washon.site` | QR 스티커가 학생을 보내는 주소 |
| `WASHON_MQTT_URL` | `tcp://mosquitto:1883` | 내부망 브로커 |
| `WASHON_MQTT_USERNAME` / `_PASSWORD` | `server-dev` | 서버용 MQTT 계정 (client id 기본값도 이 아이디) |
| `WASHON_MQTT_TOPIC_PREFIX` | `dev/washon` | 카메라 토픽 prefix |
| `JAVA_TOOL_OPTIONS` | `-Xmx512m -Duser.timezone=UTC` | 힙 상한, UTC |

시간은 DB·JVM 모두 UTC로 저장하고 한국 시간 표시는 화면에서 한다.

## 카메라 (MQTT)

| 항목 | 값 |
|---|---|
| 브로커 | `mqtts://mqtt.washon.site:8883` |
| 계정 | 기기마다 따로. **아이디 = 기기 ID** (예: `wm-a1b2c3`, 테스트 `wm-test`). `edge/mqtt-user.sh <기기ID>`로 생성 |
| 토픽 | `dev/washon/{기기ID}/ocr`, QoS 1 |
| 페이로드 | `POST /ingest/ocr` 본문과 같은 JSON (API 키 없음) |

```json
{"captured_at":"2026-10-26T01:00:00Z","confidence":0.93,"raw_text":"W 12:34","frame_hash":"a1b2c3d4"}
```

- 기기 인증은 브로커가 한다. ACL(`pattern write dev/washon/%u/#`) 때문에 기기는 자기 ID 토픽에만 쓸 수 있으므로, 백엔드는 토픽의 기기 ID를 그대로 믿는다.
- 단, 그 기기 ID가 **DB `cameras.device_id`에 등록돼 있어야** 한다. 등록 안 된 기기의 메시지는 버린다 (`Rejected MQTT OCR frame from wm-xxx: 401 Unknown device_id`).
- 카메라별 rate limit(5초에 1건, 순간 5건), frame_hash 중복 제거, 상태 머신은 HTTP와 같다.
- MQTT는 응답이 없어서 거부된 메시지는 백엔드 로그에만 남는다: `docker logs dev-app | grep "Rejected MQTT"`
- 백엔드가 재시작하는 동안 온 QoS 1 메시지는 브로커가 보관했다가 전달한다.
- HTTP(`POST /ingest/ocr`)는 기존대로 `X-Api-Key`가 필요하다.

## 시드 데이터 (개발 서버 전용)

테스트용 세탁실 1개, 세탁기 3대(카메라 `seed-device-1~3`), 관리자 계정을 만든다. 실행할 때마다 관리자 비밀번호와 HTTP용 API 키가 새로 발급된다. **운영 환경에서는 절대 실행하지 않는다.**

```bash
cd /opt/washon/dev
mkdir -p .seed-out
docker compose run --rm --no-deps -u "$(id -u):$(id -g)" \
  -e SPRING_PROFILES_ACTIVE=seed -v "$PWD/.seed-out:/seed" -w /seed dev-app
cat .seed-out/.seed-admin-credentials
```

테스트 기기 `wm-test`를 세탁기 1번 카메라로 연결:
```bash
docker exec -i dev-mariadb sh -c 'mariadb -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" "$MARIADB_DATABASE"' \
  <<< "UPDATE cameras SET device_id = 'wm-test' WHERE id = 'seed-camera-1';"
```
(시드를 다시 실행하면 `seed-device-1`로 되돌아가므로 이 명령도 다시 실행한다.)

## 확인

```bash
curl https://api-dev.washon.site/actuator/health                       # {"status":"UP",...}
curl https://api-dev.washon.site/machines/seed-machine-1/qr-target      # url이 https://dev.washon.site/...
docker logs dev-app | grep "MQTT connected"                             # subscribing to dev/washon/+/ocr

# 로컬 PC에서 시뮬레이터로 wm-test 발행
npm --prefix tools/camera-simulator install
MQTT_PASSWORD=<wm-test 비밀번호> npm --prefix tools/camera-simulator run simulate -- wash-cycle \
  --mqtt-url mqtts://mqtt.washon.site:8883 --device-id wm-test
# 학생 화면: https://dev.washon.site/room/seed-room
```

## 문제 해결

| 증상 | 확인 |
|---|---|
| 헬스 체크 실패, 로그에 Redis 연결 오류 | compose에 `REDIS_URL`이 있는지 (`SPRING_DATA_REDIS_HOST`만으로는 안 됨) |
| 502 Bad Gateway | `PORT=8080`인지 (Caddy가 `dev-app:8080`으로 보냄). `docker logs dev-app`에서 `Tomcat started on port 8080` |
| 웹에서 API가 CORS로 막힘 | `WASHON_CORS_ALLOWED_ORIGINS`가 브라우저 주소와 정확히 같은지 (`https://`, 끝 `/` 없음) |
| 관리자 로그인 후 바로 풀림 | API를 HTTPS로 부르는지 (`Secure` 쿠키) |
| MQTT 메시지가 반영 안 됨 | `docker logs dev-app \| grep -E "MQTT\|Rejected"` — 연결 여부, `Unknown device_id`면 `cameras.device_id` 등록 확인, 토픽 prefix가 `dev/washon`인지 |
| DB 비밀번호를 바꿨더니 접속 실패 | MariaDB는 **최초 기동 때만** `.env` 비밀번호로 계정을 만든다. DB 안에서 직접 바꾸거나 볼륨 초기화 |
