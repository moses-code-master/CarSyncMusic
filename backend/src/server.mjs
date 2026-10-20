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
import { advertiseLanRelay } from './mdns.mjs';

export function createServer({ dataDir = process.env.DATA_DIR || './data', port = Number(process.env.PORT || 8080), maxGuests = Number(process.env.MAX_GUESTS || 10) } = {}) {
  if (!Number.isInteger(maxGuests) || maxGuests < 1 || maxGuests > 50) throw Error('MAX_GUESTS must be an integer from 1 to 50');
  mkdirSync(dataDir, { recursive: true });
  const db = new DatabaseSync(join(dataDir, 'sessions.sqlite'));
  db.exec('PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS sessions(code TEXT PRIMARY KEY, body TEXT NOT NULL)');
  db.exec('CREATE TABLE IF NOT EXISTS telemetry(code TEXT, member TEXT, at REAL, event TEXT, drift REAL, rtt REAL, buffered INTEGER, missing INTEGER); CREATE INDEX IF NOT EXISTS telemetry_session ON telemetry(code,at)');
  let closing = false;
  let lanAdvertiser = null;
  const metric = (s, m, event, status = {}) => { if (!closing) db.prepare('INSERT INTO telemetry VALUES(?,?,?,?,?,?,?,?)').run(s.code, m.id, now(), event, status.driftMs ?? null, status.rttMs ?? null, status.buffered ? 1 : 0, status.missing ? 1 : 0); };
  const sessions = new Map(), peers = new Map(), rates = new Map();
  const guestReservations = new Map(); // Session-scoped upload grants expire after 5 minutes.
  const capabilities = ['convoy_ptt_pcm16_v1', 'convoy_location_v1', 'convoy_votes_v1', 'guest_music_upload_v1', 'guest_media_batch_v1', 'guest_video_mp4_v1'];
  // Ephemeral voice floor and position data never enter the session database.
  const floors = new Map(), frameRates = new Map();
  const announce = (session, payload, exceptId = null) => {
    for (const member of session.members) {
      if (member.id !== exceptId) send(peers.get(member.id)?.ws, payload);
    }
  };
  const mediaName = (code, t) => `${code}-${t.hash}.${t.mediaType === 'video' ? 'mp4' : 'mp3'}`;
  function releaseFloor(session, speakerId = null) {
    const current = floors.get(session.code);
    if (!current || (speakerId && current.memberId !== speakerId)) return false;
    floors.delete(session.code);
    announce(session, { type: 'ptt_stop', speakerId: current.memberId });
    return true;
  }
  function validPosition(p) {
    if (typeof p?.lat !== 'number' || typeof p?.lon !== 'number' ||
      !Number.isFinite(p.lat) || !Number.isFinite(p.lon) ||
      Math.abs(p.lat) > 90 || Math.abs(p.lon) > 180) throw Error('Invalid location');
    const speed = Number(p.speed ?? 0), bearing = Number(p.bearing ?? 0), accuracy = Number(p.accuracy ?? 1000);
    if (![speed, bearing, accuracy].every(Number.isFinite) ||
      speed < 0 || speed > 120 || bearing < 0 || bearing > 360 || accuracy < 0 || accuracy > 10000) throw Error('Invalid location metadata');
    return {lat:p.lat,lon:p.lon,speed,bearing,accuracy,at:now()};
  }
  const voteTotals = s => {
    const totals = {};
    for (const hash of Object.values(s.votes || {})) if (s.playlist.some(t => t.hash === hash)) totals[hash] = (totals[hash] || 0) + 1;
    return totals;
  };
  let reservedBytes = 0;
  const uploads = new Set();
  for (const row of db.prepare('SELECT * FROM sessions').all()) {
    const s = JSON.parse(row.body);
    // A restart invalidates the server time anchor: recover paused instead of guessing elapsed audio.
    s.frame = { ...s.frame, positionMs: position(effective(s, now()), now()), playing: false, executeAt: now(), revision: s.frame.revision + 1 }; s.previous = null;
    if (s.expiresAt > now()) { s.guestAdditions ||= false; sessions.set(s.code, s); }
    else { db.prepare('DELETE FROM sessions WHERE code=?').run(s.code); removeAudio(s.code); }
  }
  for (const f of readdirSync(dataDir)) if (/^\d{6}-[a-f0-9]{64}\.[A-Za-z0-9_-]+\.tmp$/.test(f)) unlinkSync(join(dataDir, f));
  const save = s => db.prepare('INSERT OR REPLACE INTO sessions VALUES(?,?)').run(s.code, JSON.stringify(s));
  const fail = (res, status, error) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ error })); };
  const send = (ws, x) => { if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(x)); };
  const cars = s => s.members.map(m => {
    const peer = peers.get(m.id);
    const livePosition = peer?.location && now() - peer.location.at < 10000 ? peer.location : null;
    return { id: m.id, name: m.name, role: m.role, online: !!peer, ...(peer?.status || {}), ...(livePosition ? { location: livePosition } : {}) };
  });
  function snapshot(s) {
    return { type: 'state', mediaProtocol: 2, capabilities, maxGuests, code: s.code, playlist: s.playlist, audioAvailable: s.sharing ? s.playlist.filter(t => existsSync(join(dataDir, mediaName(s.code,t)))).map(t => t.hash) : [], frame: s.frame, previous: s.previous, repeat: s.repeat, sharing: s.sharing, guestControls: s.guestControls, guestAdditions: !!s.guestAdditions, votes: voteTotals(s), myVotes: s.votes || {}, speakerId: floors.get(s.code)?.memberId || null, expiresAt: s.expiresAt, serverNow: now(), cars: cars(s) };
  }
  const broadcast = s => {
    const state = snapshot(s), video = s.playlist.some(t => t.mediaType === 'video');
    s.members.forEach(m => {
      const p = peers.get(m.id); if (!p) return;
      if (video && !p.video) { p.ws.close(4003, 'Video sessions require CarSync 1.5+. Update the host and every guest.'); return; }
      send(p.ws, state);
    });
  };
  const broadcastCars = s => {
    const msg = { type: 'cars', cars: cars(s), serverNow: now() };
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
      if (path === '/health' && req.method === 'GET') {
        res.setHeader('Content-Type','application/json');
        res.end(JSON.stringify({ok:true, protocol:'convoy-v1', capabilities})); return;
      }
      if (path === '/sessions' && req.method === 'POST') {
        if (sessions.size >= 1000) return fail(res, 503, 'Session server capacity reached');
        if (limited(req.socket.remoteAddress + ':create', 10)) return fail(res, 429, 'Too many sessions; retry in a minute');
        const b = await json(req), raw = token(); let c; do { c = code(); } while (sessions.has(c));
        const member = { id: token(), name: String(b.name || 'Host car').slice(0, 40), role: 'host', tokenHash: digest(raw) };
        const s = { code: c, members: [member], playlist: [], repeat: 'off', sharing: false, guestControls: false, guestAdditions: false, expiresAt: now() + 24 * 3600000, frame: { index: 0, positionMs: 0, playing: false, executeAt: now(), revision: 0 }, previous: null, seen: [], votes: {} };
        sessions.set(c, s); save(s); res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ code: c, token: raw, memberId: member.id, role: member.role })); return;
      }
      const match = path.match(/^\/sessions\/(\d{6})(?:\/(join|audio|video|metrics)(?:\/([a-f0-9]{64}))?)?$/);
      if (!match) return fail(res, 404, 'Not found');
      const [, c, route, hash] = match;
      if (route === 'join' && req.method === 'POST') {
        const b = await json(req), s = sessions.get(c);
        if (s && s.expiresAt > now() && s.members.filter(m => m.role === 'guest').length >= maxGuests) return fail(res, 409, `Session has reached its ${maxGuests}-guest limit. Resume with the original device or ask the host to create a new session.`);
        if (limited(req.socket.remoteAddress + ':join', 10)) return fail(res, 429, 'Too many join attempts; retry in a minute');
        if (!s || s.expiresAt < now()) return fail(res, 404, 'Session expired or code incorrect');
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
        releaseFloor(s); sessions.delete(c); db.prepare('DELETE FROM sessions WHERE code=?').run(c); db.prepare('DELETE FROM telemetry WHERE code=?').run(c); removeAudio(c); res.end('{}'); return;
      }
      if (['audio', 'video'].includes(route) && hash) {
        if (!s.sharing) return fail(res, 403, 'Host has not permitted media sharing');
        const reservedKey = c + ':' + hash;
        const reservation = guestReservations.get(reservedKey);
        const guestAuthorized = req.method === 'PUT' && s.guestAdditions && reservation &&
          reservation.memberId === member.id && reservation.expiresAt > now();
        const listedTrack = s.playlist.find(t => t.hash === hash);
        if (!listedTrack && !guestAuthorized) return fail(res, 404, 'Track is not approved for this session');
        const t = listedTrack || reservation?.track;
        if ((route === 'video') !== (t.mediaType === 'video')) return fail(res, 415, 'Media route does not match track');
        if (t.mediaType === 'video' && t.mimeType !== 'video/mp4') return fail(res, 415, 'Only MP4 video relay supported');
        const file = join(dataDir, mediaName(c,t));
        if (req.method === 'PUT' && (member.role === 'host' || guestAuthorized)) {
          const length = Number(req.headers['content-length']);
          if (!Number.isSafeInteger(length) || length <= 0 || length > 100 * 1024 * 1024) return fail(res, 413, 'Media file limit is 100 MB');
          const used = readdirSync(dataDir).filter(f => f.endsWith('.mp3') || f.endsWith('.mp4')).reduce((n, f) => n + statSync(join(dataDir, f)).size, 0);
          if (used + reservedBytes + length > 2 * 1024 ** 3) return fail(res, 507, 'Relay storage is full');
          if (uploads.has(file)) return fail(res, 409, 'This track is already uploading');
          uploads.add(file); reservedBytes += length;
          const tmp = `${file}.${token()}.tmp`, sha = createHash('sha256'); let bytes = 0;
          try {
            await pipeline(req, new Transform({ transform(chunk, enc, cb) { bytes += chunk.length; if (bytes > length) return cb(Error('Size exceeded')); sha.update(chunk); cb(null, chunk); } }), createWriteStream(tmp));
            if (bytes !== length || sha.digest('hex') !== hash) throw Error('Media size or hash mismatch');
            if (!sessions.has(c) || !s.sharing) throw Error('Sharing revoked during upload');
            if (guestAuthorized && (!s.guestAdditions || guestReservations.get(reservedKey) !== reservation || reservation.expiresAt <= now())) throw Error('Guest upload permission expired');
            renameSync(tmp, file);
            if (guestAuthorized) {
              guestReservations.delete(reservedKey);
              if (!s.playlist.some(t => t.hash === hash)) {
                if (s.playlist.length >= 500) throw Error('Playlist is full');
                s.playlist.push(reservation.track);
                save(s);
              }
            }
            res.end('{"ok":true}'); broadcast(s);
          } catch (e) { if (existsSync(tmp)) unlinkSync(tmp); throw e; }
          finally { uploads.delete(file); reservedBytes -= length; }
          return;
        }
        if (['GET', 'HEAD'].includes(req.method)) {
          if (!existsSync(file)) return fail(res, 404, 'Media file not uploaded; import identical MP3/MP4 locally or ask uploader to share');
          const size = statSync(file).size, range = req.headers.range;
          let start = 0, end = size - 1, status = 200;
          if (range) {
            const r = range.match(/^bytes=(\d+)-(\d*)$/);
            if (!r || Number(r[1]) >= size || (r[2] && Number(r[2]) < Number(r[1]))) { res.writeHead(416, { 'Content-Range': `bytes */${size}` }); res.end(); return; }
            start = Number(r[1]); end = r[2] ? Math.min(Number(r[2]), size - 1) : end; status = 206;
          }
          res.writeHead(status, { 'Content-Type': t.mediaType === 'video' ? 'video/mp4' : 'audio/mpeg', 'Accept-Ranges': 'bytes', 'Content-Length': end - start + 1, ...(status === 206 ? { 'Content-Range': `bytes ${start}-${end}/${size}` } : {}) });
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
          peers.get(member.id)?.ws.close(4002, 'Another connection resumed this car'); releaseFloor(s, member.id); peers.set(member.id, { ws, video: Array.isArray(b.mediaCapabilities) && b.mediaCapabilities.includes('video') }); metric(s, member, 'online'); broadcast(s); return;
        }
        const { s, member } = context;
        if (s.expiresAt < now() || !sessions.has(s.code)) { ws.close(4001, 'Session expired'); return; }
        if (b.type === 'ping') { const t1 = now(); send(ws, { type: 'pong', clientTime: b.clientTime, t1, t2: now() }); return; }
        // Voice frames are paced separately from playback mutations.
        if (b.type === 'ptt_request') {
          if (limited(member.id + ':ptt_requests', 12)) throw Error('Too many PTT requests');
          const floor = floors.get(s.code);
          if (floor && floor.memberId !== member.id) { send(ws, {type:'ptt_denied',reason:'Channel busy'}); return; }
          if (floor && floor.memberId === member.id) { floor.until = now()+8000; return; }
          floors.set(s.code, {memberId:member.id,until:now()+8000});
          announce(s, {type:'ptt_start',speakerId:member.id,name:member.name});
          return;
        }
        if (b.type === 'ptt_release') { releaseFloor(s, member.id); return; }
        if (b.type === 'ptt_pcm') {
          const floor = floors.get(s.code);
          if (!floor || floor.memberId !== member.id) return;
          const rateKey = member.id + ':ptt_frames';
          if (limited(rateKey, 65, 1000)) return;
          if (typeof b.data !== 'string' || b.data.length > 2000 || !/^[A-Za-z0-9+/]+={0,2}$/.test(b.data)) return;
          const bytes = Buffer.from(b.data, 'base64');
          if (bytes.length !== 640) return; // 20 ms, PCM16 mono at 16 kHz
          floor.until = now() + 8000;
          announce(s, {type:'ptt_pcm',speakerId:member.id,data:b.data}, member.id);
          return;
        }
        if (b.type === 'location') {
          const p = peers.get(member.id);
          // Revocation bypasses the rate limiter to remove coordinates immediately.
          if (b.enabled === false) {
            if (p?.ws === ws) p.location = null;
            broadcastCars(s);
            return;
          }
          if (limited(member.id + ':locations', 45, 60000)) return;
          if (p?.ws === ws) p.location = validPosition(b);
          broadcastCars(s);
          return;
        }
        if (limited(member.id + ':messages', 180)) throw Error('Too many commands');
        if (b.type === 'status') {
          const p = peers.get(member.id);
          if (p?.ws === ws) p.status = { driftMs: Number.isFinite(b.driftMs) ? b.driftMs : null, rttMs: Number.isFinite(b.rttMs) ? b.rttMs : null, buffered: !!b.buffered, missing: !!b.missing, clockReady: !!b.clockReady };
          metric(s, member, 'status', p?.status);
          broadcastCars(s); return;
        }
        if (b.type === 'snapshot') { send(ws, snapshot(s)); return; }
        // Both host and guest can request PTT regardless of guest music controls.
        if (b.type === 'guest_add') {
          if (!s.sharing || !s.guestAdditions) throw Error('Host must enable guest songs and music sharing');
          if (limited(member.id + ':guest_songs', 200, 3600000)) throw Error('Guest media rate limit reached. Try again later');
          const track = validatePlaylist([b.track])[0];
          if (!((track.mediaType === 'audio' && track.mimeType === 'audio/mpeg') || (track.mediaType === 'video' && track.mimeType === 'video/mp4')) || track.sizeBytes > 100 * 1024 * 1024) throw Error('Only MP3 or MP4 files up to 100 MB are allowed');
          if (s.playlist.length >= 500) throw Error('Playlist is full');
          if (s.playlist.some(t => t.hash === track.hash)) throw Error('Song already in the session');
          const key = s.code + ':' + track.hash;
          const previous = guestReservations.get(key);
          if (previous && previous.expiresAt > now() && previous.memberId !== member.id) throw Error('This song is already being uploaded');
          guestReservations.set(key, {memberId:member.id, track, expiresAt:now()+300000});
          send(ws,{type:'guest_upload_granted',hash:track.hash});
          return;
        }
        if (member.role !== 'host' && b.type !== 'vote_track' &&
          !(s.guestControls && b.type === 'control' && b.action?.kind !== 'ended')) throw Error('Host controls only');
        if (!/^[a-zA-Z0-9-]{8,80}$/.test(b.id || '')) throw Error('Command ID required');
        if (s.seen.includes(b.id)) { send(ws, { type: 'ack', id: b.id }); return; }
        if (b.type === 'vote_track') {
          if (!/^[a-f0-9]{64}$/.test(b.hash || '') || !s.playlist.some(t => t.hash === b.hash)) throw Error('Unknown track');
          const current = effective(s, now()).index;
          if (s.playlist.findIndex(t => t.hash === b.hash) <= current) throw Error('Only upcoming songs can receive votes');
          s.votes ||= {};
          if (s.votes[member.id] === b.hash) delete s.votes[member.id]; else s.votes[member.id] = b.hash;
          const totals = voteTotals(s);
          const end = current + 1;
          const upcoming = s.playlist.slice(end).map((track, i) => ({track,i}));
          upcoming.sort((a,b) => (totals[b.track.hash] || 0) - (totals[a.track.hash] || 0) || a.i - b.i);
          s.playlist.splice(end,s.playlist.length-end,...upcoming.map(x=>x.track));
        } else if (b.type === 'control') schedule(s, b.action);
        else if (b.type === 'playlist') {
          const playlist = validatePlaylist(b.playlist);
          s.previous = effective(s, now()); s.playlist = playlist; s.votes = {};
          s.frame = { index: 0, positionMs: 0, playing: false, executeAt: now() + 1800, revision: s.frame.revision + 1 };
        } else if (b.type === 'settings') {
          s.guestControls = !!b.guestControls; s.guestAdditions = !!b.guestAdditions; const was = s.sharing; s.sharing = !!b.sharing;
          s.repeat = ['off', 'one', 'all'].includes(b.repeat) ? b.repeat : 'off';
          if (was && !s.sharing) {
            removeAudio(s.code);
            for (const key of guestReservations.keys()) if (key.startsWith(s.code+':')) guestReservations.delete(key);
          }
        } else throw Error('Unknown message');
        s.seen.push(b.id); s.seen = s.seen.slice(-512); save(s); send(ws, { type: 'ack', id: b.id }); broadcast(s);
      } catch (e) { send(ws, { type: 'error', error: e.message }); }
    });
    ws.on('close', () => { clearTimeout(deadline); if (context && peers.get(context.member.id)?.ws === ws) { releaseFloor(context.s, context.member.id); peers.delete(context.member.id); metric(context.s, context.member, 'offline'); broadcast(context.s); } });
    ws.on('error', () => {});
  });
  function removeAudio(c) { for (const f of readdirSync(dataDir)) if (f.startsWith(c + '-') && (f.endsWith('.mp3') || f.endsWith('.mp4') || f.endsWith('.tmp'))) try { unlinkSync(join(dataDir, f)); } catch {} }
  const cleanup = setInterval(() => {
    for (const [c, s] of sessions) if (s.expiresAt < now()) { releaseFloor(s); sessions.delete(c); removeAudio(c); db.prepare('DELETE FROM sessions WHERE code=?').run(c); for (const m of s.members) peers.get(m.id)?.ws.close(4001, 'Session expired'); }
    for (const [key, a] of rates) if (now() - a.at(-1) > 60000) rates.delete(key);
    for (const [key, value] of guestReservations) if (value.expiresAt < now()) guestReservations.delete(key);
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
  const floorExpiry = setInterval(() => {
    for (const [code, floor] of floors) {
      const session = sessions.get(code);
      if (!session) floors.delete(code);
      else if (floor.until <= now()) releaseFloor(session);
    }
  }, 1000).unref();
  const heartbeat = setInterval(() => { for (const ws of wss.clients) { if (ws.alive === false) ws.terminate(); else { ws.alive = false; ws.ping(); } } }, 15000).unref();
  wss.on('connection', ws => { ws.alive = true; ws.on('pong', () => { ws.alive = true; }); });
  return { server, sessions, listen: () => new Promise(resolve => server.listen(port, '0.0.0.0', () => {
    // Explicitly opt in only on a trusted LAN; cloud deployments never advertise.
    if (process.env.CARSYNC_LAN_DISCOVERY === '1') lanAdvertiser = advertiseLanRelay({port:server.address().port});
    resolve();
  })), close: async () => { closing = true; lanAdvertiser?.close(); clearInterval(cleanup); clearInterval(advance); clearInterval(heartbeat); clearInterval(floorExpiry); for (const ws of wss.clients) ws.terminate(); await new Promise(r => wss.close(r)); await new Promise(r => server.close(r)); db.close(); } };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const app = createServer(); await app.listen(); console.log(`CarSync server listening on ${app.server.address().port}`);
  for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, async () => { await app.close(); process.exit(); });
}
