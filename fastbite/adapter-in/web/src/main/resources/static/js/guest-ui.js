// The guest menu's own behaviour, without Bootstrap: native dialogs, the folding top bar and closable notices.
// Dialogs use showModal(): Escape closes them and the page behind is inert while one is open. Focus goes back to
// whatever opened the dialog. Everything here only changes attributes and classes.
(() => {
'use strict';

function openDialog(dialog, opener) {
    if (!dialog || dialog.open) return;
    dialog.fastbiteOpener = opener || document.activeElement;
    dialog.showModal();
}

function closeDialog(dialog) {
    if (dialog && dialog.open) dialog.close();
}

document.querySelectorAll('dialog.dialog').forEach(dialog => {
    dialog.addEventListener('close', () => {
        const opener = dialog.fastbiteOpener;
        dialog.fastbiteOpener = null;
        if (opener && opener.isConnected && typeof opener.focus === 'function') opener.focus();
    });
    // A click on the backdrop (outside the dialog's box) closes it, as before
    dialog.addEventListener('click', event => {
        if (event.target !== dialog) return;
        const box = dialog.getBoundingClientRect();
        const inside = event.clientX >= box.left && event.clientX <= box.right
            && event.clientY >= box.top && event.clientY <= box.bottom;
        if (!inside) closeDialog(dialog);
    });
});

document.addEventListener('click', event => {
    const opener = event.target.closest('[data-dialog-open]');
    if (opener) {
        openDialog(document.getElementById(opener.dataset.dialogOpen), opener);
        return;
    }
    const closer = event.target.closest('[data-dialog-close]');
    if (closer) {
        closeDialog(closer.closest('dialog'));
        return;
    }
    const noticeClose = event.target.closest('[data-notice-close]');
    if (noticeClose) {
        noticeClose.closest('.notice')?.remove();
        return;
    }
    const toastClose = event.target.closest('[data-toast-close]');
    if (toastClose) {
        toastClose.closest('.toast')?.remove();
    }
});

// Below 992px the links, cart and account fold behind a toggle
const toggle = document.querySelector('[data-topbar-toggle]');
if (toggle) {
    const content = document.getElementById(toggle.getAttribute('aria-controls'));
    toggle.addEventListener('click', () => {
        const open = toggle.getAttribute('aria-expanded') !== 'true';
        toggle.setAttribute('aria-expanded', String(open));
        content?.classList.toggle('is-open', open);
    });
}

// The account menu closes when focus or a click goes elsewhere, or on Escape
document.querySelectorAll('.topbar-account').forEach(menu => {
    document.addEventListener('click', event => { if (!menu.contains(event.target)) menu.open = false; });
    menu.addEventListener('keydown', event => {
        if (event.key === 'Escape' && menu.open) {
            menu.open = false;
            menu.querySelector('summary')?.focus();
        }
    });
});

window.FastBiteDialogs = { open: openDialog, close: closeDialog };
})();
