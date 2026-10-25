import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { WebSocket } from 'ws';
import { createServer } from '../src/server.mjs';
import { encodeMulaw, decodeMulaw } from '../src/voice-codec.mjs';

test('G.711 frames are half PCM size and decode within normal speech tolerance', () => {
  const pcm = Buffer.alloc(640);
  for (let i = 0; i < 320; i++)
    pcm.writeInt16LE(Math.round(Math.sin(i * 2 * Math.PI * 300 / 16000) * 16000), i * 2);
  const encoded = encodeMulaw(pcm);
  assert.equal(encoded.length, 320);
  const decoded = decodeMulaw(encoded);
  assert.equal(decoded.length, 640);
  for (let i = 0; i < 320; i++)
    assert.ok(Math.abs(decoded.readInt16LE(i*2) - pcm.readInt16LE(i*2)) < 1500);
  assert.throws(() => encodeMulaw(Buffer.alloc(33)), /640/);
  assert.throws(() => decodeMulaw(Buffer.alloc(33)), /320/);
});

test('mixed old/new convoy clients receive the correct negotiated radio codec', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'carsync-radio-'));
  const app = createServer({dataDir:dir,port:0});
  await app.listen();
  t.after(async () => { await app.close(); rmSync(dir,{recursive:true,force:true}); });
  const address = 'http://127.0.0.1:' + app.server.address().port;
  const host = await (await fetch(address+'/sessions',{method:'POST',body:'{}'})).json();
  const newGuest = await (await fetch(address+'/sessions/'+host.code+'/join',{method:'POST',body:'{}'})).json();
  const oldGuest = await (await fetch(address+'/sessions/'+host.code+'/join',{method:'POST',body:'{}'})).json();
  const connect = async (who, compact) => {
    const ws = new WebSocket(address.replace('http','ws')+'/ws');
    const messages = [];
    ws.on('message', chunk => messages.push(JSON.parse(chunk)));
    await new Promise((resolve,reject)=>{ws.once('open',resolve);ws.once('error',reject);});
    ws.send(JSON.stringify({type:'auth',code:who.code,token:who.token,voiceCodecs:compact?['mulaw16']:[]}));
    return {ws,messages};
  };
  const hostPeer = await connect(host,true);
  const modern = await connect(newGuest,true);
  const legacy = await connect(oldGuest,false);
  const wait = async (p,pred) => {
    for(let tries=0;tries<180;tries++){
      const index=p.messages.findIndex(pred);
      if(index>=0)return p.messages.splice(index,1)[0];
      await new Promise(r=>setTimeout(r,10));
    }
    throw Error('Waited for radio frame: '+JSON.stringify(p.messages.slice(-5)));
  };
  const state=await wait(modern,x=>x.type==='state'&&x.cars.length===3);
  assert.ok(state.capabilities.includes('convoy_ptt_mulaw_v1'));
  hostPeer.ws.send(JSON.stringify({type:'ptt_request'}));
  await wait(modern,x=>x.type==='ptt_start');
  await wait(legacy,x=>x.type==='ptt_start');
  const audio=Buffer.alloc(640);
  for(let i=0;i<320;i++)audio.writeInt16LE(Math.round(Math.sin(i*.2)*12500),i*2);
  hostPeer.ws.send(JSON.stringify({type:'ptt_mulaw',data:encodeMulaw(audio).toString('base64')}));
  const frameNew=await wait(modern,x=>x.type==='ptt_mulaw');
  const frameOld=await wait(legacy,x=>x.type==='ptt_pcm');
  assert.equal(Buffer.from(frameNew.data,'base64').length,320);
  assert.equal(Buffer.from(frameOld.data,'base64').length,640);
  hostPeer.ws.send(JSON.stringify({type:'ptt_release'}));
  await wait(modern,x=>x.type==='ptt_stop');
  legacy.ws.send(JSON.stringify({type:'ptt_request'}));
  await wait(modern,x=>x.type==='ptt_start'&&x.speakerId===oldGuest.memberId);
  legacy.ws.send(JSON.stringify({type:'ptt_pcm',data:audio.toString('base64')}));
  const converted=await wait(modern,x=>x.type==='ptt_mulaw');
  assert.equal(Buffer.from(converted.data,'base64').length,320);
  for(const peer of [hostPeer,modern,legacy])peer.ws.close();
});
