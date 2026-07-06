package lib.core.clickhouse.query.builder;

import lib.core.clickhouse.query.ClickHouseQuery;
import lib.core.query.util.ColumnValidator;
import lib.core.query.util.ParameterNaming;

/**
 * Fluent builder for PREWHERE conditions (ClickHouse-specific optimization).
 * 
 * <p>PREWHERE is a ClickHouse optimization that filters rows BEFORE reading all columns,
 * significantly faster than WHERE for large tables.
 * 
 * <p>Usage:
 * <pre>{@code
 * ClickHouseQuery.select("*")
 *     .from("orders")
 *     .prewhere("tenant_id").eq(tenantId)  // Fast filter on indexed column
 *     .where("amount").gt(100)              // Additional filter after PREWHERE
 *     .query(jdbc, Order.class);
 * }</pre>
 * 
 * <p>Best practices:
 * <ul>
 *   <li>Use PREWHERE for high-cardinality columns (tenant_id, user_id)</li>
 *   <li>Use PREWHERE for columns in ORDER BY key</li>
 *   <li>PREWHERE filters ~90% of rows → WHERE filters remaining 10%</li>
 *   <li>Don't use PREWHERE for low-cardinality columns (status, type)</li>
 * </ul>
 * 
 * @param <T> the concrete query type (ClickHouseQuery)
 */
public final class PrewhereBuilder<T extends ClickHouseQuery> {
    private final T query;
    private final String column;

    public PrewhereBuilder(T query, String column) {
        this.query = query;
        this.column = ColumnValidator.validated(column);
    }

    /** {@code column = :param} — skipped when value is null or empty string. */
    public T eq(Object value) {
        if (value == null) return query;
        if (value instanceof String && ((String) value).isEmpty()) return query;
        String paramName = ParameterNaming.generate(column);
        query.prewhereClauses.add(column + " = :" + paramName);
        query.params.addValue(paramName, value);
        return query;
    }

    /** {@code column != :param} — skipped when value is null or empty string. */
    public T ne(Object value) {
        if (value == null) return query;
        if (value instanceof String && ((String) value).isEmpty()) return query;
        String paramName = ParameterNaming.generate(column, "Ne");
        query.prewhereClauses.add(column + " != :" + paramName);
        query.params.addValue(paramName, value);
        return query;
    }

    /** {@code column > :param} — skipped when value is null or empty string. */
    public T gt(Object value) {
        if (value == null) return query;
        if (value instanceof String && ((String) value).isEmpty()) return query;
        String paramName = ParameterNaming.generate(column, "Gt");
        query.prewhereClauses.add(column + " > :" + paramName);
        query.params.addValue(paramName, value);
        return query;
    }

    /** {@code column >= :param} — skipped when value is null or empty string. */
    public T gte(Object value) {
        if (value == null) return query;
        if (value instanceof String && ((String) value).isEmpty()) return query;
        String paramName = ParameterNaming.generate(column, "Gte");
        query.prewhereClauses.add(column + " >= :" + paramName);
        query.params.addValue(paramName, value);
        return query;
    }

    /** {@code column < :param} — skipped when value is null or empty string. */
    public T lt(Object value) {
        if (value == null) return query;
        if (value instanceof String && ((String) value).isEmpty()) return query;
        String paramName = ParameterNaming.generate(column, "Lt");
        query.prewhereClauses.add(column + " < :" + paramName);
        query.params.addValue(paramName, value);
        return query;
    }

    /** {@code column <= :param} — skipped when value is null or empty string. */
    public T lte(Object value) {
        if (value == null) return query;
        if (value instanceof String && ((String) value).isEmpty()) return query;
        String paramName = ParameterNaming.generate(column, "Lte");
        query.prewhereClauses.add(column + " <= :" + paramName);
        query.params.addValue(paramName, value);
        return query;
    }

    /**
     * IN clause with auto-expansion.
     * <p>Skipped when values is null or empty.
     */
    public <V> T in(java.util.Collection<V> values) {
        if (values == null || values.isEmpty()) return query;
        String prefix = ParameterNaming.toCamelCase(column);
        java.util.StringJoiner joiner = new java.util.StringJoiner(", ");
        int i = 0;
        for (V val : values) {
            String pName = prefix + i;
            joiner.add(":" + pName);
            query.params.addValue(pName, val);
            i++;
        }
        query.prewhereClauses.add(column + " IN (" + joiner + ")");
        return query;
    }

    /** {@code column IS NOT NULL} */
    public T isNotNull() {
        query.prewhereClauses.add(column + " IS NOT NULL");
        return query;
    }

    /** {@code column != ''} — ClickHouse String non-empty check */
    public T isNotEmpty() {
        query.prewhereClauses.add(column + " != ''");
        return query;
    }
}
