import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { WebSocket } from 'ws';
import { createServer } from '../src/server.mjs';
import { handoverHost } from '../src/host-handover.mjs';

test('host election waits for grace, preserves the timeline and leaves one host', () => {
  const s = { members: [{id:'h',role:'host',tokenHash:'original'}, {id:'a',role:'guest'}, {id:'b',role:'guest'}], playlist:[{hash:'x'}], frame:{revision:5,playing:true} };
  const original = JSON.stringify([s.playlist,s.frame]);
  assert.equal(handoverHost(s,new Set(['a','b']),100,144,45),null);
  assert.equal(handoverHost(s,new Set(),100,200,45),null);
  const result = handoverHost(s,new Set(['a','b']),100,145,45);
  assert.equal(result.hostId,'a'); assert.equal(s.members.filter(x=>x.role==='host').length,1);
  assert.equal(s.members[0].tokenHash,'original'); assert.equal(JSON.stringify([s.playlist,s.frame]),original);
});

async function setup(t, grace) {
  const dir = mkdtempSync(join(tmpdir(),'carsync-host-')); let app = createServer({dataDir:dir,port:0,hostHandoverMs:grace}); await app.listen();
  t.after(async()=>{await app.close();rmSync(dir,{recursive:true,force:true});});
  let base = `http://127.0.0.1:${app.server.address().port}`;
  const post = async p => (await fetch(base+p,{method:'POST',body:'{}'})).json();
  const host = await post('/sessions'), guest = await post(`/sessions/${host.code}/join`);
  const connect = async (who, capable = true) => {
    const messages=[], ws=new WebSocket(base.replace('http','ws')+'/ws'); ws.on('message',d=>messages.push(JSON.parse(d)));
    await new Promise((resolve,reject)=>{ws.once('open',resolve);ws.once('error',reject);});
    ws.send(JSON.stringify({type:'auth',...who,sessionCapabilities:capable?['host_handover_v1']:[],mediaCapabilities:['audio','video']}));
    const wait=async predicate=>{for(let i=0;i<300;i++){const n=messages.findIndex(predicate);if(n>=0)return messages.splice(n,1)[0];await new Promise(r=>setTimeout(r,10));}throw Error('Message timed out');};
    const identity = await wait(x=>x.type==='identity'); assert.equal(identity.memberId,who.memberId);
    await wait(x=>x.type==='state'); return {ws,wait};
  };
  return { get base() { return base; }, host,guest,connect, async restart() { await app.close(); app=createServer({dataDir:dir,port:0,hostHandoverMs:grace}); await app.listen(); base=`http://127.0.0.1:${app.server.address().port}`; } };
}

test('a capable connected guest becomes host and the former host loses host-only access', async t=>{
  const f=await setup(t,80), h=await f.connect(f.host), g=await f.connect(f.guest);
  const before=(await (await fetch(`${f.base}/sessions/${f.host.code}`,{headers:{Authorization:`Bearer ${f.host.token}`}})).json()).frame;
  h.ws.terminate(); await g.wait(x=>x.type==='host_changed'&&x.hostId===f.guest.memberId);
  const state=await g.wait(x=>x.type==='state'&&x.cars.some(c=>c.id===f.guest.memberId&&c.role==='host'));
  assert.deepEqual(state.frame,before);
  const old=await f.connect(f.host);
  const read=await fetch(`${f.base}/sessions/${f.host.code}`,{headers:{Authorization:`Bearer ${f.host.token}`}});
  assert.equal((await read.json()).cars.find(c=>c.id===f.host.memberId).role,'guest');
  assert.equal((await fetch(`${f.base}/sessions/${f.host.code}/metrics`,{headers:{Authorization:`Bearer ${f.host.token}`}})).status,403);
  assert.equal((await fetch(`${f.base}/sessions/${f.host.code}/metrics`,{headers:{Authorization:`Bearer ${f.guest.token}`}})).status,200);
  old.ws.close(); g.ws.close();
  await f.restart(); const recovered=await f.connect(f.guest);
  const persisted=await fetch(`${f.base}/sessions/${f.host.code}`,{headers:{Authorization:`Bearer ${f.guest.token}`}});
  assert.equal((await persisted.json()).cars.find(c=>c.id===f.guest.memberId).role,'host'); recovered.ws.close();
});

test('quick host reconnection and legacy guests do not trigger an election', async t=>{
  const f=await setup(t,600), h=await f.connect(f.host), g=await f.connect(f.guest,false);
  h.ws.terminate(); await new Promise(r=>setTimeout(r,100)); const resumed=await f.connect(f.host);
  await new Promise(r=>setTimeout(r,700));
  let read=await fetch(`${f.base}/sessions/${f.host.code}`,{headers:{Authorization:`Bearer ${f.host.token}`}});
  assert.equal((await read.json()).cars.find(c=>c.id===f.host.memberId).role,'host');
  resumed.ws.terminate(); await new Promise(r=>setTimeout(r,900));
  read=await fetch(`${f.base}/sessions/${f.host.code}`,{headers:{Authorization:`Bearer ${f.guest.token}`}});
  assert.equal((await read.json()).cars.find(c=>c.id===f.guest.memberId).role,'guest'); g.ws.close();
});

test('privacy endpoint is public and exposes no session credentials',async t=>{
  const f=await setup(t,600);
  const response=await fetch(f.base+'/privacy'); const text=await response.text();
  assert.equal(response.status,200); assert.match(response.headers.get('content-type'),/text\/plain/);
  assert.match(text,/Location/); assert.match(text,/Developer contact/); assert.equal(text.includes(f.host.token),false);
});

test('an older host never opts into host-role migration implicitly',async t=>{
  const f=await setup(t,80), h=await f.connect(f.host,false), g=await f.connect(f.guest,true);
  h.ws.terminate(); await new Promise(r=>setTimeout(r,500));
  const response=await fetch(`${f.base}/sessions/${f.host.code}`,{headers:{Authorization:`Bearer ${f.guest.token}`}});
  const state=await response.json();
  assert.equal(state.cars.find(c=>c.id===f.host.memberId).role,'host');
  assert.equal(state.cars.find(c=>c.id===f.guest.memberId).role,'guest'); g.ws.close();
});

test('a car joining mid-transmission receives the current intercom start event',async t=>{
  const f=await setup(t,600), h=await f.connect(f.host);
  h.ws.send(JSON.stringify({type:'ptt_request'})); await h.wait(x=>x.type==='ptt_start');
  const g=await f.connect(f.guest);
  const started=await g.wait(x=>x.type==='ptt_start'); assert.equal(started.speakerId,f.host.memberId);
  h.ws.close(); g.ws.close();
});
