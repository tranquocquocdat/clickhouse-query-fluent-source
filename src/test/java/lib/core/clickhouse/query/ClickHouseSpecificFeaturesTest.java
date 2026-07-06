package lib.core.clickhouse.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ClickHouse-specific features: PREWHERE, SAMPLE, FINAL.
 */
class ClickHouseSpecificFeaturesTest {

    @Nested
    @DisplayName("PREWHERE Tests")
    class PrewhereTests {

        @Test
        @DisplayName("prewhere().eq() generates PREWHERE clause")
        void prewhereEq() {
            ClickHouseQuery q = ClickHouseQuery
                .select("*")
                .from("orders")
                .prewhere("tenant_id").eq("op-1");

            String sql = q.toSql();
            assertTrue(sql.contains("PREWHERE tenant_id = :"));
            assertEquals("op-1", q.toParams().getValue("tenantId_0"));
        }

        @Test
        @DisplayName("prewhere() comes before WHERE in SQL")
        void prewhereBeforeWhere() {
            String sql = ClickHouseQuery
                .select("*")
                .from("orders")
                .prewhere("tenant_id").eq("op-1")
                .where("amount").gt(100)
                .toSql();

            int prewherePos = sql.indexOf("PREWHERE");
            int wherePos = sql.indexOf("WHERE");
            assertTrue(prewherePos < wherePos, "PREWHERE should come before WHERE");
        }

        @Test
        @DisplayName("prewhere() with null value is skipped")
        void prewhereNullSkipped() {
            String sql = ClickHouseQuery
                .select("*")
                .from("orders")
                .prewhere("tenant_id").eq(null)
                .where("amount").gt(100)
                .toSql();

            assertFalse(sql.contains("PREWHERE"));
            assertTrue(sql.contains("WHERE amount"));
        }

        @Test
        @DisplayName("prewhere() with empty string is skipped")
        void prewhereEmptySkipped() {
            String sql = ClickHouseQuery
                .select("*")
                .from("orders")
                .prewhere("tenant_id").eq("")
                .where("amount").gt(100)
                .toSql();

            assertFalse(sql.contains("PREWHERE"));
            assertTrue(sql.contains("WHERE amount"));
        }

        @Test
        @DisplayName("prewhere() supports all comparison operators")
        void prewhereAllOperators() {
            ClickHouseQuery q = ClickHouseQuery
                .select("*")
                .from("orders")
                .prewhere("tenant_id").eq("op-1")
                .prewhere("amount").gt(100)
                .prewhere("score").gte(50)
                .prewhere("latency").lt(1000)
                .prewhere("size").lte(500)
                .prewhere("status").ne("DELETED");

            String sql = q.toSql();
            assertTrue(sql.contains("PREWHERE tenant_id = :"));
            assertTrue(sql.contains("AND amount > :"));
            assertTrue(sql.contains("AND score >= :"));
            assertTrue(sql.contains("AND latency < :"));
            assertTrue(sql.contains("AND size <= :"));
            assertTrue(sql.contains("AND status != :"));
        }

        @Test
        @DisplayName("prewhere().in() generates IN clause")
        void prewhereIn() {
            ClickHouseQuery q = ClickHouseQuery
                .select("*")
                .from("orders")
                .prewhere("tenant_id").in(java.util.List.of("op-1", "op-2"));

            String sql = q.toSql();
            assertTrue(sql.contains("PREWHERE tenant_id IN ("));
        }

        @Test
        @DisplayName("prewhere().isNotNull() generates IS NOT NULL")
        void prewhereIsNotNull() {
            String sql = ClickHouseQuery
                .select("*")
                .from("orders")
                .prewhere("tenant_id").isNotNull()
                .toSql();

            assertTrue(sql.contains("PREWHERE tenant_id IS NOT NULL"));
        }

        @Test
        @DisplayName("prewhere().isNotEmpty() generates != ''")
        void prewhereIsNotEmpty() {
            String sql = ClickHouseQuery
                .select("*")
                .from("orders")
                .prewhere("session_id").isNotEmpty()
                .toSql();

            assertTrue(sql.contains("PREWHERE session_id != ''"));
        }
    }

    @Nested
    @DisplayName("SAMPLE Tests")
    class SampleTests {

        @Test
        @DisplayName("sample() generates SAMPLE clause")
        void sample() {
            String sql = ClickHouseQuery
                .select("avg(amount)")
                .from("orders")
                .sample(0.1)
                .toSql();

            assertTrue(sql.contains("FROM orders SAMPLE 0.1"));
        }

        @Test
        @DisplayName("sample() comes after FROM in SQL")
        void sampleAfterFrom() {
            String sql = ClickHouseQuery
                .select("*")
                .from("orders")
                .sample(0.5)
                .where("tenant_id").eq("op-1")
                .toSql();

            int fromPos = sql.indexOf("FROM");
            int samplePos = sql.indexOf("SAMPLE");
            int wherePos = sql.indexOf("WHERE");
            assertTrue(fromPos < samplePos);
            assertTrue(samplePos < wherePos);
        }

        @Test
        @DisplayName("sample() with invalid ratio throws exception")
        void sampleInvalidRatio() {
            assertThrows(IllegalArgumentException.class, () -> 
                ClickHouseQuery.select("*").from("orders").sample(0)
            );
            assertThrows(IllegalArgumentException.class, () -> 
                ClickHouseQuery.select("*").from("orders").sample(-0.1)
            );
            assertThrows(IllegalArgumentException.class, () -> 
                ClickHouseQuery.select("*").from("orders").sample(1.1)
            );
        }

        @Test
        @DisplayName("sample(1.0) is valid (100% sample)")
        void sampleFullSample() {
            String sql = ClickHouseQuery
                .select("*")
                .from("orders")
                .sample(1.0)
                .toSql();

            assertTrue(sql.contains("SAMPLE 1.0"));
        }
    }

    @Nested
    @DisplayName("FINAL Tests")
    class FinalTests {

        @Test
        @DisplayName("useFinal() generates FINAL modifier")
        void useFinal() {
            String sql = ClickHouseQuery
                .select("*")
                .from("users")
                .useFinal()
                .where("user_id").eq(123)
                .toSql();

            assertTrue(sql.contains("FROM users FINAL"));
        }

        @Test
        @DisplayName("FINAL comes after FROM, before SAMPLE")
        void finalPosition() {
            String sql = ClickHouseQuery
                .select("*")
                .from("users")
                .useFinal()
                .sample(0.5)
                .where("user_id").eq(123)
                .toSql();

            int fromPos = sql.indexOf("FROM");
            int finalPos = sql.indexOf("FINAL");
            int samplePos = sql.indexOf("SAMPLE");
            int wherePos = sql.indexOf("WHERE");
            
            assertTrue(fromPos < finalPos);
            assertTrue(finalPos < samplePos);
            assertTrue(samplePos < wherePos);
        }
    }

    @Nested
    @DisplayName("Combined Features Tests")
    class CombinedTests {

        @Test
        @DisplayName("PREWHERE + WHERE + SAMPLE + FINAL all work together")
        void allFeaturesCombined() {
            String sql = ClickHouseQuery
                .select("user_id", "sum(amount) AS total")
                .from("orders")
                .useFinal()
                .sample(0.1)
                .prewhere("tenant_id").eq("op-1")
                .where("amount").gt(100)
                .groupBy("user_id")
                .toSql();

            assertTrue(sql.contains("FROM orders FINAL"));
            assertTrue(sql.contains("SAMPLE 0.1"));
            assertTrue(sql.contains("PREWHERE tenant_id = :"));
            assertTrue(sql.contains("WHERE amount > :"));
            assertTrue(sql.contains("GROUP BY user_id"));
        }

        @Test
        @DisplayName("SQL clause order is correct")
        void sqlClauseOrder() {
            String sql = ClickHouseQuery
                .select("*")
                .from("orders")
                .useFinal()
                .sample(0.5)
                .prewhere("tenant_id").eq("op-1")
                .where("amount").gt(100)
                .groupBy("user_id")
                .having(lib.core.clickhouse.expression.CH.sum("amount")).gt(1000)
                .orderBy("total")
                .limit(10)
                .toSql();

            // Verify order: FROM → FINAL → SAMPLE → PREWHERE → WHERE → GROUP BY → HAVING → ORDER BY → LIMIT
            int[] positions = {
                sql.indexOf("FROM"),
                sql.indexOf("FINAL"),
                sql.indexOf("SAMPLE"),
                sql.indexOf("PREWHERE"),
                sql.indexOf("WHERE"),
                sql.indexOf("GROUP BY"),
                sql.indexOf("HAVING"),
                sql.indexOf("ORDER BY"),
                sql.indexOf("LIMIT")
            };

            for (int i = 1; i < positions.length; i++) {
                assertTrue(positions[i-1] < positions[i], 
                    "Clause order violation at position " + i);
            }
        }
    }
}
