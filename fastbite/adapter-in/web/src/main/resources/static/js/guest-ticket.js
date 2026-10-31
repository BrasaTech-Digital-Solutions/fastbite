// Check only this session's order; stop after two minutes or on navigation.
(() => {
    const panel = document.getElementById('guest-ticket');
    if (!panel) return;
    const status = document.getElementById('guest-ticket-status');
    const link = document.getElementById('guest-ticket-link');
    const deadline = Date.now() + 120000;
    let timer;
    let stopped = false;
    async function check() {
        if (stopped) return;
        try {
            const response = await fetch(getTenantPrefix() + '/api/guest-ticket', {cache: 'no-store'});
            if (response.ok) {
                const data = await response.json();
                if (data.state === 'ready' && data.ticketUrl && data.number) {
                    status.textContent = panel.dataset.label.replace('{0}', data.number);
                    link.href = data.ticketUrl;
                    link.hidden = false;
                    ['order-success-title', 'order-success-detail', 'order-progress-card'].forEach(id => {
                        const element = document.getElementById(id);
                        if (element) element.hidden = false;
                    });
                    return;
                }
            }
        } catch (_) { /* Retry until the deadline. */ }
        if (Date.now() >= deadline) { status.textContent = panel.dataset.timeout; return; }
        timer = setTimeout(check, 3000);
    }
    window.addEventListener('pagehide', () => { stopped = true; clearTimeout(timer); });
    check();
})();
