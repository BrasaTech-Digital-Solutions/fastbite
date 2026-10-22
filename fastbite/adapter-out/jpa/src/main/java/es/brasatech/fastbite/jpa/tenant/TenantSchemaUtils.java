package es.brasatech.fastbite.jpa.tenant;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Utility class for multi-tenant database operations supporting both
 * PostgreSQL (SET search_path) and H2 (SET SCHEMA).
 */
public final class TenantSchemaUtils {

    private static volatile Boolean isPostgres;

    private TenantSchemaUtils() {
    }

    /**
     * Checks whether the given connection is connected to a PostgreSQL database.
     * Caches the result to avoid redundant metadata queries on pooled connections.
     */
    public static boolean isPostgreSQL(Connection connection) {
        if (isPostgres == null) {
            synchronized (TenantSchemaUtils.class) {
                if (isPostgres == null) {
                    try {
                        String productName = connection.getMetaData().getDatabaseProductName();
                        isPostgres = productName != null && productName.toLowerCase().contains("postgres");
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
        }
        return isPostgres;
    }

    /**
     * Resets the cached database dialect flag (useful for testing).
     */
    public static void resetCache() {
        isPostgres = null;
    }

    /**
     * Resolves the default schema name based on the database dialect:
     * - PostgreSQL: "public" (lowercase)
     * - H2: "PUBLIC" (uppercase)
     */
    public static String resolveDefaultSchemaName(Connection connection) {
        return isPostgreSQL(connection) ? "public" : "PUBLIC";
    }

    /**
     * Resolves the schema name for a given tenant identifier.
     */
    public static String resolveSchemaName(Connection connection, String tenantId) {
        if (tenantId == null || tenantId.trim().isEmpty() || "default".equalsIgnoreCase(tenantId)) {
            return resolveDefaultSchemaName(connection);
        }
        return "tenant_" + tenantId.toLowerCase();
    }

    /**
     * Creates the schema if it does not already exist.
     */
    public static void createSchemaIfNotExists(Connection connection, String schemaName) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + schemaName);
        }
    }

    /**
     * Switches the active schema for the current connection using the dialect-appropriate command:
     * - PostgreSQL: SET search_path TO schemaName, public (or TO public for default)
     * - H2: SET SCHEMA schemaName
     */
    public static void switchSchema(Connection connection, String schemaName) throws SQLException {
        boolean postgres = isPostgreSQL(connection);
        try (Statement statement = connection.createStatement()) {
            if (postgres) {
                if ("public".equalsIgnoreCase(schemaName)) {
                    statement.execute("SET search_path TO public");
                } else {
                    statement.execute("SET search_path TO " + schemaName + ", public");
                }
            } else {
                statement.execute("SET SCHEMA " + schemaName);
            }
        }
    }

    /**
     * Resolves the tenant schema and switches to it.
     */
    public static void switchTenant(Connection connection, String tenantId) throws SQLException {
        String schemaName = resolveSchemaName(connection, tenantId);
        switchSchema(connection, schemaName);
    }

    /**
     * Creates the schema if needed and switches the connection to it.
     */
    public static void createAndSwitchSchema(Connection connection, String schemaName) throws SQLException {
        createSchemaIfNotExists(connection, schemaName);
        switchSchema(connection, schemaName);
    }
}
