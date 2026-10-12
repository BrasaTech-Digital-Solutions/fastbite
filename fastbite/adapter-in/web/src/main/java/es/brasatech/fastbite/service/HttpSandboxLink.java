package es.brasatech.fastbite.service;

import es.brasatech.fastbite.application.platform.LinkSignature;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.platform.SandboxLinkPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/** Production calling its sandbox, signed with the secret they share and naming the version it runs. */
@Component
public class HttpSandboxLink implements SandboxLinkPort {

    public static final String SIGNATURE = "X-FastBite-Signature";
    public static final String VERSION = "X-FastBite-Version";
    static final String LOCATIONS = "/internal/link/locations";

    private final PlatformLink link;
    private final ObjectMapper json;
    private final String version;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public HttpSandboxLink(PlatformLink link, ObjectMapper json, @Value("${fastbite.version:dev}") String version) {
        this.link = link;
        this.json = json;
        this.version = version;
    }

    @Override
    public void provision(LinkedLocation location) {
        if (!link.linked()) {
            throw new Unavailable("No sandbox is linked");
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
            throw new Unavailable("Interrupted while calling the sandbox");
        } catch (Exception e) {
            throw new Unavailable("The sandbox could not be reached: " + e.getMessage());
        }
        if (response.statusCode() / 100 != 2) {
            String reason = response.body() == null ? "" : response.body();
            throw new Unavailable("The sandbox answered " + response.statusCode() + ": "
                    + reason.substring(0, Math.min(300, reason.length())));
        }
    }
}
