package com.example.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class DatabaseMcpTools {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMcpTools.class);

    private static final Set<String> ALLOWED_TABLES = Set.of("da_products", "da_orders");

    private final JdbcTemplate jdbcTemplate;

    public DatabaseMcpTools(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(description = "List all tables the agent is allowed to query. Call this first if the user has not named a specific table.")
    public String listTables() {
        log.info("MCP tool called: listTables");
        return "Available tables: da_products, da_orders";
    }

    @Tool(description = "Get the schema (column names and types) plus 3 sample rows of a table. Always call this before writing a query so you know the exact column names.")
    public String getTableSchema(String tableName) {
        log.info("MCP tool called: getTableSchema({})", tableName);
        if (!ALLOWED_TABLES.contains(tableName.toLowerCase())) {
            return "Error: table '" + tableName + "' is not in the allowed list. Use listTables to see available tables.";
        }
        try {
            List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                    "SELECT column_name, data_type FROM information_schema.columns " +
                    "WHERE table_name = ? ORDER BY ordinal_position",
                    tableName.toLowerCase()
            );

            StringBuilder sb = new StringBuilder();
            sb.append("Table: ").append(tableName).append("\nColumns:\n");
            for (Map<String, Object> col : columns) {
                sb.append("  - ").append(col.get("column_name"))
                  .append(" (").append(col.get("data_type")).append(")\n");
            }

            List<Map<String, Object>> samples = jdbcTemplate.queryForList(
                    "SELECT * FROM " + tableName + " LIMIT 3"
            );
            sb.append("\nSample rows:\n");
            if (samples.isEmpty()) {
                sb.append("  (no rows yet)\n");
            } else {
                for (Map<String, Object> row : samples) {
                    sb.append("  ").append(row).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("getTableSchema error for {}: {}", tableName, e.getMessage());
            return "Error getting schema for '" + tableName + "': " + e.getMessage();
        }
    }

    @Tool(description = "Execute a SELECT query against the database and return the results as a formatted table. Only SELECT is allowed; results are capped at 20 rows.")
    public String executeQuery(String sql) {
        log.info("MCP tool called: executeQuery({})", sql);

        String trimmed = sql.trim();
        if (!trimmed.toUpperCase().startsWith("SELECT")) {
            return "Error: only SELECT queries are allowed.";
        }

        String lower = trimmed.toLowerCase();
        for (String kw : List.of("insert ", "update ", "delete ", "drop ", "alter ", "truncate ", "create ", "grant ", "revoke ")) {
            if (lower.contains(kw)) {
                return "Error: keyword '" + kw.trim() + "' is not allowed.";
            }
        }
        for (String blocked : List.of("knowledge_documents", "chat_history", "spring_ai_chat_memory", "pg_", "pg_catalog")) {
            if (lower.contains(blocked)) {
                return "Error: access to '" + blocked + "' is not allowed.";
            }
        }

        String finalSql = lower.contains("limit") ? trimmed : trimmed + " LIMIT 20";

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(finalSql);
            if (rows.isEmpty()) {
                return "Query returned no rows.";
            }

            List<String> headers = List.copyOf(rows.get(0).keySet());
            StringBuilder sb = new StringBuilder();
            sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
            sb.append("| ").append("--- | ".repeat(headers.size())).append("\n");
            for (Map<String, Object> row : rows) {
                sb.append("| ");
                for (String h : headers) {
                    Object val = row.get(h);
                    sb.append(val != null ? val.toString() : "NULL").append(" | ");
                }
                sb.append("\n");
            }
            sb.append("\n(").append(rows.size()).append(" row(s))");
            return sb.toString();
        } catch (Exception e) {
            log.error("executeQuery error: {}", e.getMessage());
            return "Error executing query: " + e.getMessage();
        }
    }
}
