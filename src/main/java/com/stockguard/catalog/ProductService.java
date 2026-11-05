package com.stockguard.catalog;

import com.stockguard.audit.AuditEventType;
import com.stockguard.audit.AuditTrail;
import com.stockguard.common.error.ApiException;
import com.stockguard.common.error.ErrorCode;
import com.stockguard.inventory.StockLedger;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository products;
    private final StockLedger ledger;
    private final AuditTrail audit;

    ProductService(ProductRepository products, StockLedger ledger, AuditTrail audit) {
        this.products = products;
        this.ledger = ledger;
        this.audit = audit;
    }

    /** A product and its stock record are created together, so a product without a stock row cannot exist. */
    @Transactional
    public Product create(String sku, String name, String description, UUID actorId) {
        Product product = products.saveAndFlush(new Product(sku, name, description));
        ledger.open(product.getId());
        audit.record(AuditEventType.PRODUCT_CREATED, actorId, "PRODUCT", product.getId(), Map.of("sku", sku, "name", name));
        return product;
    }

    /**
     * @param expectedVersion the version the editor was looking at. Checking it here rejects edits based on
     *     old data; Hibernate's {@code WHERE version = ?} covers the race between this check and the commit.
     */
    @Transactional
    public Product update(UUID id, String name, String description, long expectedVersion, UUID actorId) {
        Product product = products.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product " + id + " does not exist."));
        if (product.getVersion() != expectedVersion) {
            throw new ApiException(ErrorCode.STALE_VERSION,
                    "Product is at version " + product.getVersion() + " but the update was based on version " + expectedVersion + ".");
        }
        product.rename(name, description);
        products.flush();
        audit.record(AuditEventType.PRODUCT_UPDATED, actorId, "PRODUCT", id, Map.of("name", name, "version", product.getVersion()));
        return product;
    }

    @Transactional(readOnly = true)
    public Product get(UUID id) {
        return products.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.PRODUCT_NOT_FOUND, "Product " + id + " does not exist."));
    }

    @Transactional(readOnly = true)
    public Page<Product> list(int page, int size) {
        return products.findAll(PageRequest.of(page, size, Sort.by("sku")));
    }
}
