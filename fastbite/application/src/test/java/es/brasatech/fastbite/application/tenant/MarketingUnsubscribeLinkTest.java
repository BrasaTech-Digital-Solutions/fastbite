package es.brasatech.fastbite.application.tenant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingUnsubscribeLinkTest {

    @Test
    void blankSecretIssuesNothingAndRefusesEveryToken() {
        var blankLink = new MarketingUnsubscribeLink("   ", "https://fastbite.brasatech.es");
        var validLink = new MarketingUnsubscribeLink("0123456789abcdef0123456789abcdef", "https://fastbite.brasatech.es");
        String tokenFromConfigured = validLink.issueToken("ana").orElseThrow();

        assertThat(blankLink.issueToken("ana")).isEmpty();
        assertThat(blankLink.issueUrl("ana")).isEmpty();
        assertThat(blankLink.verify(tokenFromConfigured)).isEmpty();
    }

    @Test
    void validTokenVerifiesAndTamperedTokenIsRefused() {
        var links = new MarketingUnsubscribeLink("0123456789abcdef0123456789abcdef", "https://fastbite.brasatech.es/");
        String token = links.issueToken("ana@example.com").orElseThrow();

        assertThat(links.issueUrl("ana@example.com"))
                .contains("https://fastbite.brasatech.es/legal/unsubscribe?token=" + token);
        assertThat(links.verify(token)).contains("ana@example.com");

        // Tampered payload or signature is refused
        String[] parts = token.split("\\.");
        String otherPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("other".getBytes());
        assertThat(links.verify(otherPayload + "." + parts[1])).isEmpty();
        assertThat(links.verify(parts[0] + ".tampered")).isEmpty();
        assertThat(links.verify("no-dot")).isEmpty();
        assertThat(links.verify(null)).isEmpty();

        // Different secret refuses token
        var otherSecret = new MarketingUnsubscribeLink("fedcba9876543210fedcba9876543210", "https://fastbite.brasatech.es");
        assertThat(otherSecret.verify(token)).isEmpty();
    }
}
