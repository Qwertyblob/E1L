package com.qwertyblob.every1luvs.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the V10 recompute repairs an overlap-inflated {@code booked_count} down to its one
 * authoritative meaning: the count of active (non-archived) BOOKED bookings that pick the slot
 * directly. The real Flyway migrations (incl. V10) build the schema; this seeds a slot whose counter
 * is deliberately wrong plus a representative mix of bookings, re-runs the exact V10 statement, and
 * asserts the correction. H2 can't stand in — this must be real Postgres.
 */
@SpringBootTest(properties = "app.auth.token-secret=integration-test-secret-that-is-32-chars")
@Testcontainers
class BookedCountRecomputeMigrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    // The exact statement shipped in V10__recompute_booked_count.sql.
    private static final String V10_RECOMPUTE = """
            UPDATE tbl_slots s SET booked_count = (
                SELECT COUNT(*) FROM tbl_bookings b
                WHERE b.slot_id = s.id
                  AND b.status = 'BOOKED'
                  AND b.archived_at IS NULL
            )""";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v10Recompute_correctsOverlapInflatedBookedCount() {
        // A slot whose booked_count (9) was inflated by the retired overlap-lock cache.
        jdbc.update("INSERT INTO tbl_slots (id, title, start_time, end_time, capacity, booked_count, version) "
                + "VALUES (9001, 'seed', now(), now() + interval '2 hours', 5, 9, 0)");
        // Two active BOOKED direct bookings (should count), one CANCELLED and one archived BOOKED
        // (should NOT count).
        jdbc.update("INSERT INTO tbl_bookings (slot_id, status) VALUES (9001, 'BOOKED')");
        jdbc.update("INSERT INTO tbl_bookings (slot_id, status) VALUES (9001, 'BOOKED')");
        jdbc.update("INSERT INTO tbl_bookings (slot_id, status) VALUES (9001, 'CANCELLED')");
        jdbc.update("INSERT INTO tbl_bookings (slot_id, status, archived_at) VALUES (9001, 'BOOKED', now())");

        jdbc.update(V10_RECOMPUTE);

        Integer bookedCount = jdbc.queryForObject(
                "SELECT booked_count FROM tbl_slots WHERE id = 9001", Integer.class);
        assertThat(bookedCount).as("only the 2 active BOOKED direct bookings count").isEqualTo(2);
    }
}
