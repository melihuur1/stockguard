package com.stockguard.reservation;

import com.stockguard.common.config.StockGuardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs on every instance. No leader election is needed, because {@code SKIP LOCKED} keeps sweepers apart. */
@Component
@ConditionalOnProperty(prefix = "stockguard.reservation.expiry", name = "enabled", havingValue = "true")
class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);

    private final ReservationExpiryService expiry;
    private final int batchSize;

    ReservationExpiryJob(ReservationExpiryService expiry, StockGuardProperties properties) {
        this.expiry = expiry;
        this.batchSize = properties.reservation().expiry().batchSize();
    }

    @Scheduled(fixedDelayString = "${stockguard.reservation.expiry.sweep-interval}")
    void sweep() {
        int expired;
        int total = 0;
        do {
            expired = expiry.expireDueBatch(batchSize);
            total += expired;
        } while (expired == batchSize);
        if (total > 0) {
            log.info("Expired {} reservation(s)", total);
        }
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "stockguard.reservation.expiry", name = "enabled", havingValue = "true")
    static class SchedulingConfig {
    }
}
