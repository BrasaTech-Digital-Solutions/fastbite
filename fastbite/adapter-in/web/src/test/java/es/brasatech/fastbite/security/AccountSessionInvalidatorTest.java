package es.brasatech.fastbite.security;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test void reloadsSessionByIdWhenPrincipalLookupDropsSecurityContext() {
        @SuppressWarnings("unchecked") var repo = (FindByIndexNameSessionRepository<MapSession>) mock(FindByIndexNameSessionRepository.class);
        var partialOwner = new MapSession("owner-1");
        partialOwner.setAttribute(OwnerVerificationFilter.VERIFIED_SESSION_ATTRIBUTE, "alex");
        var partialOtherTenant = new MapSession("staff-1");
        partialOtherTenant.setAttribute("CSRF", "token");
        when(repo.findByPrincipalName("alex")).thenReturn(Map.of("owner-1", partialOwner, "staff-1", partialOtherTenant));
        when(repo.findById("owner-1")).thenReturn(session("owner-1", null));
        when(repo.findById("staff-1")).thenReturn(session("staff-1", "one"));

        new AccountSessionInvalidator(repo).invalidate("alex", null, null);

        verify(repo).deleteById("owner-1");
        verify(repo, never()).deleteById("staff-1");
    }

    @Test void orderedPrincipalQueryPreventsInterleavedAttributesFromDroppingSecurityContext() throws Exception {
        long now = Instant.now().toEpochMilli();
        List<SessionAttrRow> interleavedRows = List.of(
                new SessionAttrRow("p-s1", "s1", now, now, 1800, HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, serialize(securityContext("alex", null))),
                new SessionAttrRow("p-s2", "s2", now, now, 1800, HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, serialize(securityContext("alex", null))),
                new SessionAttrRow("p-st", "staff1", now, now, 1800, HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, serialize(securityContext("alex", "one"))),
                new SessionAttrRow("p-s1", "s1", now, now, 1800, OwnerVerificationFilter.VERIFIED_SESSION_ATTRIBUTE, serialize("alex")),
                new SessionAttrRow("p-s2", "s2", now, now, 1800, "CSRF_TOKEN", serialize("tok-2")),
                new SessionAttrRow("p-st", "staff1", now, now, 1800, "CSRF_TOKEN", serialize("tok-staff"))
        );

        AtomicReference<String> executedSql = new AtomicReference<>();
        List<String> deletedSessionIds = new ArrayList<>();
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    ResultSetExtractor<?> extractor = invocation.getArgument(2);
                    executedSql.set(sql);
                    List<SessionAttrRow> rows = sql.toUpperCase().contains("ORDER BY S.SESSION_ID")
                            ? interleavedRows.stream().sorted(Comparator.comparing(SessionAttrRow::sessionId)).toList()
                            : interleavedRows;
                    return extractor.extractData(resultSetFor(rows));
                });
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            if (args.length >= 2 && args[1] instanceof String sessionId) {
                deletedSessionIds.add(sessionId);
            }
            return 1;
        });

        TransactionOperations tx = new TransactionOperations() {
            @Override public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(mock(TransactionStatus.class));
            }
            @Override public void executeWithoutResult(Consumer<TransactionStatus> action) {
                action.accept(mock(TransactionStatus.class));
            }
        };

        // 1. Reproduce the Spring Session 4.0.3 bug with the default unordered query:
        var uncustomized = new JdbcIndexedSessionRepository(jdbc, tx);
        uncustomized.setTableName("PUBLIC.SPRING_SESSION");
        Map<String, ? extends Session> broken = uncustomized.findByPrincipalName("alex");
        assertThat(broken.get("s1").<Object>getAttribute(OwnerVerificationFilter.VERIFIED_SESSION_ATTRIBUTE)).isEqualTo("alex");
        assertThat(broken.get("s1").<Object>getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
        assertThat(broken.get("s2").<Object>getAttribute("CSRF_TOKEN")).isEqualTo("tok-2");
        assertThat(broken.get("s2").<Object>getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();

        // 2. Apply JdbcSessionConfig customizer: query is ordered by S.SESSION_ID and uses PUBLIC.SPRING_SESSION.
        var customized = new JdbcIndexedSessionRepository(jdbc, tx);
        customized.setTableName("PUBLIC.SPRING_SESSION");
        new JdbcSessionConfig().orderedPrincipalSessionQueryCustomizer().customize(customized);

        new AccountSessionInvalidator(customized).invalidate("alex", null, null);

        assertThat(executedSql.get())
                .contains("FROM PUBLIC.SPRING_SESSION S")
                .contains("LEFT JOIN PUBLIC.SPRING_SESSION_ATTRIBUTES SA")
                .contains("ORDER BY S.SESSION_ID");
        assertThat(deletedSessionIds).containsExactlyInAnyOrder("s1", "s2");
    }

    private record SessionAttrRow(String primaryId, String sessionId, long creationTime, long lastAccessTime,
                                  int maxInactiveInterval, String attributeName, byte[] attributeBytes) {}

    private static ResultSet resultSetFor(List<SessionAttrRow> rows) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        AtomicInteger idx = new AtomicInteger(-1);
        when(rs.next()).thenAnswer(inv -> idx.incrementAndGet() < rows.size());
        when(rs.getString("PRIMARY_ID")).thenAnswer(inv -> rows.get(idx.get()).primaryId());
        when(rs.getString("SESSION_ID")).thenAnswer(inv -> rows.get(idx.get()).sessionId());
        when(rs.getLong("CREATION_TIME")).thenAnswer(inv -> rows.get(idx.get()).creationTime());
        when(rs.getLong("LAST_ACCESS_TIME")).thenAnswer(inv -> rows.get(idx.get()).lastAccessTime());
        when(rs.getInt("MAX_INACTIVE_INTERVAL")).thenAnswer(inv -> rows.get(idx.get()).maxInactiveInterval());
        when(rs.getString("ATTRIBUTE_NAME")).thenAnswer(inv -> rows.get(idx.get()).attributeName());
        when(rs.findColumn("ATTRIBUTE_BYTES")).thenReturn(7);
        when(rs.getBytes(7)).thenAnswer(inv -> rows.get(idx.get()).attributeBytes());
        when(rs.getBytes("ATTRIBUTE_BYTES")).thenAnswer(inv -> rows.get(idx.get()).attributeBytes());
        return rs;
    }

    private static byte[] serialize(Object value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private static SecurityContextImpl securityContext(String username, String tenant) {
        var user = new TenantUser(username, "hash", true, List.of(), tenant);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(user, null, List.of());
        return new SecurityContextImpl(authentication);
    }

    private static MapSession session(String id, String tenant) {
        var session = new MapSession(id);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext("alex", tenant));
        return session;
    }
}
