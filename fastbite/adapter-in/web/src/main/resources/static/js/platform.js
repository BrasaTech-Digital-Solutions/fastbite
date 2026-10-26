(() => {
 const send = document.getElementById('demo-send'); if (!send) return;
 const result = document.getElementById('demo-result');
 const container = document.querySelector('.interactive-demo');
 const placeholder = container?.dataset?.msgPlaceholder || 'Your kitchen ticket will appear here.';
 const sentTemplate = container?.dataset?.msgSent || '✓ Sample order #024 · Preparing · {items}. Your team can now follow it through to collection.';
 const buttons = document.querySelectorAll('[data-demo]');
 const getSample = (btn) => ({
  service: btn?.dataset?.service || (btn?.dataset?.demo === 'lunch' ? 'TAKEAWAY · SAMPLE PAYMENT CONFIRMED' : 'TABLE 6'),
  title: btn?.dataset?.title || (btn?.dataset?.demo === 'lunch' ? 'Lunch, ready to go.' : 'One lovely coffee break.'),
  items: btn?.dataset?.items || (btn?.dataset?.demo === 'lunch' ? '1 toasted sandwich + 1 fresh lemonade' : '1 flat white + 1 butter croissant'),
  total: btn?.dataset?.total || (btn?.dataset?.demo === 'lunch' ? '€10.50' : '€6.00')
 });
 let activeBtn = document.querySelector('[data-demo][aria-pressed="true"]') || buttons[0];
 buttons.forEach(button => button.addEventListener('click', () => {
  activeBtn = button;
  buttons.forEach(option => option.setAttribute('aria-pressed', String(option === button)));
  const sample = getSample(button);
  ['service','title','items','total'].forEach(key => {
   const el = document.getElementById('demo-' + key);
   if (el) el.textContent = sample[key];
  });
  result.textContent = placeholder;
  result.classList.remove('sent');
 }));
 send.addEventListener('click', () => {
  const sample = getSample(activeBtn);
  result.textContent = sentTemplate.replace('{items}', sample.items);
  result.classList.add('sent');
 });
})();
