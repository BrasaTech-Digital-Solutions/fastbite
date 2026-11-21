package es.brasatech.fastbite.digitalsign;

import es.brasatech.fastbite.application.digitalsign.CallOptions;
import es.brasatech.fastbite.application.digitalsign.CreateRequestCommand;
import es.brasatech.fastbite.application.digitalsign.CreateRequestResult;
import es.brasatech.fastbite.application.digitalsign.DigitalSignClient;
import es.brasatech.fastbite.application.digitalsign.DigitalSignException;
import es.brasatech.fastbite.application.digitalsign.DigitalSignException.Kind;
import es.brasatech.fastbite.application.digitalsign.DigitalSignProperties;
import es.brasatech.fastbite.application.digitalsign.DigitalSignRequest;
import es.brasatech.fastbite.application.digitalsign.DigitalSignRequest.RequestDocuments;
import es.brasatech.fastbite.application.digitalsign.DigitalSignRequest.RequestSigner;
import es.brasatech.fastbite.application.digitalsign.DigitalSignRequest.RequestSignerFile;
import es.brasatech.fastbite.application.digitalsign.DigitalSignTemplate;
import es.brasatech.fastbite.application.digitalsign.DigitalSignTemplate.TemplateField;
import es.brasatech.fastbite.application.digitalsign.DigitalSignTemplate.TemplateRequestedFile;
import es.brasatech.fastbite.application.digitalsign.DigitalSignTemplate.TemplateSigner;
import es.brasatech.fastbite.application.digitalsign.SigningUrlResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * HTTP client for the Digital Sign REST API ({@code /api/v1}).
 *
 * <p>Uses tight timeouts, retries only on 5xx and network failures (always reusing the same
 * {@code Idempotency-Key}), parses JSON tolerantly with {@link JsonNode}, and never logs or leaks
 * the API token, webhook secret, or signing URLs.
 */
@Component
@Slf4j
public class HttpDigitalSignClient implements DigitalSignClient {

    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_ACCEPT = "Accept";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String HEADER_IDEMPOTENT_REPLAYED = "Idempotent-Replayed";
    private static final String MEDIA_JSON = "application/json";
    private static final String MEDIA_PDF = "application/pdf";

    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)Bearer\\s+\\S+");
    private static final Pattern SIGNING_URL_JSON_PATTERN = Pattern.compile("\"signingUrl\"\\s*:\\s*\"[^\"]*\"");
    private static final Pattern SIGNING_URL_KV_PATTERN = Pattern.compile("(?i)signingUrl=\\S+");
    private static final Pattern BARE_SIGNING_LINK_PATTERN = Pattern.compile("(?i)https?://\\S*/sign/\\S+");
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;
    static final int MAX_PDF_BYTES = 20 * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final DigitalSignProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Sleeper sleeper;
    private final Clock clock;

    @Autowired
    public HttpDigitalSignClient(
            DigitalSignProperties properties,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            ObjectProvider<Clock> clockProvider) {
        this(
                properties,
                objectMapperProvider.getIfAvailable(ObjectMapper::new),
                HttpClient.newBuilder()
                        .connectTimeout(defaultIfNull(properties.getConnectTimeout(), Duration.ofSeconds(2)))
                        .build(),
                duration -> {
                    long millis = duration.toMillis();
                    if (millis > 0) {
                        Thread.sleep(millis);
                    }
                },
                clockProvider.getIfAvailable(Clock::systemUTC));
    }

    HttpDigitalSignClient(
            DigitalSignProperties properties,
            ObjectMapper json,
            HttpClient http,
            Sleeper sleeper) {
        this(properties, json, http, sleeper, Clock.systemUTC());
    }

    HttpDigitalSignClient(
            DigitalSignProperties properties,
            ObjectMapper json,
            HttpClient http,
            Sleeper sleeper,
            Clock clock) {
        this.properties = properties;
        this.json = json == null ? new ObjectMapper() : json;
        this.http = http;
        this.sleeper = sleeper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Override
    public DigitalSignTemplate getTemplate(String templateId) {
        requireConfigured();
        requireNonBlank(templateId, "templateId");
        HttpRequest request = baseRequest("/templates/" + encodeSegment(templateId))
                .header(HEADER_ACCEPT, MEDIA_JSON)
                .GET()
                .build();
        HttpResponse<byte[]> response = sendWithRetries(request, "GET /templates/{id}");
        JsonNode root = parseJson(response.body(), response.statusCode());
        return parseTemplate(root);
    }

    @Override
    public CreateRequestResult createRequest(CreateRequestCommand command, String idempotencyKey) {
        requireConfigured();
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        requireNonBlank(idempotencyKey, "idempotencyKey");
        byte[] payload = serializeCreateRequest(command);
        HttpRequest request = baseRequest("/requests")
                .header(HEADER_ACCEPT, MEDIA_JSON)
                .header(HEADER_CONTENT_TYPE, MEDIA_JSON)
                .header(HEADER_IDEMPOTENCY_KEY, idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        HttpResponse<byte[]> response = sendWithRetries(request, "POST /requests");
        JsonNode root = parseJson(response.body(), response.statusCode());
        boolean replayed = response.headers()
                .firstValue(HEADER_IDEMPOTENT_REPLAYED)
                .map(v -> "true".equalsIgnoreCase(v.strip()))
                .orElse(false);
        return new CreateRequestResult(parseRequest(root), replayed);
    }

    @Override
    public DigitalSignRequest getRequest(String requestId) {
        return getRequest(requestId, CallOptions.DEFAULT);
    }

    @Override
    public DigitalSignRequest getRequest(String requestId, CallOptions options) {
        requireConfigured();
        requireNonBlank(requestId, "requestId");
        CallOptions effective = options == null ? CallOptions.DEFAULT : options;
        HttpRequest request = baseRequest("/requests/" + encodeSegment(requestId), effective, "GET /requests/{id}")
                .header(HEADER_ACCEPT, MEDIA_JSON)
                .GET()
                .build();
        HttpResponse<byte[]> response = sendWithRetries(request, "GET /requests/{id}", effective);
        JsonNode root = parseJson(response.body(), response.statusCode());
        return parseRequest(root);
    }

    @Override
    public SigningUrlResponse createSigningUrl(String requestId, String role) {
        requireConfigured();
        requireNonBlank(requestId, "requestId");
        requireNonBlank(role, "role");
        HttpRequest request = baseRequest(
                        "/requests/" + encodeSegment(requestId) + "/signers/" + encodeSegment(role) + "/signing-url")
                .header(HEADER_ACCEPT, MEDIA_JSON)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<byte[]> response = sendWithRetries(request, "POST /requests/{id}/signers/{role}/signing-url");
        JsonNode root = parseJson(response.body(), response.statusCode());
        return new SigningUrlResponse(
                textOrNull(root, "requestId"),
                textOrNull(root, "role"),
                textOrNull(root, "signingUrl"));
    }

    @Override
    public void cancelRequest(String requestId) {
        requireConfigured();
        requireNonBlank(requestId, "requestId");
        HttpRequest request = baseRequest("/requests/" + encodeSegment(requestId) + "/cancel")
                .header(HEADER_ACCEPT, MEDIA_JSON)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        sendWithRetries(request, "POST /requests/{id}/cancel");
    }

    @Override
    public byte[] downloadSignedDocument(String requestId) {
        return downloadSignedDocument(requestId, CallOptions.DEFAULT);
    }

    @Override
    public byte[] downloadSignedDocument(String requestId, CallOptions options) {
        requireConfigured();
        requireNonBlank(requestId, "requestId");
        CallOptions effective = options == null ? CallOptions.DEFAULT : options;
        HttpRequest request = baseRequest(
                        "/requests/" + encodeSegment(requestId) + "/signed-document",
                        effective,
                        "GET /requests/{id}/signed-document")
                .header(HEADER_ACCEPT, MEDIA_PDF)
                .GET()
                .build();
        HttpResponse<byte[]> response = sendWithRetries(
                request, "GET /requests/{id}/signed-document", effective, MAX_PDF_BYTES);
        return response.body() == null ? new byte[0] : response.body();
    }

    @Override
    public byte[] downloadEvidenceReport(String requestId) {
        return downloadEvidenceReport(requestId, CallOptions.DEFAULT);
    }

    @Override
    public byte[] downloadEvidenceReport(String requestId, CallOptions options) {
        requireConfigured();
        requireNonBlank(requestId, "requestId");
        CallOptions effective = options == null ? CallOptions.DEFAULT : options;
        HttpRequest request = baseRequest(
                        "/requests/" + encodeSegment(requestId) + "/evidence-report",
                        effective,
                        "GET /requests/{id}/evidence-report")
                .header(HEADER_ACCEPT, MEDIA_PDF)
                .GET()
                .build();
        HttpResponse<byte[]> response = sendWithRetries(
                request, "GET /requests/{id}/evidence-report", effective, MAX_PDF_BYTES);
        return response.body() == null ? new byte[0] : response.body();
    }

    private HttpRequest.Builder baseRequest(String path) {
        return baseRequest(path, CallOptions.DEFAULT, path);
    }

    private HttpRequest.Builder baseRequest(String path, CallOptions options, String operation) {
        URI uri = URI.create(properties.normalizedBaseUrl() + path);
        Duration timeout = defaultIfNull(properties.getReadTimeout(), Duration.ofSeconds(4));
        if (options != null && options.deadline() != null) {
            Duration remaining = Duration.between(clock.instant(), options.deadline());
            if (remaining.isZero() || remaining.isNegative()) {
                throw new DigitalSignException(
                        Kind.NETWORK_ERROR, 0, "deadline_exceeded", null,
                        "Digital Sign deadline exceeded before " + operation);
            }
            if (remaining.compareTo(timeout) < 0) {
                timeout = remaining;
            }
        }
        return HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header(HEADER_AUTHORIZATION, "Bearer " + properties.getApiToken());
    }

    private HttpResponse<byte[]> sendWithRetries(HttpRequest request, String operation) {
        return sendWithRetries(request, operation, CallOptions.DEFAULT, MAX_JSON_BYTES);
    }

    private HttpResponse<byte[]> sendWithRetries(HttpRequest request, String operation, CallOptions options) {
        return sendWithRetries(request, operation, options, MAX_JSON_BYTES);
    }

    private HttpResponse<byte[]> sendWithRetries(
            HttpRequest request, String operation, CallOptions options, int maxBodyBytes) {
        int maxRetries = options != null && options.maxRetries() >= 0
                ? options.maxRetries()
                : Math.max(0, properties.getMaxRetries());
        Instant deadline = options == null ? null : options.deadline();
        Duration initialBackoff = defaultIfNull(properties.getRetryInitialBackoff(), Duration.ofMillis(200));

        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<byte[]> response = sendBounded(request, operation, deadline, maxBodyBytes);
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response;
                }
                if (status >= 500 && status < 600 && attempt < maxRetries
                        && sleepBackoffIfWithinDeadline(initialBackoff, attempt, operation, deadline)) {
                    log.warn("Digital Sign {} returned HTTP {}, retrying (attempt {}/{})",
                            operation, status, attempt + 1, maxRetries);
                    continue;
                }
                throw mapError(status, response.body(), operation);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DigitalSignException(
                        Kind.NETWORK_ERROR, 0, "interrupted", null,
                        "Interrupted during Digital Sign " + operation, e);
            } catch (BodyTooLargeException e) {
                throw new DigitalSignException(
                        Kind.UNEXPECTED_RESPONSE, 0, "response_too_large", null,
                        "Digital Sign " + operation + " exceeded maximum size of " + maxBodyBytes + " bytes", e);
            } catch (IOException e) {
                if (attempt < maxRetries
                        && sleepBackoffIfWithinDeadline(initialBackoff, attempt, operation, deadline)) {
                    log.warn("Digital Sign {} network error ({}), retrying (attempt {}/{})",
                            operation, e.getClass().getSimpleName(), attempt + 1, maxRetries);
                    continue;
                }
                throw new DigitalSignException(
                        Kind.NETWORK_ERROR, 0, "network_error", null,
                        sanitize("Digital Sign " + operation + " failed: " + e.getMessage()), e);
            }
        }
    }

    private HttpResponse<byte[]> sendBounded(
            HttpRequest request, String operation, Instant deadline, int maxBodyBytes)
            throws IOException, InterruptedException, BodyTooLargeException {
        HttpResponse.BodyHandler<byte[]> handler = boundedByteArrayHandler(maxBodyBytes);
        if (deadline == null) {
            try {
                return http.send(request, handler);
            } catch (IOException e) {
                BodyTooLargeException tooLarge = findBodyTooLarge(e);
                if (tooLarge != null) {
                    throw tooLarge;
                }
                throw e;
            }
        }
        long remainingMillis = Duration.between(clock.instant(), deadline).toMillis();
        if (remainingMillis <= 0) {
            throw new DigitalSignException(
                    Kind.NETWORK_ERROR, 0, "deadline_exceeded", null,
                    "Digital Sign deadline exceeded before " + operation);
        }
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request, handler);
        try {
            return future.get(remainingMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new DigitalSignException(
                    Kind.NETWORK_ERROR, 0, "deadline_exceeded", null,
                    "Digital Sign " + operation + " exceeded deadline", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            BodyTooLargeException tooLarge = findBodyTooLarge(e);
            if (tooLarge != null) {
                throw tooLarge;
            }
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof DigitalSignException dse) {
                throw dse;
            }
            throw new IOException(cause == null ? e.getMessage() : cause.getMessage(), cause);
        }
    }

    private static BodyTooLargeException findBodyTooLarge(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof BodyTooLargeException tooLarge) {
                return tooLarge;
            }
        }
        return null;
    }

    private static HttpResponse.BodyHandler<byte[]> boundedByteArrayHandler(int maxBytes) {
        return responseInfo -> {
            long contentLength = responseInfo.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > maxBytes) {
                return new FailingBodySubscriber(new BodyTooLargeException(maxBytes));
            }
            return new BoundedByteArraySubscriber(maxBytes);
        };
    }

    private static final class BodyTooLargeException extends IOException {
        BodyTooLargeException(int maxBytes) {
            super("Response body exceeded " + maxBytes + " bytes");
        }
    }

    private static final class FailingBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();

        FailingBodySubscriber(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
        }
    }

    private static final class BoundedByteArraySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private int total;

        BoundedByteArraySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                int len = item.remaining();
                if (len == 0) {
                    continue;
                }
                if (total + len > maxBytes) {
                    if (subscription != null) {
                        subscription.cancel();
                    }
                    result.completeExceptionally(new BodyTooLargeException(maxBytes));
                    return;
                }
                byte[] chunk = new byte[len];
                item.get(chunk);
                buffer.write(chunk, 0, len);
                total += len;
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            if (!result.isDone()) {
                result.complete(buffer.toByteArray());
            }
        }
    }

    private boolean sleepBackoffIfWithinDeadline(
            Duration initialBackoff, int attempt, String operation, Instant deadline) {
        Duration delay = initialBackoff.multipliedBy(1L << Math.min(attempt, 10));
        if (deadline != null && !clock.instant().plus(delay).isBefore(deadline)) {
            return false;
        }
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DigitalSignException(
                    Kind.NETWORK_ERROR, 0, "interrupted", null,
                    "Interrupted while backing off for Digital Sign " + operation, e);
        }
        return deadline == null || clock.instant().isBefore(deadline);
    }

    private DigitalSignException mapError(int status, byte[] bodyBytes, String operation) {
        String code = null;
        String field = null;
        String apiMessage = null;
        if (bodyBytes != null && bodyBytes.length > 0) {
            try {
                JsonNode root = json.readTree(bodyBytes);
                JsonNode errorNode = root.get("error");
                if (errorNode != null && errorNode.isObject()) {
                    code = textOrNull(errorNode, "code");
                    apiMessage = textOrNull(errorNode, "message");
                    field = textOrNull(errorNode, "field");
                }
            } catch (Exception ignored) {
                // Non-JSON error body; fall back to HTTP status message.
            }
        }
        Kind kind = switch (status) {
            case 400 -> Kind.BAD_REQUEST;
            case 401 -> Kind.UNAUTHORIZED;
            case 402 -> Kind.ALLOWANCE_EXCEEDED;
            case 404 -> Kind.NOT_FOUND;
            case 409 -> Kind.CONFLICT;
            case 410 -> Kind.GONE;
            case 422 -> Kind.VALIDATION_FAILED;
            default -> (status >= 500 && status < 600) ? Kind.SERVER_ERROR : Kind.UNEXPECTED_RESPONSE;
        };
        String rawMessage = apiMessage != null && !apiMessage.isBlank()
                ? "Digital Sign " + operation + " failed with HTTP " + status + ": " + apiMessage
                : "Digital Sign " + operation + " failed with HTTP " + status;
        return new DigitalSignException(kind, status, code, field, sanitize(rawMessage));
    }

    private byte[] serializeCreateRequest(CreateRequestCommand command) {
        ObjectNode root = json.createObjectNode();
        root.put("templateId", command.templateId());
        if (command.title() != null) {
            root.put("title", command.title());
        }
        if (command.message() != null) {
            root.put("message", command.message());
        }
        if (command.expiresOn() != null) {
            root.put("expiresOn", command.expiresOn().toString());
        }
        if (command.language() != null) {
            root.put("language", command.language());
        }
        if (command.externalReference() != null) {
            root.put("externalReference", command.externalReference());
        }
        root.put("sendEmail", command.sendEmail());

        ArrayNode signersNode = root.putArray("signers");
        for (CreateRequestCommand.SignerInput signer : command.signers()) {
            ObjectNode s = signersNode.addObject();
            s.put("role", signer.role());
            s.put("name", signer.name());
            s.put("email", signer.email());
            if (signer.language() != null) {
                s.put("language", signer.language());
            }
            if (signer.signatureMethod() != null) {
                s.put("signatureMethod", signer.signatureMethod());
            }
            if (signer.nif() != null) {
                s.put("nif", signer.nif());
            }
        }

        if (!command.values().isEmpty()) {
            ObjectNode valuesNode = root.putObject("values");
            for (Map.Entry<String, String> entry : command.values().entrySet()) {
                valuesNode.put(entry.getKey(), entry.getValue());
            }
        }

        if (command.requestedFiles() != null) {
            ArrayNode filesNode = root.putArray("requestedFiles");
            for (CreateRequestCommand.RequestedFileInput file : command.requestedFiles()) {
                ObjectNode f = filesNode.addObject();
                f.put("role", file.role());
                f.put("label", file.label());
                f.put("required", file.required());
            }
        }

        return json.writeValueAsBytes(root);
    }

    private JsonNode parseJson(byte[] body, int status) {
        if (body == null || body.length == 0) {
            throw new DigitalSignException(
                    Kind.UNEXPECTED_RESPONSE, status, "empty_body", null,
                    "Digital Sign returned an empty JSON body (HTTP " + status + ")");
        }
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new DigitalSignException(
                    Kind.UNEXPECTED_RESPONSE, status, "invalid_json", null,
                    sanitize("Digital Sign returned invalid JSON (HTTP " + status + "): " + e.getMessage()), e);
        }
    }

    private DigitalSignTemplate parseTemplate(JsonNode root) {
        List<TemplateSigner> signers = new ArrayList<>();
        JsonNode signersNode = root.get("signers");
        if (signersNode != null && signersNode.isArray()) {
            for (JsonNode s : signersNode) {
                List<TemplateRequestedFile> files = new ArrayList<>();
                JsonNode filesNode = s.get("files");
                if (filesNode != null && filesNode.isArray()) {
                    for (JsonNode f : filesNode) {
                        files.add(new TemplateRequestedFile(
                                textOrNull(f, "label"),
                                boolOrFalse(f, "required")));
                    }
                }
                signers.add(new TemplateSigner(
                        intOrZero(s, "index"),
                        textOrNull(s, "role"),
                        boolOrFalse(s, "certificate"),
                        files));
            }
        }

        List<TemplateField> fields = new ArrayList<>();
        JsonNode fieldsNode = root.get("fields");
        if (fieldsNode != null && fieldsNode.isArray()) {
            for (JsonNode f : fieldsNode) {
                fields.add(new TemplateField(
                        textOrNull(f, "key"),
                        textOrNull(f, "label"),
                        textOrNull(f, "type"),
                        boolOrFalse(f, "required"),
                        textOrNull(f, "validation")));
            }
        }

        return new DigitalSignTemplate(
                textOrNull(root, "id"),
                textOrNull(root, "name"),
                signers,
                fields);
    }

    private DigitalSignRequest parseRequest(JsonNode root) {
        List<RequestSigner> signers = new ArrayList<>();
        JsonNode signersNode = root.get("signers");
        if (signersNode != null && signersNode.isArray()) {
            for (JsonNode s : signersNode) {
                List<RequestSignerFile> files = new ArrayList<>();
                JsonNode filesNode = s.get("files");
                if (filesNode != null && filesNode.isArray()) {
                    for (JsonNode f : filesNode) {
                        files.add(new RequestSignerFile(
                                textOrNull(f, "label"),
                                boolOrFalse(f, "required"),
                                textOrNull(f, "status")));
                    }
                }
                signers.add(new RequestSigner(
                        textOrNull(s, "role"),
                        textOrNull(s, "name"),
                        textOrNull(s, "email"),
                        textOrNull(s, "language"),
                        textOrNull(s, "status"),
                        instantOrNull(s, "signedAt"),
                        textOrNull(s, "signingUrl"),
                        files,
                        textOrNull(s, "signatureMethod"),
                        textOrNull(s, "nif"),
                        textOrNull(s, "certificateStatus"),
                        toPlainObject(s.get("certificate"))));
            }
        }

        JsonNode docsNode = root.get("documents");
        RequestDocuments docs = docsNode != null && docsNode.isObject()
                ? new RequestDocuments(boolOrFalse(docsNode, "signedDocument"), boolOrFalse(docsNode, "evidenceReport"))
                : new RequestDocuments(false, false);

        return new DigitalSignRequest(
                textOrNull(root, "id"),
                textOrNull(root, "status"),
                textOrNull(root, "externalReference"),
                textOrNull(root, "title"),
                instantOrNull(root, "createdAt"),
                instantOrNull(root, "expiresAt"),
                instantOrNull(root, "completedAt"),
                signers,
                docs,
                boolOrFalse(root, "test"));
    }

    private static Object toPlainObject(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isString()) {
            return node.asString();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode element : node) {
                list.add(toPlainObject(element));
            }
            return Collections.unmodifiableList(list);
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                map.put(entry.getKey(), toPlainObject(entry.getValue()));
            }
            return Collections.unmodifiableMap(map);
        }
        return node.asString();
    }

    String sanitize(String input) {
        if (input == null) {
            return "";
        }
        String result = input;
        String token = properties.getApiToken();
        if (token != null && !token.isBlank()) {
            result = result.replace(token, "[REDACTED]");
        }
        String secret = properties.getWebhookSecret();
        if (secret != null && !secret.isBlank()) {
            result = result.replace(secret, "[REDACTED]");
        }
        result = BEARER_PATTERN.matcher(result).replaceAll("Bearer [REDACTED]");
        result = SIGNING_URL_JSON_PATTERN.matcher(result).replaceAll("\"signingUrl\":\"[REDACTED]\"");
        result = SIGNING_URL_KV_PATTERN.matcher(result).replaceAll("signingUrl=[REDACTED]");
        result = BARE_SIGNING_LINK_PATTERN.matcher(result).replaceAll("[REDACTED]");
        if (result.length() > MAX_ERROR_MESSAGE_LENGTH) {
            result = result.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "...";
        }
        return result;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new DigitalSignException(
                    Kind.UNCONFIGURED, 0, "unconfigured", null,
                    "Digital Sign is not configured");
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.isMissingNode() ? null : value.asString();
    }

    private static boolean boolOrFalse(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() && value.asBoolean(false);
    }

    private static int intOrZero(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? 0 : value.asInt(0);
    }

    private static Instant instantOrNull(JsonNode node, String field) {
        String text = textOrNull(node, field);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Duration defaultIfNull(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }
}
