/**
 * FastBite Confirmation & Notice Dialog Helper
 * Accessible, promise-based modal dialogs replacing browser confirm() and alert().
 * Uses Bootstrap 5.3 Modals when available, falling back to native <dialog> in owner workspace.
 */
(function () {
    'use strict';

    let isBusy = false;
    let swallowedQueue = [];

    function getOpener() {
        return document.activeElement && typeof document.activeElement.focus === 'function'
            ? document.activeElement
            : null;
    }

    function restoreFocus(opener) {
        if (opener && typeof opener.focus === 'function') {
            try { opener.focus(); } catch (e) { }
        }
    }

    function getBootstrapModal() {
        let modalEl = document.getElementById('fb-confirm-modal');
        if (!modalEl) {
            modalEl = document.createElement('div');
            modalEl.id = 'fb-confirm-modal';
            modalEl.className = 'modal fade fb-dialog-modal';
            modalEl.tabIndex = -1;
            modalEl.setAttribute('aria-hidden', 'true');
            modalEl.setAttribute('role', 'dialog');
            modalEl.innerHTML =
                '<div class="modal-dialog modal-dialog-centered">' +
                '  <div class="modal-content">' +
                '    <div class="modal-header">' +
                '      <h5 class="modal-title fb-dialog-title"></h5>' +
                '      <button type="button" class="btn-close fb-dialog-close-btn" data-bs-dismiss="modal" aria-label="Close"></button>' +
                '    </div>' +
                '    <div class="modal-body fb-dialog-message" style="white-space: pre-line;"></div>' +
                '    <div class="modal-footer">' +
                '      <button type="button" class="btn btn-secondary fb-dialog-cancel-btn" data-bs-dismiss="modal">Cancel</button>' +
                '      <button type="button" class="btn fb-dialog-confirm-btn">Confirm</button>' +
                '    </div>' +
                '  </div>' +
                '</div>';
            document.body.appendChild(modalEl);
        }
        return modalEl;
    }

    function getNativeDialog() {
        let dlg = document.getElementById('fb-confirm-native-dialog');
        if (!dlg) {
            dlg = document.createElement('dialog');
            dlg.id = 'fb-confirm-native-dialog';
            dlg.className = 'fb-native-dialog';
            dlg.innerHTML =
                '<form method="dialog" class="fb-native-dialog-form">' +
                '  <h3 class="fb-dialog-title" style="margin-top:0;"></h3>' +
                '  <p class="fb-dialog-message" style="white-space: pre-line; line-height: 1.5;"></p>' +
                '  <div class="dialog-actions fb-dialog-actions" style="display:flex; gap:12px; justify-content:flex-end; margin-top:25px; flex-wrap:wrap;">' +
                '    <button type="button" class="button secondary fb-dialog-cancel-btn">Cancel</button>' +
                '    <button type="button" class="button fb-dialog-confirm-btn">Confirm</button>' +
                '  </div>' +
                '</form>';
            document.body.appendChild(dlg);
        }
        return dlg;
    }

    function showBootstrapDialog(opts) {
        return new Promise(function (resolve) {
            const opener = getOpener();
            const modalEl = getBootstrapModal();

            const titleEl = modalEl.querySelector('.fb-dialog-title');
            const msgEl = modalEl.querySelector('.fb-dialog-message');
            const headerEl = modalEl.querySelector('.modal-header');
            const cancelBtn = modalEl.querySelector('.fb-dialog-cancel-btn');
            const confirmBtn = modalEl.querySelector('.fb-dialog-confirm-btn');

            if (opts.title) {
                titleEl.textContent = opts.title;
                headerEl.style.display = '';
            } else {
                headerEl.style.display = 'none';
            }
            msgEl.textContent = opts.message || '';

            if (opts.isNotice) {
                cancelBtn.style.display = 'none';
                confirmBtn.textContent = opts.confirmText || 'OK';
                confirmBtn.className = 'btn btn-primary fb-dialog-confirm-btn';
            } else {
                cancelBtn.style.display = '';
                cancelBtn.textContent = opts.cancelText || 'Cancel';
                confirmBtn.textContent = opts.confirmText || 'Confirm';
                confirmBtn.className = opts.danger
                    ? 'btn btn-danger fb-dialog-confirm-btn'
                    : 'btn btn-primary fb-dialog-confirm-btn';
            }

            let outcome = false;
            let settled = false;
            let isFullyShown = false;
            let bsModal = window.bootstrap.Modal.getInstance(modalEl);
            if (!bsModal) {
                bsModal = new window.bootstrap.Modal(modalEl, { backdrop: 'static', keyboard: true });
            }

            function detachInteractive() {
                confirmBtn.removeEventListener('click', onConfirm);
                cancelBtn.removeEventListener('click', onCancel);
                modalEl.removeEventListener('keydown', onKeyDown);
            }

            function cleanupAll() {
                detachInteractive();
                modalEl.removeEventListener('shown.bs.modal', onShown);
                modalEl.removeEventListener('hidden.bs.modal', onHidden);
            }

            function onShown() {
                isFullyShown = true;
                if (opts.isNotice || !opts.danger) {
                    confirmBtn.focus();
                } else {
                    cancelBtn.focus();
                }
            }

            function onHidden() {
                if (!settled) {
                    settled = true;
                    cleanupAll();
                    isBusy = false;
                    restoreFocus(opener);
                    resolve(outcome);
                    const pending = swallowedQueue;
                    swallowedQueue = [];
                    pending.forEach(function (fn) {
                        try { fn(); } catch (err) { }
                    });
                }
            }

            function requestHide() {
                detachInteractive();
                if (!isFullyShown) {
                    modalEl.addEventListener('shown.bs.modal', function () {
                        bsModal.hide();
                    }, { once: true });
                } else {
                    bsModal.hide();
                }
            }

            function onConfirm(e) {
                if (e) e.preventDefault();
                outcome = true;
                requestHide();
            }

            function onCancel(e) {
                if (e) e.preventDefault();
                outcome = false;
                requestHide();
            }

            function onKeyDown(e) {
                if (e.key === 'Escape') {
                    e.preventDefault();
                    outcome = false;
                    requestHide();
                } else if (e.key === 'Enter') {
                    if (document.activeElement === confirmBtn) {
                        e.preventDefault();
                        outcome = true;
                        requestHide();
                    } else if (document.activeElement === cancelBtn) {
                        e.preventDefault();
                        outcome = false;
                        requestHide();
                    } else {
                        // Enter does not confirm unless the confirm button has focus
                        e.preventDefault();
                    }
                }
            }

            modalEl.addEventListener('shown.bs.modal', onShown);
            modalEl.addEventListener('hidden.bs.modal', onHidden);
            confirmBtn.addEventListener('click', onConfirm);
            cancelBtn.addEventListener('click', onCancel);
            modalEl.addEventListener('keydown', onKeyDown);

            try {
                bsModal.show();
            } catch (err) {
                cleanupAll();
                isBusy = false;
                restoreFocus(opener);
                resolve(false);
                const pending = swallowedQueue;
                swallowedQueue = [];
                pending.forEach(function (fn) {
                    try { fn(); } catch (e) { }
                });
            }
        });
    }

    function showNativeDialog(opts) {
        return new Promise(function (resolve) {
            const opener = getOpener();
            const dlg = getNativeDialog();

            const titleEl = dlg.querySelector('.fb-dialog-title');
            const msgEl = dlg.querySelector('.fb-dialog-message');
            const cancelBtn = dlg.querySelector('.fb-dialog-cancel-btn');
            const confirmBtn = dlg.querySelector('.fb-dialog-confirm-btn');

            if (opts.title) {
                titleEl.textContent = opts.title;
                titleEl.style.display = '';
            } else {
                titleEl.style.display = 'none';
            }
            msgEl.textContent = opts.message || '';

            if (opts.isNotice) {
                cancelBtn.style.display = 'none';
                confirmBtn.textContent = opts.confirmText || 'OK';
                confirmBtn.className = 'button fb-dialog-confirm-btn';
            } else {
                cancelBtn.style.display = '';
                cancelBtn.textContent = opts.cancelText || 'Cancel';
                confirmBtn.textContent = opts.confirmText || 'Confirm';
                confirmBtn.className = opts.danger
                    ? 'danger-button fb-dialog-confirm-btn'
                    : 'button fb-dialog-confirm-btn';
            }

            let outcome = false;
            let settled = false;

            function detachInteractive() {
                confirmBtn.removeEventListener('click', onConfirm);
                cancelBtn.removeEventListener('click', onCancel);
                dlg.removeEventListener('keydown', onKeyDown);
            }

            function cleanupAll() {
                detachInteractive();
                dlg.removeEventListener('close', onClose);
                dlg.removeEventListener('cancel', onCancelEvent);
            }

            function onClose() {
                if (!settled) {
                    settled = true;
                    cleanupAll();
                    isBusy = false;
                    restoreFocus(opener);
                    resolve(outcome);
                    const pending = swallowedQueue;
                    swallowedQueue = [];
                    pending.forEach(function (fn) {
                        try { fn(); } catch (err) { }
                    });
                }
            }

            function onCancelEvent(e) {
                if (e) e.preventDefault();
                outcome = false;
                detachInteractive();
                try { dlg.close(); } catch (err) { }
            }

            function onConfirm(e) {
                if (e) e.preventDefault();
                outcome = true;
                detachInteractive();
                try { dlg.close(); } catch (err) { }
            }

            function onCancel(e) {
                if (e) e.preventDefault();
                outcome = false;
                detachInteractive();
                try { dlg.close(); } catch (err) { }
            }

            function onKeyDown(e) {
                if (e.key === 'Escape') {
                    e.preventDefault();
                    outcome = false;
                    detachInteractive();
                    try { dlg.close(); } catch (err) { }
                } else if (e.key === 'Enter') {
                    if (document.activeElement === confirmBtn) {
                        e.preventDefault();
                        outcome = true;
                        detachInteractive();
                        try { dlg.close(); } catch (err) { }
                    } else if (document.activeElement === cancelBtn) {
                        e.preventDefault();
                        outcome = false;
                        detachInteractive();
                        try { dlg.close(); } catch (err) { }
                    } else {
                        e.preventDefault();
                    }
                }
            }

            dlg.addEventListener('close', onClose);
            dlg.addEventListener('cancel', onCancelEvent);
            confirmBtn.addEventListener('click', onConfirm);
            cancelBtn.addEventListener('click', onCancel);
            dlg.addEventListener('keydown', onKeyDown);

            try {
                dlg.showModal();
                if (opts.isNotice || !opts.danger) {
                    confirmBtn.focus();
                } else {
                    cancelBtn.focus();
                }
            } catch (err) {
                cleanupAll();
                isBusy = false;
                restoreFocus(opener);
                resolve(false);
                const pending = swallowedQueue;
                swallowedQueue = [];
                pending.forEach(function (fn) {
                    try { fn(); } catch (e) { }
                });
            }
        });
    }

    function showDialog(opts) {
        if (isBusy) {
            return new Promise(function (resolve) {
                swallowedQueue.push(function () {
                    resolve(false);
                });
            });
        }
        isBusy = true;
        if (window.bootstrap && window.bootstrap.Modal) {
            return showBootstrapDialog(opts);
        }
        return showNativeDialog(opts);
    }

    window.fbConfirm = function (options) {
        const opts = options || {};
        return showDialog({
            title: opts.title || null,
            message: opts.message || '',
            confirmText: opts.confirmText || null,
            cancelText: opts.cancelText || null,
            danger: Boolean(opts.danger),
            isNotice: false
        });
    };

    window.fbNotice = function (options) {
        let opts = options;
        if (typeof options === 'string') {
            opts = { message: options };
        } else {
            opts = options || {};
        }
        return showDialog({
            title: opts.title || null,
            message: opts.message || '',
            confirmText: opts.okText || 'OK',
            cancelText: null,
            danger: false,
            isNotice: true
        }).then(function () { });
    };

})();
