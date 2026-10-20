import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {WebSocket} from 'ws';
import {createServer} from '../src/server.mjs';

test('location sharing revokes immediately even after rate limiting', async t => {
  const dir=mkdtempSync(join(tmpdir(),'carsync-consent-')), app=createServer({dataDir:dir,port:0});
  await app.listen();
  t.after(async()=>{await app.close();rmSync(dir,{recursive:true,force:true});});
  const url='http://127.0.0.1:'+app.server.address().port;
  const host=await(await fetch(url+'/sessions',{method:'POST',body:'{}'})).json();
  const guest=await(await fetch(url+'/sessions/'+host.code+'/join',{method:'POST',body:'{}'})).json();
  const open=async member=>{
    const ws=new WebSocket(url.replace('http','ws')+'/ws');
    const messages=[];ws.on('message',m=>messages.push(JSON.parse(m)));
    await new Promise((resolve,reject)=>{ws.once('open',resolve);ws.once('error',reject);});
    ws.send(JSON.stringify({type:'auth',code:member.code,token:member.token}));
    return {ws,messages};
  };
  const first=await open(host), second=await open(guest);
  const wait=async(client,matcher)=>{
    for(let i=0;i<300;i++){const n=client.messages.findIndex(matcher);if(n>=0)return client.messages.splice(n,1)[0];await new Promise(r=>setTimeout(r,10));}
    throw Error('Event never arrived');
  };
  await wait(first,x=>x.type==='state');await wait(second,x=>x.type==='state');
  first.ws.send(JSON.stringify({type:'location',lat:-1.28,lon:36.8,speed:0,bearing:0,accuracy:7}));
  const present=await wait(second,x=>x.type==='cars'&&x.cars.some(c=>c.id===host.memberId&&c.location?.lat===-1.28));
  assert.equal(present.cars.find(c=>c.id===host.memberId).location.lon,36.8);
  for(let i=0;i<48;i++)first.ws.send(JSON.stringify({type:'location',lat:-1.28,lon:36.8,speed:0,bearing:0,accuracy:7}));
  await new Promise(r=>setTimeout(r,200));
  second.messages.length=0;
  first.ws.send(JSON.stringify({type:'location',enabled:false}));
  const revoked=await wait(second,x=>x.type==='cars'&&!x.cars.find(c=>c.id===host.memberId)?.location);
  assert.equal(revoked.cars.find(c=>c.id===host.memberId).location,undefined);
  first.ws.close();second.ws.close();
});
