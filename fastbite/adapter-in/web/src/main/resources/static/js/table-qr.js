// Renders signed table QR SVGs on screen and wires the print button.
(() => {
'use strict';

document.querySelectorAll('.qr-code').forEach(box => {
  const qr = qrcode(0, 'M'); // smallest size that fits, medium error correction
  qr.addData(box.dataset.url);
  qr.make();
  box.innerHTML = qr.createSvgTag({ cellSize: 4, margin: 2, scalable: true });
});

const printBtn = document.getElementById('printQrCodesBtn');
if (printBtn) {
  printBtn.addEventListener('click', () => window.print());
}
})();
