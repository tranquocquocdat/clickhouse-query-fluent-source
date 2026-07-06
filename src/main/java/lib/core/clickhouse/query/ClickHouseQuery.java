package lib.core.clickhouse.query;


import lib.core.clickhouse.expression.CH;
import lib.core.clickhouse.query.builder.PrewhereBuilder;
import lib.core.query.util.StringUtils;
import lib.core.query.Alias;
import lib.core.query.BaseQuery;
import lib.core.query.Page;
import lib.core.query.builder.CTEBuilder;
import lib.core.query.builder.CountQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Fluent SELECT query builder for ClickHouse with <b>clause-order validation</b>.
 *
 * <p>The builder enforces correct SQL clause ordering at runtime:
 * <pre>
 * SELECT → FROM → JOIN → WHERE → GROUP_BY → HAVING → ORDER_BY → LIMIT
 * </pre>
 * Calling a method out of order throws {@link IllegalStateException} with a clear message.
 *
 * <h3>Basic Usage</h3>
 * <pre>{@code
 * import static lib.core.clickhouse.CH.*;
 *
 * List<Item> items = ClickHouseQuery
 *     .select(col("user_id"), sum("amount").as("total"))
 *     .from("orders")
 *     .where("tenant_id").eq(tenantId)
 *     .where("created_at").between(fromPeriod, toPeriod)
 *     .where("status").eqIfNotBlank(status)
 *     .where("product_id").in(productIds)
 *     .whereILike(keyword).on("session_id", "user_id")
 *     .groupBy("user_id")
 *     .having(sum("amount")).gt(100)
 *     .orderBy("total", SortOrder.DESC)
 *     .limit(10).offset(0)
 *     .query(namedJdbc, rowMapper);
 * }</pre>
 *
 * <h3>Subquery Count</h3>
 * <pre>{@code
 * long total = ClickHouseQuery
 *     .count(
 *         ClickHouseQuery.select("user_id", "session_id")
 *             .from("order_items")
 *             .where("created_at").between(from, to)
 *             .groupBy("user_id", "session_id")
 *     )
 *     .execute(namedJdbc);
 * }</pre>
 *
 * @see CH
 * @see ClickHouseInsert
 * @see lib.core.query.builder.WhereBuilder
 * @see lib.core.query.builder.JoinBuilder
 * @see lib.core.query.builder.HavingBuilder
 */
public final class ClickHouseQuery extends BaseQuery<ClickHouseQuery> {

    /** Default LIMIT applied when query() is called without an explicit limit. */
    public static final int DEFAULT_LIMIT = 1000;

    // ClickHouse-specific clauses
    public final List<String> prewhereClauses = new ArrayList<>();  // PREWHERE (optimization)
    private Double sampleRatio;                                      // SAMPLE 0.1
    private boolean useFinal;                                        // FINAL modifier
    
    // ClickHouse-specific GROUP BY modifiers
    private String groupByModifier;  // WITH TOTALS / WITH ROLLUP / WITH CUBE

    private ClickHouseQuery() {}

    // ── Factory ──────────────────────────────────────────────────────────

    /**
     * Start a SELECT query with the given columns/expressions.
     *
     * @param columns column names or expressions (e.g. {@code "user_id"}, {@code "sum(amount) AS total"})
     * @return a new query builder in the SELECT phase
     */
    public static ClickHouseQuery select(String... columns) {
        ClickHouseQuery q = new ClickHouseQuery();
        q.selectColumns.addAll(List.of(columns));
        return q;
    }

    /**
     * Start a SELECT query accepting {@link CH.Expr} or String values.
     */
    public static ClickHouseQuery select(Object... columns) {
        String[] strs = new String[columns.length];
        for (int i = 0; i < columns.length; i++) strs[i] = columns[i].toString();
        return select(strs);
    }

    /**
     * Start a SELECT DISTINCT query.
     *
     * @param columns column names or expressions
     * @return a new query builder in the SELECT phase with DISTINCT
     */
    public static ClickHouseQuery selectDistinct(String... columns) {
        ClickHouseQuery q = new ClickHouseQuery();
        q.distinct = true;
        q.selectColumns.addAll(List.of(columns));
        return q;
    }

    /**
     * Start a SELECT DISTINCT query accepting {@link CH.Expr} or String values.
     */
    public static ClickHouseQuery selectDistinct(Object... columns) {
        String[] strs = new String[columns.length];
        for (int i = 0; i < columns.length; i++) strs[i] = columns[i].toString();
        return selectDistinct(strs);
    }

    /** Start a raw SELECT query (e.g., building from scratch). */
    public static ClickHouseQuery raw() {
        return new ClickHouseQuery();
    }

    // ── WITH (CTE) ───────────────────────────────────────────────────────

    /**
     * Start building a query with a Common Table Expression (CTE).
     *
     * <pre>{@code
     * ClickHouseQuery
     *     .with("active_users",
     *         ClickHouseQuery.select("user_id").from("users").where("status").eq("ACTIVE"))
     *     .with("user_orders",
     *         ClickHouseQuery.select("user_id", "sum(amount) AS total")
     *             .from("orders").groupBy("user_id"))
     *     .select("au.user_id", "uo.total")
     *     .from("active_users au")
     *     .join("user_orders uo").on("uo.user_id", "au.user_id")
     * }</pre>
     *
     * @param name  the CTE name
     * @param query the CTE query
     * @return a {@link CTEBuilder} for chaining more CTEs or starting SELECT
     */
    public static CTEBuilder<ClickHouseQuery> with(String name, ClickHouseQuery query) {
        CTEBuilder<ClickHouseQuery> builder = new CTEBuilder<>(new CTEBuilder.QueryFactory<ClickHouseQuery>() {
            @Override
            public ClickHouseQuery select(String... columns) {
                return ClickHouseQuery.select(columns);
            }

            @Override
            public ClickHouseQuery selectDistinct(String... columns) {
                return ClickHouseQuery.selectDistinct(columns);
            }
        });
        builder.addCTE(name, query);
        return builder;
    }

    /**
     * Start a CTE (WITH clause) using an Alias as the CTE name.
     *
     * @param alias the Alias whose toString() provides the CTE name
     * @param query the CTE query
     * @return a {@link CTEBuilder} for chaining more CTEs or starting SELECT
     */
    public static CTEBuilder<ClickHouseQuery> with(Alias alias, ClickHouseQuery query) {
        return with(alias.toString(), query);
    }

    // ── ClickHouse-specific optimization clauses ────────────────────────

    /**
     * Start a PREWHERE clause (ClickHouse optimization).
     * 
     * <p>PREWHERE filters rows BEFORE reading all columns — significantly faster than WHERE
     * for large tables with many columns.
     * 
     * <pre>{@code
     * ClickHouseQuery.select("*")
     *     .from("orders")
     *     .prewhere("tenant_id").eq(tenantId)  // Fast filter on indexed column
     *     .where("amount").gt(100)              // Additional filter
     *     .query(jdbc, Order.class);
     * }</pre>
     * 
     * <p>Best practices:
     * <ul>
     *   <li>Use for high-cardinality columns (tenant_id, user_id)</li>
     *   <li>Use for columns in ORDER BY key</li>
     *   <li>PREWHERE should filter ~90% of rows</li>
     * </ul>
     * 
     * @param column the column name
     * @return a {@link PrewhereBuilder} for specifying the condition
     */
    public PrewhereBuilder<ClickHouseQuery> prewhere(String column) {
        advanceTo(Phase.WHERE);  // PREWHERE comes before WHERE
        return new PrewhereBuilder<>(this, column);
    }

    /**
     * PREWHERE with Expr support.
     */
    public PrewhereBuilder<ClickHouseQuery> prewhere(lib.core.query.expression.CommonFunctions.Expr column) {
        return prewhere(column.toString());
    }

    /**
     * Add SAMPLE clause for approximate queries (ClickHouse-specific).
     * 
     * <p>Samples a fraction of data for fast approximate analytics.
     * 
     * <pre>{@code
     * ClickHouseQuery.select("avg(amount)")
     *     .from("orders")
     *     .sample(0.1)  // Sample 10% of data
     *     .where("tenant_id").eq(tenantId)
     *     .queryOne(jdbc, Double.class);
     * }</pre>
     * 
     * @param ratio sampling ratio (0.0 to 1.0), e.g., 0.1 = 10%
     * @return this query builder
     * @throws IllegalArgumentException if ratio is not between 0 and 1
     */
    public ClickHouseQuery sample(double ratio) {
        if (ratio <= 0 || ratio > 1) {
            throw new IllegalArgumentException("Sample ratio must be between 0 and 1, got: " + ratio);
        }
        this.sampleRatio = ratio;
        return this;
    }

    /**
     * Add FINAL modifier (ClickHouse-specific).
     * 
     * <p>Forces final merge for ReplacingMergeTree, CollapsingMergeTree, etc.
     * Returns deduplicated/collapsed data.
     * 
     * <pre>{@code
     * ClickHouseQuery.select("*")
     *     .from("users")
     *     .final()  // Force deduplication
     *     .where("user_id").eq(userId)
     *     .queryOne(jdbc, User.class);
     * }</pre>
     * 
     * <p><b>Warning:</b> FINAL is expensive — only use when necessary.
     * 
     * @return this query builder
     */
    public ClickHouseQuery useFinal() {
        this.useFinal = true;
        return this;
    }

    // ── ClickHouse-specific GROUP BY methods ────────────────────────────

    /**
     * GROUP BY with TOTALS modifier — adds a summary row with totals.
     *
     * <pre>{@code
     * ClickHouseQuery.select("product_id", sum("amount").as("total"))
     *     .from("orders")
     *     .groupByWithTotals("product_id")
     *     .query(namedJdbc, Report.class);
     * // → GROUP BY product_id WITH TOTALS
     * }</pre>
     *
     * @param columns columns to group by
     * @return this query builder
     */
    public ClickHouseQuery groupByWithTotals(String... columns) {
        groupBy(columns);
        this.groupByModifier = "WITH TOTALS";
        return this;
    }

    /**
     * GROUP BY with TOTALS modifier (Expr overload).
     */
    public ClickHouseQuery groupByWithTotals(Object... columns) {
        groupBy(columns);
        this.groupByModifier = "WITH TOTALS";
        return this;
    }

    /**
     * GROUP BY with ROLLUP modifier — creates subtotals for hierarchical grouping.
     *
     * <pre>{@code
     * ClickHouseQuery.select("year", "month", sum("amount").as("total"))
     *     .from("orders")
     *     .groupByWithRollup("year", "month")
     *     .query(namedJdbc, Report.class);
     * // → GROUP BY year, month WITH ROLLUP
     * }</pre>
     *
     * @param columns columns to group by (order matters for hierarchy)
     * @return this query builder
     */
    public ClickHouseQuery groupByWithRollup(String... columns) {
        groupBy(columns);
        this.groupByModifier = "WITH ROLLUP";
        return this;
    }

    /**
     * GROUP BY with ROLLUP modifier (Expr overload).
     */
    public ClickHouseQuery groupByWithRollup(Object... columns) {
        groupBy(columns);
        this.groupByModifier = "WITH ROLLUP";
        return this;
    }

    /**
     * GROUP BY with CUBE modifier — creates subtotals for all combinations.
     *
     * <pre>{@code
     * ClickHouseQuery.select("region", "product", sum("amount").as("total"))
     *     .from("orders")
     *     .groupByWithCube("region", "product")
     *     .query(namedJdbc, Report.class);
     * // → GROUP BY region, product WITH CUBE
     * }</pre>
     *
     * @param columns columns to group by
     * @return this query builder
     */
    public ClickHouseQuery groupByWithCube(String... columns) {
        groupBy(columns);
        this.groupByModifier = "WITH CUBE";
        return this;
    }

    /**
     * GROUP BY with CUBE modifier (Expr overload).
     */
    public ClickHouseQuery groupByWithCube(Object... columns) {
        groupBy(columns);
        this.groupByModifier = "WITH CUBE";
        return this;
    }

    /**
     * Override to append ClickHouse-specific GROUP BY modifiers.
     */
    @Override
    protected String generateGroupByClause() {
        if (groupByColumns.isEmpty()) return "";
        String clause = "GROUP BY " + String.join(", ", groupByColumns);
        if (groupByModifier != null) {
            clause += " " + groupByModifier;
        }
        return clause;
    }

    // ── Execute (override to apply DEFAULT_LIMIT) ───────────────────────

    /**
     * Execute query and return list of mapped results.
     * <p>If no LIMIT was set, a default limit of {@value #DEFAULT_LIMIT} is applied
     * to prevent accidental full-table scans on large tables.
     */
    @Override
    public <R> List<R> query(NamedParameterJdbcTemplate jdbc, RowMapper<R> rowMapper) {
        if (limitVal == null && unionQueries.isEmpty()) {
            limit(DEFAULT_LIMIT);
        }
        String sql = toSql();
        logQuery(sql);
        return jdbc.query(sql, params, rowMapper);
    }

    @Override
    public <R> Page<R> queryPage(int page, int pageSize, NamedParameterJdbcTemplate jdbc, RowMapper<R> rowMapper) {
        logQuery(toSql());
        return super.queryPage(page, pageSize, jdbc, rowMapper);
    }

    // ── Static count factory ────────────────────────────────────────────

    /**
     * Static subquery-style count. Reads more like natural SQL:
     * {@code SELECT COUNT(*) FROM (subquery)}.
     *
     * <pre>{@code
     * long total = ClickHouseQuery
     *     .count(
     *         ClickHouseQuery.select("user_id", "session_id")
     *             .from("order_items")
     *             .where("created_at").between(from, to)
     *             .groupBy("user_id", "session_id")
     *     )
     *     .execute(namedJdbc);
     * }</pre>
     *
     * @param subQuery the inner query to count
     * @return a {@link CountQuery} that can be executed
     */
    public static CountQuery<ClickHouseQuery> count(ClickHouseQuery subQuery) {
        return new CountQuery<>(subQuery);
    }

    // ── Logging ──────────────────────────────────────────────────────────

    private static final Logger log = LoggerFactory.getLogger(ClickHouseQuery.class);

    /**
     * Log the generated SQL and parameters.
     * <ul>
     *   <li><b>DEBUG</b> — logs the SQL statement</li>
     *   <li><b>TRACE</b> — also logs the bound parameter values</li>
     * </ul>
     *
     * <p>Enable in your {@code application.yml}:
     * <pre>{@code
     * logging:
     *   level:
     *     lib.core.clickhouse.query.ClickHouseQuery: DEBUG   # SQL only
     *     lib.core.clickhouse.query.ClickHouseQuery: TRACE   # SQL + params
     * }</pre>
     */
    private void logQuery(String sql) {
        if (log.isDebugEnabled()) {
            String resolved = sql;
            java.util.Map<String, Object> values = params.getValues();
            // Sort by key length desc to avoid partial replacements (e.g. :id before :id_list)
            java.util.List<String> keys = new java.util.ArrayList<>(values.keySet());
            keys.sort((a, b) -> b.length() - a.length());
            for (String key : keys) {
                Object val = values.get(key);
                resolved = resolved.replace(":" + key, formatValue(val));
            }
            log.debug("\n╔══ ClickHouse Query ══════════════════════════════════════\n{}\n╚═════════════════════════════════════════════════════════", resolved);
        }
    }

    @SuppressWarnings("unchecked")
    private static String formatValue(Object val) {
        if (val == null) return "NULL";
        if (val instanceof java.util.Collection) {
            StringJoiner sj = new StringJoiner(", ", "(", ")");
            for (Object item : (java.util.Collection<?>) val) {
                sj.add(formatValue(item));
            }
            return sj.toString();
        }
        if (val instanceof Number) return val.toString();
        return "'" + val.toString().replace("'", "\\'") + "'";
    }

    // ── Override toSql to add ClickHouse-specific clauses ───────────────

    /**
     * Override to inject ClickHouse-specific clauses: PREWHERE, SAMPLE, FINAL.
     * 
     * <p>SQL order:
     * <pre>
     * SELECT ... FROM table [FINAL] [SAMPLE ratio] [PREWHERE ...] [WHERE ...] ...
     * </pre>
     */
    @Override
    public String toSql() {
        StringBuilder sql = new StringBuilder();

        // WITH (CTE)
        if (!cteList.isEmpty()) {
            sql.append("WITH ");
            for (int i = 0; i < cteList.size(); i++) {
                if (i > 0)
                    sql.append(",\n     ");
                String[] cte = cteList.get(i);
                sql.append(cte[0]).append(" AS (\n  ").append(cte[1]).append("\n)");
            }
            sql.append("\n");
        }

        // SELECT
        if (!selectColumns.isEmpty()) {
            sql.append(distinct ? "SELECT DISTINCT " : "SELECT ");
            sql.append(String.join(",\n       ", selectColumns));
        }

        // FROM
        if (fromSubQuery != null) {
            sql.append("\nFROM (\n  ").append(fromSubQuery.toSql()).append("\n) AS ").append(fromSubQueryAlias);
            fromSubQuery.params.getValues().forEach((k, v) -> params.addValue((String) k, v));
        } else if (tableName != null) {
            sql.append("\nFROM ").append(tableName);
        }

        // FINAL (ClickHouse-specific)
        if (useFinal) {
            sql.append(" FINAL");
        }

        // SAMPLE (ClickHouse-specific)
        if (sampleRatio != null) {
            sql.append(" SAMPLE ").append(sampleRatio);
        }

        // JOIN
        for (String join : joinClauses) {
            sql.append("\n").append(join);
        }

        // PREWHERE (ClickHouse-specific optimization)
        if (!prewhereClauses.isEmpty()) {
            sql.append("\nPREWHERE ").append(prewhereClauses.get(0));
            for (int i = 1; i < prewhereClauses.size(); i++) {
                sql.append("\n  AND ").append(prewhereClauses.get(i));
            }
        }

        // WHERE
        if (!whereClauses.isEmpty()) {
            sql.append("\nWHERE ").append(whereClauses.get(0));
            for (int i = 1; i < whereClauses.size(); i++) {
                sql.append("\n  AND ").append(whereClauses.get(i));
            }
        }

        // GROUP BY (via abstract method)
        String groupByClause = generateGroupByClause();
        if (!groupByClause.isEmpty()) {
            sql.append("\n").append(groupByClause);
        }

        // HAVING
        if (!havingClauses.isEmpty()) {
            sql.append("\nHAVING ").append(havingClauses.get(0));
            for (int i = 1; i < havingClauses.size(); i++) {
                sql.append("\n  AND ").append(havingClauses.get(i));
            }
        }

        // UNION ALL
        for (BaseQuery<?> union : unionQueries) {
            sql.append("\nUNION ALL\n").append(union.toSql());
            union.params.getValues().forEach((k, v) -> params.addValue((String) k, v));
        }

        // ORDER BY
        if (!orderByClauses.isEmpty()) {
            sql.append("\nORDER BY ").append(String.join(", ", orderByClauses));
        }

        // LIMIT / OFFSET
        if (limitVal != null) {
            sql.append("\nLIMIT :_limit");
        }
        if (offsetVal != null) {
            sql.append(" OFFSET :_offset");
        }

        return sql.toString();
    }

    // ── Utilities ────────────────────────────────────────────────────────

    /** Convert snake_case to camelCase for parameter naming. */
    public static String toCamelCase(String snake) {
        return StringUtils.toCamelCase(snake);
    }
}
