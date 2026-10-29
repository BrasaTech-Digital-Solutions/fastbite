package es.brasatech.fastbite.application.mail;

import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountMailOutboxServiceTest {
    private final AccountMailOutboxPort outbox = mock(AccountMailOutboxPort.class);
    private final OwnerSetupMailPort mail = mock(OwnerSetupMailPort.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final AccountMailOutboxPort.Claim claim = new AccountMailOutboxPort.Claim("id", "lease", "checkout", 1);

    @Test void storesOnlyTheHashBeforeSendingAndMarksSuccess() throws Exception {
        when(outbox.claim(any())).thenReturn(Optional.of(claim));
        when(outbox.issue(eq(claim), anyString(), any())).thenReturn(Optional.of(new AccountMailOutboxPort.Recipient("owner@example.test", "owner", "en")));
        var service = new AccountMailOutboxService(outbox, mail, new PlatformEnvironment("production"), clock, "https://fastbite.example");
        assertEquals(1, service.run(1));
        var hash = ArgumentCaptor.forClass(String.class); var link = ArgumentCaptor.forClass(String.class);
        verify(outbox).issue(eq(claim), hash.capture(), any());
        verify(mail).sendSetupLink(eq("owner@example.test"), eq("owner"), link.capture(), eq("en"));
        String token = link.getValue().substring(link.getValue().indexOf("token=") + 6);
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))), hash.getValue());
        assertFalse(link.getValue().contains(hash.getValue()));
        verify(outbox).sent(eq(claim), any());
    }
    @Test void failedDeliveryIsRecordedForRetryWithoutEscaping() {
        when(outbox.claim(any())).thenReturn(Optional.of(claim));
        when(outbox.issue(eq(claim), anyString(), any())).thenReturn(Optional.of(new AccountMailOutboxPort.Recipient("owner@example.test", "owner", "en")));
        doThrow(new IllegalStateException("smtp unavailable")).when(mail).sendSetupLink(anyString(), anyString(), anyString(), anyString());
        assertEquals(0, new AccountMailOutboxService(outbox, mail, new PlatformEnvironment("production"), clock, "https://example.test").run(1));
        verify(outbox).failed(eq(claim), any());
        verify(outbox, never()).sent(any(), any());
    }
    @Test void sandboxNeverClaimsOrSends() {
        assertEquals(0, new AccountMailOutboxService(outbox, mail, new PlatformEnvironment("sandbox"), clock, "https://example.test").run(1));
        verifyNoInteractions(outbox, mail);
    }
    @Test void dispatcherDoesNotAccessOutboxOrMailInsideAnOuterTransaction() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            var service = new AccountMailOutboxService(outbox, mail, new PlatformEnvironment("production"), clock, "https://example.test");
            assertEquals(0, service.run(1));
            service.runForSetup("checkout");
            verifyNoInteractions(outbox, mail);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
    @Test void warningReportsOnlySafeFailureDetails() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AccountMailOutboxService.class);
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start(); logger.addAppender(captured);
        try {
            when(outbox.claim(any())).thenReturn(Optional.of(claim));
            when(outbox.issue(eq(claim), anyString(), any())).thenReturn(Optional.of(new AccountMailOutboxPort.Recipient("owner@example.test", "owner", "en")));
            when(outbox.failed(eq(claim), any())).thenReturn("PENDING");
            doThrow(new IllegalStateException("secret-token-private")).when(mail).sendSetupLink(anyString(), anyString(), anyString(), anyString());
            new AccountMailOutboxService(outbox, mail, new PlatformEnvironment("production"), clock, "https://example.test").run(1);
            assertEquals(1, captured.list.size());
            String rendered = captured.list.getFirst().getFormattedMessage();
            assertTrue(rendered.contains("outbox=id") && rendered.contains("purpose=OWNER_SETUP") && rendered.contains("attempt=1")
                    && rendered.contains("error=IllegalStateException") && rendered.contains("state=PENDING"));
            assertFalse(rendered.contains("secret-token-private"));
            assertNull(captured.list.getFirst().getThrowableProxy());
        } finally {
            logger.detachAppender(captured); captured.stop();
        }
    }
    @Test void finalAttemptLogsFailedState() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AccountMailOutboxService.class);
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start(); logger.addAppender(captured);
        try {
            var finalClaim = new AccountMailOutboxPort.Claim("last-id", "lease", "checkout", 5);
            when(outbox.claim(any())).thenReturn(Optional.of(finalClaim));
            when(outbox.issue(eq(finalClaim), anyString(), any())).thenReturn(Optional.of(new AccountMailOutboxPort.Recipient("owner@example.test", "owner", "en")));
            when(outbox.failed(eq(finalClaim), any())).thenReturn("FAILED");
            doThrow(new IllegalStateException("private")).when(mail).sendSetupLink(anyString(), anyString(), anyString(), anyString());
            new AccountMailOutboxService(outbox, mail, new PlatformEnvironment("production"), clock, "https://example.test").run(1);
            assertEquals(ch.qos.logback.classic.Level.WARN, captured.list.getFirst().getLevel());
            assertTrue(captured.list.getFirst().getFormattedMessage().contains("attempt=5"));
            assertTrue(captured.list.getFirst().getFormattedMessage().contains("state=FAILED"));
            assertNull(captured.list.getFirst().getThrowableProxy());
        } finally {
            logger.detachAppender(captured); captured.stop();
        }
    }
}
