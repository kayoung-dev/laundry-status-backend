-- Seconds-precision remaining time for displays that show MM:SS ("3:10").
--
-- Nullable: minutes-only displays ("23", "W:22") have no seconds, and every
-- state but in_use has no remaining time at all. remaining_min stays the
-- seconds rounded up, so readers that only know minutes keep working.

ALTER TABLE machine_current_states
    ADD COLUMN remaining_sec INT NULL AFTER remaining_min;
