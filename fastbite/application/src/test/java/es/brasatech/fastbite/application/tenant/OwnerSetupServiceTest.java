package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.application.mail.AccountMailOutboxService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OwnerSetupServiceTest {
    private final OwnerSetupPort persistence = mock(OwnerSetupPort.class);
    private final TenantProvisionerPort provisioner = mock(TenantProvisionerPort.class);
    private final AccountMailOutboxService mail = mock(AccountMailOutboxService.class);
    private final TenantLifecyclePort lifecycle = (tenant, operation, owner, state, work) -> work.run();
    private final OwnerSetupService service = new OwnerSetupService(persistence, provisioner, mail, lifecycle, "https://fastbite.example");
    private final OwnerSetupPort.Invitation invitation = new OwnerSetupPort.Invitation("checkout", "burger", "bob", "Bob", "bob@example.test", "RESTAURANT");

    @Test void commitsAnEmailIntentBeforeTryingDelivery() {
        service.invite(invitation);
        var order = inOrder(provisioner, persistence, mail);
        order.verify(provisioner).provisionTenant("burger");
        order.verify(persistence).prepareQueued(eq(invitation), any());
        order.verify(mail).runForSetup("checkout");
    }
    @Test void dispatchFailureDoesNotFailRegistration() {
        doThrow(new IllegalStateException("unavailable")).when(mail).runForSetup("checkout");
        assertDoesNotThrow(() -> service.invite(invitation));
        verify(persistence).prepareQueued(eq(invitation), any());
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
            assertThrows(IllegalArgumentException.class, () -> service.invite(new OwnerSetupPort.Invitation("checkout", tenant, "bob", "Bob", "bob@example.test", "RESTAURANT")));
        }
        verifyNoInteractions(persistence, provisioner, mail);
    }
}
