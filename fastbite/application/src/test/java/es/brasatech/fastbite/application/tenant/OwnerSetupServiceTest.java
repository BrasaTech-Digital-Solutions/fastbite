package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.application.mail.OwnerSetupMailPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OwnerSetupServiceTest {
    private final OwnerSetupPort persistence = mock(OwnerSetupPort.class);
    private final TenantProvisionerPort provisioner = mock(TenantProvisionerPort.class);
    private final OwnerSetupMailPort mail = mock(OwnerSetupMailPort.class);
    private final TenantLifecyclePort lifecycle = (tenant, operation, owner, state, work) -> work.run();
    private final OwnerSetupService service = new OwnerSetupService(persistence, provisioner, mail, lifecycle, "https://fastbite.example");
    private final OwnerSetupPort.Invitation invitation = new OwnerSetupPort.Invitation("checkout", "burger", "bob", "Bob", "bob@example.test", "RESTAURANT");

    @Test void onlyTheHashIsStoredAndTheEmailUsesTheConfiguredOrigin() {
        when(persistence.prepare(eq(invitation), anyString(), any())).thenReturn(true);
        service.invite(invitation);
        var hash = ArgumentCaptor.forClass(String.class);
        var expiry = ArgumentCaptor.forClass(Instant.class);
        verify(persistence).prepare(eq(invitation), hash.capture(), expiry.capture());
        var link = ArgumentCaptor.forClass(String.class);
        verify(mail).sendSetupLink(eq("bob@example.test"), eq("bob"), link.capture());
        assertTrue(link.getValue().matches("https://fastbite\\.example/set-password\\?token=[A-Za-z0-9_-]{43}"));
        assertTrue(hash.getValue().matches("[a-f0-9]{64}"));
        assertFalse(link.getValue().contains(hash.getValue()));
        assertTrue(expiry.getValue().isAfter(Instant.now().plusSeconds(86000)));
        assertTrue(expiry.getValue().isBefore(Instant.now().plusSeconds(86401)));
    }

    @Test void failedDeliveryIsRetriedAndACompletedInvitationSendsNothing() {
        when(persistence.prepare(eq(invitation), anyString(), any())).thenReturn(true, true, false);
        doThrow(new IllegalStateException("SMTP unavailable")).doNothing().when(mail).sendSetupLink(anyString(), anyString(), anyString());
        assertThrows(IllegalStateException.class, () -> service.invite(invitation));
        service.invite(invitation);
        service.invite(invitation);
        verify(mail, times(2)).sendSetupLink(anyString(), anyString(), anyString());
    }

    @Test void invalidEmailAndMalformedTokensDoNotReachPersistence() {
        assertThrows(IllegalArgumentException.class, () -> service.invite(new OwnerSetupPort.Invitation("checkout", "burger", "bob", "Bob", null, "RESTAURANT")));
        assertFalse(service.isValid("bad"));
        assertFalse(service.complete(null, "hash"));
        verifyNoInteractions(persistence, mail, provisioner);
    }

    @Test void publicUrlRejectsInsecureOrInjectedOrigins() {
        assertDoesNotThrow(() -> new OwnerSetupService(persistence, provisioner, mail, lifecycle, "http://fastbite.localhost:8088"));
        assertDoesNotThrow(() -> new OwnerSetupService(persistence, provisioner, mail, lifecycle, "http://localhost:8080"));
        for (String url : new String[]{"http://production.example", "https://example.test/?redirect=bad", "https://user@example.test", "https://example.test/#bad"}) {
            assertThrows(IllegalArgumentException.class, () -> new OwnerSetupService(persistence, provisioner, mail, lifecycle, url));
        }
    }

    @Test void reservedAndOverlongSchemaNamesNeverProvision() {
        for (String tenant : new String[]{"owner", "kebab", "default", "a".repeat(57), "bad-name"}) {
            assertThrows(IllegalArgumentException.class, () -> service.invite(
                    new OwnerSetupPort.Invitation("checkout", tenant, "bob", "Bob", "bob@example.test", "RESTAURANT")));
        }
        verifyNoInteractions(persistence, provisioner, mail);
    }
}
