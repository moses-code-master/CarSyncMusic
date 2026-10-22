/** Opt-in live positions must disappear after a short and bounded absence. */
export const LOCATION_TTL_MS = 30_000;
export function recentLocation(location, serverTimeMs) {
  if (!location || !Number.isFinite(location.at)) return null;
  const age = serverTimeMs - location.at;
  return age >= 0 && age < LOCATION_TTL_MS ? location : null;
}
