// Exact-build research observer for Navigator 29.8.1, ARM64.
// Expected libmaps-mobile.so SHA-256:
// c2842633cc3db8301f252aff52df86aab4c64a8b10b826f80f4f922a327d340f
// Hook offset is ELF VA (first PT_LOAD vaddr 0); runtime address = module.base + VA.
// The prefix check below validates this function, not the complete loaded image.
// Parent must verify the APK/library identity before attaching. Frida 16.7.19 tested.
// Persist the binary `data` argument of Frida's message callback, then decode with
// python3 -m tools.adas_bridge.yandex_graph_tile. No route or vehicle publication.
// Read-only observer of native GraphTileData decoder input; no native calls/writes.
'use strict';
const m=Process.findModuleByName('libmaps-mobile.so');
if(m===null)throw new Error('libmaps-mobile.so missing');
const hook=m.base.add(0x298bba0);
const hx=b=>Array.from(new Uint8Array(b)).map(x=>('0'+x.toString(16)).slice(-2)).join('');
const expected='ff8307d1fd7b18a9fc6f19a9fa671aa9';
if(hx(hook.readByteArray(16))!==expected)throw new Error('decoder byte mismatch');
let count=0;
Interceptor.attach(hook,{
 onEnter(args){
  if(count>=12)return;
  try{
   const begin=args[0].readPointer(),end=args[0].add(8).readPointer();
   const n=parseInt(end.sub(begin).toString(),16);
   if(!Number.isFinite(n)||n<=0||n>2097152)return;
   count++;
   send({kind:'graph_tile_input',number:count,size:n,decoder:hook.toString(),prefix:hx(begin.readByteArray(Math.min(32,n)))},begin.readByteArray(n));
  }catch(e){send({kind:'graph_tile_read_error',error:String(e)});}
 }
});
send({kind:'graph_tile_observer_start',address:hook.toString(),cap:12,maxBytesPerTile:2097152});
