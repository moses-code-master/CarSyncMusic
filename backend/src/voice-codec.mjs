// 16 kHz mono G.711 μ-law companding. One frame = 20ms (320 samples).
// Codec offers an inexpensive 2:1 payload reduction; it is not Opus.
const BIAS = 0x84, CLIP = 32635;

export function encodeSample(value) {
  const sign = value < 0 ? 0x80 : 0;
  let magnitude = Math.min(Math.abs(value), CLIP) + BIAS;
  let exponent = 0, ceiling = 0xFF;
  while (magnitude > ceiling && exponent < 7) {
    ceiling = (ceiling << 1) + 1;
    exponent += 1;
  }
  const mantissa = (magnitude >> (exponent + 3)) & 0x0F;
  return (sign | (exponent << 4) | mantissa) ^ 0xFF;
}

export function decodeSample(byte) {
  const u = byte ^ 0xFF;
  const exponent = (u >> 4) & 7, mantissa = u & 15;
  const expanded = ((mantissa << 3) + BIAS) << exponent;
  return (u & 0x80) ? BIAS - expanded : expanded - BIAS;
}

export function encodeMulaw(pcm) {
  if (pcm.length !== 640) throw Error('Expected 640-byte PCM16 frame');
  const out = Buffer.allocUnsafe(320);
  for (let i = 0; i < 320; i++) out[i] = encodeSample(pcm.readInt16LE(i * 2));
  return out;
}

export function decodeMulaw(compact) {
  if (compact.length !== 320) throw Error('Expected 320-byte G.711 frame');
  const out = Buffer.allocUnsafe(640);
  for (let i = 0; i < 320; i++) out.writeInt16LE(decodeSample(compact[i]), i * 2);
  return out;
}
