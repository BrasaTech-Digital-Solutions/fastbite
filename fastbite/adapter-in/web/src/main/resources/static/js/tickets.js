// Tickets page: reprint, a full invoice for a ticket already issued, refunds, and cancelling a
// ticket issued by mistake. Every action is done on the server; the page reloads to show it.
(() => {
'use strict';
const page = document.getElementById('tickets');
if (!page) return;

const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
const prefix = page.dataset.prefix || '';
let current = null;

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
    bootstrap.Modal.getOrCreateInstance(document.getElementById('invoiceModal')).show();
  } else if (button.dataset.action === 'refund') {
    const title = document.getElementById('refundTitle');
    title.textContent = title.dataset.template.replace('{0}', current.number);
    document.getElementById('refundForm').reset();
    document.getElementById('refundError').hidden = true;
    bootstrap.Modal.getOrCreateInstance(document.getElementById('refundModal')).show();
  } else if (button.dataset.action === 'cancel') {
    const title = document.getElementById('cancelTitle');
    title.textContent = title.dataset.template.replace('{0}', current.number);
    document.getElementById('cancelForm').reset();
    document.getElementById('cancelError').hidden = true;
    bootstrap.Modal.getOrCreateInstance(document.getElementById('cancelModal')).show();
  }
});

document.getElementById('cancelForm').addEventListener('submit', async event => {
  event.preventDefault();
  const submit = event.submitter;
  submit.disabled = true;
  try {
    const cancelled = await post(`/counter/api/tickets/${encodeURIComponent(current.id)}/cancel`,
        { reason: document.getElementById('cancelReason').value.trim() });
    bootstrap.Modal.getInstance(document.getElementById('cancelModal')).hide();
    // Nothing to print: the orders are unpaid again and can be charged at the counter
    sessionStorage.setItem('fastbite.ticketNotice', page.dataset.doneCancel.replace('{0}', cancelled.fullNumber || current.number));
    location.reload();
  } catch (error) {
    failed(document.getElementById('cancelError'), error);
  } finally {
    submit.disabled = false;
  }
});

document.getElementById('invoiceForm').addEventListener('submit', async event => {
  event.preventDefault();
  const submit = event.submitter;
  submit.disabled = true;
  try {
    const ticket = await post(`/counter/api/tickets/${encodeURIComponent(current.id)}/invoice`, invoiceDetails('inv'));
    bootstrap.Modal.getInstance(document.getElementById('invoiceModal')).hide();
    done(page.dataset.doneInvoice, ticket);
  } catch (error) {
    failed(document.getElementById('invoiceError'), error);
  } finally {
    submit.disabled = false;
  }
});

document.getElementById('refundForm').addEventListener('submit', async event => {
  event.preventDefault();
  const submit = event.submitter;
  submit.disabled = true;
  try {
    const ticket = await post(`/counter/api/tickets/${encodeURIComponent(current.id)}/refund`,
        { reason: document.getElementById('refundReason').value.trim() });
    bootstrap.Modal.getInstance(document.getElementById('refundModal')).hide();
    done(page.dataset.doneRefund, ticket);
  } catch (error) {
    failed(document.getElementById('refundError'), error);
  } finally {
    submit.disabled = false;
  }
});

// The notice of the last action survives the reload
const notice = sessionStorage.getItem('fastbite.ticketNotice');
if (notice) {
  sessionStorage.removeItem('fastbite.ticketNotice');
  const toast = document.createElement('div');
  toast.className = 'toast show align-items-center text-bg-success border-0';
  toast.setAttribute('role', 'status');
  toast.innerHTML = '<div class="d-flex"><div class="toast-body"></div></div>';
  toast.querySelector('.toast-body').textContent = notice;
  document.getElementById('toastContainer').appendChild(toast);
  setTimeout(() => toast.remove(), 5000);
}
})();
