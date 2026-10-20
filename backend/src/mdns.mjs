import dgram from 'node:dgram';
import os from 'node:os';

export const SERVICE = '_carsync._tcp.local';
const ADDRESS = '224.0.0.251';
const PORT = 5353;
const TYPE = { A: 1, PTR: 12, TXT: 16, SRV: 33 };

export function encodeName(value) {
  const segments = value.replace(/\.$/, '').split('.');
  return Buffer.concat([...segments.map(segment => {
    const bytes = Buffer.from(segment, 'utf8');
    if (!bytes.length || bytes.length > 63) throw Error('Invalid DNS-SD label');
    return Buffer.concat([Buffer.from([bytes.length]),bytes]);
  }),Buffer.from([0])]);
}
function header(answerCount) {
  const b=Buffer.alloc(12);
  b.writeUInt16BE(0x8400,2); // authoritative multicast response
  b.writeUInt16BE(answerCount,6);
  return b;
}
function resource(name,type,data,flush=false) {
  const h=Buffer.alloc(10);
  h.writeUInt16BE(type,0);h.writeUInt16BE(flush ? 0x8001 : 1,2);
  h.writeUInt32BE(120,4);h.writeUInt16BE(data.length,8);
  return Buffer.concat([encodeName(name),h,data]);
}
function txt(strings) {
  return Buffer.concat(strings.map(value => {
    const data=Buffer.from(value);
    if(data.length>255)throw Error('TXT record too long');
    return Buffer.concat([Buffer.from([data.length]),data]);
  }));
}
function ipv4(ip) {
  const parts=ip.split('.').map(Number);
  if (parts.length!==4 || parts.some(x=>!Number.isInteger(x)||x<0||x>255)) throw Error('Invalid IPv4');
  return Buffer.from(parts);
}
export function buildAnnouncement({instance='CarSync Relay',host='carsync-relay',port=8080,ip}) {
  if(!Number.isInteger(port)||port<1||port>65535)throw Error('Invalid port');
  const serviceName=SERVICE+'.', instanceName=instance+'.'+serviceName, hostName=host+'.local.';
  const srv=Buffer.alloc(6);srv.writeUInt16BE(port,4);
  const records=[
    resource(serviceName,TYPE.PTR,encodeName(instanceName)),
    resource(instanceName,TYPE.SRV,Buffer.concat([srv,encodeName(hostName)]),true),
    resource(instanceName,TYPE.TXT,txt(['version=1','protocol=http']),true),
    resource(hostName,TYPE.A,ipv4(ip),true)
  ];
  return Buffer.concat([header(records.length),...records]);
}
function readName(data,start) {
  let p=start,loops=0;const labels=[],visited=new Set();
  while(p<data.length&&loops++<40) {
    if(visited.has(p))return '';
    visited.add(p);
    const n=data[p];
    if(!n)break;
    if((n&0xc0)===0xc0) {
      if(p+1>=data.length)return '';
      p=((n&0x3f)<<8)|data[p+1];continue;
    }
    if(n>63 || p+1+n>data.length)return '';
    labels.push(data.subarray(p+1,p+1+n).toString('utf8'));p+=1+n;
  }
  return labels.join('.').toLowerCase();
}
export function queriesCarSync(data) {
  if(data.length<12 || (data.readUInt16BE(2)&0x8000)!==0)return false;
  const count=Math.min(data.readUInt16BE(4),20);
  let p=12;
  for(let i=0;i<count;i++) {
    const start=p;let loops=0;
    while(p<data.length && loops++<80) {
      const n=data[p++];
      if(n===0)break;
      if((n&0xc0)===0xc0){p++;break;}
      if(n>63 || p+n>data.length)return false;
      p+=n;
    }
    if(p+4>data.length)return false;
    const type=data.readUInt16BE(p);p+=4;
    const name=readName(data,start);
    if((name===SERVICE || name==='_services._dns-sd._udp.local') && (type===TYPE.PTR || type===255))return true;
  }
  return false;
}
export function advertiseLanRelay({port,logger=console}={}) {
  const interfaces=Object.values(os.networkInterfaces()).flat().filter(Boolean);
  const addresses=interfaces.filter(x=>x.family==='IPv4' && !x.internal && !x.address.startsWith('169.254.'));
  if(addresses.length===0)return {close() {}};
  const safeHost=os.hostname().replace(/[^a-z0-9-]/gi,'-').slice(0,30).toLowerCase()||'local';
  const announcements=addresses.map(x=>buildAnnouncement({host:'carsync-'+safeHost,instance:'CarSync Relay '+safeHost,ip:x.address,port}));
  const socket=dgram.createSocket({type:'udp4',reuseAddr:true});
  let closed=false,ready=false;
  const send=()=>{if(!ready||closed)return;for(const bytes of announcements)socket.send(bytes,PORT,ADDRESS,()=>{});};
  socket.on('error',e=>logger.warn('LAN discovery unavailable: '+e.message));
  socket.on('message',packet=>{if(queriesCarSync(packet))send();});
  socket.bind(PORT,()=>{
    if(closed)return;
    try {socket.addMembership(ADDRESS);socket.setMulticastTTL(255);ready=true;send();}
    catch(e){logger.warn('LAN multicast failed: '+e.message);}
  });
  const repeat=setInterval(send,30000);repeat.unref();
  return {close(){closed=true;clearInterval(repeat);socket.close();}};
}
