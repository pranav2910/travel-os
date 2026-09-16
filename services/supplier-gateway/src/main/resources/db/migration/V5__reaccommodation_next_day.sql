-- Sandbox airline (Slice 4, a Slice 3 carry-over fixture): a cancellation may leave nothing on the
-- same date for the disrupted passenger, so the reaccommodation fares move to the next date.
ALTER TABLE sandbox_reaccommodation ADD COLUMN next_day BOOLEAN NOT NULL DEFAULT FALSE;
