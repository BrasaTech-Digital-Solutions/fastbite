/**
 * Live updates client helper for SSE with watchdog and polling fallback.
 *
 * If EventSource is unsupported, fails to connect repeatedly, or if buffering
 * prevents the initial 'connected' handshake event from arriving within 10 seconds,
 * the stream is closed and polling is used for the rest of the page's life.
 *
 * While the stream is healthy, a safety poll timer runs (default 15s) so updates
 * handled by another backend instance in a multi-instance deployment are picked up.
 *
 * @param {string} url - The SSE endpoint URL
 * @param {Object} options
 * @param {Function} [options.onUpdate] - Callback when an update event is received
 * @param {Function} [options.poll] - Callback to poll for data when falling back or safety polling
 * @param {number} [options.intervalMs=5000] - Polling interval in milliseconds for permanent fallback
 * @param {number} [options.safetyIntervalMs=15000] - Safety polling interval in milliseconds while stream is healthy
 * @param {string} [options.eventName='order-update'] - SSE event name to listen for
 * @returns {{ close: Function }}
 */
function connectLiveUpdates(url, options) {
    const opts = options || {};
    const onUpdate = opts.onUpdate || function() {};
    const poll = opts.poll || function() {};
    const intervalMs = opts.intervalMs || 5000;
    const safetyIntervalMs = opts.safetyIntervalMs || 15000;
    const eventName = opts.eventName || 'order-update';

    let sseSource = null;
    let pollInterval = null;
    let safetyPollInterval = null;
    let watchdogTimer = null;
    let reconnectAttempts = 0;
    let connectedCount = 0;
    const MAX_RECONNECT_ATTEMPTS = 5;
    let closed = false;
    let permanentlyPolling = false;

    function stopSafetyPoll() {
        if (safetyPollInterval) {
            clearInterval(safetyPollInterval);
            safetyPollInterval = null;
        }
    }

    function startSafetyPoll() {
        if (!safetyPollInterval && !closed && !permanentlyPolling) {
            safetyPollInterval = setInterval(function() {
                if (!closed && !permanentlyPolling) poll();
            }, safetyIntervalMs);
        }
    }

    function startPolling() {
        permanentlyPolling = true;
        stopSafetyPoll();
        if (sseSource) {
            try { sseSource.close(); } catch (e) {}
            sseSource = null;
        }
        if (watchdogTimer) {
            clearTimeout(watchdogTimer);
            watchdogTimer = null;
        }
        if (!pollInterval && !closed) {
            pollInterval = setInterval(function() {
                if (!closed) poll();
            }, intervalMs);
        }
    }

    function connect() {
        if (closed || permanentlyPolling) return;

        if (!window.EventSource) {
            startPolling();
            return;
        }

        try {
            sseSource = new EventSource(url);

            // Watchdog: If no 'connected' handshake event arrives within 10s (e.g. proxy buffering),
            // assume the stream is buffered or broken, close it and fall back to polling permanently.
            watchdogTimer = setTimeout(function() {
                if (sseSource) {
                    console.warn('SSE handshake watchdog timed out (stream likely buffered); falling back to polling');
                    startPolling();
                }
            }, 10000);

            sseSource.addEventListener('connected', function() {
                if (watchdogTimer) {
                    clearTimeout(watchdogTimer);
                    watchdogTimer = null;
                }
                reconnectAttempts = 0;
                connectedCount++;
                if (connectedCount > 1) {
                    poll();
                }
                startSafetyPoll();
            });

            sseSource.addEventListener(eventName, function(event) {
                if (watchdogTimer) {
                    clearTimeout(watchdogTimer);
                    watchdogTimer = null;
                }
                reconnectAttempts = 0;
                onUpdate(event);
            });

            sseSource.onerror = function() {
                if (closed || permanentlyPolling) return;

                if (sseSource && sseSource.readyState === EventSource.CLOSED) {
                    if (watchdogTimer) {
                        clearTimeout(watchdogTimer);
                        watchdogTimer = null;
                    }
                    try { sseSource.close(); } catch (e) {}
                    sseSource = null;

                    reconnectAttempts++;
                    if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
                        console.warn('SSE exceeded maximum reconnection attempts; falling back to polling');
                        startPolling();
                    } else {
                        var delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 15000);
                        setTimeout(connect, delay);
                    }
                }
            };
        } catch (e) {
            console.warn('SSE connection failed; falling back to polling', e);
            startPolling();
        }
    }

    connect();

    return {
        close: function() {
            closed = true;
            stopSafetyPoll();
            if (watchdogTimer) {
                clearTimeout(watchdogTimer);
                watchdogTimer = null;
            }
            if (sseSource) {
                try { sseSource.close(); } catch (e) {}
                sseSource = null;
            }
            if (pollInterval) {
                clearInterval(pollInterval);
                pollInterval = null;
            }
        }
    };
}
