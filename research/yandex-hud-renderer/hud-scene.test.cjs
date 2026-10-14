'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const hud=require('./hud-scene.js');const fixtures=require('./stand/fixtures.js');
const right=fixtures.find(f=>f.id==='right');
test('route selection stays off; start, cancel, restart',()=>{
 assert.equal(hud.build({...right,guidanceActive:false}).mode,'off');
 assert.equal(hud.build(right).drawMap,true);
 assert.deepEqual(hud.build({...right,guidanceActive:false}).commands,[]);
 assert.equal(hud.build(right).mode,'guidance');
});
test('lost position and rerouting clear roads, cursor and lane glyphs',()=>{
 for(const change of [{locationFresh:false},{rerouting:true}]){
  const frame=hud.build({...right,...change});assert.equal(frame.drawMap,false);assert(frame.commands.every(c=>c.op==='text'));
 }
});
test('no maneuver text, icon or reserved header even if input has a maneuver',()=>{
 const scene=hud.build(right);
 assert(!scene.commands.some(c=>c.op==='text'));
 assert.deepEqual(scene,hud.build({...right,maneuver:{kind:'left',distance:17}}));
 assert(scene.commands.filter(c=>c.op==='roundRect').every(c=>c.x>=168 && c.w<=126 && c.h<=36));
});
test('missing, unsupported or oversized lane signs never produce guessed glyphs',()=>{
 const base=hud.build({...right,lanes:[]}).commands;
 for(const lanes of [[{directions:['unknown'],highlight:'unknown'}],Array(7).fill(right.lanes[0]),[{directions:['left'],highlight:'right'}]])
  assert.deepEqual(hud.build({...right,lanes}).commands,base);
});
test('unknown traffic stays distinct from free; every stock congestion level is preserved',()=>{
 assert.equal(new Set(Object.values(hud.TRAFFIC)).size,6);
 assert.notEqual(hud.TRAFFIC.UNKNOWN,hud.TRAFFIC.FREE);
 for(const type of hud.JAM_TYPES){
  const lightness=hex=>{const v=[1,3,5].map(i=>parseInt(hex.slice(i,i+2),16));return Math.max(...v)+Math.min(...v);};
  assert(lightness(hud.TRAFFIC[type])>lightness(hud.STOCK[type]),type);
 }
});
test('fixture traffic is one classification per route segment and overlay stays in frame',()=>{
 for(const f of fixtures){
  assert.equal(f.traffic.length,Math.max(0,f.route.length-1),f.id);
  for(const c of hud.build(f).commands){
   if(c.op==='drawable')assert(c.x>=0&&c.y>=0&&c.x+c.w<=300&&c.y+c.h<=180,f.id);
  }
 }
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
