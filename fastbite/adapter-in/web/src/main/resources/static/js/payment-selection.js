// The guest's payment page: choose how to pay, add a tip to an online payment, and go to Stripe.
// The page shows a preview only: the server works out the amount to charge. State is kept in classes and
// attributes; the stylesheet decides how a chosen option or tip looks.
(() => {
'use strict';
const paymentData = document.getElementById('paymentData').dataset;
const orderTotal = parseFloat(paymentData.orderTotal) || 0;
const money = new Intl.NumberFormat(document.documentElement.lang || 'es', { style: 'currency', currency: 'EUR' });
let tipPercent = 0;

const selectedMethod = () => document.querySelector('input[name="paymentMethod"]:checked').value;

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

// A tip only makes sense when paying online
function showSelectedMethod() {
    document.getElementById('tipCard').hidden = selectedMethod() !== 'stripe-checkout';
    updateTotals();
}

function updateTotals() {
    const percent = selectedMethod() === 'stripe-checkout' ? tipPercent : 0;
    const tip = Math.round(orderTotal * percent) / 100;
    document.querySelectorAll('.tip-chip').forEach(chip => {
        const amount = chip.querySelector('.tip-amount');
        if (amount) amount.textContent = money.format(Math.round(orderTotal * chip.dataset.percent) / 100);
    });
    document.getElementById('tipAmountDisplay').textContent = money.format(tip);
    document.getElementById('selectedTipPercent').textContent = percent;
    document.getElementById('tipRow').hidden = !(percent > 0);
    document.getElementById('displayGrandTotal').textContent = money.format(orderTotal + tip);
}

function selectTip(chip) {
    document.querySelectorAll('.tip-chip').forEach(other => {
        other.classList.toggle('active', other === chip);
        other.setAttribute('aria-pressed', String(other === chip));
    });
    tipPercent = parseInt(chip.dataset.percent, 10);
    updateTotals();
}

document.querySelectorAll('.pay-option-input').forEach(radio => radio.addEventListener('change', showSelectedMethod));
document.querySelectorAll('.tip-chip').forEach(chip => chip.addEventListener('click', () => selectTip(chip)));
document.querySelectorAll('[data-go-back]').forEach(button => button.addEventListener('click', () => window.history.back()));

document.getElementById('paymentForm').addEventListener('submit', function (e) {
    e.preventDefault();
    if (selectedMethod() !== 'stripe-checkout') {
        window.location.assign(getTenantPrefix() + '/order-confirmation');
        return;
    }

    const submitBtn = this.querySelector('button[type="submit"]');
    const label = submitBtn.querySelector('[data-submit-label]');
    const icon = submitBtn.querySelector('[data-submit-icon] use');
    const originalText = label.textContent;
    const errorBox = document.getElementById('paymentError');
    const busy = on => {
        submitBtn.disabled = on;
        submitBtn.toggleAttribute('data-busy', on);
        icon.setAttribute('href', on ? '#i-spinner' : '#i-lock');
        label.textContent = on ? paymentData.processing : originalText;
    };
    const fail = message => {
        errorBox.textContent = message || paymentData.error;
        errorBox.hidden = false;
        busy(false);
    };
    errorBox.hidden = true;
    busy(true);

    fetch(getTenantPrefix() + '/api/stripe/create-checkout-session', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': document.querySelector('meta[name="_csrf"]').content
        },
        body: JSON.stringify({ tipPercent: tipPercent })
    })
        .then(res => res.json())
        .then(data => data.checkoutUrl ? window.location.assign(data.checkoutUrl) : fail(data.error))
        .catch(() => fail());
});

showSelectedMethod();
})();
