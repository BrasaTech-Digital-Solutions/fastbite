// Guest allergy filter: hides the dishes that contain an allergen the guest chose. The choice is
// remembered on this device only. Dishes without allergen information stay visible and keep their
// "ask our staff" note, so a guest is never told a dish is safe when nobody said so.
(() => {
'use strict';
const STORAGE_KEY = 'fastbite.allergens';
const toggles = [...document.querySelectorAll('.allergen-toggle')];
if (!toggles.length) return;

const status = document.getElementById('allergenFilterStatus');
const statusText = document.getElementById('allergenFilterText');
const count = document.getElementById('allergenFilterCount');

function load() {
  try { return new Set(JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]')); } catch (e) { return new Set(); }
}
function save(selected) {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify([...selected])); } catch (e) { /* private mode */ }
}

let selected = load();

function apply() {
  toggles.forEach(button => {
    const on = selected.has(button.dataset.allergen);
    button.setAttribute('aria-pressed', String(on));
    button.classList.toggle('active', on);
  });
  let hidden = 0;
  document.querySelectorAll('[data-allergens-declared]').forEach(product => {
    const contains = (product.dataset.allergens || '').split(',').filter(Boolean);
    const hide = contains.some(code => selected.has(code));
    product.classList.toggle('allergen-hidden', hide);
    product.classList.toggle('allergen-uncertain', selected.size > 0 && product.dataset.allergensDeclared !== 'true');
    if (hide) hidden++;
  });
  const active = selected.size > 0;
  count.hidden = !active;
  count.textContent = selected.size;
  status.hidden = !active;
  if (active) {
    statusText.textContent = status.dataset.active.replace('{0}', selected.size) + ' · '
        + status.dataset.hidden.replace('{0}', hidden) + ' · ';
  }
}

toggles.forEach(button => button.addEventListener('click', () => {
  const code = button.dataset.allergen;
  if (selected.has(code)) selected.delete(code); else selected.add(code);
  save(selected);
  apply();
}));
document.querySelectorAll('[data-allergen-clear]').forEach(button => button.addEventListener('click', () => {
  selected = new Set();
  save(selected);
  apply();
}));
apply();
})();
