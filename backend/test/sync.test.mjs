import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { WebSocket } from 'ws';
import { createServer } from '../src/server.mjs';
import { digest, schedule, position, effective, validatePlaylist } from '../src/model.mjs';

const track = (hash = 'a'.repeat(64)) => ({ hash, title: 'Test track', artist: 'Test artist', album: 'Tests', durationMs: 60000 });
test('rapid select/play and seek/play retain the pending track and requested position', () => {
  const s = { playlist: [track(), track('b'.repeat(64))], repeat: 'off', previous: null, frame: { index: 0, positionMs: 0, playing: false, executeAt: 100, revision: 0 } };
  schedule(s, { kind: 'select', index: 1 }, 1000); schedule(s, { kind: 'play' }, 1010);
  assert.equal(s.frame.index, 1); assert.equal(s.frame.playing, true); assert.equal(s.previous.index, 0);
  schedule(s, { kind: 'seek', positionMs: 15000 }, 1020); schedule(s, { kind: 'play' }, 1030);
  assert.equal(s.frame.index, 1); assert.ok(s.frame.positionMs >= 15000 && s.frame.positionMs < 15100);
});
class Peer {
  constructor(url, credentials) {
    this.messages = []; this.ws = new WebSocket(url);
    this.ready = new Promise(resolve => this.ws.on('open', () => { this.ws.send(JSON.stringify({ type: 'auth', ...credentials })); resolve(); }));
    this.ws.on('message', data => this.messages.push(JSON.parse(data)));
  }
  async wait(predicate) {
    for (let i = 0; i < 200; i++) { const index = this.messages.findIndex(predicate); if (index >= 0) return this.messages.splice(index, 1)[0]; await new Promise(r => setTimeout(r, 10)); }
    throw Error('Timed out waiting for message');
  }
  send(type, payload = {}) { this.ws.send(JSON.stringify({ type, id: crypto.randomUUID(), ...payload })); }
}
async function setup(t, video = false) {
  const dir = mkdtempSync(join(tmpdir(), 'carsync-')), app = createServer({ dataDir: dir, port: 0 }); await app.listen();
  t.after(async () => { await app.close(); rmSync(dir, { recursive: true, force: true }); });
  const url = `http://127.0.0.1:${app.server.address().port}`;
  const post = async path => { const r = await fetch(url + path, { method: 'POST', body: '{"name":"Test car"}' }); return { status: r.status, ...(await r.json()) }; };
  const host = await post('/sessions'), guest = await post(`/sessions/${host.code}/join`);
  const a = new Peer(url.replace('http', 'ws') + '/ws', { ...host, mediaCapabilities: video ? ['audio','video'] : ['audio'] }), b = new Peer(url.replace('http', 'ws') + '/ws', { ...guest, mediaCapabilities: video ? ['audio','video'] : ['audio'] });
  await Promise.all([a.ready, b.ready]); await a.wait(x => x.type === 'state'); await b.wait(x => x.type === 'state');
  return { app, url, host, guest, a, b, post };
}
test('scheduled timeline preserves elapsed time, pause, seek and late-join position', () => {
  const s = { playlist: [track(), track('b'.repeat(64))], repeat: 'all', previous: null, frame: { index: 0, positionMs: 1000, playing: true, executeAt: 1000, revision: 1 } };
  schedule(s, { kind: 'pause' }, 2000); assert.equal(s.frame.executeAt, 3800); assert.equal(s.frame.positionMs, 3800);
  assert.equal(effective(s, 3000).playing, true); assert.equal(effective(s, 4000).playing, false);
  schedule(s, { kind: 'seek', positionMs: 5000 }, 4000); assert.equal(s.frame.positionMs, 5000);
  schedule(s, { kind: 'next' }, 6000); assert.equal(s.frame.index, 1); assert.equal(s.frame.positionMs, 0);
  schedule(s, { kind: 'play' }, 8000); assert.equal(position(s.frame, 11000), 1200);
});
test('two cars receive identical ordered playlist, play/pause/seek/skip deadlines and dedup', async t => {
  const { a, b } = await setup(t);
  a.send('playlist', { playlist: [track(), track('b'.repeat(64))] });
  const pa = await a.wait(x => x.type === 'state' && x.frame.revision === 1), pb = await b.wait(x => x.type === 'state' && x.frame.revision === 1);
  assert.deepEqual(pa.playlist, pb.playlist); assert.deepEqual(pa.frame, pb.frame);
  // Wait for the playlist's scheduled reset to become active.
  await new Promise(r => setTimeout(r, 1850));
  for (const [i, action] of [{ kind: 'play' }, { kind: 'pause' }, { kind: 'seek', positionMs: 30000 }, { kind: 'next' }].entries()) {
    const id = crypto.randomUUID(); const start = Date.now();
    const message = JSON.stringify({ type: 'control', id, action }); a.ws.send(message);
    const left = await a.wait(x => x.type === 'state' && x.frame.revision === i + 2), right = await b.wait(x => x.type === 'state' && x.frame.revision === i + 2);
    assert.deepEqual(left.frame, right.frame); assert.ok(left.frame.executeAt - start > 1600);
    a.ws.send(message); await a.wait(x => x.type === 'ack' && x.id === id);
    a.send('snapshot'); const snap = await a.wait(x => x.type === 'state' && x.frame.revision === i + 2); assert.equal(snap.frame.revision, i + 2);
    await new Promise(r => setTimeout(r, 1850));
  }
});
test('guest authorization, clock timestamps and authenticated reconnect snapshot', async t => {
  const { a, b, guest, url } = await setup(t);
  b.send('control', { action: { kind: 'play' } }); assert.match((await b.wait(x => x.type === 'error')).error, /Host/);
  a.send('playlist', { playlist: [track()] }); await a.wait(x => x.type === 'state' && x.playlist.length === 1);
  a.send('settings', { guestControls: true, sharing: false, repeat: 'all' }); await b.wait(x => x.type === 'state' && x.guestControls);
  b.send('control', { action: { kind: 'play' } }); const before = await b.wait(x => x.type === 'state' && x.frame.playing);
  b.send('ping', { clientTime: 12345 }); const pong = await b.wait(x => x.type === 'pong'); assert.equal(pong.clientTime, 12345); assert.ok(pong.t2 >= pong.t1);
  b.send('status', { driftMs: 20, rttMs: 40, buffered: true }); const status = await a.wait(x => x.type === 'cars');
  assert.equal(status.playlist, undefined); assert.ok(status.cars.some(c => c.driftMs === 20));
  b.ws.terminate(); const resumed = new Peer(url.replace('http', 'ws') + '/ws', guest); await resumed.ready;
  const after = await resumed.wait(x => x.type === 'state'); assert.deepEqual(after.frame, before.frame); assert.equal(after.cars.filter(x => x.online).length, 2);
});
test('audio opt-in, host-only upload, hash integrity, authenticated range streaming and revocation', async t => {
  const { a, b, host, guest, url } = await setup(t);
  const bytes = Buffer.from('fixture audio bytes for relay integrity'), hash = digest(bytes);
  a.send('playlist', { playlist: [track(hash)] }); await a.wait(x => x.type === 'state' && x.playlist.length === 1);
  const path = `${url}/sessions/${host.code}/audio/${hash}`;
  const put = async (who, data) => fetch(path, { method: 'PUT', headers: { Authorization: `Bearer ${who.token}` }, body: data });
  assert.equal((await put(host, bytes)).status, 403);
  a.send('settings', { sharing: true }); await a.wait(x => x.type === 'state' && x.sharing);
  const waiting = await b.wait(x => x.type === 'state' && x.sharing); assert.deepEqual(waiting.audioAvailable, []);
  assert.equal((await put(guest, bytes)).status, 403); assert.equal((await put(host, Buffer.from('wrong'))).status, 400);
  assert.equal((await put(host, bytes)).status, 200); assert.equal((await fetch(path)).status, 401);
  const ready = await b.wait(x => x.type === 'state' && x.audioAvailable?.includes(hash));
  assert.equal(ready.frame.revision, waiting.frame.revision);
  const range = await fetch(path, { headers: { Authorization: `Bearer ${guest.token}`, Range: 'bytes=3-9' } }); assert.equal(range.status, 206); assert.equal(await range.text(), bytes.subarray(3, 10).toString());
  const invalid = await fetch(path, { headers: { Authorization: `Bearer ${guest.token}`, Range: 'bytes=100-200' } }); assert.equal(invalid.status, 416);
  a.messages = []; // Discard authentication snapshots, which also have sharing=false.
  b.messages = [];
  a.send('settings', { sharing: false }); await a.wait(x => x.type === 'state' && !x.sharing);
  const revoked = await b.wait(x => x.type === 'state' && !x.sharing); assert.deepEqual(revoked.audioAvailable, []);
  assert.equal((await fetch(path, { headers: { Authorization: `Bearer ${guest.token}` } })).status, 403);
});
test('session capacity, missing audio and malformed playlists produce clear errors', async t => {
  const { a, host, guest, url, post } = await setup(t);
  assert.equal((await post(`/sessions/${host.code}/join`)).status, 409);
  a.send('playlist', { playlist: [track('not-a-hash')] }); assert.match((await a.wait(x => x.type === 'error')).error, /Invalid/);
  a.send('playlist', { playlist: [track()] }); await a.wait(x => x.type === 'state' && x.playlist.length === 1);
  a.send('settings', { sharing: true }); await a.wait(x => x.type === 'state' && x.sharing);
  const r = await fetch(`${url}/sessions/${host.code}/audio/${'a'.repeat(64)}`, { headers: { Authorization: `Bearer ${guest.token}` } }); assert.equal(r.status, 404);
  assert.match((await r.json()).error, /identical MP3/);
});
test('server advances playback when the host process disappears; telemetry records reconnects', async t => {
  const { a, b, app, host, url } = await setup(t);
  const session = app.sessions.get(host.code);
  session.playlist = [track(), track('b'.repeat(64))];
  session.frame = { index: 0, playing: true, positionMs: 60000, executeAt: Date.now() - 1000, revision: 1 }; session.previous = null;
  a.ws.terminate();
  const next = await b.wait(x => x.type === 'state' && x.frame.revision === 2);
  assert.equal(next.frame.index, 1); assert.equal(next.frame.positionMs, 0);
  const csv = await fetch(`${url}/sessions/${host.code}/metrics`, { headers: { Authorization: `Bearer ${host.token}` } });
  assert.equal(csv.status, 200); assert.match(await csv.text(), /online/);
});
test('backend restart retains identities and playlist but safely pauses the recovered timeline', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'carsync-restart-'));
  let app = createServer({ dataDir: dir, port: 0 }); await app.listen();
  let url = `http://127.0.0.1:${app.server.address().port}`;
  const host = await (await fetch(url + '/sessions', { method: 'POST', body: '{}' })).json();
  const a = new Peer(url.replace('http', 'ws') + '/ws', host); await a.ready; await a.wait(x => x.type === 'state');
  a.send('playlist', { playlist: [track()] }); await a.wait(x => x.type === 'state' && x.frame.revision === 1);
  await app.close();
  app = createServer({ dataDir: dir, port: 0 }); await app.listen();
  t.after(async () => { await app.close(); rmSync(dir, { recursive: true, force: true }); });
  url = `http://127.0.0.1:${app.server.address().port}`;
  const response = await fetch(`${url}/sessions/${host.code}`, { headers: { Authorization: `Bearer ${host.token}` } }); const s = await response.json();
  assert.equal(response.status, 200); assert.equal(s.playlist[0].hash, track().hash); assert.equal(s.frame.playing, false); assert.equal(s.frame.revision, 2);
});

test('video metadata, shared controls, missing-file relay rejection and legacy client protection', async t => {
  const {a,b,host,guest,url} = await setup(t,true);
  const video = { ...track(), mediaType:'video', mimeType:'video/mp4', sizeBytes:924408, width:320, height:180 };
  a.send('playlist',{playlist:[video,{...video,hash:'b'.repeat(64),mimeType:'video/x-matroska'}]});
  const sa=await a.wait(x=>x.type==='state'&&x.playlist.length===2), sb=await b.wait(x=>x.type==='state'&&x.playlist.length===2);
  assert.equal(sa.mediaProtocol,2); assert.deepEqual(sa.playlist,sb.playlist); assert.equal(sa.playlist[0].mediaType,'video');
  for (const action of [{kind:'play'},{kind:'pause'},{kind:'seek',positionMs:15000},{kind:'play'},{kind:'next'}]) {
    a.send('control',{action}); const nextA=await a.wait(x=>x.type==='state'&&x.frame.revision>sa.frame.revision); const nextB=await b.wait(x=>x.type==='state'&&x.frame.revision===nextA.frame.revision); assert.deepEqual(nextA.frame,nextB.frame); sa.frame=nextA.frame;
  }
  a.send('settings',{sharing:true}); await a.wait(x=>x.type==='state'&&x.sharing);
  assert.equal((await fetch(`${url}/sessions/${host.code}/audio/${video.hash}`,{method:'PUT',headers:{Authorization:`Bearer ${host.token}`},body:Buffer.from('video')})).status,415);
  assert.equal((await fetch(`${url}/sessions/${host.code}/audio/${video.hash}`,{headers:{Authorization:`Bearer ${guest.token}`}})).status,415);
  const legacy=new WebSocket(url.replace('http','ws')+'/ws');
  const closed=new Promise(resolve=>legacy.on('close',(code,reason)=>resolve({code,reason:reason.toString()})));
  legacy.on('open',()=>legacy.send(JSON.stringify({type:'auth',...guest})));
  const rejection=await closed; assert.equal(rejection.code,4003); assert.match(rejection.reason,/1.5/);
});
test('media validation defaults legacy songs to audio and rejects invalid video metadata',()=>{
  assert.equal(validatePlaylist([track()])[0].mediaType,'audio');
  assert.throws(()=>validatePlaylist([{...track(),mediaType:'executable'}]),/media type/);
  assert.throws(()=>validatePlaylist([{...track(),mediaType:'video',mimeType:'audio/mpeg'}]),/MIME/);
  assert.throws(()=>validatePlaylist([{...track(),mediaType:'video',width:-1}]),/metadata/);
});
