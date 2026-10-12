package es.brasatech.fastbite.verifactu.aeat;

import es.brasatech.fastbite.application.verifactu.VerifactuSubmitter;
import es.brasatech.fastbite.application.verifactu.VerifactuSystem;
import es.brasatech.fastbite.domain.fiscal.SpanishTaxId;
import es.brasatech.fastbite.domain.verifactu.VerifactuRecord;
import es.brasatech.fastbite.domain.verifactu.VerifactuStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Sends billing records straight to the AEAT's VERI*FACTU web service, signed in with the
 * producer's electronic certificate (mutual TLS). The certificate must be allowed to act for each
 * issuer: its own NIF, an apoderamiento, or colaboración social.
 *
 * <p>Nothing is sent until a certificate is configured. The sandbox only ever talks to the AEAT's
 * test environment, and the production environment stays closed until
 * {@code fastbite.verifactu.aeat.production-enabled} is set.
 */
@Component
@Primary
@Slf4j
public class AeatVerifactuSubmitter implements VerifactuSubmitter {

    static final String TEST = "https://prewww1.aeat.es/wlpl/TIKE-CONT/ws/SistemaFacturacion/VerifactuSOAP";
    static final String TEST_SEAL = "https://prewww10.aeat.es/wlpl/TIKE-CONT/ws/SistemaFacturacion/VerifactuSOAP";
    static final String PRODUCTION = "https://www1.agenciatributaria.gob.es/wlpl/TIKE-CONT/ws/SistemaFacturacion/VerifactuSOAP";
    static final String PRODUCTION_SEAL = "https://www10.agenciatributaria.gob.es/wlpl/TIKE-CONT/ws/SistemaFacturacion/VerifactuSOAP";

    /** The AEAT may be sent this many records without waiting. */
    private static final int FULL_BATCH = 1000;

    private final HttpClient http;
    private final String testEndpoint;
    private final String productionEndpoint;
    private final boolean productionEnabled;
    private final Set<String> issuers;
    private final Clock clock;
    private final String problem;
    /** When each issuer may be sent records again, as the AEAT asked. */
    private final Map<String, Instant> nextSubmission = new ConcurrentHashMap<>();

    @Autowired
    public AeatVerifactuSubmitter(
            @Value("${fastbite.verifactu.aeat.certificate-path:}") String certificatePath,
            @Value("${fastbite.verifactu.aeat.certificate-password:}") String certificatePassword,
            @Value("${fastbite.verifactu.aeat.seal-certificate:false}") boolean sealCertificate,
            @Value("${fastbite.verifactu.aeat.production-enabled:false}") boolean productionEnabled,
            @Value("${fastbite.verifactu.aeat.issuers:}") String issuers,
            VerifactuSystem system) {
        String problem = null;
        HttpClient http = null;
        if (certificatePath == null || certificatePath.isBlank()) {
            problem = "no certificate";
        } else if (system.producerNif() == null || system.producerNif().isBlank()) {
            problem = "fastbite.verifactu.producer-nif is not set";
            log.warn("VERI*FACTU: records will not be sent to the AEAT: {}", problem);
        } else {
            try {
                http = client(sslContext(Path.of(certificatePath), certificatePassword));
                log.info("VERI*FACTU: records are sent to the AEAT {}", productionEnabled
                        ? "(production enabled)" : "test environment only");
            } catch (Exception e) {
                problem = "certificate could not be loaded: " + e.getMessage();
                log.error("VERI*FACTU: records will not be sent to the AEAT: {}", problem);
            }
        }
        this.http = http;
        this.problem = problem;
        this.testEndpoint = sealCertificate ? TEST_SEAL : TEST;
        this.productionEndpoint = sealCertificate ? PRODUCTION_SEAL : PRODUCTION;
        this.productionEnabled = productionEnabled;
        this.issuers = parseIssuers(issuers);
        this.clock = Clock.systemUTC();
    }

    /** For tests: talks to the given endpoints with the given client. */
    AeatVerifactuSubmitter(HttpClient http, String testEndpoint, String productionEndpoint, boolean productionEnabled,
            Set<String> issuers, Clock clock) {
        this.http = http;
        this.problem = null;
        this.testEndpoint = testEndpoint;
        this.productionEndpoint = productionEndpoint;
        this.productionEnabled = productionEnabled;
        this.issuers = issuers;
        this.clock = clock;
    }

    @Override
    public boolean configured() {
        return problem == null;
    }

    @Override
    public String name() {
        return productionEnabled ? "AEAT" : "AEAT (entorno de pruebas)";
    }

    @Override
    public List<Result> submit(VerifactuSystem system, Installation installation, List<Submission> records,
            boolean production) {
        if (!configured() || records.isEmpty()) {
            return List.of();
        }
        if (production && !productionEnabled) {
            log.warn("VERI*FACTU: {} records wait: sending to the AEAT production environment is not enabled",
                    records.size());
            return List.of();
        }
        String endpoint = production ? productionEndpoint : testEndpoint;
        List<Result> results = new ArrayList<>();
        Map<String, List<Submission>> byIssuer = new LinkedHashMap<>();
        for (Submission submission : records) {
            VerifactuRecord record = submission.record();
            String invalid = invalid(record);
            if (invalid != null) {
                // The AEAT would refuse the whole submission for it; say why here instead
                results.add(new Result(record.id(), VerifactuStatus.REJECTED, invalid, null));
            } else if (issuers.isEmpty() || issuers.contains(record.issuerNif())) {
                byIssuer.computeIfAbsent(record.issuerNif(), nif -> new ArrayList<>()).add(submission);
            }
        }
        for (var entry : byIssuer.entrySet()) {
            Instant now = clock.instant();
            Instant allowed = nextSubmission.get(entry.getKey());
            if (allowed != null && now.isBefore(allowed) && entry.getValue().size() < FULL_BATCH) {
                continue;
            }
            results.addAll(send(endpoint, system, installation, entry.getKey(), entry.getValue()));
        }
        return results;
    }

    private List<Result> send(String endpoint, VerifactuSystem system, Installation installation, String issuer,
            List<Submission> records) {
        String body = AeatRequest.envelope(system, installation, records);
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "text/xml; charset=UTF-8")
                    .header("SOAPAction", "\"\"")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            // No answer: the records stay pending and go again next time; the AEAT spots duplicates
            throw new IllegalStateException("AEAT could not be reached: " + e.getMessage(), e);
        }
        AeatResponse answer;
        try {
            answer = AeatResponse.parse(response.body());
        } catch (AeatResponse.Fault e) {
            // The whole submission was refused: the records stay pending, and are not resent at once
            nextSubmission.put(issuer, clock.instant().plusSeconds(AeatResponse.DEFAULT_WAIT));
            throw new IllegalStateException(e.getMessage() + " (HTTP " + response.statusCode() + ")", e);
        }
        nextSubmission.put(issuer, clock.instant().plusSeconds(answer.waitSeconds()));

        Map<String, VerifactuRecord> byNumber = records.stream().map(Submission::record)
                .collect(Collectors.toMap(VerifactuRecord::invoiceNumber, record -> record, (a, b) -> a));
        List<Result> results = new ArrayList<>();
        for (AeatResponse.Line line : answer.lines()) {
            VerifactuRecord record = byNumber.get(line.invoiceNumber());
            if (record != null) {
                String csv = line.status() == VerifactuStatus.REJECTED ? null : answer.csv();
                results.add(new Result(record.id(), line.status(), line.error(), csv));
            }
        }
        log.info("VERI*FACTU: AEAT answered {} for {} records of {} (CSV {})", answer.state(), records.size(), issuer,
                answer.csv());
        return results;
    }

    /** Why the AEAT could not take this record at all, or null. */
    private static String invalid(VerifactuRecord record) {
        if (record.issuerNif() == null || record.issuerNif().isBlank()) {
            return "The location's tax ID (NIF) is missing";
        }
        // Sent exactly as recorded: it is part of the fingerprint, so it cannot be tidied up now
        if (!SpanishTaxId.parse(record.issuerNif()).map(SpanishTaxId::value).filter(record.issuerNif()::equals).isPresent()) {
            return "The location's tax ID (NIF) \"" + record.issuerNif() + "\" is not a valid NIF";
        }
        if (record.issuerName() == null || record.issuerName().isBlank()) {
            return "The location's legal name is missing";
        }
        return null;
    }

    private static Set<String> parseIssuers(String issuers) {
        if (issuers == null || issuers.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(issuers.split(",")).map(String::strip).filter(nif -> !nif.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static SSLContext sslContext(Path certificate, String password) throws Exception {
        char[] secret = password == null ? new char[0] : password.toCharArray();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(certificate)) {
            keys.load(in, secret);
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, secret);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init((KeyStore) null);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
        return context;
    }

    private static HttpClient client(SSLContext ssl) {
        return HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(30)).build();
    }
}
