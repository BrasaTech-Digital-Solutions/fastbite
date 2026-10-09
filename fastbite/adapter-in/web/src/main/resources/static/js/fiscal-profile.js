// Business details form: checks the tax ID as it is typed, fills in the province from the postal
// code and keeps the ticket preview in step with the form. The server checks everything again.
(() => {
'use strict';
const form = document.getElementById('legal-form');
if (!form) return;

const PERSON_LETTERS = 'TRWAGMYFPDXBNJZSQVHLCKE';
const COMPANY_LETTERS = 'JABCDEFGHI';

// Mirrors SpanishTaxId on the server; returns 'DNI', 'NIE', 'COMPANY', 'OTHER_PERSON' or null.
function taxIdKind(input) {
  let value = (input || '').toUpperCase().replace(/[\s.\-/]/g, '');
  if (value.startsWith('ES') && value.length === 11) value = value.slice(2);
  const letterOf = digits => PERSON_LETTERS[Number(digits) % 23];
  if (/^\d{8}[A-Z]$/.test(value)) return letterOf(value.slice(0, 8)) === value[8] ? 'DNI' : null;
  if (/^[XYZ]\d{7}[A-Z]$/.test(value)) return letterOf('XYZ'.indexOf(value[0]) + value.slice(1, 8)) === value[8] ? 'NIE' : null;
  if (/^[KLM]\d{7}[A-Z]$/.test(value)) return letterOf(value.slice(1, 8)) === value[8] ? 'OTHER_PERSON' : null;
  if (/^[ABCDEFGHJNPQRSUVW]\d{7}[0-9A-J]$/.test(value)) {
    let sum = 0;
    for (let i = 0; i < 7; i++) {
      const digit = Number(value[i + 1]);
      if (i % 2 === 0) { const doubled = digit * 2; sum += Math.floor(doubled / 10) + doubled % 10; } else { sum += digit; }
    }
    const control = (10 - sum % 10) % 10;
    const given = value[8];
    const letterGiven = /[A-Z]/.test(given);
    if ('NPQRSW'.includes(value[0]) && !letterGiven) return null;
    if ('ABEH'.includes(value[0]) && letterGiven) return null;
    return (letterGiven ? COMPANY_LETTERS[control] === given : String(control) === given) ? 'COMPANY' : null;
  }
  return null;
}

const $ = id => document.getElementById(id);
const field = name => form.elements[name];
const text = (id, value) => { const element = $(id); if (element) element.textContent = value; };

const taxId = field('taxId');
const status = $('taxId-status');
const legalLabel = $('legalName-label');
const statusMessages = {DNI: form.dataset.msgDni, NIE: form.dataset.msgNie, COMPANY: form.dataset.msgCompany, OTHER_PERSON: form.dataset.msgOther};

function checkTaxId(final) {
  const value = taxId.value.trim();
  const kind = taxIdKind(value);
  status.classList.toggle('valid', !!kind);
  status.classList.toggle('invalid', !kind && final && value.length > 0);
  status.textContent = kind ? statusMessages[kind] : (final && value ? form.dataset.msgInvalid : '');
  taxId.classList.toggle('invalid', !kind && final && value.length > 0);
  taxId.setAttribute('aria-invalid', String(!kind && final && value.length > 0));
  legalLabel.textContent = !kind ? form.dataset.labelUnknown : kind === 'COMPANY' ? form.dataset.labelCompany : form.dataset.labelPerson;
  if (kind) taxId.value = value.toUpperCase().replace(/[\s.\-/]/g, '').replace(/^ES(?=.{9}$)/, '');
}
taxId.addEventListener('input', () => checkTaxId(false));
taxId.addEventListener('blur', () => checkTaxId(true));

// The first two digits of a Spanish postal code name the province.
const postal = field('postalCode');
const province = field('province');
postal.addEventListener('input', () => {
  postal.value = postal.value.replace(/\D/g, '').slice(0, 5);
  if (postal.value.length >= 2) {
    const match = province.querySelector('option[data-prefix="' + postal.value.slice(0, 2) + '"]');
    if (match) province.value = match.value;
  }
});

const prefix = field('seriesPrefix');
prefix.addEventListener('input', () => {
  const start = prefix.selectionStart;
  prefix.value = prefix.value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 8);
  prefix.setSelectionRange(start, start);
});

const required = ['legalName', 'taxId', 'addressLine', 'postalCode', 'city', 'province', 'seriesPrefix'];
function refresh() {
  const trade = field('tradeName').value.trim();
  const legal = field('legalName').value.trim();
  text('pv-trade', trade || legal || '—');
  text('pv-legal', trade ? legal : '');
  text('pv-taxid', taxId.value.trim() || '—');
  text('pv-address', field('addressLine').value.trim() || '—');
  text('pv-postal', postal.value);
  text('pv-city', field('city').value.trim());
  const number = (prefix.value || 'T') + '-T' + form.dataset.year + '-000001';
  text('pv-number', number);
  text('series-preview', number);
  const rate = form.querySelector('input[name="defaultVatRate"]:checked');
  if (rate) text('pv-rate', parseInt(rate.value, 10) + '%');

  const filled = required.filter(name => field(name).value.trim() !== '').length;
  const bar = $('legal-progress-bar');
  bar.value = filled;
  const done = filled === required.length;
  bar.parentElement.classList.toggle('complete', done);
  text('legal-progress-text', done ? form.dataset.progressDone
      : form.dataset.progress.replace('{0}', filled).replace('{1}', required.length));
}
form.addEventListener('input', refresh);
form.addEventListener('change', refresh);
refresh();
})();
