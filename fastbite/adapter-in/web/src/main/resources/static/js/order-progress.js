// The guest's order on the confirmation page, live: the kitchen's progress shows as the tracker's data-stage
// (1 received, 2 being prepared, 3 ready, 4 done); the stylesheet draws the bar and the stages from it.
const FINISHED_STATUSES = ['COMPLETE', 'CANCELLED'];
const STAGE_BY_STATUS = { CREATED: 1, ACCEPTED: 1, PROCESSING: 2, DONE: 3, DELIVERED: 3, COMPLETE: 4 };

function getTenantPrefix() {
    const segments = window.location.pathname.split('/');
    if (segments.length > 1) {
        const firstSegment = segments[1];
        const reserved = ["signup", "login", "css", "js", "images", "webjars", "stripe", "error", "favicon.ico", "actuator", "api", "counter", "backoffice", "dashboard", "logout", "menu", "select-payment", "order-confirmation", ".well-known", "appspecific"];
        if (firstSegment && !reserved.includes(firstSegment)) {
            return '/' + firstSegment;
        }
    }
    return '';
}

function updateProgressTracker(status) {
    const tracker = document.getElementById('order-progress-card');
    const stage = STAGE_BY_STATUS[status];
    if (!tracker || !stage) return;
    tracker.dataset.stage = String(stage);
    tracker.querySelector('[role="progressbar"]')?.setAttribute('aria-valuenow', String(stage));
}

function startOrderProgressPolling() {
    let liveUpdates = null;
    let fallbackTimer = null;
    const orderStatusUrl = getTenantPrefix() + '/api/order-status';
    const sseUrl = getTenantPrefix() + '/api/order-status/live';

    const pollOnce = () => {
        fetch(orderStatusUrl)
            .then(res => res.ok ? res.json() : null)
            .then(order => {
                if (!order) return;
                updateProgressTracker(order.status);
                if (FINISHED_STATUSES.includes(order.status) && fallbackTimer) {
                    clearInterval(fallbackTimer);
                }
            })
            .catch(err => console.error('Order status poll error:', err));
    };

    pollOnce();

    liveUpdates = connectLiveUpdates(sseUrl, {
        poll: pollOnce,
        intervalMs: 5000,
        onUpdate: function (event) {
            try {
                const data = JSON.parse(event.data);
                if (data && data.status) {
                    updateProgressTracker(data.status);
                    if (FINISHED_STATUSES.includes(data.status) && liveUpdates) {
                        liveUpdates.close();
                    }
                }
            } catch (e) {
                console.error('Failed to parse order-update event', e);
            }
        }
    });
}

document.addEventListener('DOMContentLoaded', startOrderProgressPolling);
