const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/guest-ticket.js'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));

function harness(responses) {
    const elements = {
        'guest-ticket': {dataset: {label: 'Your ticket {0}', timeout: 'Ask at the counter'}},
        'guest-ticket-status': {}, 'guest-ticket-link': {hidden: true},
        'order-success-title': {hidden: true}, 'order-success-detail': {hidden: true},
        'order-progress-card': {hidden: true}
    };
    let now = 0, timer, onHide;
    const requests = [];
    const context = {
        document: {getElementById: id => elements[id]},
        window: {addEventListener: (event, callback) => { if (event === 'pagehide') onHide = callback; }},
        Date: {now: () => now}, getTenantPrefix: () => '/cafe',
        fetch: async url => { requests.push(url); return {ok: true, json: async () => responses.shift()}; },
        setTimeout: fn => { timer = fn; return 1; }, clearTimeout: () => { timer = null; }
    };
    vm.runInNewContext(source, context);
    return {elements, requests, tick: async value => { now = value; const fn = timer; timer = null; fn(); await flush(); },
        hide: () => onHide(), hasTimer: () => Boolean(timer)};
}

test('pending then ready shows the full number and signed link without fetching the ticket', async () => {
    const h = harness([{state: 'pending'}, {state: 'ready', number: 'TEST-T-000001', ticketUrl: '/cafe/ticket/1?s=v1.sig'}]);
    await flush();
    assert.equal(h.hasTimer(), true);
    await h.tick(3000);
    assert.equal(h.elements['guest-ticket-status'].textContent, 'Your ticket TEST-T-000001');
    assert.equal(h.elements['guest-ticket-link'].href, '/cafe/ticket/1?s=v1.sig');
    assert.equal(h.elements['guest-ticket-link'].hidden, false);
    assert.deepEqual(h.requests, ['/cafe/api/guest-ticket', '/cafe/api/guest-ticket']);
    assert.equal(h.hasTimer(), false);
});

test('pending stops after two minutes and navigation cancels polling', async () => {
    const h = harness([{state: 'pending'}, {state: 'pending'}]);
    await flush();
    await h.tick(120000);
    assert.equal(h.elements['guest-ticket-status'].textContent, 'Ask at the counter');
    assert.equal(h.hasTimer(), false);
    const other = harness([{state: 'pending'}]);
    await flush();
    other.hide();
    assert.equal(other.hasTimer(), false);
});
