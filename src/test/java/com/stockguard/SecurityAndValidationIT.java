package com.stockguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.stockguard.support.Api;
import com.stockguard.support.Concurrent;
import com.stockguard.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Scenario 9: unauthorised access and invalid requests are rejected")
class SecurityAndValidationIT extends IntegrationTest {

    private static final UUID ANY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Nested
    @DisplayName("Authentication")
    class Authentication {

        @ParameterizedTest
        @ValueSource(strings = {
            "/api/v1/products",
            "/api/v1/reservations",
            "/api/v1/audit-events",
            "/api/v1/admin/consistency",
            "/api/v1/products/00000000-0000-0000-0000-000000000001/stock",
            "/api/v1/does-not-exist"
        })
        void requestsWithoutTokenAreRejected(String path) {
            Api.Response response = api.get(path, null);

            assertThat(response.status()).isEqualTo(401);
            assertThat(response.code()).isEqualTo("UNAUTHENTICATED");
            assertThat(response.header("WWW-Authenticate")).isEqualTo("Bearer");
            assertThat(response.header("Content-Type")).contains("application/problem+json");
        }

        @Test
        void writesWithoutTokenAreRejected() {
            assertThat(api.post("/api/v1/reservations", null, Map.of("productId", ANY_ID, "quantity", 1)).status()).isEqualTo(401);
            assertThat(api.post("/api/v1/reservations/" + ANY_ID + "/confirm", null, null).status()).isEqualTo(401);
        }

        @Test
        void malformedTokenIsRejected() {
            assertThat(api.get("/api/v1/products", "not-a-jwt").status()).isEqualTo(401);
        }

        @Test
        void tamperedSignatureIsRejected() {
            String token = newUser().token();
            String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "AAAA" + token.substring(token.lastIndexOf('.') + 5);

            assertThat(api.get("/api/v1/products", tampered).status()).isEqualTo(401);
        }

        @Test
        @DisplayName("Changing the role claim of a real token invalidates its signature")
        void privilegeEscalationByEditingClaimsIsRejected() {
            String[] parts = newUser().token().split("\\.");
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8).replace("\"USER\"", "\"ADMIN\"");
            String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

            assertThat(api.get("/api/v1/audit-events", forged).status()).isEqualTo(401);
        }

        @Test
        void tokenSignedWithAnotherKeyIsRejected() throws Exception {
            String token = signedToken("a-completely-different-secret-of-sufficient-length", Instant.now().plusSeconds(600), "stockguard");

            assertThat(api.get("/api/v1/products", token).status()).isEqualTo(401);
        }

        @Test
        void expiredTokenIsRejected() throws Exception {
            String token = signedToken(JWT_SECRET, Instant.now().minusSeconds(600), "stockguard");

            assertThat(api.get("/api/v1/products", token).status()).isEqualTo(401);
        }

        @Test
        void tokenFromAnotherIssuerIsRejected() throws Exception {
            String token = signedToken(JWT_SECRET, Instant.now().plusSeconds(600), "someone-else");

            assertThat(api.get("/api/v1/products", token).status()).isEqualTo(401);
        }

        @Test
        @DisplayName("An unsigned token using alg=none is rejected")
        void unsignedTokenIsRejected() {
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
            String claims = encoder.encodeToString(("{\"sub\":\"" + ANY_ID + "\",\"role\":\"ADMIN\",\"iss\":\"stockguard\",\"exp\":9999999999}").getBytes(StandardCharsets.UTF_8));

            assertThat(api.get("/api/v1/audit-events", header + "." + claims + ".").status()).isEqualTo(401);
        }

        private String signedToken(String secret, Instant expiresAt, String issuer) throws Exception {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(newUser().id().toString())
                    .issuer(issuer)
                    .claim("role", "USER")
                    .expirationTime(Date.from(expiresAt))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        }
    }

    @Nested
    @DisplayName("Registration and login")
    class Accounts {

        private static final String GOOD_PASSWORD = "correct-horse-battery";

        @Test
        void registerAndLoginWorkEndToEnd() {
            String username = "user-" + UUID.randomUUID().toString().substring(0, 8);
            Api.Response registered = api.post("/api/v1/auth/register", null, Map.of("username", username, "password", GOOD_PASSWORD));
            assertThat(registered.status()).isEqualTo(201);
            assertThat(registered.rawBody()).doesNotContain(GOOD_PASSWORD, "password", "hash");

            Api.Response login = api.post("/api/v1/auth/login", null, Map.of("username", username.toUpperCase(), "password", GOOD_PASSWORD));

            assertThat(login.status()).isEqualTo(200);
            assertThat(api.get("/api/v1/products", login.text("accessToken")).status()).isEqualTo(200);
        }

        @Test
        @DisplayName("Self-registration cannot grant the admin role")
        void registrationCannotRequestAdminRole() {
            String username = "user-" + UUID.randomUUID().toString().substring(0, 8);
            api.post("/api/v1/auth/register", null, Map.of("username", username, "password", GOOD_PASSWORD, "role", "ADMIN"));
            String token = api.post("/api/v1/auth/login", null, Map.of("username", username, "password", GOOD_PASSWORD)).text("accessToken");

            assertThat(api.get("/api/v1/audit-events", token).status()).isEqualTo(403);
        }

        @Test
        @DisplayName("Passwords are stored as BCrypt hashes")
        void passwordIsHashed() {
            String username = "user-" + UUID.randomUUID().toString().substring(0, 8);
            api.post("/api/v1/auth/register", null, Map.of("username", username, "password", GOOD_PASSWORD));

            String stored = jdbc.sql("SELECT password_hash FROM app_users WHERE username = :u").param("u", username).query(String.class).single();

            assertThat(stored).startsWith("$2a$").doesNotContain(GOOD_PASSWORD);
        }

        @Test
        @DisplayName("Wrong password and unknown user are indistinguishable")
        void failedLoginsLookTheSame() {
            String username = "user-" + UUID.randomUUID().toString().substring(0, 8);
            api.post("/api/v1/auth/register", null, Map.of("username", username, "password", GOOD_PASSWORD));

            Api.Response wrongPassword = api.post("/api/v1/auth/login", null, Map.of("username", username, "password", "wrong-password-here"));
            Api.Response unknownUser = api.post("/api/v1/auth/login", null, Map.of("username", "nobody-" + username, "password", "wrong-password-here"));

            assertThat(wrongPassword.status()).isEqualTo(401);
            assertThat(unknownUser.status()).isEqualTo(401);
            assertThat(wrongPassword.code()).isEqualTo("INVALID_CREDENTIALS");
            assertThat(unknownUser.text("detail")).isEqualTo(wrongPassword.text("detail"));
        }

        @Test
        @DisplayName("Concurrent registrations of one username create one account and no server error")
        void concurrentRegistrationCreatesOneAccount() {
            String username = "user-" + UUID.randomUUID().toString().substring(0, 8);

            Concurrent.Run<Api.Response> run = Concurrent.run(10,
                    i -> api.post("/api/v1/auth/register", null, Map.of("username", username, "password", GOOD_PASSWORD)));

            assertThat(statusCounts(run.results())).containsOnly(entry(201, 1L), entry(409, 9L));
            assertThat(run.results()).filteredOn(r -> r.status() == 409)
                    .allSatisfy(r -> assertThat(r.code()).isEqualTo("USERNAME_TAKEN"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"__sim-0001", "has space", "x", "-starts-with-hyphen"})
        void invalidOrReservedUsernamesAreRejected(String username) {
            Api.Response response = api.post("/api/v1/auth/register", null, Map.of("username", username, "password", GOOD_PASSWORD));

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.json().get("errors").get(0).get("field").asString()).isEqualTo("username");
        }

        @Test
        void weakPasswordIsRejected() {
            Api.Response response = api.post("/api/v1/auth/register", null, Map.of("username", "user-" + UUID.randomUUID().toString().substring(0, 8), "password", "short"));

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.json().get("errors").get(0).get("field").asString()).isEqualTo("password");
        }

        @Test
        @DisplayName("A password over BCrypt's 72 byte limit is refused rather than silently truncated")
        void passwordOverBcryptLimitIsRejected() {
            String multibyte = "ş".repeat(40);

            Api.Response response = api.post("/api/v1/auth/register", null, Map.of("username", "user-" + UUID.randomUUID().toString().substring(0, 8), "password", multibyte));

            assertThat(response.status()).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("Authorisation")
    class Authorisation {

        @Test
        void usersCannotCallAdminEndpoints() {
            String user = newUser().token();
            UUID product = newProduct(1);

            List<Api.Response> responses = List.of(
                    api.post("/api/v1/products", user, Map.of("sku", "NOPE-" + UUID.randomUUID(), "name", "x")),
                    api.put("/api/v1/products/" + product, user, Map.of("name", "x", "version", 0)),
                    api.post("/api/v1/products/" + product + "/stock", user, Map.of("quantity", 1000)),
                    api.get("/api/v1/products/" + product + "/stock/movements", user),
                    api.get("/api/v1/audit-events", user),
                    api.get("/api/v1/admin/consistency", user),
                    api.post("/api/v1/simulations/reservations", user, Map.of("initialStock", 1, "concurrentRequests", 1, "quantityPerRequest", 1)));

            assertThat(responses).allSatisfy(response -> {
                assertThat(response.status()).as(response.toString()).isEqualTo(403);
                assertThat(response.code()).isEqualTo("ACCESS_DENIED");
            });
            assertThat(stock(product)).isEqualTo(new Stock(1, 1, 0));
        }

        @Test
        @DisplayName("Another user's reservation looks like it does not exist")
        void otherUsersReservationsAreInvisible() {
            UUID product = newProduct(2);
            List<TestUser> users = newUsers(2);
            UUID reservation = reserveOk(users.get(0), product, 1);

            assertThat(api.get("/api/v1/reservations/" + reservation, users.get(1).token()).status()).isEqualTo(404);
            assertThat(confirm(users.get(1), reservation).status()).isEqualTo(404);
            assertThat(cancel(users.get(1), reservation).status()).isEqualTo(404);

            assertThat(statusOf(reservation)).isEqualTo("PENDING");
            assertThat(stock(product)).isEqualTo(new Stock(2, 1, 1));
        }

        @Test
        void administratorsCanSettleAnyReservation() {
            UUID product = newProduct(2);
            UUID reservation = reserveOk(newUser(), product, 1);

            assertThat(api.get("/api/v1/reservations/" + reservation, admin().token()).status()).isEqualTo(200);
            assertThat(confirm(admin(), reservation).status()).isEqualTo(200);
            assertConsistent(product);
        }
    }

    @Nested
    @DisplayName("Input validation")
    class Validation {

        @ParameterizedTest
        @ValueSource(ints = {0, -1, 1001})
        void quantityOutOfRangeIsRejected(int quantity) {
            UUID product = newProduct(5);

            Api.Response response = reserve(newUser(), product, quantity, UUID.randomUUID().toString());

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
            assertThat(response.json().get("errors").get(0).get("field").asString()).isEqualTo("quantity");
            assertThat(stock(product)).isEqualTo(new Stock(5, 5, 0));
        }

        @Test
        void missingProductIdIsRejected() {
            Api.Response response = api.post("/api/v1/reservations", newUser().token(), Map.of("quantity", 1), Map.of("Idempotency-Key", "k"));

            assertThat(response.status()).isEqualTo(400);
        }

        @Test
        void malformedJsonIsRejected() {
            Api.Response response = api.postRaw("/api/v1/products", admin().token(), "{not json");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        }

        @Test
        void invalidUuidInPathIsRejected() {
            assertThat(api.get("/api/v1/reservations/not-a-uuid", newUser().token()).status()).isEqualTo(400);
        }

        @Test
        void unknownProductIsNotFound() {
            Api.Response response = reserve(newUser(), UUID.randomUUID(), 1, "unknown-product");

            assertThat(response.status()).isEqualTo(404);
            assertThat(response.code()).isEqualTo("PRODUCT_NOT_FOUND");
        }

        @Test
        void unknownReservationIsNotFound() {
            assertThat(confirm(newUser(), UUID.randomUUID()).code()).isEqualTo("RESERVATION_NOT_FOUND");
        }

        @Test
        void oversizedPageIsRejected() {
            assertThat(api.get("/api/v1/products?size=1000", newUser().token()).status()).isEqualTo(400);
            assertThat(api.get("/api/v1/products?page=-1", newUser().token()).status()).isEqualTo(400);
        }

        @Test
        void invalidStockQuantityIsRejected() {
            UUID product = newProduct(1);

            assertThat(api.post("/api/v1/products/" + product + "/stock", admin().token(), Map.of("quantity", 0)).status()).isEqualTo(400);
            assertThat(api.post("/api/v1/products/" + product + "/stock", admin().token(), Map.of("quantity", -5)).status()).isEqualTo(400);
            assertThat(stock(product)).isEqualTo(new Stock(1, 1, 0));
        }

        @Test
        @DisplayName("Error messages are in English whatever language the client asks for")
        void errorLanguageDoesNotDependOnClient() {
            UUID product = newProduct(1);

            Api.Response response = api.post("/api/v1/reservations", newUser().token(),
                    Map.of("productId", product, "quantity", 0),
                    Map.of("Idempotency-Key", "language", "Accept-Language", "tr-TR"));

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.json().get("errors").get(0).get("message").asString()).isEqualTo("must be greater than or equal to 1");
        }

        @Test
        @DisplayName("Error responses never expose stack traces or class names")
        void errorsDoNotLeakInternals() {
            Api.Response malformed = api.postRaw("/api/v1/products", admin().token(), "{not json");
            Api.Response wrongType = api.get("/api/v1/reservations/not-a-uuid", newUser().token());

            assertThat(List.of(malformed.rawBody(), wrongType.rawBody())).allSatisfy(body ->
                    assertThat(body).doesNotContain("Exception", "at com.", "at org.", "hibernate", "jackson"));
        }
    }

    @Nested
    @DisplayName("Public surface")
    class PublicSurface {

        @Test
        void healthIsPublicWithoutDetails() {
            Api.Response health = api.get("/actuator/health", null);

            assertThat(health.status()).isEqualTo(200);
            assertThat(health.text("status")).isEqualTo("UP");
            assertThat(health.rawBody()).doesNotContain("postgres", "jdbc");
        }

        @Test
        void otherActuatorEndpointsAreNotExposed() {
            assertThat(api.get("/actuator/env", admin().token()).status()).isEqualTo(404);
            assertThat(api.get("/actuator/heapdump", admin().token()).status()).isEqualTo(404);
        }

        @Test
        @DisplayName("Swagger documents every API operation")
        void openApiDescribesTheWholeApi() {
            Api.Response docs = api.get("/v3/api-docs", null);

            assertThat(docs.status()).isEqualTo(200);
            assertThat(docs.json().get("paths").propertyNames()).contains(
                    "/api/v1/auth/login",
                    "/api/v1/auth/register",
                    "/api/v1/products",
                    "/api/v1/products/{id}",
                    "/api/v1/products/{productId}/stock",
                    "/api/v1/products/{productId}/stock/movements",
                    "/api/v1/reservations",
                    "/api/v1/reservations/{id}",
                    "/api/v1/reservations/{id}/confirm",
                    "/api/v1/reservations/{id}/cancel",
                    "/api/v1/audit-events",
                    "/api/v1/admin/consistency",
                    "/api/v1/simulations/reservations");
            assertThat(docs.json().get("components").get("securitySchemes").get("bearerAuth")).isNotNull();
            assertThat(docs.rawBody()).as("every operation has a deliberate name").doesNotContainPattern("\"operationId\":\"\\w+_\\d+\"");
        }

        @Test
        void swaggerUiAndDemoPageAreReachable() {
            assertThat(api.get("/swagger-ui/index.html", null).status()).isEqualTo(200);
            assertThat(api.get("/demo/index.html", null).status()).isEqualTo(200);
        }
    }
}
