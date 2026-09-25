-- WashOn initial schema (MariaDB 10.11).
--
-- Drafted from Hibernate's own DDL for the JPA entities (one-off
-- ddl-auto=create run against a scratch database), then hand-cleaned:
--   * enum-like columns are VARCHAR(32) + CHECK, never native MariaDB ENUM
--     (native ENUM changes can force a full table rebuild); the entities map
--     them with @Enumerated(STRING) + @JdbcTypeCode(VARCHAR);
--   * constraints/indexes get readable names instead of Hibernate hashes;
--   * DB-side defaults mirror the old Prisma defaults (the entities also set
--     them in Java), except ids: those are CUIDs generated in the app;
--   * machine_current_states.machine_id -> machines FK added (Hibernate did
--     not emit it for the shared-PK one-to-one);
--   * timestamps are timezone-naive DATETIME(3) holding UTC
--     (hibernate.jdbc.time_zone=UTC).
--
-- Flyway owns this schema; Hibernate runs with ddl-auto=validate. Any schema
-- change = entity change + a new V<n>__*.sql in the same commit.

CREATE TABLE tenants (
    id         VARCHAR(64)  NOT NULL,
    name       VARCHAR(255) NOT NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE sites (
    id         VARCHAR(64)  NOT NULL,
    tenant_id  VARCHAR(64)  NOT NULL,
    name       VARCHAR(255) NOT NULL,
    address    VARCHAR(255) NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT fk_sites_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE rooms (
    id         VARCHAR(64)  NOT NULL,
    site_id    VARCHAR(64)  NOT NULL,
    name       VARCHAR(255) NOT NULL,
    building   VARCHAR(255) NULL,
    floor      VARCHAR(255) NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT fk_rooms_site FOREIGN KEY (site_id) REFERENCES sites (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE machines (
    id         VARCHAR(64)  NOT NULL,
    room_id    VARCHAR(64)  NOT NULL,
    type       VARCHAR(32)  NOT NULL,
    label      VARCHAR(255) NOT NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT fk_machines_room FOREIGN KEY (room_id) REFERENCES rooms (id) ON DELETE CASCADE,
    CONSTRAINT ck_machines_type CHECK (type IN ('washer', 'dryer'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 1:1 with machines. status = camera connectivity (online|offline), distinct
-- from the machine's operational state.
CREATE TABLE cameras (
    id           VARCHAR(64)  NOT NULL,
    machine_id   VARCHAR(64)  NOT NULL,
    device_id    VARCHAR(255) NOT NULL,
    api_key_hash VARCHAR(255) NOT NULL,
    status       VARCHAR(32)  NOT NULL DEFAULT 'offline',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_cameras_machine_id UNIQUE (machine_id),
    CONSTRAINT uk_cameras_device_id UNIQUE (device_id),
    CONSTRAINT fk_cameras_machine FOREIGN KEY (machine_id) REFERENCES machines (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Append-only event log (never updated). The (machine_id, frame_hash) unique
-- key is the ingest idempotency guard: a duplicate frame for the same machine
-- fails with ER_DUP_ENTRY (1062 / SQLState 23000). NULL frame_hash values
-- never collide, so frames without a hash are not de-duplicated.
CREATE TABLE machine_state_events (
    id            VARCHAR(64)  NOT NULL,
    machine_id    VARCHAR(64)  NOT NULL,
    source        VARCHAR(32)  NOT NULL,
    state         VARCHAR(32)  NOT NULL,
    remaining_min INT          NULL,
    error_code    VARCHAR(255) NULL,
    confidence    DOUBLE       NULL,
    raw_text      TEXT         NULL,
    frame_hash    VARCHAR(255) NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version       INT          NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT uk_machine_state_events_machine_frame_hash UNIQUE (machine_id, frame_hash),
    CONSTRAINT fk_machine_state_events_machine FOREIGN KEY (machine_id) REFERENCES machines (id) ON DELETE CASCADE,
    CONSTRAINT ck_machine_state_events_source CHECK (source IN ('ocr', 'admin_override', 'system')),
    CONSTRAINT ck_machine_state_events_state
        CHECK (state IN ('available', 'in_use', 'done', 'maintenance', 'error', 'unknown')),
    INDEX idx_machine_state_events_machine_created (machine_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Materialized current state, one row per machine. version is bumped
-- manually by the state machine (UPDATE ... WHERE version = :old), not by
-- JPA @Version.
CREATE TABLE machine_current_states (
    machine_id      VARCHAR(64)  NOT NULL,
    state           VARCHAR(32)  NOT NULL DEFAULT 'unknown',
    remaining_min   INT          NULL,
    error_code      VARCHAR(255) NULL,
    override_active BOOLEAN      NOT NULL DEFAULT FALSE,
    override_by     VARCHAR(255) NULL,
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version         INT          NOT NULL DEFAULT 1,
    last_frame_at   DATETIME(3)  NULL,
    stale_since     DATETIME(3)  NULL,
    PRIMARY KEY (machine_id),
    CONSTRAINT fk_machine_current_states_machine FOREIGN KEY (machine_id) REFERENCES machines (id) ON DELETE CASCADE,
    CONSTRAINT ck_machine_current_states_state
        CHECK (state IN ('available', 'in_use', 'done', 'maintenance', 'error', 'unknown'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE student_reports (
    id             VARCHAR(64)  NOT NULL,
    machine_id     VARCHAR(64)  NOT NULL,
    reported_state VARCHAR(32)  NOT NULL,
    device_id      VARCHAR(255) NOT NULL,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT fk_student_reports_machine FOREIGN KEY (machine_id) REFERENCES machines (id) ON DELETE CASCADE,
    CONSTRAINT ck_student_reports_reported_state
        CHECK (reported_state IN ('available', 'in_use', 'done', 'maintenance', 'error', 'unknown')),
    INDEX idx_student_reports_machine_created (machine_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE admin_users (
    id            VARCHAR(64)  NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(32)  NOT NULL DEFAULT 'site_admin',
    site_scope_id VARCHAR(64)  NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_admin_users_email UNIQUE (email),
    CONSTRAINT fk_admin_users_site_scope FOREIGN KEY (site_scope_id) REFERENCES sites (id) ON DELETE SET NULL,
    CONSTRAINT ck_admin_users_role CHECK (role IN ('super_admin', 'site_admin', 'technician', 'read_only'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
