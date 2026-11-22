// Kitchen order management board (native dialogs, ARIA tabs, delegated actions)
let orders = [];
const inFlightActions = new Set();

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
let currencyFormatter = null;

function pageLang() {
    return document.documentElement.lang || 'es';
}

function formatMoney(amount) {
    if (!currencyFormatter) {
        currencyFormatter = new Intl.NumberFormat(pageLang(), { style: 'currency', currency: 'EUR' });
    }
    return currencyFormatter.format(Number(amount || 0));
}

function formatDateTime(dateString) {
    const date = new Date(dateString);
    if (isNaN(date.getTime())) {
        return i18n.labelNa || '';
    }
    return date.toLocaleString(pageLang());
}

function format(str, ...args) {
    if (!str) return '';
    return str.replace(/{(\d+)}/g, function (match, number) {
        return typeof args[number] !== 'undefined' ? args[number] : match;
    });
}

// Escape text for both HTML element content and quoted HTML attribute values
function escapeHtml(text) {
    return String(text ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function iconSvg(symbolId, extraClass) {
    const cls = extraClass ? `icon ${extraClass}` : 'icon';
    return `<svg class="${cls}" aria-hidden="true"><use href="#${symbolId}"></use></svg>`;
}

function initializeDashboards() {
    loadI18n();
    initManagerTabs();
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

function initManagerTabs() {
    const tablist = document.getElementById('managerTabs');
    if (!tablist) return;
    const tabs = Array.from(tablist.querySelectorAll('[role="tab"]'));

    function activateTab(tab, shouldFocus) {
        tabs.forEach(t => {
            const selected = t === tab;
            t.setAttribute('aria-selected', String(selected));
            t.tabIndex = selected ? 0 : -1;
            const panelId = t.getAttribute('aria-controls');
            const panel = panelId ? document.getElementById(panelId) : null;
            if (panel) {
                panel.hidden = !selected;
            }
        });
        if (shouldFocus) {
            tab.focus();
        }
    }

    tablist.addEventListener('click', event => {
        const tab = event.target.closest('[role="tab"]');
        if (tab && tablist.contains(tab)) {
            activateTab(tab, false);
        }
    });

    tablist.addEventListener('keydown', event => {
        const currentIndex = tabs.indexOf(document.activeElement);
        if (currentIndex === -1) return;
        let nextIndex = null;
        if (event.key === 'ArrowRight') nextIndex = (currentIndex + 1) % tabs.length;
        else if (event.key === 'ArrowLeft') nextIndex = (currentIndex - 1 + tabs.length) % tabs.length;
        else if (event.key === 'Home') nextIndex = 0;
        else if (event.key === 'End') nextIndex = tabs.length - 1;
        if (nextIndex !== null) {
            event.preventDefault();
            activateTab(tabs[nextIndex], true);
        }
    });
}

function renderAllDashboards() {
    renderCashierDashboard();
    renderQueueDashboard();
    renderCookDashboard();
    renderWaiterDashboard();
    renderManagerDashboard();
}

function emptyColumnMarkup(message) {
    return `<div class="kitchen-empty">${iconSvg('i-inbox', 'icon-3x kitchen-empty-icon')}<p>${escapeHtml(message)}</p></div>`;
}

function emptyTableRowMarkup(colspan, message) {
    return `<tr><td colspan="${colspan}" class="kitchen-empty">${iconSvg('i-inbox', 'icon-2x kitchen-empty-icon')}<p>${escapeHtml(message)}</p></td></tr>`;
}

function renderCashierDashboard() {
    const dashboard = document.getElementById('cashierDashboard');
    if (!dashboard) return;
    const createdOrders = orders.filter(o => o.status === 'CREATED');
    const countEl = document.getElementById('cashierCount');
    if (countEl) countEl.textContent = createdOrders.length;

    if (createdOrders.length === 0) {
        dashboard.innerHTML = emptyColumnMarkup(i18n.emptyCreated);
        return;
    }

    dashboard.innerHTML = createdOrders.map(order => createOrderCard(order, 'cashier')).join('');
}

function renderQueueDashboard() {
    const dashboard = document.getElementById('queueDashboard');
    if (!dashboard) return;
    const acceptedOrders = orders.filter(o => o.status === 'ACCEPTED');
    const countEl = document.getElementById('queueCount');
    if (countEl) countEl.textContent = acceptedOrders.length;

    if (acceptedOrders.length === 0) {
        dashboard.innerHTML = emptyColumnMarkup(i18n.emptyConfirmed);
        return;
    }

    dashboard.innerHTML = acceptedOrders.map(order => createOrderCard(order, 'queue')).join('');
}

function renderCookDashboard() {
    const dashboard = document.getElementById('cookDashboard');
    if (!dashboard) return;
    const processingOrders = orders.filter(o => o.status === 'PROCESSING');
    const countEl = document.getElementById('cookCount');
    if (countEl) countEl.textContent = processingOrders.length;

    if (processingOrders.length === 0) {
        dashboard.innerHTML = emptyColumnMarkup(i18n.emptyPreparing);
        return;
    }

    dashboard.innerHTML = processingOrders.map(order => createOrderCard(order, 'cook')).join('');
}

function renderWaiterDashboard() {
    const dashboard = document.getElementById('waiterDashboard');
    if (!dashboard) return;
    const doneOrders = orders.filter(o => o.status === 'DONE');
    const countEl = document.getElementById('waiterCount');
    if (countEl) countEl.textContent = doneOrders.length;

    if (doneOrders.length === 0) {
        dashboard.innerHTML = emptyColumnMarkup(i18n.emptyReady);
        return;
    }

    dashboard.innerHTML = doneOrders.map(order => createOrderCard(order, 'waiter')).join('');
}

function renderManagerDashboard() {
    const allOrdersTable = document.getElementById('allOrdersTable');
    if (!allOrdersTable) return;
    const activeOrders = orders.filter(o => o.status !== 'COMPLETE');

    if (activeOrders.length === 0) {
        allOrdersTable.innerHTML = emptyTableRowMarkup(6, i18n.emptyAll);
    } else {
        allOrdersTable.innerHTML = activeOrders.map(order => createManagerRow(order)).join('');
    }

    const cancelledOrdersTable = document.getElementById('cancelledOrdersTable');
    const cancelledOrders = orders.filter(o => o.status === 'CANCELLED');

    if (cancelledOrders.length === 0) {
        cancelledOrdersTable.innerHTML = emptyTableRowMarkup(5, i18n.emptyCancelled);
    } else {
        cancelledOrdersTable.innerHTML = cancelledOrders.map(order => createCancelledRow(order)).join('');
    }

    const completedOrdersTable = document.getElementById('completedOrdersTable');
    const completedOrders = orders.filter(o => o.status === 'COMPLETE');

    if (completedOrders.length === 0) {
        completedOrdersTable.innerHTML = emptyTableRowMarkup(5, i18n.emptyCompleted);
    } else {
        completedOrdersTable.innerHTML = completedOrders.map(order => createCompletedRow(order)).join('');
    }

    renderUnpaidOrders();
}

function getKdsTimerState(order) {
    const configEl = document.getElementById('kds-timer-config');
    const yellow = configEl ? parseInt(configEl.dataset.yellowMinutes || '0', 10) : 0;
    const red = configEl ? parseInt(configEl.dataset.redMinutes || '0', 10) : 0;

    // When threshold is 0, timer styling is deactivated and defaults to normal
    if (!yellow || yellow <= 0) {
        return 'normal';
    }

    const date = new Date(order.createdAt || order.updatedAt);
    const elapsedMinutes = Math.floor((new Date() - date) / 60000);

    if (red > 0 && elapsedMinutes >= red) {
        return 'danger';
    }
    if (yellow > 0 && elapsedMinutes >= yellow) {
        return 'warning';
    }
    return 'normal';
}

function getStatusState(status) {
    const map = {
        'CREATED': 'created',
        'ACCEPTED': 'accepted',
        'PROCESSING': 'processing',
        'DONE': 'done',
        'DELIVERED': 'delivered',
        'COMPLETE': 'complete',
        'CANCELLED': 'cancelled'
    };
    return map[status] || 'complete';
}

function createOrderCard(order, role) {
    const timeAgo = getTimeAgo(order.updatedAt);
    const items = (order.items || []).map(item => {
        const customs = Array.isArray(item.customizations) && item.customizations.length > 0
            ? ` <span class="kitchen-badge kitchen-badge-muted">${item.customizations.map(cust => escapeHtml(cust.name)).join(', ')}</span>`
            : '';
        return `<div class="kitchen-card-item">${escapeHtml(item.quantity)}x ${escapeHtml(item.name)}${customs}</div>`;
    }).join('');

    const actions = getActionsForRole(order, role);
    const timerState = getKdsTimerState(order);
    const takeaway = order.serviceType === 'TAKEAWAY'
        ? `<div class="kitchen-card-takeaway"><span class="kitchen-badge kitchen-badge-dark">${iconSvg('i-bag')} ${escapeHtml(i18n.labelTakeaway)}</span> ${escapeHtml(order.customerName)}</div>`
        : '';
    const approvalNotice = order.cancelReason
        ? `<div class="kitchen-card-approval">${iconSvg('i-shield')} ${escapeHtml(order.cancelReason === 'needs-approval:too-many-orders' ? (i18n.approvalTooManyOrders || '') : order.cancelReason)}</div>`
        : '';

    return `
        <article class="kitchen-card">
            <div class="kitchen-card-head">
                <div class="kitchen-card-meta">
                    <h3 class="kitchen-card-title">${escapeHtml(i18n.labelOrder)}${escapeHtml(order.orderNumber)}</h3>
                    ${takeaway}
                    ${approvalNotice}
                    <small class="kitchen-card-time">${escapeHtml(timeAgo)}</small>
                </div>
                <span class="kitchen-badge" data-timer="${escapeHtml(timerState)}">${escapeHtml(getStatusLabel(order.status))}</span>
            </div>
            <div class="kitchen-card-items">${items}</div>
            <div class="kitchen-card-total">
                <strong>${escapeHtml(i18n.labelTotal)}</strong>
                <strong class="kitchen-card-price">${escapeHtml(formatMoney(order.total))}</strong>
            </div>
            <div class="kitchen-card-actions">${actions}</div>
        </article>
    `;
}

function getActionsForRole(order, role) {
    const safeId = escapeHtml(order.id);
    const cancelBtn = `<button type="button" class="kitchen-btn kitchen-btn-outline-danger" data-action="cancel" data-order-id="${safeId}">${iconSvg('i-x')} ${escapeHtml(i18n.actionCancel)}</button>`;
    const prevBtn = `<button type="button" class="kitchen-btn kitchen-btn-outline-secondary" data-action="previous" data-order-id="${safeId}">${iconSvg('i-undo')} ${escapeHtml(i18n.actionPrevious)}</button>`;

    switch (role) {
        case 'cashier':
            return `
                <div class="kitchen-card-action-row">
                    <button type="button" class="kitchen-btn kitchen-btn-primary" data-action="next" data-order-id="${safeId}">
                        ${iconSvg('i-check')} ${escapeHtml(i18n.actionAccept)}
                    </button>
                    ${cancelBtn}
                </div>
            `;
        case 'queue':
            return `
                <div class="kitchen-card-action-row">
                    <button type="button" class="kitchen-btn kitchen-btn-info" data-action="next" data-order-id="${safeId}">
                        ${iconSvg('i-fire')} ${escapeHtml(i18n.actionCook)}
                    </button>
                </div>
                <div class="kitchen-card-action-row">
                    ${prevBtn}
                    ${cancelBtn}
                </div>
            `;
        case 'cook':
            return `
                <div class="kitchen-card-action-row">
                    <button type="button" class="kitchen-btn kitchen-btn-success" data-action="next" data-order-id="${safeId}">
                        ${iconSvg('i-check')} ${escapeHtml(i18n.actionDone)}
                    </button>
                </div>
                <div class="kitchen-card-action-row">
                    ${prevBtn}
                    ${cancelBtn}
                </div>
            `;
        case 'waiter':
            return `
                <div class="kitchen-card-action-row">
                    <button type="button" class="kitchen-btn kitchen-btn-success" data-action="next" data-order-id="${safeId}">
                        ${iconSvg('i-check-double')} ${escapeHtml(i18n.actionDelivered)}
                    </button>
                </div>
                <div class="kitchen-card-action-row">
                    ${prevBtn}
                    ${cancelBtn}
                </div>
            `;
        default:
            return '';
    }
}

function createManagerRow(order) {
    const safeId = escapeHtml(order.id);
    const items = (order.items || []).map(item => `${escapeHtml(item.quantity)}x ${escapeHtml(item.name)}`).join(', ');
    const timeAgo = getTimeAgo(order.createdAt);

    const cancelButton = order.status !== 'CANCELLED'
        ? `<button type="button" class="kitchen-btn kitchen-btn-outline-danger" data-action="cancel" data-order-id="${safeId}">
               ${iconSvg('i-ban')} ${escapeHtml(i18n.actionCancel)}
           </button>`
        : '';

    return `
        <tr>
            <td><strong>${escapeHtml(order.orderNumber)}</strong></td>
            <td>${items}</td>
            <td>${escapeHtml(formatMoney(order.total))}</td>
            <td><span class="kitchen-badge" data-status="${escapeHtml(getStatusState(order.status))}">${escapeHtml(getStatusLabel(order.status))}</span></td>
            <td>${escapeHtml(timeAgo)}</td>
            <td>
                <div class="kitchen-table-actions">
                    <button type="button" class="kitchen-btn kitchen-btn-outline-primary" data-action="status-change" data-order-id="${safeId}">
                        ${iconSvg('i-pencil')} ${escapeHtml(i18n.actionChange)}
                    </button>
                    ${cancelButton}
                </div>
            </td>
        </tr>
    `;
}

function createCancelledRow(order) {
    const items = (order.items || []).map(item => `${escapeHtml(item.quantity)}x ${escapeHtml(item.name)}`).join(', ');
    const cancelledAt = formatDateTime(order.updatedAt);

    return `
        <tr>
            <td><strong>${escapeHtml(order.orderNumber)}</strong></td>
            <td>${items}</td>
            <td>${escapeHtml(formatMoney(order.total))}</td>
            <td>${escapeHtml(cancelledAt)}</td>
            <td>${escapeHtml(order.cancelReason || i18n.labelNa || '')}</td>
        </tr>
    `;
}

function createCompletedRow(order) {
    const items = (order.items || []).map(item => `${escapeHtml(item.quantity)}x ${escapeHtml(item.name)}`).join(', ');
    const completedAt = formatDateTime(order.updatedAt);
    const mins = Math.max(0, Math.floor((new Date(order.updatedAt) - new Date(order.createdAt)) / 60000));
    const duration = format(i18n.durationMin || '{0} min', mins);

    return `
        <tr>
            <td><strong>${escapeHtml(order.orderNumber)}</strong></td>
            <td>${items}</td>
            <td>${escapeHtml(formatMoney(order.total))}</td>
            <td>${escapeHtml(completedAt)}</td>
            <td>${escapeHtml(duration)}</td>
        </tr>
    `;
}

function getStatusLabel(status) {
    const map = {
        'CREATED': i18n.statusCreated,
        'ACCEPTED': i18n.statusConfirmed || i18n.statusAccepted,
        'PROCESSING': i18n.statusPreparing || i18n.statusProcessing,
        'DONE': i18n.statusDone || i18n.statusReady,
        'DELIVERED': i18n.statusDelivered,
        'COMPLETE': i18n.statusCompleted || i18n.statusComplete,
        'CANCELLED': i18n.statusCancelled
    };
    return map[status] || status;
}

function getRoleLabel(role) {
    const map = {
        'cashier': i18n.roleCashier,
        'queue': i18n.roleQueue,
        'cook': i18n.roleCook,
        'waiter': i18n.roleWaiter
    };
    return map[role] || role;
}

function renderUnpaidOrders() {
    const container = document.getElementById('unpaidOrdersContainer');
    const countEl = document.getElementById('unpaidCount');
    if (!container) return;

    const unpaid = orders.filter(o =>
        o.paymentStatus === 'UNPAID' &&
        o.status !== 'CANCELLED' &&
        !(o.serviceType === 'TAKEAWAY' && o.orderChannel === 'ONLINE')
    );

    if (countEl) {
        countEl.textContent = unpaid.length;
        countEl.hidden = unpaid.length === 0;
    }

    if (unpaid.length === 0) {
        container.innerHTML = `<div class="kitchen-empty">${iconSvg('i-inbox', 'icon-2x kitchen-empty-icon')}<p>${escapeHtml(i18n.emptyUnpaid)}</p></div>`;
        return;
    }

    const tableGroups = new Map();
    const counterOrders = [];

    unpaid.forEach(order => {
        if (order.tableName || order.tableId) {
            const key = order.tableId || order.tableName;
            if (!tableGroups.has(key)) {
                tableGroups.set(key, {
                    tableId: order.tableId,
                    tableName: order.tableName || format(i18n.tablePrefix || '{0}', order.tableId),
                    orders: []
                });
            }
            tableGroups.get(key).orders.push(order);
        } else {
            counterOrders.push(order);
        }
    });

    let html = '';

    tableGroups.forEach(group => {
        const total = group.orders.reduce((sum, o) => sum + (o.total || 0), 0);
        const count = group.orders.length;
        const formattedTotal = formatMoney(total);
        const summaryText = count === 1
            ? format(i18n.unpaidTableOwesSingle, group.tableName, formattedTotal)
            : format(i18n.unpaidTableOwes, group.tableName, formattedTotal, count);

        const counterUrl = getTenantPrefix() + '/counter';
        const chargeBtn = `<a href="${escapeHtml(counterUrl)}" class="kitchen-btn kitchen-btn-outline-primary">${iconSvg('i-register')} ${escapeHtml(i18n.unpaidChargeAtCounter)}</a>`;
        const rows = group.orders.map(order => createUnpaidRow(order)).join('');

        html += `
            <section class="kitchen-unpaid-group" data-kind="table">
                <header class="kitchen-unpaid-header">
                    <div class="kitchen-unpaid-summary">
                        <span class="kitchen-unpaid-name">${iconSvg('i-chair', 'kitchen-unpaid-icon-primary')}${escapeHtml(group.tableName)}</span>
                        <span class="kitchen-badge kitchen-badge-danger">${escapeHtml(summaryText)}</span>
                    </div>
                    <div>${chargeBtn}</div>
                </header>
                <div class="kitchen-table-wrap">
                    <table class="kitchen-table">
                        <thead>
                            <tr>
                                <th class="kitchen-col-order">${escapeHtml(i18n.labelOrder)}</th>
                                <th class="kitchen-col-status">${escapeHtml(i18n.labelStatus)}</th>
                                <th class="kitchen-col-items">${escapeHtml(i18n.labelFood)}</th>
                                <th class="kitchen-col-total">${escapeHtml(i18n.labelTotal)}</th>
                                <th class="kitchen-col-wait">${escapeHtml(i18n.unpaidTimeWaiting)}</th>
                            </tr>
                        </thead>
                        <tbody>${rows}</tbody>
                    </table>
                </div>
            </section>
        `;
    });

    if (counterOrders.length > 0) {
        const total = counterOrders.reduce((sum, o) => sum + (o.total || 0), 0);
        const count = counterOrders.length;
        const groupTitle = i18n.unpaidCounterGroup || '';
        const formattedTotal = formatMoney(total);
        const summaryText = count === 1
            ? format(i18n.unpaidTableOwesSingle, groupTitle, formattedTotal)
            : format(i18n.unpaidTableOwes, groupTitle, formattedTotal, count);

        const rows = counterOrders.map(order => createUnpaidRow(order)).join('');

        html += `
            <section class="kitchen-unpaid-group" data-kind="counter">
                <header class="kitchen-unpaid-header">
                    <div class="kitchen-unpaid-summary">
                        <span class="kitchen-unpaid-name">${iconSvg('i-bag', 'kitchen-unpaid-icon-muted')}${escapeHtml(groupTitle)}</span>
                        <span class="kitchen-badge kitchen-badge-warning">${escapeHtml(summaryText)}</span>
                    </div>
                </header>
                <div class="kitchen-table-wrap">
                    <table class="kitchen-table">
                        <thead>
                            <tr>
                                <th class="kitchen-col-order">${escapeHtml(i18n.labelOrder)}</th>
                                <th class="kitchen-col-status">${escapeHtml(i18n.labelStatus)}</th>
                                <th class="kitchen-col-items">${escapeHtml(i18n.labelFood)}</th>
                                <th class="kitchen-col-total">${escapeHtml(i18n.labelTotal)}</th>
                                <th class="kitchen-col-wait">${escapeHtml(i18n.unpaidTimeWaiting)}</th>
                            </tr>
                        </thead>
                        <tbody>${rows}</tbody>
                    </table>
                </div>
            </section>
        `;
    }

    container.innerHTML = html;
}

function createUnpaidRow(order) {
    const items = (order.items || []).map(item => `${escapeHtml(item.quantity)}x ${escapeHtml(item.name)}`).join(', ');
    const timeAgo = getTimeAgo(order.createdAt);
    const customer = order.customerName ? ` <small class="kitchen-unpaid-customer">(${escapeHtml(order.customerName)})</small>` : '';

    return `
        <tr>
            <td><strong>#${escapeHtml(order.orderNumber)}</strong>${customer}</td>
            <td><span class="kitchen-badge" data-status="${escapeHtml(getStatusState(order.status))}">${escapeHtml(getStatusLabel(order.status))}</span></td>
            <td>${items}</td>
            <td><strong class="kitchen-card-price">${escapeHtml(formatMoney(order.total))}</strong></td>
            <td><small class="kitchen-card-time">${escapeHtml(timeAgo)}</small></td>
        </tr>
    `;
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

function getPreviousStatus(status) {
    const classes = {
        'ACCEPTED': 'CREATED',
        'PROCESSING': 'ACCEPTED',
        'DONE': 'PROCESSING',
        'DELIVERED': 'DONE',
        'COMPLETE': 'DELIVERED'
    };
    return classes[status];
}

function getTimeAgo(dateString) {
    const date = new Date(dateString);
    if (isNaN(date.getTime())) {
        console.error('Invalid date:', dateString);
        return i18n.timeJustNow || '';
    }

    const now = new Date();
    const seconds = Math.floor((now - date) / 1000);
    if (seconds < 0) return i18n.timeJustNow || '';
    if (seconds < 60) return format(i18n.timeSecAgo || '{0}s', seconds);

    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes} ${i18n.timeMinAgo || ''}`.trim();

    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours} ${i18n.timeHoursAgo || ''}`.trim();

    const days = Math.floor(hours / 24);
    if (days < 7) return `${days} ${i18n.timeDaysAgo || ''}`.trim();

    const weeks = Math.floor(days / 7);
    if (weeks < 4) return format(i18n.timeWeeksAgo || '{0}w', weeks);

    const months = Math.floor(days / 30);
    return format(i18n.timeMonthsAgo || '{0}mo', months);
}

function csrfToken() {
    return document.querySelector('meta[name="_csrf"]')?.content;
}

function moveToNextStatus(orderId, triggerBtn) {
    const actionKey = `order:${orderId}`;
    if (inFlightActions.has(actionKey)) return;
    const order = orders.find(o => o.id === orderId);
    if (!order) return;

    const nextStatus = getNextStatus(order.status);
    if (window.kitchenChime && nextStatus) {
        window.kitchenChime.expectOwn(order.id, nextStatus);
    }

    inFlightActions.add(actionKey);
    if (triggerBtn) triggerBtn.disabled = true;

    callAPI(moveToNextStatusUrl(order.id))
        .then(response => {
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            showToast(format(i18n.toastUpdated, getStatusLabel(nextStatus)));
            return updateOrders();
        })
        .catch(error => {
            console.error('Error:', error);
            showToast(i18n.toastError, 'error');
        })
        .finally(() => {
            inFlightActions.delete(actionKey);
            if (triggerBtn && triggerBtn.isConnected) triggerBtn.disabled = false;
        });
}

function moveToPreviousStatus(orderId, triggerBtn) {
    const actionKey = `order:${orderId}`;
    if (inFlightActions.has(actionKey)) return;
    const order = orders.find(o => o.id === orderId);
    if (!order) return;

    const prevStatus = getPreviousStatus(order.status);
    if (window.kitchenChime && prevStatus) {
        window.kitchenChime.expectOwn(order.id, prevStatus);
    }

    inFlightActions.add(actionKey);
    if (triggerBtn) triggerBtn.disabled = true;

    callAPI(moveToPreviousStatusUrl(order.id))
        .then(response => {
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            showToast(format(i18n.toastPrevious, order.orderNumber));
            return updateOrders();
        })
        .catch(error => {
            console.error('Error:', error);
            showToast(i18n.toastError, 'error');
        })
        .finally(() => {
            inFlightActions.delete(actionKey);
            if (triggerBtn && triggerBtn.isConnected) triggerBtn.disabled = false;
        });
}

function moveAllToNext(role, triggerBtn) {
    const actionKey = `batch:${role}`;
    if (inFlightActions.has(actionKey)) return;
    const statusMap = {
        'cashier': 'CREATED',
        'queue': 'ACCEPTED',
        'cook': 'PROCESSING',
        'waiter': 'DONE'
    };
    const targetStatus = statusMap[role];
    const ordersToMove = orders.filter(o => o.status === targetStatus);
    const orderIds = ordersToMove.map(o => o.id);
    if (orderIds.length === 0) return;

    const nextStatus = getNextStatus(targetStatus);
    if (window.kitchenChime && nextStatus) {
        ordersToMove.forEach(o => window.kitchenChime.expectOwn(o.id, nextStatus));
    }

    inFlightActions.add(actionKey);
    if (triggerBtn) triggerBtn.disabled = true;

    fetch(batchNextStatusUrl(), {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': csrfToken()
        },
        body: JSON.stringify(orderIds)
    }).then(response => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        showToast(format(i18n.toastBatchNext, getRoleLabel(role)));
        return updateOrders();
    }).catch(error => {
        console.error('Error:', error);
        showToast(i18n.toastError, 'error');
    }).finally(() => {
        inFlightActions.delete(actionKey);
        if (triggerBtn && triggerBtn.isConnected) triggerBtn.disabled = false;
    });
}

function moveAllToPrevious(role, triggerBtn) {
    const actionKey = `batch:${role}`;
    if (inFlightActions.has(actionKey)) return;
    const statusMap = {
        'queue': 'ACCEPTED',
        'cook': 'PROCESSING',
        'waiter': 'DONE'
    };
    const targetStatus = statusMap[role];
    const ordersToMove = orders.filter(o => o.status === targetStatus);
    const orderIds = ordersToMove.map(o => o.id);
    if (orderIds.length === 0) return;

    const prevStatus = getPreviousStatus(targetStatus);
    if (window.kitchenChime && prevStatus) {
        ordersToMove.forEach(o => window.kitchenChime.expectOwn(o.id, prevStatus));
    }

    inFlightActions.add(actionKey);
    if (triggerBtn) triggerBtn.disabled = true;

    fetch(batchPreviousStatusUrl(), {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': csrfToken()
        },
        body: JSON.stringify(orderIds)
    }).then(response => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        showToast(format(i18n.toastBatchPrevious, getRoleLabel(role)));
        return updateOrders();
    }).catch(error => {
        console.error('Error:', error);
        showToast(i18n.toastError, 'error');
    }).finally(() => {
        inFlightActions.delete(actionKey);
        if (triggerBtn && triggerBtn.isConnected) triggerBtn.disabled = false;
    });
}

function showCancelModal(orderId, opener) {
    const order = orders.find(o => o.id === orderId);
    if (!order) return;
    currentCancelOrderId = orderId;
    const numberEl = document.getElementById('cancelOrderNumber');
    if (numberEl) numberEl.textContent = order.orderNumber ?? '';
    const reasonEl = document.getElementById('cancelReason');
    if (reasonEl) reasonEl.value = '';
    const dialog = document.getElementById('cancelModal');
    if (window.FastBiteDialogs && dialog) {
        window.FastBiteDialogs.open(dialog, opener);
        if (reasonEl) reasonEl.focus();
    }
}

function confirmCancel(triggerBtn) {
    const actionKey = 'dialog:cancel';
    if (inFlightActions.has(actionKey)) return;
    const order = orders.find(o => o.id === currentCancelOrderId);
    if (!order) return;

    if (window.kitchenChime) {
        window.kitchenChime.expectOwn(order.id, 'CANCELLED');
    }

    const cancelReason = document.getElementById('cancelReason')?.value || '';
    inFlightActions.add(actionKey);
    if (triggerBtn) triggerBtn.disabled = true;

    fetch(cancelOrderUrl(order.id), {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': csrfToken()
        },
        body: JSON.stringify({ value: cancelReason })
    }).then(response => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        showToast(format(i18n.toastCancelled, order.orderNumber));
        const dialog = document.getElementById('cancelModal');
        if (window.FastBiteDialogs && dialog) {
            window.FastBiteDialogs.close(dialog);
        }
        return updateOrders();
    }).catch(error => {
        console.error('Error:', error);
        showToast(i18n.toastError, 'error');
    }).finally(() => {
        inFlightActions.delete(actionKey);
        if (triggerBtn && triggerBtn.isConnected) triggerBtn.disabled = false;
    });
}

function showStatusChangeModal(orderId, opener) {
    const order = orders.find(o => o.id === orderId);
    if (!order) return;
    currentStatusChangeOrderId = orderId;
    const promptEl = document.getElementById('statusChangePrompt');
    if (promptEl) {
        const orderNum = escapeHtml(order.orderNumber);
        promptEl.innerHTML = escapeHtml(i18n.statusPrompt || '#{0}')
            .replace('{0}', `<span id="statusOrderNumber">${orderNum}</span>`);
    }
    const statusSelect = document.getElementById('newStatus');
    if (statusSelect) statusSelect.value = order.status;
    const reasonDiv = document.getElementById('statusCancelReasonDiv');
    if (reasonDiv) reasonDiv.hidden = order.status !== 'CANCELLED';
    const reasonInput = document.getElementById('statusCancelReason');
    if (reasonInput) reasonInput.value = order.cancelReason || '';

    const dialog = document.getElementById('statusChangeModal');
    if (window.FastBiteDialogs && dialog) {
        window.FastBiteDialogs.open(dialog, opener);
        if (statusSelect) statusSelect.focus();
    }
}

function confirmStatusChange(triggerBtn) {
    const actionKey = 'dialog:status';
    if (inFlightActions.has(actionKey)) return;
    const order = orders.find(o => o.id === currentStatusChangeOrderId);
    if (!order) return;

    const newStatus = document.getElementById('newStatus')?.value;
    const reason = document.getElementById('statusCancelReason')?.value || '';
    if (!newStatus) return;

    if (window.kitchenChime) {
        window.kitchenChime.expectOwn(order.id, newStatus);
    }

    inFlightActions.add(actionKey);
    if (triggerBtn) triggerBtn.disabled = true;

    const url = newStatus === 'CANCELLED' ? cancelOrderUrl(order.id) : changeOrderStatusUrl(order.id);
    const body = newStatus === 'CANCELLED' ? { value: reason } : { value: newStatus };

    fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': csrfToken()
        },
        body: JSON.stringify(body)
    }).then(response => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        showToast(newStatus === 'CANCELLED'
            ? format(i18n.toastCancelled, order.orderNumber)
            : format(i18n.toastUpdated, getStatusLabel(newStatus)));
        const dialog = document.getElementById('statusChangeModal');
        if (window.FastBiteDialogs && dialog) {
            window.FastBiteDialogs.close(dialog);
        }
        return updateOrders();
    }).catch(error => {
        console.error('Error:', error);
        showToast(i18n.toastError, 'error');
    }).finally(() => {
        inFlightActions.delete(actionKey);
        if (triggerBtn && triggerBtn.isConnected) triggerBtn.disabled = false;
    });
}

// Accessible toast: #toastContainer is the single role="status" live region; toasts inside carry no role.
function showToast(message, tone) {
    if (!message) return;
    const container = document.getElementById('toastContainer');
    if (!container) return;

    const toast = document.createElement('div');
    toast.className = 'toast kitchen-toast';
    if (tone === 'error') {
        toast.dataset.tone = 'error';
    }

    const text = document.createElement('span');
    text.className = 'kitchen-toast-text';
    text.textContent = message;

    const closeBtn = document.createElement('button');
    closeBtn.type = 'button';
    closeBtn.className = 'btn-close';
    closeBtn.dataset.toastClose = '';
    closeBtn.setAttribute('aria-label', i18n.labelClose || 'Close');

    toast.appendChild(text);
    toast.appendChild(closeBtn);
    container.appendChild(toast);

    setTimeout(() => {
        toast.remove();
    }, 4000);
}

// Start realtime updates via Server-Sent Events (SSE) with fallback polling.
// Live updates stay silent on screen (chime audio notifies staff).
function startRealtimeUpdates() {
    setInterval(() => {
        renderAllDashboards();
    }, 15000);

    const sseUrl = getTenantPrefix() + '/api/orders/live';

    connectLiveUpdates(sseUrl, {
        poll: updateOrders,
        intervalMs: 5000,
        onUpdate: function (event) {
            try {
                const data = JSON.parse(event.data);
                if (window.kitchenChime && data && data.id && data.status) {
                    window.kitchenChime.observe([data]);
                }
            } catch (e) {
                console.debug('SSE event parse error', e);
            }
            updateOrders();
        }
    });
}

let isInitialOrdersLoad = true;

function updateOrders() {
    return fetch(getOrderListUrl(), {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': csrfToken()
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
            if (window.kitchenChime) {
                window.kitchenChime.observe(orders, { initial: isInitialOrdersLoad });
            }
            isInitialOrdersLoad = false;
        }
    }).catch(error => console.error('Error fetching orders:', error));
}

function callAPI(url) {
    return fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': csrfToken()
        }
    });
}

document.addEventListener('click', event => {
    const btn = event.target.closest('[data-action]');
    if (!btn || btn.disabled) return;
    const action = btn.dataset.action;
    const orderId = btn.dataset.orderId;
    const role = btn.dataset.role;

    switch (action) {
        case 'next':
            if (orderId) moveToNextStatus(orderId, btn);
            break;
        case 'previous':
            if (orderId) moveToPreviousStatus(orderId, btn);
            break;
        case 'cancel':
            if (orderId) showCancelModal(orderId, btn);
            break;
        case 'status-change':
            if (orderId) showStatusChangeModal(orderId, btn);
            break;
        case 'batch-next':
            if (role) moveAllToNext(role, btn);
            break;
        case 'batch-previous':
            if (role) moveAllToPrevious(role, btn);
            break;
        case 'confirm-cancel':
            confirmCancel(btn);
            break;
        case 'confirm-status-change':
            confirmStatusChange(btn);
            break;
        default:
            break;
    }
});

document.addEventListener('DOMContentLoaded', function () {
    initializeDashboards();

    const newStatusSelect = document.getElementById('newStatus');
    if (newStatusSelect) {
        newStatusSelect.addEventListener('change', function () {
            const reasonDiv = document.getElementById('statusCancelReasonDiv');
            if (reasonDiv) {
                reasonDiv.hidden = this.value !== 'CANCELLED';
            }
        });
    }

    updateOrders();
});