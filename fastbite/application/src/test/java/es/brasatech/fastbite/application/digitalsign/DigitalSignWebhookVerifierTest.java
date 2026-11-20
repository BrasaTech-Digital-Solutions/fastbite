package es.brasatech.fastbite.application.digitalsign;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DigitalSignWebhookVerifierTest {

    private static final String SECRET = "whsec_test_secret_key";
    private static final long TIMESTAMP = 1700000000L;
    private static final byte[] BODY = "{\"id\":\"evt_123\",\"type\":\"request.completed\"}"
            .getBytes(StandardCharsets.UTF_8);
    private static final String KNOWN_VECTOR_HEX =
            "506867655f2b95566a53299803b4b72671e44b26745b8a0619f6555219ff5ad0";

    @Test
    void verifiesKnownHmacTestVector() {
        String header = "t=" + TIMESTAMP + ",v1=" + KNOWN_VECTOR_HEX;
        Instant now = Instant.ofEpochSecond(TIMESTAMP + 30);

        assertThat(DigitalSignWebhookVerifier.verify(SECRET, header, BODY, now)).isTrue();
        assertThat(DigitalSignWebhookVerifier.signHeader(SECRET, TIMESTAMP, BODY)).isEqualTo(header);
    }

    @Test
    void acceptsUppercaseHexSignature() {
        String header = "t=" + TIMESTAMP + ",v1=" + KNOWN_VECTOR_HEX.toUpperCase();
        Instant now = Instant.ofEpochSecond(TIMESTAMP);

        assertThat(DigitalSignWebhookVerifier.verify(SECRET, header, BODY, now)).isTrue();
    }

    @Test
    void enforcesFiveMinuteToleranceWindow() {
        String header = "t=" + TIMESTAMP + ",v1=" + KNOWN_VECTOR_HEX;

        assertThat(DigitalSignWebhookVerifier.verify(SECRET, header, BODY, Instant.ofEpochSecond(TIMESTAMP + 300)))
                .isTrue();
        assertThat(DigitalSignWebhookVerifier.verify(SECRET, header, BODY, Instant.ofEpochSecond(TIMESTAMP - 300)))
                .isTrue();
        assertThat(DigitalSignWebhookVerifier.verify(SECRET, header, BODY, Instant.ofEpochSecond(TIMESTAMP + 301)))
                .isFalse();
        assertThat(DigitalSignWebhookVerifier.verify(SECRET, header, BODY, Instant.ofEpochSecond(TIMESTAMP - 301)))
                .isFalse();
    }

    @Test
    void rejectsWrongSignatureOrModifiedBodyBytes() {
        String wrongSig = "t=" + TIMESTAMP + ",v1=" + "0".repeat(64);
        Instant now = Instant.ofEpochSecond(TIMESTAMP);
        assertThat(DigitalSignWebhookVerifier.verify(SECRET, wrongSig, BODY, now)).isFalse();

        byte[] reserialized = "{\"id\": \"evt_123\", \"type\": \"request.completed\"}"
                .getBytes(StandardCharsets.UTF_8);
        String validForOriginal = "t=" + TIMESTAMP + ",v1=" + KNOWN_VECTOR_HEX;
        assertThat(DigitalSignWebhookVerifier.verify(SECRET, validForOriginal, reserialized, now)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "t=1700000000",
            "v1=" + KNOWN_VECTOR_HEX,
            "t=notanumber,v1=" + KNOWN_VECTOR_HEX,
            "t=-1,v1=" + KNOWN_VECTOR_HEX,
            "t=9223372036854775807,v1=" + KNOWN_VECTOR_HEX,
            "t=1700000000,t=1700000000,v1=" + KNOWN_VECTOR_HEX,
            "t=1700000000,v1=abc,v1=" + KNOWN_VECTOR_HEX,
            "garbage"
    })
    void rejectsMalformedHeaders(String header) {
        assertThat(DigitalSignWebhookVerifier.verify(SECRET, header, BODY, Instant.ofEpochSecond(TIMESTAMP)))
                .isFalse();
    }

    @Test
    void rejectsNullOrBlankSecretAndSupportsComponentClock() {
        String header = "t=" + TIMESTAMP + ",v1=" + KNOWN_VECTOR_HEX;
        Instant now = Instant.ofEpochSecond(TIMESTAMP + 10);

        assertThat(DigitalSignWebhookVerifier.verify(null, header, BODY, now)).isFalse();
        assertThat(DigitalSignWebhookVerifier.verify("   ", header, BODY, now)).isFalse();
        assertThat(DigitalSignWebhookVerifier.verify(SECRET, null, BODY, now)).isFalse();
        assertThat(DigitalSignWebhookVerifier.verify(SECRET, header, null, now)).isFalse();

        Clock fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        DigitalSignProperties props = new DigitalSignProperties();
        props.setWebhookSecret(SECRET);
        DigitalSignWebhookVerifier verifier = new DigitalSignWebhookVerifier(props, fixedClock);
        assertThat(verifier.verify(header, BODY)).isTrue();
    }
}
