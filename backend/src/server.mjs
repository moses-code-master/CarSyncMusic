import http from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import { DatabaseSync } from 'node:sqlite';
import { mkdirSync, existsSync, statSync, createReadStream, createWriteStream, unlinkSync, renameSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { token, digest, code, now, effective, position, schedule, validatePlaylist } from './model.mjs';

export function createServer({ dataDir = process.env.DATA_DIR || './data', port = Number(process.env.PORT || 8080) } = {}) {
  mkdirSync(dataDir, { recursive: true });
  const db = new DatabaseSync(join(dataDir, 'sessions.sqlite'));
  db.exec('PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS sessions(code TEXT PRIMARY KEY, body TEXT NOT NULL)');
  db.exec('CREATE TABLE IF NOT EXISTS telemetry(code TEXT, member TEXT, at REAL, event TEXT, drift REAL, rtt REAL, buffered INTEGER, missing INTEGER); CREATE INDEX IF NOT EXISTS telemetry_session ON telemetry(code,at)');
  let closing = false;
  const metric = (s, m, event, status = {}) => { if (!closing) db.prepare('INSERT INTO telemetry VALUES(?,?,?,?,?,?,?,?)').run(s.code, m.id, now(), event, status.driftMs ?? null, status.rttMs ?? null, status.buffered ? 1 : 0, status.missing ? 1 : 0); };
  const sessions = new Map(), peers = new Map(), rates = new Map();
  let reservedBytes = 0;
  const uploads = new Set();
  for (const row of db.prepare('SELECT * FROM sessions').all()) {
    const s = JSON.parse(row.body);
    // A restart invalidates the server time anchor: recover paused instead of guessing elapsed audio.
    s.frame = { ...s.frame, positionMs: position(effective(s, now()), now()), playing: false, executeAt: now(), revision: s.frame.revision + 1 }; s.previous = null;
    if (s.expiresAt > now()) sessions.set(s.code, s);
    else { db.prepare('DELETE FROM sessions WHERE code=?').run(s.code); removeAudio(s.code); }
  }
  for (const f of readdirSync(dataDir)) if (/^\d{6}-[a-f0-9]{64}\.[A-Za-z0-9_-]+\.tmp$/.test(f)) unlinkSync(join(dataDir, f));
  const save = s => db.prepare('INSERT OR REPLACE INTO sessions VALUES(?,?)').run(s.code, JSON.stringify(s));
  const fail = (res, status, error) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ error })); };
  const send = (ws, x) => { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(x)); };
  function snapshot(s) {
    return { type: 'state', code: s.code, playlist: s.playlist, audioAvailable: s.sharing ? s.playlist.filter(t => existsSync(join(dataDir, `${s.code}-${t.hash}.mp3`))).map(t => t.hash) : [], frame: s.frame, previous: s.previous, repeat: s.repeat, sharing: s.sharing, guestControls: s.guestControls, expiresAt: s.expiresAt, serverNow: now(), cars: s.members.map(m => ({ id: m.id, name: m.name, role: m.role, online: peers.has(m.id), ...(peers.get(m.id)?.status || {}) })) };
  }
  const broadcast = s => s.members.forEach(m => { const p = peers.get(m.id); if (p) send(p.ws, snapshot(s)); });
  const broadcastCars = s => {
    const msg = { type: 'cars', cars: snapshot(s).cars, serverNow: now() };
    s.members.forEach(m => { const p = peers.get(m.id); if (p) send(p.ws, msg); });
  };
  function auth(req, sessionCode) {
    const s = sessions.get(sessionCode);
    if (!s || s.expiresAt < now()) return null;
    const raw = req.headers.authorization?.replace(/^Bearer /, '');
    const member = s.members.find(m => m.tokenHash === digest(raw || ''));
    return member ? { s, member } : null;
  }
  function limited(key, max = 20, interval = 60000) {
    const t = now(), a = (rates.get(key) || []).filter(x => t - x < interval);
    if (a.length >= max) return true;
    a.push(t); rates.set(key, a); return false;
  }
  async function json(req) {
    let body = ''; for await (const c of req) { body += c; if (body.length > 262144) throw Error('Request too large'); }
    return JSON.parse(body || '{}');
  }
  const server = http.createServer(async (req, res) => {
    res.setHeader('Cache-Control', 'no-store'); res.setHeader('X-Content-Type-Options', 'nosniff');
    try {
      const path = new URL(req.url, 'http://local').pathname;
      if (path === '/health' && req.method === 'GET') { res.end('{"ok":true}'); return; }
      if (path === '/sessions' && req.method === 'POST') {
        if (sessions.size >= 1000) return fail(res, 503, 'Session server capacity reached');
        if (limited(req.socket.remoteAddress + ':create', 10)) return fail(res, 429, 'Too many sessions; retry in a minute');
        const b = await json(req), raw = token(); let c; do { c = code(); } while (sessions.has(c));
        const member = { id: token(), name: String(b.name || 'Host car').slice(0, 40), role: 'host', tokenHash: digest(raw) };
        const s = { code: c, members: [member], playlist: [], repeat: 'off', sharing: false, guestControls: false, expiresAt: now() + 24 * 3600000, frame: { index: 0, positionMs: 0, playing: false, executeAt: now(), revision: 0 }, previous: null, seen: [] };
        sessions.set(c, s); save(s); res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ code: c, token: raw, memberId: member.id, role: member.role })); return;
      }
      const match = path.match(/^\/sessions\/(\d{6})(?:\/(join|audio|metrics)(?:\/([a-f0-9]{64}))?)?$/);
      if (!match) return fail(res, 404, 'Not found');
      const [, c, route, hash] = match;
      if (route === 'join' && req.method === 'POST') {
        if (limited(req.socket.remoteAddress + ':join', 10)) return fail(res, 429, 'Too many join attempts; retry in a minute');
        const b = await json(req), s = sessions.get(c);
        if (!s || s.expiresAt < now()) return fail(res, 404, 'Session expired or code incorrect');
        if (s.members.length >= 2) return fail(res, 409, 'Session already has two cars. Resume with the original device or create a new session.');
        const raw = token(), member = { id: token(), name: String(b.name || 'Guest car').slice(0, 40), role: 'guest', tokenHash: digest(raw) };
        s.members.push(member); save(s); broadcast(s); res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ code: c, token: raw, memberId: member.id, role: member.role })); return;
      }
      const a = auth(req, c); if (!a) return fail(res, 401, 'Session authentication required');
      const { s, member } = a;
      if (route === 'metrics' && req.method === 'GET' && member.role === 'host') {
        const rows = db.prepare('SELECT * FROM telemetry WHERE code=? ORDER BY at LIMIT 100000').all(c);
        res.setHeader('Content-Type', 'text/csv'); res.end('code,member,serverTimeMs,event,driftMs,rttMs,buffered,missing\n' + rows.map(r => [r.code,r.member,r.at,r.event,r.drift ?? '',r.rtt ?? '',r.buffered,r.missing].join(',')).join('\n')); return;
      }
      if (!route && req.method === 'GET') { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(snapshot(s))); return; }
      if (!route && req.method === 'DELETE' && member.role === 'host') {
        for (const m of s.members) peers.get(m.id)?.ws.close(4001, 'Session ended');
        sessions.delete(c); db.prepare('DELETE FROM sessions WHERE code=?').run(c); db.prepare('DELETE FROM telemetry WHERE code=?').run(c); removeAudio(c); res.end('{}'); return;
      }
      if (route === 'audio' && hash) {
        if (!s.sharing) return fail(res, 403, 'Host has not permitted audio sharing');
        if (!s.playlist.some(t => t.hash === hash)) return fail(res, 404, 'Track is not in this playlist');
        const file = join(dataDir, `${c}-${hash}.mp3`);
        if (req.method === 'PUT' && member.role === 'host') {
          const length = Number(req.headers['content-length']);
          if (!length || length > 100 * 1024 * 1024) return fail(res, 413, 'Track limit is 100 MB');
          const used = readdirSync(dataDir).filter(f => f.endsWith('.mp3')).reduce((n, f) => n + statSync(join(dataDir, f)).size, 0);
          if (used + reservedBytes + length > 2 * 1024 ** 3) return fail(res, 507, 'Relay storage is full');
          if (uploads.has(file)) return fail(res, 409, 'This track is already uploading');
          uploads.add(file); reservedBytes += length;
          const tmp = `${file}.${token()}.tmp`, sha = createHash('sha256'); let bytes = 0;
          try {
            await pipeline(req, new Transform({ transform(chunk, enc, cb) { bytes += chunk.length; if (bytes > length) return cb(Error('Size exceeded')); sha.update(chunk); cb(null, chunk); } }), createWriteStream(tmp));
            if (sha.digest('hex') !== hash) throw Error('Track hash mismatch');
            if (!sessions.has(c) || !s.sharing) throw Error('Sharing revoked during upload');
            renameSync(tmp, file); res.end('{"ok":true}'); broadcast(s);
          } catch (e) { if (existsSync(tmp)) unlinkSync(tmp); throw e; }
          finally { uploads.delete(file); reservedBytes -= length; }
          return;
        }
        if (['GET', 'HEAD'].includes(req.method)) {
          if (!existsSync(file)) return fail(res, 404, 'Host must upload this track, or import the identical MP3 locally');
          const size = statSync(file).size, range = req.headers.range;
          let start = 0, end = size - 1, status = 200;
          if (range) {
            const r = range.match(/^bytes=(\d+)-(\d*)$/);
            if (!r || Number(r[1]) >= size || (r[2] && Number(r[2]) < Number(r[1]))) { res.writeHead(416, { 'Content-Range': `bytes */${size}` }); res.end(); return; }
            start = Number(r[1]); end = r[2] ? Math.min(Number(r[2]), size - 1) : end; status = 206;
          }
          res.writeHead(status, { 'Content-Type': 'audio/mpeg', 'Accept-Ranges': 'bytes', 'Content-Length': end - start + 1, ...(status === 206 ? { 'Content-Range': `bytes ${start}-${end}/${size}` } : {}) });
          if (req.method === 'HEAD') res.end(); else createReadStream(file, { start, end }).pipe(res); return;
        }
      }
      fail(res, 403, 'Action is not allowed');
    } catch (e) { if (!res.headersSent && !res.destroyed) fail(res, 400, e.message); else res.destroy(); }
  });
  const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 262144 });
  wss.on('connection', ws => {
    let context;
    const deadline = setTimeout(() => ws.close(4003, 'Authenticate first'), 5000);
    ws.on('message', data => {
      try {
        const b = JSON.parse(data.toString());
        if (!context) {
          if (b.type !== 'auth' || typeof b.token !== 'string') throw Error('Authentication required');
          const s = sessions.get(b.code), member = s?.members.find(m => m.tokenHash === digest(b.token));
          if (!member || s.expiresAt < now()) { ws.close(4003, 'Invalid or expired session'); return; }
          context = { s, member }; clearTimeout(deadline);
          peers.get(member.id)?.ws.close(4002, 'Another connection resumed this car'); peers.set(member.id, { ws }); metric(s, member, 'online'); broadcast(s); return;
        }
        const { s, member } = context;
        if (s.expiresAt < now() || !sessions.has(s.code)) { ws.close(4001, 'Session expired'); return; }
        if (b.type === 'ping') { const t1 = now(); send(ws, { type: 'pong', clientTime: b.clientTime, t1, t2: now() }); return; }
        if (limited(member.id + ':messages', 180)) throw Error('Too many commands');
        if (b.type === 'status') {
          const p = peers.get(member.id);
          if (p?.ws === ws) p.status = { driftMs: Number.isFinite(b.driftMs) ? b.driftMs : null, rttMs: Number.isFinite(b.rttMs) ? b.rttMs : null, buffered: !!b.buffered, missing: !!b.missing, clockReady: !!b.clockReady };
          metric(s, member, 'status', p?.status);
          broadcastCars(s); return;
        }
        if (b.type === 'snapshot') { send(ws, snapshot(s)); return; }
        if (member.role !== 'host' && !(s.guestControls && b.type === 'control' && b.action?.kind !== 'ended')) throw Error('Host controls only');
        if (!/^[a-zA-Z0-9-]{8,80}$/.test(b.id || '')) throw Error('Command ID required');
        if (s.seen.includes(b.id)) { send(ws, { type: 'ack', id: b.id }); return; }
        if (b.type === 'control') schedule(s, b.action);
        else if (b.type === 'playlist') {
          const playlist = validatePlaylist(b.playlist);
          s.previous = effective(s, now()); s.playlist = playlist;
          s.frame = { index: 0, positionMs: 0, playing: false, executeAt: now() + 1800, revision: s.frame.revision + 1 };
        } else if (b.type === 'settings') {
          s.guestControls = !!b.guestControls; const was = s.sharing; s.sharing = !!b.sharing;
          s.repeat = ['off', 'one', 'all'].includes(b.repeat) ? b.repeat : 'off';
          if (was && !s.sharing) removeAudio(s.code);
        } else throw Error('Unknown message');
        s.seen.push(b.id); s.seen = s.seen.slice(-512); save(s); send(ws, { type: 'ack', id: b.id }); broadcast(s);
      } catch (e) { send(ws, { type: 'error', error: e.message }); }
    });
    ws.on('close', () => { clearTimeout(deadline); if (context && peers.get(context.member.id)?.ws === ws) { peers.delete(context.member.id); metric(context.s, context.member, 'offline'); broadcast(context.s); } });
    ws.on('error', () => {});
  });
  function removeAudio(c) { for (const f of readdirSync(dataDir)) if (f.startsWith(c + '-') && (f.endsWith('.mp3') || f.endsWith('.tmp'))) try { unlinkSync(join(dataDir, f)); } catch {} }
  const cleanup = setInterval(() => {
    for (const [c, s] of sessions) if (s.expiresAt < now()) { sessions.delete(c); removeAudio(c); db.prepare('DELETE FROM sessions WHERE code=?').run(c); for (const m of s.members) peers.get(m.id)?.ws.close(4001, 'Session expired'); }
    for (const [key, a] of rates) if (now() - a.at(-1) > 60000) rates.delete(key);
    db.prepare('DELETE FROM telemetry WHERE at<?').run(now() - 24 * 3600000);
  }, 60000).unref();
  // The timeline advances even when the host process is killed or loses connectivity.
  const advance = setInterval(() => {
    for (const s of sessions.values()) {
      const at = now(), f = effective(s, at), track = s.playlist[f.index];
      if (track && f.playing && at >= s.frame.executeAt && position(f, at) >= track.durationMs) {
        if (schedule(s, { kind: 'ended', revision: f.revision, index: f.index }, at)) { save(s); broadcast(s); }
      }
    }
  }, 250).unref();
  const heartbeat = setInterval(() => { for (const ws of wss.clients) { if (ws.alive === false) ws.terminate(); else { ws.alive = false; ws.ping(); } } }, 15000).unref();
  wss.on('connection', ws => { ws.alive = true; ws.on('pong', () => { ws.alive = true; }); });
  return { server, sessions, listen: () => new Promise(resolve => server.listen(port, '0.0.0.0', resolve)), close: async () => { closing = true; clearInterval(cleanup); clearInterval(advance); clearInterval(heartbeat); for (const ws of wss.clients) ws.terminate(); await new Promise(r => wss.close(r)); await new Promise(r => server.close(r)); db.close(); } };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const app = createServer(); await app.listen(); console.log(`CarSync server listening on ${app.server.address().port}`);
  for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, async () => { await app.close(); process.exit(); });
}
