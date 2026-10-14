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
test('lost position and rerouting clear roads, cursor, lanes and road events',()=>{
 for(const change of [{locationFresh:false},{rerouting:true}]){
  const frame=hud.build({...right,...change});assert.equal(frame.drawMap,false);assert(frame.commands.every(c=>c.op==='text'));
 }
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
test('a bus lane keeps its place with the road marking instead of hiding the whole sign',()=>{
 const badgeOf=lanes=>hud.build({...right,lanes}).commands.find(c=>c.op==='group'&&c.ops.some(o=>o.op==='roundRect'));
 const cells=badgeOf([{directions:['straight'],highlight:'straight'},{kind:'BUS_LANE',directions:[]}]).ops.filter(o=>o.op==='group');
 assert.equal(cells.length,2);
 assert.deepEqual(cells[1].ops.map(o=>o.op==='text'&&o.value),['А']);
 assert.equal(badgeOf([{directions:['straight'],highlight:'straight'},{kind:'TRAM_LANE',directions:[]}]).ops.filter(o=>o.op==='group').length,1);
});
test('every lane template fits the frame top and keeps glyph proportions',()=>{
 for(const t of hud.LANE_TEMPLATES){
  assert(t.w<=hud.LANES.maxWidth&&t.h<=40,`${t.n} lanes`);
  assert(t.scale>0.6,`${t.n} lanes are not squashed`);
 }
});
test('the badge leaves the corner the route turns into, and the corner the route actually reaches',()=>{
 const L=(d,h)=>({directions:d,highlight:h});
 assert.equal(hud.laneSide([L(['right'],'right'),L(['straight'])]),'left');
 assert.equal(hud.laneSide([L(['left'],'left'),L(['straight'])]),'right');
 // A left-lane sign for a later manoeuvre while the route first bends right into the right corner.
 assert.equal(hud.laneSide([L(['left'],'left'),L(['straight'])],[[150,143],[150,60],[290,20]]),'left');
 assert.equal(hud.laneSide([L(['left'],'left'),L(['straight'])],[[150,143],[150,60],[0,60]]),'right');
});
test('route classes are saturated, mutually distinct and never dark on the additive glass',()=>{
 const values=Object.values(hud.TRAFFIC);
 assert.equal(new Set(values).size,6);
 assert.notEqual(hud.TRAFFIC.UNKNOWN,hud.TRAFFIC.FREE);
 for(const [type,color] of Object.entries(hud.TRAFFIC))assert(Math.max(...hex(color))>=0xD0,type+' must stay bright');
 const hue=v=>{const [r,g,b]=hex(v).map(x=>x/255),max=Math.max(r,g,b),d=max-Math.min(r,g,b);
  const h=max===r?((g-b)/d)%6:max===g?(b-r)/d+2:(r-g)/d+4;return (h*60+360)%360;};
 const gap=(a,b)=>{const d=Math.abs(hue(a)-hue(b));return Math.min(d,360-d);};
 const T=hud.TRAFFIC;
 // Magenta also differs from red by a whole blue channel, so 35° is enough for that pair.
 for(const [a,b,min] of [['FREE','LIGHT',60],['LIGHT','HARD',60],['HARD','VERY_HARD',35],['FREE','UNKNOWN',45]])assert(gap(T[a],T[b])>=min,`${a}/${b}`);
});
test('road events: nearest first, never over lanes, cursor or each other, limit shown on the sign',()=>{
 const lanes=[{directions:['straight'],highlight:'straight'}];
 const scene=hud.build({...right,lanes,events:[
  {kind:'camera',x:150,y:70,limit:60},{kind:'bump',x:150,y:74},{kind:'police',x:150,y:140},
  {kind:'danger',x:280,y:20},{kind:'railway',x:60,y:60},{kind:'chat',x:30,y:120},{kind:'works',x:2,y:90}]});
 assert.deepEqual(scene.events.map(e=>e.kind),['camera-limit','railway']);
 const text=flat(scene.commands).find(c=>c.op==='text');
 assert.equal(text.value,'60');
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
 for(const key of ['mapStyle','traffic','jamTypes','route','lanes','icons','eventTags','eventPriority','events','finish','cursor'])assert(theme[key],key);
 for(const name of Object.values(hud.LANE_DIRECTIONS))assert(theme.lanes.glyphs[name],name);
 for(const kind of new Set(Object.values(hud.EVENT_TAGS)))assert(theme.icons[kind],kind);
 assert(theme.icons['camera-limit']);
 // Every picture NavigatorSource.manoeuvre can name, roundabout exits 0..8.
 for(const name of ['straight','left','right','slight-left','slight-right','sharp-left','sharp-right','uturn-left','uturn-right','finish'])
  assert(theme.manoeuvre.icons[name],name);
 assert.equal(theme.manoeuvre.icons.roundabout.length,9);
 assert.equal(theme.manoeuvre.size,32*theme.manoeuvre.scale);
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
