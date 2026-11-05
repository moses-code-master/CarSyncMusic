export function activeFrame(state, now) { return state.previous && now < state.frame.executeAt ? state.previous : state.frame; }
export function position(frame, now) { return Math.max(0, frame.positionMs + (frame.playing ? Math.max(0, now-frame.executeAt) : 0)); }
export function clockSample(sent, received, t1, t2, best=Infinity) {
  const rtt=received-sent-(t2-t1);
  return Number.isFinite(rtt) && rtt>=0 && rtt<5000 && rtt<=best ? {rtt,offset:((t1-sent)+(t2-received))/2} : null;
}
