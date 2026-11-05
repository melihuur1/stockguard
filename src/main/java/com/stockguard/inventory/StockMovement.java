package com.stockguard.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

/** One append-only ledger line. The identity id is assigned on insert, inside the stock row lock. */
@Entity
@Immutable
@Table(name = "stock_movements")
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MovementType type;

    @Column(name = "delta_total", nullable = false)
    private int deltaTotal;

    @Column(name = "delta_available", nullable = false)
    private int deltaAvailable;

    @Column(name = "delta_reserved", nullable = false)
    private int deltaReserved;

    @Column(name = "total_after", nullable = false)
    private int totalAfter;

    @Column(name = "available_after", nullable = false)
    private int availableAfter;

    @Column(name = "reserved_after", nullable = false)
    private int reservedAfter;

    @Column
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StockMovement() {
    }

    StockMovement(
            UUID productId,
            UUID reservationId,
            UUID actorId,
            MovementType type,
            int deltaAvailable,
            int deltaReserved,
            StockLevelRepository.StockCounts after,
            String note) {
        this.productId = productId;
        this.reservationId = reservationId;
        this.actorId = actorId;
        this.type = type;
        this.deltaTotal = deltaAvailable + deltaReserved;
        this.deltaAvailable = deltaAvailable;
        this.deltaReserved = deltaReserved;
        this.totalAfter = after.getTotal();
        this.availableAfter = after.getAvailable();
        this.reservedAfter = after.getReserved();
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public UUID getActorId() {
        return actorId;
    }

    public MovementType getType() {
        return type;
    }

    public int getDeltaTotal() {
        return deltaTotal;
    }

    public int getDeltaAvailable() {
        return deltaAvailable;
    }

    public int getDeltaReserved() {
        return deltaReserved;
    }

    public int getTotalAfter() {
        return totalAfter;
    }

    public int getAvailableAfter() {
        return availableAfter;
    }

    public int getReservedAfter() {
        return reservedAfter;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
