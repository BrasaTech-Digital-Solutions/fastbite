package es.brasatech.fastbite.digitalsign;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.brasatech.fastbite.application.digitalsign.CertificateNifExtractor;
import es.brasatech.fastbite.application.digitalsign.CertificateNifExtractor.Status;
import es.brasatech.fastbite.application.digitalsign.CreateRequestCommand;
import es.brasatech.fastbite.application.digitalsign.CreateRequestCommand.RequestedFileInput;
import es.brasatech.fastbite.application.digitalsign.CreateRequestCommand.SignerInput;
import es.brasatech.fastbite.application.digitalsign.CreateRequestResult;
import es.brasatech.fastbite.application.digitalsign.DigitalSignException;
import es.brasatech.fastbite.application.digitalsign.DigitalSignException.Kind;
import es.brasatech.fastbite.application.digitalsign.DigitalSignProperties;
import es.brasatech.fastbite.application.digitalsign.DigitalSignRequest;
import es.brasatech.fastbite.application.digitalsign.DigitalSignTemplate;
import es.brasatech.fastbite.application.digitalsign.SigningUrlResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpDigitalSignClientTest {

    private static final String TEST_TOKEN = "ds_test_secret_token_987654";
    private static final String TEST_WEBHOOK_SECRET = "whsec_test_secret_987654";

    private HttpServer server;
    private ObjectMapper mapper;
    private DigitalSignProperties properties;
    private List<Duration> recordedBackoffs;
    private HttpDigitalSignClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        mapper = new ObjectMapper();
        recordedBackoffs = new CopyOnWriteArrayList<>();

        properties = new DigitalSignProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");
        properties.setApiToken(TEST_TOKEN);
        properties.setWebhookSecret(TEST_WEBHOOK_SECRET);
        properties.setAllowInsecureLocal(true);
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(2));
        properties.setMaxRetries(2);
        properties.setRetryInitialBackoff(Duration.ofMillis(50));

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        client = new HttpDigitalSignClient(properties, mapper, http, recordedBackoffs::add);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void getTemplateParsesTolerantlyWithUnknownFieldsAndExtraRole() {
        server.createContext("/api/v1/templates/tpl-1", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + TEST_TOKEN);
            respondJson(exchange, 200, """
                    {
                      "id": "tpl-1",
                      "name": "Anexo I VERI*FACTU",
                      "unexpectedFutureField": 42,
                      "signers": [
                        {
                          "index": 0,
                          "role": "Brasatech",
                          "certificate": true,
                          "automatic": true,
                          "files": []
                        },
                        {
                          "index": 1,
                          "role": "Representante",
                          "certificate": true,
                          "files": [
                            { "label": "Copia DNI", "required": false, "extra": "ignored" }
                          ]
                        }
                      ],
                      "fields": [
                        { "key": "fld_nif", "label": "NIF", "type": "TEXT", "required": true, "validation": "DNI_NIE", "page": 1 },
                        { "key": "fld_sig", "label": "Firma", "type": "SIGNATURE", "required": true, "validation": "NONE" }
                      ]
                    }
                    """);
        });

        DigitalSignTemplate template = client.getTemplate("tpl-1");
        assertThat(template.id()).isEqualTo("tpl-1");
        assertThat(template.name()).isEqualTo("Anexo I VERI*FACTU");
        assertThat(template.signers()).hasSize(2);
        assertThat(template.signers().get(0).role()).isEqualTo("Brasatech");
        assertThat(template.signers().get(1).role()).isEqualTo("Representante");
        assertThat(template.signers().get(1).certificate()).isTrue();
        assertThat(template.signers().get(1).files()).hasSize(1);
        assertThat(template.fields()).hasSize(2);
        assertThat(template.fields().get(0).key()).isEqualTo("fld_nif");
    }

    @Test
    void createRequestSendsPayloadAndIdempotencyKeyAndParsesReplayHeader() {
        AtomicReference<String> capturedIdempotencyKey = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();

        server.createContext("/api/v1/requests", exchange -> {
            int n = calls.incrementAndGet();
            capturedIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (n > 1) {
                exchange.getResponseHeaders().add("Idempotent-Replayed", "true");
            }
            respondJson(exchange, 201, """
                    {
                      "id": "req-42",
                      "status": "IN_PROGRESS",
                      "externalReference": "auth-uuid-1",
                      "title": "Autorizacion VERIFACTU",
                      "createdAt": "2026-11-20T10:00:00Z",
                      "expiresAt": "2026-12-20T10:00:00Z",
                      "completedAt": null,
                      "signers": [
                        {
                          "role": "Representante",
                          "name": "Ana Garcia",
                          "email": "ana@example.com",
                          "language": "es",
                          "status": "PENDING",
                          "signedAt": null,
                          "signingUrl": "https://digital-sign.brasatech.es/sign/secret-token-xyz",
                          "files": [],
                          "signatureMethod": "CERTIFICATE",
                          "nif": "B09767898",
                          "certificateStatus": "WAITING_FOR_SIGNER",
                          "certificate": null
                        }
                      ],
                      "documents": { "signedDocument": false, "evidenceReport": false }
                    }
                    """);
        });

        CreateRequestCommand command = new CreateRequestCommand(
                "tpl-1",
                "Autorizacion VERIFACTU",
                null,
                LocalDate.of(2026, 12, 20),
                "es",
                "auth-uuid-1",
                false,
                List.of(new SignerInput("Representante", "Ana Garcia", "ana@example.com", "es", "CERTIFICATE", "B09767898")),
                Map.of("fld_nif", "B09767898"),
                List.of(new RequestedFileInput("Representante", "Poder", false)));

        CreateRequestResult first = client.createRequest(command, "verifactu-B09767898-1");
        assertThat(first.idempotentReplayed()).isFalse();
        assertThat(first.request().id()).isEqualTo("req-42");
        assertThat(first.request().status()).isEqualTo("IN_PROGRESS");
        assertThat(first.request().signers().get(0).signingUrl())
                .isEqualTo("https://digital-sign.brasatech.es/sign/secret-token-xyz");
        assertThat(first.request().signers().get(0).toString()).doesNotContain("secret-token-xyz");
        assertThat(capturedIdempotencyKey.get()).isEqualTo("verifactu-B09767898-1");

        JsonNode sent = mapper.readTree(capturedBody.get());
        assertThat(sent.get("templateId").asString()).isEqualTo("tpl-1");
        assertThat(sent.get("sendEmail").asBoolean()).isFalse();
        assertThat(sent.get("externalReference").asString()).isEqualTo("auth-uuid-1");
        assertThat(sent.get("values").get("fld_nif").asString()).isEqualTo("B09767898");

        CreateRequestResult replay = client.createRequest(command, "verifactu-B09767898-1");
        assertThat(replay.idempotentReplayed()).isTrue();
        assertThat(replay.request().id()).isEqualTo("req-42");
    }

    @Test
    void getRequestPreservesNestedCertificateForNifExtraction() {
        server.createContext("/api/v1/requests/req-99", exchange -> respondJson(exchange, 200, """
                {
                  "id": "req-99",
                  "status": "COMPLETED",
                  "externalReference": "auth-uuid-99",
                  "title": "Anexo I",
                  "createdAt": "2026-11-20T10:00:00Z",
                  "expiresAt": "2026-12-20T10:00:00Z",
                  "completedAt": "2026-11-20T10:05:00Z",
                  "extraTopLevel": "ignored",
                  "signers": [
                    {
                      "role": "Brasatech",
                      "name": "BRASATECH",
                      "status": "SIGNED",
                      "signedAt": "2026-11-20T10:05:00Z",
                      "signatureMethod": "CERTIFICATE",
                      "certificateStatus": "READY",
                      "certificate": { "organizationIdentifier": "VATES-B09767898" }
                    },
                    {
                      "role": "Representante",
                      "name": "Luis Perez",
                      "status": "SIGNED",
                      "signedAt": "2026-11-20T10:04:00Z",
                      "signatureMethod": "CERTIFICATE",
                      "nif": "A28015865",
                      "certificateStatus": "READY",
                      "certificate": {
                        "subject": "CN=PEREZ LUIS - 12345678Z, 2.5.4.97=VATES-A28015865, C=ES",
                        "issuer": "FNMT"
                      }
                    }
                  ],
                  "documents": { "signedDocument": true, "evidenceReport": true }
                }
                """));

        DigitalSignRequest req = client.getRequest("req-99");
        assertThat(req.status()).isEqualTo("COMPLETED");
        assertThat(req.documents().signedDocument()).isTrue();
        assertThat(req.documents().evidenceReport()).isTrue();
        assertThat(req.signers()).hasSize(2);

        Object cert = req.signers().get(1).certificate();
        assertThat(CertificateNifExtractor.verify(cert, "A28015865").status()).isEqualTo(Status.MATCH);
        assertThat(CertificateNifExtractor.verify(cert, "B09767898").status()).isEqualTo(Status.MISMATCH);
    }

    @Test
    void createSigningUrlCancelAndDownloadDocuments() {
        server.createContext("/api/v1/requests/req-1/signers/", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestURI().getRawPath())
                    .isEqualTo("/api/v1/requests/req-1/signers/Representante%20Legal/signing-url");
            respondJson(exchange, 200, """
                    {
                      "requestId": "req-1",
                      "role": "Representante Legal",
                      "signingUrl": "https://digital-sign.brasatech.es/sign/very-secret-link"
                    }
                    """);
        });
        server.createContext("/api/v1/requests/req-1/cancel", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        byte[] signedPdf = "%PDF-1.4 signed".getBytes(StandardCharsets.US_ASCII);
        byte[] evidencePdf = "%PDF-1.4 evidence".getBytes(StandardCharsets.US_ASCII);
        server.createContext("/api/v1/requests/req-1/signed-document", exchange -> respondBytes(exchange, 200, "application/pdf", signedPdf));
        server.createContext("/api/v1/requests/req-1/evidence-report", exchange -> respondBytes(exchange, 200, "application/pdf", evidencePdf));

        SigningUrlResponse url = client.createSigningUrl("req-1", "Representante Legal");
        assertThat(url.signingUrl()).isEqualTo("https://digital-sign.brasatech.es/sign/very-secret-link");
        assertThat(url.toString()).doesNotContain("very-secret-link").contains("[REDACTED]");

        client.cancelRequest("req-1");
        assertThat(client.downloadSignedDocument("req-1")).isEqualTo(signedPdf);
        assertThat(client.downloadEvidenceReport("req-1")).isEqualTo(evidencePdf);
    }

    @ParameterizedTest
    @CsvSource({
            "400, BAD_REQUEST, bad_request, payload",
            "401, UNAUTHORIZED, unauthorized, ",
            "402, ALLOWANCE_EXCEEDED, allowance_exceeded, ",
            "404, NOT_FOUND, not_found, ",
            "409, CONFLICT, conflict, ",
            "410, GONE, gone, ",
            "422, VALIDATION_FAILED, request.organizationCertificateRequired, signers"
    })
    void mapsEvery4xxErrorWithoutRetrying(int status, Kind expectedKind, String code, String field) {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/api/v1/requests/err-" + status, exchange -> {
            attempts.incrementAndGet();
            String fieldJson = (field == null || field.isBlank()) ? "null" : "\"" + field + "\"";
            respondJson(exchange, status,
                    "{\"error\":{\"code\":\"" + code + "\",\"message\":\"Something failed\",\"field\":" + fieldJson + "}}");
        });

        assertThatThrownBy(() -> client.getRequest("err-" + status))
                .isInstanceOf(DigitalSignException.class)
                .satisfies(ex -> {
                    DigitalSignException dse = (DigitalSignException) ex;
                    assertThat(dse.getKind()).isEqualTo(expectedKind);
                    assertThat(dse.getHttpStatus()).isEqualTo(status);
                    assertThat(dse.getErrorCode()).isEqualTo(code);
                    if (field == null || field.isBlank()) {
                        assertThat(dse.getField()).isNull();
                    } else {
                        assertThat(dse.getField()).isEqualTo(field);
                    }
                });

        assertThat(attempts.get()).isEqualTo(1);
        assertThat(recordedBackoffs).isEmpty();
    }

    @Test
    void retriesOn5xxReusingExactSameIdempotencyKey() {
        List<String> keysSeen = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();

        server.createContext("/api/v1/requests", exchange -> {
            keysSeen.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            int n = attempts.incrementAndGet();
            if (n < 3) {
                respondJson(exchange, 503, "{\"error\":{\"code\":\"unavailable\",\"message\":\"Try again\",\"field\":null}}");
                return;
            }
            respondJson(exchange, 201, """
                    {
                      "id": "req-retry-ok",
                      "status": "IN_PROGRESS",
                      "signers": [],
                      "documents": { "signedDocument": false, "evidenceReport": false }
                    }
                    """);
        });

        CreateRequestCommand cmd = new CreateRequestCommand(
                "tpl-1", "Title", null, null, "es", "ref-1", false, List.of(), Map.of(), null);
        CreateRequestResult result = client.createRequest(cmd, "idem-key-123");

        assertThat(result.request().id()).isEqualTo("req-retry-ok");
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(keysSeen).containsExactly("idem-key-123", "idem-key-123", "idem-key-123");
        assertThat(recordedBackoffs).containsExactly(Duration.ofMillis(50), Duration.ofMillis(100));
    }

    @Test
    void throwsServerErrorAfterExhausting5xxRetries() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/api/v1/requests/fail-500", exchange -> {
            attempts.incrementAndGet();
            respondJson(exchange, 500, "{\"error\":{\"code\":\"internal_error\",\"message\":\"boom\",\"field\":null}}");
        });

        assertThatThrownBy(() -> client.getRequest("fail-500"))
                .isInstanceOf(DigitalSignException.class)
                .satisfies(ex -> {
                    DigitalSignException dse = (DigitalSignException) ex;
                    assertThat(dse.getKind()).isEqualTo(Kind.SERVER_ERROR);
                    assertThat(dse.getHttpStatus()).isEqualTo(500);
                });

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(recordedBackoffs).hasSize(2);
    }

    @Test
    void retriesOnNetworkErrorAndMasksSecretsInExceptionMessages() {
        server.createContext("/api/v1/requests/leak-test", exchange -> respondJson(exchange, 400,
                "{\"error\":{\"code\":\"bad_request\",\"message\":\"Rejected Bearer " + TEST_TOKEN
                        + " webhook=" + TEST_WEBHOOK_SECRET
                        + " \\\"signingUrl\\\":\\\"https://digital-sign.brasatech.es/sign/secret123\\\""
                        + " link https://digital-sign.brasatech.es/sign/bare-secret-456\",\"field\":null}}"));

        assertThatThrownBy(() -> client.getRequest("leak-test"))
                .isInstanceOf(DigitalSignException.class)
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).doesNotContain(TEST_TOKEN);
                    assertThat(ex.getMessage()).doesNotContain(TEST_WEBHOOK_SECRET);
                    assertThat(ex.getMessage()).doesNotContain("secret123");
                    assertThat(ex.getMessage()).doesNotContain("bare-secret-456");
                    assertThat(ex.getMessage()).contains("[REDACTED]");
                });

        // Stop server to trigger a real network error and verify retries happen
        server.stop(0);
        server = null;
        recordedBackoffs.clear();

        assertThatThrownBy(() -> client.getRequest("down"))
                .isInstanceOf(DigitalSignException.class)
                .satisfies(ex -> assertThat(((DigitalSignException) ex).getKind()).isEqualTo(Kind.NETWORK_ERROR));
        assertThat(recordedBackoffs).hasSize(2);
    }

    @Test
    void unconfiguredClientThrowsWithoutMakingNetworkCalls() {
        DigitalSignProperties unconfigured = new DigitalSignProperties();
        unconfigured.setApiToken("");
        HttpDigitalSignClient unconfiguredClient = new HttpDigitalSignClient(
                unconfigured, mapper, HttpClient.newHttpClient(), recordedBackoffs::add);

        assertThat(unconfiguredClient.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfiguredClient.getTemplate("tpl-1"))
                .isInstanceOf(DigitalSignException.class)
                .satisfies(ex -> assertThat(((DigitalSignException) ex).getKind()).isEqualTo(Kind.UNCONFIGURED));
    }

    private static void respondJson(HttpExchange exchange, int status, String jsonBody) throws IOException {
        respondBytes(exchange, status, "application/json", jsonBody.getBytes(StandardCharsets.UTF_8));
    }

    private static void respondBytes(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
