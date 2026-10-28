package es.brasatech.fastbite.service;

import es.brasatech.fastbite.application.platform.SandboxLinkPort.BundleStreamException;
import es.brasatech.fastbite.application.platform.SandboxLinkPort.Reason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps an InputStream with a scheduled watchdog deadline and a byte limit.
 * If reading exceeds maxBytes, throws BundleStreamException(TOO_LARGE).
 * If the deadline expires before close(), closes the stream and throws BundleStreamException(TIMED_OUT).
 */
class WatchdogInputStream extends FilterInputStream {

    private static final Logger log = LoggerFactory.getLogger(WatchdogInputStream.class);

    private final ScheduledFuture<?> timeoutTask;
    private final AtomicBoolean timedOut;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Duration timeout;
    private final long maxBytes;
    private long bytesRead = 0;

    public static InputStream wrap(InputStream in, ScheduledExecutorService scheduler, Duration timeout, long maxBytes) {
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            timedOut.set(true);
            log.warn("Sandbox bundle stream exceeded {} ms timeout; closing stream", timeout.toMillis());
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        return new WatchdogInputStream(in, timeoutTask, timedOut, timeout, maxBytes);
    }

    public static InputStream wrap(InputStream in, ScheduledExecutorService scheduler, Duration timeout) {
        return wrap(in, scheduler, timeout, Long.MAX_VALUE);
    }

    WatchdogInputStream(InputStream in, ScheduledFuture<?> timeoutTask, AtomicBoolean timedOut, Duration timeout, long maxBytes) {
        super(in);
        this.timeoutTask = timeoutTask;
        this.timedOut = timedOut;
        this.timeout = timeout;
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        try {
            int b = super.read();
            if (b != -1) {
                bytesRead++;
                if (bytesRead > maxBytes) {
                    throw new BundleStreamException(Reason.TOO_LARGE,
                            "Sandbox bundle exceeded size limit of " + maxBytes + " bytes");
                }
            }
            return b;
        } catch (IOException e) {
            throw checkTimeout(e);
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        try {
            int n = super.read(b, off, len);
            if (n != -1) {
                bytesRead += n;
                if (bytesRead > maxBytes) {
                    throw new BundleStreamException(Reason.TOO_LARGE,
                            "Sandbox bundle exceeded size limit of " + maxBytes + " bytes");
                }
            }
            return n;
        } catch (IOException e) {
            throw checkTimeout(e);
        }
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 0) return 0;
        try {
            long skipped = super.skip(n);
            if (skipped > 0) {
                bytesRead += skipped;
                if (bytesRead > maxBytes) {
                    throw new BundleStreamException(Reason.TOO_LARGE,
                            "Sandbox bundle exceeded size limit of " + maxBytes + " bytes");
                }
            }
            return skipped;
        } catch (IOException e) {
            throw checkTimeout(e);
        }
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            timeoutTask.cancel(false);
            super.close();
        }
    }

    private IOException checkTimeout(IOException e) {
        if (e instanceof BundleStreamException) {
            return e;
        }
        if (timedOut.get()) {
            return new BundleStreamException(Reason.TIMED_OUT,
                    "Sandbox bundle stream timed out after " + timeout.toMillis() + " ms", e);
        }
        return e;
    }
}
