import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { WebSocket } from 'ws';
import { createServer } from '../src/server.mjs';
import { digest } from '../src/model.mjs';

class Client {
  constructor(url, credentials) {
    this.messages = []; this.ws = new WebSocket(url.replace('http', 'ws') + '/ws');
    this.ready = new Promise((resolve, reject) => { this.ws.on('error', reject); this.ws.on('open', () => { this.ws.send(JSON.stringify({type:'auth',...credentials})); resolve(); }); });
    this.ws.on('message', data => this.messages.push(JSON.parse(data)));
  }
  send(type, payload = {}) { const id = crypto.randomUUID(); this.ws.send(JSON.stringify({type,id,...payload})); return id; }
  async wait(fn) { for(let i=0;i<400;i++) { const n=this.messages.findIndex(fn); if(n>=0) return this.messages.splice(n,1)[0]; await new Promise(r=>setTimeout(r,10)); } throw Error('Group message timed out'); }
}
test('one host shares an uploaded track and identical controls with three independent guests', async t => {
  const dir=mkdtempSync(join(tmpdir(),'carsync-group-')), app=createServer({dataDir:dir,port:0}); await app.listen();
  const url=`http://127.0.0.1:${app.server.address().port}`;
  t.after(async()=>{await app.close();rmSync(dir,{recursive:true,force:true});});
  const post=async path=>{const r=await fetch(url+path,{method:'POST',body:'{}'});assert.equal(r.status,200);return r.json();};
  const host=await post('/sessions'); const guests=[];
  for(let i=0;i<3;i++) guests.push(await post(`/sessions/${host.code}/join`));
  assert.equal(new Set([host,...guests].map(m=>m.token)).size,4);
  const a=new Client(url,host), listeners=guests.map(g=>new Client(url,g));
  await Promise.all([a,...listeners].map(c=>c.ready));
  const initial=await a.wait(x=>x.type==='state'&&x.cars.filter(c=>c.online).length===4);
  assert.equal(initial.maxGuests,10);
  const bytes=Buffer.from('Group sharing integrity fixture'), hash=digest(bytes);
  a.send('playlist',{playlist:[{hash,title:'Shared track',artist:'Test',durationMs:60000},{hash:'b'.repeat(64),title:'Second',artist:'Test',durationMs:60000}]});
  const playlist=await a.wait(x=>x.type==='state'&&x.frame.revision===1);
  for(const c of listeners) assert.deepEqual((await c.wait(x=>x.type==='state'&&x.frame.revision===1)).playlist,playlist.playlist);
  for(const [i,kind] of ['play','pause','seek','next','previous'].entries()) {
    a.send('control',{action:{kind,...(kind==='seek'?{positionMs:12000}:{})}});
    const hostState=await a.wait(x=>x.type==='state'&&x.frame.revision===i+2);
    for(const c of listeners) assert.deepEqual((await c.wait(x=>x.type==='state'&&x.frame.revision===i+2)).frame,hostState.frame);
  }
  // Guests have no control until explicitly granted by the host.
  listeners[0].send('control',{action:{kind:'pause'}}); assert.match((await listeners[0].wait(x=>x.type==='error')).error,/Host/);
  a.send('settings',{sharing:true,guestControls:true}); await a.wait(x=>x.type==='state'&&x.sharing&&x.guestControls);
  for(const c of listeners) await c.wait(x=>x.type==='state'&&x.sharing&&x.guestControls);
  const media=`${url}/sessions/${host.code}/audio/${hash}`;
  assert.equal((await fetch(media,{method:'PUT',headers:{Authorization:`Bearer ${host.token}`},body:bytes})).status,200);
  for(const c of listeners) assert.ok((await c.wait(x=>x.type==='state'&&x.audioAvailable.includes(hash))).audioAvailable.includes(hash));
  await Promise.all(guests.map(async g=>{const response=await fetch(media,{headers:{Authorization:`Bearer ${g.token}`,Range:'bytes=2-12'}});assert.equal(response.status,206);assert.equal(await response.text(),bytes.subarray(2,13).toString());}));
  assert.equal((await fetch(media,{method:'PUT',headers:{Authorization:`Bearer ${guests[1].token}`},body:bytes})).status,403);
  listeners[1].send('control',{action:{kind:'pause'}}); const paused=await a.wait(x=>x.type==='state'&&x.frame.revision===7);
  for(const c of listeners) assert.deepEqual((await c.wait(x=>x.type==='state'&&x.frame.revision===7)).frame,paused.frame);
  // Rejoining uses the original token and doesn't consume another place or disturb peers.
  listeners[0].ws.terminate(); const resumed=new Client(url,guests[0]);await resumed.ready;
  const snapshot=await resumed.wait(x=>x.type==='state'); assert.equal(snapshot.cars.length,4);assert.deepEqual(snapshot.frame,paused.frame);assert.ok(snapshot.audioAvailable.includes(hash));
  // A fourth guest can join an already-playing/shared session and access the same stored upload.
  const late=await post(`/sessions/${host.code}/join`), lateClient=new Client(url,late);await lateClient.ready;
  const lateState=await lateClient.wait(x=>x.type==='state');assert.equal(lateState.cars.length,5);assert.deepEqual(lateState.frame,paused.frame);assert.ok(lateState.audioAvailable.includes(hash));
  assert.equal((await fetch(media,{headers:{Authorization:`Bearer ${late.token}`}})).status,200);
  for(const c of [...listeners,resumed,lateClient]) c.messages=[];
  a.send('settings',{sharing:false,guestControls:false});await a.wait(x=>x.type==='state'&&!x.sharing);
  for(const c of [listeners[1],listeners[2],resumed,lateClient]) assert.deepEqual((await c.wait(x=>x.type==='state'&&!x.sharing)).audioAvailable,[]);
  for(const g of [...guests,late]) assert.equal((await fetch(media,{headers:{Authorization:`Bearer ${g.token}`}})).status,403);
});

test('default session admits ten guests and rejects the eleventh without displacing anyone',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'carsync-capacity-')),app=createServer({dataDir:dir,port:0});await app.listen();t.after(async()=>{await app.close();rmSync(dir,{recursive:true,force:true});});
  const url=`http://127.0.0.1:${app.server.address().port}`, host=await(await fetch(url+'/sessions',{method:'POST',body:'{}'})).json();
  const members=[];
  for(let i=0;i<10;i++){const r=await fetch(`${url}/sessions/${host.code}/join`,{method:'POST',body:'{}'});assert.equal(r.status,200);members.push(await r.json());}
  const overflow=await fetch(`${url}/sessions/${host.code}/join`,{method:'POST',body:'{}'});assert.equal(overflow.status,409);assert.match((await overflow.json()).error,/10-guest limit/);
  const state=await(await fetch(`${url}/sessions/${host.code}`,{headers:{Authorization:`Bearer ${members[0].token}`}})).json();assert.equal(state.cars.length,11);assert.equal(state.cars.filter(c=>c.role==='host').length,1);assert.equal(state.maxGuests,10);
});
test('guest limit configuration rejects invalid values',()=>{for(const maxGuests of [0,-1,1.5,51,NaN])assert.throws(()=>createServer({maxGuests}),/MAX_GUESTS/);});
