package es.brasatech.fastbite.service;

import es.brasatech.fastbite.application.platform.LinkSignature;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.platform.SandboxLinkPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Production calling its sandbox, signed with the secret they share and naming the version it runs. */
@Component
public class HttpSandboxLink implements SandboxLinkPort {

    private static final Logger log = LoggerFactory.getLogger(HttpSandboxLink.class);

    public static final String SIGNATURE = "X-FastBite-Signature";
    public static final String VERSION = "X-FastBite-Version";
    static final String LOCATIONS = "/internal/link/locations";
    static final String PRODUCTION_BUNDLE = "/internal/link/production-bundle";

    private static final ScheduledExecutorService WATCHDOG_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sandbox-bundle-watchdog");
                t.setDaemon(true);
                return t;
            });

    private final PlatformLink link;
    private final ObjectMapper json;
    private final String version;
    private final HttpClient http;
    private final Duration callTimeout;

    @Autowired
    public HttpSandboxLink(PlatformLink link, ObjectMapper json, @Value("${fastbite.version:dev}") String version) {
        this(link, json, version, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), Duration.ofSeconds(40));
    }

    HttpSandboxLink(PlatformLink link, ObjectMapper json, String version, HttpClient http) {
        this(link, json, version, http, Duration.ofSeconds(40));
    }

    HttpSandboxLink(PlatformLink link, ObjectMapper json, String version, HttpClient http, Duration callTimeout) {
        this.link = link;
        this.json = json;
        this.version = version;
        this.http = http;
        this.callTimeout = callTimeout;
    }

    @Override
    public void provision(LinkedLocation location) {
        if (!link.linked()) {
            throw new Unavailable(Reason.UNREACHABLE, "No sandbox is linked");
        }
        var body = json.createObjectNode()
                .put("tenantId", location.tenantId())
                .put("ownerUsername", location.ownerUsername())
                .put("ownerName", location.ownerName())
                .put("plan", location.plan());
        byte[] content = json.writeValueAsBytes(body);
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(link.internalUrl() + LOCATIONS))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header(SIGNATURE, LinkSignature.header(link.secret(), Instant.now(), content))
                    .header(VERSION, version)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable(Reason.UNREACHABLE, "Interrupted while calling the sandbox", e);
        } catch (Exception e) {
            throw new Unavailable(Reason.UNREACHABLE, "The sandbox could not be reached: " + e.getMessage(), e);
        }
        if (response.statusCode() / 100 != 2) {
            String reason = response.body() == null ? "" : response.body();
            throw new Unavailable(Reason.UNREACHABLE, "The sandbox answered " + response.statusCode() + ": "
                    + reason.substring(0, Math.min(300, reason.length())));
        }
    }

    @Override
    public InputStream streamBundle(String tenantId, String ownerUsername, long maxBytes) {
        if (!link.linked()) {
            throw new Unavailable(Reason.UNREACHABLE, "No sandbox is linked");
        }
        var body = json.createObjectNode()
                .put("tenantId", tenantId)
                .put("ownerUsername", ownerUsername);
        byte[] content = json.writeValueAsBytes(body);

        Instant now = Instant.now();
        Instant deadline = now.plus(callTimeout);
        Duration requestTimeout = Duration.between(now, deadline);
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new Unavailable(Reason.TIMED_OUT, "Timed out before calling the sandbox");
        }

        HttpResponse<InputStream> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(link.internalUrl() + PRODUCTION_BUNDLE))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header(SIGNATURE, LinkSignature.header(link.secret(), Instant.now(), content))
                    .header(VERSION, version)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                    .build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new Unavailable(Reason.TIMED_OUT, "The sandbox request timed out waiting for headers: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable(Reason.UNREACHABLE, "Interrupted while calling the sandbox", e);
        } catch (IOException e) {
            if (e instanceof HttpTimeoutException || (e.getCause() instanceof HttpTimeoutException)) {
                throw new Unavailable(Reason.TIMED_OUT, "The sandbox request timed out waiting for headers: " + e.getMessage(), e);
            }
            throw new Unavailable(Reason.UNREACHABLE, "The sandbox could not be reached: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new Unavailable(Reason.UNREACHABLE, "The sandbox could not be reached: " + e.getMessage(), e);
        }

        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            try {
                response.body().close();
            } catch (IOException ignored) {
            }
            throw new Unavailable(Reason.TIMED_OUT, "Timed out waiting for sandbox headers");
        }

        if (response.statusCode() / 100 != 2) {
            String reason = "";
            try (InputStream errStream = response.body()) {
                byte[] errBytes = errStream.readNBytes(300);
                reason = new String(errBytes, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
            log.warn("The sandbox bundle request answered {}: {}", response.statusCode(), reason);
            throw mapSandboxError(response.statusCode());
        }

        if (response.headers() != null) {
            var contentLengthOpt = response.headers().firstValueAsLong("content-length");
            if (contentLengthOpt.isPresent() && contentLengthOpt.getAsLong() > maxBytes) {
                try {
                    response.body().close();
                } catch (IOException ignored) {
                }
                throw new Unavailable(Reason.TOO_LARGE,
                        "Sandbox bundle Content-Length (" + contentLengthOpt.getAsLong() + ") exceeds limit of " + maxBytes + " bytes");
            }
        }

        return WatchdogInputStream.wrap(response.body(), WATCHDOG_EXECUTOR, remaining, maxBytes);
    }

    private Unavailable mapSandboxError(int statusCode) {
        return switch (statusCode) {
            case 400 -> new Unavailable(Reason.REFUSED, "Bad request to sandbox");
            case 401 -> new Unavailable(Reason.REFUSED, "Not signed by production");
            case 403 -> new Unavailable(Reason.REFUSED, "You do not own this location in the sandbox");
            case 404 -> new Unavailable(Reason.NOTHING_TO_PROMOTE, "Location not found in the sandbox");
            case 409 -> new Unavailable(Reason.OTHER_VERSION, "The sandbox runs a different version of FastBite");
            case 422 -> new Unavailable(Reason.NOTHING_TO_PROMOTE, "The sandbox location has no products to promote");
            default -> new Unavailable(Reason.UNREACHABLE, "The sandbox answered " + statusCode);
        };
    }
}
