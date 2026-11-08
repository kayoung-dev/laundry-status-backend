-- Student accounts (sign-up from the /signup screen), separate from
-- admin_users.
--
-- default_room_id is the laundry room the student picked at sign-up; it is
-- cleared, not cascaded, when the room goes away. floor / room_number are
-- optional free digits from the form.

CREATE TABLE users (
    id              VARCHAR(64)  NOT NULL,
    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    name            VARCHAR(100) NOT NULL,
    hall            VARCHAR(100) NOT NULL,
    floor           VARCHAR(16)  NULL,
    room_number     VARCHAR(16)  NULL,
    default_room_id VARCHAR(64)  NULL,
    push_agreed     BOOLEAN      NOT NULL DEFAULT FALSE,
    terms_agreed_at DATETIME(3)  NOT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT fk_users_default_room FOREIGN KEY (default_room_id) REFERENCES rooms (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- The latest emailed sign-up code per address (sending a new one replaces
-- it). Only a scrypt hash of the code is stored; the row is deleted once the
-- account is created or too many wrong codes were tried.
CREATE TABLE signup_codes (
    email           VARCHAR(255) NOT NULL,
    code_hash       VARCHAR(255) NOT NULL,
    expires_at      DATETIME(3)  NOT NULL,
    failed_attempts INT          NOT NULL DEFAULT 0,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
