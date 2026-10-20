import {test} from 'node:test';
import assert from 'node:assert/strict';
import {buildAnnouncement,encodeName,queriesCarSync,SERVICE} from '../src/mdns.mjs';

test('mDNS announcer publishes a DNS-SD service and IPv4 endpoint', () => {
  const b=buildAnnouncement({instance:'CarSync Relay Test',host:'carsync-laptop',ip:'192.168.1.42',port:8080});
  assert.equal(b.readUInt16BE(6),4);
  assert.ok(b.includes(encodeName(SERVICE)));
  assert.ok(b.includes(Buffer.from([192,168,1,42])));
  assert.ok(b.includes(Buffer.from([0x1f,0x90]))); // port 8080
  assert.throws(()=>buildAnnouncement({ip:'bad.address',port:8080}),/IPv4/);
});

test('discovery only responds to relevant PTR requests', () => {
  const question=Buffer.alloc(12);
  question.writeUInt16BE(1,4);
  const tail=Buffer.from([0,12,0,1]);
  const packet=Buffer.concat([question,encodeName(SERVICE),tail]);
  assert.equal(queriesCarSync(packet),true);
  assert.equal(queriesCarSync(Buffer.concat([question,encodeName('_printer._tcp.local'),tail])),false);
  assert.equal(queriesCarSync(Buffer.from([0])),false);
});
