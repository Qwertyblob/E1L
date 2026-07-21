package com.qwertyblob.every1luvs.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Singleton-row advisory lock that serializes every operation which can <em>add</em> scheduling
 * concurrency — booking confirmation and slot create/update/delete. A caller takes a
 * {@code PESSIMISTIC_WRITE} lock on the single seeded row (id = {@link #SINGLETON_ID}, inserted by
 * migration {@code V8}) before reading capacity/appointments, so {@code SchedulingGuard} evaluates
 * a consistent snapshot and two confirmations can't both admit an unsafe booking.
 *
 * <p>Operations that only <em>remove</em> concurrency (cancellation, completion, account deletion,
 * archival) deliberately do not take this lock: a stale guard can conservatively reject but can
 * never admit an unsafe booking.
 */
@Entity
@Table(name = "tbl_scheduling_lock")
public class SchedulingLock {

    /** The only row that ever exists in the table; seeded by {@code V8__scheduling_lock.sql}. */
    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
}
