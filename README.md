# WashOn Backend

카메라+OCR 기반 세탁기/건조기 실시간 상태 모니터링 시스템 **WashOn**의 API 서버.

프론트엔드(학생 뷰 + 관리자 대시보드)는 별도 저장소: [laundry-status-front](https://github.com/kayoung-dev/laundry-status-front)

## Stack

- Java 17, Spring Boot 3.5, Gradle (Kotlin DSL)
- Spring Data JPA (Hibernate) + **MariaDB 10.11**
- **Flyway**: 스키마는 `src/main/resources/db/migration`의 마이그레이션이 소유하며, Hibernate는 `ddl-auto: validate`로 검증만 한다
- **Redis**: 기기별 락과 OCR 수신 rate limit 용도로만 사용 (Spring Data repository 저장소로는 쓰지 않음)
- **SSE**: `GET /rooms/{roomId}/stream`으로 기기 상태 변경을 실시간 push
- 테스트: JUnit 5 + Testcontainers (실제 MariaDB/Redis 사용)

## 디렉토리 구조

```
src/main/java/dev/washon/
  admin/      관리자 대시보드, 상태 오버라이드, 감사 로그
  auth/       관리자 로그인/세션
  facility/   테넌트, 사이트, 세탁실(Room)
  machine/    OCR 수신, 상태 머신, 카메라 다운 감지, QR 대상
  realtime/   SSE 룸 스트림
  report/     학생 제보("확인 필요" 플래그)
  common/     crypto, jpa(CUID), redis(락/rate limit), seed, web(CORS/인터셉터)
src/main/resources/
  application.yml, application-seed.yml
  db/migration/        Flyway 마이그레이션 (V1__init.sql, ...)
tools/camera-simulator/  목(mock) 카메라: OCR 페이로드를 POST /ingest/ocr로 전송
docs/TESTING.md          상태별 수동 테스트 가이드
docker-compose.yml       로컬 MariaDB + Redis
```

각 도메인 패키지는 `api`(컨트롤러) / `application`(서비스) / `domain`(엔티티) / `infrastructure`(리포지토리) / `payload`(요청·응답 DTO)로 나뉜다.

## API

| Method | Path | 설명 |
|---|---|---|
| POST | `/ingest/ocr` | 카메라 OCR 프레임 수신 (`X-Api-Key`) |
| GET | `/rooms/{roomId}/stream` | 세탁실 기기 상태 SSE 스트림 |
| GET | `/machines/{machineId}/qr-target` | 기기별 QR 이동 URL |
| POST | `/reports` | 학생 상태 제보 |
| POST | `/admin/auth/login`, `/admin/auth/logout` | 관리자 로그인/로그아웃 (세션 쿠키) |
| GET | `/admin/auth/me` | 현재 관리자 |
| GET | `/admin/machines` | 관리자 대시보드 기기 목록 |
| GET | `/admin/machines/stream` | 관리자 대시보드 SSE (모든 세탁실의 기기 변경 신호) |
| POST | `/admin/machines/{id}/override`, `/override/clear` | 상태 수동 지정/해제 |
| GET | `/admin/machines/{id}/events` | 상태 변경 감사 로그 |
| GET | `/admin/machines/{id}/needs-check` | "확인 필요" 여부 |
| GET | `/actuator/health` | 헬스 체크 (db, redis) |

## 로컬 실행

사전 요구사항: Java 17, Docker, Node.js 20+ (시뮬레이터 사용 시)

```bash
# 1. MariaDB(3307) + Redis(6379)
docker compose up -d

# 2. (최초 1회) 시드 데이터 생성 — 완료 후 자동 종료되며 dev 자격증명을 파일로 남김
./gradlew bootRun --args='--spring.profiles.active=seed'
#   .seed-api-key, .seed-api-keys.txt, .seed-admin-credentials  (gitignore됨)

# 3. 서버 실행 → http://localhost:8081
./gradlew bootRun

# 4. (선택) 카메라 시뮬레이터
npm --prefix tools/camera-simulator install
npm --prefix tools/camera-simulator run simulate -- decreasing-countdown
```

### 환경변수

| 변수 | 기본값 |
|---|---|
| `DATABASE_URL` | `jdbc:mariadb://localhost:3307/washon` |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | `washon` / `washon` |
| `REDIS_URL` | `redis://localhost:6379` |
| `PORT` | `8081` |
| `WASHON_CORS_ALLOWED_ORIGINS` | `http://localhost:3000` (쉼표로 여러 개, `*` 불가. 예전 이름 `WASHON_CORS_ALLOWED_ORIGIN`도 동작) |
| `WASHON_FRONTEND_BASE_URL` | `http://localhost:3000` (QR 링크가 가리키는 프론트 주소) |
| `WASHON_MQTT_ENABLED` | `false` (켜면 `WASHON_MQTT_URL`/`_USERNAME`/`_PASSWORD`/`_TOPIC_PREFIX`로 카메라 MQTT 수신) |

상태 머신 대기시간(`washon.state-machine.*`)과 관리자 세션(`washon.admin.session.*`) 설정은 `StateMachineProperties`, `AdminSessionProperties` 참고.

## 테스트

```bash
./gradlew test     # 전체 JUnit (Testcontainers — Docker 필요)
./gradlew build    # 테스트 + 빌드
```

상태별 수동 테스트 절차는 [docs/TESTING.md](docs/TESTING.md) 참고.

## 개발 서버 배포

`https://api-dev.washon.site` — GCP VM의 `/opt/washon/deploy-dev.sh`가 `dev` 브랜치를 받아 이 저장소의 `Dockerfile`로 빌드한다. 환경변수·카메라 MQTT·시드는 [docs/DEPLOY.md](docs/DEPLOY.md) 참고.
