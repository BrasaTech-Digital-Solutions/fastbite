// FastFood Application JavaScript
// Application state
let cart = [];
let currentCustomization = {};
function getApiSaveCartUrl() { return getTenantPrefix() + '/api/create-order'; }
function getCartUrl() { return getTenantPrefix() + '/api/calculate-cart'; }
function getConfirmationUrl() { return getTenantPrefix() + '/api/calculate-confirmation'; }
function getOrderConfirmationUrl() { return getTenantPrefix() + '/order-confirmation'; }
function getToastUrl() { return getTenantPrefix() + '/api/toast'; }

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

// Initialize the app
document.addEventListener('DOMContentLoaded', function () {
    handleAddEventListener();
    updateCartDisplay();
});

function formatPrice(price) {
    return parseFloat(price).toFixed(2);
}

// Render menu items
function handleAddEventListener() {
    // Navbar
    document.getElementById('navBarCheckoutBtn').addEventListener('click', toggleCart);
    document.getElementById('floatingCartBtn')?.addEventListener('click', toggleCart);

    // Combos Tab
    const tabs = document.getElementById('menuTabs').dataset.availableTabs?.split(',');
    tabs.forEach(tab => addListenerToMenuCategory(tab));

    // Customization Modal
    addEventListenerToCustomizationModel();
}

function addEventListenerToCustomizationModel() {
    const parent = document.getElementById('customizations');
    const buttons = parent.querySelectorAll('button');

    buttons.forEach(button => {
        // minus
        const minusSuffix = '-minus';
        if (button.id?.endsWith(minusSuffix)) {
            button.addEventListener("click", function () {
                const displayId = button.id.replace(minusSuffix, '');
                changeQuantityToggle(displayId, -1);
            });
        }

        // plus
        const plusSuffix = '-plus';
        if (button.id?.endsWith(plusSuffix)) {
            button.addEventListener("click", function () {
                const displayId = button.id.replace(plusSuffix, '');
                changeQuantityToggle(displayId, 1);
            });
        }
    });

    document.getElementById('customizationCancelBtn').addEventListener('click', closeCustomizationModal);
    document.getElementById('customizationSubmitBtn').addEventListener('click', addCustomizedItem);
}

function addListenerToMenuCategory(category) {
    const parent = document.getElementById(category);
    // buttons
    const buttons = parent.querySelectorAll('button');

    buttons.forEach(button => {
        // minus
        const minusSuffix = '-minus';
        if (button.id?.endsWith(minusSuffix)) {
            button.addEventListener("click", function () {
                const displayId = button.id.replace(minusSuffix, '');
                changeQuantityNoLessThanOne(displayId, -1);
            });
        }

        // plus
        const plusSuffix = '-plus';
        if (button.id?.endsWith(plusSuffix)) {
            button.addEventListener("click", function () {
                const displayId = button.id.replace(plusSuffix, '');
                changeQuantityNoLessThanOne(displayId, 1);
            });
        }

        // to-cart
        const toCartSuffix = '-to-cart';
        if (button.id?.endsWith(toCartSuffix)) {
            button.addEventListener("click", function () {
                const productId = button.id.replace(/^add-/, '').replace(new RegExp(toCartSuffix + '$'), '');
                customizeItem(productId, category);
            });
        }
    });
}

// Quantity control
function changeQuantityToggle(itemId, change) {
    const qtyElement = document.getElementById(itemId);
    let currentQty = parseInt(qtyElement.textContent);
    const newQty = Math.min(1, Math.max(0, currentQty + change));
    qtyElement.textContent = newQty;
    const hiddenInput = document.getElementById(itemId.replace('qty-', ''));
    if (hiddenInput) {
        hiddenInput.value = newQty;
    }
    const minusButton = document.getElementById(itemId + '-minus');
    const plusButton = document.getElementById(itemId + '-plus');
    if (minusButton) minusButton.disabled = (newQty === 0);
    if (plusButton) plusButton.disabled = (newQty >= 1);
}

// Quantity control no less than one
function changeQuantityNoLessThanOne(itemId, change) {
    const qtyElement = document.getElementById(itemId);
    let currentQty = parseInt(qtyElement.textContent);
    const newQty = Math.max(1, currentQty + change);
    qtyElement.textContent = newQty;
}

// Quantity control no less than zero
function changeQuantityNoLessThanZero(itemId, change) {
    const qtyElement = document.getElementById(itemId);
    let currentQty = parseInt(qtyElement.textContent);
    const newQty = Math.max(0, currentQty + change);
    qtyElement.textContent = newQty;
}

// Quantity control set no less than zero
function changeQuantitySetNoLessThanZero(itemId, change) {
    const qtyElement = document.getElementById(itemId);
    qtyElement.textContent = Math.max(0, change);;
}

// Customize item
function customizeItem(itemId, category) {
    const quantity = parseInt(document.getElementById(`qty-${itemId}`).textContent);
    const defaults = getDefaultCustomizations(itemId);

    addToCart(itemId, quantity, defaults);

    // Reset quantity to 1
    document.getElementById(`qty-${itemId}`).textContent = '1';
}

function getDefaultCustomizations(itemId) {
    const customizationsMap = new Map();
    const allowedCustomizationIds = document.getElementById(itemId).dataset.customizations?.split(',') || [];

    allowedCustomizationIds.forEach(customizationId => {
        const parent = document.getElementById(customizationId);
        if (!parent) return;

        // Radios and Checkboxes
        const inputs = parent.querySelectorAll('input[type="radio"], input[type="checkbox"]');
        inputs.forEach(input => {
            if (input.dataset.selectedByDefault === 'true' && !customizationsMap.has(input.id)) {
                customizationsMap.set(input.id, {
                    id: input.id,
                    name: input.value,
                    price: parseFloat(input.dataset.price),
                    quantity: 1
                });
            }
        });

        // Ingredients (Hidden inputs with quantity control):
        // Untouched defaults are omitted from customizations, so nothing is added here.
    });
    return Array.from(customizationsMap.values());
}

// Show customization modal
function showCustomizationModal(itemId, customizations) {
    // clear the options
    const customizationsModal = document.getElementById('customizationsModal').dataset.availableCustomizations.split(',');
    customizationsModal.forEach(option => {
        const parent = document.getElementById(option);
        parent.style.display = "none";
        // Reset radio buttons
        const radios = parent.querySelectorAll('input[type="radio"]');
        radios.forEach(radio => {
            radio.checked = radio.defaultChecked;
            if (radio.dataset.selectedByDefault === 'true') {
                radio.checked = true;
            }
        });

        // Reset checkboxes
        const checkboxes = parent.querySelectorAll('input[type="checkbox"]');
        checkboxes.forEach(checkbox => {
            checkbox.checked = checkbox.defaultChecked;
            if (checkbox.dataset.selectedByDefault === 'true') {
                checkbox.checked = true;
            }
        });

        // Reset Ingredients
        const ingredientsParent = document.getElementById('customizations');
        const buttons = ingredientsParent.querySelectorAll('button');

        buttons.forEach(button => {
            // minus
            const minusSuffix = '-minus';
            if (button.id?.endsWith(minusSuffix)) {
                const displayId = button.id.replace(minusSuffix, '');
                const input = document.getElementById(displayId.replace('qty-', ''));
                const defaultValue = parseInt(input?.dataset.defaultValue || "0");
                const initialQty = defaultValue >= 1 ? 1 : 0;
                changeQuantitySetNoLessThanZero(displayId, initialQty);
                if (input) {
                    input.value = initialQty.toString();
                }
                button.disabled = (initialQty === 0);
                const plusBtn = document.getElementById(displayId + '-plus');
                if (plusBtn) {
                    plusBtn.disabled = (initialQty >= 1);
                }
            }
        });
    });

    // display the desired options
    customizationsModal.forEach(option => {
        let currentCustomizationElement = document.getElementById(option);
        if (currentCustomizationElement && customizations.includes(option)) {
            currentCustomizationElement.style.display = "block";
        }
    });

    const productName = document.getElementById(itemId).dataset.name;
    const image = document.getElementById(itemId).dataset.image;
    document.getElementById('modalProductImage').src = image;
    document.getElementById('modalProductImage').alt = productName;

    const modalTitle = document.querySelector('#customizationsModal .modal-title');
    modalTitle.textContent = modalTitle.dataset.title + ' ' + productName;

    // Update submit button text
    const submitBtn = document.getElementById('customizationSubmitBtn');
    const dictionary = document.getElementById('dictionary');
    if (currentCustomization.editId) {
        submitBtn.textContent = dictionary.dataset.buttonUpdateItem || 'Update Item';
    } else {
        submitBtn.textContent = dictionary.dataset.buttonAddToCart || 'Add to Cart';
    }

    const modal = new bootstrap.Modal(document.getElementById('customizationsModal'));
    modal.show();
}

// Edit item in cart
function editCartItem(cartItemId) {
    const cartItem = cart.find(item => item.id === cartItemId);
    if (!cartItem) return;

    const customizations = document.getElementById(cartItem.itemId).dataset.customizations?.split(',');

    currentCustomization = {
        itemId: cartItem.itemId,
        quantity: cartItem.quantity,
        editId: cartItem.id,
        customizations: cartItem.customizations
    };

    showCustomizationModal(cartItem.itemId, customizations);

    // Pre-select current choices in modal
    setTimeout(() => {
        cartItem.customizations.forEach(c => {
            const input = document.getElementById(c.id);
            if (input) {
                if (input.type === 'radio' || input.type === 'checkbox') {
                    input.checked = true;
                } else if (input.type === 'hidden') {
                    // This is an ingredient toggle (value 0 means without, 1 means with/added)
                    if (c.quantity === 0) {
                        changeQuantitySetNoLessThanZero('qty-' + c.id, 0);
                        const minusButton = document.getElementById('qty-' + c.id + '-minus');
                        const plusButton = document.getElementById('qty-' + c.id + '-plus');
                        if (minusButton) minusButton.disabled = true;
                        if (plusButton) plusButton.disabled = false;
                        input.value = "0";
                    } else if (c.quantity === 1) {
                        changeQuantitySetNoLessThanZero('qty-' + c.id, 1);
                        const minusButton = document.getElementById('qty-' + c.id + '-minus');
                        const plusButton = document.getElementById('qty-' + c.id + '-plus');
                        if (minusButton) minusButton.disabled = false;
                        if (plusButton) plusButton.disabled = true;
                        input.value = "1";
                    }
                }
            }
        });
    }, 100);
}

// Add customized item to cart
function addCustomizedItem() {
    const customizationsMap = new Map();
    
    // Get visible customization groups
    const visibleGroups = Array.from(document.querySelectorAll('#customizations > div'))
        .filter(div => div.style.display !== 'none');

    visibleGroups.forEach(group => {
        // Radios and Checkboxes
        const inputs = group.querySelectorAll('input[type="checkbox"]:checked, input[type="radio"]:checked');
        inputs.forEach(input => {
            if (!customizationsMap.has(input.id)) {
                customizationsMap.set(input.id, {
                    id: input.id,
                    name: input.value,
                    price: parseFloat(input.dataset.price),
                    quantity: 1
                });
            }
        });

        // Ingredients (Hidden inputs)
        const ingredients = group.querySelectorAll('input[type="hidden"]');
        ingredients.forEach(input => {
            const defaultValue = parseInt(input.dataset.defaultValue || "0");
            const currentQty = parseInt(input.value || "0");
            if (defaultValue >= 1 && currentQty === 0 && !customizationsMap.has(input.id)) {
                customizationsMap.set(input.id, {
                    id: input.id,
                    name: input.dataset.no + ' ' + input.dataset.name,
                    price: 0,
                    quantity: 0
                });
            } else if (defaultValue === 0 && currentQty === 1 && !customizationsMap.has(input.id)) {
                customizationsMap.set(input.id, {
                    id: input.id,
                    name: input.dataset.name,
                    price: parseFloat(input.dataset.price) || 0,
                    quantity: 1
                });
            }
        });
    });

    const customizations = Array.from(customizationsMap.values());
    addToCart(currentCustomization.itemId, currentCustomization.quantity, customizations);

    closeCustomizationModal();

    // Reset quantity to 1
    document.getElementById(`qty-${currentCustomization.itemId}`).textContent = '1';
}

// Close the modal in accessibility-compliant way
function closeCustomizationModal() {
    const modalElement = document.getElementById('customizationsModal');
    const focusedElement = modalElement.querySelector(':focus');
    if (focusedElement) {
        focusedElement.blur();
    }
    const modal = bootstrap.Modal.getInstance(modalElement);
    modal.hide();
}

// Add item to cart
function addToCart(itemId, quantity, customizations) {
    const name = document.getElementById(itemId).dataset.name;
    const description = document.getElementById(itemId).dataset.description;
    const image = document.getElementById(itemId).dataset.image;
    const productPrice = Number.parseFloat(document.getElementById(itemId).dataset.price);

    if (currentCustomization.editId) {
        const cartItem = cart.find(item => item.id === currentCustomization.editId);
        if (cartItem) {
            cartItem.quantity = quantity;
            cartItem.customizations = customizations;
            updateCartDisplay();
            const msg = (document.getElementById('dictionary').dataset.itemUpdated || '{0} updated!').replace('{0}', name);
            showToast(msg);
            currentCustomization = {};
            return;
        }
    }

    const cartItem = {
        id: `${itemId}-${Date.now()}`,
        itemId: itemId,
        name: name,
        description: description,
        image: image,
        quantity: quantity,
        customizations: customizations,
        price: productPrice
    };

    cart.push(cartItem);
    updateCartDisplay();
    const msg = (document.getElementById('dictionary').dataset.itemAdded || '{0} added to cart!').replace('{0}', name);
    showToast(msg);
    currentCustomization = {};
}

// Update cart display
function updateCartDisplay() {
    const cartCount = document.getElementById('cartCount');
    const totalItems = cart.reduce((sum, item) => sum + item.quantity, 0);
    cartCount.textContent = totalItems;
    const cartSection = document.getElementById('cartSection');
    if (cartSection.style.display === 'block') {
        loadCartData()
    }

    // Update floating cart badge
    const floatingCount = document.getElementById('floatingCartCount');
    if (floatingCount) {
        floatingCount.textContent = totalItems;
        floatingCount.classList.toggle('d-none', totalItems === 0);
    }
}

// Update cart item quantity
function updateCartItemQuantity(cartItemId, change) {
    const cartItem = cart.find(item => item.id === cartItemId);
    if (cartItem) {
        cartItem.quantity = Math.max(1, cartItem.quantity + change);
        updateCartDisplay();
    }
}

// Remove item from cart
function removeFromCart(cartItemId) {
    cart = cart.filter(item => item.id !== cartItemId);
    updateCartDisplay();
    showToast(document.getElementById('dictionary').dataset.itemRemoved || 'Item removed from cart');
}

// Toggle cart view
function toggleCart() {
    const menuSection = document.getElementById('menuSection');
    const cartSection = document.getElementById('cartSection');

    if (cartSection.style.display === 'none') {
        loadCartData()
        menuSection.style.display = 'none';
        cartSection.style.display = 'block';
        updateStep(2);
    } else {
        cartSection.style.display = 'none';
        menuSection.style.display = 'block';
        cartSection.innerHTML = '';
        updateStep(1);
    }
}

let activeCouponCode = null;

function applyCoupon() {
    const code = document.getElementById('couponCode')?.value?.trim();
    if (code) {
        activeCouponCode = code;
    } else {
        activeCouponCode = null;
    }
    loadCartData();
}

function loadCartData() {
    const baseUrl = getCartUrl();
    const url = activeCouponCode ? `${baseUrl}?couponCode=${encodeURIComponent(activeCouponCode)}` : baseUrl;
    loadFragments(url)
        .then(html => {
            cartSection.innerHTML = html;
            disableNonCustomizableEditButtons();
        })
        .catch(error => showToast(error.message));
}

function proceedToCheckout() {
    const baseUrl = getConfirmationUrl();
    const url = activeCouponCode ? `${baseUrl}?couponCode=${encodeURIComponent(activeCouponCode)}` : baseUrl;
    loadFragments(url).then(html => {
        const confirmationSection = document.getElementById('confirmationSection');
        confirmationSection.innerHTML = html;
        document.getElementById('cartSection').style.display = 'none';
        confirmationSection.style.display = 'block';
        updateStep(3);
    }).catch(error => showToast(error.message));
}

// How the order is served, as the server decided it: at the table the QR code named, or takeaway
function selectedServiceType() {
    const serviceType = document.querySelector('input[name="serviceType"]');
    return serviceType ? serviceType.value : 'TAKEAWAY';
}

function disableNonCustomizableEditButtons() {
    const editButtons = document.querySelectorAll('.edit-cart-button');
    editButtons.forEach(button => {
        const productId = button.dataset.productId;
        const productMetadata = document.getElementById(productId);
        if (productMetadata) {
            const customizations = productMetadata.dataset.customizations;
            if (!customizations || customizations.trim() === '') {
                button.disabled = true;
                button.classList.add('opacity-50'); // Visual hint it's disabled
            }
        }
    });
}

// Back to menu
function backToMenu() {
    document.getElementById('cartSection').style.display = 'none';
    document.getElementById('menuSection').style.display = 'block';
    updateStep(1);
}

// Back to cart
function backToCart() {
    const confirmationSection = document.getElementById('confirmationSection');
    confirmationSection.style.display = 'none';
    document.getElementById('cartSection').style.display = 'block';
    updateStep(2);
    confirmationSection.innerHTML = '';
}

// Submit order
function submitOrder() {
    const customerNameInput = document.getElementById('customerName');
    const customerName = customerNameInput ? customerNameInput.value.trim() : '';
    const paymentMethodEl = document.querySelector('input[name="paymentMethod"]:checked');
    // Takeaway has no choice: it is paid online
    const paymentMethod = selectedServiceType() === 'DINE_IN' && paymentMethodEl ? paymentMethodEl.value : 'online';
    const messages = document.getElementById('orderDetails')?.dataset || {};

    if (!customerName) {
        showToast(messages.messageName);
        if (customerNameInput) customerNameInput.focus();
        return;
    }

    const payload = {
        items: cart,
        customerName: customerName,
        paymentMethod: paymentMethod
    };

    // Show loading state
    const submitBtn = document.getElementById('submitOrderBtn');
    if (submitBtn) {
        submitBtn.disabled = true;
        submitBtn.innerHTML = '<i class="fas fa-spinner fa-spin me-2"></i> Procesando...';
    }

    fetch(getApiSaveCartUrl(), {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': document.querySelector('meta[name="_csrf"]')?.content
        },
        body: JSON.stringify(payload)
    })
    .then(response => response.json())
    .then(data => {
        if (data.status === 'success') {
            // An order that must be paid first goes to the payment page whatever was chosen
            if (data.payFirst || paymentMethod === 'online') {
                window.location.assign(getTenantPrefix() + '/select-payment');
            } else {
                window.location.assign(getOrderConfirmationUrl());
            }
        } else {
            showToast("Error al crear el pedido: " + (data.message || "Error desconocido"));
            if (submitBtn) {
                submitBtn.disabled = false;
                submitBtn.innerHTML = '<i class="fas fa-paper-plane me-2"></i> Confirmar y enviar';
            }
        }
    })
    .catch(error => {
        console.error('Error submitting order:', error);
        showToast("Error de conexión al enviar el pedido.");
        if (submitBtn) {
            submitBtn.disabled = false;
            submitBtn.innerHTML = '<i class="fas fa-paper-plane me-2"></i> Confirmar y enviar';
        }
    });
}

// Start new order
function startNewOrder() {
    document.getElementById('menuSection').style.display = 'block';
    updateStep(1);
}

// Update step indicator
function updateStep(step) {
    // Reset all steps
    for (let i = 1; i <= 4; i++) {
        const stepElement = document.getElementById(`step${i}`);
        if (!stepElement) continue;
        stepElement.classList.remove('active', 'completed');
        if (i < step) {
            stepElement.classList.add('completed');
        } else if (i === step) {
            stepElement.classList.add('active');
        }
    }
}

// Show toast notification
function showToast(message) {
    // Create toast element
    const toastContainer = document.getElementById('toastContainer') || createToastContainer();

    loadFragments(getToastUrl(), { message: message })
        .then(html => {
            const div = document.createElement('div');
            div.innerHTML = html;
            const toast = div.firstElementChild;
            toastContainer.appendChild(toast);

            // Auto remove after 3 seconds
            setTimeout(() => {
                if (toast.parentNode) {
                    toast.parentNode.removeChild(toast);
                }
            }, 3000);
        })
        .catch(error => console.error('Error:', error));
}

// Create toast container if it doesn't exist
function createToastContainer() {
    const container = document.createElement('div');
    container.id = 'toastContainer';
    container.className = 'toast-container position-fixed bottom-0 end-0 p-3';
    document.body.appendChild(container);
    return container;
}

function loadFragments(url, payload = cart) {
    return fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': document.querySelector('meta[name="_csrf"]')?.content
        },
        body: JSON.stringify(payload)
    })
        .then(response => response.text().then(text => {
            // A rejected cart (e.g. a product that just sold out) comes back as the reason in plain text
            if (!response.ok) throw new Error(text);
            return text;
        }));
}