package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.NifTrialPort;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.statement;
import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.transaction;
import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.update;

@Component
public class NifTrialJdbcAdapter implements NifTrialPort {

    private final DataSource source;

    public NifTrialJdbcAdapter(DataSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public Optional<NifTrial> findByNif(String nif) {
        String normalized = normalize(nif);
        if (normalized == null) {
            return Optional.empty();
        }
        return transaction(source, c -> {
            try (var q = statement(c,
                    "SELECT nif, trial_started_at, trial_ends_at FROM public.nif_trials WHERE nif = ?",
                    normalized);
                 var r = q.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                return Optional.of(new NifTrial(
                        r.getString("nif"),
                        r.getTimestamp("trial_started_at").toInstant(),
                        r.getTimestamp("trial_ends_at").toInstant()));
            }
        });
    }

    @Override
    public NifTrial startOrGet(String nif, Instant now, int trialDays) {
        String normalized = normalize(nif);
        if (normalized == null) {
            throw new IllegalArgumentException("NIF is required");
        }
        Instant endsAt = now.plus(trialDays, ChronoUnit.DAYS);
        return transaction(source, c -> {
            try (var q = statement(c,
                    "SELECT nif, trial_started_at, trial_ends_at FROM public.nif_trials WHERE nif = ? FOR UPDATE",
                    normalized);
                 var r = q.executeQuery()) {
                if (r.next()) {
                    return new NifTrial(
                            r.getString("nif"),
                            r.getTimestamp("trial_started_at").toInstant(),
                            r.getTimestamp("trial_ends_at").toInstant());
                }
            }
            update(c,
                    """
                    INSERT INTO public.nif_trials (nif, trial_started_at, trial_ends_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    normalized, Timestamp.from(now), Timestamp.from(endsAt),
                    Timestamp.from(now), Timestamp.from(now));
            return new NifTrial(normalized, now, endsAt);
        });
    }

    @Override
    public Optional<NifTrial> extendByDays(String nif, int extraDays, Instant now) {
        String normalized = normalize(nif);
        if (normalized == null) {
            return Optional.empty();
        }
        return transaction(source, c -> {
            Instant startedAt;
            Instant currentEndsAt;
            try (var q = statement(c,
                    "SELECT trial_started_at, trial_ends_at FROM public.nif_trials WHERE nif = ? FOR UPDATE",
                    normalized);
                 var r = q.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                startedAt = r.getTimestamp("trial_started_at").toInstant();
                currentEndsAt = r.getTimestamp("trial_ends_at").toInstant();
            }
            Instant newEndsAt = currentEndsAt.plus(extraDays, ChronoUnit.DAYS);
            update(c,
                    "UPDATE public.nif_trials SET trial_ends_at = ?, updated_at = ? WHERE nif = ?",
                    Timestamp.from(newEndsAt), Timestamp.from(now), normalized);
            return Optional.of(new NifTrial(normalized, startedAt, newEndsAt));
        });
    }

    @Override
    public Optional<String> findNifForTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        return transaction(source, c -> {
            try (var q = statement(c,
                    "SELECT tax_id FROM public.fiscal_issuers WHERE tenant_id = ? ORDER BY first_seen_at DESC LIMIT 1",
                    tenantId);
                 var r = q.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(normalize(r.getString("tax_id")));
            }
        });
    }

    @Override
    public List<String> findTenantsForNif(String nif) {
        String normalized = normalize(nif);
        if (normalized == null) {
            return List.of();
        }
        return transaction(source, c -> {
            List<String> tenants = new ArrayList<>();
            try (var q = statement(c,
                    "SELECT tenant_id FROM public.fiscal_issuers WHERE UPPER(TRIM(tax_id)) = ?",
                    normalized);
                 var r = q.executeQuery()) {
                while (r.next()) {
                    tenants.add(r.getString("tenant_id"));
                }
            }
            return tenants;
        });
    }

    static String normalize(String nif) {
        if (nif == null) {
            return null;
        }
        String trimmed = nif.strip().toUpperCase(Locale.ROOT);
        return trimmed.isEmpty() || trimmed.length() > 16 ? null : trimmed;
    }
}
