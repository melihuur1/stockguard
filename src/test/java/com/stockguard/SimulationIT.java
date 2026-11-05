package com.stockguard;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockguard.support.Api;
import com.stockguard.support.ConcurrencyReport;
import com.stockguard.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class SimulationIT extends IntegrationTest {

    private JsonNode simulate(int stock, int requests, int quantity) {
        Api.Response response = api.post("/api/v1/simulations/reservations", admin().token(),
                Map.of("initialStock", stock, "concurrentRequests", requests, "quantityPerRequest", quantity));
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        return response.json();
    }

    @Test
    @DisplayName("Stock 1, 100 requests: the report shows 1 success and 99 rejections and they match the database")
    void oneUnit() {
        JsonNode report = simulate(1, 100, 1);

        assertThat(report.get("successfulReservations").asInt()).isEqualTo(1);
        assertThat(report.get("rejectedReservations").asInt()).isEqualTo(99);
        assertThat(report.get("unexpectedFailures").asInt()).isZero();
        assertThat(report.get("oversoldUnits").asInt()).isZero();
        assertThat(report.get("negativeStockCounters").asInt()).isZero();
        assertThat(report.get("dataConsistent").asBoolean()).isTrue();
        assertThat(report.get("consistency").get("consistent").asBoolean()).isTrue();
        assertMatchesDatabase(report);

        ConcurrencyReport.add("Simulation endpoint: stock 1, 100 requests",
                "success=" + report.get("successfulReservations") + ", rejected=" + report.get("rejectedReservations")
                        + ", consistent=" + report.get("dataConsistent") + ", " + report.get("durationMillis") + " ms");
    }

    @Test
    @DisplayName("The report changes with the input, so it is computed and not a fixed answer")
    void differentInputsGiveDifferentReports() {
        JsonNode tenUnits = simulate(10, 100, 1);
        assertThat(tenUnits.get("successfulReservations").asInt()).isEqualTo(10);
        assertThat(tenUnits.get("rejectedReservations").asInt()).isEqualTo(90);
        assertThat(tenUnits.get("availableQuantity").asInt()).isZero();
        assertThat(tenUnits.get("reservedQuantity").asInt()).isEqualTo(10);
        assertMatchesDatabase(tenUnits);

        JsonNode multiUnit = simulate(7, 20, 3);
        assertThat(multiUnit.get("successfulReservations").asInt()).isEqualTo(2);
        assertThat(multiUnit.get("rejectedReservations").asInt()).isEqualTo(18);
        assertThat(multiUnit.get("availableQuantity").asInt()).isEqualTo(1);
        assertThat(multiUnit.get("reservedQuantity").asInt()).isEqualTo(6);
        assertMatchesDatabase(multiUnit);

        JsonNode plenty = simulate(50, 20, 1);
        assertThat(plenty.get("successfulReservations").asInt()).isEqualTo(20);
        assertThat(plenty.get("rejectedReservations").asInt()).isZero();
        assertThat(plenty.get("availableQuantity").asInt()).isEqualTo(30);
        assertMatchesDatabase(plenty);

        JsonNode noStock = simulate(0, 10, 1);
        assertThat(noStock.get("successfulReservations").asInt()).isZero();
        assertThat(noStock.get("rejectedReservations").asInt()).isEqualTo(10);
        assertMatchesDatabase(noStock);
    }

    @Test
    void runsAreIsolatedAndAudited() {
        JsonNode first = simulate(1, 10, 1);
        JsonNode second = simulate(1, 10, 1);
        assertThat(first.get("productId").asString()).isNotEqualTo(second.get("productId").asString());

        JsonNode audit = api.get("/api/v1/audit-events?entityId=" + first.get("productId").asString(), admin().token()).json().get("items");

        assertThat(audit.get(0).get("type").asString()).isEqualTo("SIMULATION_EXECUTED");
        assertThat(audit.get(0).get("details").get("successfulReservations").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Simulated customers can never log in")
    void simulatedUsersCannotAuthenticate() {
        simulate(1, 1, 1);

        Api.Response login = api.post("/api/v1/auth/login", null, Map.of("username", "__sim-0001", "password", "!"));

        assertThat(login.status()).isEqualTo(401);
    }

    @Test
    void requestsAreBounded() {
        Api.Response tooMany = api.post("/api/v1/simulations/reservations", admin().token(),
                Map.of("initialStock", 1, "concurrentRequests", 100_000, "quantityPerRequest", 1));

        assertThat(tooMany.status()).isEqualTo(400);
    }

    private void assertMatchesDatabase(JsonNode report) {
        UUID product = UUID.fromString(report.get("productId").asString());
        Stock stock = stock(product);
        assertThat(report.get("totalQuantity").asInt()).isEqualTo(stock.total());
        assertThat(report.get("availableQuantity").asInt()).isEqualTo(stock.available());
        assertThat(report.get("reservedQuantity").asInt()).isEqualTo(stock.reserved());
        assertThat(report.get("reservationRowsInDatabase").asLong()).isEqualTo(reservationRows(product));
        assertConsistent(product);
    }
}
