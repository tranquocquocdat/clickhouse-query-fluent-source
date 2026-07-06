package lib.core.query.util;

import java.util.regex.Pattern;

/**
 * Validates SQL column names to prevent SQL injection.
 * 
 * <p>Allows:
 * <ul>
 *   <li>Simple columns: {@code user_id}, {@code amount}</li>
 *   <li>Table-qualified: {@code orders.user_id}, {@code o.amount}</li>
 *   <li>Function calls: {@code sum(amount)}, {@code count(*)}</li>
 *   <li>Expressions: {@code amount * 1.1}, {@code CASE WHEN ...}</li>
 * </ul>
 * 
 * <p>Blocks:
 * <ul>
 *   <li>SQL keywords that could cause injection: {@code DROP}, {@code DELETE}, {@code INSERT}, {@code UPDATE}</li>
 *   <li>Comment markers: {@code --}, {@code /*}, {@code *i>
 *   <li>Statement terminators: {@code ;}</li>
 * </ul>
 */
public final class ColumnValidator {
    
    private ColumnValidator() {}
    
    // Dangerous SQL keywords that should never appear in column expressions
    private static final Pattern DANGEROUS_KEYWORDS = Pattern.compile(
        "(?i)\\b(DROP|DELETE|INSERT|UPDATE|TRUNCATE|ALTER|CREATE|EXEC|EXECUTE)\\b"
    );
    
    // SQL comment markers
    private static final Pattern COMMENT_MARKERS = Pattern.compile("--|/\\*|\\*/");
    
    //ment terminator
    private static final Pattern STATEMENT_TERMINATOR = Pattern.compile(";");
    
    /**
     * Validates a column name or expression.
     * 
     * @param column the column name or expression to validate
     * @throws IllegalArgumentException if the column contains dangerous SQL
     */
    public static void validate(String column) {
        if (column == null || column.isEmpty()) {
    pty");
        }
        
        // Check for dangerous keywords
        if (DANGEROUS_KEYWORDS.matcher(column).find()) {
            throw new IllegalArgumentException(
                "Column name contains dangerous SQL keyword: " + column
            );
        }
        
        // Check for comment markers
        if (COMMENT_MARKERS.matcher(column).find()) {
            throw new IllegalArgumentException(
                "Column name contains SQL comment marker: " + column
            );
        }
        
        // Check for statement terminator
        if (STATEMENT_TERMINATOR.matcher(column).find()) {
            throw new IllegalArgumentException(
                "Column name contains statement terminator: " + column
            );
        }
    }
    
    /**
     * Validates a column name and returns it (for fluent chaining).
     * 
     * @param column the column name to validate
     * @return the validated column name
     * @throws IllegalArgumentException if validation fails
     */
    public stac String validateAndReturn(String column) {
        validate(column);
        return column;
    }
}
