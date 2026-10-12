package es.brasatech.fastbite.application.platform;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxSignInLinkTest {

    static final String SECRET = "a-secret-of-at-least-thirty-two-characters";
    static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    @Test
    void aLinkSaysWhoEntersWhereFromWhichVersionForAMinute() {
        String token = SandboxSignInLink.issue(SECRET, "ana@example.com", "anacafe", "1.2.0", NOW);

        assertThat(token).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
        assertThat(SandboxSignInLink.read(SECRET, token, NOW.plusSeconds(59))).get().satisfies(ticket -> {
            assertThat(ticket.owner()).isEqualTo("ana@example.com");
            assertThat(ticket.tenantId()).isEqualTo("anacafe");
            assertThat(ticket.version()).isEqualTo("1.2.0");
            assertThat(ticket.expires()).isEqualTo(NOW.plusSeconds(60));
            assertThat(ticket.nonce()).isNotBlank();
        });
        assertThat(SandboxSignInLink.read(SECRET, token, NOW.plusSeconds(60))).as("expired").isEmpty();
    }

    @Test
    void everyLinkIsDifferent() {
        assertThat(SandboxSignInLink.issue(SECRET, "ana", "anacafe", "1.2.0", NOW))
                .isNotEqualTo(SandboxSignInLink.issue(SECRET, "ana", "anacafe", "1.2.0", NOW));
    }

    @Test
    void onlyLinksSignedWithTheSecretAreRead() {
        String token = SandboxSignInLink.issue(SECRET, "ana", "anacafe", "1.2.0", NOW);
        String other = SandboxSignInLink.issue("another-secret-of-at-least-thirty-two-chars", "ana", "anacafe", "1.2.0", NOW);
        String forged = SandboxSignInLink.issue(SECRET, "eve", "anacafe", "1.2.0", NOW).split("\\.")[0]
                + "." + token.split("\\.")[1];

        assertThat(SandboxSignInLink.read(SECRET, other, NOW)).isEmpty();
        assertThat(SandboxSignInLink.read(SECRET, forged, NOW)).as("another owner under ana's signature").isEmpty();
        assertThat(SandboxSignInLink.read(SECRET, "", NOW)).isEmpty();
        assertThat(SandboxSignInLink.read(SECRET, "not a link", NOW)).isEmpty();
        assertThat(SandboxSignInLink.read(SECRET, "a.%%%", NOW)).isEmpty();
        assertThat(SandboxSignInLink.read(SECRET, null, NOW)).isEmpty();
        assertThat(SandboxSignInLink.read(null, token, NOW)).isEmpty();
    }

    @Test
    void aLinkGoodForLongerThanProductionIssuesIsRefused() {
        var ticket = new SandboxSignInLink.Ticket("ana", "anacafe", "1.2.0", NOW.plusSeconds(3600), "abc");

        assertThat(SandboxSignInLink.read(SECRET, SandboxSignInLink.sign(SECRET, ticket), NOW)).isEmpty();
    }

    @Test
    void aSignatureOfAnotherKindOfMessageIsNoSignInLink() {
        byte[] content = "{}".getBytes();
        String header = LinkSignature.header(SECRET, NOW, content);

        assertThat(SandboxSignInLink.read(SECRET, "e30." + header, NOW)).isEmpty();
    }

    @Test
    void fieldsMustFitOnOneLine() {
        assertThatThrownBy(() -> SandboxSignInLink.issue(SECRET, "ana\nadmin", "anacafe", "1.2.0", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
