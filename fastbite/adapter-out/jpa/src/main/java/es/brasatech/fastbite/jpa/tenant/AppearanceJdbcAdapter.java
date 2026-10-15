package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.tenant.AppearancePort;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.*;

@Component public class AppearanceJdbcAdapter implements AppearancePort {
    private final DataSource source;
    public AppearanceJdbcAdapter(DataSource source) { this.source = source; }
    private String read(String sql, String id) {
        return transaction(source, c -> {
            try (var q = statement(c, sql, id); var r = q.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        });
    }
    public String ownerTheme(String owner) {
        return read("SELECT theme_id FROM public.owner_appearance WHERE owner_username=?", owner);
    }
    public String locationTheme(String tenant) {
        return read("SELECT theme_id FROM public.location_appearance WHERE tenant_id=?", tenant);
    }
    public String effectiveTheme(String tenant) {
        return read("SELECT COALESCE(a.theme_id,o.theme_id) FROM public.tenant_locations l "
                + "LEFT JOIN public.location_appearance a ON a.tenant_id=l.tenant_id "
                + "LEFT JOIN public.owner_appearance o ON o.owner_username=l.owner_username WHERE l.tenant_id=?", tenant);
    }
    public void saveOwnerTheme(String owner, String theme) {
        transaction(source, c -> {
            // Serialize changes for the same owner, including the first preference save.
            try (var q=statement(c,"SELECT id FROM public.users WHERE username=? FOR UPDATE",owner); var r=q.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("Owner not found.");
            }
            update(c,"DELETE FROM public.owner_appearance WHERE owner_username=?",owner);
            update(c,"INSERT INTO public.owner_appearance (owner_username,theme_id) VALUES (?,?)",owner,theme);
            return null;
        });
    }
    public void saveLocationTheme(String tenant, String theme) {
        transaction(source, c -> {
            try(var q=statement(c,"SELECT tenant_id FROM public.tenant_locations WHERE tenant_id=? FOR UPDATE",tenant);var r=q.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("Location not found.");
            }
            update(c,"DELETE FROM public.location_appearance WHERE tenant_id=?",tenant);
            if (theme != null) update(c,"INSERT INTO public.location_appearance (tenant_id,theme_id) VALUES (?,?)",tenant,theme);
            return null;
        });
    }
}
