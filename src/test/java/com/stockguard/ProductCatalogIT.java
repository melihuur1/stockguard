package com.stockguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.stockguard.support.Api;
import com.stockguard.support.Concurrent;
import com.stockguard.support.ConcurrencyReport;
import com.stockguard.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ProductCatalogIT extends IntegrationTest {

    @Test
    void createReadUpdateAndList() {
        String sku = "SKU-" + UUID.randomUUID();
        Api.Response created = api.post("/api/v1/products", admin().token(), Map.of("sku", sku, "name", "Original", "description", "First"));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.header("Location")).isEqualTo("/api/v1/products/" + created.text("id"));
        assertThat(created.number("version")).isZero();
        assertThat(created.number("totalQuantity")).isZero();

        String id = created.text("id");
        String reader = newUser().token();
        assertThat(api.get("/api/v1/products/" + id, reader).text("name")).isEqualTo("Original");

        Api.Response updated = api.put("/api/v1/products/" + id, admin().token(), Map.of("name", "Renamed", "description", "Second", "version", 0));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.text("name")).isEqualTo("Renamed");
        assertThat(updated.number("version")).isEqualTo(1);
        assertThat(updated.text("sku")).as("the SKU is immutable").isEqualTo(sku);

        JsonNode page = api.get("/api/v1/products?size=100", reader).json();
        assertThat(page.get("size").asInt()).isEqualTo(100);
        assertThat(page.get("totalElements").asInt()).isPositive();
    }

    @Test
    void listingIncludesAllThreeStockQuantities() {
        UUID product = newProduct(5);
        reserveOk(newUser(), product, 2);

        Api.Response single = api.get("/api/v1/products/" + product, newUser().token());
        Api.Response stock = api.get("/api/v1/products/" + product + "/stock", newUser().token());

        assertThat(single.number("totalQuantity")).isEqualTo(5);
        assertThat(single.number("availableQuantity")).isEqualTo(3);
        assertThat(single.number("reservedQuantity")).isEqualTo(2);
        assertThat(stock.number("total")).isEqualTo(5);
        assertThat(stock.number("available")).isEqualTo(3);
        assertThat(stock.number("reserved")).isEqualTo(2);
    }

    @Test
    @DisplayName("Two administrators editing the same version: one succeeds and the other is told to reload")
    void concurrentEditsAreDetected() {
        for (int round = 0; round < 10; round++) {
            UUID product = newProduct(0);

            Concurrent.Run<Api.Response> run = Concurrent.run(2, i -> api.put("/api/v1/products/" + product, admin().token(),
                    Map.of("name", "Edit " + i, "version", 0)));

            assertThat(statusCounts(run.results())).containsOnly(entry(200, 1L), entry(409, 1L));
            assertThat(run.results()).filteredOn(r -> r.status() == 409)
                    .allSatisfy(r -> assertThat(r.code()).isEqualTo("STALE_VERSION"));
            assertThat(api.get("/api/v1/products/" + product, admin().token()).number("version")).isEqualTo(1);
        }
        ConcurrencyReport.add("Two concurrent product edits of one version, 10 rounds", "always 200=1, 409=1 (optimistic lock)");
    }

    @Test
    void editBasedOnOldVersionIsRejected() {
        UUID product = newProduct(0);
        api.put("/api/v1/products/" + product, admin().token(), Map.of("name", "First edit", "version", 0));

        Api.Response stale = api.put("/api/v1/products/" + product, admin().token(), Map.of("name", "Stale edit", "version", 0));

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("STALE_VERSION");
        assertThat(api.get("/api/v1/products/" + product, admin().token()).text("name")).isEqualTo("First edit");
    }

    @Test
    @DisplayName("Stock activity never conflicts with a catalogue edit, because they live on different rows")
    void stockChangesDoNotBumpProductVersion() {
        UUID product = newProduct(5);
        TestUser user = newUser();
        UUID reservation = reserveOk(user, product, 1);
        confirm(user, reservation);
        addStock(product, 3);

        Api.Response edit = api.put("/api/v1/products/" + product, admin().token(), Map.of("name", "Edit after stock activity", "version", 0));

        assertThat(edit.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("Concurrent creation of one SKU gives one product and clean conflicts")
    void duplicateSkuIsAConflict() {
        String sku = "DUP-" + UUID.randomUUID();

        Concurrent.Run<Api.Response> run = Concurrent.run(10, i -> api.post("/api/v1/products", admin().token(), Map.of("sku", sku, "name", "Racing")));

        assertThat(statusCounts(run.results())).containsOnly(entry(201, 1L), entry(409, 9L));
        assertThat(run.results()).filteredOn(r -> r.status() == 409)
                .allSatisfy(r -> assertThat(r.code()).isEqualTo("SKU_TAKEN"));
    }

    @Test
    void invalidProductsAreRejected() {
        String admin = admin().token();

        assertThat(api.post("/api/v1/products", admin, Map.of("sku", "", "name", "x")).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/products", admin, Map.of("sku", "has space", "name", "x")).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/products", admin, Map.of("sku", "OK-" + UUID.randomUUID(), "name", " ")).status()).isEqualTo(400);
        assertThat(api.put("/api/v1/products/" + UUID.randomUUID(), admin, Map.of("name", "x", "version", 0)).code()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(api.post("/api/v1/products/" + UUID.randomUUID() + "/stock", admin, Map.of("quantity", 1)).code()).isEqualTo("PRODUCT_NOT_FOUND");
    }
}
