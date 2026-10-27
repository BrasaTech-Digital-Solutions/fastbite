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
        // Works on PostgreSQL and H2 alike; the first acceptance of a version is the one that counts
        jdbc.update("""
                INSERT INTO public.legal_acceptances (username, document, version, accepted_at)
                SELECT ?, ?, ?, ? WHERE NOT EXISTS (
                    SELECT 1 FROM public.legal_acceptances WHERE username = ? AND document = ? AND version = ?)""",
                username, document, version, Timestamp.from(acceptedAt), username, document, version);
    }
}
