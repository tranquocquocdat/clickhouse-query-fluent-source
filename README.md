<![CDATA[<div align="center">

# ClickHouse Query Builder

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Spring](https://img.shields.io/badge/Spring_JDBC-6.x-brightgreen.svg)](https://spring.io/)

**Fluent Java DSL for building type-safe ClickHouse queries with Spring `NamedParameterJdbcTemplate`.**

Zero code-gen · Zero config · Null-safe · Auto DTO mapping · Fully type-safe `Expr` column references.

</div>

---

## Table of Contents

- [⚡ Best Practice — Real-World Example](#-best-practice--real-world-example)
- [Features](#features)
- [⚡ Performance](#-performance)
- [Installation](#installation)
- [Query Examples](#query-examples)
  - [1. Basic SELECT](#1-basic-select)
  - [2. Type-Safe Alias](#2-type-safe-alias)
  - [3. Fluent JOIN](#3-fluent-join)
  - [4. WHERE Operators](#4-where-operators)
  - [5. Fluent OR Conditions (`whereOr`)](#5-fluent-or-conditions-whereor)
  - [6. LIKE / ILIKE Search](#6-like--ilike-search)
  - [7. Subquery (IN / NOT IN)](#7-subquery-in--not-in)
  - [8. CASE WHEN](#8-case-when)
  - [9. ClickHouse String Type (`isEmpty` / `isNotEmpty`)](#9-clickhouse-string-type-isempty--isnotempty)
  - [10. Fluent Custom Logic (`apply`)](#10-fluent-custom-logic-apply)
  - [11. Fluent Arithmetic](#11-fluent-arithmetic-minus--plus)
  - [12. Multi-Column `countDistinct`](#12-multi-column-countdistinct)
  - [13. Expression Builder & Conditional Aggregates](#13-expression-builder--conditional-aggregates)
  - [14. HAVING with Aggregates](#14-having-with-aggregates)
  - [15. Subquery FROM](#15-subquery-from)
  - [16. UNION ALL](#16-union-all)
  - [17. WITH (CTE)](#17-with-cte--common-table-expressions)
  - [18. Subquery Count](#18-subquery-count)
  - [19. Single-Query Pagination (`queryPage`)](#19-single-query-pagination-querypage)
  - [20. Streaming — Large Export / SSE](#20-streaming--large-export--sse)
  - [21. Auto Caching (Redis / Caffeine)](#21-auto-caching-redis--caffeine)
  - [22. Auto DTO Mapping](#22-auto-dto-mapping)
  - [23. Default LIMIT (Safety Guard)](#23-default-limit-safety-guard)
  - [24. Window Functions](#24-window-functions)
  - [25. GROUP BY Modifiers](#25-group-by-modifiers)
  - [26. INSERT](#26-insert)
- [Validations & Safety](#validations--safety)
- [Observability (Logging & Metrics)](#observability-logging--metrics)
- [Clause-Order Validation](#clause-order-validation)
- [API Reference](#api-reference)
  - [CH Expressions](#ch-expressions)
  - [CASE WHEN Operators](#case-when-operators)
  - [ClickHouseQuery Builder](#clickhousequery-select-builder)
  - [CHParams](#chparams-insert-parameter-builder)
- [Comparison with Other Libraries](#-comparison-with-other-libraries)
- [Feature Coverage](#-feature-coverage)
- [Roadmap](#-roadmap)
- [Changelog](#changelog)

---

## ⚡ Best Practice — Real-World Example

> **Use case:** E-commerce analytics dashboard — covers **every** library feature in one coherent example.

```java
import static lib.core.clickhouse.expression.CH.*;
import lib.core.clickhouse.insert.ClickHouseInsert;
import lib.core.clickhouse.query.*;
import lib.core.clickhouse.util.CHParams;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

// ══════════════════════════════════════════════════════════════════
// 1. TYPE-SAFE ALIASES  —  no more "t." string literals
// ══════════════════════════════════════════════════════════════════
Alias oi  = Alias.of("order_items").as("oi");          // main table
Alias p   = Alias.of("products").as("p");              // INNER JOIN
Alias u   = Alias.of("users").as("u");                 // LEFT JOIN
Alias cfg = Alias.of("product_config").as("cfg");      // RIGHT JOIN
Alias rb  = Alias.of("return_buckets").as("rb");       // FULL OUTER JOIN
Alias sub = Alias.of("user_summary").as("us");         // subquery alias

// ══════════════════════════════════════════════════════════════════
// 2. CTE (WITH) + ALL 4 JOIN TYPES + EVERY SELECT FEATURE
// ══════════════════════════════════════════════════════════════════
Page<SalesReport> report = ClickHouseQuery

    // ── WITH (CTE) ─────────────────────────────────────────────
    .with("active_products",
        ClickHouseQuery.select("product_id")
            .from("product_config")
            .where("status").eq("ACTIVE")
            .where("tenant_id").eq(tenantId)
    )

    // ── SELECT: aggregates + arithmetic + conditional aggs + CASE WHEN
    .select(
        // Plain columns via Alias
        oi.col("tenant_id"),
        p.col("product_name"),
        u.col("currency"),

        // Basic aggregates
        oi.sum("revenue").as("total_revenue"),
        oi.sum("cost").as("total_cost"),
        count().as("total_orders"),
        avg("revenue").as("avg_revenue"),
        min("revenue").as("min_revenue"),
        max("revenue").as("max_revenue"),

        // Multi-column countDistinct
        countDistinct(oi.col("user_id")).as("unique_users"),
        countDistinct(oi.col("user_id"), oi.col("session_id")).as("unique_sessions"),

        // Arithmetic: minus / plus / multiply / divide
        oi.sum("revenue").minus(oi.sum("cost")).as("net_profit"),
        oi.sum("revenue").minus(oi.sum("cost"))
            .divide(oi.sum("revenue")).multiply("100").as("margin_pct"),
        oi.sum("debit").plus(oi.sum("credit")).as("total_flow"),

        // Conditional aggregates — fluent
        sumIf("revenue").where("action").eq("SALE").as("actual_sales"),
        countIf("user_id").where("is_promotion").eq(1).as("promo_orders"),
        avgIf("revenue").where("status").eq("COMPLETED").as("avg_completed_revenue"),
        minIf("cost").where("action").eq("PURCHASE").as("min_cost"),
        maxIf("revenue").where("tier").in("GOLD", "PLATINUM").as("max_premium_revenue"),

        // Conditional aggregates — raw
        sumIfRaw("refund_amount", "action = 'REFUND' AND refund_amount > 0").as("total_refund"),
        countIfRaw("user_id", in("status", "CANCELLED", "ERROR")).as("failed_orders"),

        // CASE WHEN: string results, numeric results, between, in, isNull, raw, thenRaw, orElseRaw, end
        caseWhen("net_profit").gt(0).then("PROFITABLE")
            .when("net_profit").eq(0).then("BREAK_EVEN")
            .orElse("LOSS").as("profitability"),

        caseWhen("revenue").between(0, 10).then("MICRO")
            .when("revenue").between(10, 100).then("SMALL")
            .when("revenue").between(100, 1000).then("MEDIUM")
            .orElse("ENTERPRISE").as("order_tier"),

        caseWhen("currency").in("USD", "EUR", "GBP").then("FIAT")
            .orElse("CRYPTO").as("currency_group"),

        caseWhen("coupon_code").isNull().then("NO_COUPON")
            .orElseRaw("coupon_code").as("effective_coupon"),

        caseWhen("action").eq("REFUND").thenRaw("revenue")
            .orElseRaw("revenue * -1").as("adjusted_amount"),

        caseWhen("error_code").isNotNull().then("HAS_ERROR")
            .end().as("error_flag")   // no ELSE
    )

    // ── FROM + 4 JOIN types ────────────────────────────────────
    .from("active_products")                                                    // FROM (CTE)
    .join(oi).on(oi.col("product_id"), "active_products.product_id")           // INNER JOIN
    .leftJoin(u).on(u.col("id"), oi.col("user_id"))                           // LEFT JOIN
    .rightJoin(cfg).on(cfg.col("product_id"), oi.col("product_id"))           // RIGHT JOIN
    .fullJoin(rb).on(rb.col("session_id"), oi.col("session_id"))              // FULL OUTER JOIN

    // ── WHERE: every operator ─────────────────────────────────
    .where(oi.col("tenant_id")).eq(tenantId)              // eq (null-safe)
    .where(oi.col("status")).ne("CANCELLED")              // ne
    .where(oi.col("revenue")).gt(0)                       // gt
    .where(oi.col("revenue")).gte(minRevenue)             // gte — skipped if null
    .where(oi.col("revenue")).lt(maxRevenue)              // lt  — skipped if null
    .where(oi.col("line_number")).lte(9999)               // lte
    .where(oi.col("created_at")).between(fromDate, toDate)  // between(Instant)
    .where(oi.col("processing_ms")).between(100, 30_000)    // between(Number)
    .where(oi.col("product_id")).in(productIds)           // in(Collection) — skipped if empty
    .where(oi.col("user_id")).notIn(blockedUsers)         // notIn(Collection)
    .where(oi.col("completed_at")).isNotNull()            // isNotNull
    .where(oi.col("error_code")).isNull()                 // isNull
    .where(u.col("session_id")).isEmpty()                 // ClickHouse String = '' (LEFT JOIN miss)
    .where(u.col("session_id")).isNotEmpty()              // ClickHouse String != ''
    .where(p.col("category")).eqIfNotBlank(categoryFilter)  // eqIfNotBlank
    .where(oi.col("is_sample")).eqIf(sampleOnly, 1)         // eqIf
    .where(u.col("id")).in(                               // in(subquery)
        ClickHouseQuery.select("user_id").from("premium_list")
            .where("tenant_id").eq(tenantId)
    )
    .where(u.col("id")).notIn(                            // notIn(subquery)
        ClickHouseQuery.select("user_id").from("blocked_users")
            .where("active").eq(1)
    )
    .whereRaw("toYYYYMM(oi.created_at) = toYYYYMM(now())")  // whereRaw
    .whereILike(keyword).on(u.col("username"), oi.col("session_id"))   // ILIKE multi-col
    .whereLike(sessionPrefix).onPrefix(oi.col("session_id"))           // LIKE prefix (index-friendly)
    .whereOr(or -> or                                     // OR group — all operators
        .where(oi.col("action")).eq("MANUAL_CREDIT")
        .where(oi.col("status")).ne("VOID")
        .where(oi.col("revenue")).gt(10_000)
        .where(oi.col("cost")).gte(50_000)
        .where(oi.col("latency_ms")).lt(500)
        .where(oi.col("latency_ms")).lte(1_000)
        .where(oi.col("product_id")).in(List.of("p1", "p2"))
        .where(oi.col("user_id")).notIn(premiumExclusions)
        .where(oi.col("coupon_code")).isNull()
        .where(oi.col("discount_id")).isNotNull()
        .where(oi.col("revenue")).between(500, 5_000)     // between inside OR
        .whereILike(keyword).on("session_id", "user_id")  // ILIKE inside OR
        .addRaw("oi.is_featured = 1")                     // raw inside OR
    )

    // ── GROUP BY + HAVING ─────────────────────────────────────
    .groupBy(oi.col("tenant_id"), p.col("product_name"), u.col("currency"))
    .having(sum("revenue")).gt(1_000)
    .having(count()).gte(10)
    .having(avg("revenue")).between(5, 50_000)
    .havingRaw("sum(cost) < sum(revenue) * 0.99")

    // ── ORDER BY (multiple columns) ───────────────────────────
    .orderBy("total_revenue", SortOrder.DESC)
    .orderBy(p.col("product_name"), SortOrder.ASC)

    // ── PAGINATED EXECUTION: data + total count in ONE query ──
    .queryPage(page, pageSize, namedJdbc, SalesReport.class);

// Page result
List<SalesReport> data = report.getData();
long total                = report.getTotal();
int  totalPages           = report.getTotalPages();
boolean hasNext           = report.hasNext();
boolean hasPrev           = report.hasPrevious();


// ══════════════════════════════════════════════════════════════════
// 3. SELECT DISTINCT
// ══════════════════════════════════════════════════════════════════
List<String> currencies = ClickHouseQuery
    .selectDistinct("currency")
    .from("order_items")
    .where("tenant_id").eq(tenantId)
    .query(namedJdbc, String.class);


// ══════════════════════════════════════════════════════════════════
// 4. UNION ALL — combine partitioned tables
// ══════════════════════════════════════════════════════════════════
List<UserSummary> history = ClickHouseQuery
    .select("user_id", sum("revenue").as("total"))
    .from("order_items_2024").where("tenant_id").eq(tenantId).groupBy("user_id")
    .unionAll(
        ClickHouseQuery.select("user_id", sum("revenue").as("total"))
            .from("order_items_2025").where("tenant_id").eq(tenantId).groupBy("user_id")
    )
    .orderBy("total", SortOrder.DESC)
    .limit(100)
    .query(namedJdbc, UserSummary.class);


// ══════════════════════════════════════════════════════════════════
// 5. SUBQUERY FROM — derived table
// ══════════════════════════════════════════════════════════════════
List<UserRankRow> ranked = ClickHouseQuery
    .select(sub.col("user_id"), sub.col("total"),
            "rank() OVER (ORDER BY total DESC) AS rank")
    .from(
        ClickHouseQuery.select("user_id", sum("revenue").as("total"))
            .from("order_items").where("tenant_id").eq(tenantId)
            .groupBy("user_id"),
        sub
    )
    .where(sub.col("total")).gt(minTotal)
    .orderBy("rank", SortOrder.ASC)
    .limit(50)
    .query(namedJdbc, UserRankRow.class);


// ══════════════════════════════════════════════════════════════════
// 6. queryOne (single row) + terminal count + subquery count
// ══════════════════════════════════════════════════════════════════
// Single DTO (returns null if no rows)
DailySummary today = ClickHouseQuery
    .select(sum("revenue").as("total_revenue"), count().as("total_orders"))
    .from("order_items")
    .where("tenant_id").eq(tenantId)
    .where("created_at").between(Instant.now().minus(1, ChronoUnit.DAYS), Instant.now())
    .queryOne(namedJdbc, DailySummary.class);

// Terminal .count() on the query itself
long totalRows = ClickHouseQuery.select("1")
    .from("order_items")
    .where("tenant_id").eq(tenantId)
    .count(namedJdbc);

// Static ClickHouseQuery.count(subQuery)
long distinctSessions = ClickHouseQuery.count(
    ClickHouseQuery.select("user_id", "session_id")
        .from("order_items").where("tenant_id").eq(tenantId)
        .groupBy("user_id", "session_id")
).execute(namedJdbc);


// ══════════════════════════════════════════════════════════════════
// 7. INSERT batch with CHParams — every param type
// ══════════════════════════════════════════════════════════════════
ClickHouseInsert.into("order_items")
    .columns("id", "tenant_id", "user_id", "product_id", "action",
             "revenue", "cost", "status", "currency",
             "tags", "created_at", "session_id", "coupon_code")
    .executeBatch(namedJdbc, incomingOrders, tx -> CHParams.of()
        .set("id",           tx.getId())                               // set (any value)
        .set("tenantId",     tx.getTenantId())
        .set("userId",       tx.getUserId())
        .set("productId",    tx.getProductId())
        .setEnum("action",   tx.getAction())                          // Enum → name()
        .setOrDefault("revenue", tx.getRevenue(), BigDecimal.ZERO)    // null-safe default
        .setOrDefault("cost", tx.getCost(), BigDecimal.ZERO)
        .setEnum("status",   tx.getStatus())
        .set("currency",     tx.getCurrency())
        .setArray("tags",    tx.getTags(), String.class)              // List → SQL ARRAY
        .setTimestamp("createdAt", tx.getCreatedAt())                 // Instant → Timestamp
        .set("sessionId",    tx.getSessionId())
        .setIfNotNull("couponCode", tx.getCouponCode())               // skip if null (dynamic columns only)
        .build()
    );


// ══════════════════════════════════════════════════════════════════
// 8. AUTO CACHE (Redis / Caffeine) — check cache before hitting DB
// ══════════════════════════════════════════════════════════════════
// First call  (~800ms): cache MISS → query ClickHouse → save to Redis
// Subsequent  (~2ms):   cache HIT  → return from Redis, no DB call
// On Redis failure:     silent fallback → query ClickHouse normally

Page<SalesReport> cachedReport = ClickHouseQuery
    .select(oi.col("tenant_id"), sum("revenue").as("total_revenue"))
    .from("order_items")
    .where(oi.col("tenant_id")).eq(tenantId)
    .where(oi.col("created_at")).between(fromDate, toDate)
    .groupBy(oi.col("tenant_id"))
    .orderBy("total_revenue", SortOrder.DESC)
    // Cache key auto-generated via MD5(SQL + params), TTL = 30 min
    .cached(redisCacheManager, Duration.ofMinutes(30))
    .queryPage(page, pageSize, namedJdbc, SalesReport.class);


// ══════════════════════════════════════════════════════════════════
// 9. STREAMING — O(1) memory, pipeline direct to HTTP client
// ══════════════════════════════════════════════════════════════════

// Style A: stream() → CSV export (memory O(1), 1 row at a time)
@GetMapping("/export")
public void exportCsv(HttpServletResponse response) throws IOException {
    response.setContentType("text/csv");
    response.setHeader("Content-Disposition", "attachment; filename=export.csv");
    PrintWriter writer = response.getWriter();
    writer.println("tenant_id,revenue,created_at");

    ClickHouseQuery.select(oi.col("tenant_id"), oi.col("revenue"), oi.col("created_at"))
        .from("order_items")
        .where(oi.col("tenant_id")).eq(tenantId)
        .stream(namedJdbc, rs -> {
            writer.println(
                rs.getString("tenant_id") + "," +
                rs.getBigDecimal("revenue") + "," +
                rs.getString("created_at")
            );
            // each row flushed to browser over TCP immediately
        });
}
// FE: window.location.href = '/api/export?tenantId=...'

// Style B: streamBatch() → SSE, push N rows at a time (memory O(batchSize))
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamDashboard() {
    SseEmitter emitter = new SseEmitter();
    executor.submit(() -> {
        ClickHouseQuery.select(oi.col("tenant_id"), sum("revenue").as("total"))
            .from("order_items")
            .where(oi.col("tenant_id")).eq(tenantId)
            .groupBy(oi.col("tenant_id"))
            .streamBatch(namedJdbc, SalesReport.class, 10, batch -> {
                emitter.send(batch);   // push List<SalesReport> as JSON
            });
        emitter.complete();
    });
    return emitter;
}
// FE: const es = new EventSource('/api/stream'); es.onmessage = e => render(JSON.parse(e.data));
```

> **Every feature covered:** SELECT DISTINCT, type-safe `Alias`, CTE (`WITH`), all 4 JOINs (INNER/LEFT/RIGHT/FULL OUTER), every WHERE operator (eq/ne/gt/gte/lt/lte/in/notIn/isNull/isNotNull/**isEmpty/isNotEmpty**/between/eqIfNotBlank/eqIf/in(subquery)/notIn(subquery)/whereRaw/whereILike/whereLike.onPrefix), `whereOr` (all 13 operators), CASE WHEN (all operators incl. **isEmpty/isNotEmpty**, thenRaw, orElseRaw, end), **`apply()` custom logic injection**, all 5 aggregates + 5 conditional aggregates (fluent & raw), arithmetic chain (minus/plus/multiply/divide), multi-column `countDistinct`, HAVING + `havingRaw`, multi-column ORDER BY, UNION ALL, subquery FROM, `queryPage`/`queryOne`/terminal `.count()`/subquery count, INSERT batch (`set`/`setOrDefault`/`setIfNotNull`/`setEnum`/`setTimestamp`/`setArray`), **Auto Cache** `.cached(manager, Duration)`, **Streaming** `.stream(handler)` + `.streamBatch(type, batchSize, consumer)`.


---

## Features

| # | Feature | Highlight |
|---|---|---|
| 1 | **Fluent API** | Chainable methods that read like SQL |
| 2 | **Clause-order validation** | Runtime enforcement: `SELECT → FROM → JOIN → WHERE → GROUP_BY → HAVING → ORDER_BY → LIMIT` |
| 3 | **Null-safe WHERE** | All operators skip clause when value is `null` — no manual null checks |
| 4 | **Auto DTO mapping** | `.query(jdbc, MyDto.class)` maps `snake_case` → `camelCase` automatically |
| 5 | **Single-query pagination** | `queryPage()` → `Page<T>` with data + total count in **one query** |
| 6 | **Default LIMIT** | Auto `LIMIT 1000` safety guard when no explicit limit is set |
| 7 | **Fluent JOIN** | `.join(alias).on(...)` / `.leftJoin()` / `.rightJoin()` / `.fullJoin()` |
| 8 | **Fluent OR** | `.whereOr(or -> or.where("col").eq(v).where("col2").gt(n))` — 13 operators |
| 9 | **LIKE / ILIKE** | `.whereILike(kw).on("col1", "col2")` / `.onPrefix("col")` |
| 10 | **Conditional filters** | `.eqIfNotBlank()`, `.eqIf()` skip when value is empty |
| 11 | **CASE WHEN** | `caseWhen("col").gt(0).then("HIGH").orElse("LOW").as("level")` |
| 12 | **Expression builder** | `CH.sum()`, `CH.count()`, `CH.avg()`, `CH.min()`, `CH.max()` |
| 13 | **Conditional aggregates** | `CH.sumIf()`, `CH.countIf()`, `CH.avgIf()`, `CH.minIf()`, `CH.maxIf()` |
| 14 | **Fluent arithmetic** | `sum("revenue").minus(sum("cost")).divide(100)` / `.plus()` / `.multiply()` |
| 15 | **Multi-column countDistinct** | `countDistinct(col1, col2).as("unique")` → `count(DISTINCT (col1, col2))` |
| 16 | **Fluent Subquery** | `.where("col").in(ClickHouseQuery.select(...))` |
| 17 | **Subquery FROM** | `.from(ClickHouseQuery.select(...)).as("alias")` |
| 18 | **UNION ALL** | `.unionAll(ClickHouseQuery.select(...))` |
| 19 | **WITH (CTE)** | `ClickHouseQuery.with("name", subQuery).select(...)` |
| 20 | **Type-safe Alias** | `Alias.of("orders")` → `.from(orders)` / `orders.col("amount")` |
| 21 | **INSERT batch** | `ClickHouseInsert.into("t").columns(...).executeBatch(...)` |
| 22 | **Window Functions** | `rowNumber()`, `rank()`, `lag()`, `lead()` + `.over().partitionBy().orderBy()` |
| 23 | **GROUP BY modifiers** | `WITH TOTALS` / `WITH ROLLUP` / `WITH CUBE` |
| 24 | **Streaming** | `.stream(handler)` O(1) / `.streamBatch(class, N, consumer)` O(N) |
| 25 | **Auto Cache** | `.cached(manager, Duration)` — Redis/Caffeine, MD5 auto-key |
| 26 | **ClickHouse String** | `.isEmpty()` / `.isNotEmpty()` — correct `''` checks for non-Nullable String |
| 27 | **Apply (custom logic)** | `.apply(q -> { ... })` — inject conditional logic without breaking chain |


---

## ⚡ Performance

### Two-Tier Reflection Cache

Auto DTO mapping (`.query(jdbc, MyDto.class)`) uses a **zero-overhead reflection model** when your DTO is a Java `record`:

| Tier | Scope | What is cached |
|---|---|---|
| **`RecordMapperCache`** (app-scoped) | Entire app lifetime | `Constructor` + `RecordComponent[]` + component-index map — resolved **once per record class** via `ClassValue` |
| **`rsMapping`** (query-scoped) | One query execution | ResultSet column → record component mapping — built **once on the first row**, reused for every subsequent row |

```
App lifecycle:
  1st query(OrderDto.class)  → reflect once → cache in RecordMapperCache
  2nd query(OrderDto.class)  → cache HIT → 0 reflection ✅
  3rd query(OrderDto.class)  → cache HIT → 0 reflection ✅

Within one query (10,000 rows):
  row 0   → build rsMapping from ResultSet metadata (once)
  row 1+  → reuse rsMapping ✅
```

> **`ClassValue` backing store** — the JVM manages weak references to `Class` objects automatically, so cache entries are reclaimed when a `ClassLoader` is unloaded (safe with Spring DevTools, Tomcat hot-deploy, OSGi).

---

## Installation

### Gradle (local JAR)

```bash
./gradlew clean jar
# → build/libs/clickhouse-query-builder-1.2.0.jar
```

Copy JAR to your project's `app/libs/` folder:

```groovy
repositories {
    mavenCentral()
    flatDir { dirs 'libs' }
}
dependencies {
    implementation name: 'clickhouse-query-builder-1.2.0'
}
```

### Publish to local Maven

```bash
./gradlew publishToMavenLocal
```

```groovy
repositories { mavenLocal() }
dependencies { implementation 'lib.core:clickhouse-query-builder:1.2.0' }
```

**Requirements:** Java 17+ · Spring JDBC 6.x

---

## Query Examples

### 1. Basic SELECT

```java
import static lib.core.clickhouse.expression.CH.*;

List<Order> orders = ClickHouseQuery
    .select(col("user_id"), sum("amount").as("total"))
    .from("orders")
    .where("tenant_id").eq(tenantId)
    .where("created_at").between(fromDate, toDate)
    .where("status").eqIfNotBlank(status)       // skipped if blank
    .where("category_id").in(categoryIds)       // IN (list)
    .where("deleted_at").isNull()
    .groupBy("user_id")
    .orderBy("total", SortOrder.DESC)
    .limit(10).offset(0)
    .query(namedJdbc, Order.class);              // auto DTO mapping
```

### 2. Type-Safe Alias

Avoid hard-coded `"o."`, `"u."` prefix strings:

```java
Alias orders = Alias.of("orders");     // orders.col("amount") → Expr("orders.amount")
Alias users  = Alias.of("users");      // users.col("name")    → Expr("users.name")

// With short alias:
Alias o = Alias.of("orders").as("o");  // o.col("amount") → Expr("o.amount")

ClickHouseQuery.select(
        users.col("name"),
        orders.sum("amount").as("total_revenue"),
        orders.countDistinct("order_id").as("order_count")
    )
    .from(orders)                                            // FROM orders
    .join(users).on(users.col("id"), orders.col("user_id"))  // JOIN users ON ...
    .where(orders.col("tenant_id")).eq(tenantId)
    .groupBy(users.col("name"))
    .having(orders.sum("amount")).gt(1000)
    .query(namedJdbc, Report.class);
```

**Alias methods:**

| Method | Return | Output |
|---|---|---|
| `orders.col("amount")` | `Expr` | `orders.amount` — for SELECT, WHERE, JOIN, GROUP BY |
| `orders.col("amount").as("total")` | `Expr` | `orders.amount AS total` |
| `orders.col("revenue").minus(orders.col("cost")).as("net")` | `Expr` | `orders.revenue - orders.cost AS net` |
| `orders.sum("amount")` | `Expr` | `sum(orders.amount)` |
| `orders.sum("revenue").minus(orders.sum("cost")).as("net")` | `Expr` | `sum(orders.revenue) - sum(orders.cost) AS net` |
| `orders.count("id")` | `Expr` | `count(orders.id)` |
| `orders.countDistinct("id")` | `Expr` | `countDistinct(orders.id)` |
| `orders.min("created_at")` | `Expr` | `min(orders.created_at)` |
| `orders.max("created_at")` | `Expr` | `max(orders.created_at)` |
| `orders.avg("score")` | `Expr` | `avg(orders.score)` |
| `orders.sumIf("amount").where("status").eq("ACTIVE")` | `Expr` | `sumIf(orders.amount, status = 'ACTIVE')` |
| `orders.sumIfRaw("amount", "cond")` | `Expr` | `sumIf(orders.amount, cond)` |
| `orders.caseWhen("amount").gt(5000).then("HIGH")` | — | `CASE WHEN orders.amount > 5000 ...` |

> [!TIP]
> **`col()` returns `Expr`** — a type-safe wrapper. `Expr` is accepted directly by `select()`, `groupBy()`, `on()`, `where()`, etc. No `.toString()` needed.
> `Expr.as("alias")` also returns `Expr`, so the entire chain stays type-safe.
> `Expr.equals(String)` works for convenient assertion: `assertEquals(expr, "expected")`.

> [!IMPORTANT]
> **When using JOIN (≥ 2 tables), always use `Alias.col()` for ALL column references:**
>
> ```java
> // ✅ Correct — every column is qualified
> .where(orders.col("status")).eq("ACTIVE")
> .groupBy(users.col("name"))
> .orderBy(orders.col("amount"), SortOrder.DESC)
>
> // ❌ Wrong — ClickHouse reports "ambiguous column"
> .where("status").eq("ACTIVE")      // which table?
> .groupBy("name")                   // which table?
> ```
>
> **Rule:** Single table → optional. JOIN → **always** use `alias.col("col")`, `alias.sum("col")`, `alias.caseWhen("col")`, etc.

### 3. Fluent JOIN

```java
Alias orders   = Alias.of("orders").as("o");
Alias users    = Alias.of("users").as("u");
Alias products = Alias.of("products").as("p");

ClickHouseQuery
    .select(users.col("name"), orders.sum("amount").as("total"))
    .from(orders)
    .join(users).on(users.col("id"), orders.col("user_id"))
    .leftJoin(products).on(products.col("id"), orders.col("product_id"))
    .where(orders.col("tenant_id")).eq(tenantId)
    .groupBy(users.col("name"))
    .having(orders.sum("amount")).gt(1000)
    .orderBy("total", SortOrder.DESC)
    .query(namedJdbc, Report.class);

// Raw ON condition for complex cases:
.join(users).on("u.id = o.user_id AND u.active = 1")
```

### 4. WHERE Operators

```java
.where("amount").gt(100)              // amount > 100
.where("amount").gte(100)             // amount >= 100
.where("amount").lt(50)               // amount < 50
.where("amount").lte(50)              // amount <= 50
.where("status").eq("ACTIVE")         // status = 'ACTIVE'
.where("status").ne("DELETED")        // status != 'DELETED'
.where("category_id").in(ids)         // category_id IN (:id0, :id1, ...)
.where("category_id").notIn(excluded) // category_id NOT IN (...)
.where("deleted_at").isNull()         // deleted_at IS NULL
.where("error").isNotNull()           // error IS NOT NULL
.where("session_id").isEmpty()        // session_id = '' (ClickHouse String)
.where("session_id").isNotEmpty()     // session_id != '' (ClickHouse String)
.where("created_at").between(from, to) // created_at >= :from AND created_at <= :to
.where("status").eqIfNotBlank(status) // skipped if null/blank
.where("role").eqIf(hasRole, role)    // skipped if condition false
```

> **Null-safe**: All operators (`eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `in`, `notIn`, `between`) **silently skip** the clause when value is `null`. No manual null checks needed.

```java
String status = request.getStatus();     // may be null
Integer minAmount = request.getMin();    // may be null

ClickHouseQuery.select("*").from("orders")
    .where("status").eq(status)          // skipped if null
    .where("amount").gt(minAmount)       // skipped if null
    .where("tenant_id").eq("op-1")       // always applied
    .query(namedJdbc, Order.class);
// → SELECT * FROM orders WHERE tenant_id = :tenantId
```

### 5. Fluent OR Conditions (`whereOr`)

All conditions inside `whereOr` are joined by **OR**. Groups themselves join with the outer query by **AND**.

```
WHERE  ①  AND  ②  AND  (③a OR ③b)  AND  (④a OR ④b)
       ↑        ↑       └ whereOr ┘      └ whereOr ┘
     where    where     inner = OR      inner = OR

└──────────── outer: always AND ────────────────────┘
```

```java
ClickHouseQuery.select("*")
    .from("orders")
    .where("tenant_id").eq(tenantId)            // AND
    .whereOr(or -> or                            // AND (
        .where("status").eq("ACTIVE")            //   status = 'ACTIVE'
        .where("status").eq("PENDING")           //   OR status = 'PENDING'
    )                                            // )
    .whereOr(or -> or                            // AND (
        .where("type").in(List.of("PREMIUM"))    //   type IN ('PREMIUM')
        .where("amount").gt(5000)                //   OR amount > 5000
        .where("name").ilike("john")             //   OR name ILIKE '%john%'
        .where("deleted_at").isNull()            //   OR deleted_at IS NULL
        .where("user_id").in(                    //   OR user_id IN (subquery)
            ClickHouseQuery.select("id").from("premium_users")
        )
    )                                            // )
    .query(namedJdbc, Order.class);
```

**Supported operators inside `whereOr`:**

| Operator | Example | SQL |
|---|---|---|
| `eq(v)` | `.where("status").eq("ACTIVE")` | `status = :_or0` |
| `ne(v)` | `.where("status").ne("DELETED")` | `status != :_or0` |
| `gt(v)` | `.where("amount").gt(100)` | `amount > :_or0` |
| `gte(v)` | `.where("amount").gte(100)` | `amount >= :_or0` |
| `lt(v)` | `.where("score").lt(10)` | `score < :_or0` |
| `lte(v)` | `.where("score").lte(5)` | `score <= :_or0` |
| `in(list)` | `.where("type").in(List.of(...))` | `type IN (:_or0, :_or1)` |
| `notIn(list)` | `.where("type").notIn(List.of(...))` | `type NOT IN (...)` |
| `isNull()` | `.where("col").isNull()` | `col IS NULL` |
| `isNotNull()` | `.where("col").isNotNull()` | `col IS NOT NULL` |
| `ilike(v)` | `.where("name").ilike("john")` | `name ILIKE '%john%'` |
| `like(v)` | `.where("name").like("john")` | `name LIKE '%john%'` |
| `in(subQuery)` | `.where("id").in(subQuery)` | `id IN (SELECT ...)` |

> **Null-safe:** All operators inside `whereOr` skip when value is `null`. Legacy `add()` / `addRaw()` still work.

### 6. LIKE / ILIKE Search

```java
// Case-insensitive (ILIKE) — contains %keyword%
.whereILike(keyword).on("name", "email")
// → (name ILIKE '%keyword%' OR email ILIKE '%keyword%')

// Case-sensitive (LIKE) — contains %keyword%
.whereLike(keyword).on("name", "email")

// Prefix search (keyword%) — index-friendly 🔥
.whereILike(keyword).onPrefix("name", "email")
// → (name ILIKE 'keyword%' OR email ILIKE 'keyword%')
```

### 7. Subquery (IN / NOT IN)

```java
.where("product_id").in(
    ClickHouseQuery.select("id")
        .from("products")
        .where("active").eq(1)
)
.where("user_id").notIn(
    ClickHouseQuery.select("id")
        .from("blocked_users")
        .where("status").eq("BLOCKED")
)
```

### 8. CASE WHEN

```java
import static lib.core.clickhouse.expression.CH.*;

// String result values (auto-quoted)
caseWhen("amount").gt(0).then("INCOME")
    .when("amount").eq(0).then("NEUTRAL")
    .orElse("EXPENSE")
    .as("type")

// Number result values (not quoted)
caseWhen("status").eq("COMPLETED").then(1)
    .orElse(0).as("is_completed")

// IN operator
caseWhen("category").in("FOOD", "DRINK").then("F&B")
    .orElse("OTHER").as("group")

// BETWEEN
caseWhen("score").between(0, 49).then("LOW")
    .when("score").between(50, 79).then("MEDIUM")
    .when("score").between(80, 100).then("HIGH")
    .orElse("UNKNOWN").as("grade")

// Raw expressions
caseWhen("type").eq("REFUND").thenRaw("amount")
    .orElseRaw("amount * -1").as("adjusted_amount")

// No ELSE
caseWhen("role").eq("ADMIN").then("YES")
    .end().as("is_admin")
```

### 9. ClickHouse String Type (`isEmpty` / `isNotEmpty`)

> [!IMPORTANT]
> ClickHouse `String` columns are **non-Nullable** by default. `LEFT JOIN` misses produce `''` (empty string), **not** `NULL`. Using `isNull()` / `isNotNull()` on these columns is **always true/false** — a silent bug.

**WHERE — filter by empty/non-empty String:**

```java
// ClickHouse String LEFT JOIN miss → '' not NULL
.where(endSession.col("session_id")).isNotEmpty()   // session_id != ''
.where(endSession.col("session_id")).isEmpty()      // session_id = ''

// ❌ WRONG — always true for non-Nullable String!
.where(endSession.col("session_id")).isNotNull()    // always true!
.where(endSession.col("session_id")).isNull()       // always false!
```

**CASE WHEN — conditional value based on empty/non-empty:**

```java
// Derive status from LEFT JOIN result
endSession.caseWhen("session_id")
    .isNotEmpty()                 // session_id != '' → matched
    .then("ENDED")
    .orElse("PLAYING")            // session_id = '' → no match
    .as("status")
```

**When to use:**

| Scenario | Use | **Don't** use |
|---|---|---|
| Non-Nullable String column | `.isEmpty()` / `.isNotEmpty()` | `.isNull()` / `.isNotNull()` |
| LEFT JOIN miss check | `.isEmpty()` | `.isNull()` |
| Nullable(String) column | `.isNull()` / `.isNotNull()` | — |

### 10. Fluent Custom Logic (`apply`)

Inject conditional logic into the query chain **without breaking fluency**:

```java
return ClickHouseQuery.select(...)
    .from(gameTransaction)
    .join(walletTransaction).on(...)
    .leftJoin(endSession).on(...)
    .where(walletTransaction.col("operator_id")).in(operatorIds)
    .apply(q -> applyStatusFilter(q, statuses))  // ← custom logic
    .where(gameTransaction.col("round_date_time")).between(from, to)
    .groupBy(...)
    .queryPage(page, pageSize, namedJdbc, PlayerSessionItem.class);

// Helper method — encapsulates complex if/else branching
private static void applyStatusFilter(ClickHouseQuery query, List<String> statuses) {
    if (statuses == null || statuses.size() != 1) return;
    String status = statuses.get(0).toUpperCase();
    if ("ENDED".equals(status)) {
        query.where(endSession.col("session_id")).isNotEmpty();
    } else if ("PLAYING".equals(status)) {
        query.where(endSession.col("session_id")).isEmpty();
    }
}
```

**Signature:**

```java
public T apply(Consumer<T> customizer)
```

`apply()` passes the current query to a `Consumer`, then returns the same query — keeping the chain intact.

**Use cases:**
- Complex if/else conditional WHERE (like status filtering)
- Reusable filter methods (e.g. `applyTenantFilter`, `applyDateRange`)
- Extracting logic into helpers while keeping query as a single return statement

### 11. Fluent Arithmetic (`minus` / `plus`)

Chain arithmetic operations on any `Expr` returned by `sum()`, `col()`, `count()`, etc.:

```java
import static lib.core.clickhouse.expression.CH.*;

Alias oi = Alias.of("order_items");

// sum().minus(sum()) — aggregate arithmetic
oi.sum("revenue").minus(oi.sum("cost")).as("net_profit")
// → sum(order_items.revenue) - sum(order_items.cost) AS net_profit

// col().minus(col()) — column arithmetic
oi.col("revenue").minus(oi.col("cost")).as("net_profit")
// → order_items.revenue - order_items.cost AS net_profit

// Plus
oi.sum("debit").plus(oi.sum("credit")).as("total")
// → sum(order_items.debit) + sum(order_items.credit) AS total
```

### 12. Multi-Column `countDistinct`

Count distinct combinations of multiple columns:

```java
import static lib.core.clickhouse.expression.CH.*;

Alias oi = Alias.of("order_items");

countDistinct(oi.col("user_id"), oi.col("session_id")).as("total_sessions")
// → count(DISTINCT (order_items.user_id, order_items.session_id)) AS total_sessions
```

### 13. Expression Builder & Conditional Aggregates

**Fluent (recommended):**

```java
import static lib.core.clickhouse.expression.CH.*;

ClickHouseQuery.select(
    col("product_id"),
    sumIf("amount").where("action").eq("SALE").as("total_sales"),
    countIf("user_id").where("status").eq("ACTIVE").as("active_count"),
    minIf("amount").where("type").eq("PURCHASE").as("min_purchase"),
    maxIf("amount").where("score").gt(100).as("max_order"),
    avgIf("score").where("status").isNotNull().as("avg_score"),
    countIf("user_id").where("tier").in("GOLD", "PREMIUM").as("premium_count")
).from("orders").groupBy("product_id");
```

**Raw (explicit):**

```java
sumIfRaw("amount", "action = 'SALE'").as("total_sales")
countIfRaw("user_id", in("tier", "GOLD", "PREMIUM")).as("premium_count")
```

### 14. HAVING with Aggregates

```java
ClickHouseQuery.select("user_id", sum("amount").as("total"))
    .from("orders")
    .groupBy("user_id")
    .having(sum("amount")).gt(1000)
    .having(count()).gte(5)
    .having(avg("score")).between(50, 100)
    .query(namedJdbc, Report.class);
```

### 15. Subquery FROM

```java
Alias sub    = Alias.of("sub");
Alias orders = Alias.of("orders");

ClickHouseQuery.select(sub.col("user_id"), sub.col("total"))
    .from(
        ClickHouseQuery.select(col("user_id"), sum("amount").as("total"))
            .from(orders)
            .where(orders.col("tenant_id")).eq(tenantId)
            .groupBy("user_id"),
        sub
    )
    .where(sub.col("total")).gt(1000)
    .orderBy("total", SortOrder.DESC)
    .limit(10)
    .query(namedJdbc, Report.class);
// → SELECT sub.user_id, sub.total FROM (SELECT ... GROUP BY user_id) AS sub WHERE sub.total > ...
```

### 16. UNION ALL

```java
// Combine results from multiple tables
ClickHouseQuery.select("user_id", "amount").from("orders_2024")
    .unionAll(ClickHouseQuery.select("user_id", "amount").from("orders_2025"))
    .orderBy("amount", SortOrder.DESC)
    .limit(10)
    .query(namedJdbc, Report.class);

// Chain 3+ unions
ClickHouseQuery.select("user_id", "amount").from("orders_2023")
    .unionAll(ClickHouseQuery.select("user_id", "amount").from("orders_2024"))
    .unionAll(ClickHouseQuery.select("user_id", "amount").from("orders_2025"))
    .orderBy("amount", SortOrder.DESC)
    .query(namedJdbc, Report.class);
```

### 17. WITH (CTE — Common Table Expressions)

```java
// Single CTE
Alias au = Alias.of("active_users").as("au");
Alias o  = Alias.of("orders").as("o");

ClickHouseQuery
    .with("active_users",
        ClickHouseQuery.select("user_id").from("users").where("status").eq("ACTIVE"))
    .select(au.col("user_id"), count().as("order_count"))
    .from(au)
    .join(o).on(au.col("user_id"), o.col("user_id"))
    .groupBy(au.col("user_id"))
    .query(namedJdbc, Report.class);

// Multiple CTEs
Alias u = Alias.of("cte_users").as("u");
Alias uo = Alias.of("cte_orders").as("uo");

ClickHouseQuery
    .with("cte_users",
        ClickHouseQuery.select("user_id").from("users").where("status").eq("ACTIVE"))
    .with("cte_orders",
        ClickHouseQuery.select("user_id", "sum(amount) AS total")
            .from("orders").groupBy("user_id"))
    .select(u.col("user_id"), uo.col("total"))
    .from(u)
    .join(uo).on(uo.col("user_id"), u.col("user_id"))
    .orderBy(uo.col("total"), SortOrder.DESC)
    .query(namedJdbc, Report.class);
```

### 18. Subquery Count

```java
// Style 1 — Static count
long total = ClickHouseQuery
    .count(
        ClickHouseQuery.select("user_id", "order_id")
            .from("order_items")
            .where("created_at").between(from, to)
            .groupBy("user_id", "order_id")
    )
    .execute(namedJdbc);

// Style 2 — Terminal count
long total = ClickHouseQuery
    .select("user_id", "order_id")
    .from("order_items")
    .where("created_at").between(from, to)
    .groupBy("user_id", "order_id")
    .count(namedJdbc);
```

### 19. Single-Query Pagination (`queryPage`)

Get paginated data **and total count in one query** — no extra `COUNT(*)` query:

```java
Page<Report> page = ClickHouseQuery.select("user_id", "amount")
    .from("orders")
    .where("tenant_id").eq(tenantId)
    .orderBy("amount", SortOrder.DESC)
    .queryPage(0, 10, namedJdbc, Report.class);   // page 0, size 10

page.getData();       // List<Report> — max 10 items
page.getTotal();      // 1234 — total matching rows
page.getTotalPages(); // 124
page.hasNext();       // true
page.hasPrevious();   // false (page 0)
```

Internally uses `count(*) OVER()` window function — total count computed **before** LIMIT.

### 20. Streaming — Large Export / SSE

True row-by-row pipeline from ClickHouse → app → HTTP client. No intermediate `List`, memory usage is O(1) or O(batchSize).

**`stream()` — CSV file download (memory O(1))**

```java
// Spring MVC controller
@GetMapping("/export")
public void exportCsv(HttpServletResponse response) throws IOException {
    response.setContentType("text/csv");
    response.setHeader("Content-Disposition", "attachment; filename=export.csv");
    PrintWriter writer = response.getWriter();
    writer.println("user_id,amount,created_at");   // CSV header

    ClickHouseQuery.select("user_id", "amount", "created_at")
        .from("orders")
        .where("tenant_id").eq(tenantId)
        .where("created_at").between(from, to)
        .stream(namedJdbc, rs -> {
            writer.println(
                rs.getString("user_id") + "," +
                rs.getBigDecimal("amount") + "," +
                rs.getString("created_at")
            );
            // each row is flushed to the browser immediately via TCP
        });
}
// FE: window.location.href = '/api/export?tenantId=...'
```

**`streamBatch()` — SSE / chunked JSON (memory O(batchSize))**

```java
// Push 10 rows at a time as JSON via Server-Sent Events
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamData() {
    SseEmitter emitter = new SseEmitter();
    executor.submit(() -> {
        ClickHouseQuery.select("user_id", "amount")
            .from("orders")
            .where("tenant_id").eq(tenantId)
            .streamBatch(namedJdbc, OrderDto.class, 10, batch -> {
                emitter.send(batch);   // push List<OrderDto> as JSON to browser
            });
        emitter.complete();
    });
    return emitter;
}

// FE — receive batches in real-time
const es = new EventSource('/api/stream');
es.onmessage = (e) => renderRows(JSON.parse(e.data));
```

> **Memory model:**
> - `stream()` — O(1): processes 1 row at a time, no buffer  
> - `streamBatch(batchSize=10)` — O(10): only 10 rows in memory at a time, regardless of total result size

### 21. Auto Caching (Redis / Caffeine)

ClickHouse queries can be heavy. To protect the database from concurrent dashboard reloads (cache stampede), you can enable **transparent caching** directly in the query builder.

The cache key is **automatically generated** via MD5 hash of the `SQL + parameters`.

**1. Implement `QueryCacheManager` adapter:**

```java
@Component
public class RedisQueryCacheManager implements QueryCacheManager {
    @Autowired private StringRedisTemplate redis;
    @Autowired private ObjectMapper mapper; // Must support JavaTimeModule

    @Override
    public <T> Object get(String key, Class<T> returnType, boolean isList) {
        String json = redis.opsForValue().get(key);
        if (json == null) return null;
        try {
            if (isList) {
                var listType = mapper.getTypeFactory().constructCollectionType(List.class, returnType);
                return mapper.readValue(json, listType);
            }
            return mapper.readValue(json, returnType);
        } catch (Exception e) { return null; }
    }

    @Override
    public void put(String key, Object data, long ttlSeconds) {
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(data), Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) { /* log error */ }
    }
}
```

**2. Just add `.cached()` to your query:**

```java
@Autowired QueryCacheManager redisCache;

public List<Report> getMonthlyRevenue(String tenantId) {
    return ClickHouseQuery.select(col("date"), sum("revenue"))
        .from("orders")
        .where("tenant_id").eq(tenantId)
        
        // ✨ MAGIC HAPPENS HERE:
        // Try Cache -> (Miss) -> Query DB -> Save Cache
        .cached(redisCache, Duration.ofMinutes(10)) 
        
        .groupBy("date")
        .query(namedJdbc, Report.class);
}
```

> The `.cached(...)` modifier works transparently with `.query()`, `.queryOne()`, and `.queryPage()`.

### 22. Auto DTO Mapping

No `RowMapper` needed — just pass your DTO class:

**Option A — Java `record` (recommended):**

```java
public record OrderReport(
    String userId,             // ← auto mapped from user_id
    BigDecimal totalAmount,    // ← auto mapped from total_amount
    long orderCount            // ← auto mapped from order_count
) {}

List<OrderReport> reports = ClickHouseQuery.select(
        col("user_id"), sum("amount").as("total_amount"), count().as("order_count")
    )
    .from("orders")
    .groupBy("user_id")
    .query(namedJdbc, OrderReport.class);   // auto RecordRowMapper
```

**Option B — POJO class:**

```java
public class OrderReport {
    private String userId;            // ← auto mapped from user_id
    private BigDecimal totalAmount;    // ← auto mapped from total_amount
    private Long orderCount;           // ← auto mapped from order_count
    // getters + setters (required)
}

List<OrderReport> reports = ClickHouseQuery.select(
        col("user_id"), sum("amount").as("total_amount"), count().as("order_count")
    )
    .from("orders")
    .groupBy("user_id")
    .query(namedJdbc, OrderReport.class);   // auto BeanPropertyRowMapper
```

**Common usage:**

```java
// Single result
OrderSummary summary = ClickHouseQuery.select("count(*) AS total_orders")
    .from("orders")
    .queryOne(namedJdbc, OrderSummary.class);     // single DTO or null

// Page with auto mapping
Page<OrderReport> page = ClickHouseQuery.select(col("user_id"), sum("amount").as("total"))
    .from("orders")
    .groupBy("user_id")
    .queryPage(0, 10, namedJdbc, OrderReport.class);
```

**Smart Mapper** auto-detects:
- Java `record` → uses `RecordRowMapper` (reflection-based, matches component names)
- POJO class → uses `BeanPropertyRowMapper` (Spring, `snake_case` → `camelCase`)

**Manual RowMapper** — for complex mapping, transformation, or when column names don't match:

```java
List<OrderReport> reports = ClickHouseQuery.select(
        col("user_id"), sum("amount").as("total"), count().as("cnt")
    )
    .from("orders")
    .groupBy("user_id")
    .query(namedJdbc, (rs, rowNum) -> {
        OrderReport r = new OrderReport();
        r.setUserId(rs.getString("user_id"));
        r.setTotal(rs.getBigDecimal("total"));
        r.setOrderCount(rs.getLong("cnt"));
        r.setFormatted("$" + rs.getBigDecimal("total").toPlainString());
        return r;
    });
```

| Method | When to use |
|---|---|
| **Auto `record`** `.query(jdbc, Record.class)` | Java 16+, immutable, column alias = component name |
| **Auto `class`** `.query(jdbc, Class)` | POJO with setters, `snake_case` → `camelCase` |
| **Manual** `.query(jdbc, RowMapper)` | Custom transform, combine fields, or names don't match |

### 23. Default LIMIT (Safety Guard)

Auto `LIMIT 1000` when `.query()` is called without an explicit `.limit()`:

```java
// No .limit() → auto LIMIT 1000
ClickHouseQuery.select("*").from("orders")
    .query(namedJdbc, Order.class);
// → SQL: ... LIMIT 1000

// Explicit .limit() → your value
ClickHouseQuery.select("*").from("orders")
    .limit(50)
    .query(namedJdbc, Order.class);
// → SQL: ... LIMIT 50
```

> **Note:** `UNION ALL` queries are excluded. Default value: `ClickHouseQuery.DEFAULT_LIMIT = 1000`.

### 24. Window Functions

Window functions perform calculations across rows related to the current row within a partition.

**Basic window functions:**

```java
import static lib.core.clickhouse.expression.CH.*;

ClickHouseQuery.select(
    col("user_id"),
    col("order_id"),
    col("amount"),
    // Row number within partition
    rowNumber().over().partitionBy("user_id").orderBy("created_at").as("row_num"),
    
    // Rank with gaps for ties
    rank().over().partitionBy("user_id").orderBy("amount", SortOrder.DESC).as("rank"),
    
    // Dense rank without gaps
    denseRank().over().partitionBy("user_id").orderBy("amount", SortOrder.DESC).as("dense_rank"),
    
    // Running total per user
    sum("amount").over().partitionBy("user_id").orderBy("created_at").as("running_total"),
    
    // Previous row value
    lag("amount").over().partitionBy("user_id").orderBy("created_at").as("prev_amount"),
    
    // Next row value
    lead("amount").over().partitionBy("user_id").orderBy("created_at").as("next_amount"),
    
    // First value in partition
    firstValue("amount").over().partitionBy("user_id").orderBy("created_at").as("first_order"),
    
    // Last value in partition
    lastValue("amount").over().partitionBy("user_id").orderBy("created_at").as("last_order"),
    
    // Divide into N buckets
    ntile(4).over().partitionBy("user_id").orderBy("amount", SortOrder.DESC).as("quartile")
)
.from("orders")
.where("tenant_id").eq(tenantId)
.query(namedJdbc, OrderWithRank.class);
```

**Window function without partition (whole result set):**

```java
// Global ranking across all rows
ClickHouseQuery.select(
    col("user_id"),
    col("amount"),
    rowNumber().over().orderBy("amount", SortOrder.DESC).as("global_rank")
)
.from("orders")
.query(namedJdbc, Report.class);
```

**With type-safe Alias:**

```java
Alias o = Alias.of("orders").as("o");

ClickHouseQuery.select(
    o.col("user_id"),
    o.col("amount"),
    rowNumber().over()
        .partitionBy(o.col("user_id"))
        .orderBy(o.col("created_at"), SortOrder.DESC)
        .as("rank")
)
.from(o)
.where(o.col("tenant_id")).eq(tenantId)
.query(namedJdbc, Report.class);
```

**Available window functions:**

| Function | Description | Example |
|---|---|---|
| `rowNumber()` | Sequential number within partition | `rowNumber().over().partitionBy("user_id").orderBy("created_at")` |
| `rank()` | Rank with gaps for ties | `rank().over().orderBy("score", SortOrder.DESC)` |
| `denseRank()` | Rank without gaps | `denseRank().over().orderBy("score", SortOrder.DESC)` |
| `lag(col)` | Previous row value (offset 1) | `lag("amount").over().partitionBy("user_id").orderBy("created_at")` |
| `lag(col, n)` | Value N rows before | `lag("amount", 3).over().orderBy("created_at")` |
| `lead(col)` | Next row value (offset 1) | `lead("amount").over().partitionBy("user_id").orderBy("created_at")` |
| `lead(col, n)` | Value N rows after | `lead("amount", 2).over().orderBy("created_at")` |
| `firstValue(col)` | First value in window frame | `firstValue("amount").over().partitionBy("user_id").orderBy("created_at")` |
| `lastValue(col)` | Last value in window frame | `lastValue("amount").over().partitionBy("user_id").orderBy("created_at")` |
| `ntile(n)` | Divide into N buckets | `ntile(4).over().orderBy("amount")` — quartiles |
| `sum(col).over()` | Running sum | `sum("amount").over().partitionBy("user_id").orderBy("created_at")` |
| `avg(col).over()` | Running average | `avg("amount").over().partitionBy("user_id").orderBy("created_at")` |
| `count().over()` | Running count | `count().over().partitionBy("user_id").orderBy("created_at")` |

### 25. GROUP BY Modifiers

ClickHouse supports special GROUP BY modifiers for advanced aggregation.

**WITH TOTALS — adds a summary row:**

```java
ClickHouseQuery.select(
    col("product_id"),
    sum("amount").as("total")
)
.from("orders")
.where("tenant_id").eq(tenantId)
.groupByWithTotals("product_id")
.query(namedJdbc, Report.class);
// → GROUP BY product_id WITH TOTALS
// Returns: regular rows + one extra row with totals across all groups
```

**WITH ROLLUP — hierarchical subtotals:**

```java
ClickHouseQuery.select(
    col("year"),
    col("month"),
    sum("amount").as("total")
)
.from("orders")
.groupByWithRollup("year", "month")
.query(namedJdbc, Report.class);
// → GROUP BY year, month WITH ROLLUP
// Returns: (year, month), (year, NULL), (NULL, NULL) — hierarchical subtotals
```

**WITH CUBE — all combinations:**

```java
ClickHouseQuery.select(
    col("region"),
    col("product"),
    sum("amount").as("total")
)
.from("orders")
.groupByWithCube("region", "product")
.query(namedJdbc, Report.class);
// → GROUP BY region, product WITH CUBE
// Returns: (region, product), (region, NULL), (NULL, product), (NULL, NULL)
```

### 26. INSERT

```java
ClickHouseInsert.into("orders")
    .columns("id", "user_id", "amount", "created_at")
    .executeBatch(namedJdbc, orders, o -> CHParams.of()
        .set("id", o.getId())
        .set("userId", o.getUserId())
        .setOrDefault("amount", o.getAmount(), BigDecimal.ZERO)
        .setTimestamp("createdAt", o.getCreatedAt())
        .build()
    );
```

---

## Validations & Safety

The library includes comprehensive validations to ensure SQL correctness and prevent common errors.

### 1. SQL Clause Ordering (Phase Validation)

Enforces correct SQL clause order at runtime:

```
SELECT → FROM → JOIN → WHERE → GROUP_BY → HAVING → ORDER_BY → LIMIT
```

```java
// ✅ Valid order
ClickHouseQuery.select("*")
    .from("orders")
    .where("status").eq("ACTIVE")
    .groupBy("user_id")
    .having(sum("amount")).gt(100)
    .orderBy("created_at")
    .limit(10);

// ❌ Invalid - throws IllegalStateException
ClickHouseQuery.select("*")
    .where("status").eq("ACTIVE")  // WHERE before FROM!
    .from("orders");
// Exception: Cannot call FROM after WHERE. 
// Expected order: SELECT → FROM → JOIN → WHERE → GROUP_BY → HAVING → ORDER_BY → LIMIT
```

**Rules:**
- Same phase can be called multiple times (e.g., multiple `.where()`)
- Phases can be skipped (e.g., `SELECT → FROM → LIMIT`)
- Cannot go backward (throws `IllegalStateException`)

### 2. Null & Empty Value Safety

All WHERE and HAVING operators automatically skip when value is `null` or empty:

```java
String status = null;
Integer amount = null;
List<Integer> ids = List.of();  // empty

ClickHouseQuery.select("*")
    .from("orders")
    .where("status").eq(status)      // ✅ Skipped (null)
    .where("amount").gt(amount)      // ✅ Skipped (null)
    .where("product_id").in(ids)     // ✅ Skipped (empty)
    .where("tenant_id").eq("op-1")   // ✅ Applied
    .toSql();
// → SELECT * FROM orders WHERE tenant_id = :tenantId
```

**Null-safe operators:**
- `eq()`, `ne()`, `gt()`, `gte()`, `lt()`, `lte()` - skip if null or empty string
- `in()`, `notIn()` - skip if collection is null or empty
- `between()` - only applies non-null, non-empty bounds
- `whereILike()`, `whereLike()` - skip if keyword is null or blank

**Special methods:**
```java
// eqIfNotBlank - only applies if string is not null and not blank
query.where("status").eqIfNotBlank("  ");  // ✅ Skipped (blank)

// eqIf - only applies if condition is true
query.where("role").eqIf(false, "ADMIN");  // ✅ Skipped (condition false)
```

### 3. Range Validation (between)

Validates that `from <= to`, throws `InvalidRangeException` if invalid:

```java
// ✅ Valid ranges
query.where("amount").between(100, 200);
query.where("created_at").between(
    Instant.parse("2024-01-01T00:00:00Z"),
    Instant.parse("2024-12-31T00:00:00Z")
);

// ❌ Invalid - throws InvalidRangeException
query.where("amount").between(200, 100);
// Exception: Invalid range for column 'amount': 
// from (200) must be less than or equal to to (100)

// ✅ Null bounds skip validation
query.where("amount").between(null, 200);  // Only applies upper bound
query.where("amount").between(100, null);  // Only applies lower bound

// ✅ Equal bounds are valid (inclusive range)
query.where("amount").between(100, 100);
```

**Supported types:**
- `Instant` (dates)
- `Number` (Integer, Long, BigDecimal, etc.)
- `String` (alphabetical comparison)
- Any `Comparable` type

**Exception handling:**
```java
try {
    query.where("amount").between(maxAmount, minAmount);
} catch (InvalidRangeException ex) {
    System.out.println(ex.getColumn());   // "amount"
    System.out.println(ex.getFrom());     // 200
    System.out.println(ex.getTo());       // 100
    System.out.println(ex.getMessage());  // Full error message
}
```

### 4. Best Practices

**✅ DO:**
```java
// Check nulls explicitly when needed
String status = getStatus();
if (status != null && !status.isEmpty()) {
    query.where("status").eq(status);
}

// Or use conditional methods
query.where("status").eqIfNotBlank(getStatus());

// Validate ranges before between()
if (minAmount <= maxAmount) {
    query.where("amount").between(minAmount, maxAmount);
}

// Use type-safe Alias for JOINs
Alias orders = Alias.of("orders").as("o");
Alias users = Alias.of("users").as("u");
query.where(orders.col("status")).eq("ACTIVE");
```

**❌ DON'T:**
```java
// Don't rely solely on null-safety for business logic
query.where("status").eq(possiblyNull);  // Works, but unclear intent

// Don't ignore range validation
query.where("amount").between(from, to);  // May throw if from > to

// Don't use string literals in JOINs
query.where("status").eq("ACTIVE");  // Ambiguous if multiple tables
```

---

## Observability (Logging & Metrics)

The library ships with **Spring Boot AutoConfiguration** — no `@Configuration` class needed.
Drop the JAR in, flip one flag in `application.yml`, and query logging/metrics activate automatically.

### How It Works

```
Spring Boot starts
  → scans META-INF/spring/AutoConfiguration.imports
  → finds ClickHouseQueryAutoConfiguration
  → reads clickhouse-query.* from application.yml
  → creates LoggingQueryObserver @Bean (if enabled=true)
  → registers it in QueryObserverRegistry

Every query():
  [time start]
  → cache HIT  → emit(QueryEvent{cache=HIT,  durationMs=2})   → return
  → cache MISS → jdbc.query() → emit(QueryEvent{cache=MISS, durationMs=800}) → async save → return
```

### Configuration — just YAML

```yaml
# application.yml
clickhouse-query:
  logging:
    enabled: true             # ← flip this switch to activate
    log-sql: true             # log every SQL at DEBUG level
    log-params: false         # ⚠ also log bind values (may expose PII)
    slow-query-ms: 500        # queries > 500ms → logged at WARN
  metrics:
    enabled: true             # cache HIT / MISS → logged at INFO

# Enable the logger to see DEBUG output
logging:
  level:
    clickhouse-query: DEBUG
```

> **That's all.** No `@Bean`, no `@PostConstruct`, no imports. The auto-config does everything.

### Log Output

```log
# Every query — DEBUG (log-sql: true)
🔍  [CH] LIST  │ MISS     │  843ms │ rows=31
   ├─ sql   : SELECT date, sum(revenue) FROM orders WHERE tenant_id = :t
   └─ params: {tenantId=TENANT_001, fromDate=2026-01-01}

# Cache HIT — INFO (metrics.enabled: true)
✅  [CH] PAGE  │ HIT      │    2ms │ rows=20 │ page=0/20

# Cache MISS — INFO (saved to Redis in background)
💾  [CH] LIST  │ MISS     │  843ms │ rows=31 — saved to cache (async)

# Slow query — WARN (slow-query-ms threshold breached)
┌─── ⚠️  SLOW QUERY ───────────────────────────────────────────────────
  type    : LIST            cache    : MISS
  duration: 2341ms          rows     : 10000
  sql     : SELECT date, sum(revenue) FROM orders WHERE ...
  params  : {tenantId=TENANT_001, fromDate=2026-01-01T00:00:00Z}
└──────────────────────────────────────────────────────────────────────
```

### Options Reference

| YAML Key | Default | Description |
|---|---|---|
| `logging.enabled` | `false` | Master switch — `true` activates the auto-configured observer |
| `logging.log-sql` | `true` | Log every SQL at `DEBUG` |
| `logging.log-params` | `false` | Log bind parameter values (**⚠ may expose PII**) |
| `logging.slow-query-ms` | `1000` | Queries above this threshold → `WARN` |
| `metrics.enabled` | `true` | Log cache `HIT` / `MISS` at `INFO` |

### Override with Custom Observer

Define your own `@Bean QueryObserver` to bypass the auto-config (e.g., for Micrometer/DataDog):

```java
// ClickHouseQueryAutoConfiguration skips its bean when this exists
@Bean
public QueryObserver clickHouseQueryObserver(MeterRegistry registry) {
    return event -> registry.timer("clickhouse.query",
            "type",  event.getQueryType().name(),
            "cache", event.getCacheStatus().name())
        .record(Duration.ofMillis(event.getDurationMs()));
}
```

> Register your custom observer in `@PostConstruct`:
> ```java
> @PostConstruct
> public void setup() { QueryObserverRegistry.register(clickHouseQueryObserver()); }
> ```


---

## Clause-Order Validation

The builder enforces SQL clause ordering at runtime (including subqueries):

```
SELECT → FROM → JOIN → WHERE → GROUP_BY → HAVING → ORDER_BY → LIMIT
```

```java
// ❌ Throws IllegalStateException
ClickHouseQuery.select("user_id")
    .from("t")
    .groupBy("user_id")
    .where("status").eq("ACTIVE");   // ERROR: cannot call WHERE after GROUP_BY

// ✅ Correct order
ClickHouseQuery.select("user_id")
    .from("t")
    .where("status").eq("ACTIVE")
    .groupBy("user_id");
```

- Same phase is allowed (e.g. multiple `.where()` calls)
- Skipping phases is allowed (e.g. `SELECT → FROM → ORDER_BY`)
- Going backward throws `IllegalStateException`

---

## API Reference

### CH (Expressions)

| Method | Output |
|---|---|
| `col("name")` | `name` |
| `col("name", "alias")` | `name AS alias` |
| `raw("expr")` | `expr` — raw SQL expression |
| `count()` | `count(*)` |
| `count("col")` | `count(col)` |
| `countDistinct("col")` | `countDistinct(col)` |
| `countDistinct("col1", "col2")` | `count(DISTINCT (col1, col2))` — **multi-column** |
| `sum("col")` | `sum(col)` |
| `min("col")` | `min(col)` |
| `max("col")` | `max(col)` |
| `avg("col")` | `avg(col)` |
| `any("col")` | `any(col)` — arbitrary value |
| `.minus(expr)` | `expr1 - expr2` — **arithmetic** |
| `.plus(expr)` | `expr1 + expr2` — **arithmetic** |
| `.multiply(expr)` | `expr1 * expr2` — **arithmetic** |
| `.divide(expr)` | `expr1 / expr2` — **arithmetic** |
| `sumIf("col").where("c").eq(v)` | `sumIf(col, c = 'v')` — **fluent** |
| `countIf("col").where("c").in(...)` | `countIf(col, c IN (...))` — **fluent** |
| `minIf("col").where("c").gt(v)` | `minIf(col, c > v)` — **fluent** |
| `maxIf("col").where("c").lt(v)` | `maxIf(col, c < v)` — **fluent** |
| `avgIf("col").where("c").isNotNull()` | `avgIf(col, c IS NOT NULL)` — **fluent** |
| `sumIfRaw("col", "cond")` | `sumIf(col, cond)` — **raw** |
| `countIfRaw("col", "cond")` | `countIf(col, cond)` — **raw** |
| `minIfRaw("col", "cond")` | `minIf(col, cond)` — **raw** |
| `maxIfRaw("col", "cond")` | `maxIf(col, cond)` — **raw** |
| `avgIfRaw("col", "cond")` | `avgIf(col, cond)` — **raw** |
| `rowNumber()` | `row_number()` — **window function** |
| `rank()` | `rank()` — **window function** |
| `denseRank()` | `dense_rank()` — **window function** |
| `lag("col")` / `lag("col", n)` | `lag(col, 1)` / `lag(col, n)` — **window function** |
| `lead("col")` / `lead("col", n)` | `lead(col, 1)` / `lead(col, n)` — **window function** |
| `firstValue("col")` | `first_value(col)` — **window function** |
| `lastValue("col")` | `last_value(col)` — **window function** |
| `ntile(n)` | `ntile(n)` — **window function** |
| `.over().partitionBy(...).orderBy(...)` | `OVER(PARTITION BY ... ORDER BY ...)` — **window** |
| `in("col", "v1", "v2")` | `col IN ('v1','v2')` |
| `.as("alias")` | `... AS alias` |

### CASE WHEN Operators

| Method | SQL |
|---|---|
| `caseWhen("col").eq(val)` | `CASE WHEN col = val` |
| `.ne(val)` | `WHEN col != val` |
| `.gt(val)` / `.gte(val)` | `WHEN col > val` / `>=` |
| `.lt(val)` / `.lte(val)` | `WHEN col < val` / `<=` |
| `.between(from, to)` | `WHEN col BETWEEN from AND to` |
| `.in("v1", "v2")` | `WHEN col IN ('v1', 'v2')` |
| `.isNull()` / `.isNotNull()` | `WHEN col IS NULL` / `IS NOT NULL` |
| `.isEmpty()` / `.isNotEmpty()` | `WHEN col = ''` / `WHEN col != ''` — **ClickHouse String** |
| `.then(val)` | `THEN 'val'` (String) / `THEN val` (Number) |
| `.thenRaw("expr")` | `THEN expr` (unquoted) |
| `.orElse(val)` / `.orElseRaw("expr")` | `ELSE ... END` |
| `.end()` | `END` (no ELSE) |

### ClickHouseQuery (SELECT Builder)

| Category | Method | Description |
|---|---|---|
| **SELECT** | `.select(...)` | Start SELECT query |
| | `.selectDistinct(...)` | Start SELECT DISTINCT |
| **FROM** | `.from("table")` / `.from(alias)` | Set table |
| | `.from(subQuery, alias)` / `.from(subQuery).as("alias")` | Subquery as table source |
| **JOIN** | `.join(alias).on(a.col("id"), b.col("user_id"))` | INNER JOIN |
| | `.leftJoin(alias).on(...)` | LEFT JOIN |
| | `.rightJoin(alias).on(...)` | RIGHT JOIN |
| **WHERE** | `.where("col").eq/ne/gt/gte/lt/lte(val)` | Comparison operators |
| | `.where("col").in(list)` / `.notIn(list)` | IN / NOT IN |
| | `.where("col").in(subQuery)` / `.notIn(subQuery)` | Subquery IN |
| | `.where("col").isNull()` / `.isNotNull()` | NULL checks |
| | `.where("col").isEmpty()` / `.isNotEmpty()` | ClickHouse String empty checks |
| | `.where("col").between(from, to)` | Range |
| | `.where("col").eqIfNotBlank(val)` | Conditional equality |
| | `.where("col").eqIf(cond, val)` | Boolean-conditional |
| | `.whereILike(kw).on(...)` / `.onPrefix(...)` | ILIKE search |
| | `.whereLike(kw).on(...)` | LIKE search |
| | `.whereOr(or -> or.where(...).eq(...))` | Fluent OR group |
| | `.whereRaw("condition")` | Raw WHERE |
| **Apply** | `.apply(q -> { ... })` | Inject custom logic inline |
| **GROUP BY** | `.groupBy("col1", "col2")` | Group by columns |
| | `.groupByWithTotals("col1", "col2")` | GROUP BY ... WITH TOTALS |
| | `.groupByWithRollup("col1", "col2")` | GROUP BY ... WITH ROLLUP |
| | `.groupByWithCube("col1", "col2")` | GROUP BY ... WITH CUBE |
| **HAVING** | `.having(sum("col")).gt(100)` | Aggregate filter |
| **ORDER BY** | `.orderBy("col", SortOrder.DESC)` | Sort (multiple calls OK) |
| **LIMIT** | `.limit(10).offset(0)` | Pagination |
| **Execute** | `.query(jdbc, mapper/class)` | Run and map results |
| | `.queryOne(jdbc, class)` | Single DTO or null |
| | `.queryPage(page, size, jdbc, class)` | Paginated results |
| | `.count(jdbc)` | Terminal count |
| **UNION** | `.unionAll(subQuery)` | Append UNION ALL |
| **CTE** | `.with("name", subQuery).select(...)` | Common Table Expression |
| **Static** | `ClickHouseQuery.count(subQuery)` | Subquery count |

### CHParams (Insert Parameter Builder)

| Method | Description |
|---|---|
| `.set("name", value)` | Set value |
| `.setOrDefault("name", val, default)` | Set with fallback |
| `.setEnum("name", enumVal)` | Enum → String |
| `.setTimestamp("name", instant)` | Instant → Timestamp |
| `.setArray("name", list, type)` | List → Array |

---

## 🏆 Comparison with Other Libraries

| Feature | This Library | jOOQ | QueryDSL | JdbcTemplate | CH JDBC |
|---|---|---|---|---|---|
| **Type Safety** | Runtime | Compile-time | Compile-time | None | None |
| **Code Generation** | ❌ Not needed | ✅ Required | ✅ Required | ❌ No | ❌ No |
| **Fluent API** | ✅✅✅ Excellent | ✅✅ Good | ✅ Good | ❌ None | ❌ None |
| **ClickHouse Features** | ✅✅✅ Full | ✅ Partial | ❌ Limited | ❌ Manual | ✅ Full |
| **Window Functions** | ✅ Yes | ✅ Yes | ✅ Yes | ❌ Manual | ❌ Manual |
| **GROUP BY Modifiers** | ✅ Yes | ⚠️ Partial | ❌ No | ❌ Manual | ❌ Manual |
| **Null-Safe Operators** | ✅ Yes | ❌ No | ❌ No | ❌ No | ❌ No |
| **Auto DTO Mapping** | ✅ Yes | ✅ Yes | ✅ Yes | ⚠️ Manual | ⚠️ Manual |
| **Single-Query Pagination** | ✅ Yes | ✅ Yes | ✅ Yes | ❌ Manual | ❌ Manual |
| **CTE (WITH)** | ✅ Yes | ✅ Yes | ✅ Yes | ❌ Manual | ❌ Manual |
| **Multi-Database** | ❌ ClickHouse only | ✅ Yes | ✅ Yes | ✅ Yes | ❌ No |
| **Learning Curve** | ✅ Easy | ⚠️ Medium | ⚠️ Medium | ✅ Easy | ✅ Easy |
| **Dependencies** | ✅ Lightweight | ⚠️ Heavy | ⚠️ Medium | ✅ Light | ✅ Light |
| **License** | ✅ Free | ⚠️ Dual (commercial) | ✅ Free | ✅ Free | ✅ Free |

**Best choice when:**
- ✅ **This library** — ClickHouse-dedicated projects, small/medium teams, zero-config setup, fast iteration
- ⚠️ **jOOQ** — Multi-database, compile-time type safety, large teams with commercial budget
- ⚠️ **QueryDSL** — JPA/Hibernate-based projects
- ⚠️ **JdbcTemplate** — Simple queries, raw SQL preferred, maximum raw performance

---

## 📊 Feature Coverage

| Category | Coverage | Status |
|---|---|---|
| **SELECT Queries** | 100% | ✅ Complete |
| **JOIN Operations** | 80% | ✅ Good (missing ARRAY/ASOF/CROSS) |
| **WHERE Conditions** | 100% | ✅ Complete |
| **GROUP BY & Aggregation** | 100% | ✅ Complete |
| **Window Functions** | 100% | ✅ Complete |
| **Advanced Features (CTE, UNION, Subquery)** | 100% | ✅ Complete |
| **ClickHouse-Specific** | 40% | ⚠️ Missing PREWHERE, SAMPLE, FINAL |
| **DML Operations** | 50% | ⚠️ INSERT only (no UPDATE/DELETE) |
| **Built-in Functions** | 30% | ⚠️ Use `raw()` for string/date/math functions |
| **Execution & Performance** | 100% | ✅ Complete |
| **Validation & Safety** | 100% | ✅ Complete |
| **Observability** | 100% | ✅ Complete |

**Overall Coverage: ~85%** — covers 95%+ of real-world ClickHouse use cases in production.

---

## 🚀 Roadmap

### v1.3.0 — ClickHouse Optimization
- [ ] `PREWHERE` clause (2-10x faster for large tables)
- [ ] `SAMPLE` clause (fast approximate analytics)
- [ ] `FINAL` modifier (ReplacingMergeTree deduplication)

### v1.4.0 — Advanced Features
- [ ] `ARRAY JOIN` (flatten array columns)
- [ ] `ASOF JOIN` (time-series matching)
- [ ] Common string functions (`concat`, `substring`, `trim`)
- [ ] Common date functions (`toStartOfMonth`, `dateDiff`)

### v2.0.0 — Multi-Database
- [ ] PostgreSQL support
- [ ] MySQL support
- [ ] Database-agnostic query builder

---

## Changelog

### v1.2.0

**New features**
- `WhereBuilder.isEmpty()` / `.isNotEmpty()` — ClickHouse-specific String empty checks (`= ''` / `!= ''`). Use instead of `isNull()` / `isNotNull()` for non-Nullable String columns (e.g. LEFT JOIN misses)
- `CaseConditionBuilder.isEmpty()` / `.isNotEmpty()` — same empty checks available in CASE WHEN expressions
- `BaseQuery.apply(Consumer<T>)` — inject custom logic (e.g. conditional WHERE filters) without breaking the fluent chain. Enables single-return-statement queries with complex branching

**Bug fixes**
- `build.gradle`: fix duplicate `plugins {}` blocks (caused Gradle build failure)
- `BaseQuery.queryPage()`: replace fragile `String.replace()` with `buildPaginatedSql()` using `try/finally` — correctly injects `count(*) OVER() AS _total` into the SELECT pipeline without risking SQL corruption on complex queries
- `RecordRowMapper`: remove non-thread-safe `volatile` lazy-init pattern; field is now `final`

**Performance**
- `RecordMapperCache` (new): app-scoped `ClassValue`-backed cache for record class metadata — constructor + components resolved **once per class**, safe with ClassLoader reloading
- `RecordRowMapper`: ResultSet column mapping (`rsMapping`) now cached after the first row — eliminates repeated `rs.getMetaData()` calls for large result sets (e.g. range scans returning 10k+ rows)

### v1.1.0
- Initial public release

---

<div align="center">

**Made with ❤️ for the ClickHouse + Java community**

</div>
]]>
