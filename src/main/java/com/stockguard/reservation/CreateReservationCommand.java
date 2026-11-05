package com.stockguard.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** @param ttlSeconds how long to hold the stock, or null for the configured default */
public record CreateReservationCommand(UUID productId, int quantity, Integer ttlSeconds, String idempotencyKey) {

    /**
     * Identifies what was asked for, to detect a key reused for a different request. It is computed from the
     * parsed values rather than the raw body, so whitespace or JSON key order cannot make equal requests differ.
     * A null TTL stays "default" instead of being resolved, so changing the default cannot break a later replay.
     */
    public String fingerprint() {
        String canonical = productId + "|" + quantity + "|" + (ttlSeconds == null ? "default" : ttlSeconds);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required on every JVM", ex);
        }
    }
}
