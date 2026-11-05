package com.stockguard.inventory;

import com.stockguard.inventory.ConsistencyService.ConsistencyReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/consistency")
@Tag(name = "Consistency")
public class ConsistencyController {

    private final ConsistencyService consistency;

    ConsistencyController(ConsistencyService consistency) {
        this.consistency = consistency;
    }

    @GetMapping
    @Operation(operationId = "checkConsistency", summary = "Recompute stock invariants from the raw tables (admin)")
    public ConsistencyReport check(@RequestParam(required = false) UUID productId) {
        return consistency.check(productId);
    }
}
