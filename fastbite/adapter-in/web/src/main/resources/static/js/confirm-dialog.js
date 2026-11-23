/**
 * FastBite Confirmation & Notice Dialog Helper
 * Accessible, promise-based native <dialog> replacing browser confirm() and alert().
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

    function getNativeDialog() {
        let dlg = document.getElementById('fb-confirm-native-dialog');
        if (!dlg) {
            dlg = document.createElement('dialog');
            dlg.id = 'fb-confirm-native-dialog';
            dlg.className = 'fb-native-dialog';
            dlg.innerHTML =
                '<form method="dialog" class="fb-native-dialog-form">' +
                '  <h3 class="fb-dialog-title"></h3>' +
                '  <p class="fb-dialog-message"></p>' +
                '  <div class="dialog-actions fb-dialog-actions">' +
                '    <button type="button" class="button secondary fb-dialog-cancel-btn">Cancel</button>' +
                '    <button type="button" class="button fb-dialog-confirm-btn">Confirm</button>' +
                '  </div>' +
                '</form>';
            document.body.appendChild(dlg);
        }
        return dlg;
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
                titleEl.hidden = false;
            } else {
                titleEl.textContent = '';
                titleEl.hidden = true;
            }
            msgEl.textContent = opts.message || '';

            if (opts.isNotice) {
                cancelBtn.hidden = true;
                confirmBtn.textContent = opts.confirmText || 'OK';
                confirmBtn.className = 'button fb-dialog-confirm-btn';
            } else {
                cancelBtn.hidden = false;
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
