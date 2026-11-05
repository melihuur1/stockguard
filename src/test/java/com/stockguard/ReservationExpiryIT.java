package com.stockguard;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockguard.reservation.ReservationExpiryService;
import com.stockguard.support.Api;
import com.stockguard.support.Concurrent;
import com.stockguard.support.ConcurrencyReport;
import com.stockguard.support.IntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReservationExpiryIT extends IntegrationTest {

    @Autowired
    private ReservationExpiryService expiry;

    @BeforeEach
    void drainLeftovers() {
        sweepUntilEmpty();
    }

    private int sweepUntilEmpty() {
        int total = 0;
        int expired;
        do {
            expired = expiry.expireDueBatch(100);
            total += expired;
        } while (expired > 0);
        return total;
    }

    @Test
    @DisplayName("An overdue reservation is released by the sweep, exactly once")
    void sweepReleasesStock() {
        UUID product = newProduct(5);
        UUID reservation = reserveOk(newUser(), product, 2);
        makeOverdue(reservation);
        assertThat(stock(product)).isEqualTo(new Stock(5, 3, 2));

        assertThat(sweepUntilEmpty()).isEqualTo(1);

        assertThat(statusOf(reservation)).isEqualTo("EXPIRED");
        assertThat(stock(product)).isEqualTo(new Stock(5, 5, 0));
        assertConsistent(product);
        assertThat(sweepUntilEmpty()).as("a second sweep finds nothing").isZero();
        assertThat(stock(product)).isEqualTo(new Stock(5, 5, 0));
    }

    @Test
    @DisplayName("Past its deadline a reservation can no longer be confirmed or cancelled, even before the sweep")
    void deadlineIsEnforcedBeforeTheSweepRuns() {
        UUID product = newProduct(5);
        TestUser user = newUser();
        UUID reservation = reserveOk(user, product, 1);
        makeOverdue(reservation);

        Api.Response confirm = confirm(user, reservation);
        Api.Response cancel = cancel(user, reservation);

        assertThat(confirm.status()).isEqualTo(409);
        assertThat(confirm.code()).isEqualTo("RESERVATION_EXPIRED");
        assertThat(cancel.status()).isEqualTo(409);
        assertThat(cancel.code()).isEqualTo("RESERVATION_EXPIRED");
        assertThat(stock(product)).as("still held until the sweep").isEqualTo(new Stock(5, 4, 1));

        sweepUntilEmpty();

        assertThat(statusOf(reservation)).isEqualTo("EXPIRED");
        assertThat(stock(product)).isEqualTo(new Stock(5, 5, 0));
        assertThat(confirm(user, reservation).code()).isEqualTo("RESERVATION_EXPIRED");
        assertConsistent(product);
    }

    @Test
    @DisplayName("A reservation that has not reached its deadline is left alone by the sweep")
    void sweepIgnoresReservationsInTime() {
        UUID product = newProduct(5);
        UUID reservation = reserveOk(newUser(), product, 1);

        assertThat(sweepUntilEmpty()).isZero();

        assertThat(statusOf(reservation)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("Scenario 6: confirm racing the sweep at the deadline has exactly one winner")
    void confirmRacingExpiryAtTheDeadline() {
        int rounds = 60;
        UUID product = newProduct(rounds);
        TestUser user = newUser();
        int confirmWins = 0;
        int expiryWins = 0;

        for (int round = 0; round < rounds; round++) {
            UUID reservation = reserveOk(user, product, 1);
            // The deadline is 40 ms away on the database clock. Confirm fires 15 to 65 ms from now, spreading
            // its arrival on both sides of the deadline, while a sweeper runs continuously across the same window.
            jdbc.sql("UPDATE reservations SET expires_at = clock_timestamp() + interval '40 milliseconds' WHERE id = :id")
                    .param("id", reservation)
                    .update();
            int confirmDelay = 15 + ThreadLocalRandom.current().nextInt(50);

            Api.Response confirmResult = Concurrent.run(2, i -> {
                if (i == 0) {
                    Thread.sleep(confirmDelay);
                    return confirm(user, reservation);
                }
                long end = System.nanoTime() + 110_000_000L;
                while (System.nanoTime() < end) {
                    expiry.expireDueBatch(100);
                    Thread.sleep(1);
                }
                return null;
            }).results().get(0);

            sweepUntilEmpty();
            String finalStatus = statusOf(reservation);

            if (confirmResult.status() == 200) {
                assertThat(finalStatus).isEqualTo("CONFIRMED");
                confirmWins++;
            } else {
                assertThat(confirmResult.status()).isEqualTo(409);
                assertThat(confirmResult.code()).isEqualTo("RESERVATION_EXPIRED");
                assertThat(finalStatus).isEqualTo("EXPIRED");
                expiryWins++;
            }
        }

        assertThat(confirmWins + expiryWins).isEqualTo(rounds);
        assertThat(confirmWins).as("the race must be real: confirm has to win sometimes").isPositive();
        assertThat(expiryWins).as("the race must be real: expiry has to win sometimes").isPositive();
        assertThat(stock(product)).isEqualTo(new Stock(rounds - confirmWins, rounds - confirmWins, 0));
        assertConsistent(product);

        ConcurrencyReport.add("Scenario 6: confirm racing expiry, " + rounds + " rounds",
                "confirm won " + confirmWins + ", expiry won " + expiryWins + ", double settlements=0, stock=" + stock(product));
    }

    @Test
    @DisplayName("The sweep passes over a row held by an in-flight transaction instead of waiting for it")
    void sweepSkipsLockedRows() throws Exception {
        UUID product = newProduct(5);
        UUID reservation = reserveOk(newUser(), product, 1);
        makeOverdue(reservation);

        try (Connection inFlight = dataSource.getConnection()) {
            inFlight.setAutoCommit(false);
            try (PreparedStatement lock = inFlight.prepareStatement("SELECT 1 FROM reservations WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, reservation);
                lock.executeQuery();
            }

            long startedAt = System.nanoTime();
            int expired = expiry.expireDueBatch(100);
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(expired).isZero();
            assertThat(elapsedMillis).as("must not wait for the row lock").isLessThan(1_000);
            inFlight.rollback();
        }

        assertThat(sweepUntilEmpty()).isEqualTo(1);
        assertThat(stock(product)).isEqualTo(new Stock(5, 5, 0));
    }

    @Test
    @DisplayName("8 parallel sweepers over 300 reservations on 5 products: each released once and no deadlock")
    void parallelSweepersReleaseEachReservationOnce() {
        List<UUID> products = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            products.add(newProduct(60));
        }
        TestUser user = newUser();
        List<UUID> reservations = Concurrent.run(300, i -> reserveOk(user, products.get(i % 5), 1)).results();
        for (UUID reservation : reservations) {
            makeOverdue(reservation);
        }

        // Small batches spanning all products maximise the chance of a lock-order deadlock between sweepers.
        // Concurrent.run fails the test if a sweeper throws, which is what a deadlock would cause.
        Concurrent.Run<Integer> run = Concurrent.run(8, i -> {
            int total = 0;
            int expired;
            do {
                expired = expiry.expireDueBatch(25);
                total += expired;
            } while (expired > 0);
            return total;
        });

        assertThat(run.results().stream().mapToInt(Integer::intValue).sum()).isEqualTo(300);
        for (UUID product : products) {
            assertThat(stock(product)).isEqualTo(new Stock(60, 60, 0));
            assertConsistent(product);
        }

        ConcurrencyReport.add("8 parallel sweepers, 300 overdue reservations, 5 products",
                "released per sweeper=" + run.results() + ", total=300, deadlocks=0, stock restored");
    }

    @Test
    @DisplayName("TTL from the request is honoured")
    void requestedTtlSetsDeadline() {
        UUID product = newProduct(1);
        Api.Response created = api.post("/api/v1/reservations", newUser().token(),
                Map.of("productId", product, "quantity", 1, "ttlSeconds", 120), Map.of("Idempotency-Key", "ttl"));

        long seconds = java.time.Duration.between(
                java.time.Instant.parse(created.text("createdAt")), java.time.Instant.parse(created.text("expiresAt"))).toSeconds();

        assertThat(seconds).isEqualTo(120);
    }
}
