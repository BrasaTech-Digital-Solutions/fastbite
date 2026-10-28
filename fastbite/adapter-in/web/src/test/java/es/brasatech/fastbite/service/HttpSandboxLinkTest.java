package es.brasatech.fastbite.service;

import com.sun.net.httpserver.HttpServer;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.platform.SandboxLinkPort;
import es.brasatech.fastbite.application.platform.SandboxLinkPort.BundleStreamException;
import es.brasatech.fastbite.application.platform.SandboxLinkPort.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpSandboxLinkTest {

    static final String SECRET = "a-secret-of-at-least-thirty-two-characters";

    PlatformEnvironment env = new PlatformEnvironment("production");
    PlatformLink link = new PlatformLink(env, "http://sandbox", "", SECRET, false);
    PlatformLink unlinked = new PlatformLink(env, "", "", "", true);
    ObjectMapper json = new ObjectMapper();
    HttpClient http = mock(HttpClient.class);

    HttpSandboxLink client;
    HttpSandboxLink unlinkedClient;

    @BeforeEach
    void setUp() {
        client = new HttpSandboxLink(link, json, "1.2.0", http);
        unlinkedClient = new HttpSandboxLink(unlinked, json, "1.2.0", http);
    }

    @Test
    void streamBundleWhenNotLinkedThrowsUnavailable() {
        assertThatThrownBy(() -> unlinkedClient.streamBundle("anacafe", "ana"))
                .isInstanceOf(SandboxLinkPort.Unavailable.class)
                .satisfies(e -> {
                    var ex = (SandboxLinkPort.Unavailable) e;
                    assertThat(ex.reason()).isEqualTo(Reason.UNREACHABLE);
                    assertThat(ex.getMessage()).isEqualTo("No sandbox is linked");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamBundleSuccessReturnsWatchdogWrappedStream() throws Exception {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream("bundle data".getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()))).thenReturn(response);

        try (InputStream stream = client.streamBundle("anacafe", "ana")) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("bundle data");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamBundleMaps400ToRefused() throws Exception {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(400);
        when(response.body()).thenReturn(new ByteArrayInputStream("Bad request".getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()))).thenReturn(response);

        assertThatThrownBy(() -> client.streamBundle("anacafe", "ana"))
                .isInstanceOf(SandboxLinkPort.Unavailable.class)
                .satisfies(e -> assertThat(((SandboxLinkPort.Unavailable) e).reason()).isEqualTo(Reason.REFUSED));
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamBundleMaps401ToRefused() throws Exception {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(401);
        when(response.body()).thenReturn(new ByteArrayInputStream("Unauthorized".getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()))).thenReturn(response);

        assertThatThrownBy(() -> client.streamBundle("anacafe", "ana"))
                .isInstanceOf(SandboxLinkPort.Unavailable.class)
                .satisfies(e -> {
                    var ex = (SandboxLinkPort.Unavailable) e;
                    assertThat(ex.reason()).isEqualTo(Reason.REFUSED);
                    assertThat(ex.getMessage()).isEqualTo("Not signed by production");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamBundleMaps403ToRefused() throws Exception {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(403);
        when(response.body()).thenReturn(new ByteArrayInputStream("Forbidden".getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()))).thenReturn(response);

        assertThatThrownBy(() -> client.streamBundle("anacafe", "ana"))
                .isInstanceOf(SandboxLinkPort.Unavailable.class)
                .satisfies(e -> {
                    var ex = (SandboxLinkPort.Unavailable) e;
                    assertThat(ex.reason()).isEqualTo(Reason.REFUSED);
                    assertThat(ex.getMessage()).isEqualTo("You do not own this location in the sandbox");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamBundleMaps404ToRefused() throws Exception {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(404);
        when(response.body()).thenReturn(new ByteArrayInputStream("Not Found".getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()))).thenReturn(response);

        assertThatThrownBy(() -> client.streamBundle("anacafe", "ana"))
                .isInstanceOf(SandboxLinkPort.Unavailable.class)
                .satisfies(e -> {
                    var ex = (SandboxLinkPort.Unavailable) e;
                    assertThat(ex.reason()).isEqualTo(Reason.REFUSED);
                    assertThat(ex.getMessage()).isEqualTo("Location not found in the sandbox");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamBundleMaps409ToVersionMismatch() throws Exception {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(409);
        when(response.body()).thenReturn(new ByteArrayInputStream("Conflict".getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()))).thenReturn(response);

        assertThatThrownBy(() -> client.streamBundle("anacafe", "ana"))
                .isInstanceOf(SandboxLinkPort.Unavailable.class)
                .satisfies(e -> {
                    var ex = (SandboxLinkPort.Unavailable) e;
                    assertThat(ex.reason()).isEqualTo(Reason.OTHER_VERSION);
                    assertThat(ex.getMessage()).isEqualTo("The sandbox runs a different version of FastBite");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamBundleMaps422ToNoProducts() throws Exception {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(422);
        when(response.body()).thenReturn(new ByteArrayInputStream("Empty".getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()))).thenReturn(response);

        assertThatThrownBy(() -> client.streamBundle("anacafe", "ana"))
                .isInstanceOf(SandboxLinkPort.Unavailable.class)
                .satisfies(e -> {
                    var ex = (SandboxLinkPort.Unavailable) e;
                    assertThat(ex.reason()).isEqualTo(Reason.NOTHING_TO_PROMOTE);
                    assertThat(ex.getMessage()).isEqualTo("The sandbox location has no products to promote");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamBundleMaps500ToServerError() throws Exception {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn(new ByteArrayInputStream("Internal Error".getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream()))).thenReturn(response);

        assertThatThrownBy(() -> client.streamBundle("anacafe", "ana"))
                .isInstanceOf(SandboxLinkPort.Unavailable.class)
                .satisfies(e -> {
                    var ex = (SandboxLinkPort.Unavailable) e;
                    assertThat(ex.reason()).isEqualTo(Reason.UNREACHABLE);
                    assertThat(ex.getMessage()).isEqualTo("The sandbox answered 500");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamBundleHandlesIOException() throws Exception {
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream())))
                .thenThrow(new IOException("Connection reset"));

        assertThatThrownBy(() -> client.streamBundle("anacafe", "ana"))
                .isInstanceOf(SandboxLinkPort.Unavailable.class)
                .satisfies(e -> {
                    var ex = (SandboxLinkPort.Unavailable) e;
                    assertThat(ex.reason()).isEqualTo(Reason.UNREACHABLE);
                    assertThat(ex.getMessage()).contains("The sandbox could not be reached: Connection reset");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamBundleHandlesHttpTimeoutException() throws Exception {
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream())))
                .thenThrow(new HttpTimeoutException("request timed out"));

        assertThatThrownBy(() -> client.streamBundle("anacafe", "ana"))
                .isInstanceOf(SandboxLinkPort.Unavailable.class)
                .satisfies(e -> {
                    var ex = (SandboxLinkPort.Unavailable) e;
                    assertThat(ex.reason()).isEqualTo(Reason.TIMED_OUT);
                    assertThat(ex.getMessage()).contains("The sandbox request timed out waiting for headers");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamBundleHandlesInterruptedException() throws Exception {
        when(http.send(any(HttpRequest.class), eq(HttpResponse.BodyHandlers.ofInputStream())))
                .thenThrow(new InterruptedException());

        assertThatThrownBy(() -> client.streamBundle("anacafe", "ana"))
                .isInstanceOf(SandboxLinkPort.Unavailable.class)
                .satisfies(e -> {
                    var ex = (SandboxLinkPort.Unavailable) e;
                    assertThat(ex.reason()).isEqualTo(Reason.UNREACHABLE);
                    assertThat(ex.getMessage()).isEqualTo("Interrupted while calling the sandbox");
                });
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void provisionWhenNotLinkedThrowsUnavailable() {
        var location = new SandboxLinkPort.LinkedLocation("anacafe", "ana", "Ana García", "RESTAURANT");
        assertThatThrownBy(() -> unlinkedClient.provision(location))
                .isInstanceOf(SandboxLinkPort.Unavailable.class)
                .satisfies(e -> assertThat(((SandboxLinkPort.Unavailable) e).reason()).isEqualTo(Reason.UNREACHABLE));
    }

    @Test
    @SuppressWarnings("unchecked")
    void provisionSuccess() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn((HttpResponse) response);

        var location = new SandboxLinkPort.LinkedLocation("anacafe", "ana", "Ana García", "RESTAURANT");
        client.provision(location);
    }

    @Test
    @SuppressWarnings("unchecked")
    void provisionErrorStatusThrowsUnavailable() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(502);
        when(response.body()).thenReturn("Bad Gateway");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn((HttpResponse) response);

        var location = new SandboxLinkPort.LinkedLocation("anacafe", "ana", "Ana García", "RESTAURANT");
        assertThatThrownBy(() -> client.provision(location))
                .isInstanceOf(SandboxLinkPort.Unavailable.class)
                .satisfies(e -> {
                    var ex = (SandboxLinkPort.Unavailable) e;
                    assertThat(ex.reason()).isEqualTo(Reason.UNREACHABLE);
                    assertThat(ex.getMessage()).isEqualTo("The sandbox answered 502: Bad Gateway");
                });
    }

    @Nested
    class RealServerWatchdogProof {

        HttpServer server;
        HttpClient realHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

        @AfterEach
        void stopServer() {
            if (server != null) {
                server.stop(0);
            }
        }

        @Test
        void stalledStreamUnblocksReadWithTimedOutException() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/link/production-bundle", exchange -> {
                exchange.sendResponseHeaders(200, 0); // chunked
                exchange.getResponseBody().write(new byte[1024]);
                exchange.getResponseBody().flush();
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException ignored) {
                }
            });
            server.start();

            int port = server.getAddress().getPort();
            PlatformLink realLink = new PlatformLink(env, "http://127.0.0.1:" + port, "", SECRET, false);
            HttpSandboxLink realClient = new HttpSandboxLink(realLink, json, "1.2.0", realHttp, Duration.ofMillis(300));

            long start = System.currentTimeMillis();
            try (InputStream stream = realClient.streamBundle("anacafe", "ana", 10_000_000)) {
                assertThatThrownBy(() -> {
                    byte[] buf = new byte[512];
                    while (stream.read(buf) != -1) {
                    }
                }).isInstanceOf(BundleStreamException.class)
                        .satisfies(e -> assertThat(((BundleStreamException) e).reason()).isEqualTo(Reason.TIMED_OUT));
            }
            long elapsed = System.currentTimeMillis() - start;
            assertThat(elapsed).isLessThan(2500); // 300 ms deadline + well under 2 s
        }

        @Test
        void chunkedStreamExceedingMaxBytesThrowsTooLarge() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/link/production-bundle", exchange -> {
                exchange.sendResponseHeaders(200, 0); // chunked
                exchange.getResponseBody().write(new byte[4096]);
                exchange.getResponseBody().close();
            });
            server.start();

            int port = server.getAddress().getPort();
            PlatformLink realLink = new PlatformLink(env, "http://127.0.0.1:" + port, "", SECRET, false);
            HttpSandboxLink realClient = new HttpSandboxLink(realLink, json, "1.2.0", realHttp, Duration.ofSeconds(5));

            try (InputStream stream = realClient.streamBundle("anacafe", "ana", 1000)) {
                assertThatThrownBy(stream::readAllBytes)
                        .isInstanceOf(BundleStreamException.class)
                        .satisfies(e -> assertThat(((BundleStreamException) e).reason()).isEqualTo(Reason.TOO_LARGE));
            }
        }

        @Test
        void contentLengthExceedingMaxBytesRefusedBeforeReadingBody() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/link/production-bundle", exchange -> {
                byte[] body = new byte[5000];
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
            });
            server.start();

            int port = server.getAddress().getPort();
            PlatformLink realLink = new PlatformLink(env, "http://127.0.0.1:" + port, "", SECRET, false);
            HttpSandboxLink realClient = new HttpSandboxLink(realLink, json, "1.2.0", realHttp, Duration.ofSeconds(5));

            assertThatThrownBy(() -> realClient.streamBundle("anacafe", "ana", 1000))
                    .isInstanceOf(SandboxLinkPort.Unavailable.class)
                    .satisfies(e -> assertThat(((SandboxLinkPort.Unavailable) e).reason()).isEqualTo(Reason.TOO_LARGE));
        }
    }
}
