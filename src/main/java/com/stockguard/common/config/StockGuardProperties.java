package com.stockguard.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("stockguard")
public record StockGuardProperties(
        @NotBlank String instanceId,
        @Valid @NotNull Security security,
        @Valid @NotNull Reservation reservation,
        @Valid @NotNull Simulation simulation) {

    public record Security(
            @NotBlank @Size(min = 32, message = "must be at least 32 characters for HS256") String jwtSecret,
            @NotNull Duration tokenTtl,
            @Valid @NotNull Admin admin) {
    }

    /** Both blank means no administrator is seeded. */
    public record Admin(String username, String password) {
    }

    public record Reservation(@NotNull Duration defaultTtl, @Valid @NotNull Expiry expiry) {
    }

    public record Expiry(boolean enabled, @NotNull Duration sweepInterval, @Min(1) int batchSize) {
    }

    public record Simulation(boolean enabled, List<String> targets) {
    }
}
