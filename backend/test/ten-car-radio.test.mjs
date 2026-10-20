import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { WebSocket } from 'ws';
import { createServer } from '../src/server.mjs';
import { encodeMulaw } from '../src/voice-codec.mjs';

const delay = ms => new Promise(r => setTimeout(r, ms));
async function until(predicate, timeout = 5000) {
  const end = performance.now() + timeout;
  while (performance.now() < end) {
    const value = predicate();
    if (value) return value;
    await delay(10);
  }
  throw Error('Radio convoy test timed out');
}

/** Ten real WebSocket clients: join, transmit, transfer floor, recover. Not a WAN benchmark. */
test('ten cars receive live G.711 voice with floor handoff and bounded local fanout', {timeout:30000}, async t => {
  const dir = mkdtempSync(join(tmpdir(), 'carsync-radio-10-'));
  const app = createServer({dataDir:dir,port:0,maxGuests:10});
  await app.listen();
  t.after(async () => { await app.close(); rmSync(dir,{recursive:true,force:true}); });
  const base='http://127.0.0.1:'+app.server.address().port;
  const identities=[await (await fetch(base+'/sessions',{method:'POST',body:'{}'})).json()];
  for(let n=1;n<10;n++){
    const guest=await (await fetch(base+'/sessions/'+identities[0].code+'/join',{
      method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({name:'Convoy '+n})
    })).json();
    assert.ok(guest.token);
    identities.push(guest);
  }
  const members=[];
  for(let i=0;i<10;i++){
    const who=identities[i], ws=new WebSocket(base.replace('http:','ws:')+'/ws');
    const packets=[];
    ws.on('message',data=>packets.push(JSON.parse(data.toString())));
    await new Promise((resolve,reject)=>{ws.once('open',resolve);ws.once('error',reject);});
    ws.send(JSON.stringify({type:'auth',code:who.code,token:who.token,
      voiceCodecs:i%3===0?[]:['mulaw16']}));
    members.push({ws,packets,compact:i%3!==0});
  }
  t.after(()=>members.forEach(x=>x.ws.close()));
  await until(()=>members.every(x=>x.packets.some(p=>p.type==='state'&&p.cars.length===10)));
  const pcm=Buffer.alloc(640);
  for(let i=0;i<320;i++)pcm.writeInt16LE(Math.round(Math.sin(i*.11)*13000),i*2);
  const encoded=encodeMulaw(pcm).toString('base64'), raw=pcm.toString('base64');
  const runSpeaker=async speaker=>{
    const lead=members[speaker], speakerId=identities[speaker].memberId;
    const speakersBefore=members.map(m=>m.packets.filter(x=>x.type==='ptt_start'&&x.speakerId===speakerId).length);
    lead.ws.send(JSON.stringify({type:'ptt_request'}));
    await until(()=>members.every((m,i)=>m.packets.filter(x=>x.type==='ptt_start'&&x.speakerId===speakerId).length>speakersBefore[i]));
    const counters=members.map(m=>m.packets.filter(x=>x.type==='ptt_mulaw'||x.type==='ptt_pcm').length);
    const start=performance.now();
    // Send 15 packets at 20 ms intervals, approximating microphone capture pacing.
    for(let n=0;n<15;n++){
      lead.ws.send(JSON.stringify({type:lead.compact?'ptt_mulaw':'ptt_pcm',data:lead.compact?encoded:raw}));
      await delay(20);
    }
    await until(()=>members.every((m,i)=>i===speaker||m.packets.filter(x=>x.type==='ptt_mulaw'||x.type==='ptt_pcm').length>counters[i]),5000);
    const observedMs=Math.round(performance.now()-start);
    for(let i=0;i<10;i++){
      const newPackets=members[i].packets.filter(x=>x.type==='ptt_mulaw'||x.type==='ptt_pcm').slice(counters[i]);
      if(i===speaker){assert.equal(newPackets.length,0);continue;}
      assert.ok(newPackets.length>0 && newPackets.length<=15);
      for(const packet of newPackets) {
        const len=Buffer.from(packet.data,'base64').length;
        assert.equal(packet.type,members[i].compact?'ptt_mulaw':'ptt_pcm');
        assert.equal(len,members[i].compact?320:640);
      }
    }
    lead.ws.send(JSON.stringify({type:'ptt_release'}));
    await until(()=>members.every(m=>m.packets.some(x=>x.type==='ptt_stop'&&x.speakerId===speakerId)));
    return observedMs;
  };
  const first=await runSpeaker(0), second=await runSpeaker(1);
  // Local performance numbers are diagnostic only and not used as WAN guarantees.
  t.diagnostic(JSON.stringify({clients:10, framesPerSpeaker:15, firstMs:first,
    secondMs:second, codecs:{compact:6, legacy:4}}));
});
