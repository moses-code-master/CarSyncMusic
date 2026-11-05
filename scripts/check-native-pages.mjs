import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';

const root = process.argv[2];
if (!root) throw Error('Usage: node scripts/check-native-pages.mjs <merged native lib directory>');
const files = [];
function walk(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) walk(path); else if (entry.name.endsWith('.so')) files.push(path);
  }
}
walk(root);
const report = files.map(path => {
  const bytes = readFileSync(path);
  if (bytes.subarray(0,4).toString('hex') !== '7f454c46' || bytes[5] !== 1) throw Error('Not a little-endian ELF: ' + path);
  const wide = bytes[4] === 2;
  const headers = wide ? Number(bytes.readBigUInt64LE(32)) : bytes.readUInt32LE(28);
  const size = bytes.readUInt16LE(wide ? 54 : 42), count = bytes.readUInt16LE(wide ? 56 : 44);
  const alignments = [];
  for (let index=0;index<count;index++) {
    const at = headers + index * size;
    if (bytes.readUInt32LE(at) === 1) alignments.push(wide ? Number(bytes.readBigUInt64LE(at+48)) : bytes.readUInt32LE(at+28));
  }
  return { file: relative(root,path), bits: wide ? 64 : 32, loadAlignments: alignments,
    compatible16KB: alignments.length > 0 && alignments.every(value => value >= 16384) };
});
process.stdout.write(JSON.stringify(report,null,2)+'\n');
if (report.some(item => item.bits === 64 && !item.compatible16KB)) process.exitCode = 1;
