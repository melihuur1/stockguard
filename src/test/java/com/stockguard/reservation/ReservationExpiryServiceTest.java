package com.stockguard.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stockguard.audit.AuditEventType;
import com.stockguard.audit.AuditTrail;
import com.stockguard.inventory.MovementType;
import com.stockguard.inventory.StockLedger;
import com.stockguard.reservation.ReservationRepository.ExpiredReservation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationExpiryServiceTest {

    @Mock
    private ReservationRepository reservations;

    @Mock
    private StockLedger ledger;

    @Mock
    private AuditTrail audit;

    @InjectMocks
    private ReservationExpiryService service;

    private static ExpiredReservation expired(UUID product) {
        ExpiredReservation row = mock(ExpiredReservation.class);
        when(row.getId()).thenReturn(UUID.randomUUID());
        when(row.getProductId()).thenReturn(product);
        when(row.getUserId()).thenReturn(UUID.randomUUID());
        when(row.getQuantity()).thenReturn(1);
        return row;
    }

    @Test
    @DisplayName("Stock rows are always locked in product order, which is what prevents sweeper deadlocks")
    void releasesInProductOrder() {
        UUID low = new UUID(0, 1);
        UUID middle = new UUID(0, 2);
        UUID high = new UUID(0, 3);
        List<ExpiredReservation> rows = List.of(expired(high), expired(low), expired(middle));
        when(reservations.expireDue(10)).thenReturn(rows);

        int count = service.expireDueBatch(10);

        assertThat(count).isEqualTo(3);
        InOrder order = inOrder(ledger);
        order.verify(ledger).releaseReserved(eq(low), anyInt(), any(), isNull(), eq(MovementType.EXPIRY_RELEASED));
        order.verify(ledger).releaseReserved(eq(middle), anyInt(), any(), isNull(), eq(MovementType.EXPIRY_RELEASED));
        order.verify(ledger).releaseReserved(eq(high), anyInt(), any(), isNull(), eq(MovementType.EXPIRY_RELEASED));
    }

    @Test
    @DisplayName("Expiry is recorded as an action of the system, not of a user")
    void auditedAsSystem() {
        List<ExpiredReservation> rows = List.of(expired(new UUID(0, 1)));
        when(reservations.expireDue(10)).thenReturn(rows);

        service.expireDueBatch(10);

        org.mockito.Mockito.verify(audit).record(eq(AuditEventType.RESERVATION_EXPIRED), isNull(), eq("RESERVATION"), any(), any());
    }

    @Test
    void nothingDueMeansNothingHappens() {
        when(reservations.expireDue(10)).thenReturn(List.of());

        assertThat(service.expireDueBatch(10)).isZero();

        verifyNoInteractions(ledger, audit);
    }
}
