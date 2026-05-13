package com.chimeta.tools.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

public final class MySqlSchemaMcpServer {

    private static final String TOOL_LIST_TABLES = "list_tables";
    private static final String TOOL_DESCRIBE_TABLE = "describe_table";
    private static final String TOOL_ENTITY_CONTEXT = "get_entity_context";

    private static final String ENV_HOST = "MYSQL_MCP_HOST";
    private static final String ENV_PORT = "MYSQL_MCP_PORT";
    private static final String ENV_DATABASE = "MYSQL_MCP_DATABASE";
    private static final String ENV_USERNAME = "MYSQL_MCP_USERNAME";
    private static final String ENV_PASSWORD = "MYSQL_MCP_PASSWORD";

    private MySqlSchemaMcpServer() {
    }

    public static void main(String[] args) {
        try {
            Config config = Config.fromEnvironment();
            StdioServerTransportProvider transportProvider =
                    new StdioServerTransportProvider(new JacksonMcpJsonMapper(new ObjectMapper()));

            McpSyncServer server = McpServer.sync(transportProvider)
                    .serverInfo("mysql-schema-mcp", "0.1.0")
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                    .tools(
                            createListTablesTool(config),
                            createDescribeTableTool(config),
                            createEntityContextTool(config)
                    )
                    .build();

            System.err.println("mysql-schema-mcp started for database " + config.database());
            Thread.currentThread().join();
            server.closeGracefully();
        } catch (Exception ex) {
            System.err.println("mysql-schema-mcp failed to start: " + ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static McpServerFeatures.SyncToolSpecification createListTablesTool(Config config) {
        McpSchema.Tool tool = new McpSchema.Tool(
                TOOL_LIST_TABLES,
                null,
                "List base tables in the configured MySQL database. Optional tableLike filters by substring.",
                new McpSchema.JsonSchema("object", listTablesProperties(), null, null, null, null),
                null,
                null,
                null
        );

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> arguments = request.arguments();
            String tableLike = asString(arguments.get("tableLike"));
            int limit = intValue(arguments.get("limit"), 100, 1, 500);
            List<TableSummary> tables = listTables(config, tableLike, limit);
            String text = renderTableList(config.database(), tableLike, tables);
            return textResult(text);
        });
    }

    private static McpServerFeatures.SyncToolSpecification createDescribeTableTool(Config config) {
        McpSchema.Tool tool = new McpSchema.Tool(
                TOOL_DESCRIBE_TABLE,
                null,
                "Describe one table in detail, including columns, primary key, nullability, defaults and comments.",
                new McpSchema.JsonSchema("object", requiredTableNameProperties(), List.of("tableName"), null, null, null),
                null,
                null,
                null
        );

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            String tableName = requiredString(request.arguments(), "tableName");
            TableDetails details = describeTable(config, tableName);
            return textResult(renderTableDetails(details));
        });
    }

    private static McpServerFeatures.SyncToolSpecification createEntityContextTool(Config config) {
        McpSchema.Tool tool = new McpSchema.Tool(
                TOOL_ENTITY_CONTEXT,
                null,
                "Return a Java-oriented entity context for one table, including suggested class name, field names and Java types.",
                new McpSchema.JsonSchema("object", entityContextProperties(), List.of("tableName"), null, null, null),
                null,
                null,
                null
        );

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> arguments = request.arguments();
            String tableName = requiredString(arguments, "tableName");
            String packageName = asString(arguments.get("packageName"));
            TableDetails details = describeTable(config, tableName);
            return textResult(renderEntityContext(details, packageName));
        });
    }

    private static List<TableSummary> listTables(Config config, String tableLike, int limit) {
        String sql = """
                select table_name, table_comment
                from information_schema.tables
                where table_schema = ?
                  and table_type = 'BASE TABLE'
                  and (? is null or table_name like concat('%', ?, '%'))
                order by table_name
                limit ?
                """;
        List<TableSummary> result = new ArrayList<>();
        try (Connection connection = config.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, config.database());
            statement.setString(2, blankToNull(tableLike));
            statement.setString(3, blankToNull(tableLike));
            statement.setInt(4, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new TableSummary(
                            rs.getString("table_name"),
                            rs.getString("table_comment")
                    ));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list tables: " + ex.getMessage(), ex);
        }
        return result;
    }

    private static TableDetails describeTable(Config config, String tableName) {
        String tableSql = """
                select table_name, table_comment
                from information_schema.tables
                where table_schema = ?
                  and table_name = ?
                  and table_type = 'BASE TABLE'
                """;
        String columnSql = """
                select column_name,
                       data_type,
                       column_type,
                       is_nullable,
                       column_key,
                       column_default,
                       extra,
                       column_comment,
                       numeric_precision,
                       numeric_scale,
                       character_maximum_length,
                       ordinal_position
                from information_schema.columns
                where table_schema = ?
                  and table_name = ?
                order by ordinal_position
                """;

        try (Connection connection = config.openConnection()) {
            String comment = null;
            try (PreparedStatement statement = connection.prepareStatement(tableSql)) {
                statement.setString(1, config.database());
                statement.setString(2, tableName);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Table not found: " + tableName);
                    }
                    comment = rs.getString("table_comment");
                }
            }

            List<ColumnDetails> columns = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(columnSql)) {
                statement.setString(1, config.database());
                statement.setString(2, tableName);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        columns.add(new ColumnDetails(
                                rs.getString("column_name"),
                                rs.getString("data_type"),
                                rs.getString("column_type"),
                                "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                                "PRI".equalsIgnoreCase(rs.getString("column_key")),
                                rs.getString("column_default"),
                                rs.getString("extra"),
                                rs.getString("column_comment"),
                                rs.getObject("numeric_precision") == null ? null : rs.getInt("numeric_precision"),
                                rs.getObject("numeric_scale") == null ? null : rs.getInt("numeric_scale"),
                                rs.getObject("character_maximum_length") == null ? null : rs.getLong("character_maximum_length"),
                                rs.getInt("ordinal_position")
                        ));
                    }
                }
            }

            List<String> indexes = readIndexes(connection, config.database(), tableName);
            return new TableDetails(config.database(), tableName, comment, columns, indexes);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to describe table " + tableName + ": " + ex.getMessage(), ex);
        }
    }

    private static List<String> readIndexes(Connection connection, String database, String tableName) throws SQLException {
        List<String> indexes = new ArrayList<>();
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getIndexInfo(database, null, tableName, false, false)) {
            Map<String, List<String>> byIndex = new LinkedHashMap<>();
            Map<String, Boolean> uniqueFlags = new LinkedHashMap<>();
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                String columnName = rs.getString("COLUMN_NAME");
                if (indexName == null || columnName == null || "PRIMARY".equalsIgnoreCase(indexName)) {
                    continue;
                }
                byIndex.computeIfAbsent(indexName, key -> new ArrayList<>()).add(columnName);
                uniqueFlags.putIfAbsent(indexName, !rs.getBoolean("NON_UNIQUE"));
            }
            for (Map.Entry<String, List<String>> entry : byIndex.entrySet()) {
                String prefix = uniqueFlags.getOrDefault(entry.getKey(), false) ? "UNIQUE " : "";
                indexes.add(prefix + entry.getKey() + "(" + String.join(", ", entry.getValue()) + ")");
            }
        }
        return indexes;
    }

    private static McpSchema.CallToolResult textResult(String text) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(text)))
                .isError(false)
                .build();
    }

    private static Map<String, Object> listTablesProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("tableLike", Map.of(
                "type", "string",
                "description", "Optional substring filter for table names."
        ));
        properties.put("limit", Map.of(
                "type", "integer",
                "description", "Optional max number of rows to return. Default 100."
        ));
        return properties;
    }

    private static Map<String, Object> requiredTableNameProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("tableName", Map.of(
                "type", "string",
                "description", "Exact table name."
        ));
        return properties;
    }

    private static Map<String, Object> entityContextProperties() {
        Map<String, Object> properties = new LinkedHashMap<>(requiredTableNameProperties());
        properties.put("packageName", Map.of(
                "type", "string",
                "description", "Optional Java package name to include in the output."
        ));
        return properties;
    }

    private static String renderTableList(String database, String tableLike, List<TableSummary> tables) {
        StringBuilder sb = new StringBuilder();
        sb.append("database: ").append(database).append('\n');
        if (tableLike != null && !tableLike.isBlank()) {
            sb.append("filter: ").append(tableLike).append('\n');
        }
        sb.append("count: ").append(tables.size()).append("\n\n");
        for (TableSummary table : tables) {
            sb.append("- ").append(table.tableName());
            if (table.tableComment() != null && !table.tableComment().isBlank()) {
                sb.append(" // ").append(table.tableComment());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static String renderTableDetails(TableDetails details) {
        StringBuilder sb = new StringBuilder();
        sb.append("table: ").append(details.tableName()).append('\n');
        sb.append("database: ").append(details.database()).append('\n');
        if (details.tableComment() != null && !details.tableComment().isBlank()) {
            sb.append("comment: ").append(details.tableComment()).append('\n');
        }
        sb.append('\n');
        sb.append("columns:\n");
        for (ColumnDetails column : details.columns()) {
            sb.append("- ").append(column.columnName())
                    .append(" | ").append(column.columnType())
                    .append(" | java=").append(toJavaType(column))
                    .append(" | nullable=").append(column.nullable())
                    .append(" | pk=").append(column.primaryKey());
            if (column.defaultValue() != null) {
                sb.append(" | default=").append(column.defaultValue());
            }
            if (column.extra() != null && !column.extra().isBlank()) {
                sb.append(" | extra=").append(column.extra());
            }
            if (column.columnComment() != null && !column.columnComment().isBlank()) {
                sb.append(" | comment=").append(column.columnComment());
            }
            sb.append('\n');
        }
        if (!details.indexes().isEmpty()) {
            sb.append("\nindexes:\n");
            for (String index : details.indexes()) {
                sb.append("- ").append(index).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static String renderEntityContext(TableDetails details, String packageName) {
        String className = toClassName(details.tableName()) + "DO";
        StringBuilder sb = new StringBuilder();
        if (packageName != null && !packageName.isBlank()) {
            sb.append("package: ").append(packageName).append("\n\n");
        }
        sb.append("suggestedClassName: ").append(className).append('\n');
        sb.append("tableName: ").append(details.tableName()).append('\n');
        if (details.tableComment() != null && !details.tableComment().isBlank()) {
            sb.append("tableComment: ").append(details.tableComment()).append('\n');
        }
        sb.append('\n');
        sb.append("javaImports:\n");
        for (String importName : collectImports(details.columns())) {
            sb.append("- ").append(importName).append('\n');
        }
        sb.append('\n');
        sb.append("fields:\n");
        for (ColumnDetails column : details.columns()) {
            sb.append("- column=").append(column.columnName())
                    .append(" | field=").append(toFieldName(column.columnName()))
                    .append(" | javaType=").append(toJavaType(column))
                    .append(" | tableField=@TableField(\"").append(column.columnName()).append("\")");
            if (column.primaryKey()) {
                sb.append(" | tableId=true");
            }
            if (column.columnComment() != null && !column.columnComment().isBlank()) {
                sb.append(" | comment=").append(column.columnComment());
            }
            sb.append('\n');
        }
        sb.append('\n');
        sb.append("suggestedSkeleton:\n");
        sb.append("```java\n");
        if (packageName != null && !packageName.isBlank()) {
            sb.append("package ").append(packageName).append(";\n\n");
        }
        sb.append("import com.baomidou.mybatisplus.annotation.*;\n");
        for (String importName : collectImports(details.columns())) {
            if (!importName.startsWith("java.lang.")) {
                sb.append("import ").append(importName).append(";\n");
            }
        }
        sb.append("\n@TableName(\"").append(details.tableName()).append("\")\n");
        sb.append("public class ").append(className).append(" {\n\n");
        for (ColumnDetails column : details.columns()) {
            if (column.columnComment() != null && !column.columnComment().isBlank()) {
                sb.append("    /** ").append(column.columnComment()).append(" */\n");
            }
            if (column.primaryKey()) {
                sb.append("    @TableId(value = \"").append(column.columnName()).append("\")\n");
            } else {
                sb.append("    @TableField(\"").append(column.columnName()).append("\")\n");
            }
            sb.append("    private ").append(simpleName(toJavaType(column))).append(' ').append(toFieldName(column.columnName())).append(";\n\n");
        }
        sb.append("}\n");
        sb.append("```");
        return sb.toString().trim();
    }

    private static List<String> collectImports(List<ColumnDetails> columns) {
        List<String> imports = new ArrayList<>();
        for (ColumnDetails column : columns) {
            String javaType = toJavaType(column);
            if (javaType.contains(".")) {
                if (!imports.contains(javaType)) {
                    imports.add(javaType);
                }
            }
        }
        return imports;
    }

    private static String toJavaType(ColumnDetails column) {
        String type = column.dataType().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "bigint" -> column.nullable() ? "java.lang.Long" : "java.lang.Long";
            case "int", "integer", "mediumint", "smallint", "tinyint" -> {
                if ("tinyint".equals(type) && "tinyint(1)".equalsIgnoreCase(column.columnType())) {
                    yield "java.lang.Boolean";
                }
                yield "java.lang.Integer";
            }
            case "decimal", "numeric" -> BigDecimal.class.getName();
            case "double" -> "java.lang.Double";
            case "float" -> "java.lang.Float";
            case "bit" -> "java.lang.Boolean";
            case "date" -> LocalDate.class.getName();
            case "time" -> LocalTime.class.getName();
            case "datetime", "timestamp" -> LocalDateTime.class.getName();
            case "char", "varchar", "text", "tinytext", "mediumtext", "longtext", "json", "enum", "set" -> String.class.getName();
            case "blob", "binary", "varbinary", "tinyblob", "mediumblob", "longblob" -> "byte[]";
            default -> String.class.getName();
        };
    }

    private static String toClassName(String tableName) {
        StringBuilder sb = new StringBuilder();
        for (String part : tableName.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }

    private static String toFieldName(String columnName) {
        String className = toClassName(columnName);
        if (className.isEmpty()) {
            return columnName;
        }
        return Character.toLowerCase(className.charAt(0)) + className.substring(1);
    }

    private static String simpleName(String typeName) {
        int index = typeName.lastIndexOf('.');
        return index >= 0 ? typeName.substring(index + 1) : typeName;
    }

    private static String requiredString(Map<String, Object> arguments, String name) {
        String value = asString(arguments.get(name));
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + name);
        }
        return value.trim();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value, int defaultValue, int min, int max) {
        if (value == null) {
            return defaultValue;
        }
        int parsed = Integer.parseInt(String.valueOf(value));
        return Math.max(min, Math.min(max, parsed));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Config(String host, int port, String database, String username, String password) {

        static Config fromEnvironment() {
            String host = requiredEnv(ENV_HOST);
            String database = requiredEnv(ENV_DATABASE);
            String username = requiredEnv(ENV_USERNAME);
            String password = requiredEnv(ENV_PASSWORD);
            String portText = System.getenv().getOrDefault(ENV_PORT, "3306");
            return new Config(host, Integer.parseInt(portText), database, username, password);
        }

        Connection openConnection() throws SQLException {
            String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8";
            return DriverManager.getConnection(url, username, password);
        }

        private static String requiredEnv(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Missing environment variable: " + name);
            }
            return value.trim();
        }
    }

    private record TableSummary(String tableName, String tableComment) {
    }

    private record TableDetails(
            String database,
            String tableName,
            String tableComment,
            List<ColumnDetails> columns,
            List<String> indexes
    ) {
    }

    private record ColumnDetails(
            String columnName,
            String dataType,
            String columnType,
            boolean nullable,
            boolean primaryKey,
            String defaultValue,
            String extra,
            String columnComment,
            Integer numericPrecision,
            Integer numericScale,
            Long characterMaximumLength,
            int ordinalPosition
    ) {
    }
}
