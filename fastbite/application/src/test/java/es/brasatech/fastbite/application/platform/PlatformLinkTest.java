package es.brasatech.fastbite.application.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformLinkTest {

    static final String SECRET = "a-secret-of-at-least-thirty-two-characters";
    static final PlatformEnvironment PRODUCTION = new PlatformEnvironment("production");
    static final PlatformEnvironment SANDBOX = new PlatformEnvironment("sandbox");

    @Test
    void productionRefusesToStartWithoutItsSandboxUnlessAllowed() {
        assertThatThrownBy(() -> new PlatformLink(PRODUCTION, "", "", "", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FASTBITE_STANDALONE");

        var standalone = new PlatformLink(PRODUCTION, "", "", "", true);
        assertThat(standalone.linked()).isFalse();
        assertThat(standalone.standaloneProduction()).isTrue();
    }

    @Test
    void theSandboxMayRunOnItsOwn() {
        var sandbox = new PlatformLink(SANDBOX, "", "", "", false);

        assertThat(sandbox.linked()).isFalse();
        assertThat(sandbox.standaloneProduction()).isFalse();
    }

    @Test
    void aLinkNeedsBothTheAddressAndALongSecret() {
        assertThatThrownBy(() -> new PlatformLink(PRODUCTION, "https://sandbox.example", "", "short", false))
                .hasMessageContaining("at least 32");
        assertThatThrownBy(() -> new PlatformLink(SANDBOX, "", "", SECRET, false))
                .hasMessageContaining("FASTBITE_LINK_URL is not");

        var link = new PlatformLink(PRODUCTION, " https://sandbox.example/ ", "", SECRET, false);
        assertThat(link.linked()).isTrue();
        assertThat(link.url()).isEqualTo("https://sandbox.example");
        assertThat(link.internalUrl()).as("the public address unless set apart").isEqualTo("https://sandbox.example");
        assertThat(new PlatformLink(PRODUCTION, "http://localhost:8091", "http://sandbox:8080", SECRET, false)
                .internalUrl()).isEqualTo("http://sandbox:8080");
    }
}
