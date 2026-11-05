// Selection changes authority only. The canonical playlist/timeline and each
// member's original authentication credentials remain intact.
export function handoverHost(session, eligibleIds, offlineAt, at, graceMs) {
  if (offlineAt == null || at - offlineAt < graceMs) return null;
  const previous = session.members.find(member => member.role === 'host');
  if (!previous || eligibleIds.has(previous.id)) return null;
  const successor = session.members.find(member => member.role === 'guest' && eligibleIds.has(member.id));
  if (!successor) return null;
  previous.role = 'guest'; successor.role = 'host';
  return { previousHostId: previous.id, hostId: successor.id, hostName: successor.name };
}
