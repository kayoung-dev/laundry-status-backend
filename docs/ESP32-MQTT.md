# ESP32-CAM MQTT 연동 규격

ESP32-CAM이 세탁기 표시창(7세그먼트 숫자)을 읽어 MQTT 브로커로 보내는 규격이다.
기기 쪽 코드는 이 문서를 따른다. 규격을 바꿔야 하면 먼저 이 문서를 고친다.

```
ESP32 ─8883(TLS)─▶ Mosquitto ─1883─▶ 백엔드 (dev/washon/+/ocr 구독)
  → 상태 머신 → SSE GET /rooms/{roomId}/stream → 웹앱
```

웹앱(브라우저)은 브로커에 직접 접속하지 않는다. 백엔드가 `ocr` 메시지로 상태를 정하고 SSE로 내보낸다.
그래서 웹앱용 MQTT 계정이나 WebSocket(wss) 접속은 없다.

## 브로커 접속 정보

| 항목 | 값 |
|---|---|
| 브로커 주소 | `mqtt.washon.site` |
| 접속 | MQTT over TLS, 포트 `8883` (`mqtts://mqtt.washon.site:8883`) |
| 서버 인증서 | Let's Encrypt. ESP32에는 루트 인증서 **ISRG Root X1**을 넣는다 |
| 계정 | 기기마다 따로. **아이디 = 기기 ID**, 비밀번호는 VM에서 `edge/mqtt-user.sh <기기ID>`로 발급 |
| Client ID | 기기 ID 권장 (같은 ID로 두 대가 붙으면 서로 끊는다) |
| Keep alive | 30초 권장. 연결이 끊긴 뒤 keep alive × 1.5 안에 브로커가 LWT를 보낸다 |

- 토픽 prefix(`{prefix}`)는 개발 서버 기준 `dev/washon`이다.
- 브로커 ACL은 `pattern write dev/washon/%u/#`이다. 기기는 **자기 기기 ID 아래 토픽에만** 발행할 수 있고, 다른 토픽에 보낸 메시지는 브로커가 버린다.
- 비밀번호는 기기마다 다르다. 펌웨어 소스에 넣지 말고 설정 파일이나 NVS로 따로 넣는다.

## 기기 ID

- 형식: `wm-` + 식별자 (예: `wm-a1b2c3`). 테스트 기기는 **`wm-test`**.
- 같은 값이 세 군데에서 같아야 한다.
  - MQTT 아이디 (ACL의 `%u`)
  - 토픽의 `{deviceId}`
  - 백엔드 DB `cameras.device_id`. 등록되지 않은 기기의 메시지는 백엔드가 버리고 로그에 `Rejected MQTT OCR frame from wm-xxx: 401 Unknown device_id`를 남긴다.

## 토픽

| 토픽 | 내용 | retained | 보내는 시점 | 백엔드가 읽음 |
|---|---|---|---|---|
| `dev/washon/{deviceId}/ocr` | 읽은 값 JSON (아래) | 아니오 | 값이 바뀔 때 + 표시를 읽을 수 있는 동안 10초마다 | **예** |
| `dev/washon/{deviceId}/status` | 문자열 `"online"` 또는 `"offline"` | 예 | 접속 시 `online`. 연결이 끊기면 브로커가 LWT로 `offline` | 아니오 |
| `dev/washon/{deviceId}/state` | 현재 상태 JSON | 예 | 확정된 값이 바뀔 때마다 + 변화가 없어도 60초마다 | 아니오 |
| `dev/washon/{deviceId}/event` | 이벤트 JSON | 아니오 | 세탁 완료처럼 한 번만 알려야 하는 일이 생겼을 때 | 아니오 |

- 화면에 반영되는 것은 `ocr`뿐이다. `status`/`state`/`event`는 기기 디버깅과 이후 기능용이다.
- 기기는 `dev/washon/{deviceId}/cmd`를 구독할 수 있다 (지금은 쓰지 않음).
- QoS: `ocr`은 QoS 1 권장(백엔드가 재시작하는 동안 온 메시지를 브로커가 보관한다). 나머지는 QoS 0이어도 된다.

### ocr

```json
{"device_id":"wm-test","captured_at":"2026-11-08T05:00:00Z","confidence":0.95,"raw_text":"3:10","frame_hash":"3f9a1c20-42"}
```

| 필드 | 필수 | 내용 |
|---|---|---|
| `raw_text` | 예 | **남은 시간을 `"M:SS"`로** (표시값 `0310` → `"3:10"`, 끝나서 `0000` → `"0:00"`). 숫자를 제대로 못 읽으면 `""` (카메라 오류) |
| `frame_hash` | 예 | 메시지마다 다른 값. 백엔드는 같은 기기에서 같은 값이 다시 오면 중복으로 버린다 |
| `captured_at` | 아니오 | 읽은 시각, ISO-8601 UTC. 기기 시계가 맞지 않으면(`ts` = 0) 빼면 된다. 그러면 백엔드가 받은 시각을 쓴다 |
| `confidence` | 아니오 | 읽기 신뢰도 0–1. 0.5 미만이면 백엔드가 무시한다 |
| `device_id` | 아니오 | 넣으면 토픽의 기기 ID와 같아야 한다 (다르면 버림) |

규칙:

- **표시창은 MM:SS 네 자리다.** 끝의 두 자리가 초, 앞의 두 자리가 분이다 (`0310` = 3분 10초).
- **네 자리를 모두 읽었을 때만 정상 값이다.** 숫자를 못 읽음(`?`), 일부만 읽음(`9`, `1?`), 표시 없음은 모두 **카메라 오류**다. 이 상태가 5초 넘게 이어지면 `phase`를 `unknown`으로 바꾸고 `raw_text` `""`를 보낸다. 빈 값은 "세탁 끝"이 아니다.
- **`raw_text`에 표시창 숫자(`digits`)를 그대로 넣지 않는다.** 백엔드는 콜론 없는 숫자를 분으로 읽으므로 `"0310"`은 310분이 된다. 반드시 콜론을 넣은 `"3:10"`으로 보낸다.
- 세탁이 끝나 `0000`이 보이면 `"0:00"`을 보낸다. 백엔드는 이것만 "끝남 → 사용 가능"으로 본다.
- 아무것도 오지 않아도 60초 뒤 백엔드가 그 기기를 `unknown`으로 바꾼다.
- `frame_hash`는 재부팅해도 겹치지 않게 **부팅마다 새로 만든 난수 + `seq`**로 만든다 (예: `esp_random()` 8자리 hex + `-` + seq). `seq`만 쓰면 재부팅 뒤 0부터 다시 시작해서 앞 메시지와 겹쳐 버려진다.
- 전송 간격: **인식한 값이 바뀔 때마다 바로** 보내고(초 표시면 1초마다), 값이 그대로여도(카메라 오류 `""` 포함) 최소 10초마다 보낸다. 웹 화면은 받은 값을 그대로 보여 줄 뿐 스스로 줄이지 않는다. 백엔드의 기기별 제한은 **1초에 1건(순간 5건)**이고, 넘으면 버린다.

백엔드가 `raw_text`를 해석하는 방식:

| `raw_text` | 백엔드 판단 |
|---|---|
| `"3:10"` | 남은 3분 10초 (`remainingSec` 190, `remainingMin` 4). 3건 연속으로 늘지 않고 전체적으로 줄면 `in_use` |
| `"87"` | 남은 87분 (초 없음, 분만 보이는 표시용) |
| `"0:00"`, `"00"`, `"0"` | 바로 `available` (끝났거나 비어 있음) |
| `""` | 바로 `unknown` (카메라가 표시창을 읽지 못함, 화면에 "카메라 오류") |
| `"E01"`, `"F21"` | `error` (표시창이 에러 코드를 보일 때 그대로 넣어도 된다) |
| 그 밖 | 무시 |

`phase`와 백엔드 상태의 관계 (참고용):

| `phase` | 보내는 `raw_text` | 백엔드 상태 |
|---|---|---|
| `running` | `"M:SS"` | `in_use` |
| `finished` | `"0:00"` | `available` |
| `unknown` | `""` | `unknown` (카메라 오류) |

### status

- 접속할 때 LWT를 `dev/washon/{deviceId}/status` = `"offline"`(retained)으로 설정한다.
- 접속 직후 같은 토픽에 `"online"`(retained)을 발행한다.

### state

```json
{"device":"wm-test","phase":"running","digits":"0310","remaining_min":4,"remaining_sec":190,"display_on":true,"ts":1762578000,"seq":42}
```

- `phase`
  - `"running"`: 남은 시간을 표시하며 세탁 중
  - `"finished"`: 표시가 `0000`이 됨. 다음 세탁이 시작될 때까지 유지
  - `"idle"`: 켜진 뒤 아직 네 자리를 한 번도 읽지 못함
  - `"unknown"`: 네 자리를 5초 넘게 읽지 못함 (못 읽음, 일부만 읽음, 표시 없음) = 카메라 오류
- `digits`: 마지막으로 확정한 네 자리 숫자(콜론 없음).
- `remaining_sec`: `digits`를 MM:SS로 해석한 남은 시간(초). 끝의 두 자리가 초 (`0310` → 190). 해석할 수 없으면(초 자리가 60 이상 등) `null`.
- `remaining_min`: `remaining_sec`을 분으로 올림한 값 (190초 → 4). `remaining_sec`이 `null`이면 `null`.
- `display_on`: 표시창이 켜져 있으면 `true`.
- `ts`: 기기 기준 유닉스 시간(초). 기기 시계가 아직 맞지 않았으면 0일 수 있다.
- `seq`: 기기가 켜진 뒤 1씩 늘어나는 번호. 재부팅하면 0부터 다시 시작한다.

### event

```json
{"device":"wm-test","type":"finished","ts":1762581000}
```

- `type`: 지금은 `"finished"`만 쓴다. 받는 쪽은 모르는 `type`을 무시한다.

## 기기 없이 확인하기

`tools/camera-simulator`의 `esp32` 시나리오가 위 규격대로 기기를 흉내 낸다.

- 접속할 때 LWT(`status` = `"offline"`, retained)를 설정하고 `"online"`을 발행한다.
- `digits` `"0130"`(1분 30초)부터 `"0000"`까지 실제 표시처럼 1초마다 `state`와 `ocr`(`"1:30"`, `"1:29"`, …)을 발행한다 (`phase` `"running"`).
  - 간격은 `--interval <초>`로 바꿀 수 있다 (그만큼 숫자도 건너뛴다). 백엔드 제한 때문에 1초보다 짧으면 `ocr` 일부가 버려진다.
- `"0000"`(`ocr` `"0:00"`) 다음에는 `phase` `"finished"`인 `state`와 `finished` 이벤트를 한 번 발행한다.
- 그 뒤에는 기기처럼 접속을 유지하며 `ocr`(`"0:00"`)을 10초마다, `state`를 60초마다 보낸다. `Ctrl+C`로 끄면 브로커가 LWT로 `offline`을 발행한다.

```bash
npm --prefix tools/camera-simulator install
MQTT_PASSWORD=<wm-test 비밀번호> npm --prefix tools/camera-simulator run simulate -- esp32 \
  --mqtt-url mqtts://mqtt.washon.site:8883 --device-id wm-test
```

확인할 것:

- 학생 뷰에서 `wm-test` 카메라가 붙은 세탁기가 세 번째 `ocr`부터 사용 중이 되고, 남은 시간이 분·초로 줄어든다.
- 끝나면 사용 가능으로 바뀌고, 알림을 켜 둔 브라우저에 완료 알림이 한 번 뜬다.
- 시뮬레이터를 끄면 60초 뒤 `unknown`, 5분 뒤 관리자 화면에 카메라 다운 배지가 뜬다.
- 반영이 안 되면 `docker logs dev-app | grep -E "MQTT|Rejected"`로 확인한다.
