/* Shared 300×180 HUD scene. Stock Yandex color families and drawable names; no copied assets. */
(function(root){
'use strict';
const SIZE={width:300,height:180,focus:[150,143]};
// Reviewed Navigator 29.8.1 res/values/colors.xml: mapkit_styling_automotive_jam_*, balloon_new_blue.
const STOCK={FREE:'#82EA0E',LIGHT:'#FFFF41',HARD:'#FF5413',VERY_HARD:'#932100',UNKNOWN:'#A0A0A0',BLOCKED:'#1A1A1A',blue:'#1666FE'};
// Raise HSL lightness while preserving hue and saturation. Deep congestion remains darker than HARD.
function brighter(hex,amount){
 const rgb=[1,3,5].map(i=>parseInt(hex.slice(i,i+2),16)/255),max=Math.max(...rgb),min=Math.min(...rgb);
 const l=(max+min)/2,d=max-min,s=d===0?0:d/(1-Math.abs(2*l-1));
 let h=0;if(d){h=max===rgb[0]?((rgb[1]-rgb[2])/d)%6:max===rgb[1]?(rgb[2]-rgb[0])/d+2:(rgb[0]-rgb[1])/d+4;h=(h+6)%6;}
 const next=Math.min(.9,l+amount),c=(1-Math.abs(2*next-1))*s,x=c*(1-Math.abs(h%2-1)),m=next-c/2;
 const v=[[c,x,0],[x,c,0],[0,c,x],[0,x,c],[x,0,c],[c,0,x]][Math.floor(h)];
 return '#'+v.map(n=>Math.round((n+m)*255).toString(16).padStart(2,'0')).join('').toUpperCase();
}
const JAM_TYPES=['UNKNOWN','BLOCKED','FREE','LIGHT','HARD','VERY_HARD'];
const TRAFFIC=Object.fromEntries(JAM_TYPES.map(k=>[k,brighter(STOCK[k],k==='VERY_HARD'?.16:k==='UNKNOWN'?.14:k==='BLOCKED'?.18:.07)]));
const PALETTE={black:'#000000',road:'#D5D6DC',minor:'#8D9099',white:'#FFFFFF',blue:brighter(STOCK.blue,.04),hint:'#FFD84A'};
const mapStyle=[{elements:['geometry'],stylers:{color:PALETTE.black}},
 {elements:['label'],stylers:{visibility:'off'}},
 {tags:{any:['road']},elements:['geometry'],stylers:{color:PALETTE.road}},
 {tags:{any:['road']},elements:['geometry.outline'],stylers:{color:PALETTE.black}},
 {tags:{any:['road_minor']},elements:['geometry'],stylers:{color:PALETTE.minor}},
 {tags:{none:['road']},elements:['geometry'],stylers:{visibility:'off'}},
 {tags:{any:['path','crosswalk','road_marking','traffic_light']},stylers:{visibility:'off'}}];
const laneNames={straight:'straightahead',left:'left90',right:'right90','slight-left':'left45','slight-right':'right45',
 'hard-left':'left135','hard-right':'right135','uturn-left':'left180','uturn-right':'right180'};
const assetNames=['notification_arrow',...Object.values(laneNames).map(n=>'context_lane_'+n+'_large')];
function cameraZoom(distance,latitude){
 const ahead=Math.max(130,Math.min(600,Number.isFinite(distance)?distance*1.2:350));
 return Math.max(14.6,Math.min(16.7,Math.log2(156543.03392*Math.cos(latitude*Math.PI/180)*118/ahead)));
}
function validLanes(lanes){return Array.isArray(lanes)&&lanes.length>0&&lanes.length<=6&&lanes.every(l=>
 (!l.kind||l.kind==='PLAIN_LANE')&&Array.isArray(l.directions)&&l.directions.length>0&&l.directions.every(d=>laneNames[d])&&(!l.highlight||l.directions.includes(l.highlight)));}
function build(input={}){
 const commands=[], palette=input.stockPalette?STOCK:PALETTE;
 const text=value=>commands.push({op:'text',value,x:10,y:24,size:16,color:PALETTE.hint});
 if(input.guidanceActive!==true)return {mode:'off',drawMap:false,commands};
 if(input.rerouting===true){text('Маршрут…');return {mode:'rerouting',drawMap:false,commands};}
 if(input.routePresent!==true)return {mode:'off',drawMap:false,commands};
 if(input.locationFresh!==true){text('Нет позиции');return {mode:'waiting-location',drawMap:false,commands};}
 // No maneuver, distance or header rectangle: the map uses all 180 pixels.
 if(validLanes(input.lanes)){
  const cell=Math.min(23,116/input.lanes.length),width=Math.ceil(cell*input.lanes.length+10),x=294-width;
  commands.push({op:'roundRect',x,y:6,w:width,h:36,r:5,color:palette.blue});
  input.lanes.forEach((lane,i)=>{
   const glyph=(direction,alpha)=>commands.push({op:'drawable',name:'context_lane_'+laneNames[direction]+'_large',
    x:x+5+i*cell,y:10,w:cell,h:28,alpha,brightness:1,rotation:0});
   lane.directions.filter(d=>d!==lane.highlight).forEach(d=>glyph(d,.4));
   if(lane.highlight)glyph(lane.highlight,1);
  });
 }
 // Navigator's golden notification cursor, turned north. Asset is loaded from the owner's APK.
 commands.push({op:'path',points:[[150,128],[163,155],[150,150],[137,155],[150,128]],color:'#000000',width:3,fill:true});
 commands.push({op:'drawable',name:'notification_arrow',x:132,y:125,w:36,h:36,alpha:1,brightness:input.stockPalette?1:1.12,rotation:-45});
 return {mode:'guidance',drawMap:true,commands};
}
function paint(ctx,commands,assets={}){
 ctx.lineJoin='round';ctx.lineCap='round';
 for(const c of commands){
  ctx.fillStyle=c.color||'#000';ctx.strokeStyle=c.color||'#000';
  if(c.op==='roundRect'){ctx.beginPath();ctx.roundRect(c.x,c.y,c.w,c.h,c.r);ctx.fill();}
  if(c.op==='text'){ctx.font=`500 ${c.size}px HudSans,sans-serif`;ctx.textBaseline='alphabetic';ctx.fillText(c.value,c.x,c.y);}
  if(c.op==='path'){ctx.beginPath();c.points.forEach((v,i)=>i?ctx.lineTo(...v):ctx.moveTo(...v));if(c.fill){ctx.closePath();ctx.fill();}else{ctx.lineWidth=c.width;ctx.stroke();}}
  if(c.op==='drawable'){
   if(!assets[c.name])throw new Error('Missing stock drawable: '+c.name);
   ctx.save();ctx.globalAlpha=c.alpha;ctx.filter=`brightness(${c.brightness})`;
   ctx.translate(c.x+c.w/2,c.y+c.h/2);ctx.rotate(c.rotation*Math.PI/180);ctx.drawImage(assets[c.name],-c.w/2,-c.h/2,c.w,c.h);ctx.restore();
  }
 }
}
const api={SIZE,STOCK,TRAFFIC,JAM_TYPES,PALETTE,mapStyle,laneNames,assetNames,brighter,cameraZoom,validLanes,build,paint};
if(typeof module!=='undefined')module.exports=api;root.HudScene=api;
})(typeof globalThis!=='undefined'?globalThis:this);
