import { randomInt, randomBytes, createHash } from 'node:crypto';
export const token = () => randomBytes(32).toString('base64url');
export const digest = x => createHash('sha256').update(x).digest('hex');
export const code = () => String(randomInt(100000, 1000000));
export const now = (() => { const epoch = Date.now(), mono = performance.now(); return () => epoch + performance.now() - mono; })();
export function position(frame, at) {
  return Math.max(0, frame.positionMs + (frame.playing ? Math.max(0, at - frame.executeAt) : 0));
}
export function effective(s, at) { return s.previous && at < s.frame.executeAt ? s.previous : s.frame; }
export function schedule(s, action, at = now()) {
  const executeAt = at + 1800;
  const old = effective(s, at);
  let frame = { ...old, positionMs: position(old, executeAt), executeAt, revision: s.frame.revision + 1 };
  if (action.kind === 'play') frame.playing = true;
  else if (action.kind === 'pause') frame.playing = false;
  else if (action.kind === 'seek') {
    if (!Number.isFinite(action.positionMs)) throw Error('Invalid position');
    frame.positionMs = Math.max(0, Math.min(action.positionMs, s.playlist[frame.index]?.durationMs || 0));
  } else if (['next', 'previous', 'select'].includes(action.kind)) {
    frame.index = action.kind === 'select' ? action.index : (frame.index + (action.kind === 'next' ? 1 : -1) + s.playlist.length) % s.playlist.length;
    if (!Number.isInteger(frame.index) || frame.index < 0 || frame.index >= s.playlist.length) throw Error('Invalid track');
    frame.positionMs = 0;
  } else if (action.kind === 'ended') {
    if (action.revision !== old.revision || action.index !== old.index || !old.playing) return false;
    const duration = s.playlist[old.index]?.durationMs || 0;
    if (position(old, at) < duration - 1000) return false;
    frame.positionMs = 0;
    if (s.repeat === 'one') frame.index = old.index;
    else if (old.index + 1 < s.playlist.length) frame.index++;
    else if (s.repeat === 'all') frame.index = 0;
    else { frame.playing = false; frame.positionMs = duration; }
  } else throw Error('Unknown command');
  if (!s.playlist.length) throw Error('Import and publish a playlist first');
  s.previous = old; s.frame = frame;
  return true;
}
export function validatePlaylist(list) {
  if (!Array.isArray(list) || list.length > 500) throw Error('Playlist must contain at most 500 tracks');
  return list.map(t => {
    if (!/^[a-f0-9]{64}$/.test(t.hash) || !Number.isFinite(t.durationMs) || t.durationMs <= 0 || t.durationMs > 86400000) throw Error('Invalid track');
    return { hash: t.hash, title: String(t.title || 'Untitled').slice(0, 200), artist: String(t.artist || 'Unknown artist').slice(0, 200), album: String(t.album || '').slice(0, 200), durationMs: t.durationMs };
  });
}
