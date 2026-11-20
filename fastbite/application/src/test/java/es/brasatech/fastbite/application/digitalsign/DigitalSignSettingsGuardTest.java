package es.brasatech.fastbite.application.digitalsign;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DigitalSignSettingsGuardTest {

    @Test
    void allowsDefaultUnconfiguredSettings() {
        DigitalSignProperties props = new DigitalSignProperties();
        assertThatCode(() -> DigitalSignSettingsGuard.validate(props, "https://fastbite.brasatech.es"))
                .doesNotThrowAnyException();
        assertThat(props.isConfigured()).isFalse();
    }

    @Test
    void allowsProductionHttpsWithApiToken() {
        DigitalSignProperties props = new DigitalSignProperties();
        props.setBaseUrl("https://digital-sign.brasatech.es/api/v1");
        props.setApiToken("ds_live_example_token");
        assertThatCode(() -> DigitalSignSettingsGuard.validate(props, "https://fastbite.brasatech.es"))
                .doesNotThrowAnyException();
        assertThat(props.isConfigured()).isTrue();
    }

    @Test
    void rejectsHttpBaseUrlWhenTokenSetAndInsecureLocalDisabled() {
        DigitalSignProperties props = new DigitalSignProperties();
        props.setBaseUrl("http://localhost:8099/api/v1");
        props.setApiToken("ds_live_example_token");
        props.setAllowInsecureLocal(false);

        assertThatThrownBy(() -> DigitalSignSettingsGuard.validate(props, "http://localhost:8080"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https://");
    }

    @Test
    void rejectsInsecureLocalWithHttpsBaseUrl() {
        DigitalSignProperties props = new DigitalSignProperties();
        props.setBaseUrl("https://localhost:8099/api/v1");
        props.setAllowInsecureLocal(true);

        assertThatThrownBy(() -> DigitalSignSettingsGuard.validate(props, "http://localhost:8080"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https://");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://digital-sign.brasatech.es/api/v1",
            "http://example.com/api/v1",
            "http://8.8.8.8:8099/api/v1",
            "http://172.32.0.1:8099/api/v1",
            "http://192.169.1.1:8099/api/v1"
    })
    void rejectsInsecureLocalWithPublicHost(String baseUrl) {
        DigitalSignProperties props = new DigitalSignProperties();
        props.setBaseUrl(baseUrl);
        props.setAllowInsecureLocal(true);

        assertThatThrownBy(() -> DigitalSignSettingsGuard.validate(props, "http://localhost:8080"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback or private");
    }

    @Test
    void rejectsInsecureLocalWhenPublicUrlIsHttps() {
        DigitalSignProperties props = new DigitalSignProperties();
        props.setBaseUrl("http://digital-sign-mock:8099/api/v1");
        props.setAllowInsecureLocal(true);

        assertThatThrownBy(() -> DigitalSignSettingsGuard.validate(props, "https://fastbite.brasatech.es"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fastbite.public-url");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:8099/api/v1",
            "http://127.0.0.1:8099/api/v1",
            "http://[::1]:8099/api/v1",
            "http://10.0.0.5:8099/api/v1",
            "http://172.18.0.4:8099/api/v1",
            "http://192.168.1.25:8099/api/v1",
            "http://digital-sign-mock:8099/api/v1",
            "http://mock.internal:8099/api/v1"
    })
    void allowsInsecureLocalWithLoopbackOrPrivateHostAndHttpPublicUrl(String baseUrl) {
        DigitalSignProperties props = new DigitalSignProperties();
        props.setBaseUrl(baseUrl);
        props.setApiToken("mock-token");
        props.setAllowInsecureLocal(true);

        assertThatCode(() -> DigitalSignSettingsGuard.validate(props, "http://localhost:8080"))
                .doesNotThrowAnyException();
    }

    @Test
    void propertiesToStringRedactsSecrets() {
        DigitalSignProperties props = new DigitalSignProperties();
        props.setApiToken("super-secret-api-token");
        props.setWebhookSecret("super-secret-webhook-key");

        String rendered = props.toString();
        assertThat(rendered).doesNotContain("super-secret-api-token");
        assertThat(rendered).doesNotContain("super-secret-webhook-key");
        assertThat(rendered).contains("[REDACTED]");
    }
}
