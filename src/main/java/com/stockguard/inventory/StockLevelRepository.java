package com.stockguard.inventory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every write here is a single conditional statement. The row lock PostgreSQL takes for the UPDATE is what
 * serialises competing requests, and it is re-checked after the wait: under READ COMMITTED a blocked UPDATE
 * re-evaluates its WHERE clause against the row version committed by the previous lock holder. A request
 * that no longer fits therefore matches zero rows, on any instance, with no read-then-write gap.
 *
 * <p>MANDATORY propagation means these can never run outside a business transaction, so the ledger entry
 * written next to them cannot be committed separately.
 */
interface StockLevelRepository extends Repository<StockLevel, UUID> {

    Optional<StockLevel> findById(UUID productId);

    boolean existsById(UUID productId);

    List<StockLevel> findByProductIdIn(Collection<UUID> productIds);

    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(nativeQuery = true, value = """
            INSERT INTO stock_levels (product_id, total_quantity, available_quantity, reserved_quantity, updated_at)
            VALUES (:productId, 0, 0, 0, now())
            """)
    int insertEmpty(@Param("productId") UUID productId);

    @Transactional(propagation = Propagation.MANDATORY)
    @Query(nativeQuery = true, value = """
            UPDATE stock_levels
               SET total_quantity = total_quantity + :quantity,
                   available_quantity = available_quantity + :quantity,
                   updated_at = now()
             WHERE product_id = :productId
            RETURNING total_quantity AS "total", available_quantity AS "available", reserved_quantity AS "reserved"
            """)
    Optional<StockCounts> addAvailable(@Param("productId") UUID productId, @Param("quantity") int quantity);

    /** The anti-overselling statement. An empty result means too little stock was left once it got the row lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(nativeQuery = true, value = """
            UPDATE stock_levels
               SET available_quantity = available_quantity - :quantity,
                   reserved_quantity = reserved_quantity + :quantity,
                   updated_at = now()
             WHERE product_id = :productId
               AND available_quantity >= :quantity
            RETURNING total_quantity AS "total", available_quantity AS "available", reserved_quantity AS "reserved"
            """)
    Optional<StockCounts> moveAvailableToReserved(@Param("productId") UUID productId, @Param("quantity") int quantity);

    /** Confirmation: reserved units leave the warehouse. Available was already reduced when they were reserved. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(nativeQuery = true, value = """
            UPDATE stock_levels
               SET total_quantity = total_quantity - :quantity,
                   reserved_quantity = reserved_quantity - :quantity,
                   updated_at = now()
             WHERE product_id = :productId
               AND reserved_quantity >= :quantity
            RETURNING total_quantity AS "total", available_quantity AS "available", reserved_quantity AS "reserved"
            """)
    Optional<StockCounts> removeReserved(@Param("productId") UUID productId, @Param("quantity") int quantity);

    /** Cancellation or expiry: held units become sellable again. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(nativeQuery = true, value = """
            UPDATE stock_levels
               SET available_quantity = available_quantity + :quantity,
                   reserved_quantity = reserved_quantity - :quantity,
                   updated_at = now()
             WHERE product_id = :productId
               AND reserved_quantity >= :quantity
            RETURNING total_quantity AS "total", available_quantity AS "available", reserved_quantity AS "reserved"
            """)
    Optional<StockCounts> moveReservedToAvailable(@Param("productId") UUID productId, @Param("quantity") int quantity);

    /** The counters as they were left by the statement that returned them, read under the row lock. */
    interface StockCounts {
        int getTotal();

        int getAvailable();

        int getReserved();
    }
}
