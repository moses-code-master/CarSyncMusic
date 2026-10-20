import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { WebSocket } from 'ws';
import { createServer } from '../src/server.mjs';

test('intercom retries acknowledge an existing floor, release unlocks guests and throttling provides denial', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'carsync-voice-retry-'));
  const app = createServer({ dataDir: dir, port: 0 });
  await app.listen();
  t.after(async () => { await app.close(); rmSync(dir, { recursive: true, force: true }); });
  const base = 'http://127.0.0.1:' + app.server.address().port;
  const host = await (await fetch(base + '/sessions', { method: 'POST', body: '{}' })).json();
  const guest = await (await fetch(base + '/sessions/' + host.code + '/join', { method: 'POST', body: '{}' })).json();
  const open = async identity => {
    const ws = new WebSocket(base.replace('http:', 'ws:') + '/ws');
    const messages = [];
    ws.on('message', data => messages.push(JSON.parse(data.toString())));
    await new Promise((resolve, reject) => { ws.once('open', resolve); ws.once('error', reject); });
    ws.send(JSON.stringify({ type: 'auth', code: identity.code, token: identity.token }));
    return { ws, messages };
  };
  const h = await open(host), g = await open(guest);
  const wait = async (peer, predicate) => {
    for (let i = 0; i < 250; i++) {
      const n = peer.messages.findIndex(predicate);
      if (n >= 0) return peer.messages.splice(n, 1)[0];
      await new Promise(r => setTimeout(r, 10));
    }
    throw Error('Voice event timeout: ' + JSON.stringify(peer.messages.slice(-5)));
  };
  await wait(g, m => m.type === 'state' && m.cars.length === 2);
  g.ws.send(JSON.stringify({ type: 'ptt_request' }));
  assert.equal((await wait(g, m => m.type === 'ptt_start')).speakerId, guest.memberId);
  await wait(h, m => m.type === 'ptt_start');
  // A repeat request from the same user must re-acknowledge their floor.
  g.ws.send(JSON.stringify({ type: 'ptt_request' }));
  assert.equal((await wait(g, m => m.type === 'ptt_start')).speakerId, guest.memberId);
  h.ws.send(JSON.stringify({ type: 'ptt_request' }));
  assert.match((await wait(h, m => m.type === 'ptt_denied')).reason, /busy/i);
  g.ws.send(JSON.stringify({ type: 'ptt_release' }));
  await wait(h, m => m.type === 'ptt_stop');
  h.ws.send(JSON.stringify({ type: 'ptt_request' }));
  assert.equal((await wait(h, m => m.type === 'ptt_start')).speakerId, host.memberId);
  h.ws.send(JSON.stringify({ type: 'ptt_release' }));
  await wait(g, m => m.type === 'ptt_stop');
  for (let n = 0; n < 11; n++) g.ws.send(JSON.stringify({ type: 'ptt_request' }));
  assert.match((await wait(g, m => m.type === 'ptt_denied' && /too many/i.test(m.reason))).reason, /wait one minute/i);
  h.ws.close(); g.ws.close();
});
