const VERSION='carsync-shell-v1';
const SHELL=['/','/style.css','/app.js','/timing.mjs','/icon.svg','/manifest.webmanifest'];
self.addEventListener('install',event=>event.waitUntil(caches.open(VERSION).then(cache=>cache.addAll(SHELL))));
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith('carsync-shell-')&&k!==VERSION).map(k=>caches.delete(k))))));
self.addEventListener('fetch',event=>{
  const url=new URL(event.request.url);
  if(event.request.method!=='GET'||url.origin!==self.location.origin||!SHELL.includes(url.pathname)||url.search)return;
  event.respondWith(fetch(event.request).catch(()=>caches.match(event.request)));
});
