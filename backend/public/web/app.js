import {activeFrame,position,clockSample} from './timing.mjs';
const $=id=>document.getElementById(id),audio=$('audio');
let auth=null,state=null,ws=null,offset=0,best=Infinity,reconnect=null,ping=null,enabled=false,loaded=null,loading=null,objectURL=null,generation=0,uploading=false;
const epoch=Date.now(),anchor=performance.now(),now=()=>epoch+performance.now()-anchor;
const error=e=>{$('error').textContent=e?.message||String(e);};
try{auth=JSON.parse(localStorage.getItem('carsync-session')||'null');}catch{}
const save=()=>localStorage.setItem('carsync-session',JSON.stringify(auth));
const send=value=>{if(ws?.readyState!==WebSocket.OPEN)throw Error('Connection lost. Wait for reconnection.');ws.send(JSON.stringify(value));};
const command=value=>send({id:crypto.randomUUID(),...value});
async function api(path,options={}){
  const response=await fetch(path,{...options,headers:{...(auth?{Authorization:`Bearer ${auth.token}`} :{}),...options.headers}});
  if(!response.ok){let message;try{message=(await response.json()).error;}catch{}throw Error(message||`Server error ${response.status}`);}
  return response;
}
const database=new Promise((resolve,reject)=>{const req=indexedDB.open('carsync-music',1);req.onupgradeneeded=()=>req.result.createObjectStore('tracks');req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);});
async function storage(mode,work){const db=await database;return new Promise((resolve,reject)=>{const tx=db.transaction('tracks',mode);let result;work(tx.objectStore('tracks'),value=>result=value);tx.oncomplete=()=>resolve(result);tx.onerror=()=>reject(tx.error);tx.onabort=()=>reject(tx.error);});}
const cached=hash=>storage('readonly',(store,done)=>{const req=store.get(hash);req.onsuccess=()=>done(req.result);});
const cache=(hash,blob)=>storage('readwrite',store=>store.put(blob,hash));
async function digest(bytes){return [...new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))].map(x=>x.toString(16).padStart(2,'0')).join('');}
function controls(){
  $('join').hidden=!!auth;$('session').hidden=!auth;
  $('sessionCode').textContent=auth?`SESSION ${auth.code} · ${auth.role}`:'';
  $('hostTools').hidden=auth?.role!=='host';
  for(const button of document.querySelectorAll('[data-control]'))button.disabled=!(ws?.readyState===WebSocket.OPEN&&(auth?.role==='host'||state?.guestControls));
}
function cars(items=[]){$('cars').replaceChildren(...items.map(car=>{const li=document.createElement('li');li.textContent=`${car.name} · ${car.online?'connected':'offline'} · ${car.role}`;return li;}));}
function render(){
  controls();cars(state?.cars);
  $('queue').replaceChildren(...(state?.playlist||[]).map((track,index)=>{
    const li=document.createElement('li'),button=document.createElement('button');button.textContent=track.title;
    button.disabled=!(auth?.role==='host'||state.guestControls)||(track.mediaType&&track.mediaType!=='audio');
    button.onclick=()=>{try{command({type:'control',action:{kind:'select',index}});}catch(e){error(e);}};
    li.append(button);return li;
  }));
}
function connect(){
  if(!auth)return;
  clearTimeout(reconnect);clearInterval(ping);ws?.close();
  const socket=new WebSocket(`${location.protocol==='https:'?'wss:':'ws:'}//${location.host}/ws`);ws=socket;best=Infinity;
  $('status').textContent='Connecting…';controls();
  socket.onopen=()=>{
    if(ws!==socket)return;
    send({type:'auth',...auth,mediaCapabilities:['audio'],sessionCapabilities:[]});
    send({type:'ping',clientTime:now()});ping=setInterval(()=>{if(socket.readyState===WebSocket.OPEN)socket.send(JSON.stringify({type:'ping',clientTime:now()}));},2000);
  };
  socket.onmessage=event=>{
    if(ws!==socket)return;
    try{
      const data=JSON.parse(event.data);
      if(data.type==='state'){
        state=data;auth.role=data.cars.find(c=>c.id===auth.memberId)?.role||auth.role;save();render();
        $('status').textContent=best===Infinity?'Connected · calibrating timing':`Connected · ${Math.round(best)} ms round trip`;
      }else if(data.type==='cars'){cars(data.cars);}
      else if(data.type==='pong'){
        const sample=clockSample(data.clientTime,now(),data.t1,data.t2,best);
        if(sample){best=sample.rtt;offset=sample.offset;$('status').textContent=`Connected · ${Math.round(best)} ms round trip`;}
      }else if(data.type==='error')error(data.error);
    }catch(e){error(e);}
  };
  socket.onclose=event=>{
    if(ws!==socket)return;
    clearInterval(ping);audio.pause();controls();
    if([4001,4002,4003].includes(event.code)){leave();error('Session ended, expired, or opened elsewhere. Join again.');return;}
    $('status').textContent='Connection lost · reconnecting…';reconnect=setTimeout(connect,2500);
  };
  socket.onerror=()=>{$('status').textContent='Unable to connect · retrying…';};
}
function leave(){
  generation++;auth=null;state=null;save();clearTimeout(reconnect);clearInterval(ping);
  const old=ws;ws=null;old?.close();audio.pause();audio.removeAttribute('src');audio.load();loaded=null;loading=null;
  if(objectURL){URL.revokeObjectURL(objectURL);objectURL=null;}
  $('status').textContent='Not connected';$('song').textContent='No song selected';$('transfer').textContent='';render();
}
async function start(host){
  try{
    const code=$('code').value.trim();if(!host&&!/^\d{6}$/.test(code))throw Error('Enter the six-digit session code.');
    $('error').textContent='';$('joinButton').disabled=true;$('create').disabled=true;
    leave();
    const response=await api(host?'/sessions':`/sessions/${code}/join`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:$('name').value||'iPhone car'})});
    auth=await response.json();save();generation++;connect();
  }catch(e){error(e);}finally{$('joinButton').disabled=false;$('create').disabled=false;}
}
$('joinButton').onclick=()=>start(false);$('create').onclick=()=>start(true);$('leave').onclick=leave;
// This user gesture unlocks Safari audio before remote playback begins. No silent background loop.
$('enable').onclick=async()=>{
  try{
    const context=new (window.AudioContext||window.webkitAudioContext)();await context.resume();
    const buffer=context.createBuffer(1,1,context.sampleRate),source=context.createBufferSource();source.buffer=buffer;source.connect(context.destination);source.start();
    await context.close();enabled=true;$('enable').textContent='Audio enabled · tap again if playback is blocked';
    if(audio.src)await audio.play();
  }catch(e){error(e);}
};
for(const button of document.querySelectorAll('[data-control]'))button.onclick=()=>{try{command({type:'control',action:{kind:button.dataset.control}});}catch(e){error(e);}};
async function load(track){
  const ownGeneration=generation;loading=track.hash;
  try{
    let blob=await cached(track.hash);
    if(!blob){
      if(!state.sharing||!state.audioAvailable.includes(track.hash))return;
      $('transfer').textContent='Downloading shared song…';
      const response=await api(`/sessions/${auth.code}/audio/${track.hash}`);
      if(Number(response.headers.get('Content-Length'))>100*1024*1024)throw Error('Song exceeds the 100 MB browser preview limit.');
      const bytes=await response.arrayBuffer();
      if(bytes.byteLength>100*1024*1024||await digest(bytes)!==track.hash)throw Error('Song integrity or size check failed.');
      blob=new Blob([bytes],{type:'audio/mpeg'});try{await cache(track.hash,blob);}catch{$('transfer').textContent='Storage full · playing without saving';}
    }
    if(ownGeneration!==generation)return;
    audio.pause();if(objectURL)URL.revokeObjectURL(objectURL);objectURL=URL.createObjectURL(blob);audio.src=objectURL;loaded=track.hash;audio.load();$('transfer').textContent='Song ready';
  }catch(e){error(e);await new Promise(r=>setTimeout(r,5000));}finally{if(ownGeneration===generation)loading=null;}
}
let playPending=false;
setInterval(()=>{
  if(!state||!auth||!enabled||ws?.readyState!==WebSocket.OPEN||!Number.isFinite(best))return;
  const t=now()+offset,frame=activeFrame(state,t),track=state.playlist[frame.index];
  if(!track){audio.pause();return;}
  $('song').textContent=track.title;
  if(track.mediaType&&track.mediaType!=='audio'){audio.pause();$('audioHint').textContent='This preview plays audio sessions only.';return;}
  if(!/^[a-f0-9]{64}$/.test(track.hash))return;
  if(loaded!==track.hash){audio.pause();if(!loading)void load(track);return;}
  if(audio.readyState<2||!Number.isFinite(audio.duration))return;
  const target=Math.min(audio.duration,Math.max(0,(position(frame,t)+Number($('offset').value))/1000)),drift=target-audio.currentTime;
  if(Math.abs(drift)>.3)audio.currentTime=target;
  audio.playbackRate=Math.abs(drift)>.06?(drift>0?1.015:.985):1;
  if(frame.playing&&target<audio.duration){if(audio.paused&&!playPending){playPending=true;audio.play().catch(()=>{enabled=false;$('audioHint').textContent='Safari needs another tap: press Enable audio.';}).finally(()=>{playPending=false;});}}
  else audio.pause();
},100);
$('offset').oninput=()=>{$('offsetValue').textContent=`${$('offset').value} ms`;};
$('clear').onclick=async()=>{try{generation++;audio.pause();loaded=null;loading=null;await storage('readwrite',store=>store.clear());$('transfer').textContent='Local songs cleared. Current shared song may download again.';}catch(e){error(e);}};
$('import').onchange=async event=>{
  const file=event.target.files[0];if(!file)return;
  if(uploading){error('Wait for the current upload to finish.');return;}
  const session=auth?.code,ownGeneration=generation;
  try{
    if(auth?.role!=='host')throw Error('Only the host can import in this preview.');
    if(!/\.mp3$/i.test(file.name)||!file.size||file.size>100*1024*1024)throw Error('Choose an MP3 up to 100 MB.');
    uploading=true;$('transfer').textContent='Reading MP3…';
    const bytes=await file.arrayBuffer(),hash=await digest(bytes);
    const temp=new Audio(),url=URL.createObjectURL(file);
    const duration=await new Promise((resolve,reject)=>{const timeout=setTimeout(()=>reject(Error('Could not read MP3 duration.')),15000);temp.onloadedmetadata=()=>{clearTimeout(timeout);resolve(temp.duration*1000);};temp.onerror=()=>{clearTimeout(timeout);reject(Error('Unsupported MP3 file.'));};temp.src=url;}).finally(()=>{temp.removeAttribute('src');temp.load();URL.revokeObjectURL(url);});
    if(!Number.isFinite(duration)||duration<=0)throw Error('Invalid MP3 duration.');
    if(ownGeneration!==generation)throw Error('Session changed; select your song again.');
    const track={hash,title:file.name.replace(/\.mp3$/i,''),artist:'Shared music',durationMs:duration,mediaType:'audio',mimeType:'audio/mpeg',sizeBytes:file.size};
    await cache(hash,new Blob([bytes],{type:'audio/mpeg'}));
    const playlist=state?.playlist||[];
    if(!playlist.some(t=>t.hash===hash))command({type:'playlist',playlist:[...playlist,track]});
    command({type:'settings',sharing:true,guestControls:state?.guestControls||false,guestAdditions:state?.guestAdditions||false,repeat:state?.repeat||'off'});
    for(let i=0;i<50&&(!state?.sharing||!state.playlist.some(t=>t.hash===hash));i++)await new Promise(r=>setTimeout(r,100));
    let failures=0;
    while(ownGeneration===generation&&auth?.code===session){
      try{
        const progress=await(await api(`/sessions/${session}/audio/${hash}?upload=1&size=${file.size}`)).json();
        if(progress.complete){$('transfer').textContent='Upload complete';break;}
        const start=progress.offset;if(!Number.isSafeInteger(start)||start<0||start>=file.size)throw Error('Invalid upload offset.');
        const end=Math.min(file.size,start+262144);
        await api(`/sessions/${session}/audio/${hash}`,{method:'PATCH',headers:{'Content-Type':'application/octet-stream','Content-Range':`bytes ${start}-${end-1}/${file.size}`},body:bytes.slice(start,end)});
        $('transfer').textContent=`Uploading ${Math.round(end/file.size*100)}%`;failures=0;
      }catch(e){if(++failures>8)throw e;$('transfer').textContent=`Reconnecting upload · retry ${failures}/8`;await new Promise(r=>setTimeout(r,Math.min(15000,failures*2000)));}
    }
  }catch(e){error(e);$('transfer').textContent='Upload paused. Select the same MP3 to resume.';}finally{uploading=false;event.target.value='';}
};
document.addEventListener('visibilitychange',()=>{if(!document.hidden&&auth){if(ws?.readyState===WebSocket.OPEN)send({type:'snapshot'});else if(ws?.readyState!==WebSocket.CONNECTING)connect();}});
if('serviceWorker'in navigator)navigator.serviceWorker.register('/sw.js').catch(()=>{});
controls();if(auth)connect();
