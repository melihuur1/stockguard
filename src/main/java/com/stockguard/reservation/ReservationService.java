package com.stockguard.reservation;

import com.stockguard.common.error.ApiException;
import com.stockguard.common.error.ErrorCode;
import com.stockguard.reservation.ReservationTransactions.CreateOutcome;
import com.stockguard.security.Actor;
import java.util.UUID;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The retry boundary. It sits deliberately outside the transaction: PostgreSQL aborts the whole transaction
 * when a deadlock or lock timeout hits it, so only a brand new transaction can try again. These failures say
 * nothing about the request itself, which makes a short bounded retry safe; business rejections are not retried.
 */
@Service
public class ReservationService {

    private final ReservationTransactions transactions;
    private final ReservationRepository reservations;

    ReservationService(ReservationTransactions transactions, ReservationRepository reservations) {
        this.transactions = transactions;
        this.reservations = reservations;
    }

    @Retryable(includes = PessimisticLockingFailureException.class, maxRetries = 2, delay = 50, jitter = 25, multiplier = 2)
    public CreateOutcome create(Actor actor, CreateReservationCommand command) {
        return transactions.create(actor, command);
    }

    @Retryable(includes = PessimisticLockingFailureException.class, maxRetries = 2, delay = 50, jitter = 25, multiplier = 2)
    public Reservation confirm(Actor actor, UUID id) {
        return transactions.confirm(actor, id);
    }

    @Retryable(includes = PessimisticLockingFailureException.class, maxRetries = 2, delay = 50, jitter = 25, multiplier = 2)
    public Reservation cancel(Actor actor, UUID id) {
        return transactions.cancel(actor, id);
    }

    @Transactional(readOnly = true)
    public Reservation get(Actor actor, UUID id) {
        return reservations.findById(id)
                .filter(reservation -> actor.canAccessResourceOf(reservation.getUserId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND, "Reservation " + id + " does not exist."));
    }

    /** Administrators see every reservation, users only their own. */
    @Transactional(readOnly = true)
    public Page<Reservation> list(Actor actor, int page, int size) {
        PageRequest request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return actor.isAdmin()
                ? reservations.findAllBy(request)
                : reservations.findByUserId(actor.userId(), request);
    }
}
