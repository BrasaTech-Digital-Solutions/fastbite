package es.brasatech.fastbite.application.platform;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LinkSignatureTest {

    static final String SECRET = "a-secret-of-at-least-thirty-two-characters";
    static final byte[] CONTENT = "{\"tenantId\":\"kebab\"}".getBytes(StandardCharsets.UTF_8);
    static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    @Test
    void whatIsSignedWithTheSecretIsAccepted() {
        String header = LinkSignature.header(SECRET, NOW, CONTENT);

        assertThat(header).startsWith("t=" + NOW.getEpochSecond() + ",v1=");
        assertThat(LinkSignature.verify(SECRET, header, CONTENT, NOW.plusSeconds(30))).isTrue();
    }

    @Test
    void anotherSecretChangedContentOrAnOldSignatureAreRefused() {
        String header = LinkSignature.header(SECRET, NOW, CONTENT);

        assertThat(LinkSignature.verify("another-secret-of-at-least-thirty-two-chars", header, CONTENT, NOW)).isFalse();
        assertThat(LinkSignature.verify(SECRET, header, "{\"tenantId\":\"other\"}".getBytes(StandardCharsets.UTF_8), NOW))
                .isFalse();
        assertThat(LinkSignature.verify(SECRET, header, CONTENT, NOW.plus(LinkSignature.TOLERANCE).plusSeconds(1)))
                .as("a captured call cannot be replayed later").isFalse();
        assertThat(LinkSignature.verify(SECRET, null, CONTENT, NOW)).isFalse();
        assertThat(LinkSignature.verify(SECRET, "t=abc,v1=00", CONTENT, NOW)).isFalse();
        assertThat(LinkSignature.verify(SECRET, "v1=00", CONTENT, NOW)).isFalse();
    }
}
