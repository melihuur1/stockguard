package com.stockguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.stockguard.support.Api;
import com.stockguard.support.Concurrent;
import com.stockguard.support.ConcurrencyReport;
import com.stockguard.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class ReservationConcurrencyIT extends IntegrationTest {

    @RepeatedTest(3)
    @DisplayName("Scenario 1: stock 1, 100 different users at once, exactly 1 succeeds")
    void oneUnitHundredUsers() {
        UUID product = newProduct(1);
        List<TestUser> users = newUsers(100);

        Concurrent.Run<Api.Response> run = Concurrent.run(100, i -> reserve(users.get(i), product, 1, "key-" + i));

        assertThat(run.peakInFlight()).as("requests must really overlap").isGreaterThanOrEqualTo(50);
        assertThat(statusCounts(run.results())).containsOnly(entry(201, 1L), entry(409, 99L));
        assertThat(run.results()).filteredOn(r -> r.status() == 409)
                .allSatisfy(r -> assertThat(r.code()).isEqualTo("INSUFFICIENT_STOCK"));
        assertThat(stock(product)).isEqualTo(new Stock(1, 0, 1));
        assertThat(reservationRows(product)).isEqualTo(1);
        assertConsistent(product);

        ConcurrencyReport.add("Scenario 1: stock 1, 100 users",
                "201=1, 409=99, stock=" + stock(product) + ", peak in-flight=" + run.peakInFlight() + ", " + run.durationMillis() + " ms");
    }

    @RepeatedTest(3)
    @DisplayName("Scenario 2: stock 10, 100 different users at once, exactly 10 succeed")
    void tenUnitsHundredUsers() {
        UUID product = newProduct(10);
        List<TestUser> users = newUsers(100);

        Concurrent.Run<Api.Response> run = Concurrent.run(100, i -> reserve(users.get(i), product, 1, "key-" + i));

        assertThat(run.peakInFlight()).isGreaterThanOrEqualTo(50);
        assertThat(statusCounts(run.results())).containsOnly(entry(201, 10L), entry(409, 90L));
        assertThat(stock(product)).isEqualTo(new Stock(10, 0, 10));
        assertThat(reservationRows(product)).isEqualTo(10);
        assertConsistent(product);

        ConcurrencyReport.add("Scenario 2: stock 10, 100 users",
                "201=10, 409=90, stock=" + stock(product) + ", peak in-flight=" + run.peakInFlight() + ", " + run.durationMillis() + " ms");
    }

    @Test
    @DisplayName("A request larger than what is left is rejected whole: stock is never split")
    void multiUnitRequestsNeverPartiallyFill() {
        UUID product = newProduct(10);
        List<TestUser> users = newUsers(20);

        Concurrent.Run<Api.Response> run = Concurrent.run(20, i -> reserve(users.get(i), product, 3, "key-" + i));

        // 10 units fit 3 requests of 3; the remaining unit cannot satisfy anyone.
        assertThat(statusCounts(run.results())).containsOnly(entry(201, 3L), entry(409, 17L));
        assertThat(stock(product)).isEqualTo(new Stock(10, 1, 9));
        assertConsistent(product);
    }

    @Test
    @DisplayName("Reserving, confirming, cancelling and restocking all at once keeps every invariant")
    void mixedWorkloadKeepsInvariants() {
        UUID product = newProduct(50);
        List<TestUser> users = newUsers(30);

        List<UUID> held = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            held.add(reserveOk(users.get(i), product, 1));
        }

        // Each index does a different thing, all competing for the same stock row.
        Concurrent.Run<Api.Response> run = Concurrent.run(150, i -> {
            TestUser user = users.get(i % users.size());
            return switch (i % 5) {
                case 0 -> reserve(user, product, 1 + ThreadLocalRandom.current().nextInt(3), "mixed-" + i);
                case 1 -> confirm(users.get(i / 5 % 30), held.get(i / 5 % 30));
                case 2 -> cancel(users.get(i / 5 % 30), held.get(i / 5 % 30));
                case 3 -> api.post("/api/v1/products/" + product + "/stock", admin().token(), Map.of("quantity", 1));
                default -> reserve(user, product, 1, "mixed-" + i);
            };
        });

        assertThat(run.results()).as("only business outcomes, no server errors")
                .allSatisfy(r -> assertThat(r.status()).isIn(200, 201, 409));
        Stock after = stock(product);
        assertThat(after.available()).isNotNegative();
        assertThat(after.total()).isEqualTo(after.available() + after.reserved());
        assertConsistent(product);

        ConcurrencyReport.add("Mixed reserve, confirm, cancel, restock (150 requests)",
                "statuses=" + statusCounts(run.results()) + ", stock=" + after + ", ledger consistent");
    }
}
