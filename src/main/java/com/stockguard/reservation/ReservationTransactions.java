package com.stockguard.reservation;

import com.stockguard.audit.AuditEventType;
import com.stockguard.audit.AuditTrail;
import com.stockguard.common.config.StockGuardProperties;
import com.stockguard.common.error.ApiException;
import com.stockguard.common.error.ErrorCode;
import com.stockguard.inventory.InventoryService;
import com.stockguard.inventory.MovementType;
import com.stockguard.inventory.StockLedger;
import com.stockguard.security.Actor;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transaction boundaries of the reservation lifecycle. Every method is one all-or-nothing unit and uses
 * the same lock order, reservation row first and stock row second, so these transactions cannot deadlock
 * against each other. The stock row is the contended one, so it is always touched last and held for as short
 * a time as possible.
 */
@Service
public class ReservationTransactions {

    private final ReservationRepository reservations;
    private final StockLedger ledger;
    private final InventoryService inventory;
    private final AuditTrail audit;
    private final long defaultTtlSeconds;

    ReservationTransactions(
            ReservationRepository reservations,
            StockLedger ledger,
            InventoryService inventory,
            AuditTrail audit,
            StockGuardProperties properties) {
        this.reservations = reservations;
        this.ledger = ledger;
        this.inventory = inventory;
        this.audit = audit;
        this.defaultTtlSeconds = properties.reservation().defaultTtl().toSeconds();
    }

    @Transactional
    public CreateOutcome create(Actor actor, CreateReservationCommand command) {
        UUID productId = command.productId();
        if (!inventory.productExists(productId)) {
            throw new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product " + productId + " does not exist.");
        }

        UUID id = UUID.randomUUID();
        String fingerprint = command.fingerprint();
        long ttlSeconds = command.ttlSeconds() != null ? command.ttlSeconds() : defaultTtlSeconds;
        int inserted = reservations.insertIfKeyUnused(
                id, productId, actor.userId(), command.quantity(), command.idempotencyKey(), fingerprint, ttlSeconds);
        if (inserted == 0) {
            return replay(actor, command.idempotencyKey(), fingerprint);
        }

        Reservation created = reservations.findById(id).orElseThrow();
        audit.record(AuditEventType.RESERVATION_CREATED, actor.userId(), "RESERVATION", id,
                Map.of("productId", productId, "quantity", command.quantity()));

        if (!ledger.tryReserve(productId, command.quantity(), id, actor.userId())) {
            // Rolls back the insert above too, so a rejected request does not use up its idempotency key.
            throw new ApiException(ErrorCode.INSUFFICIENT_STOCK, "Not enough stock is available for this request.");
        }
        return new CreateOutcome(created, false);
    }

    private CreateOutcome replay(Actor actor, String idempotencyKey, String fingerprint) {
        Reservation original = reservations.findByUserIdAndIdempotencyKey(actor.userId(), idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("A conflicting key was reported but the row is not visible"));
        if (!original.getRequestHash().equals(fingerprint)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used with a different request.");
        }
        return new CreateOutcome(original, true);
    }

    @Transactional
    public Reservation confirm(Actor actor, UUID id) {
        Reservation current = loadVisible(actor, id);
        settle(id, ReservationStatus.CONFIRMED);
        ledger.commitReserved(current.getProductId(), current.getQuantity(), id, actor.userId());
        audit.record(AuditEventType.RESERVATION_CONFIRMED, actor.userId(), "RESERVATION", id,
                Map.of("productId", current.getProductId(), "quantity", current.getQuantity()));
        return reservations.findById(id).orElseThrow();
    }

    @Transactional
    public Reservation cancel(Actor actor, UUID id) {
        Reservation current = loadVisible(actor, id);
        settle(id, ReservationStatus.CANCELLED);
        ledger.releaseReserved(current.getProductId(), current.getQuantity(), id, actor.userId(), MovementType.CANCEL_RELEASED);
        audit.record(AuditEventType.RESERVATION_CANCELLED, actor.userId(), "RESERVATION", id,
                Map.of("productId", current.getProductId(), "quantity", current.getQuantity()));
        return reservations.findById(id).orElseThrow();
    }

    /** Someone else's reservation looks exactly like a missing one, so ids cannot be probed. */
    private Reservation loadVisible(Actor actor, UUID id) {
        return reservations.findById(id)
                .filter(reservation -> actor.canAccessResourceOf(reservation.getUserId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND, "Reservation " + id + " does not exist."));
    }

    private void settle(UUID id, ReservationStatus target) {
        if (reservations.settleIfPendingAndInTime(id, target.name()) == 0) {
            Reservation latest = reservations.findById(id).orElseThrow();
            throw rejection(latest);
        }
    }

    /** Works out why the compare-and-set lost, using the latest committed state. */
    private static ApiException rejection(Reservation latest) {
        return switch (latest.getStatus()) {
            // Still PENDING means the deadline has passed and the sweeper has not reached this row yet.
            case PENDING, EXPIRED -> new ApiException(ErrorCode.RESERVATION_EXPIRED, "This reservation has expired.");
            case CONFIRMED, CANCELLED -> new ApiException(ErrorCode.RESERVATION_ALREADY_SETTLED,
                    "This reservation is already " + latest.getStatus() + ".");
        };
    }

    public record CreateOutcome(Reservation reservation, boolean replayed) {
    }
}
