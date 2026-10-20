# WashOn 테스트 가이드

이 문서는 로컬에서 WashOn을 직접 띄워서, 세탁기 상태(state)별로 실제 동작을 확인하는 방법을 정리한다. 자동화된 백엔드 테스트(84개, JUnit)는 6절을 참고.

시드 데이터는 세탁실(`seed-room`) 하나에 세탁기 3대(`seed-machine-1`/`2`/`3`, "Washer 1/2/3")로 구성되어 있어, 기기 간 독립성(한 기기의 상태 변화가 다른 기기에 영향을 주지 않는지)도 같이 확인할 수 있다.

> **이 문서의 명령어는 백엔드 저장소 루트(`laundry-status-backend/`)에서 실행한다고 가정한다.** 프론트엔드는 별도 저장소([laundry-status-front](https://github.com/kayoung-dev/laundry-status-front))이며, 아래 예시는 두 저장소를 같은 상위 디렉토리에 나란히 클론했다고 가정한다(`../laundry-status-front`).
>
> 카메라 시뮬레이터는 최초 1회 `npm --prefix tools/camera-simulator install`이 필요하다.

---

## 1. 사전 준비

```bash
# 1) DB + Redis
docker compose up -d          # mariadb:3307, redis:6379

# 2) 백엔드 (최초 1회 또는 시드 데이터를 새로 만들고 싶을 때)
./gradlew bootRun --args='--spring.profiles.active=seed'
# 프로세스가 자동 종료되며 아래 파일에 자격증명이 기록됨:
#   .seed-api-key                (seed-device-1의 API 키만, 기본 경로)
#   .seed-api-keys.txt           (3대 모두의 "device_id=key" 목록)
#   .seed-admin-credentials (관리자 이메일/비밀번호)

# 3) 백엔드 실행
./gradlew bootRun             # http://localhost:8081

# 4) 프론트엔드
cd ../laundry-status-front
NEXT_PUBLIC_API_URL=http://localhost:8081 npm run dev   # http://localhost:3000
```

**확인:**
```bash
curl http://localhost:8081/actuator/health   # {"status":"UP",...}
cat .seed-api-keys.txt
# seed-device-1=dev_xxxx...
# seed-device-2=dev_xxxx...
# seed-device-3=dev_xxxx...
cat .seed-admin-credentials
```

시드 재실행 시 모든 API 키/관리자 비밀번호가 새로 발급되니, 아래 명령의 `--api-key` 값은 매번 `.seed-api-keys.txt`에서 확인할 것.

**학생 뷰:** http://localhost:3000/room/seed-room
**관리자:** http://localhost:3000/admin/login (`.seed-admin-credentials`의 계정으로 로그인)

---

## 2. 카메라 시뮬레이터로 상태 발생시키기

실제 카메라/AI OCR 모델이 없으므로, `tools/camera-simulator/camera-simulator.ts`가 OCR 텍스트를 흉내 내어 `POST /ingest/ocr`로 전송한다. 학생 뷰를 브라우저에 띄워둔 채로 실행하면 새로고침 없이 실시간(SSE)으로 반영되는 것을 볼 수 있다.

```bash
npm --prefix tools/camera-simulator run simulate -- <시나리오> \
  --device-id <device_id> \
  --api-key <해당 device의 API 키> \
  --base-url http://localhost:8081
```

`--device-id`/`--api-key`를 생략하면 각각 `seed-device-1`, `.seed-api-key`(=seed-device-1의 키)가 기본값으로 쓰인다. `seed-machine-2`/`3`을 테스트하려면 `.seed-api-keys.txt`에서 `seed-device-2`/`seed-device-3`의 키를 꺼내 지정해야 한다.

---

## 3. 세탁기 상태별 테스트 케이스

각 케이스는 "무엇을 보내고 → 무엇이 바뀌어야 하는지"로 정리했다. 백엔드 로직 근거는 `src/main/java/dev/washon/machine/`.

### 3.1 `available` (사용 가능) — 초기 상태
- **준비물:** 없음 (시드 직후 3대 모두 `unknown`으로 시작하며, 아래 3.2~3.4 중 하나를 거쳐 `available`로 돌아오는 것이 일반적인 경로)
- **확인:** 학생 뷰에서 "사용 가능" 배지, 관리자 대시보드에서 `state: available`
- **참고:** `available`은 `done` 상태에서 60초 경과 후 자동 전이되거나(3.3), 관리자가 오버라이드를 해제할 때(3.7) 돌아오는 상태다. OCR 텍스트로 직접 만드는 시나리오는 없음.

### 3.2 `in_use` (사용 중) — 정상 카운트다운
```bash
npm --prefix tools/camera-simulator run simulate -- decreasing-countdown --device-id seed-device-1 --api-key <키>
```
- **동작:** 감소하는 숫자(28→25→22→19→16) 3프레임 연속 수신 시 `in_use`로 전이 (N-of-3 합의)
- **확인:** 학생 뷰 "사용 중, 남은 시간: 16분", 관리자 대시보드 `remainingMin` 갱신, SSE 이벤트가 **2초 이내** 반영되는지 체감 확인
- **자동화 테스트:** `MachineStateServiceTest$Ac1ThreeConsecutiveDecreasingFramesToInUse`

### 3.3 `in_use` + 세탁 단계 아이콘 (세탁/헹굼/탈수) — Phase 2
```bash
npm --prefix tools/camera-simulator run simulate -- wash-cycle --device-id seed-device-2 --api-key <키>
```
- **동작:** `W:20→W:16→W:12`(세탁) → `R:9`(헹굼) → `S:5`(탈수) → 빈 화면(완료) 전송
- **확인:** 학생 뷰에서 상태 라벨이 "🧺 세탁 중" → "💧 헹굼 중" → "🌀 탈수 중" 순서로 바뀌고, 완료 후 "완료(세탁물 확인 필요)"로 전환. 일반 카운트다운(3.2)에는 아이콘이 뜨지 않고 기존 "사용 중"만 표시되어야 함(회귀 확인)
- **참고:** `W:nn`/`R:nn`/`S:nn`은 실제 OCR 모델이 없는 상태에서 정의한 임시 컨벤션(`OcrReading.STAGE_PATTERN`). 실제 카메라/OCR이 붙으면 이 컨벤션을 그대로 따르거나 파싱 로직을 다시 써야 한다.
- **자동화 테스트:** `MachineStateServiceTest$Ac7WashStageInferredFromOcrTextConvention`, `StateDecisionTest`

### 3.4 `done` → `available` (완료 후 자동 복귀)
```bash
npm --prefix tools/camera-simulator run simulate -- done --device-id seed-device-1 --api-key <키>
```
- **동작:** 05→02→00→빈 화면 순으로 수신 시 `done`으로 전이, **60초 후 자동으로 `available`로 복귀**
- **확인:** 완료 직후 "완료(세탁물 확인 필요)" 표시 → 60초 뒤 새로고침 없이 "사용 가능"으로 자동 전환되는지 확인 (실제로 60초 기다려야 함 — 급하면 아래 4절의 짧은 대기시간 오버라이드 참고)
- **자동화 테스트:** `MachineStateServiceTest$Ac2BlankOrZeroDisplayToDoneThenAvailable` (테스트는 60초가 아닌 짧게 오버라이드된 값 사용)

### 3.5 `error` (장애)
```bash
npm --prefix tools/camera-simulator run simulate -- error --device-id seed-device-3 --api-key <키>
```
- **동작:** `E`/`F` 접두 에러코드(`E01`) 수신 시 `error`로 전이, 에러코드 저장
- **확인:** 학생 뷰 "오류" 배지 + 에러코드, 관리자 대시보드에 **폴링 없이 즉시(SSE push)** 표시되는지 확인
- **자동화 테스트:** `MachineStateServiceTest$Ac3ErrorTextToErrorWithCodeStored`

### 3.6 `unknown` (무응답)
- **동작:** 60초 이상 새 프레임이 없으면 `unknown`으로 자동 전이 (수동으로 트리거할 시나리오는 없음 — 아무 기기에나 프레임을 보내지 않고 60초 기다리면 재현됨)
- **확인:** `unknown` 상태에서는 완료 알림이 발송되지 않음(현재는 스텁이라 로그도 안 남는 것으로 확인 가능)
- **자동화 테스트:** `MachineStateServiceTest$Ac4SilenceToUnknownWithStalenessData` (짧게 오버라이드된 임계값 사용)

### 3.7 `maintenance` (관리자 수동 지정) + 해제
1. http://localhost:3000/admin/login 로그인
2. 대시보드에서 임의 기기의 "점검 중으로 지정" 클릭
3. **다른 터미널에서** 같은 기기로 `decreasing-countdown` 전송 → 상태가 바뀌지 않고 `maintenance`로 유지되는지 확인 (오버라이드가 OCR 갱신을 억제)
4. "해제" 클릭 → 다음 OCR 프레임부터 자동 판별이 재개되는지 확인
- **감사 로그:** 지정/해제 모두 `source=admin_override` 이벤트로 기록됨 (대시보드의 감사 로그 보기에서 확인 가능, 관리자 이메일 + 시각 포함)
- **자동화 테스트:** `AdminApiTest`, `AdminOverrideConcurrencyTest` (동시성 — 오버라이드와 OCR 갱신이 동시에 들어와도 레이스가 없는지)

### 3.8 "확인 필요" 플래그 (학생 제보)
```bash
for i in 1 2 3; do
  curl -s -X POST http://localhost:8081/reports \
    -H "Content-Type: application/json" \
    -d "{\"machine_id\":\"seed-machine-1\",\"reported_state\":\"available\",\"device_id\":\"test-device-$i\"}"
done
```
- **동작:** 서로 다른 `device_id` 3건 이상이 10분 내 접수되면 해당 기기에 플래그
- **확인:** 관리자 대시보드에 "확인 필요" 배지, `needsCheck: true`. 같은 `device_id`로 여러 번 보내면 카운트가 늘지 않는지도 함께 확인
- **자동화 테스트:** `ReportApiTest`

### 3.9 카메라 다운 알림
- **동작:** 한 기기에 5분 이상 새 프레임이 없으면 대시보드에 `cameraDown: true` 배지, 복구 시(새 프레임 수신) 자동 해제
- **확인:** 실제로는 5분이 걸려 수동 테스트하기 번거로움 — 아래 4절처럼 임계값을 짧게 오버라이드해서 확인하는 것을 권장
- **자동화 테스트:** `CameraDownMonitorTest`

### 3.10 per-machine QR 라우팅
```
http://localhost:3000/room/seed-room?machine=seed-machine-2
```
- **확인:** 해당 기기 카드가 하이라이트되며 화면 중앙으로 스크롤되는지 확인. `?machine=` 없이 `/room/seed-room`만 열었을 때는 아무 카드도 하이라이트되지 않아야 함(회귀 확인)
- **API 직접 확인:** `curl http://localhost:8081/machines/seed-machine-2/qr-target` → 동일 machineId로 여러 번 호출해도 같은 URL이 나오는지(결정론적), 존재하지 않는 machineId는 404가 나는지 확인

---

## 4. 대기시간이 긴 케이스를 빠르게 테스트하기 (선택)

3.4(60초)/3.6(60초)/3.9(5분)는 실제로 기다리면 오래 걸린다. 로컬에서만 짧게 확인하고 싶다면 환경변수로 오버라이드해서 백엔드를 재기동하면 된다:

```bash
WASHON_STATE_MACHINE_DONE_TO_AVAILABLE=3s \
WASHON_STATE_MACHINE_STALE_UNKNOWN=5s \
WASHON_STATE_MACHINE_CAMERA_DOWN=10s \
WASHON_STATE_MACHINE_CAMERA_DOWN_SWEEP=2s \
./gradlew bootRun
```
정확한 프로퍼티 키는 `src/main/java/dev/washon/machine/application/StateMachineProperties.java` 참고. **운영/기본 실행 시에는 오버라이드 없이(60초/60초/5분 기본값으로) 띄울 것.**

---

## 5. 여러 기기 동시 확인

시드 데이터가 3대(`seed-machine-1/2/3`, `seed-device-1/2/3`)이므로, 서로 다른 터미널에서 각기 다른 시나리오를 동시에 실행해 기기 간 상태가 서로 간섭하지 않는지 확인할 수 있다:

```bash
# 터미널 A
npm --prefix tools/camera-simulator run simulate -- decreasing-countdown --device-id seed-device-1 --api-key <1번 키>
# 터미널 B (동시에)
npm --prefix tools/camera-simulator run simulate -- error --device-id seed-device-2 --api-key <2번 키>
# 터미널 C (동시에)
npm --prefix tools/camera-simulator run simulate -- wash-cycle --device-id seed-device-3 --api-key <3번 키>
```
학생 뷰(`/room/seed-room`)를 열어두면 세 카드가 각각 독립적으로 "사용 중" / "오류" / "세탁 중"으로 바뀌는 것을 동시에 볼 수 있다.

---

## 6. 자동화 테스트 (백엔드)

실제 MariaDB+Redis(Testcontainers)를 사용하는 84개 JUnit 테스트. Mock 없음.

```bash
./gradlew test          # 전체 실행
./gradlew build          # 테스트 + 빌드
```

특정 케이스만 실행:
```bash
./gradlew test --tests "dev.washon.machine.application.MachineStateServiceTest*"
./gradlew test --tests "*AdminOverrideConcurrencyTest*"
```

프론트엔드는 별도 테스트 스위트 없이 빌드 확인만 한다:
```bash
cd ../laundry-status-front && npm run build
```

---

## 7. 범위 밖 (아직 구현되지 않음)

`.omc/plans/washon-plan.md` Phase 2 중 아래 항목은 외부 의존성(카카오 비즈니스 채널 승인, 실제 카메라 원본 이미지 미수신) 때문에 아직 구현되지 않았다 — 테스트 대상 아님:
- 카카오 알림톡(AlimTalk) 실제 발송 (`NotificationService`는 스텁 상태)
- OCR 원본 스냅샷 검증 화면
- 정확도 측정 기반 알림 게이팅
