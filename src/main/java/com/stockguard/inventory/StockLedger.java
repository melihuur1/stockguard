package com.stockguard.inventory;

import com.stockguard.inventory.StockLevelRepository.StockCounts;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single gateway for changing stock. Each operation performs one atomic counter update and records its
 * ledger line while the row lock is still held. Callers own the transaction, so the change, the ledger line
 * and the caller's own writes commit or roll back as one unit.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class StockLedger {

    private final StockLevelRepository levels;
    private final StockMovementRepository movements;

    StockLedger(StockLevelRepository levels, StockMovementRepository movements) {
        this.levels = levels;
        this.movements = movements;
    }

    public void open(UUID productId) {
        levels.insertEmpty(productId);
    }

    /** @return the new counters, or empty if the product has no stock record */
    public Optional<StockCounts> receive(UUID productId, int quantity, UUID actorId, String note) {
        Optional<StockCounts> after = levels.addAvailable(productId, quantity);
        after.ifPresent(counts -> movements.save(new StockMovement(
                productId, null, actorId, MovementType.STOCK_IN, quantity, 0, counts, note)));
        return after;
    }

    /** @return false when fewer than {@code quantity} units are available. Nothing has changed in that case. */
    public boolean tryReserve(UUID productId, int quantity, UUID reservationId, UUID actorId) {
        Optional<StockCounts> after = levels.moveAvailableToReserved(productId, quantity);
        after.ifPresent(counts -> movements.save(new StockMovement(
                productId, reservationId, actorId, MovementType.RESERVED, -quantity, quantity, counts, null)));
        return after.isPresent();
    }

    public void commitReserved(UUID productId, int quantity, UUID reservationId, UUID actorId) {
        StockCounts after = levels.removeReserved(productId, quantity)
                .orElseThrow(() -> reservedUnitsMissing(productId, reservationId));
        movements.save(new StockMovement(
                productId, reservationId, actorId, MovementType.CONFIRMED, 0, -quantity, after, null));
    }

    public void releaseReserved(UUID productId, int quantity, UUID reservationId, UUID actorId, MovementType reason) {
        StockCounts after = levels.moveReservedToAvailable(productId, quantity)
                .orElseThrow(() -> reservedUnitsMissing(productId, reservationId));
        movements.save(new StockMovement(productId, reservationId, actorId, reason, quantity, -quantity, after, null));
    }

    /** Each PENDING reservation is backed by reserved units, so this signals corruption: fail and roll back. */
    private static IllegalStateException reservedUnitsMissing(UUID productId, UUID reservationId) {
        return new IllegalStateException(
                "Reservation " + reservationId + " holds more units than product " + productId + " has reserved");
    }
}
