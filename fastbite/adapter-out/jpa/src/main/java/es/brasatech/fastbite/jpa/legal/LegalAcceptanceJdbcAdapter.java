package es.brasatech.fastbite.jpa.legal;

import es.brasatech.fastbite.application.tenant.LegalAcceptancePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

@Component
public class LegalAcceptanceJdbcAdapter implements LegalAcceptancePort {

    private final JdbcTemplate jdbc;

    public LegalAcceptanceJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(String username, String document, String version, Instant acceptedAt) {
        // Works on PostgreSQL and H2 alike; an active acceptance of the same version is not duplicated,
        // while opting in again after a withdrawal inserts a new row and keeps the prior withdrawal intact.
        jdbc.update("""
                INSERT INTO public.legal_acceptances (id, username, document, version, accepted_at)
                SELECT ?, ?, ?, ?, ? WHERE NOT EXISTS (
                    SELECT 1 FROM public.legal_acceptances
                    WHERE username = ? AND document = ? AND version = ? AND withdrawn_at IS NULL)""",
                java.util.UUID.randomUUID().toString(), username, document, version, Timestamp.from(acceptedAt),
                username, document, version);
    }

    @Override
    public void withdraw(String username, String document, Instant withdrawnAt) {
        jdbc.update("""
                UPDATE public.legal_acceptances
                SET withdrawn_at = ?
                WHERE username = ? AND document = ? AND withdrawn_at IS NULL""",
                Timestamp.from(withdrawnAt), username, document);
    }

    @Override
    public boolean mayReceiveOffers(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM public.legal_acceptances
                WHERE username = ? AND document = ? AND withdrawn_at IS NULL""",
                Integer.class, username, MARKETING);
        return count != null && count > 0;
    }
}
