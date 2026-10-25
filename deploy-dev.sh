#!/usr/bin/env bash
# Dev server deploy: pull the latest main, rebuild the backend image and
# (re)start the docker-compose.dev.yml stack, then wait until it is healthy.
# Run on the VM from the repository root: ./deploy-dev.sh
# Setup and troubleshooting: docs/DEPLOY.md
set -euo pipefail

# Everything runs inside main(), and bash parses the whole function before
# running it, so `git pull` replacing this file mid-run is safe.
main() {
    cd "$(dirname "$0")"
    local compose=(docker compose -f docker-compose.dev.yml)

    if [[ ! -f .env ]]; then
        echo "✗ .env가 없습니다. cp .env.example .env 후 값을 채우세요." >&2
        exit 1
    fi
    local key
    for key in API_DOMAIN FRONTEND_ORIGIN DB_PASSWORD DB_ROOT_PASSWORD ADMIN_SESSION_SECRET \
        MQTT_BACKEND_PASSWORD MQTT_CAMERA_PASSWORD; do
        if [[ -z "$(env_value "$key")" ]]; then
            echo "✗ .env의 $key 값이 비어 있습니다." >&2
            exit 1
        fi
    done

    echo "▶ git pull"
    git pull --ff-only

    echo "▶ mosquitto 계정 파일 생성"
    write_mosquitto_passwd

    echo "▶ 빌드 및 시작"
    "${compose[@]}" up -d --build --remove-orphans
    # Already-running mosquitto re-reads passwd/acl on SIGHUP.
    "${compose[@]}" kill -s SIGHUP mosquitto > /dev/null

    echo "▶ 백엔드 헬스 체크 (최대 180초)"
    local i
    for i in $(seq 1 36); do
        if "${compose[@]}" exec -T caddy wget -qO- http://backend:8081/actuator/health 2>/dev/null \
            | grep -q '"status":"UP"'; then
            echo "✓ 배포 완료: https://$(env_value API_DOMAIN)/actuator/health"
            docker image prune -f > /dev/null
            exit 0
        fi
        sleep 5
    done

    echo "✗ 백엔드가 180초 안에 UP 상태가 되지 않았습니다. 최근 로그:" >&2
    "${compose[@]}" logs --tail 80 backend >&2
    exit 1
}

# Reads KEY=value from .env without sourcing it (values may contain $ etc.).
env_value() {
    grep -E "^$1=" .env | tail -n 1 | cut -d= -f2- || true
}

# mosquitto needs hashed passwords; regenerate the file from .env on every
# deploy so a changed password in .env takes effect.
write_mosquitto_passwd() {
    local dir="$PWD/deploy/dev/mosquitto"
    docker run --rm \
        -e BACKEND_PW="$(env_value MQTT_BACKEND_PASSWORD)" \
        -e CAMERA_PW="$(env_value MQTT_CAMERA_PASSWORD)" \
        -v "$dir:/work" \
        eclipse-mosquitto:2.0.20 sh -c '
            rm -f /work/passwd
            mosquitto_passwd -b -c /work/passwd washon-backend "$BACKEND_PW"
            mosquitto_passwd -b /work/passwd washon-camera "$CAMERA_PW"
            chown 1883:1883 /work/passwd
            chmod 0600 /work/passwd'
}

main "$@"
