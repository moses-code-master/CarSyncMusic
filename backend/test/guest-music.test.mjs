import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { WebSocket } from 'ws';
import { createServer } from '../src/server.mjs';

const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
test('guest speaks and uploads an MP3 with permission, without controlling session settings', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'carsync-guest-music-'));
  const app = createServer({dataDir:dir,port:0});
  await app.listen();
  t.after(async()=>{await app.close();rmSync(dir,{recursive:true,force:true});});
  const url = 'http://127.0.0.1:' + app.server.address().port;
  const post = async path => (await fetch(url+path,{method:'POST',body:'{}'})).json();
  const host = await post('/sessions');
  const guest = await post('/sessions/'+host.code+'/join');
  const other = await post('/sessions/'+host.code+'/join');
  const connect = async who => {
    const ws = new WebSocket(url.replace('http','ws')+'/ws');
    const messages = [];
    ws.on('message', data => messages.push(JSON.parse(data)));
    await new Promise((resolve,reject)=>{ws.once('open',resolve);ws.once('error',reject);});
    ws.send(JSON.stringify({type:'auth',code:who.code,token:who.token}));
    return {ws,messages};
  };
  const h=await connect(host), g=await connect(guest), o=await connect(other);
  const wait=async (c,p) => {
    for (let i=0;i<240;i++) {
      const index=c.messages.findIndex(p);
      if(index>=0)return c.messages.splice(index,1)[0];
      await pause(10);
    }
    throw Error('Timed out waiting for message: '+JSON.stringify(c.messages.slice(-3)));
  };
  const send=(c,type,extra={})=>c.ws.send(JSON.stringify({type,id:randomUUID(),...extra}));
  const state=await wait(g,x=>x.type==='state'&&x.cars.length===3);
  assert.ok(state.capabilities.includes('guest_music_upload_v1'));
  assert.equal(state.guestControls,false);
  // Voice floor is independent of playback and host privileges.
  send(g,'ptt_request');
  assert.equal((await wait(h,x=>x.type==='ptt_start')).speakerId,guest.memberId);
  send(h,'ptt_request');
  await wait(h,x=>x.type==='ptt_denied');
  send(g,'ptt_release');
  await wait(h,x=>x.type==='ptt_stop');
  send(g,'settings',{guestAdditions:true,sharing:true});
  assert.match((await wait(g,x=>x.type==='error')).error,/Host/);
  const bytes=Buffer.from('test-audio-payload');
  const hash=createHash('sha256').update(bytes).digest('hex');
  const track={hash,title:'Guest MP3',artist:'Test',album:'',durationMs:60000,mediaType:'audio',mimeType:'audio/mpeg',sizeBytes:bytes.length};
  send(g,'guest_add',{track});
  assert.match((await wait(g,x=>x.type==='error')).error,/Host must enable/);
  send(h,'settings',{sharing:true,guestAdditions:true,guestControls:true,repeat:'off'});
  await wait(g,x=>x.type==='state'&&x.sharing&&x.guestAdditions);
  send(g,'guest_add',{track});
  assert.equal((await wait(g,x=>x.type==='guest_upload_granted')).hash,hash);
  const address=url+'/sessions/'+host.code+'/audio/'+hash;
  const put=async who=>fetch(address,{method:'PUT',headers:{Authorization:'Bearer '+who.token},body:bytes});
  assert.equal((await put(other)).status,404,'Other member has no upload grant');
  const before=await fetch(url+'/sessions/'+host.code,{headers:{Authorization:'Bearer '+host.token}});
  assert.equal((await before.json()).playlist.length,0,'Song is not queued before upload completes');
  assert.equal((await put(guest)).status,200);
  const added=await wait(h,x=>x.type==='state'&&x.playlist.some(t=>t.hash===hash)&&x.audioAvailable.includes(hash));
  assert.equal(added.playlist[0].title,'Guest MP3');
  const get=await fetch(address,{headers:{Authorization:'Bearer '+other.token}});
  assert.equal(get.status,200);assert.deepEqual(Buffer.from(await get.arrayBuffer()),bytes);
  send(g,'settings',{sharing:false});
  assert.match((await wait(g,x=>x.type==='error')).error,/Host/);
  send(g,'control',{action:{kind:'play'}});
  await wait(h,x=>x.type==='state'&&x.frame.revision>0);
  for(const c of [h,g,o])c.ws.close();
});

test('health reports feature capabilities for outdated-client detection',async t=>{
 const dir=mkdtempSync(join(tmpdir(),'carsync-features-'));
 const app=createServer({dataDir:dir,port:0});await app.listen();
 t.after(async()=>{await app.close();rmSync(dir,{recursive:true,force:true});});
 const result=await(await fetch('http://127.0.0.1:'+app.server.address().port+'/health')).json();
 assert.equal(result.protocol,'convoy-v1');
 assert.ok(result.capabilities.includes('convoy_ptt_pcm16_v1'));
});
