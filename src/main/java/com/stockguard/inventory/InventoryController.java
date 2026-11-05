package com.stockguard.inventory;

import com.stockguard.common.web.PageResponse;
import com.stockguard.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products/{productId}/stock")
@Tag(name = "Stock")
public class InventoryController {

    private final InventoryService inventory;

    InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping
    @Operation(operationId = "getStock", summary = "Read total, available and reserved quantities")
    public StockResponse get(@PathVariable UUID productId) {
        return StockResponse.from(inventory.get(productId));
    }

    @PostMapping
    @Operation(operationId = "addStock", summary = "Receive stock (admin)")
    public StockCountsResponse add(
            @PathVariable UUID productId,
            @Valid @RequestBody AddStockRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        var after = inventory.addStock(productId, request.quantity(), Actor.from(jwt).userId(), request.note());
        return new StockCountsResponse(productId, after.getTotal(), after.getAvailable(), after.getReserved());
    }

    @GetMapping("/movements")
    @Operation(operationId = "listStockMovements", summary = "Browse the stock ledger, newest first (admin)")
    public PageResponse<MovementResponse> movements(
            @PathVariable UUID productId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(inventory.movements(productId, page, size), MovementResponse::from);
    }

    public record AddStockRequest(
            @NotNull @Min(1) @Max(1_000_000) Integer quantity,
            @Size(max = 200) String note) {
    }

    public record StockResponse(UUID productId, int total, int available, int reserved, Instant updatedAt) {

        public static StockResponse from(StockLevel level) {
            return new StockResponse(
                    level.getProductId(), level.getTotal(), level.getAvailable(), level.getReserved(), level.getUpdatedAt());
        }
    }

    public record StockCountsResponse(UUID productId, int total, int available, int reserved) {
    }

    public record MovementResponse(
            Long id,
            UUID productId,
            MovementType type,
            UUID reservationId,
            UUID actorId,
            int deltaTotal,
            int deltaAvailable,
            int deltaReserved,
            int totalAfter,
            int availableAfter,
            int reservedAfter,
            String note,
            Instant createdAt) {

        static MovementResponse from(StockMovement movement) {
            return new MovementResponse(
                    movement.getId(),
                    movement.getProductId(),
                    movement.getType(),
                    movement.getReservationId(),
                    movement.getActorId(),
                    movement.getDeltaTotal(),
                    movement.getDeltaAvailable(),
                    movement.getDeltaReserved(),
                    movement.getTotalAfter(),
                    movement.getAvailableAfter(),
                    movement.getReservedAfter(),
                    movement.getNote(),
                    movement.getCreatedAt());
        }
    }
}
