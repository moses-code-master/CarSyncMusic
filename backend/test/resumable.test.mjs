import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, renameSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import http from 'node:http';
import { WebSocket } from 'ws';
import { createServer } from '../src/server.mjs';
import { digest } from '../src/model.mjs';

async function fixture(t) {
  const dir = mkdtempSync(join(tmpdir(), 'carsync-resume-'));
  let app = createServer({ dataDir: dir, port: 0 }); await app.listen();
  let base = `http://127.0.0.1:${app.server.address().port}`;
  t.after(async () => { await app.close(); rmSync(dir, { recursive: true, force: true }); });
  const host = await (await fetch(base + '/sessions', { method: 'POST', body: '{}' })).json();
  const guest = await (await fetch(`${base}/sessions/${host.code}/join`, { method: 'POST', body: '{}' })).json();
  const bytes = Buffer.alloc(600000, 73), hash = digest(bytes);
  const ws = new WebSocket(base.replace('http', 'ws') + '/ws');
  const messages = [];
  ws.on('message', x => messages.push(JSON.parse(x)));
  await new Promise(resolve => ws.on('open', resolve));
  ws.send(JSON.stringify({ type: 'auth', ...host }));
  async function wait(predicate) {
    for (let i = 0; i < 200; i++) {
      if (messages.some(predicate)) return;
      await new Promise(r => setTimeout(r, 10));
    }
    throw Error('State timed out');
  }
  await wait(x => x.type === 'state');
  ws.send(JSON.stringify({ type: 'playlist', id: 'playlist', playlist: [{ hash, title: 'Test', artist: 'Test', durationMs: 60000 }] }));
  await wait(x => x.type === 'state' && x.playlist.length === 1);
  ws.send(JSON.stringify({ type: 'settings', id: 'sharing-enabled', sharing: true }));
  await wait(x => x.type === 'state' && x.sharing);
  const url = () => `${base}/sessions/${host.code}/audio/${hash}`;
  const headers = { Authorization: `Bearer ${host.token}` };
  const status = async () => (await fetch(url() + '?upload=1&size=' + bytes.length, { headers })).json();
  const patch = (start, end, body = bytes.subarray(start, end + 1), token = host.token, total = bytes.length) => fetch(url(), {
    method: 'PATCH', headers: { Authorization: `Bearer ${token}`, 'Content-Range': `bytes ${start}-${end}/${total}` }, body
  });
  return { bytes, hash, host, guest, url, headers, status, patch, ws, wait, dir,
    async restart() {
      await app.close(); app = createServer({ dataDir: dir, port: 0 }); await app.listen();
      base = `http://127.0.0.1:${app.server.address().port}`;
    }
  };
}

test('chunk progress survives server restart, duplicate requests and a lost final response', async t => {
  const f = await fixture(t);
  assert.deepEqual(await f.status(), { offset: 0, complete: false });
  assert.equal((await f.patch(0, 199999)).status, 200);
  assert.equal((await f.patch(0, 199999)).status, 409);
  assert.equal((await fetch(f.url(), { headers: f.headers })).status, 404);
  await f.restart();
  assert.deepEqual(await f.status(), { offset: 200000, complete: false });
  assert.equal((await f.patch(200000, 399999)).status, 200);
  // A client can lose this response and discover completion via the status endpoint.
  assert.equal((await f.patch(400000, 599999)).status, 200);
  assert.deepEqual(await f.status(), { offset: 600000, complete: true });
  assert.equal((await f.patch(400000, 599999)).status, 200);
  const download = await fetch(f.url(), { headers: { Authorization: `Bearer ${f.guest.token}` } });
  assert.equal(download.status, 200);
  assert.deepEqual(Buffer.from(await download.arrayBuffer()), f.bytes);
});

test('a disconnected half chunk keeps only previously completed chunks', async t => {
  const f = await fixture(t);
  await f.patch(0, 199999);
  const req = http.request(f.url(), { method: 'PATCH', headers: { ...f.headers, 'Content-Range': 'bytes 200000-399999/600000', 'Content-Length': 200000 } });
  req.on('error', () => {});
  req.write(f.bytes.subarray(200000, 250000));
  await new Promise(r => setTimeout(r, 100));
  assert.equal((await fetch(f.url() + '?upload=1', { headers: f.headers })).status, 409);
  req.destroy();
  let result;
  for (let i = 0; i < 100; i++) {
    const response = await fetch(f.url() + '?upload=1', { headers: f.headers });
    if (response.ok) { result = await response.json(); break; }
    await new Promise(r => setTimeout(r, 10));
  }
  assert.deepEqual(result, { offset: 200000, complete: false });
  assert.equal((await f.patch(200000, 399999)).status, 200);
  assert.equal((await f.patch(400000, 599999)).status, 200);
});

test('guest uploads, invalid offsets and corrupted completed tracks are rejected', async t => {
  const f = await fixture(t);
  assert.equal((await f.patch(0, 199999, undefined, f.guest.token)).status, 403);
  assert.equal((await fetch(f.url() + '?upload=1', { headers: { Authorization: `Bearer ${f.guest.token}` } })).status, 403);
  assert.equal((await f.patch(1, 199999)).status, 409);
  assert.equal((await f.patch(0, 599999, Buffer.alloc(600000, 8))).status, 400);
  assert.deepEqual(await f.status(), { offset: 0, complete: false });
  assert.equal((await fetch(f.url(), { headers: f.headers })).status, 404);
  assert.equal((await f.patch(0, 599999)).status, 200);
  f.ws.send(JSON.stringify({ type: 'settings', id: 'sharing-revoked', sharing: false }));
  await f.wait(x => x.type === 'ack' && x.id === 'sharing-revoked');
  assert.equal((await fetch(f.url() + '?upload=1', { headers: f.headers })).status, 403);
});

test('revoking sharing clears saved partial data and malformed ranges cannot advance it', async t => {
  const f = await fixture(t);
  await f.patch(0, 199999);
  assert.equal((await fetch(f.url(), { method: 'PATCH', headers: { ...f.headers, 'Content-Range': 'bytes 200000-199999/600000' }, body: 'bad' })).status, 413);
  assert.equal((await fetch(f.url(), { method: 'PATCH', headers: f.headers, body: 'bad' })).status, 400);
  assert.deepEqual(await f.status(), { offset: 200000, complete: false });
  f.ws.send(JSON.stringify({ type: 'settings', id: 'partial-revoked', sharing: false }));
  await f.wait(x => x.type === 'ack' && x.id === 'partial-revoked');
  f.ws.send(JSON.stringify({ type: 'settings', id: 'partial-reenabled', sharing: true }));
  await f.wait(x => x.type === 'ack' && x.id === 'partial-reenabled');
  assert.deepEqual(await f.status(), { offset: 0, complete: false });
});

test('restart between final append and publication finalizes the verified track', async t => {
  const f = await fixture(t);
  const file = join(f.dir, `${f.host.code}-${f.hash}.mp3`);
  writeFileSync(file + '.part', Buffer.alloc(f.bytes.length, 4));
  await f.restart();
  assert.deepEqual(await f.status(), { offset: 0, complete: false });
  assert.equal((await fetch(f.url(), { headers: f.headers })).status, 404);
  await f.patch(0, 599999);
  renameSync(file, file + '.part'); // State left if the process dies before final publication.
  await f.restart();
  assert.deepEqual(await f.status(), { offset: f.bytes.length, complete: true });
  const response = await fetch(f.url(), { headers: f.headers });
  assert.deepEqual(Buffer.from(await response.arrayBuffer()), f.bytes);
});
