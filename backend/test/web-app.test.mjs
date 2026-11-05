import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createServer} from '../src/server.mjs';
import {activeFrame,position,clockSample} from '../public/web/timing.mjs';
test('web audio timing keeps the prior frame until the scheduled command and rejects slow clocks',()=>{
  const previous={positionMs:100,playing:true,executeAt:1000},frame={positionMs:400,playing:false,executeAt:2000};
  assert.equal(activeFrame({previous,frame},1999),previous);
  assert.equal(activeFrame({previous,frame},2000),frame);
  assert.equal(position(previous,1500),600);assert.equal(position(frame,6000),400);
  assert.deepEqual(clockSample(100,200,160,160),{rtt:100,offset:10});
  assert.equal(clockSample(100,400,200,200,100),null);
  assert.equal(clockSample(100,110,200,250),null);
});
test('Home Screen shell serves only allowlisted files and keeps API credentials outside offline cache',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'carsync-web-')),app=createServer({dataDir:dir,port:0});await app.listen();
  t.after(async()=>{await app.close();rmSync(dir,{recursive:true,force:true});});
  const base=`http://127.0.0.1:${app.server.address().port}`;
  for(const path of ['/','/app.js','/timing.mjs','/style.css','/manifest.webmanifest','/sw.js','/icon.svg']){
    const response=await fetch(base+path);assert.equal(response.status,200,path);assert.match(response.headers.get('content-security-policy'),/frame-ancestors 'none'/);
  }
  const sw=await(await fetch(base+'/sw.js')).text();assert.match(sw,/SHELL.includes\(url.pathname\)/);assert.ok(!sw.includes("'/sessions'"));
  assert.equal((await fetch(base+'/src/server.mjs')).status,404);
  assert.equal((await fetch(base+'/',{method:'HEAD'})).status,200);
  const manifest=await(await fetch(base+'/manifest.webmanifest')).json();assert.equal(manifest.display,'standalone');
});
