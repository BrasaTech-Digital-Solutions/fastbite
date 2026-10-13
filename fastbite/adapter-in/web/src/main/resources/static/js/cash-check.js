/*
 * The cash check: what the drawer should hold by the day's tickets, against what the cashier counted.
 * Amounts are worked out in cents so they add up exactly. Nothing is saved: closing the page forgets it.
 *
 * Markup: an element with data-cash-check and data-url (the day's cash, as JSON), inputs marked
 * data-cash="start|out|in|counted", note and coin inputs marked data-denomination, and outputs marked
 * data-cash-out="sales|refunds|asOf|expected|difference|status|error".
 */
(() => {
    // Written as tickets and the end-of-day report print them (23,20 €), whatever the language
    const euros = cents => (cents / 100).toLocaleString('es-ES', {style: 'currency', currency: 'EUR'});
    const cents = value => {
        const text = String(value ?? '').trim().replace(/\s/g, '').replace(',', '.');
        if (text === '') return null;
        const number = Number(text);
        return Number.isFinite(number) ? Math.round(number * 100) : null;
    };
    const fill = (template, value) => (template || '').replace('{0}', value);

    function bind(panel) {
        const field = name => panel.querySelector(`[data-cash="${name}"]`);
        const output = name => panel.querySelector(`[data-cash-out="${name}"]`);
        const show = (name, text) => { const element = output(name); if (element) element.textContent = text; };
        const denominations = [...panel.querySelectorAll('[data-denomination]')];
        let cashNet = null;

        /** Notes and coins counted one by one fill in the total; a total typed afterwards is kept. */
        function addUpDenominations() {
            const total = denominations.reduce((sum, input) =>
                sum + Math.round(Number(input.dataset.denomination) * 100) * Math.max(0, parseInt(input.value, 10) || 0), 0);
            field('counted').value = denominations.some(input => input.value.trim() !== '') ? (total / 100).toFixed(2) : '';
        }

        function recalc() {
            const status = output('status');
            status.classList.remove('is-match', 'is-over', 'is-short');
            if (cashNet === null) {
                show('expected', '—');
                show('difference', '—');
                status.textContent = '';
                return;
            }
            const expected = (cents(field('start').value) ?? 0) + cashNet
                - (cents(field('out').value) ?? 0) + (cents(field('in').value) ?? 0);
            show('expected', euros(expected));
            const counted = cents(field('counted').value);
            if (counted === null) {
                show('difference', '—');
                status.textContent = panel.dataset.pending;
                return;
            }
            const difference = counted - expected;
            show('difference', (difference > 0 ? '+' : '') + euros(difference));
            if (difference === 0) {
                status.textContent = panel.dataset.match;
                status.classList.add('is-match');
            } else if (difference > 0) {
                status.textContent = fill(panel.dataset.over, euros(difference));
                status.classList.add('is-over');
            } else {
                status.textContent = fill(panel.dataset.short, euros(-difference));
                status.classList.add('is-short');
            }
        }

        async function load() {
            try {
                const response = await fetch(panel.dataset.url, {headers: {Accept: 'application/json'}});
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                const day = await response.json();
                cashNet = cents(day.cashNet) ?? 0;
                // The owner's starting cash, unless the cashier already typed another one
                if (!panel.dataset.started) {
                    field('start').value = Number(day.cashFloat || 0).toFixed(2);
                    panel.dataset.started = 'yes';
                }
                show('sales', euros(cents(day.cashSales) ?? 0));
                show('refunds', euros(cents(day.cashRefunds) ?? 0));
                show('asOf', day.asOf ? fill(panel.dataset.asOf, day.asOf) : '');
                show('error', '');
            } catch (error) {
                cashNet = null;
                show('error', panel.dataset.error);
            }
            recalc();
        }

        panel.addEventListener('input', event => {
            if (event.target.matches('[data-denomination]')) addUpDenominations();
            recalc();
        });
        const modal = panel.closest('.modal');
        // In a dialog, the figures are fetched each time it opens, so they include the latest tickets
        if (modal) modal.addEventListener('show.bs.modal', load); else load();
    }

    document.querySelectorAll('[data-cash-check]').forEach(bind);
})();
