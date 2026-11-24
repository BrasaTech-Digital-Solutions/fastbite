import http from 'node:http';
import crypto from 'node:crypto';

const PORT = Number(process.env.PORT || 8099);
const API_TOKEN = process.env.MOCK_API_TOKEN || 'local-digitalsign-mock-token';
const WEBHOOK_SECRET = process.env.MOCK_WEBHOOK_SECRET || 'local-digitalsign-mock-webhook-secret';
const WEBHOOK_URL = process.env.MOCK_WEBHOOK_URL || 'http://production:8080/api/webhooks/digital-sign';
const TEMPLATE_ID = process.env.MOCK_TEMPLATE_ID || 'tpl_verifactu_anexo1_local';
const SIGNER_ROLE = process.env.MOCK_SIGNER_ROLE || 'Representante';

const requests = new Map();
const idempotency = new Map();
let defaultTestMode = String(process.env.MOCK_TEST_MODE || 'false').toLowerCase() === 'true';

function minimalPdf(label) {
  const safe = String(label).replace(/[()\\]/g, '');
  const content = `%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Contents 4 0 R >> endobj\n4 0 obj << /Length ${safe.length + 12} >> stream\nBT (${safe}) Tj ET\nendstream endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n`;
  return Buffer.from(content, 'utf8');
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body)
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', chunk => chunks.push(chunk));
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function checkAuth(req, res) {
  const header = req.headers.authorization || '';
  if (header !== `Bearer ${API_TOKEN}`) {
    sendJson(res, 401, { error: { code: 'unauthorized', message: 'Invalid bearer token' } });
    return false;
  }
  return true;
}

function validateRedirectUrl(raw) {
  if (raw === undefined || raw === null) {
    return { valid: true, value: null };
  }
  const trimmed = String(raw).trim();
  if (!trimmed) {
    return { valid: true, value: null };
  }
  try {
    const parsed = new URL(trimmed);
    if (parsed.username || parsed.password) {
      return { valid: false, value: null };
    }
    const host = (parsed.hostname || '').toLowerCase();
    const isLocalhost = host === 'localhost' || host === '127.0.0.1' || host.endsWith('.localhost');
    if (parsed.protocol === 'https:' || (parsed.protocol === 'http:' && isLocalhost)) {
      return { valid: true, value: trimmed };
    }
    return { valid: false, value: null };
  } catch {
    return { valid: false, value: null };
  }
}

function applyCompletion(stored, targetStatus, opts = {}) {
  const nowIso = new Date().toISOString();
  stored.status = targetStatus;
  if (typeof opts.test === 'boolean') {
    stored.test = opts.test;
  }
  if (targetStatus === 'COMPLETED') {
    stored.completedAt = nowIso;
    stored.documents = { signedDocument: true, evidenceReport: true };
    for (const s of stored.signers) {
      const certNif = String(opts.certificateNif || s.nif || '').toUpperCase();
      s.status = 'SIGNED';
      s.signedAt = nowIso;
      s.certificateStatus = 'VERIFIED';
      s.certificate = {
        subject: `CN=REPRESENTANTE MOCK, SERIALNUMBER=IDCES-12345678Z, 2.5.4.97=VATES-${certNif}`,
        organizationIdentifier: `VATES-${certNif}`,
        serialNumber: 'IDCES-12345678Z'
      };
    }
  } else if (targetStatus === 'DECLINED') {
    for (const s of stored.signers) {
      s.status = 'DECLINED';
    }
  }
}

function templatePayload(id) {
  return {
    id,
    name: 'Anexo I VERI*FACTU (local mock)',
    signers: [
      { index: 0, role: SIGNER_ROLE, certificate: true, files: [] }
    ],
    fields: [
      { key: 'fld_legal_name', label: 'Razón social', type: 'TEXT', required: true, validation: 'NONE' },
      { key: 'fld_nif', label: 'NIF', type: 'TEXT', required: true, validation: 'NONE' },
      { key: 'fld_address', label: 'Dirección', type: 'TEXT', required: true, validation: 'NONE' },
      { key: 'fld_brasatech_name', label: 'Colaborador social', type: 'TEXT', required: true, validation: 'NONE' },
      { key: 'fld_brasatech_nif', label: 'NIF colaborador', type: 'TEXT', required: true, validation: 'NONE' },
      { key: 'fld_brasatech_address', label: 'Dirección colaborador', type: 'TEXT', required: true, validation: 'NONE' }
    ]
  };
}

function postWebhook(eventType, requestId, webhookUrl) {
  const eventId = `evt_mock_${crypto.randomUUID()}`;
  const rawBody = Buffer.from(JSON.stringify({
    id: eventId,
    type: eventType,
    data: { requestId }
  }), 'utf8');
  const ts = Math.floor(Date.now() / 1000);
  const sig = crypto
    .createHmac('sha256', Buffer.from(WEBHOOK_SECRET, 'utf8'))
    .update(Buffer.from(`${ts}.`, 'ascii'))
    .update(rawBody)
    .digest('hex');

  return new Promise((resolve, reject) => {
    const target = new URL(webhookUrl || WEBHOOK_URL);
    const req = http.request({
      hostname: target.hostname,
      port: target.port || 80,
      path: target.pathname + target.search,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': rawBody.length,
        'DigitalSign-Signature': `t=${ts},v1=${sig}`,
        'DigitalSign-Event-Id': eventId
      },
      timeout: 8000
    }, res => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => resolve({
        status: res.statusCode || 0,
        body: Buffer.concat(chunks).toString('utf8')
      }));
    });
    req.on('error', reject);
    req.on('timeout', () => {
      req.destroy(new Error('Webhook request timed out'));
    });
    req.write(rawBody);
    req.end();
  });
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || '/', `http://localhost:${PORT}`);
    const path = url.pathname;
    const method = req.method || 'GET';

    if (method === 'GET' && path === '/health') {
      return sendJson(res, 200, { status: 'ok', test: defaultTestMode });
    }

    if (method === 'POST' && path === '/_mock/test-mode') {
      const raw = await readBody(req);
      let opts = {};
      if (raw && raw.trim()) {
        opts = JSON.parse(raw);
      }
      if (typeof opts.test === 'boolean') {
        defaultTestMode = opts.test;
      }
      return sendJson(res, 200, { test: defaultTestMode });
    }

    const mockSignCompleteMatch = path.match(/^\/_mock\/sign\/([^/]+)\/complete$/);
    if (method === 'POST' && mockSignCompleteMatch) {
      const id = decodeURIComponent(mockSignCompleteMatch[1]);
      const stored = requests.get(id);
      if (!stored) {
        return sendJson(res, 404, { error: 'request_not_found' });
      }
      const raw = await readBody(req);
      let action = 'signed';
      if (raw && raw.trim()) {
        if ((req.headers['content-type'] || '').includes('application/json')) {
          const parsed = JSON.parse(raw);
          if (parsed && parsed.action) {
            action = String(parsed.action).toLowerCase();
          }
        } else {
          const params = new URLSearchParams(raw);
          if (params.get('action')) {
            action = String(params.get('action')).toLowerCase();
          }
        }
      }
      const isDeclined = action === 'declined';
      const targetStatus = isDeclined ? 'DECLINED' : 'COMPLETED';
      const redirectStatus = isDeclined ? 'declined' : 'signed';
      applyCompletion(stored, targetStatus);
      const eventType = targetStatus === 'COMPLETED' ? 'request.completed' : `request.${targetStatus.toLowerCase()}`;
      try {
        await postWebhook(eventType, id);
      } catch {
        // Ignore webhook delivery failures during interactive browser completion; return endpoint syncs directly.
      }
      if (stored.redirectUrl) {
        const target = new URL(stored.redirectUrl);
        target.searchParams.set('requestId', id);
        target.searchParams.set('status', redirectStatus);
        res.writeHead(303, { Location: target.toString() });
        return res.end();
      }
      const doneBody = `<!doctype html><html><body><h1>Digital Sign Local Mock</h1><p>Status: ${escapeHtml(redirectStatus)}</p></body></html>`;
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      return res.end(doneBody);
    }

    if (method === 'GET' && path.startsWith('/_mock/sign/')) {
      const id = decodeURIComponent(path.slice('/_mock/sign/'.length));
      const stored = requests.get(id);
      const title = stored ? stored.title : 'VERI*FACTU';
      const redirectInfo = stored && stored.redirectUrl
        ? `<p>Return URL: <code>${escapeHtml(stored.redirectUrl)}</code></p>`
        : '<p>No return URL configured.</p>';
      const actionUrl = `/_mock/sign/${encodeURIComponent(id)}/complete`;
      const body = `<!doctype html><html lang="es"><head><meta charset="utf-8"><title>Digital Sign Mock</title></head><body><h1>Digital Sign Local Mock</h1><p>Request: ${escapeHtml(id)}</p><p>Title: ${escapeHtml(title)}</p>${redirectInfo}<form method="post" action="${escapeHtml(actionUrl)}"><button type="submit" name="action" value="signed" id="mock-sign-btn">Firmar (mock)</button> <button type="submit" name="action" value="declined" id="mock-decline-btn">Rechazar (mock)</button></form></body></html>`;
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      return res.end(body);
    }

    if (method === 'POST' && path.startsWith('/_mock/complete/')) {
      const id = decodeURIComponent(path.slice('/_mock/complete/'.length));
      const stored = requests.get(id);
      if (!stored) {
        return sendJson(res, 404, { error: 'request_not_found' });
      }
      const raw = await readBody(req);
      let opts = {};
      if (raw && raw.trim()) {
        opts = JSON.parse(raw);
      }
      const targetStatus = String(opts.status || 'COMPLETED').toUpperCase();
      applyCompletion(stored, targetStatus, opts);
      const eventType = targetStatus === 'COMPLETED' ? 'request.completed' : `request.${targetStatus.toLowerCase()}`;
      const webhookResult = await postWebhook(eventType, id, opts.webhookUrl);
      return sendJson(res, 200, {
        requestId: id,
        status: stored.status,
        test: stored.test,
        webhookStatus: webhookResult.status,
        webhookBody: webhookResult.body
      });
    }

    if (path.startsWith('/api/v1/')) {
      if (!checkAuth(req, res)) {
        return;
      }

      if (method === 'GET' && path.startsWith('/api/v1/templates/')) {
        const id = decodeURIComponent(path.slice('/api/v1/templates/'.length));
        if (id !== TEMPLATE_ID) {
          return sendJson(res, 404, { error: { code: 'template.notFound', message: 'Template not found' } });
        }
        return sendJson(res, 200, templatePayload(id));
      }

      if (method === 'GET' && path === '/api/v1/requests') {
        return sendJson(res, 200, { items: Array.from(requests.values()) });
      }

      if (method === 'POST' && path === '/api/v1/requests') {
        const raw = await readBody(req);
        const key = req.headers['idempotency-key'];
        if (key && idempotency.has(key)) {
          return sendJson(res, 200, requests.get(idempotency.get(key)));
        }
        const input = JSON.parse(raw || '{}');
        const redirectCheck = validateRedirectUrl(input.redirectUrl);
        if (!redirectCheck.valid) {
          return sendJson(res, 400, {
            error: {
              code: 'invalid_redirect_url',
              message: 'redirectUrl must use https (http is allowed only for localhost)',
              field: 'redirectUrl'
            }
          });
        }
        const id = `req_mock_${crypto.randomUUID()}`;
        const nowIso = new Date().toISOString();
        const signers = (input.signers || []).map(s => ({
          role: s.role,
          name: s.name,
          email: s.email,
          language: s.language || 'es',
          status: 'PENDING',
          signedAt: null,
          signingUrl: `http://127.0.0.1:${PORT}/_mock/sign/${id}`,
          files: [],
          signatureMethod: s.signatureMethod || 'CERTIFICATE',
          nif: s.nif || null,
          certificateStatus: 'PENDING',
          certificate: null
        }));
        const created = {
          id,
          status: 'IN_PROGRESS',
          externalReference: input.externalReference || null,
          title: input.title || 'VERI*FACTU',
          redirectUrl: redirectCheck.value,
          createdAt: nowIso,
          expiresAt: new Date(Date.now() + 30 * 86400_000).toISOString(),
          completedAt: null,
          signers,
          documents: { signedDocument: false, evidenceReport: false },
          test: defaultTestMode
        };
        requests.set(id, created);
        if (key) {
          idempotency.set(key, id);
        }
        return sendJson(res, 201, created);
      }

      const reqMatch = path.match(/^\/api\/v1\/requests\/([^/]+)$/);
      if (method === 'GET' && reqMatch) {
        const stored = requests.get(decodeURIComponent(reqMatch[1]));
        if (!stored) {
          return sendJson(res, 404, { error: { code: 'request.notFound', message: 'Request not found' } });
        }
        return sendJson(res, 200, stored);
      }

      const urlMatch = path.match(/^\/api\/v1\/requests\/([^/]+)\/signers\/([^/]+)\/signing-url$/);
      if (method === 'POST' && urlMatch) {
        const stored = requests.get(decodeURIComponent(urlMatch[1]));
        if (!stored) {
          return sendJson(res, 404, { error: { code: 'request.notFound', message: 'Request not found' } });
        }
        if (stored.status !== 'IN_PROGRESS') {
          return sendJson(res, 409, { error: { code: 'request.notInProgress', message: 'Request is not in progress' } });
        }
        return sendJson(res, 200, {
          signingUrl: `http://127.0.0.1:${PORT}/_mock/sign/${stored.id}`,
          expiresAt: new Date(Date.now() + 3600_000).toISOString()
        });
      }

      const signedMatch = path.match(/^\/api\/v1\/requests\/([^/]+)\/signed-document$/);
      if (method === 'GET' && signedMatch) {
        const stored = requests.get(decodeURIComponent(signedMatch[1]));
        if (!stored) {
          return sendJson(res, 404, { error: { code: 'request.notFound', message: 'Request not found' } });
        }
        if (stored.status !== 'COMPLETED') {
          return sendJson(res, 409, { error: { code: 'request.documentsNotReady', message: 'Documents not ready' } });
        }
        const pdf = minimalPdf(`Signed Anexo I ${stored.id}`);
        res.writeHead(200, { 'Content-Type': 'application/pdf', 'Content-Length': pdf.length });
        return res.end(pdf);
      }

      const evidenceMatch = path.match(/^\/api\/v1\/requests\/([^/]+)\/evidence-report$/);
      if (method === 'GET' && evidenceMatch) {
        const stored = requests.get(decodeURIComponent(evidenceMatch[1]));
        if (!stored) {
          return sendJson(res, 404, { error: { code: 'request.notFound', message: 'Request not found' } });
        }
        if (stored.status !== 'COMPLETED') {
          return sendJson(res, 409, { error: { code: 'request.documentsNotReady', message: 'Documents not ready' } });
        }
        const pdf = minimalPdf(`Evidence Report ${stored.id}`);
        res.writeHead(200, { 'Content-Type': 'application/pdf', 'Content-Length': pdf.length });
        return res.end(pdf);
      }
    }

    sendJson(res, 404, { error: { code: 'notFound', message: 'Not found' } });
  } catch (err) {
    sendJson(res, 500, { error: { code: 'internal', message: err && err.message ? err.message : 'Internal error' } });
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`digital-sign-mock listening on :${PORT}`);
});
