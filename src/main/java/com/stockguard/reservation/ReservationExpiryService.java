package com.stockguard.reservation;

import com.stockguard.audit.AuditEventType;
import com.stockguard.audit.AuditTrail;
import com.stockguard.inventory.MovementType;
import com.stockguard.inventory.StockLedger;
import com.stockguard.reservation.ReservationRepository.ExpiredReservation;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationExpiryService {

    private final ReservationRepository reservations;
    private final StockLedger ledger;
    private final AuditTrail audit;

    ReservationExpiryService(ReservationRepository reservations, StockLedger ledger, AuditTrail audit) {
        this.reservations = reservations;
        this.ledger = ledger;
        this.audit = audit;
    }

    /**
     * Expires one batch in a single transaction. A batch touches several stock rows, so two sweepers locking
     * them in different orders could deadlock; releasing in a fixed product order rules that out.
     *
     * @return how many reservations were expired
     */
    @Transactional
    public int expireDueBatch(int batchSize) {
        List<ExpiredReservation> expired = reservations.expireDue(batchSize).stream()
                .sorted(Comparator.comparing(ExpiredReservation::getProductId))
                .toList();
        for (ExpiredReservation reservation : expired) {
            ledger.releaseReserved(
                    reservation.getProductId(), reservation.getQuantity(), reservation.getId(), null, MovementType.EXPIRY_RELEASED);
            audit.record(AuditEventType.RESERVATION_EXPIRED, null, "RESERVATION", reservation.getId(), Map.of(
                    "productId", reservation.getProductId(),
                    "userId", reservation.getUserId(),
                    "quantity", reservation.getQuantity()));
        }
        return expired.size();
    }
}
