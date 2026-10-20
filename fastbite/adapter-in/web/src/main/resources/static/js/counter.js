// POS Logic
let products = [];
let categories = [];
let allCustomizations = [];
let cart = [];
let selectedTable = null;
let selectedGroup = 'all';
let currentEditingItemIndex = null;
let editingOrderId = null;
let allTables = [];
let isTableMode = false;
let tableOrders = [];
let reassigningOrderId = null;
let currentProduct = null;
let payingSingleOrderId = null;
let isFirstQuickCashClick = true;

// Takeaway takes the place of a table: a new order without one, or the list of those still to be paid
const TAKEAWAY = { id: null, takeaway: true };
const isTakeaway = () => Boolean(selectedTable && selectedTable.takeaway);
const sessionOrdersUrl = () => isTakeaway()
    ? '/counter/api/takeaway/orders'
    : `/counter/api/tables/${selectedTable.id}/active-orders`;
const sessionCartUrl = () => isTakeaway()
    ? '/counter/fragments/takeaway-session-cart'
    : `/counter/fragments/table-session-cart/${selectedTable.id}`;

// CSRF tokens
const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;

// Translation dictionary helper
const i18nElement = document.getElementById('dictionary');
const t = (key) => i18nElement?.dataset[key] || key;

function selectPaymentMethod(method) {
    document.querySelectorAll('.payment-method-btn').forEach(el => {
        el.classList.toggle('active', el.dataset.method === method);
    });
    const cashDetails = document.getElementById('cash-payment-details');
    if (cashDetails) {
        cashDetails.style.display = method === 'CASH' ? 'block' : 'none';
    }
}

// The ticket issued for a payment, exactly as stored; printing is optional, the ticket is issued anyway
function printTicket(ticketId) {
    const doPrint = document.getElementById('print-receipt-toggle').checked;
    if (!doPrint || !ticketId) return;

    const url = `/counter/tickets/${encodeURIComponent(ticketId)}?t=${Date.now()}`;
    const printFrame = document.getElementById('print-frame');
    if (printFrame) {
        printFrame.onload = function() {
            printFrame.contentWindow.focus();
            printFrame.contentWindow.print();
        };
        printFrame.src = url;
    }
}

// The customer named on a full invoice, when the cashier chose one
function invoiceDetails() {
    if (!document.getElementById('type-invoice').checked) return null;
    const value = id => document.getElementById(`pay-${id}`).value.trim();
    return { taxId: value('taxId'), country: value('country'), name: value('name'), address: value('address') };
}

// Cash handed over, so the ticket can show the change
function cashReceived(method) {
    if (method !== 'CASH') return null;
    const received = parseFloat(document.getElementById('received-amount').value);
    return Number.isFinite(received) ? received : null;
}

// The server explains a refused payment in the cashier's language
async function errorMessage(response, fallback) {
    const text = (await response.text()).trim();
    return text && text.length < 300 && !text.startsWith('<') && !text.startsWith('{') ? text : fallback;
}

function printProforma(orderId) {
    const url = `/counter/receipt?ids=${orderId}&t=${Date.now()}`;
    const printFrame = document.getElementById('print-frame');
    if (printFrame) {
        printFrame.onload = function() {
            printFrame.contentWindow.focus();
            printFrame.contentWindow.print();
        };
        printFrame.src = url;
    }
}

document.addEventListener('DOMContentLoaded', async () => {
    // The grid and tables are rendered by the server and don't need the data below,
    // so everything loads at once. Clicks are enabled once the data has arrived.
    const data = loadInitialData();
    renderAll();
    showTableCount();
    renderTables();
    refreshPickupCount();
    startRealtimeOrderUpdates();
    await data;
    setupEventListeners();
});

async function fetchItems(url) {
    const res = await fetch(url);
    if (!res.ok) {
        return [];
    }
    const data = await res.json();
    return data.map(d => ({ id: d.id, ...d.customFields }));
}

async function loadInitialData() {
    try {
        [products, categories, allCustomizations, allTables] = await Promise.all([
            fetchItems('/api/backoffice/products'),
            fetchItems('/api/backoffice/groups'),
            fetchItems('/api/backoffice/customizations'),
            fetchItems('/api/backoffice/tables')
        ]);
    } catch (error) {
        console.error('Error loading data:', error);
    }
}

function setupEventListeners() {
    // Category filtering
    document.getElementById('categories-sidebar').addEventListener('click', (e) => {
        const item = e.target.closest('.category-item');
        if (!item) return;

        document.querySelectorAll('.category-item').forEach(el => el.classList.remove('active'));
        item.classList.add('active');
        selectedGroup = item.dataset.groupId;
        renderProducts();
    });

    // Search
    document.getElementById('productSearch').addEventListener('input', (e) => {
        renderProducts(e.target.value.toLowerCase());
    });

    // Table selection modal trigger
    document.getElementById('btn-select-table').addEventListener('click', () => {
        new bootstrap.Modal(document.getElementById('tableModal')).show();
    });

    // Tables grid click (inside modal)
    document.getElementById('tables-list').addEventListener('click', async (e) => {
        if (e.target.closest('.qr-trust-toggle')) {
            return;
        }
        const btn = e.target.closest('.table-btn');
        if (!btn || btn.classList.contains('opacity-50')) return;

        const tableId = btn.dataset.id;
        const tableName = btn.dataset.name;
        const status = btn.dataset.status;

        if (status === 'OCCUPIED' || status === 'BILLING') {
            document.getElementById('active-orders-table-name').textContent = tableName;
            selectedTable = { id: tableId, name: tableName };
            await loadTableOrders(tableId);
            hideModal('tableModal');
            new bootstrap.Modal(document.getElementById('activeOrdersModal')).show();
        } else {
            selectAvailableTable(tableId, tableName);
            hideModal('tableModal');
        }
    });

    // Takeaway: a new order to collect, or the orders waiting to be paid at pickup
    document.getElementById('btn-takeaway').addEventListener('click', selectTakeaway);
    document.getElementById('btn-pickup-orders').addEventListener('click', async () => {
        selectedTable = TAKEAWAY;
        await loadTable();
        if (tableOrders.length === 0) {
            showToast(t('messageNoPickupOrders'));
            resetPOS();
        }
    });

    // Standard buttons (Load Table, New Order)
    document.getElementById('btn-load-table').addEventListener('click', () => {
        loadTable();
        hideModal('activeOrdersModal');
    });

    document.getElementById('btn-new-order-table').addEventListener('click', () => {
        selectAvailableTable(selectedTable.id, selectedTable.name);
        hideModal('activeOrdersModal');
    });

    // POS Actions
    document.getElementById('btn-save-order').addEventListener('click', () => saveOrder());
    document.getElementById('btn-assign-table').addEventListener('click', () => submitOrder(false));
    document.getElementById('btn-billing').addEventListener('click', () => setTableStatus(selectedTable.id, 'BILLING'));
    document.getElementById('btn-clear-cart').addEventListener('click', () => {
        if (confirm(t('messageConfirmClearOrder'))) {
            resetPOS();
        }
    });

    // Payment Logic
    document.getElementById('btn-proceed-payment').addEventListener('click', () => {
        if (isTableMode) {
            showPaymentModalForTable();
        } else {
            showPaymentModal();
        }
    });

    document.getElementById('btn-complete-order').addEventListener('click', () => submitOrder());

    // Payment Method Selection
    document.querySelectorAll('.payment-method-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            selectPaymentMethod(btn.dataset.method);
        });
    });

    // Quick Cash Buttons
    document.querySelectorAll('.money-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const val = parseFloat(btn.dataset.value);
            const input = document.getElementById('received-amount');
            if (isFirstQuickCashClick) {
                input.value = val.toFixed(2);
                isFirstQuickCashClick = false;
            } else {
                const current = parseFloat(input.value || 0);
                input.value = (current + val).toFixed(2);
            }
            calculateChange();
        });
    });

    document.getElementById('received-amount').addEventListener('input', calculateChange);

    // Customization Modal
    document.getElementById('btn-add-with-customs').addEventListener('click', saveCustomizations);

    // Mobile UI
    document.getElementById('mobile-cart-toggle')?.addEventListener('click', toggleMobileCart);
    document.getElementById('btn-close-cart')?.addEventListener('click', toggleMobileCart);
    document.getElementById('cart-overlay')?.addEventListener('click', toggleMobileCart);

    // Printing options toggle visibility
    document.getElementsByName('receiptType').forEach(radio => {
        radio.addEventListener('change', (e) => {
            const invoiceDetails = document.getElementById('invoice-details');
            if (invoiceDetails) {
                invoiceDetails.style.display = e.target.value === 'INVOICE' ? 'block' : 'none';
            }
        });
    });
}

function toggleMobileCart() {
    const cartEl = document.querySelector('.pos-cart');
    const overlayEl = document.querySelector('.cart-overlay');
    cartEl.classList.toggle('active');
    overlayEl.classList.toggle('active');
}

function hideModal(id) {
    const modalEl = document.getElementById(id);
    if (!modalEl) return;
    const modal = bootstrap.Modal.getInstance(modalEl);
    if (modal) modal.hide();

    // FORCE BACKDROP REMOVAL
    setTimeout(() => {
        const backdrop = document.querySelector('.modal-backdrop');
        if (backdrop) {
            document.querySelectorAll('.modal-backdrop').forEach(el => el.remove());
            document.body.classList.remove('modal-open');
            document.body.style.paddingRight = '';
            document.body.style.overflow = '';
        }
    }, 150);
}

function calculateChange() {
    let total = 0;
    if (payingSingleOrderId) {
        const order = tableOrders.find(o => o.id === payingSingleOrderId);
        total = order ? order.total : 0;
    } else if (isTableMode) {
        tableOrders.forEach(o => {
            if (o.paymentStatus !== 'PAID' && o.status !== 'CANCELLED') {
                total += o.total;
            }
        });
    } else {
        total = calculateTotal();
    }
    const received = parseFloat(document.getElementById('received-amount').value || 0);
    const change = Math.max(0, received - total);
    document.getElementById('payment-change').textContent = formatPrice(change);
}

function selectAvailableTable(id, name) {
    selectedTable = { id, name };
    document.getElementById('selected-table-name').textContent = `${t('labelTableName')}: ${name}`;
    startNewOrder();
}

function selectTakeaway() {
    selectedTable = TAKEAWAY;
    document.getElementById('selected-table-name').textContent = t('labelAssignTable');
    startNewOrder();
}

// An empty cart for the table or takeaway that was just picked
function startNewOrder() {
    isTableMode = false;
    editingOrderId = null;
    document.querySelectorAll('.table-btn').forEach(el => {
        el.classList.toggle('active', el.dataset.id === selectedTable.id);
    });

    document.getElementById('cart-actions-new').classList.remove('d-none');
    document.getElementById('cart-actions-edit').classList.add('d-none');
    document.getElementById('btn-assign-table').classList.remove('d-none');
    document.getElementById('btn-billing').classList.add('d-none');

    cart = [];
    showOrderTarget();
    updateCartUI();
}

// Shows whether the order being entered is for a table or to take away
function showOrderTarget() {
    const takeaway = isTakeaway();
    document.getElementById('btn-takeaway').classList.toggle('active', takeaway);
    // A class, not the button text: the text is replaced by a spinner while an order is sent
    document.getElementById('cart-actions-new').classList.toggle('order-for-takeaway', takeaway);
    const name = document.getElementById('takeaway-customer-name');
    name.classList.toggle('d-none', !takeaway || isTableMode || editingOrderId !== null);
    if (!takeaway) name.value = '';
}

async function loadTableOrders(tableId) {
    const list = document.getElementById('active-orders-list');
    if (!list) return;
    list.innerHTML = `<div class="text-center p-3 text-muted"><i class="fas fa-spinner fa-spin me-2"></i>${t('labelLoading')}</div>`;

    try {
        const res = await fetch(`/counter/fragments/active-orders/${tableId}`);
        if (res.ok) {
            list.innerHTML = await res.text();
        } else {
            throw new Error('Failed to load active orders');
        }
    } catch (e) {
        console.error(e);
        list.innerHTML = `<div class="alert alert-danger py-2 m-0 small">${t('messageOrderCreateError')}</div>`;
    }
}

async function loadTable() {
    if (!selectedTable) return;
    try {
        const res = await fetch(sessionOrdersUrl());
        if (!res.ok) throw new Error('Failed to load orders');
        const orders = await res.json();
        isTableMode = true;
        tableOrders = orders.map(o => ({ ...o, expanded: false }));
        showOrderTarget();
        updateCartUI();
    } catch (error) {
        console.error('Error loading table orders:', error);
        showToast('Error loading table orders');
    }
}

async function refreshTableMode() {
    if (!selectedTable) {
        resetPOS();
        return;
    }
    const res = await fetch(sessionOrdersUrl());
    const orders = await res.json();
    tableOrders = orders.map(o => {
        const old = tableOrders.find(prev => prev.id === o.id);
        return { ...o, expanded: old ? old.expanded : false };
    });
    updateCartUI();
}

async function renderTableCart() {
    const container = document.getElementById('cart-items');
    if (!container) return;

    if (tableOrders.length === 0) {
        isTableMode = false;
        showOrderTarget();
        updateCartUI();
        return;
    }

    try {
        const expandedIndices = tableOrders.map((o, idx) => o.expanded ? idx : null).filter(idx => idx !== null);
        const res = await fetch(`${sessionCartUrl()}?expandedIndices=${expandedIndices.join(',')}`);
        if (res.ok) {
            container.innerHTML = await res.text();

            const subtotalHeader = res.headers.get('X-Cart-Subtotal');
            const discountHeader = res.headers.get('X-Cart-Discount');
            const totalHeader = res.headers.get('X-Cart-Total');

            if (subtotalHeader && discountHeader && totalHeader) {
                const subVal = parseFloat(subtotalHeader);
                const discVal = parseFloat(discountHeader);
                const totVal = parseFloat(totalHeader);

                document.getElementById('cart-subtotal').textContent = formatPrice(subVal);
                document.getElementById('cart-discount').textContent = (discVal > 0 ? '-' : '') + formatPrice(discVal);
                document.getElementById('cart-total').textContent = formatPrice(totVal);
            } else {
                let totalUnpaid = 0;
                tableOrders.forEach(order => {
                    if (order.paymentStatus !== 'PAID' && order.status !== 'CANCELLED') {
                        totalUnpaid += order.total;
                    }
                });
                document.getElementById('cart-total').textContent = formatPrice(totalUnpaid);
                document.getElementById('cart-subtotal').textContent = formatPrice(totalUnpaid);
                document.getElementById('cart-discount').textContent = formatPrice(0);
            }

            let totalUnpaid = 0;
            tableOrders.forEach(order => {
                if (order.paymentStatus !== 'PAID' && order.status !== 'CANCELLED') {
                    totalUnpaid += order.total;
                }
            });
            // Takeaway orders belong to different customers: each is paid on its own, never together
            document.getElementById('btn-proceed-payment').disabled = totalUnpaid <= 0 || isTakeaway();
            document.getElementById('btn-assign-table').classList.add('d-none');
            document.getElementById('btn-billing').classList.toggle('d-none', isTakeaway());
        }
    } catch (e) {
        console.error(e);
    }
}

function toggleOrderDetails(idx) {
    tableOrders[idx].expanded = !tableOrders[idx].expanded;
    renderTableCart();
}

function getStatusBadgeClass(paymentStatus, status) {
    if (status === 'CANCELLED') return 'bg-danger';
    if (paymentStatus === 'PAID') return 'bg-success';
    return 'bg-warning text-dark';
}

function getLocalizedStatus(paymentStatus, status) {
    if (status === 'CANCELLED') return t('labelCanceled');
    if (paymentStatus === 'PAID') return t('labelPaid');
    return t('labelUnpaid');
}

async function payIndividualOrder(orderId) {
    const order = tableOrders.find(o => o.id === orderId);
    if (!order) return;

    resetPaymentModal();
    payingSingleOrderId = orderId;
    document.getElementById('payment-total').textContent = formatPrice(order.total);
    document.getElementById('received-amount').value = order.total.toFixed(2);
    calculateChange();

    new bootstrap.Modal(document.getElementById('paymentModal')).show();
}

async function cancelOrderFromTable(orderId) {
    if (!confirm(t('messageConfirmCancelOrder'))) return;
    try {
        const res = await fetch(`/counter/api/orders/${orderId}/cancel`, {
            method: 'POST',
            headers: { 'X-CSRF-TOKEN': csrfToken }
        });
        if (res.ok) {
            refreshTableMode();
            refreshTables();
        }
    } catch (e) {
        console.error(e);
    }
}

function editOrderFromTable(orderId) {
    const order = tableOrders.find(o => o.id === orderId);
    if (!order) return;

    editingOrderId = orderId;
    cart = order.items.map(item => ({
        id: item.id || btoa(Math.random()).substring(0, 8),
        productId: item.itemId,
        itemId: item.itemId,
        name: item.name,
        price: item.price,
        quantity: item.quantity,
        customizations: item.customizations || []
    }));

    isTableMode = false;
    document.getElementById('cart-actions-new').classList.add('d-none');
    document.getElementById('cart-actions-edit').classList.remove('d-none');
    // Billing is a table state; a takeaway order being edited is just saved
    document.querySelector('#cart-actions-edit #btn-billing').classList.toggle('d-none', isTakeaway());
    showOrderTarget();
    updateCartUI();
}

async function openReassignModal(orderId) {
    reassigningOrderId = orderId;
    const container = document.getElementById('reassign-tables-list');
    if (!container) return;

    try {
        const currentTableId = selectedTable ? selectedTable.id : '';
        const res = await fetch(`/counter/fragments/reassign-tables?currentTableId=${currentTableId}`);
        if (res.ok) {
            container.innerHTML = await res.text();
            new bootstrap.Modal(document.getElementById('reassignModal')).show();
        }
    } catch (e) {
        console.error(e);
    }
}

async function reassignOrder(newTableId) {
    if (!reassigningOrderId) return;
    try {
        const res = await fetch(`/counter/api/orders/${reassigningOrderId}/reassign?tableId=${newTableId}`, {
            method: 'POST',
            headers: { 'X-CSRF-TOKEN': csrfToken }
        });
        if (res.ok) {
            hideModal('reassignModal');
            refreshTableMode();
            refreshTables();
        }
    } catch (e) {
        console.error(e);
    }
}

function showPaymentModal() {
    resetPaymentModal();
    payingSingleOrderId = null;
    const total = calculateTotal();
    document.getElementById('payment-total').textContent = formatPrice(total);
    document.getElementById('received-amount').value = total.toFixed(2);
    
    // Ensure correct default method UI state
    const activeBtn = document.querySelector('.payment-method-btn.active');
    if (activeBtn) {
        selectPaymentMethod(activeBtn.dataset.method);
    }

    calculateChange();
    new bootstrap.Modal(document.getElementById('paymentModal')).show();
}

async function showPaymentModalForTable() {
    resetPaymentModal();
    payingSingleOrderId = null;
    let totalUnpaid = 0;
    tableOrders.forEach(o => {
        if (o.paymentStatus !== 'PAID' && o.status !== 'CANCELLED') {
            totalUnpaid += o.total;
        }
    });

    document.getElementById('payment-total').textContent = formatPrice(totalUnpaid);
    document.getElementById('received-amount').value = totalUnpaid.toFixed(2);
    
    // Ensure correct default method UI state
    const activeBtn = document.querySelector('.payment-method-btn.active');
    if (activeBtn) {
        selectPaymentMethod(activeBtn.dataset.method);
    }

    calculateChange();

    new bootstrap.Modal(document.getElementById('paymentModal')).show();
}

async function submitTableBulkPayment() {
    const btn = document.getElementById('btn-complete-order');
    const originalHtml = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = `<span class="spinner-border spinner-border-sm me-2"></span>${t('labelLoading')}`;

    try {
        const ordersToPay = payingSingleOrderId
            ? [tableOrders.find(o => o.id === payingSingleOrderId)]
            : tableOrders.filter(o => o.paymentStatus !== 'PAID' && o.status !== 'CANCELLED');

        // One payment for all of them, and one ticket
        const method = document.querySelector('.payment-method-btn.active').dataset.method;
        const response = await fetch('/counter/api/settle', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrfToken },
            body: JSON.stringify({
                orderIds: ordersToPay.map(o => o.id),
                paymentMethod: method,
                cashReceived: cashReceived(method),
                invoice: invoiceDetails()
            })
        });
        if (response.ok) {
            const ticket = await response.json();
            // Only clear table if bulk payment or all orders now paid
            if (!payingSingleOrderId) {
                await fetch(`/counter/api/tables/${selectedTable.id}/status?status=AVAILABLE`, {
                    method: 'POST',
                    headers: { 'X-CSRF-TOKEN': csrfToken }
                });
            }

            showToast(t('labelPaid'));
            hideModal('paymentModal');

            printTicket(ticket.ticketId);
            if (payingSingleOrderId) {
                payingSingleOrderId = null;
                refreshTableMode();
                refreshTables();
            } else {
                resetPOS();
            }
        } else {
            showToast(await errorMessage(response, t('messageOrderSubmitError')));
        }
    } catch (e) {
        console.error(e);
        showToast('Error processing payment');
    } finally {
        btn.disabled = false;
        btn.innerHTML = originalHtml;
    }
}

async function submitOrder(paid = true) {
    if (isTableMode && paid) {
        await submitTableBulkPayment();
        return;
    }

    const method = paid ? document.querySelector('.payment-method-btn.active').dataset.method : 'CASH';
    const request = {
        items: cart,
        tableId: selectedTable ? selectedTable.id : null,
        paymentMethod: method,
        paid: paid,
        customerName: isTakeaway() ? document.getElementById('takeaway-customer-name').value.trim() : null,
        cashReceived: paid ? cashReceived(method) : null,
        invoice: paid ? invoiceDetails() : null
    };

    const btn = paid ? document.getElementById('btn-complete-order') : document.getElementById('btn-assign-table');
    const originalHtml = btn.innerHTML;

    btn.disabled = true;
    btn.innerHTML = `<span class="spinner-border spinner-border-sm me-2"></span>${t('labelLoading')}`;

    try {
        const url = editingOrderId ? `/counter/api/orders/${editingOrderId}` : '/counter/api/order';
        const method = editingOrderId ? 'PUT' : 'POST';

        const res = await fetch(url, {
            method: method,
            headers: {
                'Content-Type': 'application/json',
                'X-CSRF-TOKEN': csrfToken
            },
            body: JSON.stringify(request)
        });

        if (res.ok) {
            const data = await res.json();
            const orderId = editingOrderId || data.orderId;
            
            if (paid) {
                printTicket(data.ticketId);
            }

            if (request.tableId) {
                await fetch(`/counter/api/tables/${request.tableId}/status?status=OCCUPIED`, {
                    method: 'POST',
                    headers: { 'X-CSRF-TOKEN': csrfToken }
                });
            }

            showToast(t('messageOrderCreatedSuccess').replace('{0}', data.orderNumber || editingOrderId));
            if (paid) hideModal('paymentModal');
            resetPOS();
        } else {
            showToast(await errorMessage(res, t('messageOrderCreateError')));
        }
    } catch (error) {
        console.error('Error submitting order:', error);
        showToast(t('messageOrderSubmitError'));
    } finally {
        btn.disabled = false;
        btn.innerHTML = originalHtml;
    }
}

async function saveOrder() {
    await submitOrder(false);
}

function resetPOS() {
    cart = [];
    selectedTable = null;
    editingOrderId = null;
    isTableMode = false;
    tableOrders = [];
    document.getElementById('selected-table-name').textContent = t('labelAssignTable');
    document.getElementById('cart-actions-new').classList.remove('d-none');
    document.getElementById('cart-actions-edit').classList.add('d-none');
    document.getElementById('btn-billing').classList.add('d-none');
    document.getElementById('btn-assign-table').classList.remove('d-none');
    showOrderTarget();
    updateCartUI();
    refreshTables();
}

// How many tables have open orders, counted from the table picker
function showTableCount() {
    const busy = document.querySelectorAll('#tables-list .table-btn:not([data-status="AVAILABLE"])').length;
    const badge = document.getElementById('table-count');
    badge.textContent = busy;
    badge.classList.toggle('d-none', busy === 0);
}

// How many takeaway orders are waiting to be paid at pickup
async function refreshPickupCount() {
    const badge = document.getElementById('pickup-count');
    try {
        const res = await fetch('/counter/api/takeaway/orders');
        if (!res.ok) return;
        const waiting = (await res.json()).length;
        badge.textContent = waiting;
        badge.classList.toggle('d-none', waiting === 0);
    } catch (error) {
        console.error('Error counting takeaway orders:', error);
    }
}

async function refreshTables() {
    refreshPickupCount(); // both change whenever an order is placed, paid or cancelled
    try {
        const tableRes = await fetch('/api/backoffice/tables');
        if (tableRes.ok) {
            const data = await tableRes.json();
            allTables = data.map(d => ({
                id: d.id,
                ...d.customFields
            }));
            renderTables();
        }
    } catch (error) {
        console.error('Error refreshing tables:', error);
    }
}

async function renderTables() {
    const list = document.getElementById('tables-list');
    if (!list) return;

    try {
        const selectedTableId = selectedTable?.id || '';
        const res = await fetch(`/counter/fragments/tables?selectedTableId=${selectedTableId}`);
        if (res.ok) {
            list.innerHTML = await res.text();
            showTableCount();
        }
    } catch (error) {
        console.error('Error refreshing tables:', error);
    }
}

function renderAll() {
    renderCategories();
    renderProducts();
    updateCartUI();
}

async function renderCategories() {
    const sidebar = document.getElementById('categories-sidebar');
    if (!sidebar) return;

    try {
        const res = await fetch(`/counter/fragments/categories?selectedGroup=${selectedGroup}`);
        if (res.ok) {
            sidebar.innerHTML = await res.text();
        }
    } catch (e) {
        console.error(e);
    }
}

async function renderProducts(filter = '') {
    const grid = document.getElementById('products-grid');
    if (!grid) return;

    try {
        const res = await fetch(`/counter/fragments/products?groupId=${selectedGroup}&filter=${encodeURIComponent(filter)}`);
        if (res.ok) {
            grid.innerHTML = await res.text();
        }
    } catch (e) {
        console.error(e);
    }
}

function handleProductClick(productId) {
    addToCart(productId);
}

function addToCart(productId, quantity = 1, customizations = null) {
    const product = products.find(p => p.id === productId);
    if (!product) return;

    if (currentEditingItemIndex !== null) {
        cart[currentEditingItemIndex].quantity = quantity;
        cart[currentEditingItemIndex].customizations = customizations || [];
        currentEditingItemIndex = null;
    } else {
        const finalCustomizations = customizations || getDefaultCustomizations(product);
        cart.push({
            id: btoa(Math.random()).substring(0, 8),
            productId: product.id,
            itemId: product.id,
            name: product.name,
            price: product.price,
            quantity: quantity,
            customizations: finalCustomizations
        });
    }

    updateCartUI();
    if (window.innerWidth < 992) toggleMobileCart(); // Open cart on mobile when item added
}

function getDefaultCustomizations(product) {
    const defaults = [];
    if (!product.customizations) return defaults;

    product.customizations.forEach(custId => {
        const customization = allCustomizations.find(c => c.id === custId);
        if (!customization) return;

        customization.options.forEach((opt, idx) => {
            if (opt.isSelectedByDefault) {
                defaults.push({
                    id: opt.id || `${custId}-opt-${idx}`,
                    name: opt.name,
                    price: opt.price,
                    quantity: 1
                });
            }
        });
    });
    return defaults;
}

async function updateCartUI() {
    if (isTableMode) {
        renderTableCart();
        return;
    }

    const container = document.getElementById('cart-items');
    if (!container) return;

    try {
        const res = await fetch('/counter/fragments/order-cart', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-CSRF-TOKEN': csrfToken
            },
            body: JSON.stringify(cart)
        });
        if (res.ok) {
            container.innerHTML = await res.text();

            // Extract calculated subtotal, discount and total from headers or DOM dataset if available
            // Let's add header values from controller response to update calculations on the fly!
            const subtotalHeader = res.headers.get('X-Cart-Subtotal');
            const discountHeader = res.headers.get('X-Cart-Discount');
            const totalHeader = res.headers.get('X-Cart-Total');

            if (subtotalHeader && discountHeader && totalHeader) {
                const subVal = parseFloat(subtotalHeader);
                const discVal = parseFloat(discountHeader);
                const totVal = parseFloat(totalHeader);

                document.getElementById('cart-subtotal').textContent = formatPrice(subVal);
                document.getElementById('cart-discount').textContent = (discVal > 0 ? '-' : '') + formatPrice(discVal);
                document.getElementById('cart-total').textContent = formatPrice(totVal);
            } else {
                // Fallback to simple calculation if headers are missing
                const total = calculateTotal();
                document.getElementById('cart-subtotal').textContent = formatPrice(total);
                document.getElementById('cart-discount').textContent = formatPrice(0);
                document.getElementById('cart-total').textContent = formatPrice(total);
            }

            const mobileBadge = document.getElementById('mobile-cart-count');
            const totalItems = cart.reduce((sum, item) => sum + item.quantity, 0);
            if (mobileBadge) {
                mobileBadge.textContent = totalItems;
                mobileBadge.classList.toggle('d-none', totalItems === 0);
            }

            document.getElementById('btn-proceed-payment').disabled = cart.length === 0;
            document.getElementById('btn-assign-table').disabled = !selectedTable || cart.length === 0;
            document.getElementById('btn-assign-table').classList.remove('d-none');
            document.getElementById('btn-billing').classList.add('d-none');
        }
    } catch (e) {
        console.error(e);
    }
}

function calculateItemPrice(item) {
    let price = item.price;
    if (item.customizations) {
        item.customizations.forEach(c => {
            price += (c.price || 0);
        });
    }
    return price;
}

function editItem(index) {
    const item = cart[index];
    const product = products.find(p => p.id === item.productId);
    currentEditingItemIndex = index;
    showCustomizationModal(product, item.customizations);
}

function updateQty(index, delta) {
    cart[index].quantity += delta;
    if (cart[index].quantity <= 0) {
        cart.splice(index, 1);
    }
    updateCartUI();
}

function removeItem(index) {
    cart.splice(index, 1);
    updateCartUI();
}

function calculateTotal() {
    return cart.reduce((sum, item) => sum + (calculateItemPrice(item) * item.quantity), 0);
}

function formatPrice(val) {
    return parseFloat(val).toFixed(2);
}

async function showCustomizationModal(product, currentCustomizations = []) {
    currentProduct = product;
    document.getElementById('product-modal-name').textContent = `${t('customizerTitle')} ${product.name}`;
    const optionsContainer = document.getElementById('customization-options');
    if (!optionsContainer) return;

    if (!product.customizations) return;

    try {
        const selectedIds = currentCustomizations.map(c => c.id).join(',');
        const res = await fetch(`/counter/fragments/customization-options/${product.id}?selected=${selectedIds}`);
        if (res.ok) {
            optionsContainer.innerHTML = await res.text();
            new bootstrap.Modal(document.getElementById('customizationModal')).show();
        }
    } catch (e) {
        console.error(e);
    }
}

function saveCustomizations() {
    const customizations = [];
    const inputs = document.querySelectorAll('.customization-input:checked');

    inputs.forEach(input => {
        customizations.push({
            id: input.dataset.optionId || input.id,
            name: input.value,
            price: parseFloat(input.dataset.price || 0),
            quantity: 1
        });
    });

    const productId = currentProduct.id;
    addToCart(productId, 1, customizations);
    hideModal('customizationModal');
}

async function setTableStatus(tableId, status) {
    if (!tableId) return;
    try {
        const res = await fetch(`/counter/api/tables/${tableId}/status?status=${status}`, {
            method: 'POST',
            headers: { 'X-CSRF-TOKEN': csrfToken }
        });
        if (res.ok) {
            showToast(t(`messageTableStatus${status.charAt(0).toUpperCase() + status.slice(1).toLowerCase()}`));
            resetPOS();
        }
    } catch (error) {
        console.error('Error updating table status:', error);
    }
}

async function toggleTableQrTrust(tableId, targetTrusted) {
    if (!tableId) return;
    try {
        const res = await fetch(`/counter/api/tables/${tableId}/qr-trusted?trusted=${targetTrusted}`, {
            method: 'POST',
            headers: { 'X-CSRF-TOKEN': csrfToken }
        });
        if (res.ok) {
            await renderTables();
        } else {
            console.error('Failed to update table QR trust setting');
        }
    } catch (err) {
        console.error('Error updating table QR trust:', err);
    }
}

function showToast(message) {
    const toastContainer = document.getElementById('toastContainer');
    if (!toastContainer) return;

    fetch('/api/toast', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': csrfToken
        },
        body: JSON.stringify({ message: message })
    })
        .then(response => response.text())
        .then(html => {
            const div = document.createElement('div');
            div.innerHTML = html;
            const toast = div.firstElementChild;
            toastContainer.appendChild(toast);

            setTimeout(() => {
                if (toast.parentNode) toast.parentNode.removeChild(toast);
            }, 3000);
        })
        .catch(error => console.error('Error:', error));
}

function resetPaymentModal() {
    isFirstQuickCashClick = true;
    
    // Reset receipt type to Simple
    const simpleRadio = document.getElementById('type-simple');
    if (simpleRadio) {
        simpleRadio.checked = true;
        // Manually trigger change event to hide invoice details
        const event = new Event('change');
        simpleRadio.dispatchEvent(event);
    }
    
    // Clear customer fields
    ['pay-taxId', 'pay-name', 'pay-address'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.value = '';
    });
    const country = document.getElementById('pay-country');
    if (country) country.value = 'ES';
    
    // Hide details section just in case
    const details = document.getElementById('invoice-details');
    if (details) details.style.display = 'none';
}

function startRealtimeOrderUpdates() {
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

    const sseUrl = getTenantPrefix() + '/api/orders/live';

    function refreshCounterView() {
        renderTables();
        refreshPickupCount();
        if (selectedTable) {
            loadOrders();
        }
    }

    connectLiveUpdates(sseUrl, {
        poll: refreshCounterView,
        intervalMs: 5000,
        onUpdate: function(event) {
            try {
                const data = JSON.parse(event.data);
                if (data && data.orderNumber) {
                    showToast(`Order #${data.orderNumber} updated: ${data.status}`);
                }
                if (window.kitchenChime && (!data || !data.status || data.status === 'CREATED' || data.status === 'ACCEPTED')) {
                    window.kitchenChime.play();
                }
            } catch (e) {
                console.debug('SSE event parse error in counter', e);
            }
            refreshCounterView();
        }
    });
}
