-- Singleton-row advisory lock serializing every operation that can ADD scheduling concurrency
-- (booking confirmation and slot create/update/delete). Callers take a PESSIMISTIC_WRITE lock on
-- the single row below before reading capacity/appointments, so the SchedulingGuard sweep-line
-- check sees a consistent snapshot and two confirmations can't both admit an unsafe booking.
CREATE TABLE tbl_scheduling_lock (
    id INT PRIMARY KEY
);

-- The only row that ever exists; SchedulingLockRepository.acquire() locks it by id = 1.
INSERT INTO tbl_scheduling_lock (id) VALUES (1);
