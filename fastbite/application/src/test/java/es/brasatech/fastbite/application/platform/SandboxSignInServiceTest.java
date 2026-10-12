package es.brasatech.fastbite.application.platform;

import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.tenant.TenantRegistrationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SandboxSignInServiceTest {

    static final String SECRET = "a-secret-of-at-least-thirty-two-characters";
    static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    /** The used links in memory. */
    static class MemorySignIns implements SandboxSignInPort {
        final Set<String> used = new HashSet<>();

        public boolean claim(String nonce, Instant expires, Instant now) {
            return used.add(nonce);
        }
    }

    final PlatformEnvironment sandbox = new PlatformEnvironment("sandbox");
    final MemorySignIns signIns = new MemorySignIns();
    final TenantLocationService locations = mock(TenantLocationService.class);
    final TenantRegistrationPort registration = mock(TenantRegistrationPort.class);
    final SandboxSignInService service = new SandboxSignInService(sandbox,
            new PlatformLink(sandbox, "http://production", "", SECRET, false), signIns, locations, registration,
            "1.2.0", Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void anaOwnsHerCafeHere() {
        when(locations.isOwnerOf("ana", "anacafe")).thenReturn(true);
    }

    static String link(String version) {
        return SandboxSignInLink.issue(SECRET, "ana", "anacafe", version, NOW.minusSeconds(5));
    }

    @Test
    void productionsLinkSignsTheOwnerInOnce() {
        String token = link("1.2.0");

        var outcome = service.accept(token);
        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.owner()).isEqualTo("ana");
        assertThat(outcome.tenantId()).isEqualTo("anacafe");

        assertThat(service.accept(token).refusal()).as("used before").isEqualTo(SandboxSignInService.Refusal.INVALID);
    }

    @Test
    void linksNotSignedByProductionAreRefused() {
        String other = SandboxSignInLink.issue("another-secret-of-at-least-thirty-two-chars", "ana", "anacafe", "1.2.0", NOW);

        assertThat(service.accept(other).refusal()).isEqualTo(SandboxSignInService.Refusal.INVALID);
        assertThat(service.accept(null).refusal()).isEqualTo(SandboxSignInService.Refusal.INVALID);
        assertThat(signIns.used).isEmpty();
    }

    @Test
    void aLinkFromAnotherVersionIsTriedAgainAfterTheRelease() {
        assertThat(service.accept(link("1.3.0")).refusal()).isEqualTo(SandboxSignInService.Refusal.OTHER_VERSION);
    }

    @Test
    void aLocationTheSandboxDoesNotHaveForThatOwnerIsNotReady() {
        when(locations.isOwnerOf("ana", "anacafe")).thenReturn(false);

        assertThat(service.accept(link("1.2.0")).refusal()).isEqualTo(SandboxSignInService.Refusal.NOT_READY);
    }

    @Test
    void aDisabledOwnerIsNotSignedIn() {
        doThrow(new IllegalArgumentException("An active owner account is required.")).when(registration).requireActiveOwner("ana");

        assertThat(service.accept(link("1.2.0")).accepted()).isFalse();
    }

    @Test
    void onlyALinkedSandboxTakesLinks() {
        var production = new PlatformEnvironment("production");
        var standalone = new SandboxSignInService(sandbox, new PlatformLink(sandbox, "", "", "", false), signIns,
                locations, registration, "1.2.0", Clock.fixed(NOW, ZoneOffset.UTC));
        var inProduction = new SandboxSignInService(production,
                new PlatformLink(production, "http://sandbox", "", SECRET, false), signIns, locations, registration,
                "1.2.0", Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(standalone.active()).isFalse();
        assertThat(standalone.accept(link("1.2.0")).accepted()).isFalse();
        assertThat(inProduction.active()).isFalse();
        assertThat(inProduction.accept(link("1.2.0")).accepted()).isFalse();
    }
}
