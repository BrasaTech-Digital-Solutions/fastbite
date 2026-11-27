package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.LocationNameHoldPort;
import es.brasatech.fastbite.application.tenant.TenantRegistrationRules;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.statement;
import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.transaction;
import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.update;

@Component
public class LocationNameHoldJdbcAdapter implements LocationNameHoldPort {

    private final DataSource source;

    public LocationNameHoldJdbcAdapter(DataSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public void hold(String slug, String owner, Instant heldUntil, Instant now) {
        String cleanSlug = TenantRegistrationRules.tenantId(slug);
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(heldUntil, "heldUntil");
        Instant tsNow = now == null ? Instant.now() : now;
        transaction(source, c -> {
            int updated = update(c,
                    """
                    UPDATE public.location_name_holds
                    SET owner = ?, held_until = ?, created_at = ?
                    WHERE LOWER(slug) = LOWER(?)
                    """,
                    owner, Timestamp.from(heldUntil), Timestamp.from(tsNow), cleanSlug);
            if (updated == 0) {
                update(c,
                        """
                        INSERT INTO public.location_name_holds (slug, owner, held_until, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                        cleanSlug, owner, Timestamp.from(heldUntil), Timestamp.from(tsNow));
            }
            return null;
        });
    }

    @Override
    public Optional<NameHold> findActive(String slug, Instant now) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        String cleanSlug = slug.trim().toLowerCase();
        Instant tsNow = now == null ? Instant.now() : now;
        return transaction(source, c -> {
            try (var q = statement(c,
                    """
                    SELECT slug, owner, held_until, created_at
                    FROM public.location_name_holds
                    WHERE LOWER(slug) = LOWER(?) AND held_until > ?
                    """,
                    cleanSlug, Timestamp.from(tsNow));
                 var r = q.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                return Optional.of(new NameHold(
                        r.getString("slug"),
                        r.getString("owner"),
                        r.getTimestamp("held_until").toInstant(),
                        r.getTimestamp("created_at").toInstant()));
            }
        });
    }

    @Override
    public void release(String slug) {
        if (slug == null || slug.isBlank()) {
            return;
        }
        String cleanSlug = slug.trim().toLowerCase();
        transaction(source, c -> {
            update(c, "DELETE FROM public.location_name_holds WHERE LOWER(slug) = LOWER(?)", cleanSlug);
            return null;
        });
    }

    @Override
    public int purgeExpired(Instant now) {
        Instant tsNow = now == null ? Instant.now() : now;
        return transaction(source, c ->
                update(c, "DELETE FROM public.location_name_holds WHERE held_until <= ?", Timestamp.from(tsNow)));
    }
}
