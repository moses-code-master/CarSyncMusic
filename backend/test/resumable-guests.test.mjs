import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { WebSocket } from 'ws';
import { createServer } from '../src/server.mjs';
import { digest } from '../src/model.mjs';

async function fixture(t, video = false) {
  const dir = mkdtempSync(join(tmpdir(), 'carsync-resume-guest-'));
  let app = createServer({ dataDir: dir, port: 0 }); await app.listen();
  let base = `http://127.0.0.1:${app.server.address().port}`;
  t.after(async () => { await app.close(); rmSync(dir, { recursive: true, force: true }); });
  const post = async path => (await fetch(base + path, { method: 'POST', body: '{}' })).json();
  const host = await post('/sessions'), guest = await post(`/sessions/${host.code}/join`), other = await post(`/sessions/${host.code}/join`);
  const bytes = Buffer.alloc(600000, 32), hash = digest(bytes);
  const track = { hash, title: 'Guest track', artist: 'Test', durationMs: 60000, mediaType: video ? 'video' : 'audio', mimeType: video ? 'video/mp4' : 'audio/mpeg', sizeBytes: bytes.length };
  async function connect(who) {
    const messages = [], ws = new WebSocket(base.replace('http', 'ws') + '/ws');
    ws.on('message', data => messages.push(JSON.parse(data)));
    await new Promise((resolve, reject) => { ws.once('open', resolve); ws.once('error', reject); });
    ws.send(JSON.stringify({ type: 'auth', ...who, mediaCapabilities: ['audio','video'] }));
    const wait = async predicate => {
      for (let i = 0; i < 300; i++) {
        const index = messages.findIndex(predicate);
        if (index >= 0) return messages.splice(index, 1)[0];
        await new Promise(r => setTimeout(r, 10));
      }
      throw Error('Message timed out: ' + JSON.stringify(messages.slice(-2)));
    };
    await wait(x => x.type === 'state');
    return { ws, wait, send(type, payload) { const id = randomUUID(); ws.send(JSON.stringify({ type, id, ...payload })); return id; } };
  }
  let h = await connect(host), g = await connect(guest);
  const setting = h.send('settings', { sharing: true, guestAdditions: true });
  await h.wait(x => x.type === 'ack' && x.id === setting);
  const grant = async () => { g.send('guest_add', { track }); await g.wait(x => x.type === 'guest_upload_granted' && x.hash === hash); };
  const url = () => `${base}/sessions/${host.code}/${video ? 'video' : 'audio'}/${hash}`;
  const headers = who => ({ Authorization: `Bearer ${who.token}` });
  return { host, guest, other, bytes, hash, url, headers, grant,
    patch: (who, start, end) => fetch(url(), { method: 'PATCH', headers: { ...headers(who), 'Content-Range': `bytes ${start}-${end}/${bytes.length}` }, body: bytes.subarray(start, end + 1) }),
    status: who => fetch(url() + '?upload=1&size=' + bytes.length, { headers: headers(who) }),
    snapshot: async () => (await fetch(`${base}/sessions/${host.code}`, { headers: headers(host) })).json(),
    async restart() {
      await app.close(); app = createServer({ dataDir: dir, port: 0 }); await app.listen();
      base = `http://127.0.0.1:${app.server.address().port}`;
      h = await connect(host); g = await connect(guest);
    },
    async sharing(enabled) {
      const id = h.send('settings', { sharing: enabled, guestAdditions: enabled });
      await h.wait(x => x.type === 'ack' && x.id === id);
    }
  };
}

test('guest refreshes grant after restart, resumes partial MP3 and publishes exactly once', async t => {
  const f = await fixture(t); await f.grant();
  assert.equal((await f.patch(f.other, 0, 199999)).status, 404);
  assert.equal((await f.status(f.other)).status, 404);
  assert.equal((await f.patch(f.guest, 0, 199999)).status, 200);
  assert.equal((await f.snapshot()).playlist.length, 0);
  await f.restart();
  assert.equal((await f.status(f.guest)).status, 404, 'Restart requires a fresh guest grant');
  await f.grant();
  assert.deepEqual(await (await f.status(f.guest)).json(), { offset: 200000, complete: false });
  assert.equal((await f.patch(f.guest, 200000, 599999)).status, 200);
  assert.deepEqual(await (await f.status(f.guest)).json(), { offset: 600000, complete: true }, 'Lost completion acknowledgement remains recoverable after grant is consumed');
  const s = await f.snapshot(); assert.equal(s.playlist.length, 1); assert.ok(s.audioAvailable.includes(f.hash));
  const response = await fetch(f.url(), { headers: f.headers(f.other) });
  assert.deepEqual(Buffer.from(await response.arrayBuffer()), f.bytes);
  assert.equal((await f.patch(f.other, 0, 199999)).status, 403);
});

test('revoked guest sharing hides saved chunks and removes them before a new grant', async t => {
  const f = await fixture(t); await f.grant(); await f.patch(f.guest, 0, 199999);
  await f.sharing(false);
  assert.equal((await f.status(f.guest)).status, 403);
  assert.equal((await f.patch(f.guest, 200000, 599999)).status, 403);
  await f.sharing(true); await f.grant();
  assert.deepEqual(await (await f.status(f.guest)).json(), { offset: 0, complete: false });
});

test('approved MP4 guest uploads retain video routing and authenticated streaming', async t => {
  const f = await fixture(t, true); await f.grant();
  await f.patch(f.guest, 0, 199999); await f.patch(f.guest, 200000, 599999);
  const s = await f.snapshot(); assert.equal(s.playlist[0].mediaType, 'video');
  const response = await fetch(f.url(), { headers: f.headers(f.other) });
  assert.equal(response.headers.get('content-type'), 'video/mp4');
  assert.deepEqual(Buffer.from(await response.arrayBuffer()), f.bytes);
});
