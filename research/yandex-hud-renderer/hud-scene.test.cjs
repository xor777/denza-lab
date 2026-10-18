'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const hud=require('./hud-scene.js');const fixtures=require('./stand/fixtures.js');
const right=fixtures.find(f=>f.id==='right');
const flat=commands=>commands.flatMap(c=>c.op==='group'?[c,...flat(c.ops)]:[c]);
const hex=v=>[1,3,5].map(i=>parseInt(v.slice(i,i+2),16));
test('route selection stays off; start, cancel, restart',()=>{
 assert.equal(hud.build({...right,guidanceActive:false}).mode,'off');
 assert.equal(hud.build(right).drawMap,true);
 assert.deepEqual(hud.build({...right,guidanceActive:false}).commands,[]);
 assert.equal(hud.build(right).mode,'guidance');
});
test('no position, a long gap or a rebuild never blanks the window or prints a word',()=>{
 const events=[{kind:'camera',x:150,y:70,limit:60}];
 const held=hud.build({...right,events,staleMs:10000});
 assert.equal(held.mode,'holding');assert.equal(held.mapAlpha,1);assert.equal(held.events.length,1);
 const lost=hud.build({...right,events,staleMs:40000});
 assert.equal(lost.mode,'lost');assert(lost.drawMap&&lost.mapAlpha<0.5);assert.equal(lost.events.length,0);
 const rebuilt=hud.build({...right,events,rerouting:true});
 assert.equal(rebuilt.mode,'rerouting');assert(rebuilt.drawMap&&rebuilt.routeAlpha<0.5&&rebuilt.mapAlpha===1);assert.equal(rebuilt.events.length,0);
 for(const frame of [held,lost,rebuilt])assert(!flat(frame.commands).some(c=>c.op==='text'&&c.value!=='{limit}'&&!/^\d+$/.test(c.value)));
});
test('no manoeuvre text, icon or reserved header even if input has a manoeuvre',()=>{
 const scene=hud.build(right);
 assert(!flat(scene.commands).some(c=>c.op==='text'));
 assert.deepEqual(scene,hud.build({...right,maneuver:{kind:'left',distance:17}}));
});
test('missing, unsupported or oversized lane signs never produce guessed glyphs',()=>{
 const base=hud.build({...right,lanes:[]}).commands;
 for(const lanes of [[{directions:['unknown'],highlight:'unknown'}],Array(7).fill(right.lanes[0]),[{directions:['left'],highlight:'right'}]])
  assert.deepEqual(hud.build({...right,lanes}).commands,base);
});
test('lane signs belong to the native HUD slot and never occupy the map',()=>{
 const base=hud.build({...right,lanes:[]});
 for(const lanes of [right.lanes,[{directions:['straight'],highlight:'straight'},{kind:'BUS_LANE',directions:[]}]])
  assert.deepEqual(hud.build({...right,lanes}),base);
});
test('route classes are saturated, mutually distinct, bright on the glass but for the dark red very hard jam',()=>{
 const values=Object.values(hud.TRAFFIC);
 assert.equal(new Set(values).size,6);
 assert.notEqual(hud.TRAFFIC.UNKNOWN,hud.TRAFFIC.FREE);
 for(const [type,color] of Object.entries(hud.TRAFFIC))if(type!=='VERY_HARD')assert(Math.max(...hex(color))>=0xD0,type+' must stay bright');
 // Very hard is Yandex's dark red by the owner's choice (2026-10-18): red, dark, and a step under hard.
 const [r,g,b]=hex(hud.TRAFFIC.VERY_HARD);
 assert(r>=0x90&&r<=0xC0&&g<0x40&&b<0x20,'very hard is a dark red');
 assert(hex(hud.TRAFFIC.HARD)[0]-r>=0x40,'very hard stays darker than hard');
 const hue=v=>{const [r,g,b]=hex(v).map(x=>x/255),max=Math.max(r,g,b),d=max-Math.min(r,g,b);
  const h=max===r?((g-b)/d)%6:max===g?(b-r)/d+2:(r-g)/d+4;return (h*60+360)%360;};
 const gap=(a,b)=>{const d=Math.abs(hue(a)-hue(b));return Math.min(d,360-d);};
 const T=hud.TRAFFIC;
 // Magenta also differs from red by a whole blue channel, so 35° is enough for that pair.
 for(const [a,b,min] of [['FREE','LIGHT',60],['LIGHT','HARD',60],['FREE','UNKNOWN',45]])assert(gap(T[a],T[b])>=min,`${a}/${b}`);
});
test('road events: one icon a place, two places, beside the route, never over the cursor, limit on the sign',()=>{
 const route=[];for(let y=20;y<146;y+=3)route.push([150,y]);
 const scene=hud.build({...right,route,events:[
  {kind:'bump',distance:40,x:150,y:112},
  {kind:'no-stopping-camera',distance:120,x:149,y:72},{kind:'camera',distance:130,x:150,y:68,limit:60},{kind:'crossroad-camera',distance:140,x:150,y:64},
  {kind:'danger',distance:400,x:280,y:20},{kind:'chat',distance:500,x:30,y:120}]});
 assert.deepEqual(scene.events.map(e=>e.kind),['bump','camera-limit'],'nearest two places; three cameras at one crossing are one sign');
 const inside=(b,[x,y])=>x>=b[0]-2&&x<=b[0]+b[2]+2&&y>=b[1]-2&&y<=b[1]+b[3]+2;
 for(const e of scene.events){
  const [cx,cy,cw,ch]=hud.EVENTS.cursor;
  assert(!(e.box[0]<cx+cw&&cx<e.box[0]+e.box[2]&&e.box[1]<cy+ch&&cy<e.box[1]+e.box[3]),e.kind+' over the cursor');
  assert(!route.some(p=>inside(e.box,p)),e.kind+' on the route');
 }
 const text=flat(scene.commands).find(c=>c.op==='text');
 assert.equal(text.value,'60');
 for(const icon of Object.values(hud.ICONS))assert(icon.size[0]>=33&&icon.size[0]<=40,'one plate size for every icon');
 assert.equal(hud.eventKind(['SPEED_CONTROL','POLICE']),'camera');
 assert.equal(hud.eventKind(['CHAT','FEEDBACK']),null);
});
test('fixture traffic is one classification per route segment and everything stays in frame',()=>{
 for(const f of fixtures){
  assert.equal(f.traffic.length,Math.max(0,f.route.length-1),f.id);
  const scene=hud.build(f);
  if(scene.lanes){const [x,y,w,h]=scene.lanes.rect;assert(x>=0&&y>=0&&x+w<=300&&y+h<=180,f.id);}
 }
});
test('the module theme carries every template the painter needs',()=>{
 const theme=JSON.parse(JSON.stringify(hud.theme()));
 for(const key of ['mapStyle','traffic','jamTypes','route','icons','eventTags','eventPriority','events','states','finish','cursor','laneArt'])assert(theme[key],key);
 for(const icon of Object.values(theme.icons))assert(icon.size.length===2&&icon.ops.length>0);
 for(const kind of new Set(Object.values(hud.EVENT_TAGS)))assert(theme.icons[kind],kind);
 assert(theme.icons['camera-limit']);
 assert.equal(theme.lanes,undefined);
 assert.equal(theme.manoeuvre,undefined);
});
test('traffic cache cannot carry green across outdated data, route changes or bad segment counts',()=>{
 const adapter=require('./navigator-adapter.js');
 const previous=global.Java;
 class List{constructor(items=[]){this.items=items;}size(){return this.items.length;}get(i){return this.items[i];}add(v){this.items.push(v);}$dispose(){}}
 global.Java={cast:x=>x,use:name=>name==='java.util.ArrayList'?{$new:()=>new List()}:name==='java.lang.Integer'?{valueOf:x=>x}:{}};
 let id='first',outdated=false,types=['FREE','LIGHT','HARD'];
 const route={getRouteId:()=>id,isAreConditionsOutdated:()=>outdated,
  getGeometry:()=>({getPoints:()=>new List([0,1,2,3])}),getJamSegments:()=>new List(types.map(type=>({getJamType:()=>type})))};
 try{
  const cache={};adapter.traffic(route,cache,0);assert.equal(cache.valid,true);assert.deepEqual(cache.types,{FREE:1,LIGHT:1,HARD:1});
  outdated=true;adapter.traffic(route,cache,1);assert.equal(cache.valid,false);assert.deepEqual(cache.types,{UNKNOWN:3});
  outdated=false;id='rerouted';types=['VERY_HARD','VERY_HARD','VERY_HARD'];adapter.traffic(route,cache,2);assert.deepEqual(cache.types,{VERY_HARD:3});
  id='bad-length';types=['FREE'];adapter.traffic(route,cache,3);assert.equal(cache.valid,false);assert.deepEqual(cache.types,{UNKNOWN:3});
 }finally{global.Java=previous;}
});
