import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { WebSocket } from 'ws';
import { createServer } from '../src/server.mjs';

test('guest PTT floor is exclusive, frames relay, and disconnect releases it', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'carsync-ptt-'));
  const app = createServer({dataDir:dir,port:0}); await app.listen();
  t.after(async () => { await app.close(); rmSync(dir,{recursive:true,force:true}); });
  const url = 'http://127.0.0.1:' + app.server.address().port;
  const post = async path => (await fetch(url+path,{method:'POST',body:'{}'})).json();
  const host = await post('/sessions'), guest = await post('/sessions/'+host.code+'/join');
  const open = async member => {
    const socket = new WebSocket(url.replace('http','ws')+'/ws');
    const queue = [];
    socket.on('message', data => queue.push(JSON.parse(data)));
    await new Promise((resolve,reject) => { socket.once('open',resolve); socket.once('error',reject); });
    socket.send(JSON.stringify({type:'auth',code:member.code,token:member.token}));
    return {socket,queue};
  };
  const h=await open(host), g=await open(guest);
  const wait=async(client,predicate) => {
    for(let i=0;i<150;i++) {
      const pos=client.queue.findIndex(predicate);
      if(pos>=0)return client.queue.splice(pos,1)[0];
      await new Promise(r=>setTimeout(r,10));
    }
    throw Error('Timed out waiting for PTT event');
  };
  await wait(h,x=>x.type==='state'); await wait(g,x=>x.type==='state');
  g.socket.send(JSON.stringify({type:'ptt_request'}));
  assert.equal((await wait(h,x=>x.type==='ptt_start')).speakerId,guest.memberId);
  h.socket.send(JSON.stringify({type:'ptt_request'}));
  assert.equal((await wait(h,x=>x.type==='ptt_denied')).reason,'Channel busy');
  const pcm=Buffer.alloc(640).toString('base64');
  g.socket.send(JSON.stringify({type:'ptt_pcm',data:pcm}));
  assert.equal((await wait(h,x=>x.type==='ptt_pcm')).data,pcm);
  g.socket.close();
  assert.equal((await wait(h,x=>x.type==='ptt_stop')).speakerId,guest.memberId);
});

test('guests can vote on upcoming tracks without gaining playback control', async t => {
  const dir=mkdtempSync(join(tmpdir(),'carsync-vote-'));
  const app=createServer({dataDir:dir,port:0}); await app.listen();
  t.after(async()=>{await app.close();rmSync(dir,{recursive:true,force:true});});
  const url='http://127.0.0.1:'+app.server.address().port;
  const post=async path=>(await fetch(url+path,{method:'POST',body:'{}'})).json();
  const host=await post('/sessions'), guest=await post('/sessions/'+host.code+'/join');
  const mk=async member=>{
    const ws=new WebSocket(url.replace('http','ws')+'/ws'), messages=[];
    ws.on('message',d=>messages.push(JSON.parse(d)));
    await new Promise(r=>ws.once('open',r));
    ws.send(JSON.stringify({type:'auth',code:member.code,token:member.token}));
    return {ws,messages};
  };
  const a=await mk(host), b=await mk(guest);
  const wait=async(client,fn)=>{
    for(let i=0;i<150;i++){const j=client.messages.findIndex(fn);if(j>=0)return client.messages.splice(j,1)[0];await new Promise(r=>setTimeout(r,10));}
    throw Error('No response');
  };
  await wait(a,x=>x.type==='state');await wait(b,x=>x.type==='state');
  const tracks=['a','b','c'].map(h=>({hash:h.repeat(64),title:h,artist:'test',durationMs:50000}));
  a.ws.send(JSON.stringify({type:'playlist',id:crypto.randomUUID(),playlist:tracks}));
  await wait(b,x=>x.type==='state'&&x.playlist.length===3);
  b.ws.send(JSON.stringify({type:'control',id:crypto.randomUUID(),action:{kind:'next'}}));
  await wait(b,x=>x.type==='error'&&x.error.includes('Host'));
  b.ws.send(JSON.stringify({type:'vote_track',id:crypto.randomUUID(),hash:tracks[2].hash}));
  const voted=await wait(b,x=>x.type==='state'&&x.votes[tracks[2].hash]===1);
  assert.equal(voted.playlist[0].hash,tracks[0].hash);
  assert.equal(voted.playlist[1].hash,tracks[2].hash);
  a.ws.close();b.ws.close();
});
