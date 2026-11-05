package com.stockguard.inventory;

import com.stockguard.audit.AuditEventType;
import com.stockguard.audit.AuditTrail;
import com.stockguard.common.error.ApiException;
import com.stockguard.common.error.ErrorCode;
import com.stockguard.inventory.StockLevelRepository.StockCounts;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

    private final StockLevelRepository levels;
    private final StockMovementRepository movements;
    private final StockLedger ledger;
    private final AuditTrail audit;

    InventoryService(StockLevelRepository levels, StockMovementRepository movements, StockLedger ledger, AuditTrail audit) {
        this.levels = levels;
        this.movements = movements;
        this.ledger = ledger;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public StockLevel get(UUID productId) {
        return levels.findById(productId).orElseThrow(() -> notFound(productId));
    }

    @Transactional(readOnly = true)
    public List<StockLevel> getAll(Collection<UUID> productIds) {
        return levels.findByProductIdIn(productIds);
    }

    @Transactional(readOnly = true)
    public boolean productExists(UUID productId) {
        return levels.existsById(productId);
    }

    @Transactional
    public StockCounts addStock(UUID productId, int quantity, UUID actorId, String note) {
        StockCounts after = ledger.receive(productId, quantity, actorId, note).orElseThrow(() -> notFound(productId));
        audit.record(AuditEventType.STOCK_RECEIVED, actorId, "PRODUCT", productId, Map.of("quantity", quantity));
        return after;
    }

    @Transactional(readOnly = true)
    public Page<StockMovement> movements(UUID productId, int page, int size) {
        if (!levels.existsById(productId)) {
            throw notFound(productId);
        }
        return movements.findByProductId(productId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
    }

    private static ApiException notFound(UUID productId) {
        return new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product " + productId + " does not exist.");
    }
}
