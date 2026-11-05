package com.stockguard.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stockguard.audit.AuditEventType;
import com.stockguard.audit.AuditTrail;
import com.stockguard.common.config.StockGuardProperties;
import com.stockguard.common.error.ApiException;
import com.stockguard.common.error.ErrorCode;
import com.stockguard.inventory.InventoryService;
import com.stockguard.inventory.MovementType;
import com.stockguard.inventory.StockLedger;
import com.stockguard.reservation.ReservationTransactions.CreateOutcome;
import com.stockguard.security.Actor;
import com.stockguard.user.Role;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The decision logic in isolation: which error for which state, and which collaborators are or are not touched.
 * Whether the SQL actually enforces these decisions under concurrency is what the integration tests prove.
 */
@ExtendWith(MockitoExtension.class)
class ReservationTransactionsTest {

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final Actor OWNER_ACTOR = new Actor(OWNER, Role.USER);

    @Mock
    private ReservationRepository reservations;

    @Mock
    private StockLedger ledger;

    @Mock
    private InventoryService inventory;

    @Mock
    private AuditTrail audit;

    private ReservationTransactions transactions;

    @BeforeEach
    void setUp() {
        StockGuardProperties properties = new StockGuardProperties(
                "unit-test",
                new StockGuardProperties.Security("x".repeat(32), Duration.ofHours(1), new StockGuardProperties.Admin("", "")),
                new StockGuardProperties.Reservation(Duration.ofMinutes(15), new StockGuardProperties.Expiry(false, Duration.ofSeconds(5), 100)),
                new StockGuardProperties.Simulation(false, List.of()));
        transactions = new ReservationTransactions(reservations, ledger, inventory, audit, properties);
    }

    private static CreateReservationCommand command(int quantity) {
        return new CreateReservationCommand(PRODUCT, quantity, null, "key-1");
    }

    private static Reservation reservation(UUID id, UUID owner, ReservationStatus status, int quantity, String requestHash) {
        Reservation reservation = mock(Reservation.class, Mockito.withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        when(reservation.getId()).thenReturn(id);
        when(reservation.getProductId()).thenReturn(PRODUCT);
        when(reservation.getUserId()).thenReturn(owner);
        when(reservation.getStatus()).thenReturn(status);
        when(reservation.getQuantity()).thenReturn(quantity);
        when(reservation.getRequestHash()).thenReturn(requestHash);
        return reservation;
    }

    private void keyIsUnused() {
        when(inventory.productExists(PRODUCT)).thenReturn(true);
        when(reservations.insertIfKeyUnused(any(), eq(PRODUCT), eq(OWNER), anyInt(), eq("key-1"), anyString(), anyDouble())).thenReturn(1);
        when(reservations.findById(any())).thenAnswer(call -> Optional.of(
                reservation(call.getArgument(0), OWNER, ReservationStatus.PENDING, 2, "hash")));
    }

    private void keyWasUsedBefore(String requestHash) {
        when(inventory.productExists(PRODUCT)).thenReturn(true);
        when(reservations.insertIfKeyUnused(any(), any(), any(), anyInt(), any(), any(), anyDouble())).thenReturn(0);
        Reservation original = reservation(UUID.randomUUID(), OWNER, ReservationStatus.PENDING, 2, requestHash);
        when(reservations.findByUserIdAndIdempotencyKey(OWNER, "key-1")).thenReturn(Optional.of(original));
    }

    @Test
    @DisplayName("Unknown product is refused before anything is written")
    void unknownProduct() {
        when(inventory.productExists(PRODUCT)).thenReturn(false);

        assertThatThrownBy(() -> transactions.create(OWNER_ACTOR, command(1)))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));

        verifyNoInteractions(reservations, ledger, audit);
    }

    @Test
    @DisplayName("A new key creates a reservation, audits it, then touches the stock row last")
    void newKeyReservesStockLast() {
        keyIsUnused();
        when(ledger.tryReserve(eq(PRODUCT), eq(2), any(), eq(OWNER))).thenReturn(true);

        CreateOutcome outcome = transactions.create(OWNER_ACTOR, command(2));

        assertThat(outcome.replayed()).isFalse();
        InOrder order = Mockito.inOrder(reservations, audit, ledger);
        order.verify(reservations).insertIfKeyUnused(any(), any(), any(), anyInt(), any(), any(), anyDouble());
        order.verify(audit).record(eq(AuditEventType.RESERVATION_CREATED), eq(OWNER), eq("RESERVATION"), any(), any());
        order.verify(ledger).tryReserve(eq(PRODUCT), eq(2), any(), eq(OWNER));
    }

    @Test
    @DisplayName("The configured default TTL applies when the request has none")
    void defaultTtl() {
        keyIsUnused();
        when(ledger.tryReserve(any(), anyInt(), any(), any())).thenReturn(true);

        transactions.create(OWNER_ACTOR, command(1));

        verify(reservations).insertIfKeyUnused(any(), any(), any(), anyInt(), any(), any(), eq(900.0));
    }

    @Test
    @DisplayName("Not enough stock throws, which is what rolls the insert back and frees the key")
    void insufficientStock() {
        keyIsUnused();
        when(ledger.tryReserve(any(), anyInt(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> transactions.create(OWNER_ACTOR, command(2)))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK));
    }

    @Test
    @DisplayName("Same key and same request is a replay: stock is not touched and nothing is audited")
    void replayOfSameRequest() {
        keyWasUsedBefore(command(2).fingerprint());

        CreateOutcome outcome = transactions.create(OWNER_ACTOR, command(2));

        assertThat(outcome.replayed()).isTrue();
        verifyNoInteractions(ledger, audit);
    }

    @Test
    @DisplayName("Same key with a different request is refused")
    void keyReuseWithDifferentRequest() {
        keyWasUsedBefore(command(2).fingerprint());

        assertThatThrownBy(() -> transactions.create(OWNER_ACTOR, command(3)))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));

        verifyNoInteractions(ledger, audit);
    }

    private UUID existing(ReservationStatus statusAfterLosingTheRace) {
        UUID id = UUID.randomUUID();
        Reservation reservation = reservation(id, OWNER, statusAfterLosingTheRace, 2, "hash");
        when(reservations.findById(id)).thenReturn(Optional.of(reservation));
        return id;
    }

    @Test
    @DisplayName("Winning the compare-and-set moves the stock exactly once")
    void confirmWinner() {
        UUID id = existing(ReservationStatus.PENDING);
        when(reservations.settleIfPendingAndInTime(id, "CONFIRMED")).thenReturn(1);

        transactions.confirm(OWNER_ACTOR, id);

        verify(ledger).commitReserved(PRODUCT, 2, id, OWNER);
        verify(audit).record(eq(AuditEventType.RESERVATION_CONFIRMED), eq(OWNER), eq("RESERVATION"), eq(id), any());
    }

    @Test
    void cancelWinnerReleasesUnits() {
        UUID id = existing(ReservationStatus.PENDING);
        when(reservations.settleIfPendingAndInTime(id, "CANCELLED")).thenReturn(1);

        transactions.cancel(OWNER_ACTOR, id);

        verify(ledger).releaseReserved(PRODUCT, 2, id, OWNER, MovementType.CANCEL_RELEASED);
    }

    @Test
    @DisplayName("Losing to another settlement reports the status that won and never touches the stock")
    void losingToAnotherSettlement() {
        for (ReservationStatus winner : List.of(ReservationStatus.CONFIRMED, ReservationStatus.CANCELLED)) {
            UUID id = existing(winner);
            when(reservations.settleIfPendingAndInTime(eq(id), anyString())).thenReturn(0);

            assertThatThrownBy(() -> transactions.confirm(OWNER_ACTOR, id))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.code()).isEqualTo(ErrorCode.RESERVATION_ALREADY_SETTLED);
                        assertThat(ex.getMessage()).contains(winner.name());
                    });
        }
        verifyNoInteractions(ledger, audit);
    }

    @Test
    @DisplayName("A failed compare-and-set on a still PENDING row can only mean the deadline passed")
    void pendingAfterFailedCompareAndSetMeansExpired() {
        UUID id = existing(ReservationStatus.PENDING);
        when(reservations.settleIfPendingAndInTime(id, "CONFIRMED")).thenReturn(0);

        assertThatThrownBy(() -> transactions.confirm(OWNER_ACTOR, id))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.RESERVATION_EXPIRED));
        verifyNoInteractions(ledger);
    }

    @Test
    void alreadyExpired() {
        UUID id = existing(ReservationStatus.EXPIRED);
        when(reservations.settleIfPendingAndInTime(id, "CANCELLED")).thenReturn(0);

        assertThatThrownBy(() -> transactions.cancel(OWNER_ACTOR, id))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.RESERVATION_EXPIRED));
        verifyNoInteractions(ledger);
    }

    @Test
    @DisplayName("Another user's reservation is reported as missing and is never settled")
    void otherUserCannotSettle() {
        UUID id = existing(ReservationStatus.PENDING);
        Actor stranger = new Actor(UUID.randomUUID(), Role.USER);

        assertThatThrownBy(() -> transactions.confirm(stranger, id))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.RESERVATION_NOT_FOUND));

        verify(reservations, never()).settleIfPendingAndInTime(any(), any());
        verifyNoInteractions(ledger, audit);
    }

    @Test
    void administratorCanSettleAnyReservation() {
        UUID id = existing(ReservationStatus.PENDING);
        UUID adminId = UUID.randomUUID();
        when(reservations.settleIfPendingAndInTime(id, "CONFIRMED")).thenReturn(1);

        transactions.confirm(new Actor(adminId, Role.ADMIN), id);

        verify(ledger).commitReserved(PRODUCT, 2, id, adminId);
    }
}
