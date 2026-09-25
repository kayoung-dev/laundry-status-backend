# WashOn Backend — Claude 작업 규칙

Spring Boot 3.5 (Java 17, Gradle Kotlin DSL) + Spring Data JPA + MariaDB 10.11 + Flyway + Redis.
이 저장소는 현재 코드가 기준이다. 과거 계획 문서(`docs/archive/`)는 참고용 기록일 뿐이며, NestJS/Prisma 관련 내용이 있더라도 따르지 않는다.

## 코드 구조

- 루트 패키지 `dev.washon`, 도메인별 패키지(`admin`, `auth`, `facility`, `machine`, `realtime`, `report`)와 공통 `common`.
- 도메인 패키지 내부 계층:
  - `api` — `@RestController`. 요청 검증과 응답 매핑만, 비즈니스 로직 금지
  - `application` — 서비스, 트랜잭션 경계(`@Transactional`), `@ConfigurationProperties`
  - `domain` — JPA 엔티티, enum, 순수 도메인 로직(예: `StateDecision`)
  - `infrastructure` — Spring Data JPA 리포지토리
  - `payload/request`, `payload/response` — API DTO. 엔티티를 API로 직접 노출하지 않는다
- 새 기능도 같은 구조를 따른다. 해당 계층이 필요 없으면 만들지 않는다.
- ID는 CUID(`common/jpa/CuidId`)를 사용한다.
- 시간은 `Instant` / `DATETIME(3)` UTC 기준 (테스트 JVM도 UTC 고정).
- Redis는 락·rate limit 전용이다. Redis를 Spring Data repository 저장소로 쓰지 않는다.

## DB / 스키마 규칙

- **스키마 변경 시 엔티티 수정과 새 Flyway 마이그레이션 파일을 반드시 같은 커밋에 넣는다.**
  - 새 파일은 `src/main/resources/db/migration/V{다음번호}__{설명}.sql`.
  - 이미 적용된 마이그레이션 파일(V1, V2, ...)은 수정하지 않는다.
- **`spring.jpa.hibernate.ddl-auto: validate`를 유지한다.** `update`/`create`/`create-drop`으로 바꾸지 않는다. 스키마는 Flyway만 소유한다.
- **시드 데이터는 `seed` 프로필을 명시적으로 켰을 때만 실행한다** (`DevSeedRunner`의 `@Profile("seed")` 유지). **운영 환경에서는 `seed` 프로필을 절대 켜지 않는다.**
  - 시드가 생성하는 비밀값 파일(`.seed-*`)은 커밋하지 않는다.

## 검증

- 변경 후 `./gradlew test` (Testcontainers — Docker 필요)로 확인한다.
- 수동 확인 절차는 `docs/TESTING.md`.

## 로컬 환경 / 프로세스

- 사용 포트: 백엔드 `8081`, MariaDB `3307`, Redis `6379` (`docker-compose.yml`).
- **이 프로젝트와 무관한 프로세스는 절대 종료하지 않는다.**
- **필요한 포트가 이미 사용 중이면 프로세스를 죽이거나 포트를 임의로 바꾸지 말고, 사용 중인 프로세스 정보와 함께 사용자에게 알린다.**
