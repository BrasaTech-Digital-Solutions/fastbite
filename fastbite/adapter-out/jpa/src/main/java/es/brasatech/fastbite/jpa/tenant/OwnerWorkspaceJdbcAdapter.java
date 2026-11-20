package es.brasatech.fastbite.jpa.tenant;
import es.brasatech.fastbite.application.tenant.OwnerWorkspacePort;
import es.brasatech.fastbite.domain.tenant.SetupProgress;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.*;
import java.util.Optional;
import java.util.function.UnaryOperator;
import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.*;
@Component public class OwnerWorkspaceJdbcAdapter implements OwnerWorkspacePort {
    private final DataSource source;
    public OwnerWorkspaceJdbcAdapter(DataSource source) { this.source=source; }
    @Override
    public Optional<OwnerSigner> findOwnerSigner(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return transaction(source, c -> {
            try (var q = statement(c,
                    "SELECT full_name, email, email_verified_at FROM public.users WHERE username = ? AND active = TRUE",
                    username.strip());
                 var r = q.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                String fullName = r.getString("full_name");
                String verifiedEmail = r.getObject("email_verified_at") != null ? r.getString("email") : null;
                return Optional.of(new OwnerSigner(fullName, verifiedEmail));
            }
        });
    }
    public SetupProgress read(String tenant) { return transaction(source,c->read(c,tenant)); }
    private SetupProgress read(Connection c,String tenant)throws SQLException {
        try(var q=statement(c,"SELECT * FROM public.owner_onboarding WHERE tenant_id=?",tenant); var r=q.executeQuery()) {
            return r.next()?new SetupProgress(r.getBoolean("started"),r.getBoolean("service_reviewed"),r.getBoolean("preview_reviewed"),r.getBoolean("dismissed"),r.getBoolean("practice_guest_viewed"),r.getBoolean("practice_table_ordered"),r.getBoolean("practice_kitchen_prepared"),r.getBoolean("practice_counter_charged"),r.getBoolean("practice_menu_personalized"),r.getBoolean("practice_team_tested"),r.getBoolean("work_alone"),r.getBoolean("opened")):SetupProgress.EMPTY;
        }
    }
    public void update(String tenant,UnaryOperator<SetupProgress> change) {
        transaction(source,c->{
            try(var q=statement(c,"SELECT tenant_id FROM public.tenant_locations WHERE tenant_id=? FOR UPDATE",tenant);var r=q.executeQuery()) {
                if(!r.next()) throw new IllegalArgumentException("Location not found.");
            }
            var next=change.apply(read(c,tenant));
            RegistrationJdbc.update(c,"INSERT INTO public.owner_onboarding (tenant_id) SELECT ? WHERE NOT EXISTS (SELECT 1 FROM public.owner_onboarding WHERE tenant_id=?)",tenant,tenant);
            RegistrationJdbc.update(c,"UPDATE public.owner_onboarding SET started=?,service_reviewed=?,preview_reviewed=?,dismissed=?,practice_guest_viewed=?,practice_table_ordered=?,practice_kitchen_prepared=?,practice_counter_charged=?,practice_menu_personalized=?,practice_team_tested=?,work_alone=?,opened=? WHERE tenant_id=?",next.started(),next.serviceReviewed(),next.previewReviewed(),next.dismissed(),next.practiceGuestViewed(),next.practiceTableOrdered(),next.practiceKitchenPrepared(),next.practiceCounterCharged(),next.practiceMenuPersonalized(),next.practiceTeamTested(),next.workAlone(),next.opened(),tenant);
            return null;
        });
    }
}
