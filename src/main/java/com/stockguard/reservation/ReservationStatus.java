package com.stockguard.reservation;

/** PENDING is the only state with outgoing transitions; every other state is final. */
public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}
