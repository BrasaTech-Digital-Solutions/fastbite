package es.brasatech.fastbite.service;

import es.brasatech.fastbite.application.platform.SandboxLinkPort.BundleStreamException;
import es.brasatech.fastbite.application.platform.SandboxLinkPort.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchdogInputStreamTest {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void normalReadAndCloseCancelsWatchdog() throws Exception {
        byte[] data = "hello world".getBytes();
        InputStream raw = new ByteArrayInputStream(data);
        InputStream watchdog = WatchdogInputStream.wrap(raw, scheduler, Duration.ofSeconds(10), 100);

        assertArrayEquals(data, watchdog.readAllBytes());
        watchdog.close();
    }

    @Test
    void stalledStreamIsClosedWhenWatchdogExpires() throws Exception {
        CountDownLatch closedLatch = new CountDownLatch(1);
        InputStream blockingStream = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    Thread.sleep(5000);
                    return 'a';
                } catch (InterruptedException e) {
                    throw new IOException("Interrupted", e);
                }
            }

            @Override
            public void close() {
                closedLatch.countDown();
            }
        };

        InputStream watchdog = WatchdogInputStream.wrap(blockingStream, scheduler, Duration.ofMillis(50), 1000);
        assertTrue(closedLatch.await(500, TimeUnit.MILLISECONDS), "Watchdog should close stalled stream");
    }

    @Test
    void sizeExceededSingleByteReadThrowsTooLarge() {
        byte[] data = new byte[]{1, 2, 3, 4, 5};
        InputStream watchdog = WatchdogInputStream.wrap(new ByteArrayInputStream(data), scheduler, Duration.ofSeconds(10), 3);

        assertThatThrownBy(() -> {
            for (int i = 0; i < 5; i++) {
                watchdog.read();
            }
        }).isInstanceOf(BundleStreamException.class)
                .satisfies(e -> assertThat(((BundleStreamException) e).reason()).isEqualTo(Reason.TOO_LARGE));
    }

    @Test
    void sizeExceededChunkReadThrowsTooLarge() {
        byte[] data = new byte[1024];
        InputStream watchdog = WatchdogInputStream.wrap(new ByteArrayInputStream(data), scheduler, Duration.ofSeconds(10), 500);

        byte[] buf = new byte[256];
        assertThatThrownBy(() -> {
            while (watchdog.read(buf) != -1) {
            }
        }).isInstanceOf(BundleStreamException.class)
                .satisfies(e -> assertThat(((BundleStreamException) e).reason()).isEqualTo(Reason.TOO_LARGE));
    }

    @Test
    void exactlyMaxBytesDoesNotThrow() throws Exception {
        byte[] data = new byte[]{1, 2, 3};
        InputStream watchdog = WatchdogInputStream.wrap(new ByteArrayInputStream(data), scheduler, Duration.ofSeconds(10), 3);

        byte[] result = watchdog.readAllBytes();
        assertArrayEquals(data, result);
    }
}
