'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { once } = require('node:events');

test('real Node server and installed dependency start, authenticate and reject malformed imports', async () => {
  process.env.PORT = '0'; process.env.HOST = '127.0.0.1';
  process.env.MINERADIO_API_TOKEN = 'test-only-local-smoke-token-0000000000000000000';
  const server = require('../server');
  try {
    if (!server.listening) await once(server, 'listening');
    const base = 'http://127.0.0.1:' + server.address().port;
    const headers = { Authorization: 'Bearer ' + process.env.MINERADIO_API_TOKEN, 'Content-Type': 'application/json' };
    const denied = await fetch(base + '/api/health'); assert.equal(denied.status, 401);
    const health = await fetch(base + '/api/health', { headers });
    assert.equal(health.status, 200); assert.equal((await health.json()).service, 'mineradio');
    for (const route of ['/api/login/cookie', '/api/qq/login/cookie']) {
      const response = await fetch(base + route, { method: 'POST', headers, body: JSON.stringify({ cookie: '' }) });
      assert.equal(response.status, 400); assert.equal((await response.json()).loggedIn, false);
    }
    const blocked = await fetch(base + '/api/update/download', { headers }); assert.equal(blocked.status, 403);
  } finally { await new Promise(resolve => server.close(resolve)); }
});
