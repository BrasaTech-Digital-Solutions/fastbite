// Demo orders data
let orders = [];

function getTenantPrefix() {
    const path = window.location.pathname;
    const segments = path.split('/');
    if (segments.length > 1) {
        const firstSegment = segments[1];
        const reserved = ["signup", "login", "css", "js", "images", "webjars", "stripe", "error", "favicon.ico", "actuator", "api", "counter", "backoffice", "dashboard", "logout", "menu", "select-payment", "order-confirmation", ".well-known", "appspecific"];
        if (firstSegment && !reserved.includes(firstSegment)) {
            return '/' + firstSegment;
        }
    }
    return '';
}

const getOrderListUrl = () => getTenantPrefix() + '/api/order';
const moveToNextStatusUrl = (id) => getTenantPrefix() + '/api/order/' + id + '/next';
const moveToPreviousStatusUrl = (id) => getTenantPrefix() + '/api/order/' + id + '/previous';
const batchNextStatusUrl = () => getTenantPrefix() + '/api/order/batch/next';
const batchPreviousStatusUrl = () => getTenantPrefix() + '/api/order/batch/previous';
const cancelOrderUrl = (id) => getTenantPrefix() + '/api/order/' + id + '/cancel';
const changeOrderStatusUrl = (id) => getTenantPrefix() + '/api/order/' + id + '/status';
let currentCancelOrderId = null;
let currentStatusChangeOrderId = null;
let i18n = {};

// Helper for formatting
function format(str, ...args) {
    if (!str) return '';
    return str.replace(/{(\d+)}/g, function (match, number) {
        return typeof args[number] != 'undefined' ? args[number] : match;
    });
}

// Initialize dashboards
function initializeDashboards() {
    loadI18n();
    renderAllDashboards();
    startRealtimeUpdates();
}

function loadI18n() {
    const el = document.getElementById('i18n-dashboard');
    if (!el) return;
    for (const key in el.dataset) {
        i18n[key] = el.dataset[key];
    }
}

// Render all dashboards
function renderAllDashboards() {
    renderCashierDashboard();
    renderQueueDashboard();
    renderCookDashboard();
    renderWaiterDashboard();
    renderManagerDashboard();
}

// Render Cashier Dashboard (CREATED)
function renderCashierDashboard() {
    const dashboard = document.getElementById('cashierDashboard');
    if (!dashboard) return;
    const createdOrders = orders.filter(o => o.status === 'CREATED');
    document.getElementById('cashierCount').textContent = createdOrders.length;

    if (createdOrders.length === 0) {
        dashboard.innerHTML = `<div class="text-center text-muted py-5"><i class="fas fa-inbox fa-3x mb-3"></i><p>${i18n.emptyCreated}</p></div>`;
        return;
    }

    dashboard.innerHTML = createdOrders.map(order => createOrderCard(order, 'cashier')).join('');
}

// Render Queue Dashboard (ACCEPTED)
function renderQueueDashboard() {
    const dashboard = document.getElementById('queueDashboard');
    if (!dashboard) return;
    const acceptedOrders = orders.filter(o => o.status === 'ACCEPTED');
    document.getElementById('queueCount').textContent = acceptedOrders.length;

    if (acceptedOrders.length === 0) {
        dashboard.innerHTML = `<div class="text-center text-muted py-5"><i class="fas fa-inbox fa-3x mb-3"></i><p>${i18n.emptyConfirmed}</p></div>`;
        return;
    }

    dashboard.innerHTML = acceptedOrders.map(order => createOrderCard(order, 'queue')).join('');
}

// Render Cook Dashboard (PROCESSING)
function renderCookDashboard() {
    const dashboard = document.getElementById('cookDashboard');
    if (!dashboard) return;
    const processingOrders = orders.filter(o => o.status === 'PROCESSING');
    document.getElementById('cookCount').textContent = processingOrders.length;

    if (processingOrders.length === 0) {
        dashboard.innerHTML = `<div class="text-center text-muted py-5"><i class="fas fa-inbox fa-3x mb-3"></i><p>${i18n.emptyPreparing}</p></div>`;
        return;
    }

    dashboard.innerHTML = processingOrders.map(order => createOrderCard(order, 'cook')).join('');
}

// Render Waiter Dashboard (DONE)
function renderWaiterDashboard() {
    const dashboard = document.getElementById('waiterDashboard');
    if (!dashboard) return;
    const doneOrders = orders.filter(o => o.status === 'DONE');
    document.getElementById('waiterCount').textContent = doneOrders.length;

    if (doneOrders.length === 0) {
        dashboard.innerHTML = `<div class="text-center text-muted py-5"><i class="fas fa-inbox fa-3x mb-3"></i><p>${i18n.emptyReady}</p></div>`;
        return;
    }

    dashboard.innerHTML = doneOrders.map(order => createOrderCard(order, 'waiter')).join('');
}

// Render Manager Dashboard
function renderManagerDashboard() {
    // All Orders
    const allOrdersTable = document.getElementById('allOrdersTable');
    if (!allOrdersTable) return;
    const activeOrders = orders.filter(o => o.status !== 'COMPLETE');

    if (activeOrders.length === 0) {
        allOrdersTable.innerHTML = `<tr><td colspan="6" class="text-center text-muted py-4"><i class="fas fa-inbox fa-2x mb-2"></i><p>${i18n.emptyAll}</p></td></tr>`;
    } else {
        allOrdersTable.innerHTML = activeOrders.map(order => createManagerRow(order)).join('');
    }

    // Cancelled Orders
    const cancelledOrdersTable = document.getElementById('cancelledOrdersTable');
    const cancelledOrders = orders.filter(o => o.status === 'CANCELLED');

    if (cancelledOrders.length === 0) {
        cancelledOrdersTable.innerHTML = `<tr><td colspan="5" class="text-center text-muted py-4"><i class="fas fa-inbox fa-2x mb-2"></i><p>${i18n.emptyCancelled}</p></td></tr>`;
    } else {
        cancelledOrdersTable.innerHTML = cancelledOrders.map(order => createCancelledRow(order)).join('');
    }

    // Completed Orders
    const completedOrdersTable = document.getElementById('completedOrdersTable');
    const completedOrders = orders.filter(o => o.status === 'COMPLETE');

    if (completedOrders.length === 0) {
        completedOrdersTable.innerHTML = `<tr><td colspan="5" class="text-center text-muted py-4"><i class="fas fa-inbox fa-2x mb-2"></i><p>${i18n.emptyCompleted}</p></td></tr>`;
    } else {
        completedOrdersTable.innerHTML = completedOrders.map(order => createCompletedRow(order)).join('');
    }
}

function getKdsTimerBadgeClass(order) {
    const configEl = document.getElementById('kds-timer-config');
    const yellow = configEl ? parseInt(configEl.dataset.yellowMinutes || '0', 10) : 0;
    const red = configEl ? parseInt(configEl.dataset.redMinutes || '0', 10) : 0;

    // When threshold is 0, timer styling is deactivated and defaults to Blue (bg-primary)
    if (!yellow || yellow <= 0) {
        return 'bg-primary';
    }

    const date = new Date(order.createdAt || order.updatedAt);
    const elapsedMinutes = Math.floor((new Date() - date) / 60000);

    if (red > 0 && elapsedMinutes >= red) {
        return 'bg-danger text-white';
    }
    if (yellow > 0 && elapsedMinutes >= yellow) {
        return 'bg-warning text-dark';
    }
    return 'bg-primary';
}

// Customer names are typed in by guests: never put them into the page as markup
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text ?? '';
    return div.innerHTML;
}

// Create order card
function createOrderCard(order, role) {
    const timeAgo = getTimeAgo(order.updatedAt);
    const items = order.items.map(item =>
        `<div class="small">${item.quantity}x ${item.name} ${item.customizations.length > 0 ? '<span class="badge bg-secondary">' + item.customizations.map(cust => cust.name).join(', ') + '</span>' : ''}</div>`
    ).join('');

    const actions = getActionsForRole(order, role);
    const timerBadgeClass = getKdsTimerBadgeClass(order);
    const takeaway = order.serviceType === 'TAKEAWAY'
        ? `<div class="small"><span class="badge bg-dark"><i class="fas fa-shopping-bag me-1"></i>${i18n.labelTakeaway}</span> ${escapeHtml(order.customerName)}</div>`
        : '';

    return `
        <div class="cart-item mb-2">
            <div class="d-flex justify-content-between align-items-start mb-2">
                <div>
                    <h6 class="mb-1 fw-bold">${i18n.labelOrder}${order.orderNumber}</h6>
                    ${takeaway}
                    <small class="text-muted">${timeAgo}</small>
                </div>
                <span class="badge ${timerBadgeClass}">${i18n['status' + (order.status.charAt(0).toUpperCase() + order.status.slice(1).toLowerCase())] || order.status}</span>
            </div>
            <div class="mb-2">${items}</div>
            <div class="d-flex justify-content-between align-items-center mb-2">
                <strong>${i18n.labelTotal}</strong>
                <strong class="text-danger">${order.total.toFixed(2)}</strong>
            </div>
            <div class="d-flex gap-2">${actions}</div>
        </div>
    `;
}

// Get actions for role
function getActionsForRole(order, role) {
    const cancelBtn = `<button class="btn btn-outline-danger btn-sm flex-grow-1" onclick="showCancelModal('${order.id}')"><i class="fas fa-times"></i> ${i18n.actionCancel}</button>`;
    const prevBtn = `<button class="btn btn-outline-secondary btn-sm flex-grow-1" onclick="moveToPreviousStatus('${order.id}')"><i class="fas fa-undo"></i> ${i18n.actionPrevious}</button>`;

    switch (role) {
        case 'cashier':
            return `
                <button class="btn btn-primary btn-sm flex-grow-1" onclick="moveToNextStatus('${order.id}')">
                    <i class="fas fa-check"></i> ${i18n.actionAccept}
                </button>
                ${cancelBtn}
            `;
        case 'queue':
            return `
                <div class="d-flex w-100 gap-2 mb-2">
                    <button class="btn btn-info btn-sm flex-grow-1" onclick="moveToNextStatus('${order.id}')">
                        <i class="fas fa-fire"></i> ${i18n.actionCook}
                    </button>
                </div>
                <div class="d-flex w-100 gap-2">
                    ${prevBtn}
                    ${cancelBtn}
                </div>
            `;
        case 'cook':
            return `
                <div class="d-flex w-100 gap-2 mb-2">
                    <button class="btn btn-success btn-sm flex-grow-1" onclick="moveToNextStatus('${order.id}')">
                        <i class="fas fa-check"></i> ${i18n.actionDone}
                    </button>
                </div>
                <div class="d-flex w-100 gap-2">
                    ${prevBtn}
                    ${cancelBtn}
                </div>
            `;
        case 'waiter':
            return `
                <div class="d-flex w-100 gap-2 mb-2">
                    <button class="btn btn-success btn-sm flex-grow-1" onclick="moveToNextStatus('${order.id}')">
                        <i class="fas fa-check-double"></i> ${i18n.actionDelivered}
                    </button>
                </div>
                <div class="d-flex w-100 gap-2">
                    ${prevBtn}
                    ${cancelBtn}
                </div>
            `;
        default:
            return '';
    }
}

// Create manager table row
function createManagerRow(order) {
    const items = order.items.map(item => `${item.quantity}x ${item.name}`).join(', ');
    const timeAgo = getTimeAgo(order.createdAt);

    // Create cancel button only if order is not already cancelled
    const cancelButton = order.status !== 'CANCELLED'
        ? `<button class="btn btn-sm btn-outline-danger" onclick="showCancelModal('${order.id}')">
               <i class="fas fa-ban"></i> ${i18n.actionCancel}
           </button>`
        : '';

    return `
        <tr>
            <td><strong>${order.orderNumber}</strong></td>
            <td>${items}</td>
            <td>${order.total.toFixed(2)}</td>
            <td><span class="badge ${getStatusBadgeClass(order.status)}">${order.status}</span></td>
            <td>${timeAgo}</td>
            <td>
                <button class="btn btn-sm btn-outline-primary" onclick="showStatusChangeModal('${order.id}')">
                    <i class="fas fa-edit"></i> ${i18n.actionChange}
                </button>
                ${cancelButton}
            </td>
        </tr>
    `;
}

// Create cancelled row
function createCancelledRow(order) {
    const items = order.items.map(item => `${item.quantity}x ${item.name}`).join(', ');
    const cancelledAt = order.updatedAt.toLocaleString();

    return `
        <tr>
            <td><strong>${order.orderNumber}</strong></td>
            <td>${items}</td>
            <td>${order.total.toFixed(2)}</td>
            <td>${cancelledAt}</td>
            <td>${order.cancelReason || 'N/A'}</td>
        </tr>
    `;
}

// Create completed row
function createCompletedRow(order) {
    const items = order.items.map(item => `${item.quantity}x ${item.name}`).join(', ');
    const completedAt = order.updatedAt.toLocaleString();
    const duration = Math.floor((new Date(order.updatedAt) - new Date(order.createdAt)) / 60000) + ' min';

    return `
        <tr>
            <td><strong>${order.orderNumber}</strong></td>
            <td>${items}</td>
            <td>${order.total.toFixed(2)}</td>
            <td>${completedAt}</td>
            <td>${duration}</td>
        </tr>
    `;
}

// Get status badge class
function getStatusBadgeClass(status) {
    const classes = {
        'CREATED': 'bg-primary',
        'ACCEPTED': 'bg-warning text-dark',
        'PROCESSING': 'bg-info',
        'DONE': 'bg-success',
        'DELIVERED': 'bg-success',
        'COMPLETE': 'bg-secondary',
        'CANCELLED': 'bg-danger'
    };
    return classes[status] || 'bg-secondary';
}

function getNextStatus(status) {
    const classes = {
        'CREATED': 'ACCEPTED',
        'ACCEPTED': 'PROCESSING',
        'PROCESSING': 'DONE',
        'DONE': 'DELIVERED',
        'DELIVERED': 'COMPLETE'
    };
    return classes[status];
}

// Get time ago
function getTimeAgo(dateString) {
    // Parse the datetime string
    const date = new Date(dateString);

    // Validate date
    if (isNaN(date.getTime())) {
        console.error('Invalid date:', dateString);
        return i18n.timeJustNow;
    }

    const now = new Date();
    const seconds = Math.floor((now - date) / 1000);

    // Handle future dates
    if (seconds < 0) return i18n.timeJustNow;

    // Less than a minute
    if (seconds < 60) return `${seconds}s ago`;

    // Less than an hour
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes} ${i18n.timeMinAgo}`;

    // Less than a day
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours} ${i18n.timeHoursAgo}`;

    // Days
    const days = Math.floor(hours / 24);
    if (days < 7) return `${days} ${i18n.timeDaysAgo}`;

    // Weeks
    const weeks = Math.floor(days / 7);
    if (weeks < 4) return `${weeks}w ago`;

    // Months
    const months = Math.floor(days / 30);
    return `${months}mo ago`;
}

// Move to next status
function moveToNextStatus(orderId) {
    const order = orders.find(o => o.id === orderId);
    if (!order) return;

    callAPI(moveToNextStatusUrl(order.id)).then(response => updateOrders()).catch(error => console.error('Error:', error));
    const nextStatus = getNextStatus(order.status)
    showToast(format(i18n.toastUpdated, nextStatus));
}

// Move to previous status
function moveToPreviousStatus(orderId) {
    const order = orders.find(o => o.id === orderId);
    if (!order) return;

    callAPI(moveToPreviousStatusUrl(order.id)).then(response => updateOrders()).catch(error => console.error('Error:', error));
    showToast(`Order #${order.orderNumber} moved to previous status`);
}

function moveAllToNext(role) {
    const statusMap = {
        'cashier': 'CREATED',
        'queue': 'ACCEPTED',
        'cook': 'PROCESSING',
        'waiter': 'DONE'
    };
    const targetStatus = statusMap[role];
    const orderIds = orders.filter(o => o.status === targetStatus).map(o => o.id);
    
    if (orderIds.length === 0) return;

    fetch(batchNextStatusUrl(), {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': document.querySelector('meta[name="_csrf"]')?.content
        },
        body: JSON.stringify(orderIds)
    }).then(response => {
        updateOrders();
        showToast(`All ${role} orders moved to next status`);
    }).catch(error => console.error('Error:', error));
}

function moveAllToPrevious(role) {
    const statusMap = {
        'queue': 'ACCEPTED',
        'cook': 'PROCESSING',
        'waiter': 'DONE'
    };
    const targetStatus = statusMap[role];
    const orderIds = orders.filter(o => o.status === targetStatus).map(o => o.id);
    
    if (orderIds.length === 0) return;

    fetch(batchPreviousStatusUrl(), {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': document.querySelector('meta[name="_csrf"]')?.content
        },
        body: JSON.stringify(orderIds)
    }).then(response => {
        updateOrders();
        showToast(`All ${role} orders moved to previous status`);
    }).catch(error => console.error('Error:', error));
}

// Show cancel modal
function showCancelModal(orderId) {
    currentCancelOrderId = orderId;
    document.getElementById('cancelOrderNumber').textContent = orders.find(o => o.id === orderId)?.orderNumber ?? '';
    document.getElementById('cancelReason').value = '';
    const modal = new bootstrap.Modal(document.getElementById('cancelModal'));
    modal.show();
}

// Confirm cancel
function confirmCancel() {
    const order = orders.find(o => o.id === currentCancelOrderId);

    if (order) {
        const cancelReason = document.getElementById('cancelReason').value;
        fetch(cancelOrderUrl(order.id), {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-CSRF-TOKEN': document.querySelector('meta[name="_csrf"]')?.content
            },
            body: JSON.stringify({ value: cancelReason })
        }).then(response => {
            updateOrders();
            showToast(`Order #${order.orderNumber} cancelled`);
            bootstrap.Modal.getInstance(document.getElementById('cancelModal')).hide();
        })
            .catch(error => console.error('Error:', error));
    }
}

// Show status change modal
function showStatusChangeModal(orderId) {
    currentStatusChangeOrderId = orderId;
    const order = orders.find(o => o.id === orderId);
    document.getElementById('statusOrderNumber').textContent = order.orderNumber;
    document.getElementById('newStatus').value = order.status;
    document.getElementById('statusCancelReasonDiv').style.display = order.status === 'CANCELLED' ? 'block' : 'none';
    document.getElementById('statusCancelReason').value = order.cancelReason || '';
    const modal = new bootstrap.Modal(document.getElementById('statusChangeModal'));
    modal.show();
}

// Confirm status change
function confirmStatusChange() {
    const order = orders.find(o => o.id === currentStatusChangeOrderId);
    if (order) {
        const newStatus = document.getElementById('newStatus').value;
        const reason = document.getElementById('statusCancelReason').value;

        if (newStatus === 'CANCELLED') {
            fetch(cancelOrderUrl(order.id), {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'X-CSRF-TOKEN': document.querySelector('meta[name="_csrf"]')?.content
                },
                body: JSON.stringify({ value: reason })
            }).then(response => {
                updateOrders();
                showToast(`Order #${order.orderNumber} cancelled`);
                bootstrap.Modal.getInstance(document.getElementById('statusChangeModal')).hide();
            }).catch(error => console.error('Error:', error));
        } else {
            fetch(changeOrderStatusUrl(order.id), {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'X-CSRF-TOKEN': document.querySelector('meta[name="_csrf"]')?.content
                },
                body: JSON.stringify({ value: newStatus })
            }).then(response => {
                updateOrders();
                showToast(format(i18n.toastUpdated, newStatus));
                bootstrap.Modal.getInstance(document.getElementById('statusChangeModal')).hide();
            }).catch(error => console.error('Error:', error));
        }
    }

}

// Show toast
function showToast(message) {
    // Simple alert for demo, you can implement a proper toast
    console.log(message);
}

// Start realtime updates via Server-Sent Events (SSE) with fallback polling
function startRealtimeUpdates() {
    let sseSource = null;
    let fallbackInterval = null;

    // Periodic time-ago ticker
    setInterval(() => {
        renderAllDashboards();
    }, 15000);

    const sseUrl = getTenantPrefix() + '/api/orders/live';

    if (window.EventSource) {
        try {
            sseSource = new EventSource(sseUrl);
            sseSource.addEventListener('order-update', (event) => {
                try {
                    const data = JSON.parse(event.data);
                    if (data && data.orderNumber) {
                        showToast(`Order #${data.orderNumber} status changed: ${data.status}`);
                    }
                    if (window.kitchenChime && (!data || !data.status || data.status === 'CREATED' || data.status === 'ACCEPTED')) {
                        window.kitchenChime.play();
                    }
                } catch (e) {
                    console.debug('SSE event parse error', e);
                }
                updateOrders();
            });

            sseSource.onerror = () => {
                if (!fallbackInterval) {
                    fallbackInterval = setInterval(updateOrders, 5000);
                }
            };
            return;
        } catch (e) {
            console.warn('Dashboard SSE failed, falling back to polling', e);
        }
    }

    fallbackInterval = setInterval(updateOrders, 5000);
}

// Add demo order (for testing)
function addDemoOrder() {
    const newOrder = {
        id: 'FB' + (1000 + orders.length + 1),
        items: [
            { name: 'Classic Cheeseburger', quantity: 1, customizations: ['No Onions'] }
        ],
        total: 8.99,
        status: 'CREATED',
        createdAt: new Date(),
        updatedAt: new Date()
    };
    orders.push(newOrder);
    renderAllDashboards();
    showToast(`New order #${newOrder.orderNumber} received!`);
}

// Add demo order (for testing)
function updateOrders() {
    fetch(getOrderListUrl(), {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': document.querySelector('meta[name="_csrf"]')?.content
        }
    }).then(response => {
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        return response.json();
    }).then(data => {
        if (Array.isArray(data)) {
            orders = data;
            renderAllDashboards();
        }
    }).catch(error => console.error('Error fetching orders:', error));
}

function callAPI(url) {
    return fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': document.querySelector('meta[name="_csrf"]')?.content
        }
    })
        .then(response => response);
}

// Initialize on page load
document.addEventListener('DOMContentLoaded', function () {
    initializeDashboards();

    // Event listener for status change modal
    const newStatusSelect = document.getElementById('newStatus');
    if (newStatusSelect) {
        newStatusSelect.addEventListener('change', function () {
            const reasonDiv = document.getElementById('statusCancelReasonDiv');
            if (reasonDiv) {
                reasonDiv.style.display = this.value === 'CANCELLED' ? 'block' : 'none';
            }
        });
    }

    // Initial orders load
    updateOrders();
});