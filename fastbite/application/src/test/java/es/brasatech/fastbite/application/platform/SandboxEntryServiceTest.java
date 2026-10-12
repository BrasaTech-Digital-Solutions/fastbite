package es.brasatech.fastbite.application.platform;

import es.brasatech.fastbite.application.tenant.SubscriptionService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SandboxEntryServiceTest {

    static final String SECRET = "a-secret-of-at-least-thirty-two-characters";
    static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    final PlatformEnvironment production = new PlatformEnvironment("production");
    final SandboxProvisioningService provisioning = mock(SandboxProvisioningService.class);
    final TenantLocationService locations = mock(TenantLocationService.class);
    final SubscriptionService subscriptions = mock(SubscriptionService.class);
    final SandboxEntryService service = new SandboxEntryService(provisioning, locations, subscriptions,
            new PlatformLink(production, "https://sandbox.example/", "", SECRET, false), "1.2.0",
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void anaOwnsHerCafeAndMayTrade() {
        when(provisioning.active()).thenReturn(true);
        when(locations.isOwnerOf("ana", "anacafe")).thenReturn(true);
        when(subscriptions.canTakeOrders("anacafe")).thenReturn(true);
        when(provisioning.ensure("anacafe", "ana")).thenReturn(true);
    }

    @Test
    void theOwnerGetsASignedLinkIntoTheSandbox() {
        var entry = service.enter("ana", "anacafe");

        assertThat(entry.state()).isEqualTo(SandboxEntryService.State.READY);
        assertThat(entry.url()).startsWith("https://sandbox.example/link/enter?token=");
        String token = entry.url().substring(entry.url().indexOf("token=") + 6);
        assertThat(SandboxSignInLink.read(SECRET, token, NOW)).get().satisfies(ticket -> {
            assertThat(ticket.owner()).isEqualTo("ana");
            assertThat(ticket.tenantId()).isEqualTo("anacafe");
            assertThat(ticket.version()).isEqualTo("1.2.0");
        });
    }

    @Test
    void aSandboxWithoutTheLocationYetIsBeingPrepared() {
        when(provisioning.ensure("anacafe", "ana")).thenReturn(false);

        assertThat(service.enter("ana", "anacafe")).isEqualTo(new SandboxEntryService.Entry(SandboxEntryService.State.PREPARING, null));
    }

    @Test
    void anEndedSubscriptionClosesTheSandboxToo() {
        when(subscriptions.canTakeOrders("anacafe")).thenReturn(false);

        assertThat(service.enter("ana", "anacafe").state()).isEqualTo(SandboxEntryService.State.SUBSCRIPTION_ENDED);
        verify(provisioning, never()).ensure(any(), any());
    }

    @Test
    void onlyTheOwnerEntersALocation() {
        assertThatThrownBy(() -> service.enter("eve", "anacafe")).isInstanceOf(IllegalArgumentException.class);
        verify(provisioning, never()).ensure(any(), any());
    }

    @Test
    void withoutASandboxThereIsNothingToEnter() {
        when(provisioning.active()).thenReturn(false);

        assertThat(service.available()).isFalse();
        assertThatThrownBy(() -> service.enter("ana", "anacafe")).isInstanceOf(IllegalStateException.class);
    }
}
