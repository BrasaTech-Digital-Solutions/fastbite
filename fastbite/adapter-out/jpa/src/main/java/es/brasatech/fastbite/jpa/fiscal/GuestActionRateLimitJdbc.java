package es.brasatech.fastbite.jpa.fiscal;

import es.brasatech.fastbite.application.fiscal.GuestActionRateLimit;
import es.brasatech.fastbite.application.fiscal.GuestInvoiceRateLimit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Cross-instance guest quotas use their own short transactional lock, independent of sign-up. */
@Component
public class GuestActionRateLimitJdbc implements GuestInvoiceRateLimit, GuestActionRateLimit {
    private static final Duration HOUR = Duration.ofHours(1);
    private final DataSource source;
    private final Clock clock;

    @Autowired
    public GuestActionRateLimitJdbc(DataSource source) { this(source, Clock.systemUTC()); }
    GuestActionRateLimitJdbc(DataSource source, Clock clock) { this.source = source; this.clock = clock; }

    @Override public boolean tryAcquire(String ticketKey, String clientKey) {
        return tryAcquire(Action.INVOICE, ticketKey, clientKey);
    }

    @Override public boolean tryAcquire(Action action, String ticketKey, String clientKey) {
        Objects.requireNonNull(action, "action");
        if (ticketKey == null || ticketKey.isBlank() || clientKey == null || clientKey.isBlank()) {
            throw new IllegalArgumentException("Guest quota keys are required");
        }
        long now = clock.millis();
        long ticketCutoff = now - (action == Action.EMAIL ? Duration.ofHours(24) : HOUR).toMillis();
        long clientCutoff = now - HOUR.toMillis();
        String ticketPurpose = "GUEST_" + action.name() + "_TICKET";
        String clientPurpose = "GUEST_" + action.name() + "_CLIENT";
        int ticketMaximum = action == Action.EMAIL ? 3 : 5;
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement lock = connection.prepareStatement("SELECT id FROM public.guest_action_lock WHERE id = 1 FOR UPDATE");
                        var rows = lock.executeQuery()) {
                    if (!rows.next()) throw new SQLException("Guest quota lock is missing");
                }
                // Delete only this action's expired rows, respecting its individual retention windows.
                cleanup(connection, ticketPurpose, ticketCutoff);
                cleanup(connection, clientPurpose, clientCutoff);
                boolean allowed = count(connection, ticketKey, ticketPurpose, ticketCutoff) < ticketMaximum
                        && count(connection, clientKey, clientPurpose, clientCutoff) < 10;
                if (allowed) {
                    insert(connection, ticketKey, ticketPurpose, now);
                    insert(connection, clientKey, clientPurpose, now);
                }
                connection.commit();
                return allowed;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not check guest action limit", e);
        }
    }

    private static void cleanup(Connection connection, String purpose, long cutoff) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("DELETE FROM public.guest_action_limits WHERE purpose = ? AND occurred_at <= ?")) {
            query.setString(1, purpose); query.setLong(2, cutoff); query.executeUpdate();
        }
    }

    private static long count(Connection connection, String key, String purpose, long cutoff) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM public.guest_action_limits WHERE subject_key = ? AND purpose = ? AND occurred_at > ?")) {
            query.setString(1, key); query.setString(2, purpose); query.setLong(3, cutoff);
            try (var rows = query.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }

    private static void insert(Connection connection, String key, String purpose, long now) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO public.guest_action_limits (id, subject_key, purpose, occurred_at) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, UUID.randomUUID().toString()); insert.setString(2, key); insert.setString(3, purpose); insert.setLong(4, now); insert.executeUpdate();
        }
    }
}
