// Tickets page: reprint, a full invoice for a ticket already issued, refunds, and cancelling a
// ticket issued by mistake. Every action is done on the server; the page reloads to show it.
(() => {
'use strict';
const page = document.getElementById('tickets');
if (!page) return;

const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
const prefix = page.dataset.prefix || '';
const inFlight = new Set();
let current = null;

function openDialog(id, opener, focusId) {
  const dialog = document.getElementById(id);
  if (!dialog) return;
  if (window.FastBiteDialogs) {
    window.FastBiteDialogs.open(dialog, opener);
  } else if (!dialog.open) {
    dialog.showModal();
  }
  if (focusId) {
    const field = document.getElementById(focusId);
    if (field && typeof field.focus === 'function') field.focus();
  }
}

function closeDialog(id) {
  const dialog = document.getElementById(id);
  if (!dialog) return;
  if (window.FastBiteDialogs) {
    window.FastBiteDialogs.close(dialog);
  } else if (dialog.open) {
    dialog.close();
  }
}

function printTicket(ticketId) {
  const frame = document.getElementById('print-frame');
  frame.onload = () => { frame.contentWindow.focus(); frame.contentWindow.print(); };
  frame.src = `${prefix}/counter/tickets/${encodeURIComponent(ticketId)}`;
}

async function post(url, body) {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', [csrfHeader]: csrfToken },
    body: JSON.stringify(body)
  });
  if (!response.ok) throw new Error(await response.text());
  return response.json();
}

function invoiceDetails(idPrefix) {
  const value = id => document.getElementById(`${idPrefix}-${id}`).value.trim();
  return { taxId: value('taxId'), country: value('country'), name: value('name'), address: value('address') };
}

function done(template, ticket) {
  sessionStorage.setItem('fastbite.ticketNotice', template.replace('{0}', ticket.fullNumber));
  printTicket(ticket.ticketId);
  setTimeout(() => location.reload(), 1200);
}

function failed(errorBox, error) {
  errorBox.textContent = error.message || String(error);
  errorBox.hidden = false;
}

const dayInput = document.getElementById('ticket-day');
if (dayInput && dayInput.form) {
  dayInput.addEventListener('change', () => dayInput.form.submit());
}

page.addEventListener('click', event => {
  const button = event.target.closest('[data-action]');
  if (!button) return;
  current = { id: button.dataset.ticket, number: button.dataset.number };
  if (button.dataset.action === 'print') {
    printTicket(current.id);
  } else if (button.dataset.action === 'invoice') {
    const intro = document.getElementById('invoiceIntro');
    intro.textContent = intro.dataset.template.replace('{0}', current.number);
    document.getElementById('invoiceForm').reset();
    document.getElementById('invoiceError').hidden = true;
    openDialog('invoiceModal', button, 'inv-taxId');
  } else if (button.dataset.action === 'refund') {
    const title = document.getElementById('refundTitle');
    title.textContent = title.dataset.template.replace('{0}', current.number);
    document.getElementById('refundForm').reset();
    document.getElementById('refundError').hidden = true;
    openDialog('refundModal', button, 'refundReason');
  } else if (button.dataset.action === 'cancel') {
    const title = document.getElementById('cancelTitle');
    title.textContent = title.dataset.template.replace('{0}', current.number);
    document.getElementById('cancelForm').reset();
    document.getElementById('cancelError').hidden = true;
    openDialog('cancelModal', button, 'cancelReason');
  }
});

async function submitGuarded(actionKey, form, errorBox, handler) {
  if (!current || inFlight.has(actionKey)) return;
  inFlight.add(actionKey);
  const submit = form.querySelector('[type="submit"]');
  if (submit) submit.disabled = true;
  let succeeded = false;
  try {
    await handler();
    succeeded = true;
  } catch (error) {
    failed(errorBox, error);
  } finally {
    if (!succeeded) {
      inFlight.delete(actionKey);
      if (submit) submit.disabled = false;
    }
  }
}

document.getElementById('cancelForm').addEventListener('submit', event => {
  event.preventDefault();
  submitGuarded('cancel', event.currentTarget, document.getElementById('cancelError'), async () => {
    const cancelled = await post(`/counter/api/tickets/${encodeURIComponent(current.id)}/cancel`,
        { reason: document.getElementById('cancelReason').value.trim() });
    closeDialog('cancelModal');
    // Nothing to print: the orders are unpaid again and can be charged at the counter
    const noticeTemplate = cancelled.ordersMissing && page.dataset.doneCancelNoOrders
        ? page.dataset.doneCancelNoOrders
        : page.dataset.doneCancel;
    sessionStorage.setItem('fastbite.ticketNotice', noticeTemplate.replace('{0}', cancelled.fullNumber || current.number));
    location.reload();
  });
});

document.getElementById('invoiceForm').addEventListener('submit', event => {
  event.preventDefault();
  submitGuarded('invoice', event.currentTarget, document.getElementById('invoiceError'), async () => {
    const ticket = await post(`/counter/api/tickets/${encodeURIComponent(current.id)}/invoice`, invoiceDetails('inv'));
    closeDialog('invoiceModal');
    done(page.dataset.doneInvoice, ticket);
  });
});

document.getElementById('refundForm').addEventListener('submit', event => {
  event.preventDefault();
  submitGuarded('refund', event.currentTarget, document.getElementById('refundError'), async () => {
    const ticket = await post(`/counter/api/tickets/${encodeURIComponent(current.id)}/refund`,
        { reason: document.getElementById('refundReason').value.trim() });
    closeDialog('refundModal');
    done(page.dataset.doneRefund, ticket);
  });
});

// The notice of the last action survives the reload
const notice = sessionStorage.getItem('fastbite.ticketNotice');
if (notice) {
  sessionStorage.removeItem('fastbite.ticketNotice');
  const toast = document.createElement('div');
  toast.className = 'toast tickets-toast-success';
  const body = document.createElement('div');
  body.className = 'toast-body';
  body.textContent = notice;
  toast.appendChild(body);
  document.getElementById('toastContainer').appendChild(toast);
  setTimeout(() => toast.remove(), 5000);
}
})();
