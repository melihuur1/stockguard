package com.stockguard.catalog;

import com.stockguard.common.web.PageResponse;
import com.stockguard.inventory.InventoryService;
import com.stockguard.inventory.StockLevel;
import com.stockguard.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products")
public class ProductController {

    private final ProductService products;
    private final InventoryService inventory;

    ProductController(ProductService products, InventoryService inventory) {
        this.products = products;
        this.inventory = inventory;
    }

    @PostMapping
    @Operation(operationId = "createProduct", summary = "Create a product with empty stock (admin)")
    public ResponseEntity<ProductResponse> create(
            @Valid @RequestBody CreateProductRequest request, @AuthenticationPrincipal Jwt jwt) {
        Product product = products.create(request.sku(), request.name(), request.description(), Actor.from(jwt).userId());
        return ResponseEntity.created(URI.create("/api/v1/products/" + product.getId()))
                .body(ProductResponse.of(product, inventory.get(product.getId())));
    }

    @PutMapping("/{id}")
    @Operation(operationId = "updateProduct", summary = "Update a product (admin). Fails with 409 if someone else changed it first.")
    public ProductResponse update(
            @PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request, @AuthenticationPrincipal Jwt jwt) {
        Product product = products.update(id, request.name(), request.description(), request.version(), Actor.from(jwt).userId());
        return ProductResponse.of(product, inventory.get(id));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getProduct", summary = "Read one product with its stock quantities")
    public ProductResponse get(@PathVariable UUID id) {
        return ProductResponse.of(products.get(id), inventory.get(id));
    }

    @GetMapping
    @Operation(operationId = "listProducts", summary = "List products with their stock quantities")
    public PageResponse<ProductResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Page<Product> result = products.list(page, size);
        Map<UUID, StockLevel> stock = inventory.getAll(result.map(Product::getId).getContent()).stream()
                .collect(Collectors.toMap(StockLevel::getProductId, Function.identity()));
        return PageResponse.of(result, product -> ProductResponse.of(product, stock.get(product.getId())));
    }

    public record CreateProductRequest(
            @NotBlank @Size(max = 64)
            @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]*$", message = "must contain only letters, digits, dots, underscores or hyphens")
            String sku,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description) {
    }

    public record UpdateProductRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotNull @Min(0) Long version) {
    }

    public record ProductResponse(
            UUID id,
            String sku,
            String name,
            String description,
            long version,
            int totalQuantity,
            int availableQuantity,
            int reservedQuantity,
            Instant createdAt,
            Instant updatedAt) {

        static ProductResponse of(Product product, StockLevel stock) {
            return new ProductResponse(
                    product.getId(),
                    product.getSku(),
                    product.getName(),
                    product.getDescription(),
                    product.getVersion(),
                    stock.getTotal(),
                    stock.getAvailable(),
                    stock.getReserved(),
                    product.getCreatedAt(),
                    product.getUpdatedAt());
        }
    }
}
