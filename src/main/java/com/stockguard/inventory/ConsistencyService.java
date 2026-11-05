package com.stockguard.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes the invariants from the raw tables, independently of the code paths that wrote them. The tests,
 * the simulation and the admin endpoint all rely on it, so {@code ConsistencyVerifierIT} proves that it
 * really detects corruption.
 *
 * <p>REPEATABLE READ gives every rule the same snapshot, so a report is correct while writes keep flowing:
 * an in-flight transaction is either fully visible or not visible at all.
 */
@Service
public class ConsistencyService {

    private static final String IN_SCOPE = "(CAST(:productId AS uuid) IS NULL OR s.product_id = CAST(:productId AS uuid))";

    private static final List<Rule> RULES = List.of(
            new Rule("NEGATIVE_STOCK", """
                    SELECT s.product_id, format('total=%s available=%s reserved=%s',
                           s.total_quantity, s.available_quantity, s.reserved_quantity) AS detail
                      FROM stock_levels s
                     WHERE $SCOPE AND (s.total_quantity < 0 OR s.available_quantity < 0 OR s.reserved_quantity < 0)
                    """),
            new Rule("TOTAL_IS_NOT_AVAILABLE_PLUS_RESERVED", """
                    SELECT s.product_id, format('total=%s available=%s reserved=%s',
                           s.total_quantity, s.available_quantity, s.reserved_quantity) AS detail
                      FROM stock_levels s
                     WHERE $SCOPE AND s.total_quantity <> s.available_quantity + s.reserved_quantity
                    """),
            new Rule("RESERVED_DOES_NOT_MATCH_PENDING_RESERVATIONS", """
                    SELECT s.product_id, format('reserved=%s but pending reservations hold %s',
                           s.reserved_quantity, COALESCE(p.held, 0)) AS detail
                      FROM stock_levels s
                      LEFT JOIN (SELECT product_id, SUM(quantity) AS held FROM reservations
                                  WHERE status = 'PENDING' GROUP BY product_id) p USING (product_id)
                     WHERE $SCOPE AND s.reserved_quantity <> COALESCE(p.held, 0)
                    """),
            new Rule("OVERSOLD", """
                    SELECT s.product_id, format('%s unit(s) held or sold but only %s ever received',
                           COALESCE(h.held, 0), COALESCE(m.received, 0)) AS detail
                      FROM stock_levels s
                      LEFT JOIN (SELECT product_id, SUM(quantity) AS held FROM reservations
                                  WHERE status IN ('PENDING', 'CONFIRMED') GROUP BY product_id) h USING (product_id)
                      LEFT JOIN (SELECT product_id, SUM(delta_total) AS received FROM stock_movements
                                  WHERE type = 'STOCK_IN' GROUP BY product_id) m USING (product_id)
                     WHERE $SCOPE AND COALESCE(h.held, 0) > COALESCE(m.received, 0)
                    """),
            new Rule("COUNTERS_DO_NOT_MATCH_LEDGER", """
                    SELECT s.product_id, format('counters=(%s,%s,%s) ledger=(%s,%s,%s)',
                           s.total_quantity, s.available_quantity, s.reserved_quantity,
                           COALESCE(m.t, 0), COALESCE(m.a, 0), COALESCE(m.r, 0)) AS detail
                      FROM stock_levels s
                      LEFT JOIN (SELECT product_id, SUM(delta_total) AS t, SUM(delta_available) AS a,
                                        SUM(delta_reserved) AS r
                                   FROM stock_movements GROUP BY product_id) m USING (product_id)
                     WHERE $SCOPE AND (s.total_quantity, s.available_quantity, s.reserved_quantity)
                                  IS DISTINCT FROM (COALESCE(m.t, 0), COALESCE(m.a, 0), COALESCE(m.r, 0))
                    """),
            new Rule("LEDGER_CHAIN_BROKEN", """
                    SELECT c.product_id, format('movement %s does not follow its predecessor', c.id) AS detail
                      FROM (SELECT m.*,
                                   LAG(total_after, 1, 0) OVER w AS prev_total,
                                   LAG(available_after, 1, 0) OVER w AS prev_available,
                                   LAG(reserved_after, 1, 0) OVER w AS prev_reserved
                              FROM stock_movements m
                            WINDOW w AS (PARTITION BY product_id ORDER BY id)) c
                      JOIN stock_levels s ON s.product_id = c.product_id
                     WHERE $SCOPE AND (c.prev_total + c.delta_total <> c.total_after
                                OR c.prev_available + c.delta_available <> c.available_after
                                OR c.prev_reserved + c.delta_reserved <> c.reserved_after)
                    """),
            new Rule("RESERVATION_NOT_SETTLED_EXACTLY_ONCE", """
                    SELECT r.product_id, format('reservation %s is %s with reserved=%s confirmed=%s cancelled=%s expired=%s',
                           r.id, r.status, c.reserved, c.confirmed, c.cancelled, c.expired) AS detail
                      FROM reservations r
                      JOIN stock_levels s ON s.product_id = r.product_id
                      CROSS JOIN LATERAL (
                           SELECT COUNT(*) FILTER (WHERE m.type = 'RESERVED') AS reserved,
                                  COUNT(*) FILTER (WHERE m.type = 'CONFIRMED') AS confirmed,
                                  COUNT(*) FILTER (WHERE m.type = 'CANCEL_RELEASED') AS cancelled,
                                  COUNT(*) FILTER (WHERE m.type = 'EXPIRY_RELEASED') AS expired
                             FROM stock_movements m WHERE m.reservation_id = r.id) c
                     WHERE $SCOPE AND (c.reserved <> 1
                                OR c.confirmed <> CASE WHEN r.status = 'CONFIRMED' THEN 1 ELSE 0 END
                                OR c.cancelled <> CASE WHEN r.status = 'CANCELLED' THEN 1 ELSE 0 END
                                OR c.expired <> CASE WHEN r.status = 'EXPIRED' THEN 1 ELSE 0 END)
                    """));

    private final JdbcClient jdbc;

    ConsistencyService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param productId the product to check, or null for all products */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConsistencyReport check(UUID productId) {
        List<Violation> violations = new ArrayList<>();
        for (Rule rule : RULES) {
            violations.addAll(jdbc.sql(rule.sql().replace("$SCOPE", IN_SCOPE))
                    .param("productId", productId)
                    .query((row, index) -> new Violation(
                            rule.name(), row.getObject("product_id", UUID.class), row.getString("detail")))
                    .list());
        }
        long productsChecked = jdbc.sql("SELECT COUNT(*) FROM stock_levels s WHERE " + IN_SCOPE)
                .param("productId", productId)
                .query(Long.class)
                .single();
        return new ConsistencyReport(violations.isEmpty(), productsChecked, RULES.stream().map(Rule::name).toList(), violations);
    }

    public record ConsistencyReport(boolean consistent, long productsChecked, List<String> rulesEvaluated, List<Violation> violations) {
    }

    public record Violation(String rule, UUID productId, String detail) {
    }

    private record Rule(String name, String sql) {
    }
}
