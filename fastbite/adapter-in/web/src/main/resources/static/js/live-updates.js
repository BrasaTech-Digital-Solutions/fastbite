/**
 * Live updates client helper for SSE with watchdog and polling fallback.
 *
 * If EventSource is unsupported, fails to connect repeatedly, or if buffering
 * prevents the initial 'connected' handshake event from arriving within 10 seconds,
 * the stream is closed and polling is used for the rest of the page's life.
 *
 * @param {string} url - The SSE endpoint URL
 * @param {Object} options
 * @param {Function} [options.onUpdate] - Callback when an update event is received
 * @param {Function} [options.poll] - Callback to poll for data when falling back
 * @param {number} [options.intervalMs=5000] - Polling interval in milliseconds
 * @param {string} [options.eventName='order-update'] - SSE event name to listen for
 * @returns {{ close: Function }}
 */
function connectLiveUpdates(url, options) {
    const opts = options || {};
    const onUpdate = opts.onUpdate || function() {};
    const poll = opts.poll || function() {};
    const intervalMs = opts.intervalMs || 5000;
    const eventName = opts.eventName || 'order-update';

    let sseSource = null;
    let pollInterval = null;
    let watchdogTimer = null;
    let reconnectAttempts = 0;
    const MAX_RECONNECT_ATTEMPTS = 5;
    let closed = false;
    let permanentlyPolling = false;

    function startPolling() {
        permanentlyPolling = true;
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
