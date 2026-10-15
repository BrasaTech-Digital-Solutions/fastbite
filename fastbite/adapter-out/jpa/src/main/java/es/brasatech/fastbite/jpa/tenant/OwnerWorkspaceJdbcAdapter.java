package es.brasatech.fastbite.jpa.tenant;
import es.brasatech.fastbite.application.tenant.OwnerWorkspacePort;
import es.brasatech.fastbite.domain.tenant.SetupProgress;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.*;
import java.util.function.UnaryOperator;
import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.*;
@Component public class OwnerWorkspaceJdbcAdapter implements OwnerWorkspacePort {
    private final DataSource source;
    public OwnerWorkspaceJdbcAdapter(DataSource source) { this.source=source; }
    public SetupProgress read(String tenant) { return transaction(source,c->read(c,tenant)); }
    private SetupProgress read(Connection c,String tenant)throws SQLException {
        try(var q=statement(c,"SELECT * FROM public.owner_onboarding WHERE tenant_id=?",tenant); var r=q.executeQuery()) {
            return r.next()?new SetupProgress(r.getBoolean("started"),r.getBoolean("service_reviewed"),r.getBoolean("preview_reviewed"),r.getBoolean("dismissed")):SetupProgress.EMPTY;
        }
    }
    public void update(String tenant,UnaryOperator<SetupProgress> change) {
        transaction(source,c->{
            try(var q=statement(c,"SELECT tenant_id FROM public.tenant_locations WHERE tenant_id=? FOR UPDATE",tenant);var r=q.executeQuery()) {
                if(!r.next()) throw new IllegalArgumentException("Location not found.");
            }
            var next=change.apply(read(c,tenant));
            RegistrationJdbc.update(c,"INSERT INTO public.owner_onboarding (tenant_id) SELECT ? WHERE NOT EXISTS (SELECT 1 FROM public.owner_onboarding WHERE tenant_id=?)",tenant,tenant);
            RegistrationJdbc.update(c,"UPDATE public.owner_onboarding SET started=?,service_reviewed=?,preview_reviewed=?,dismissed=? WHERE tenant_id=?",next.started(),next.serviceReviewed(),next.previewReviewed(),next.dismissed(),tenant);
            return null;
        });
    }
}
