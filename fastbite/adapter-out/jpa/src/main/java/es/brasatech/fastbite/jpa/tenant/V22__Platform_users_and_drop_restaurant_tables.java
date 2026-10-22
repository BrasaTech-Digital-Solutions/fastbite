package es.brasatech.fastbite.jpa.tenant;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * PUBLIC only. Until V21, PUBLIC also ran the restaurant migrations, so it holds empty copies of every restaurant
 * table. A query without a restaurant context silently used them. This migration gives users and user_roles (the
 * platform accounts) their own platform definition, refuses to continue if any restaurant copy holds real data, and
 * drops the copies. Immutable: future changes belong in a new version.
 */
@Slf4j
public class V22__Platform_users_and_drop_restaurant_tables extends BaseJavaMigration {

    /** Every table the restaurant migrations create, except users and user_roles. */
    static final List<String> RESTAURANT_TABLES = List.of(
            "cart_item_customizations", "cart_items", "orders", "table_orders", "dining_tables",
            "product_customizations", "group_products", "customization_options", "customizations", "products", "groups",
            "discount_rules", "payment_modes", "money_denominations", "payment_configs", "restaurant_settings",
            "order_counter", "verifactu_records", "fiscal_ticket_cancellations", "fiscal_sale_orders", "fiscal_tickets",
            "fiscal_series", "fiscal_profile", "verifactu_chain", "verifactu_installations");

    @Override public Integer getChecksum() { return 1; }

    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String schema = context.getConfiguration().getDefaultSchema();

        try (Statement statement = connection.createStatement()) {
            // Same definition as restaurant V1, which created them here until now.
            statement.execute("CREATE TABLE IF NOT EXISTS " + schema + ".users ("
                    + "id VARCHAR(36) NOT NULL PRIMARY KEY, "
                    + "username VARCHAR(255) NOT NULL UNIQUE, "
                    + "password VARCHAR(255) NOT NULL, "
                    + "full_name VARCHAR(255), "
                    + "active BOOLEAN NOT NULL DEFAULT TRUE, "
                    + "tenant_id VARCHAR(255))");
            statement.execute("CREATE TABLE IF NOT EXISTS " + schema + ".user_roles ("
                    + "user_id VARCHAR(36) NOT NULL, "
                    + "role VARCHAR(255) NOT NULL, "
                    + "PRIMARY KEY (user_id, role), "
                    + "FOREIGN KEY (user_id) REFERENCES " + schema + ".users(id) ON DELETE CASCADE)");
        }

        List<String> present = new ArrayList<>();
        for (String table : RESTAURANT_TABLES) {
            if (tableExists(connection, schema, table)) {
                present.add(table);
                long rows = unexpectedRows(connection, schema + "." + table, table);
                if (rows > 0) {
                    throw new IllegalStateException("Refusing to drop " + schema + "." + table + ": it holds " + rows
                            + " row(s) of real data. Move or remove them by hand, then deploy again.");
                }
            }
        }
        if (present.isEmpty()) return;

        // One statement: the database resolves the foreign keys inside the set, and still refuses if anything
        // outside it depends on these tables.
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + String.join(", ", present.stream().map(t -> schema + "." + t).toList()));
        }
        log.info("Dropped the unused restaurant tables from {}: {}", schema, String.join(", ", present));
    }

    /** Rows other than the seed rows the restaurant migrations insert. */
    private static long unexpectedRows(Connection connection, String qualified, String table) throws SQLException {
        String sql = switch (table) {
            case "order_counter" -> "SELECT COUNT(*) FROM " + qualified + " WHERE id <> 1 OR last_number <> 0";
            case "verifactu_chain" -> "SELECT COUNT(*) FROM " + qualified
                    + " WHERE chain NOT IN ('REAL', 'TRAINING') OR records <> 0 OR last_record_id IS NOT NULL";
            default -> "SELECT COUNT(*) FROM " + qualified;
        };
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static boolean tableExists(Connection connection, String schema, String table) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE LOWER(table_schema) = LOWER(?) AND LOWER(table_name) = ?")) {
            query.setString(1, schema);
            query.setString(2, table);
            try (ResultSet result = query.executeQuery()) {
                return result.next() && result.getInt(1) > 0;
            }
        }
    }
}
