package es.brasatech.fastbite.jpa.fiscal;

import es.brasatech.fastbite.application.fiscal.GuestInvoiceRateLimit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/** Reuses the public quota table and its global lock, with independent invoice purposes. */
@Component
public class GuestInvoiceRateLimitJdbc implements GuestInvoiceRateLimit {
    private static final String TICKET = "GUEST_INVOICE_TICKET";
    private static final String CLIENT = "GUEST_INVOICE_CLIENT";
    private final DataSource source;
    private final Clock clock;

    @Autowired
    public GuestInvoiceRateLimitJdbc(DataSource source) { this(source, Clock.systemUTC()); }
    GuestInvoiceRateLimitJdbc(DataSource source, Clock clock) { this.source = source; this.clock = clock; }

    @Override public boolean tryAcquire(String ticketKey, String clientKey) {
        long now = clock.millis(), cutoff = now - Duration.ofHours(1).toMillis();
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement lock = connection.prepareStatement("SELECT id FROM public.signup_reservation_lock WHERE id = 1 FOR UPDATE");
                        var rows = lock.executeQuery()) {
                    if (!rows.next()) throw new SQLException("Quota lock is missing");
                }
                try (PreparedStatement cleanup = connection.prepareStatement(
                        "DELETE FROM public.account_email_limits WHERE purpose IN (?, ?) AND sent_at <= ?")) {
                    cleanup.setString(1, TICKET); cleanup.setString(2, CLIENT); cleanup.setLong(3, cutoff); cleanup.executeUpdate();
                }
                boolean allowed = count(connection, ticketKey, TICKET, cutoff) < 5
                        && count(connection, clientKey, CLIENT, cutoff) < 10;
                if (allowed) {
                    insert(connection, ticketKey, TICKET, now);
                    insert(connection, clientKey, CLIENT, now);
                }
                connection.commit();
                return allowed;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not check guest invoice limit", e);
        }
    }

    private static long count(Connection connection, String key, String purpose, long cutoff) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM public.account_email_limits WHERE subject_key = ? AND purpose = ? AND sent_at > ?")) {
            query.setString(1, key); query.setString(2, purpose); query.setLong(3, cutoff);
            try (var rows = query.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }

    private static void insert(Connection connection, String key, String purpose, long now) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO public.account_email_limits (id, subject_key, purpose, sent_at) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, UUID.randomUUID().toString()); insert.setString(2, key); insert.setString(3, purpose); insert.setLong(4, now); insert.executeUpdate();
        }
    }
}
