package es.brasatech.fastbite.security;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/** Deletes only sessions belonging to the same account, even where staff usernames overlap. */
@Component
public class AccountSessionInvalidator {
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public AccountSessionInvalidator(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    public void invalidate(String username, String homeTenantId, String keepSessionId) {
        sessions.findByPrincipalName(username).forEach((id, session) -> {
            if (id.equals(keepSessionId)) return;
            SecurityContext context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (context == null || context.getAuthentication() == null
                    || !(context.getAuthentication().getPrincipal() instanceof TenantUser user)) return;
            boolean sameTenant = homeTenantId == null ? user.isPlatformAccount() : user.belongsTo(homeTenantId);
            if (sameTenant && username.equals(user.getUsername())) sessions.deleteById(id);
        });
    }
}
