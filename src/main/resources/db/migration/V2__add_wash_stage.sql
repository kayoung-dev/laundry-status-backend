-- P2-002: wash-stage detection (세탁/헹굼/탈수).
--
-- Nullable by design: a machine that is not in_use, or whose OCR text
-- doesn't use the new stage-prefix convention (see OcrReading.STAGE_PATTERN
-- -- "W:22"/"R:8"/"S:3"), has no known stage. Follows V1's convention:
-- VARCHAR + CHECK instead of a native MariaDB ENUM.

ALTER TABLE machine_current_states
    ADD COLUMN wash_stage VARCHAR(16) NULL AFTER remaining_min,
    ADD CONSTRAINT ck_machine_current_states_wash_stage
        CHECK (wash_stage IS NULL OR wash_stage IN ('wash', 'rinse', 'spin'));
