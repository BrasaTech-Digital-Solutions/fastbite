package es.brasatech.fastbite.jpa.tenant;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

final class RegistrationJdbc {
    private RegistrationJdbc() { }

    static PreparedStatement statement(Connection connection, String sql, Object... values) throws SQLException {
        var statement = connection.prepareStatement(sql);
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
        return statement;
    }

    static int update(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = statement(connection, sql, values)) { return statement.executeUpdate(); }
    }

    static <T> T transaction(DataSource dataSource, Work<T> work) {
        try (var connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                if (e instanceof IllegalArgumentException invalid) throw invalid;
                if (e instanceof es.brasatech.fastbite.domain.tenant.TenantProvisioningInProgressException inProgress) throw inProgress;
                if (e instanceof SQLException se && isLockConflict(se)) {
                    throw new es.brasatech.fastbite.domain.tenant.TenantProvisioningInProgressException(
                            "Restaurant setup is currently in progress. Please retry.");
                }
                // SQL errors may contain passwords or customer details. Do not propagate their text.
                throw new IllegalStateException("Restaurant registration could not be completed. Please retry.");
            } finally { connection.setAutoCommit(autoCommit); }
        } catch (SQLException e) {
            throw new IllegalStateException("Restaurant registration database unavailable.");
        }
    }

    private static boolean isLockConflict(SQLException se) {
        return "55P03".equals(se.getSQLState()) || ("HYT00".equals(se.getSQLState()) && se.getErrorCode() == 50200);
    }

    interface Work<T> { T run(Connection connection) throws SQLException; }
}
