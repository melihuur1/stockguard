package com.stockguard.reservation;

import com.stockguard.common.web.PageResponse;
import com.stockguard.reservation.ReservationTransactions.CreateOutcome;
import com.stockguard.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reservations")
@Tag(name = "Reservations")
public class ReservationController {

    static final String REPLAY_HEADER = "Idempotency-Replayed";

    private final ReservationService reservations;

    ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @PostMapping
    @Operation(operationId = "createReservation", summary = "Hold stock for a limited time", description = """
            Retrying with the same `Idempotency-Key` and the same body returns the original reservation instead \
            of creating another one. A failed attempt holds nothing and does not use up the key.""")
    @ApiResponse(responseCode = "201", description = "Reservation created")
    @ApiResponse(responseCode = "200", description = "Replay of an earlier request: nothing new was created")
    @ApiResponse(responseCode = "400", description = "Invalid body or Idempotency-Key")
    @ApiResponse(responseCode = "404", description = "Unknown product")
    @ApiResponse(responseCode = "409", description = "INSUFFICIENT_STOCK")
    @ApiResponse(responseCode = "422", description = "IDEMPOTENCY_KEY_REUSED with a different request")
    @ApiResponse(responseCode = "503", description = "Temporary lock contention: safe to retry with the same key")
    public ResponseEntity<ReservationResponse> create(
            @Parameter(description = "Unique per logical operation, for example a UUID")
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "^[A-Za-z0-9_:.\\-]{1,128}$", message = "must be 1-128 letters, digits, '_', '-', ':' or '.'")
            String idempotencyKey,
            @Valid @RequestBody CreateReservationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        CreateOutcome outcome = reservations.create(Actor.from(jwt), new CreateReservationCommand(
                request.productId(), request.quantity(), request.ttlSeconds(), idempotencyKey));
        ReservationResponse body = ReservationResponse.from(outcome.reservation());
        if (outcome.replayed()) {
            return ResponseEntity.ok().header(REPLAY_HEADER, "true").body(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/reservations/" + body.id())).body(body);
    }

    @GetMapping
    @Operation(operationId = "listReservations", summary = "List reservations: your own, or all of them for an administrator")
    public PageResponse<ReservationResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal Jwt jwt) {
        return PageResponse.of(reservations.list(Actor.from(jwt), page, size), ReservationResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getReservation", summary = "Read a reservation")
    public ReservationResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ReservationResponse.from(reservations.get(Actor.from(jwt), id));
    }

    @PostMapping("/{id}/confirm")
    @Operation(operationId = "confirmReservation", summary = "Confirm a reservation: the held units leave the stock")
    @ApiResponse(responseCode = "409", description = "RESERVATION_EXPIRED or RESERVATION_ALREADY_SETTLED")
    public ReservationResponse confirm(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ReservationResponse.from(reservations.confirm(Actor.from(jwt), id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(operationId = "cancelReservation", summary = "Cancel a reservation: the held units become available again")
    @ApiResponse(responseCode = "409", description = "RESERVATION_EXPIRED or RESERVATION_ALREADY_SETTLED")
    public ReservationResponse cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ReservationResponse.from(reservations.cancel(Actor.from(jwt), id));
    }

    public record CreateReservationRequest(
            @NotNull UUID productId,
            @NotNull @Min(1) @Max(1000) Integer quantity,
            @Min(1) @Max(3600) Integer ttlSeconds) {
    }

    public record ReservationResponse(
            UUID id,
            UUID productId,
            UUID userId,
            int quantity,
            ReservationStatus status,
            Instant expiresAt,
            Instant settledAt,
            Instant createdAt) {

        static ReservationResponse from(Reservation reservation) {
            return new ReservationResponse(
                    reservation.getId(),
                    reservation.getProductId(),
                    reservation.getUserId(),
                    reservation.getQuantity(),
                    reservation.getStatus(),
                    reservation.getExpiresAt(),
                    reservation.getSettledAt(),
                    reservation.getCreatedAt());
        }
    }
}
