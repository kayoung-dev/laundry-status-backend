-- Drop the `done` state. The system only answers "is the machine running, and
-- how long is left": a cycle that ends goes straight to `available` (see
-- StateDecision), so there is no "finished, collect your laundry" state and
-- no done -> available grace period any more.
--
-- Existing `done` rows (current state, event history, student reports) are
-- rewritten to `available` first, because the JPA enum can no longer read
-- `done`; a cycle that had ended is exactly what `available` now means.

UPDATE machine_current_states
SET state = 'available', remaining_min = NULL, wash_stage = NULL
WHERE state = 'done';

UPDATE machine_state_events
SET state = 'available', remaining_min = NULL
WHERE state = 'done';

UPDATE student_reports
SET reported_state = 'available'
WHERE reported_state = 'done';

ALTER TABLE machine_current_states
    DROP CONSTRAINT ck_machine_current_states_state,
    ADD CONSTRAINT ck_machine_current_states_state
        CHECK (state IN ('available', 'in_use', 'maintenance', 'error', 'unknown'));

ALTER TABLE machine_state_events
    DROP CONSTRAINT ck_machine_state_events_state,
    ADD CONSTRAINT ck_machine_state_events_state
        CHECK (state IN ('available', 'in_use', 'maintenance', 'error', 'unknown'));

ALTER TABLE student_reports
    DROP CONSTRAINT ck_student_reports_reported_state,
    ADD CONSTRAINT ck_student_reports_reported_state
        CHECK (reported_state IN ('available', 'in_use', 'maintenance', 'error', 'unknown'));
