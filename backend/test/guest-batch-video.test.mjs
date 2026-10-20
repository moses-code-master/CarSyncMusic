import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { WebSocket } from 'ws';
import { createServer } from '../src/server.mjs';

test('guest can sequentially contribute multiple MP3 songs and MP4s, including range playback', async t => {
  const dir=mkdtempSync(join(tmpdir(),'carsync-video-')),app=createServer({dataDir:dir,port:0});
  await app.listen();
  t.after(async()=>{await app.close();rmSync(dir,{recursive:true,force:true});});
  const url='http://127.0.0.1:'+app.server.address().port;
  const host=await(await fetch(url+'/sessions',{method:'POST',body:'{}'})).json();
  const guest=await(await fetch(url+'/sessions/'+host.code+'/join',{method:'POST',body:'{}'})).json();
  const messages=[];
  const ws=new WebSocket(url.replace('http','ws')+'/ws');
  ws.on('message',m=>messages.push(JSON.parse(m)));
  await new Promise((resolve,reject)=>{ws.once('open',resolve);ws.once('error',reject);});
  ws.send(JSON.stringify({type:'auth',code:guest.code,token:guest.token,mediaCapabilities:['audio','video']}));
  const wait=async predicate=>{
    for(let i=0;i<350;i++){let n=messages.findIndex(predicate);if(n>=0)return messages.splice(n,1)[0];await new Promise(r=>setTimeout(r,10));}
    throw Error('Timed out '+JSON.stringify(messages.slice(-4)));
  };
  await wait(x=>x.type==='state');
  const send=(type,data)=>ws.send(JSON.stringify({id:randomUUID(),type,...data}));
  const configure=()=>fetch(url+'/sessions/'+host.code,{headers:{Authorization:'Bearer '+host.token}});
  // Host switches permission on through authenticated WebSocket.
  const hostSocket=new WebSocket(url.replace('http','ws')+'/ws');
  await new Promise((resolve,reject)=>{hostSocket.once('open',resolve);hostSocket.once('error',reject);});
  hostSocket.send(JSON.stringify({type:'auth',code:host.code,token:host.token,mediaCapabilities:['audio','video']}));
  hostSocket.send(JSON.stringify({type:'settings',id:randomUUID(),guestAdditions:true,sharing:true,guestControls:true,repeat:'off'}));
  await wait(x=>x.type==='state'&&x.guestAdditions&&x.sharing);
  const objects=[
    {bytes:Buffer.from('first-song-byte-content'),type:'audio',mime:'audio/mpeg'},
    {bytes:Buffer.from('second-song-byte-content'),type:'audio',mime:'audio/mpeg'},
    {bytes:Buffer.from('video-container-test-bytes'),type:'video',mime:'video/mp4'}
  ];
  for(const [i,obj] of objects.entries()){
    const hash=createHash('sha256').update(obj.bytes).digest('hex');
    const track={hash,title:'Guest file '+i,artist:'Guest',durationMs:15000,mediaType:obj.type,mimeType:obj.mime,sizeBytes:obj.bytes.length,
      ...(obj.type==='video'?{width:320,height:180}:{})};
    send('guest_add',{track});
    assert.equal((await wait(x=>x.type==='guest_upload_granted')).hash,hash);
    const api=url+'/sessions/'+host.code+'/'+(obj.type==='video'?'video':'audio')+'/'+hash;
    const notYet=await configure();
    assert.equal((await notYet.json()).playlist.length,i,'Queue must not list unfinished uploads');
    const put=await fetch(api,{method:'PUT',headers:{Authorization:'Bearer '+guest.token},body:obj.bytes});
    assert.equal(put.status,200);
    await wait(x=>x.type==='state'&&x.playlist.length===i+1);
    const got=await fetch(api,{headers:{Authorization:'Bearer '+host.token,Range:'bytes=2-7'}});
    assert.equal(got.status,206);
    assert.deepEqual(Buffer.from(await got.arrayBuffer()),obj.bytes.subarray(2,8));
    assert.equal(got.headers.get('content-type'),obj.mime);
  }
  const state=await configure();
  const value=await state.json();
  assert.deepEqual(value.playlist.map(x=>x.mediaType),['audio','audio','video']);
  assert.equal(value.audioAvailable.length,3);
  const broken={...value.playlist[2],hash:'f'.repeat(64),mimeType:'video/x-matroska'};
  send('guest_add',{track:broken});
  assert.match((await wait(x=>x.type==='error')).error,/Only MP3 or MP4/);
  ws.close();hostSocket.close();
});
