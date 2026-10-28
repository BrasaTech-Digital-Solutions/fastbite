// Data storage
let groups = [];
let customizations = [];
let products = [];
let tables = [];
let allPaymentConfigs = [];
let selectedPaymentConfig = null;
let discounts = [];
let currentSection = 'groups';
let editingId = null;
let selectedProductsForGroup = [];
let selectedCustomizationsForProduct = [];
let i18n = {};
let optionSeq = 0;

// CSRF token helper
function getCsrfToken() {
    return document.querySelector('meta[name="_csrf"]')?.content || '';
}

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

async function fetchFragment(url, payload) {
    const response = await fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': getCsrfToken()
        },
        body: JSON.stringify(payload)
    });
    return await response.text();
}

// Load i18n data from the HTML
function loadI18n() {
    const el = document.getElementById('i18n-backoffice');
    if (!el) return;

    // Copy all dataset properties to i18n object
    for (const key in el.dataset) {
        i18n[key] = el.dataset[key];
    }
}

// Initialize
// Initialize
document.addEventListener('DOMContentLoaded', function () {
    loadI18n();
    loadData();
    loadDemoTemplates();

    // Setup form handlers
    document.getElementById('groupFormElement').addEventListener('submit', handleGroupSubmit);
    document.getElementById('customizationFormElement').addEventListener('submit', handleCustomizationSubmit);
    document.getElementById('customization-type')?.addEventListener('change', updateCustomizationTypeVisibility);
    document.getElementById('quick-customization-type')?.addEventListener('change', updateQuickCustomizationTypeVisibility);
    document.getElementById('productFormElement').addEventListener('submit', handleProductSubmit);
    document.getElementById('tableFormElement').addEventListener('submit', handleTableSubmit);

    // Payment config doesn't use standard submit if it's dynamic, 
    // but in my fragment I used a form with id paymentConfigForm
});

async function handlePaymentConfigSubmit(e) {
    if (e) e.preventDefault();
    const modes = [];
    if (document.getElementById('mode-cash').checked) modes.push('CASH');
    if (document.getElementById('mode-card').checked) modes.push('CARD');

    const images = Array.from(document.querySelectorAll('input[name="moneyImages"]')).map(i => i.value);

    try {
        const res = await fetch('/api/backoffice/payment', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-CSRF-TOKEN': getCsrfToken()
            },
            body: JSON.stringify({ activeModes: modes, moneyImages: images })
        });
        if (res.ok) {
            await fbNotice({
                title: i18n.paymentTitle || 'Payment Configuration',
                message: i18n.paymentSavedSuccess || 'Payment configuration saved successfully!',
                okText: i18n.buttonOk || 'OK'
            });
            loadData();
        }
    } catch (error) {
        console.error('Error saving payment config:', error);
    }
}

async function fetchJson(url) {
    const response = await fetch(url);
    return response.ok ? response.json() : null;
}

// Load data from backend
async function loadData() {
    try {
        // All requests in parallel; a failed one keeps the previously loaded list.
        const [productsData, customizationsData, groupsData, tablesData, discountsData, paymentData] = await Promise.all([
            fetchJson('/api/backoffice/products'),
            fetchJson('/api/backoffice/customizations'),
            fetchJson('/api/backoffice/groups'),
            fetchJson('/api/backoffice/tables'),
            fetchJson('/api/backoffice/discounts'),
            fetchJson('/api/backoffice/payment/all')
        ]);

        if (productsData) {
            products = productsData.map(data => ({
                id: data.id,
                name: data.customFields.name,
                price: data.customFields.price,
                description: data.customFields.description,
                image: data.customFields.image,
                customizations: data.customFields.customizations || [],
                active: data.customFields.active !== false,
                vatRate: data.customFields.vatRate,
                // null: not declared yet; []: none of the 14
                allergens: data.customFields.allergens ?? null
            }));
        }

        if (customizationsData) {
            customizations = customizationsData.map(data => ({
                id: data.id,
                name: data.customFields.name,
                type: data.customFields.type,
                options: data.customFields.options || [],
                usageCount: data.customFields.usageCount || 0
            }));
        }

        if (groupsData) {
            groups = groupsData.map(data => ({
                id: data.id,
                name: data.customFields.name,
                description: data.customFields.description,
                icon: data.customFields.icon,
                products: data.customFields.products || []
            }));
        }

        if (tablesData) {
            tables = tablesData.map(data => ({
                id: data.id,
                name: data.customFields.name,
                seats: data.customFields.seats,
                status: data.customFields.status,
                active: data.customFields.active
            }));
        }

        if (discountsData) {
            discounts = discountsData.map(data => ({
                id: data.id,
                name: data.customFields.name,
                scope: data.customFields.scope,
                type: data.customFields.type,
                value: data.customFields.value,
                minSubtotal: data.customFields.minSubtotal,
                couponCode: data.customFields.couponCode,
                active: data.customFields.active,
                accumulative: data.customFields.accumulative,
                applyOnCounter: data.customFields.applyOnCounter
            }));
        }

        if (paymentData) {
            allPaymentConfigs = paymentData;
            // Default to first or active one if not selected
            if (!selectedPaymentConfig && allPaymentConfigs.length > 0) {
                selectedPaymentConfig = allPaymentConfigs.find(c => c.active) || allPaymentConfigs[0];
            } else if (selectedPaymentConfig) {
                // Refresh the selected one from the list
                selectedPaymentConfig = allPaymentConfigs.find(c => c.id === selectedPaymentConfig.id) || allPaymentConfigs[0];
            }
        }

        renderAll();
    } catch (error) {
        console.error('Error loading data:', error);
    }
}

// Render all sections
function renderAll() {
    renderGroupsList();
    renderCustomizationsList();
    renderProductsList();
    renderTablesList();
    renderPaymentConfig();
    renderDiscountsList();
    renderQuickViews();
    updateCounts();
}

// Show section
function showSection(section) {
    currentSection = section;

    // Hide all sections
    document.querySelectorAll('.section-content').forEach(el => el.style.display = 'none');

    // Show selected section
    document.getElementById(`${section}-section`).style.display = 'block';

    // Update navigation
    document.querySelectorAll('.list-group-item').forEach(el => el.classList.remove('active'));
    document.getElementById(`nav-${section}`).classList.add('active');

    // Update quick view
    document.querySelectorAll('.quick-view-section').forEach(el => el.style.display = 'none');
    const quickView = document.getElementById(`${section}-quick-view`);
    if (quickView) quickView.style.display = 'block';
}

// Toggle side view
function toggleSideView() {
    const sideView = document.getElementById('side-view');
    const mainView = sideView.previousElementSibling;

    if (sideView.style.display === 'none') {
        sideView.style.display = 'block';
        mainView.classList.remove('col-md-10');
        mainView.classList.add('col-md-7');
    } else {
        sideView.style.display = 'none';
        mainView.classList.remove('col-md-7');
        mainView.classList.add('col-md-10');
    }
}

// Create new (based on current section)
function createNew() {
    if (currentSection === 'groups') showGroupForm();
    else if (currentSection === 'customizations') showCustomizationForm();
    else if (currentSection === 'products') showProductForm();
    else if (currentSection === 'tables') showTableForm();
    else if (currentSection === 'discounts') showDiscountForm();
}

// Update counts
function updateCounts() {
    document.getElementById('groups-count').textContent = groups.length;
    document.getElementById('customizations-count').textContent = customizations.length;
    document.getElementById('products-count').textContent = products.length;
    updateAllergensBanner();
    const tablesCount = document.getElementById('tables-count');
    if (tablesCount) tablesCount.textContent = tables.length;
    const discountsCount = document.getElementById('discounts-count');
    if (discountsCount) discountsCount.textContent = discounts.length;
}

// Dishes on sale without allergen information: guests are told to ask the staff
function updateAllergensBanner() {
    const banner = document.getElementById('allergens-missing-banner');
    if (!banner) return;
    const missing = products.filter(p => p.active && (p.allergens === null || p.allergens === undefined)).length;
    banner.hidden = missing === 0;
    document.getElementById('allergens-missing-text').textContent = banner.dataset.template.replace('{0}', missing);
}

function setProductAllergens(allergens) {
    const declared = Array.isArray(allergens);
    document.querySelectorAll('input[name="product-allergen"]').forEach(box => {
        box.checked = declared && allergens.includes(box.value);
    });
    document.getElementById('product-allergens-none').checked = declared && allergens.length === 0;
    updateProductAllergensState();
}

function productAllergens() {
    if (document.getElementById('product-allergens-none').checked) return [];
    const chosen = [...document.querySelectorAll('input[name="product-allergen"]:checked')].map(box => box.value);
    return chosen.length ? chosen : null;
}

function updateProductAllergensState() {
    const state = document.getElementById('product-allergens-state');
    if (state) state.hidden = productAllergens() !== null;
}

document.addEventListener('change', event => {
    if (event.target.name === 'product-allergen' && event.target.checked) {
        document.getElementById('product-allergens-none').checked = false;
    }
    if (event.target.id === 'product-allergens-none' && event.target.checked) {
        document.querySelectorAll('input[name="product-allergen"]').forEach(box => box.checked = false);
    }
    if (event.target.name === 'product-allergen' || event.target.id === 'product-allergens-none') {
        updateProductAllergensState();
    }
});

// Cancel form
function cancelForm() {
    document.getElementById('group-form').style.display = 'none';
    document.getElementById('customization-form').style.display = 'none';
    document.getElementById('product-form').style.display = 'none';
    const preview = document.getElementById('product-image-preview');
    if (preview) preview.style.display = 'none';
    const tableList = document.getElementById('tables-list');
    const tableFormContainer = document.getElementById('table-form-container');
    if (tableList) tableList.style.display = 'block';
    if (tableFormContainer) tableFormContainer.innerHTML = '';
    const discountList = document.getElementById('discounts-list');
    const discountFormContainer = document.getElementById('discount-form-container');
    if (discountList) discountList.style.display = 'block';
    if (discountFormContainer) discountFormContainer.innerHTML = '';
    editingId = null;
}

// === GROUPS MANAGEMENT ===

async function renderGroupsList() {
    const container = document.getElementById('groups-list');
    container.innerHTML = await fetchFragment('/api/backoffice/fragments/groups-list', groups);
}

function showGroupForm(groupId = null) {
    editingId = groupId;
    const form = document.getElementById('group-form');
    const title = document.getElementById('group-form-title');

    if (groupId) {
        const group = groups.find(g => g.id === groupId);
        title.textContent = 'Edit Group';
        document.getElementById('group-id').value = group.id;
        document.getElementById('group-name').value = group.name;
        document.getElementById('group-description').value = group.description || '';
        document.getElementById('group-icon').value = group.icon || '';
        selectedProductsForGroup = [...group.products];
        updateGroupProductsList();
    } else {
        title.textContent = 'Create New Group';
        document.getElementById('groupFormElement').reset();
        selectedProductsForGroup = [];
        updateGroupProductsList();
    }

    form.style.display = 'block';
    form.scrollIntoView({ behavior: 'smooth' });
}

async function handleGroupSubmit(e) {
    e.preventDefault();

    const groupData = {
        name: document.getElementById('group-name').value,
        description: document.getElementById('group-description').value,
        icon: document.getElementById('group-icon').value,
        products: selectedProductsForGroup
    };

    try {
        let response;
        if (editingId) {
            response = await fetch(`/api/backoffice/groups/${editingId}`, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json',
                    'X-CSRF-TOKEN': getCsrfToken()
                },
                body: JSON.stringify(groupData)
            });
        } else {
            response = await fetch('/api/backoffice/groups', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'X-CSRF-TOKEN': getCsrfToken()
                },
                body: JSON.stringify(groupData)
            });
        }

        if (response.ok) {
            await loadData();
            cancelForm();
            await loadData();
            cancelForm();
            showToast(i18n.groupsSaveSuccess);
        } else {
            showToast(i18n.groupsSaveError, 'error');
        }
    } catch (error) {
        console.error('Error saving group:', error);
        showToast('Error saving group', 'error');
    }
}

function editGroup(id) {
    showGroupForm(id);
}

async function deleteGroup(id) {
    if (await fbConfirm({
        message: i18n.groupsDeleteConfirm,
        confirmText: i18n.buttonDelete || 'Delete',
        cancelText: i18n.buttonCancel || 'Cancel',
        danger: true
    })) {
        try {
            const response = await fetch(`/api/backoffice/groups/${id}`, {
                method: 'DELETE',
                headers: {
                    'X-CSRF-TOKEN': getCsrfToken()
                }
            });

            if (response.ok) {
                await loadData();
                showToast('Group deleted successfully!');
            } else {
                showToast('Error deleting group', 'error');
            }
        } catch (error) {
            console.error('Error deleting group:', error);
            showToast('Error deleting group', 'error');
        }
    }
}

async function updateGroupProductsList() {
    const container = document.getElementById('group-products-list');
    const selectedProducts = selectedProductsForGroup.map(id => products.find(p => p.id === id)).filter(p => p);
    container.innerHTML = await fetchFragment('/api/backoffice/fragments/group-products-badges', selectedProducts);
}

function removeProductFromGroup(productId) {
    selectedProductsForGroup = selectedProductsForGroup.filter(id => id !== productId);
    updateGroupProductsList();
}

async function openProductSelector(context) {
    // Populate product selector modal
    const container = document.getElementById('product-selector-list');
    container.innerHTML = await fetchFragment('/api/backoffice/fragments/selector-list', { items: products, selectedIds: selectedProductsForGroup });

    // Show modal
    const modal = new bootstrap.Modal(document.getElementById('productSelectorModal'));
    modal.show();
}

function confirmProductSelection() {
    // Get selected products
    const checkboxes = document.querySelectorAll('#product-selector-list input[type="checkbox"]:checked');
    selectedProductsForGroup = Array.from(checkboxes).map(cb => cb.value);
    updateGroupProductsList();

    // Close modal
    const modal = bootstrap.Modal.getInstance(document.getElementById('productSelectorModal'));
    modal.hide();
}

// === CUSTOMIZATIONS MANAGEMENT ===

async function renderCustomizationsList() {
    const container = document.getElementById('customizations-list');
    container.innerHTML = await fetchFragment('/api/backoffice/fragments/customizations-list', customizations);
}

function updateCustomizationTypeVisibility() {
    const type = document.getElementById('customization-type')?.value;
    const isQuantity = type === 'quantity';
    const container = document.getElementById('customization-options-container');
    if (!container) return;
    const cards = container.querySelectorAll('.card');
    cards.forEach(card => {
        const switchCol = card.querySelector('.opt-selected-default-col');
        if (switchCol) {
            switchCol.style.display = isQuantity ? 'none' : '';
        }
        const defInput = card.querySelector('.opt-defaultValue');
        if (defInput) {
            if (isQuantity) {
                defInput.setAttribute('min', '0');
                defInput.setAttribute('max', '1');
                if (parseInt(defInput.value, 10) > 1) {
                    defInput.value = '1';
                }
            } else {
                defInput.removeAttribute('max');
                defInput.setAttribute('min', '0');
            }
        }
    });
}

function showCustomizationForm(customId = null) {
    editingId = customId;
    const form = document.getElementById('customization-form');
    const title = document.getElementById('customization-form-title');

    if (customId) {
        const custom = customizations.find(c => c.id === customId);
        title.textContent = i18n.customizationsEditTitle || 'Edit Customization';
        document.getElementById('customization-id').value = custom.id;
        document.getElementById('customization-name').value = custom.name;
        document.getElementById('customization-type').value = custom.type;
        renderCustomizationOptions(custom.options);
    } else {
        title.textContent = i18n.customizationsCreateTitle || 'Create New Customization';
        document.getElementById('customizationFormElement').reset();
        renderCustomizationOptions([]);
    }

    form.style.display = 'block';
    form.scrollIntoView({ behavior: 'smooth' });
}

async function renderCustomizationOptions(options = []) {
    const container = document.getElementById('customization-options-container');

    if (options.length === 0) {
        container.innerHTML = `<p class="text-muted">${i18n.customizationsOptionsEmptyHint}</p>`;
        return;
    }

    let html = '';
    for (let i = 0; i < options.length; i++) {
        html += await fetchFragment('/api/backoffice/fragments/customization-option', { opt: options[i], index: optionSeq++ });
    }
    container.innerHTML = html;
    updateCustomizationTypeVisibility();
}

async function addCustomizationOption() {
    const container = document.getElementById('customization-options-container');
    const count = container.querySelectorAll('.card').length;

    // Remove placeholder text if this is the first option
    if (count === 0) {
        const placeholder = container.querySelector('p.text-muted');
        if (placeholder) {
            placeholder.remove();
        }
    }

    const optionHtml = await fetchFragment('/api/backoffice/fragments/customization-option', { opt: null, index: optionSeq++ });
    container.insertAdjacentHTML('beforeend', optionHtml);
    updateCustomizationTypeVisibility();
}

function removeCustomizationOption(target) {
    const card = target?.closest('.card');
    if (card) {
        card.remove();
    }
    const container = document.getElementById('customization-options-container');
    if (container && container.querySelectorAll('.card').length === 0) {
        container.innerHTML = `<p class="text-muted">${i18n.customizationsOptionsEmptyHint}</p>`;
    }
}

async function handleCustomizationSubmit(e) {
    e.preventDefault();

    const customizationType = document.getElementById('customization-type').value;
    const isQuantity = customizationType === 'quantity';

    // Collect options
    const optionsContainer = document.getElementById('customization-options-container');
    const optionElements = optionsContainer.querySelectorAll('.card');
    const options = Array.from(optionElements).map((el) => {
        const name = el.querySelector('.opt-name')?.value || '';
        const price = el.querySelector('.opt-price')?.value || '0';
        const defaultValue = el.querySelector('.opt-defaultValue')?.value || '1';
        const isSelectedByDefault = isQuantity
            ? (parseInt(defaultValue, 10) >= 1)
            : (el.querySelector('.opt-isSelectedByDefault')?.checked || false);
        const allergens = [...el.querySelectorAll('input[data-option-allergen]:checked')].map(box => box.value);

        return {
            name,
            price: parseFloat(price),
            defaultValue: parseInt(defaultValue, 10),
            isSelectedByDefault,
            allergens
        };
    }).filter(opt => opt.name.trim() !== '');

    if (options.length === 0) {
        showToast(i18n.customizationsValidationError || 'Please fill in all fields', 'error');
        return;
    }

    const customizationData = {
        name: document.getElementById('customization-name').value,
        type: customizationType,
        options: options,
        usageCount: 0
    };

    try {
        let response;
        if (editingId) {
            response = await fetch(`/api/backoffice/customizations/${editingId}`, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json',
                    'X-CSRF-TOKEN': getCsrfToken()
                },
                body: JSON.stringify(customizationData)
            });
        } else {
            response = await fetch('/api/backoffice/customizations', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'X-CSRF-TOKEN': getCsrfToken()
                },
                body: JSON.stringify(customizationData)
            });
        }

        if (response.ok) {
            await loadData();
            cancelForm();
            showToast(i18n.customizationsSaveSuccess);
        } else {
            const errorMsg = response.status === 400 ? await response.text() : null;
            showToast(errorMsg || i18n.customizationsSaveError, 'error');
        }
    } catch (error) {
        console.error('Error saving customization:', error);
        showToast(i18n.customizationsSaveError, 'error');
    }
}

function editCustomization(id) {
    showCustomizationForm(id);
}

async function deleteCustomization(id) {
    if (await fbConfirm({
        message: i18n.customizationsDeleteConfirm,
        confirmText: i18n.buttonDelete || 'Delete',
        cancelText: i18n.buttonCancel || 'Cancel',
        danger: true
    })) {
        try {
            const response = await fetch(`/api/backoffice/customizations/${id}`, {
                method: 'DELETE',
                headers: {
                    'X-CSRF-TOKEN': getCsrfToken()
                }
            });

            if (response.ok) {
                await loadData();
                showToast(i18n.customizationsDeleteSuccess);
            } else {
                showToast(i18n.customizationsDeleteError, 'error');
            }
        } catch (error) {
            console.error('Error deleting customization:', error);
            showToast(i18n.customizationsDeleteError, 'error');
        }
    }
}

// === PRODUCTS MANAGEMENT ===

async function renderProductsList() {
    const container = document.getElementById('products-list');
    container.innerHTML = await fetchFragment('/api/backoffice/fragments/products-list', products);
}

function showProductForm(productId = null) {
    editingId = productId;
    const form = document.getElementById('product-form');
    const title = document.getElementById('product-form-title');

    if (productId) {
        const product = products.find(p => p.id === productId);
        title.textContent = 'Edit Product';
        document.getElementById('product-id').value = product.id;
        document.getElementById('product-name').value = product.name;
        document.getElementById('product-price').value = product.price;
        // Empty: the location's default rate
        document.getElementById('product-vat').value = product.vatRate != null ? Number(product.vatRate).toFixed(2) : '';
        setProductAllergens(product.allergens);
        document.getElementById('product-description').value = product.description;
        document.getElementById('product-image').value = product.image || '';
        const preview = document.getElementById('product-image-preview');
        const previewImg = document.getElementById('product-image-preview-img');
        if (product.image) {
            if (previewImg) previewImg.src = product.image;
            if (preview) preview.style.display = 'block';
        } else {
            if (preview) preview.style.display = 'none';
        }
        selectedCustomizationsForProduct = product.customizations ? [...product.customizations] : [];
        updateProductCustomizationsList();
    } else {
        title.textContent = 'Create New Product';
        document.getElementById('productFormElement').reset();
        document.getElementById('product-vat').value = '';
        setProductAllergens(null);
        const preview = document.getElementById('product-image-preview');
        if (preview) preview.style.display = 'none';
        selectedCustomizationsForProduct = [];
        updateProductCustomizationsList();
    }

    form.style.display = 'block';
    form.scrollIntoView({ behavior: 'smooth' });
}

async function handleProductSubmit(e) {
    e.preventDefault();

    const productData = {
        name: document.getElementById('product-name').value,
        price: parseFloat(document.getElementById('product-price').value),
        description: document.getElementById('product-description').value,
        image: document.getElementById('product-image').value,
        customizations: selectedCustomizationsForProduct,
        active: document.getElementById('product-active').checked,
        vatRate: document.getElementById('product-vat').value ? Number(document.getElementById('product-vat').value) : null,
        allergens: productAllergens()
    };

    try {
        let response;
        if (editingId) {
            response = await fetch(`/api/backoffice/products/${editingId}`, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json',
                    'X-CSRF-TOKEN': getCsrfToken()
                },
                body: JSON.stringify(productData)
            });
        } else {
            response = await fetch('/api/backoffice/products', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'X-CSRF-TOKEN': getCsrfToken()
                },
                body: JSON.stringify(productData)
            });
        }

        if (response.ok) {
            await loadData();
            cancelForm();
            showToast(i18n.productsSaveSuccess);
        } else {
            showToast(i18n.productsSaveError, 'error');
        }
    } catch (error) {
        console.error('Error saving product:', error);
        showToast(i18n.productsSaveError, 'error');
    }
}

function editProduct(id) {
    showProductForm(id);
}

async function deleteProduct(id) {
    if (await fbConfirm({
        message: i18n.productsDeleteConfirm,
        confirmText: i18n.buttonDelete || 'Delete',
        cancelText: i18n.buttonCancel || 'Cancel',
        danger: true
    })) {
        try {
            const response = await fetch(`/api/backoffice/products/${id}`, {
                method: 'DELETE',
                headers: {
                    'X-CSRF-TOKEN': getCsrfToken()
                }
            });

            if (response.ok) {
                await loadData();
                showToast(i18n.productsDeleteSuccess);
            } else {
                showToast(i18n.productsDeleteError, 'error');
            }
        } catch (error) {
            console.error('Error deleting product:', error);
            showToast(i18n.productsDeleteError, 'error');
        }
    }
}

async function updateProductCustomizationsList() {
    const container = document.getElementById('product-customizations-list');
    const selectedCustomizations = selectedCustomizationsForProduct.map(id => customizations.find(c => c.id === id)).filter(c => c);
    container.innerHTML = await fetchFragment('/api/backoffice/fragments/product-customizations-badges', selectedCustomizations);
}

function removeCustomizationFromProduct(customizationId) {
    selectedCustomizationsForProduct = selectedCustomizationsForProduct.filter(id => id !== customizationId);
    updateProductCustomizationsList();
}

async function openCustomizationSelector() {
    // Populate customization selector modal
    const container = document.getElementById('customization-selector-list');
    container.innerHTML = await fetchFragment('/api/backoffice/fragments/selector-list', { items: customizations, selectedIds: selectedCustomizationsForProduct });

    // Show modal
    const modal = new bootstrap.Modal(document.getElementById('customizationSelectorModal'));
    modal.show();
}

function confirmCustomizationSelection() {
    // Get selected customizations
    const checkboxes = document.querySelectorAll('#customization-selector-list input[type="checkbox"]:checked');
    selectedCustomizationsForProduct = Array.from(checkboxes).map(cb => cb.value);
    updateProductCustomizationsList();

    // Close modal
    const modal = bootstrap.Modal.getInstance(document.getElementById('customizationSelectorModal'));
    modal.hide();
}

function updateQuickCustomizationTypeVisibility() {
    const type = document.getElementById('quick-customization-type')?.value;
    const wrapper = document.getElementById('quick-customization-included-wrapper');
    if (wrapper) {
        wrapper.style.display = type === 'quantity' ? 'block' : 'none';
    }
}

function showCustomizationFormPopup() {
    updateQuickCustomizationTypeVisibility();
    const modal = new bootstrap.Modal(document.getElementById('quickCustomizationModal'));
    modal.show();
}

async function saveQuickCustomization() {
    const name = document.getElementById('quick-customization-name').value.trim();
    const type = document.getElementById('quick-customization-type').value;
    const optName = document.getElementById('quick-customization-opt-name').value.trim();
    const optPrice = document.getElementById('quick-customization-opt-price').value || '0';

    if (!name || !type || !optName) {
        showToast(i18n.customizationsValidationError || 'Please fill in all fields', 'error');
        return;
    }

    const isQuantity = type === 'quantity';
    const defaultValue = isQuantity
        ? (document.getElementById('quick-customization-opt-default').checked ? 1 : 0)
        : 1;
    const isSelectedByDefault = isQuantity ? (defaultValue >= 1) : false;

    const customizationData = {
        name: name,
        type: type,
        options: [{
            name: optName,
            price: parseFloat(optPrice),
            defaultValue: defaultValue,
            isSelectedByDefault: isSelectedByDefault,
            allergens: []
        }],
        usageCount: 0
    };

    try {
        const response = await fetch('/api/backoffice/customizations', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-CSRF-TOKEN': getCsrfToken()
            },
            body: JSON.stringify(customizationData)
        });

        if (response.ok) {
            const created = await response.json();
            await loadData();

            // Select for the product being edited
            if (created && created.id && !selectedCustomizationsForProduct.includes(created.id)) {
                selectedCustomizationsForProduct.push(created.id);
                updateProductCustomizationsList();
            }

            // Close modal
            const modal = bootstrap.Modal.getInstance(document.getElementById('quickCustomizationModal'));
            if (modal) modal.hide();

            // Clear form
            document.getElementById('quick-customization-name').value = '';
            document.getElementById('quick-customization-type').value = 'radio';
            document.getElementById('quick-customization-opt-name').value = '';
            document.getElementById('quick-customization-opt-price').value = '0.00';
            document.getElementById('quick-customization-opt-default').checked = true;
            updateQuickCustomizationTypeVisibility();

            showToast(i18n.customizationsSaveSuccess);
        } else {
            const errorMsg = response.status === 400 ? await response.text() : null;
            showToast(errorMsg || i18n.customizationsSaveError, 'error');
        }
    } catch (error) {
        console.error('Error creating customization:', error);
        showToast(i18n.customizationsSaveError, 'error');
    }
}

// === QUICK VIEWS ===

async function renderQuickViews() {
    // Groups quick view
    const groupsQuickView = document.getElementById('groups-quick-view');
    groupsQuickView.innerHTML = await fetchFragment('/api/backoffice/fragments/quick-view', { data: groups, type: 'groups' });

    // Customizations quick view
    const customizationsQuickView = document.getElementById('customizations-quick-view');
    customizationsQuickView.innerHTML = await fetchFragment('/api/backoffice/fragments/quick-view', { data: customizations, type: 'customizations' });

    // Products quick view
    const productsQuickView = document.getElementById('products-quick-view');
    productsQuickView.innerHTML = await fetchFragment('/api/backoffice/fragments/quick-view', { data: products, type: 'products' });
}

// === UTILITY FUNCTIONS ===

function showToast(message, type = 'success') {
    const toastElement = document.getElementById('notification-toast');
    const toastBody = document.getElementById('toast-message');

    // Set the message
    toastBody.textContent = message;

    // Set the color based on type
    toastElement.classList.remove('bg-success', 'bg-danger', 'bg-warning', 'text-white');
    if (type === 'success') {
        toastElement.classList.add('bg-success', 'text-white');
    } else if (type === 'error') {
        toastElement.classList.add('bg-danger', 'text-white');
    } else if (type === 'warning') {
        toastElement.classList.add('bg-warning', 'text-dark');
    }

    // Show the toast
    const toast = new bootstrap.Toast(toastElement, {
        autohide: true,
        delay: 2000  // Auto-dismiss after 2 seconds
    });
    toast.show();
}

// === IMAGE MANAGEMENT ===

let imageSelectionCallback = null;

/**
 * Open the image selector modal
 * @param {Function} callback Optional callback to handle selection
 */
function openImageSelector(callback = null) {
    imageSelectionCallback = callback;
    const modal = new bootstrap.Modal(document.getElementById('imageSelectorModal'));

    // Load images when modal opens
    loadSystemImages();
    loadUserImages();

    // Setup upload form handler
    const uploadForm = document.getElementById('imageUploadForm');
    if (uploadForm) {
        uploadForm.onsubmit = handleImageUpload;
    }

    // Setup file input preview
    const fileInput = document.getElementById('imageFile');
    if (fileInput) {
        fileInput.onchange = previewUploadImage;
    }

    modal.show();
}

/**
 * Load system images from classpath
 */
async function loadSystemImages() {
    const container = document.getElementById('system-images-container');

    try {
        const response = await fetch('/api/backoffice/images/system');
        if (!response.ok) {
            container.innerHTML = '<div class="col-12 text-center text-muted py-5"><p>No system images found</p></div>';
            return;
        }

        const imagesByFolder = await response.json();
        renderImageGallery(imagesByFolder, container, 'system');
    } catch (error) {
        console.error('Error loading system images:', error);
        container.innerHTML = '<div class="col-12 text-center text-danger py-5"><p>Error loading system images</p></div>';
    }
}

/**
 * Load user-uploaded images
 */
async function loadUserImages() {
    const container = document.getElementById('user-images-container');

    try {
        const response = await fetch('/api/backoffice/images/user');
        if (!response.ok) {
            container.innerHTML = '<div class="col-12 text-center text-muted py-5"><p>No uploaded images yet</p></div>';
            return;
        }

        const imagesByFolder = await response.json();
        if (Object.keys(imagesByFolder).length === 0) {
            container.innerHTML = '<div class="col-12 text-center text-muted py-5"><p>No uploaded images yet. Upload your first image!</p></div>';
            return;
        }

        renderImageGallery(imagesByFolder, container, 'user');
    } catch (error) {
        console.error('Error loading user images:', error);
        container.innerHTML = '<div class="col-12 text-center text-danger py-5"><p>Error loading user images</p></div>';
    }
}

/**
 * Render image gallery grouped by folders
 */
async function renderImageGallery(imagesByFolder, container, type) {
    // Pre-format sizes for Thymeleaf
    const formattedImagesByFolder = {};
    for (const [folder, images] of Object.entries(imagesByFolder)) {
        formattedImagesByFolder[folder] = images.map(img => ({
            ...img,
            formattedSize: formatFileSize(img.size)
        }));
    }

    container.innerHTML = await fetchFragment('/api/backoffice/fragments/image-gallery', { imagesByFolder: formattedImagesByFolder, type: type });
}

/**
 * Select an image
 */
function selectImage(imageUrl) {
    if (imageSelectionCallback) {
        imageSelectionCallback(imageUrl);
    } else {
        // Default product form behavior
        document.getElementById('product-image').value = imageUrl;

        const preview = document.getElementById('product-image-preview');
        const previewImg = document.getElementById('product-image-preview-img');
        if (previewImg) previewImg.src = imageUrl;
        if (preview) preview.style.display = 'block';
    }

    // Close modal
    const modal = bootstrap.Modal.getInstance(document.getElementById('imageSelectorModal'));
    if (modal) modal.hide();

    showToast('Image selected successfully!', 'success');
}

/**
 * Preview image before upload
 */
function previewUploadImage(event) {
    const file = event.target.files[0];
    if (file) {
        const reader = new FileReader();
        reader.onload = function (e) {
            document.getElementById('imagePreview').src = e.target.result;
            document.getElementById('imagePreviewContainer').style.display = 'block';
        };
        reader.readAsDataURL(file);
    }
}

/**
 * Handle image upload
 */
async function handleImageUpload(event) {
    event.preventDefault();

    const fileInput = document.getElementById('imageFile');
    const folderInput = document.getElementById('imageFolder');

    if (!fileInput.files || fileInput.files.length === 0) {
        showToast('Please select a file', 'error');
        return;
    }

    const formData = new FormData();
    formData.append('file', fileInput.files[0]);
    if (folderInput.value.trim()) {
        formData.append('folder', folderInput.value.trim());
    }

    try {
        const response = await fetch('/api/backoffice/images/upload', {
            method: 'POST',
            headers: {
                'X-CSRF-TOKEN': getCsrfToken()
            },
            body: formData
        });

        if (!response.ok) {
            const error = await response.json();
            showToast(error.error || 'Upload failed', 'error');
            return;
        }

        const imageInfo = await response.json();

        // Select the newly uploaded image
        selectImage(imageInfo.url);

        // Reset form
        document.getElementById('imageUploadForm').reset();
        document.getElementById('imagePreviewContainer').style.display = 'none';

        // Reload user images
        loadUserImages();

        showToast('Image uploaded successfully!', 'success');
    } catch (error) {
        console.error('Error uploading image:', error);
        showToast('Error uploading image', 'error');
    }
}

/**
 * Format file size for display
 */
function formatFileSize(bytes) {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i];
}

// === TRANSLATION MANAGEMENT ===

/**
 * Navigate to group translations page
 */
function manageGroupTranslations(groupId) {
    window.location.assign(getTenantPrefix() + `/backoffice/translations/groups/${groupId}`);
}

/**
 * Navigate to product translations page
 */
function manageProductTranslations(productId) {
    window.location.assign(getTenantPrefix() + `/backoffice/translations/products/${productId}`);
}

/**
 * Navigate to customization translations page
 */
function manageCustomizationTranslations(customizationId) {
    window.location.assign(getTenantPrefix() + `/backoffice/translations/customizations/${customizationId}`);
}

/**
 * Navigate to table translations page
 */
function manageTableTranslations(tableId) {
    window.location.assign(getTenantPrefix() + `/backoffice/translations/tables/${tableId}`);
}

// === TABLES MANAGEMENT ===

/** Printable QR codes: one table's, or all active tables' when no id is given. */
function openTableQr(tableId) {
    const url = getTenantPrefix() + '/backoffice/tables/qr-codes' + (tableId ? `?table=${encodeURIComponent(tableId)}` : '');
    window.open(url, '_blank');
}

async function renderTablesList() {
    const list = document.getElementById('tables-list');
    if (!list) return;
    list.innerHTML = await fetchFragment('/api/backoffice/fragments/tables-list', tables);
}

async function showTableForm(tableId = null) {
    editingId = tableId;
    const container = document.getElementById('table-form-container');
    const list = document.getElementById('tables-list');

    // Hide list while editing
    list.style.display = 'none';

    let table = null;
    if (tableId) {
        table = tables.find(t => t.id === tableId);
    }

    container.innerHTML = await fetchFragment('/api/backoffice/fragments/table-form', {
        table: table,
        statuses: ['AVAILABLE', 'OCCUPIED', 'BILLING']
    });

    // Populate fields if editing
    if (table) {
        document.getElementById('table-form-title').textContent = 'Edit Table';
        document.getElementById('table-id').value = table.id;
        document.getElementById('table-name').value = table.name;
        document.getElementById('table-seats').value = table.seats;
        document.getElementById('table-status').value = table.status;
        document.getElementById('table-active').checked = table.active;
    }

    // Add submit listener
    const form = document.getElementById('tableFormElement');
    if (form) {
        form.addEventListener('submit', handleTableSubmit);
    }

    container.scrollIntoView({ behavior: 'smooth' });
}

async function handleTableSubmit(e) {
    e.preventDefault();

    const id = document.getElementById('table-id').value;
    const tableData = {
        name: document.getElementById('table-name').value,
        seats: parseInt(document.getElementById('table-seats').value),
        status: document.getElementById('table-status').value,
        active: document.getElementById('table-active').checked
    };

    try {
        const url = id ? `/api/backoffice/tables/${id}` : '/api/backoffice/tables';
        const method = id ? 'PUT' : 'POST';

        const response = await fetch(url, {
            method: method,
            headers: {
                'Content-Type': 'application/json',
                'X-CSRF-TOKEN': getCsrfToken()
            },
            body: JSON.stringify(tableData)
        });

        if (response.ok) {
            await loadData();
            cancelForm();
            showToast('Table saved successfully!');
        } else {
            showToast('Error saving table', 'error');
        }
    } catch (error) {
        console.error('Error saving table:', error);
        showToast('Error saving table', 'error');
    }
}

function editTable(id) {
    showTableForm(id);
}

async function deleteTable(id) {
    if (await fbConfirm({
        message: i18n.tablesDeleteConfirm || 'Are you sure you want to delete this table?',
        confirmText: i18n.buttonDelete || 'Delete',
        cancelText: i18n.buttonCancel || 'Cancel',
        danger: true
    })) {
        try {
            const response = await fetch(`/api/backoffice/tables/${id}`, {
                method: 'DELETE',
                headers: {
                    'X-CSRF-TOKEN': getCsrfToken()
                }
            });

            if (response.ok) {
                await loadData();
                showToast('Table deleted successfully!');
            } else {
                showToast('Error deleting table', 'error');
            }
        } catch (error) {
            console.error('Error deleting table:', error);
            showToast('Error deleting table', 'error');
        }
    }
}

// === PAYMENT MANAGEMENT ===

async function renderPaymentConfig() {
    const container = document.getElementById('payment-config-container');
    if (!container) return;

    container.innerHTML = await fetchFragment('/api/backoffice/fragments/payment-section', {
        paymentConfig: selectedPaymentConfig,
        allConfigs: allPaymentConfigs
    });

    // Handle form submission
    const form = document.getElementById('paymentConfigForm');
    if (form) {
        form.addEventListener('submit', async (e) => {
            e.preventDefault();
            const id = document.getElementById('payment-config-id').value;
            const modes = [];
            if (document.getElementById('mode-cash').checked) modes.push('CASH');
            if (document.getElementById('mode-card').checked) modes.push('CARD');

            const configData = {
                id: id,
                activeModes: modes,
                moneyDenominations: selectedPaymentConfig.moneyDenominations || [],
                active: selectedPaymentConfig.active || false
            };

            if (modes.length === 0) {
                showToast('Please select at least one active payment mode.', 'warning');
                return;
            }

            try {
                const response = await fetch('/api/backoffice/payment', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'X-CSRF-TOKEN': getCsrfToken()
                    },
                    body: JSON.stringify(configData)
                });

                if (response.ok) {
                    showToast('Payment configuration saved successfully!');
                    await loadData();
                    // Keep the saved one selected
                    selectedPaymentConfig = allPaymentConfigs.find(c => c.id === id);
                    renderPaymentConfig();
                } else {
                    showToast('Error saving payment configuration', 'error');
                }
            } catch (error) {
                console.error('Error saving payment config:', error);
                showToast('Error saving payment config', 'error');
            }
        });
    }
}

function selectPaymentConfig(id) {
    selectedPaymentConfig = allPaymentConfigs.find(c => c.id === id);
    renderPaymentConfig();
}

async function setPaymentConfigDefault(id) {
    try {
        const response = await fetch(`/api/backoffice/payment/${id}/active`, {
            method: 'POST',
            headers: {
                'X-CSRF-TOKEN': getCsrfToken()
            }
        });

        if (response.ok) {
            showToast('Default payment configuration updated!');
            await loadData();
            renderPaymentConfig();
        } else {
            showToast('Error setting default configuration', 'error');
        }
    } catch (error) {
        console.error('Error setting default payment config:', error);
        showToast('Error setting default payment config', 'error');
    }
}

async function deletePaymentConfig(id) {
    if (id === 'default') return;
    const msg = (i18n.customcodesDeleteConfirm || 'Are you sure you want to delete the configuration "{0}"?').replace('{0}', id);
    if (!await fbConfirm({
        message: msg,
        confirmText: i18n.buttonDelete || 'Delete',
        cancelText: i18n.buttonCancel || 'Cancel',
        danger: true
    })) return;

    try {
        const response = await fetch(`/api/backoffice/payment/${id}`, {
            method: 'DELETE',
            headers: {
                'X-CSRF-TOKEN': getCsrfToken()
            }
        });

        if (response.ok) {
            showToast('Configuration deleted successfully!');
            await loadData();
            // Reset selection if deleted
            if (selectedPaymentConfig && selectedPaymentConfig.id === id) {
                selectedPaymentConfig = allPaymentConfigs[0];
            }
            renderPaymentConfig();
        } else {
            showToast('Error deleting configuration', 'error');
        }
    } catch (error) {
        console.error('Error deleting payment config:', error);
        showToast('Error deleting payment config', 'error');
    }
}

function createNewPaymentConfig() {
    selectedPaymentConfig = {
        id: 'new',
        activeModes: [],
        moneyDenominations: [],
        active: false
    };
    renderPaymentConfig();
}

function addDenomination() {
    const value = document.getElementById('new-denom-value').value;
    const type = document.getElementById('new-denom-type').value;
    const image = document.getElementById('new-denom-image').value;

    if (!value || !image) {
        showToast('Please provide value and image for the denomination', 'warning');
        return;
    }

    if (!selectedPaymentConfig.moneyDenominations) selectedPaymentConfig.moneyDenominations = [];

    selectedPaymentConfig.moneyDenominations.push({
        value: parseFloat(value),
        type: type,
        image: image
    });

    // Re-render to show updated list
    renderPaymentConfig();
}

function removeDenomination(index) {
    selectedPaymentConfig.moneyDenominations.splice(index, 1);
    renderPaymentConfig();
}

async function resetPaymentConfig() {
    await loadData();
    renderPaymentConfig();
    showToast('Payment configuration reset to database state.');
}

let denomImageCallback = null;

function openDenomImageSelector() {
    openImageSelector((url) => {
        document.getElementById('new-denom-image').value = url;
    });
}

// Override cancelForm to handle table and discount list visibility
const originalCancelForm = cancelForm;
window.cancelForm = function () {
    const tableList = document.getElementById('tables-list');
    const tableFormContainer = document.getElementById('table-form-container');
    if (tableList) tableList.style.display = 'block';
    if (tableFormContainer) tableFormContainer.innerHTML = '';
    const discountList = document.getElementById('discounts-list');
    const discountFormContainer = document.getElementById('discount-form-container');
    if (discountList) discountList.style.display = 'block';
    if (discountFormContainer) discountFormContainer.innerHTML = '';
    originalCancelForm();
};

// === DISCOUNTS MANAGEMENT ===
async function renderDiscountsList() {
    const list = document.getElementById('discounts-list');
    if (!list) return;
    list.innerHTML = await fetchFragment('/api/backoffice/fragments/discounts-list', discounts);
}

async function showDiscountForm(ruleId = null) {
    editingId = ruleId;
    const container = document.getElementById('discount-form-container');
    const list = document.getElementById('discounts-list');

    if (list) list.style.display = 'none';

    let rule = null;
    if (ruleId) {
        rule = discounts.find(d => d.id === ruleId);
    }

    container.innerHTML = await fetchFragment('/api/backoffice/fragments/discount-form', {
        discount: rule
    });

    if (rule) {
        document.getElementById('discount-form-title').textContent = 'Edit Discount';
        document.getElementById('discount-id').value = rule.id;
        document.getElementById('discount-name').value = rule.name;
        document.getElementById('discount-scope').value = rule.scope;
        document.getElementById('discount-type').value = rule.type;
        document.getElementById('discount-value').value = rule.value;
        document.getElementById('discount-minSubtotal').value = rule.minSubtotal;
        document.getElementById('discount-couponCode').value = rule.couponCode || '';
        document.getElementById('discount-active').checked = rule.active;
        document.getElementById('discount-accumulative').checked = rule.accumulative;
        document.getElementById('discount-applyOnCounter').checked = rule.applyOnCounter || false;
    }

    const form = document.getElementById('discountFormElement');
    if (form) {
        form.addEventListener('submit', handleDiscountSubmit);
    }

    container.scrollIntoView({ behavior: 'smooth' });
}

async function handleDiscountSubmit(e) {
    e.preventDefault();

    const id = document.getElementById('discount-id').value;
    const discountData = {
        name: document.getElementById('discount-name').value,
        scope: document.getElementById('discount-scope').value,
        type: document.getElementById('discount-type').value,
        value: parseFloat(document.getElementById('discount-value').value),
        minSubtotal: parseFloat(document.getElementById('discount-minSubtotal').value),
        couponCode: document.getElementById('discount-couponCode').value.trim() || null,
        active: document.getElementById('discount-active').checked,
        accumulative: document.getElementById('discount-accumulative').checked,
        applyOnCounter: document.getElementById('discount-applyOnCounter').checked
    };

    try {
        const url = id ? `/api/backoffice/discounts/${id}` : '/api/backoffice/discounts';
        const method = id ? 'PUT' : 'POST';

        const response = await fetch(url, {
            method: method,
            headers: {
                'Content-Type': 'application/json',
                'X-CSRF-TOKEN': getCsrfToken()
            },
            body: JSON.stringify(discountData)
        });

        if (response.ok) {
            await loadData();
            cancelForm();
            showToast(i18n.discountsSaveSuccess || 'Discount saved successfully!');
        } else {
            showToast('Error saving discount', 'error');
        }
    } catch (error) {
        console.error('Error saving discount:', error);
        showToast('Error saving discount', 'error');
    }
}

function editDiscount(id) {
    showDiscountForm(id);
}

async function deleteDiscount(id) {
    if (await fbConfirm({
        message: i18n.discountsDeleteConfirm || 'Are you sure you want to delete this discount?',
        confirmText: i18n.buttonDelete || 'Delete',
        cancelText: i18n.buttonCancel || 'Cancel',
        danger: true
    })) {
        try {
            const response = await fetch(`/api/backoffice/discounts/${id}`, {
                method: 'DELETE',
                headers: {
                    'X-CSRF-TOKEN': getCsrfToken()
                }
            });

            if (response.ok) {
                await loadData();
                showToast(i18n.discountsDeleteSuccess || 'Discount deleted successfully!');
            } else {
                showToast('Error deleting discount', 'error');
            }
        } catch (error) {
            console.error('Error deleting discount:', error);
            showToast('Error deleting discount', 'error');
        }
    }
}

function manageDiscountTranslations(ruleId) {
    window.location.assign(getTenantPrefix() + `/backoffice/translations/discounts/${ruleId}`);
}

// ===== Maintenance (Backup & Restore) =====

function triggerDownloadBackup() {
    const tenantPrefix = getTenantPrefix();
    window.location.assign(tenantPrefix + '/api/backoffice/maintenance/backup');
}

async function triggerCleanData() {
    if (await fbConfirm({
        message: i18n.maintenanceResetConfirm || 'WARNING: Are you sure you want to completely reset the database? All categories, products, customizations, tables, discounts, and custom images will be permanently deleted!',
        confirmText: i18n.buttonResetDatabase || 'Reset Database',
        cancelText: i18n.buttonCancel || 'Cancel',
        danger: true
    })) {
        try {
            const tenantPrefix = getTenantPrefix();
            const response = await fetch(tenantPrefix + '/api/backoffice/maintenance/clean', {
                method: 'POST',
                headers: {
                    'X-CSRF-TOKEN': getCsrfToken()
                }
            });

            const result = await response.json();
            if (response.ok && result.status === 'success') {
                showToast(result.message || 'Database successfully reset.');
                await loadData();
            } else {
                showToast(result.message || 'Error resetting data.', 'error');
            }
        } catch (error) {
            console.error('Error cleaning data:', error);
            showToast('Error resetting data.', 'error');
        }
    }
}

async function triggerRestoreBackup(event) {
    event.preventDefault();
    const fileInput = document.getElementById('backupFile');
    if (!fileInput.files || fileInput.files.length === 0) {
        showToast('Please select a backup zip file first.', 'error');
        return;
    }

    const file = fileInput.files[0];
    const formData = new FormData();
    formData.append('file', file);

    const submitBtn = document.getElementById('restoreSubmitBtn');
    const originalBtnHtml = submitBtn.innerHTML;
    submitBtn.disabled = true;
    submitBtn.innerHTML = '<i class="fas fa-spinner fa-spin me-2"></i> Restoring...';

    try {
        const tenantPrefix = getTenantPrefix();
        const response = await fetch(tenantPrefix + '/api/backoffice/maintenance/restore', {
            method: 'POST',
            headers: {
                'X-CSRF-TOKEN': getCsrfToken()
            },
            body: formData
        });

        const result = await response.json();
        if (response.ok && result.status === 'success') {
            showToast(result.message || 'Backup successfully restored.');
            fileInput.value = '';
            await loadData();
            if (result.notice) {
                await fbNotice({ message: result.notice });
            }
        } else {
            showToast(result.message || 'Error restoring backup.', 'error');
        }
    } catch (error) {
        console.error('Error restoring backup:', error);
        showToast('Error restoring backup.', 'error');
    } finally {
        submitBtn.disabled = false;
        submitBtn.innerHTML = originalBtnHtml;
    }
}

async function loadDemoTemplates() {
    const select = document.getElementById('demoTemplateSelect');
    const desc = document.getElementById('demoTemplateDescription');
    if (!select) return;
    try {
        const response = await fetch(getTenantPrefix() + '/api/backoffice/maintenance/templates');
        if (response.ok) {
            const templates = await response.json();
            select.innerHTML = '';
            templates.forEach(t => {
                const opt = document.createElement('option');
                opt.value = t.id;
                opt.textContent = `${t.icon ? t.icon + ' ' : ''}${t.name} (${t.productsCount} items)`;
                opt.dataset.desc = t.description || '';
                select.appendChild(opt);
            });
            const updateDesc = () => {
                const selectedOpt = select.options[select.selectedIndex];
                if (desc) desc.textContent = selectedOpt ? (selectedOpt.dataset.desc || '') : '';
            };
            select.onchange = updateDesc;
            updateDesc();
        }
    } catch (e) {
        console.error('Failed to load demo templates', e);
    }
}

async function triggerRestoreDemo() {
    const select = document.getElementById('demoTemplateSelect');
    const templateId = select ? select.value : 'kebab';
    const templateName = select && select.options[select.selectedIndex] ? select.options[select.selectedIndex].text : 'demo';
    const msg = (i18n.maintenanceTemplateLoadConfirm || 'Are you sure you want to load the {0} template? This will overwrite all current configurations, categories, products, customizations, dining tables, and discounts.').replace('{0}', templateName);
    if (await fbConfirm({
        message: msg,
        confirmText: i18n.buttonLoadTemplate || 'Load Template',
        cancelText: i18n.buttonCancel || 'Cancel',
        danger: true
    })) {
        const submitBtn = document.getElementById('restoreDemoBtn');
        const originalBtnHtml = submitBtn.innerHTML;
        submitBtn.disabled = true;
        submitBtn.innerHTML = '<i class="fas fa-spinner fa-spin me-2"></i> Loading Demo...';

        try {
            const tenantPrefix = getTenantPrefix();
            const response = await fetch(tenantPrefix + '/api/backoffice/maintenance/restore-demo?template=' + encodeURIComponent(templateId), {
                method: 'POST',
                headers: {
                    'X-CSRF-TOKEN': getCsrfToken()
                }
            });

            const result = await response.json();
            if (response.ok && result.status === 'success') {
                showToast(result.message || 'Demo data loaded successfully.');
                await loadData();
                if (result.notice) {
                    await fbNotice({ message: result.notice });
                }
            } else {
                showToast(result.message || 'Error loading demo data.', 'error');
            }
        } catch (error) {
            console.error('Error loading demo data:', error);
            showToast('Error loading demo data.', 'error');
        } finally {
            submitBtn.disabled = false;
            submitBtn.innerHTML = originalBtnHtml;
        }
    }
}

// The ordering options and the KDS timers are saved together as this restaurant's settings
async function saveRestaurantSettings(event) {
    event.preventDefault();
    const takeawayEl = document.getElementById('takeawayInput');
    const settings = {
        dineIn: document.getElementById('dineInInput').checked,
        takeaway: takeawayEl.disabled ? true : takeawayEl.checked,
        tableQrTrusted: document.getElementById('tableQrTrustedInput') ? document.getElementById('tableQrTrustedInput').checked : false,
        kdsYellowMinutes: parseInt(document.getElementById('yellowTimerInput').value || '0'),
        kdsRedMinutes: parseInt(document.getElementById('redTimerInput').value || '0')
    };

    try {
        const response = await fetch('/api/backoffice/settings', {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json',
                'X-CSRF-TOKEN': getCsrfToken()
            },
            body: JSON.stringify(settings)
        });
        if (response.ok) {
            showToast('Settings saved successfully!');
        } else {
            const msg = await response.text();
            showToast(msg || 'Failed to save settings', 'error');
        }
    } catch (err) {
        console.error('Error saving settings:', err);
        showToast('Error saving settings', 'error');
    }
}

async function toggleProductStock86(productId) {
    try {
        const response = await fetch(`/api/backoffice/products/${productId}/toggle-86`, {
            method: 'POST',
            headers: {
                'X-CSRF-TOKEN': getCsrfToken()
            }
        });
        if (response.ok) {
            await loadData();
            showToast('Product stock 86 availability toggled!');
        } else {
            showToast('Failed to toggle product stock', 'error');
        }
    } catch (err) {
        console.error('Error toggling product stock:', err);
        showToast('Error toggling product stock', 'error');
    }
}

