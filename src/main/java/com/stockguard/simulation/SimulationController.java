package com.stockguard.simulation;

import com.stockguard.security.Actor;
import com.stockguard.simulation.SimulationService.SimulationReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/simulations")
@ConditionalOnProperty(prefix = "stockguard.simulation", name = "enabled", havingValue = "true")
@Tag(name = "Simulation")
public class SimulationController {

    private final SimulationService simulation;

    SimulationController(SimulationService simulation) {
        this.simulation = simulation;
    }

    @PostMapping("/reservations")
    @Operation(operationId = "runReservationSimulation", summary = "Race real concurrent reservations against a fresh product and report the outcome (admin)")
    public SimulationReport run(@Valid @RequestBody SimulationRequest request, @AuthenticationPrincipal Jwt jwt) {
        return simulation.run(request.initialStock(), request.concurrentRequests(), request.quantityPerRequest(), Actor.from(jwt).userId());
    }

    public record SimulationRequest(
            @NotNull @Min(0) @Max(100_000) Integer initialStock,
            @NotNull @Min(1) @Max(1000) Integer concurrentRequests,
            @NotNull @Min(1) @Max(100) Integer quantityPerRequest) {
    }
}
