/** Straight-line GPS distance without a map dependency. Coordinates are degrees. */
const EARTH_RADIUS_METERS = 6_371_000;
const radians = degrees => degrees * Math.PI / 180;
const valid = p => p && Number.isFinite(p.lat) && Number.isFinite(p.lon) && Math.abs(p.lat) <= 90 && Math.abs(p.lon) <= 180;
export function estimateDistanceMeters(a, b) {
  if (!valid(a) || !valid(b)) return null;
  const dLat = radians(b.lat - a.lat);
  const dLon = radians(b.lon - a.lon);
  const h = Math.sin(dLat / 2) ** 2 +
    Math.cos(radians(a.lat)) * Math.cos(radians(b.lat)) * Math.sin(dLon / 2) ** 2;
  return Math.round(2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(Math.min(1, h))));
}
/** Do not show an old position as a live estimate. */
export function estimateRecentDistance(a, b, nowMs, maxAgeMs = 30_000) {
  if (!Number.isFinite(nowMs) || !Number.isFinite(maxAgeMs) || maxAgeMs <= 0) return null;
  if (![a,b].every(p => p && Number.isFinite(p.at) && nowMs >= p.at && nowMs - p.at < maxAgeMs)) return null;
  return estimateDistanceMeters(a, b);
}
