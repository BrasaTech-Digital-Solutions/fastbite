package es.brasatech.fastbite.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;

class AccountSessionInvalidatorTest {
    @Test void deletesOnlyTheMatchingAccountAndKeepsCurrentSession() {
        @SuppressWarnings("unchecked") var repo = (FindByIndexNameSessionRepository<MapSession>) mock(FindByIndexNameSessionRepository.class);
        var owner = session("owner", null); var first = session("first", "one");
        var current = session("current", "one"); var other = session("other", "two");
        when(repo.findByPrincipalName("alex")).thenReturn(Map.of("owner", owner, "first", first, "current", current, "other", other));
        new AccountSessionInvalidator(repo).invalidate("alex", "one", "current");
        verify(repo).deleteById("first");
        verify(repo, never()).deleteById("owner");
        verify(repo, never()).deleteById("current");
        verify(repo, never()).deleteById("other");
    }
    private static MapSession session(String id, String tenant) {
        var session = new MapSession(id);
        var user = new TenantUser("alex", "hash", true, List.of(), tenant);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(user, null, List.of());
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(authentication));
        return session;
    }
}
