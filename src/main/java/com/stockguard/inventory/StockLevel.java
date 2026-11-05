package com.stockguard.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * Read model of the stock counters. {@code @Immutable} and the absence of setters are deliberate: a counter
 * written back from a Java copy is the classic lost update, so the counters only ever change through the
 * conditional statements in {@link StockLevelRepository}.
 */
@Entity
@Immutable
@Table(name = "stock_levels")
public class StockLevel {

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "total_quantity", nullable = false)
    private int total;

    @Column(name = "available_quantity", nullable = false)
    private int available;

    @Column(name = "reserved_quantity", nullable = false)
    private int reserved;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StockLevel() {
    }

    public UUID getProductId() {
        return productId;
    }

    public int getTotal() {
        return total;
    }

    public int getAvailable() {
        return available;
    }

    public int getReserved() {
        return reserved;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
