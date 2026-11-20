package es.brasatech.fastbite.application.digitalsign;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings for FastBite's integration with Digital Sign (Anexo I VERI*FACTU authorisation).
 *
 * <p>Secrets ({@code apiToken} and {@code webhookSecret}) are never exposed by {@link #toString()}.
 */
@ConfigurationProperties(prefix = "fastbite.digitalsign")
public class DigitalSignProperties {

    private String baseUrl = "https://digital-sign.brasatech.es/api/v1";
    private String apiToken = "";
    private String webhookSecret = "";
    private boolean allowInsecureLocal = false;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(4);
    private int maxRetries = 2;
    private Duration retryInitialBackoff = Duration.ofMillis(200);
    private Verifactu verifactu = new Verifactu();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public boolean isAllowInsecureLocal() {
        return allowInsecureLocal;
    }

    public void setAllowInsecureLocal(boolean allowInsecureLocal) {
        this.allowInsecureLocal = allowInsecureLocal;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Duration getRetryInitialBackoff() {
        return retryInitialBackoff;
    }

    public void setRetryInitialBackoff(Duration retryInitialBackoff) {
        this.retryInitialBackoff = retryInitialBackoff;
    }

    public Verifactu getVerifactu() {
        return verifactu;
    }

    public void setVerifactu(Verifactu verifactu) {
        this.verifactu = verifactu == null ? new Verifactu() : verifactu;
    }

    public boolean isConfigured() {
        return apiToken != null && !apiToken.isBlank()
                && baseUrl != null && !baseUrl.isBlank();
    }

    public boolean isVerifactuConfigured() {
        return isConfigured()
                && verifactu != null
                && verifactu.getTemplateId() != null
                && !verifactu.getTemplateId().isBlank();
    }

    public String normalizedBaseUrl() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        String trimmed = baseUrl.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    @Override
    public String toString() {
        return "DigitalSignProperties{"
                + "baseUrl='" + baseUrl + '\''
                + ", apiToken=" + (apiToken != null && !apiToken.isBlank() ? "[REDACTED]" : "<empty>")
                + ", webhookSecret=" + (webhookSecret != null && !webhookSecret.isBlank() ? "[REDACTED]" : "<empty>")
                + ", allowInsecureLocal=" + allowInsecureLocal
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout
                + ", maxRetries=" + maxRetries
                + ", verifactu=" + verifactu
                + '}';
    }

    public static class Verifactu {

        private String templateId = "";
        private String signerRole = "Representante";
        private Fields fields = new Fields();

        public String getTemplateId() {
            return templateId;
        }

        public void setTemplateId(String templateId) {
            this.templateId = templateId;
        }

        public String getSignerRole() {
            return signerRole;
        }

        public void setSignerRole(String signerRole) {
            this.signerRole = signerRole;
        }

        public Fields getFields() {
            return fields;
        }

        public void setFields(Fields fields) {
            this.fields = fields == null ? new Fields() : fields;
        }

        @Override
        public String toString() {
            return "Verifactu{"
                    + "templateId='" + templateId + '\''
                    + ", signerRole='" + signerRole + '\''
                    + ", fields=" + fields
                    + '}';
        }
    }

    public static class Fields {

        private String legalName = "";
        private String nif = "";
        private String address = "";
        private String brasatechName = "";
        private String brasatechNif = "";
        private String brasatechAddress = "";

        public String getLegalName() {
            return legalName;
        }

        public void setLegalName(String legalName) {
            this.legalName = legalName;
        }

        public String getNif() {
            return nif;
        }

        public void setNif(String nif) {
            this.nif = nif;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public String getBrasatechName() {
            return brasatechName;
        }

        public void setBrasatechName(String brasatechName) {
            this.brasatechName = brasatechName;
        }

        public String getBrasatechNif() {
            return brasatechNif;
        }

        public void setBrasatechNif(String brasatechNif) {
            this.brasatechNif = brasatechNif;
        }

        public String getBrasatechAddress() {
            return brasatechAddress;
        }

        public void setBrasatechAddress(String brasatechAddress) {
            this.brasatechAddress = brasatechAddress;
        }

        @Override
        public String toString() {
            return "Fields{"
                    + "legalName='" + legalName + '\''
                    + ", nif='" + nif + '\''
                    + ", address='" + address + '\''
                    + ", brasatechName='" + brasatechName + '\''
                    + ", brasatechNif='" + brasatechNif + '\''
                    + ", brasatechAddress='" + brasatechAddress + '\''
                    + '}';
        }
    }
}
