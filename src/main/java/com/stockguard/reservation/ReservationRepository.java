package com.stockguard.reservation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deadlines are compared with the database clock ({@code now()}), never the JVM clock, so all instances agree
 * on when a reservation has expired regardless of clock skew between them.
 */
interface ReservationRepository extends Repository<Reservation, UUID> {

    Optional<Reservation> findById(UUID id);

    Optional<Reservation> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    Page<Reservation> findAllBy(Pageable pageable);

    Page<Reservation> findByUserId(UUID userId, Pageable pageable);

    /**
     * Claims the idempotency key and creates the reservation in one statement. If another transaction has
     * just inserted the same key, PostgreSQL blocks this statement until that transaction ends. If it
     * committed, this returns 0 (a duplicate). If it rolled back, this insert simply goes ahead. That is what
     * turns concurrent duplicates into exactly one logical operation.
     */
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(nativeQuery = true, value = """
            INSERT INTO reservations
                (id, product_id, user_id, quantity, status, idempotency_key, request_hash, expires_at, created_at)
            VALUES
                (:id, :productId, :userId, :quantity, 'PENDING', :idempotencyKey, :requestHash,
                 now() + make_interval(secs => :ttlSeconds), now())
            ON CONFLICT (user_id, idempotency_key) DO NOTHING
            """)
    int insertIfKeyUnused(
            @Param("id") UUID id,
            @Param("productId") UUID productId,
            @Param("userId") UUID userId,
            @Param("quantity") int quantity,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("ttlSeconds") double ttlSeconds);

    /**
     * Compare-and-set: of all attempts to settle one reservation, exactly one can match {@code PENDING}.
     * Losers either see the winner's committed status directly or wait on its row lock and then re-check.
     * After the deadline neither confirm nor cancel matches, which leaves expiry as the only way out.
     */
    @Modifying(clearAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(nativeQuery = true, value = """
            UPDATE reservations
               SET status = :target, settled_at = now()
             WHERE id = :id
               AND status = 'PENDING'
               AND expires_at > now()
            """)
    int settleIfPendingAndInTime(@Param("id") UUID id, @Param("target") String target);

    /**
     * Claims a batch of overdue reservations. SKIP LOCKED makes parallel sweepers on different instances pass
     * over rows another sweeper or an in-flight confirm is holding, instead of queueing behind them. Whichever
     * side loses the race then finds that the row is no longer PENDING.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(nativeQuery = true, value = """
            WITH due AS (
                SELECT id FROM reservations
                 WHERE status = 'PENDING' AND expires_at <= now()
                 ORDER BY expires_at
                 LIMIT :limit
                 FOR UPDATE SKIP LOCKED
            )
            UPDATE reservations r
               SET status = 'EXPIRED', settled_at = now()
              FROM due
             WHERE r.id = due.id
            RETURNING r.id AS "id", r.product_id AS "productId", r.user_id AS "userId", r.quantity AS "quantity"
            """)
    List<ExpiredReservation> expireDue(@Param("limit") int limit);

    interface ExpiredReservation {
        UUID getId();

        UUID getProductId();

        UUID getUserId();

        int getQuantity();
    }
}
