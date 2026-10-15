/* Shared 300×180 HUD scene: palette, map style and every overlay template.
 * The browser stand paints it directly; the embedded module receives the same data as
 * assets/denza-hud/theme.json and only positions the templates. No copied Yandex assets. */
(function(root){
'use strict';
const SIZE={width:300,height:180,focus:[150,143]};
// Reviewed Navigator 29.8.1 res/values/colors.xml: mapkit_styling_automotive_jam_*, balloon_new_blue.
const STOCK={FREE:'#82EA0E',LIGHT:'#FFFF41',HARD:'#FF5413',VERY_HARD:'#932100',UNKNOWN:'#A0A0A0',BLOCKED:'#1A1A1A',blue:'#1666FE'};
const JAM_TYPES=['UNKNOWN','BLOCKED','FREE','LIGHT','HARD','VERY_HARD'];
// The projector only adds light: a dark colour is no colour, and over daylit asphalt every colour
// loses about half its saturation (glass model of 2026-10-12, docs/hud-projection-findings.md).
// So every class is a saturated primary with zeroed spare channels: green, yellow and red are 60°+
// apart, very hard is magenta rather than Yandex's burgundy (which the glass shows as a dim red),
// unknown is cyan and never a shade of free.
const TRAFFIC={UNKNOWN:'#00C8FF',BLOCKED:'#D0D4DC',FREE:'#00F050',LIGHT:'#FFE600',HARD:'#FF1F3D',VERY_HARD:'#FF1FC8'};
const PALETTE={black:'#000000',white:'#FFFFFF',road:'#C8CCD4',minor:'#8A8E96',
 plate:'#0E3FA3',amber:'#FFB21A',red:'#FF3B30',orange:'#FF7A1A',blue:'#3D8BFF',hint:'#FFD84A'};
// Roads: about 80 % white and 1.35× MapKit's width, driveways and yards about 54 %. On the owner's
// glass (2026-10-14) roads at 42 %/22 % were almost invisible while the route and cursor read well;
// the route still leads by colour, width and its black gap.
const ROADS={scale:1.35};
const mapStyle=[{elements:['geometry'],stylers:{color:PALETTE.black}},
 {elements:['label'],stylers:{visibility:'off'}},
 {tags:{any:['road']},elements:['geometry'],stylers:{color:PALETTE.road,scale:ROADS.scale}},
 {tags:{any:['road']},elements:['geometry.outline'],stylers:{color:PALETTE.black}},
 {tags:{any:['road_minor','road_unclassified','road_7']},elements:['geometry'],stylers:{color:PALETTE.minor,scale:ROADS.scale}},
 // Painted black, not hidden: hidden land shows MapKit's night clear colour (13,13,13), a faint glowing
 // rectangle on the glass. Black land keeps the empty field optically transparent.
 {tags:{none:['road']},elements:['geometry'],stylers:{color:PALETTE.black}},
 {tags:{any:['path','crosswalk','road_marking','traffic_light']},stylers:{visibility:'off'}}];
// Native MapKit route: wider than any road, black gap around it, soft traffic transitions,
// white Yandex manoeuvre arrows on the line itself.
const ROUTE={width:8,outline:2,gradient:24,turnRadius:12,
 arrow:{length:46,fill:PALETTE.white,outline:PALETTE.black,outlineWidth:1.5,triangleHeight:10,ahead:2}};

/* ---------- lanes ---------- */
// One glyph grid for every direction: 28×32, shared stem at x=14, round 3.6-unit strokes and a
// filled head 8 long × 10 wide. Turns keep the stem, so a lane's several directions overlay cleanly.
const GLYPH={w:28,h:32,stroke:3.6,head:[8,5]};
function head(tip,angle){
 const a=angle*Math.PI/180,[l,w]=GLYPH.head,d=[Math.cos(a),Math.sin(a)],n=[-d[1],d[0]];
 const base=[tip[0]-d[0]*l,tip[1]-d[1]*l];
 return [['M',...tip],['L',+(base[0]+n[0]*w).toFixed(2),+(base[1]+n[1]*w).toFixed(2)],
  ['L',+(base[0]-n[0]*w).toFixed(2),+(base[1]-n[1]*w).toFixed(2)],['Z']];
}
const RIGHT={
 straightahead:{stroke:[['M',14,30],['L',14,10]],head:head([14,1.5],-90)},
 right45:{stroke:[['M',14,30],['L',14,17],['Q',14,12.4,17.3,9.2],['L',19.6,6.9]],head:head([25,1.5],-45)},
 right90:{stroke:[['M',14,30],['L',14,15],['Q',14,10,19,10],['L',20.5,10]],head:head([27.5,10],0)},
 right135:{stroke:[['M',14,30],['L',14,11],['Q',14,5.6,18.4,8.6],['L',20.4,10.4]],head:head([25.6,15.6],45)},
 right180:{stroke:[['M',14,30],['L',14,9],['Q',14,3,19,3],['Q',24,3,24,9],['L',24,14]],head:head([24,21.5],90)},
 rightshift:{stroke:[['M',14,30],['L',14,23],['C',14,18,21,18,21,13],['L',21,10]],head:head([21,1.5],-90)}};
const mirror=cmds=>cmds.map(c=>c.length===1?c:[c[0],...c.slice(1).map((v,i)=>i%2===0?+(GLYPH.w-v).toFixed(2):v)]);
const GLYPHS={straightahead:RIGHT.straightahead};
for(const [name,g] of Object.entries(RIGHT)) if(name!=='straightahead'){
 GLYPHS[name]=g;GLYPHS[name.replace('right','left')]={stroke:mirror(g.stroke),head:mirror(g.head)};
}
// Navigator's LaneDirection → glyph. LEFT_FROM_RIGHT/RIGHT_FROM_LEFT read as the turn itself.
const LANE_DIRECTIONS={STRAIGHT_AHEAD:'straightahead',LEFT45:'left45',RIGHT45:'right45',LEFT90:'left90',RIGHT90:'right90',
 LEFT135:'left135',RIGHT135:'right135',LEFT180:'left180',RIGHT180:'right180',LEFT_SHIFT:'leftshift',RIGHT_SHIFT:'rightshift',
 LEFT_FROM_RIGHT:'left90',RIGHT_FROM_LEFT:'right90'};
// Stand fixtures use short names.
const laneNames={straight:'straightahead',left:'left90',right:'right90','slight-left':'left45','slight-right':'right45',
 'hard-left':'left135','hard-right':'right135','uturn-left':'left180','uturn-right':'right180',
 'shift-left':'leftshift','shift-right':'rightshift'};
const glyphName=d=>laneNames[d]||LANE_DIRECTIONS[d]||(GLYPHS[d]?d:null);
// The badge: navy-dimmed Yandex blue (the glass reads a full blue plate as a bright patch and it
// drowns the arrows), recommended direction white, the rest 38 %; dashed lane markings between.
const LANES={max:6,glyphHeight:26,maxWidth:132,margin:5,pad:[6,5],radius:7,gap:1,dim:.38,reach:260,
 marking:{alpha:.32,width:1.2,dash:3,gap:3,inset:7},reserved:['BUS_LANE','TAXI_LANE'],
 reservedGlyph:{op:'text',value:'А',x:14,y:17,size:21,color:'#FFFFFF',alpha:.38,align:'center'},
 rightward:['right45','right90','right135','right180','rightshift']};
// Every lane count has one fixed layout: glyph scale, cell positions and plate size.
function laneTemplate(n){
 const scaleMax=LANES.glyphHeight/GLYPH.h,cell=GLYPH.w+LANES.gap;
 const scale=Math.min(scaleMax,(LANES.maxWidth-2*LANES.pad[0])/(n*cell-LANES.gap));
 const w=Math.ceil((n*cell-LANES.gap)*scale+2*LANES.pad[0]),h=Math.ceil(GLYPH.h*scale+2*LANES.pad[1]);
 return {n,scale:+scale.toFixed(4),w,h,cells:Array.from({length:n},(_,i)=>+(LANES.pad[0]+i*cell*scale).toFixed(2)),top:LANES.pad[1]};
}
const LANE_TEMPLATES=Array.from({length:LANES.max},(_,i)=>laneTemplate(i+1));
function validLanes(lanes){
 return Array.isArray(lanes)&&lanes.length>0&&lanes.length<=LANES.max&&lanes.every(l=>{
  if(l.kind&&l.kind!=='PLAIN_LANE')return true; // shown as an empty lane: count and position stay true
  return Array.isArray(l.directions)&&l.directions.length>0&&l.directions.every(glyphName)
   &&(!l.highlight||l.directions.includes(l.highlight));
 });
}
// Lanes sit in the top corner the manoeuvre does not turn into. The lane sign may belong to a later
// manoeuvre than the next bend, so the route actually drawn ahead decides when it reaches that corner.
function laneRect(n,side){
 const t=LANE_TEMPLATES[n-1];
 return [side==='left'?LANES.margin:SIZE.width-LANES.margin-t.w,LANES.margin,t.w,t.h];
}
// Route samples inside a badge rectangle (inflated by 3 px): the polyline walked in 4 px steps over
// its first LANES.reach pixels, the stretch the driver reads now. A later return of the route may hide.
function routeHits(route,rect){
 let hits=0,walked=0;
 for(let i=0;i+1<(route||[]).length&&walked<=LANES.reach;i++){
  const [ax,ay]=route[i],[bx,by]=route[i+1],length=Math.hypot(bx-ax,by-ay),steps=Math.max(1,Math.ceil(length/4));
  for(let k=i?1:0;k<=steps&&walked+length*k/steps<=LANES.reach;k++){
   const x=ax+(bx-ax)*k/steps,y=ay+(by-ay)*k/steps;
   if(x>=rect[0]-3&&x<=rect[0]+rect[2]+3&&y>=rect[1]-3&&y<=rect[1]+rect[3]+3)hits++;
  }
  walked+=length;
 }
 return hits;
}
function laneSide(lanes,route){
 const turns=lanes.map(l=>l.highlight&&glyphName(l.highlight)).filter(Boolean);
 const preferred=turns.length&&turns.every(t=>LANES.rightward.includes(t))?'left':'right',other=preferred==='left'?'right':'left';
 const here=routeHits(route,laneRect(lanes.length,preferred));
 return here>0&&routeHits(route,laneRect(lanes.length,other))<here?other:preferred;
}
function laneBadge(lanes,palette,route){
 const t=LANE_TEMPLATES[lanes.length-1],side=laneSide(lanes,route);
 const [x,y]=laneRect(lanes.length,side);
 const ops=[{op:'roundRect',x:0,y:0,w:t.w,h:t.h,r:LANES.radius,fill:palette.plate}];
 const m=LANES.marking,cell=(GLYPH.w+LANES.gap)*t.scale;
 for(let i=1;i<t.n;i++){
  const mx=+(t.cells[i]-LANES.gap*t.scale/2).toFixed(2);
  ops.push({op:'shape',d:[['M',mx,m.inset],['L',mx,t.h-m.inset]],stroke:palette.white,width:m.width,alpha:m.alpha,dash:[m.dash,m.gap]});
 }
 lanes.forEach((lane,i)=>{
  if(lane.kind&&lane.kind!=='PLAIN_LANE'){
   // A bus or taxi lane carries the road marking "А"; tram and bike lanes stay empty.
   if(LANES.reserved.includes(lane.kind))ops.push({op:'group',x:t.cells[i],y:t.top,scale:t.scale,ops:[LANES.reservedGlyph]});
   return;
  }
  const glyphs=[];
  for(const d of lane.directions)if(d!==lane.highlight)glyphs.push([glyphName(d),LANES.dim]);
  if(lane.highlight)glyphs.push([glyphName(lane.highlight),1]);
  const group={op:'group',x:t.cells[i],y:t.top,scale:t.scale,ops:[]};
  for(const [name,alpha] of glyphs){
   const g=GLYPHS[name];
   group.ops.push({op:'shape',d:g.stroke,stroke:palette.white,width:GLYPH.stroke,alpha});
   group.ops.push({op:'shape',d:g.head,fill:palette.white,alpha});
  }
  ops.push(group);
 });
 return {rect:[x,y,t.w,t.h],side,command:{op:'group',x,y,scale:1,ops}};
}

/* ---------- road events ---------- */
// Icons are drawn upright where the event lies on the route, ~20 px, each on a black halo so the
// route and roads under it stop short. Bright line symbols: the glass blurs thin dark cut-outs.
const W=PALETTE.white,A=PALETTE.amber,R=PALETTE.red,BK=PALETTE.black;
const circle=(x,y,r,style)=>({op:'circle',x,y,r,...style});
const shape=(d,style)=>({op:'shape',d,...style});
const triangle=[['M',0,-9.5],['L',10,8],['L',-10,8],['Z']];
const bang=color=>[shape([['M',0,-3.2],['L',0,2.2]],{stroke:color,width:2.4}),circle(0,5.2,1.35,{fill:color})];
const cameraGlyph=dx=>[
 shape([['M',dx-7,-3],['Q',dx-7,-5,dx-5,-5],['L',dx-3.2,-5],['L',dx-2,-7],['L',dx+2,-7],['L',dx+3.2,-5],['L',dx+5,-5],
  ['Q',dx+7,-5,dx+7,-3],['L',dx+7,4],['Q',dx+7,6,dx+5,6],['L',dx-5,6],['Q',dx-7,6,dx-7,4],['Z']],{fill:W}),
 circle(dx,0.6,3.3,{fill:BK}),circle(dx,0.6,1.5,{fill:W})];
const ICONS={
 camera:{box:[-11,-11,22,22],ops:[circle(0,0,11.5,{fill:BK}),...cameraGlyph(0)]},
 // A camera that also enforces a limit: the sign carries the number, the camera sits at its left.
 'camera-limit':{box:[-31,-12,43,24],ops:[{op:'roundRect',x:-31,y:-12.5,w:43.5,h:25,r:12.5,fill:BK},...cameraGlyph(-20),
  circle(0,0,9.6,{stroke:R,width:2.8}),{op:'text',value:'{limit}',x:0,y:0.5,size:10.5,size3:8.2,color:W,align:'center'}]},
 police:{box:[-11,-11,22,22],ops:[circle(0,0,11.5,{fill:BK}),
  shape([['M',-7,5],['L',-7,1],['Q',-7,-6,0,-6],['L',0,5],['Z']],{fill:R}),
  shape([['M',7,5],['L',7,1],['Q',7,-6,0,-6],['L',0,5],['Z']],{fill:PALETTE.blue}),
  shape([['M',-9,7],['L',9,7]],{stroke:W,width:2.6}),
  shape([['M',-9.5,-6.5],['L',-7.5,-5],['M',9.5,-6.5],['L',7.5,-5],['M',0,-10],['L',0,-8.6]],{stroke:W,width:1.6})]},
 // The sign's hump on its road line, as wide as a sign: smaller reads as a dot on the glass.
 bump:{box:[-14,-11,28,20],ops:[{op:'roundRect',x:-14,y:-11,w:28,h:20,r:7,fill:BK},
  shape([['M',-8.5,5],['C',-8.5,-11,8.5,-11,8.5,5],['Z']],{fill:A}),shape([['M',-11.5,5.6],['L',11.5,5.6]],{stroke:A,width:2.8})]},
 railway:{box:[-10,-10,20,20],ops:[circle(0,0,11,{fill:BK}),
  shape([['M',-6.5,-6.5],['L',6.5,6.5],['M',6.5,-6.5],['L',-6.5,6.5]],{stroke:W,width:3.2})]},
 danger:{box:[-12,-12,24,22],ops:[circle(0,0,12.5,{fill:BK}),shape(triangle,{stroke:A,width:2.3}),...bang(W)]},
 accident:{box:[-12,-12,24,22],ops:[circle(0,0,12.5,{fill:BK}),shape(triangle,{stroke:R,width:2.3}),...bang(W)]},
 works:{box:[-12,-8,24,16],ops:[{op:'roundRect',x:-12,y:-8,w:24,h:16,r:4,fill:BK},
  {op:'roundRect',x:-9.5,y:-5,w:19,h:10,r:2.5,fill:PALETTE.orange},
  shape([['M',-5.5,5],['L',-0.5,-5],['M',2,5],['L',7,-5]],{stroke:BK,width:2.6})]},
 closed:{box:[-11,-11,22,22],ops:[circle(0,0,11.5,{fill:BK}),circle(0,0,9,{fill:R}),
  shape([['M',-5,0],['L',5,0]],{stroke:W,width:3.2})]}};
// Navigator EventTag → icon. Chat, feedback and "other" carry nothing to act on and stay off the glass.
const EVENT_TAGS={SPEED_CONTROL:'camera',MOBILE_CONTROL:'camera',LANE_CONTROL:'camera',ROAD_MARKING_CONTROL:'camera',
 CROSS_ROAD_CONTROL:'camera',NO_STOPPING_CONTROL:'camera',TRAFFIC_CONTROL:'camera',POLICE:'police',POLICE_PATROL:'police',
 ACCIDENT:'accident',CLOSED:'closed',RECONSTRUCTION:'works',DANGER:'danger',OVERTAKING_DANGER:'danger',
 PEDESTRIAN_DANGER:'danger',CROSS_ROAD_DANGER:'danger',SCHOOL:'danger',DRAWBRIDGE:'danger'};
const EVENT_PRIORITY=['camera','police','accident','closed','works','danger'];
function eventKind(tags){
 const kinds=(tags||[]).map(t=>EVENT_TAGS[t]).filter(Boolean);
 return EVENT_PRIORITY.find(k=>kinds.includes(k))||null;
}
const EVENTS={max:4,edge:4,cursor:[134,120,32,44]};
const overlaps=(a,b,pad=0)=>a[0]<b[0]+b[2]+pad&&b[0]<a[0]+a[2]+pad&&a[1]<b[1]+b[3]+pad&&b[1]<a[1]+a[3]+pad;
// Nearest first; an icon that would cover the lanes, the cursor or a nearer icon is left out.
function placeEvents(events,blocked){
 const placed=[];
 for(const e of events||[]){
  if(placed.length>=EVENTS.max)break;
  const kind=e.kind==='camera'&&e.limit>0?'camera-limit':e.kind,icon=ICONS[kind];
  if(!icon||!Number.isFinite(e.x)||!Number.isFinite(e.y))continue;
  const x=Math.round(e.x),y=Math.round(e.y),box=[x+icon.box[0],y+icon.box[1],icon.box[2],icon.box[3]];
  if(x<EVENTS.edge||x>SIZE.width-EVENTS.edge||y<EVENTS.edge||y>SIZE.height-EVENTS.edge)continue;
  if(blocked.some(b=>overlaps(box,b,2))||placed.some(p=>overlaps(box,p.box,1)))continue;
  placed.push({kind,x,y,box,limit:e.limit});
 }
 return placed;
}
function eventCommand(p){
 const ops=p.kind==='camera-limit'?ICONS[p.kind].ops.map(o=>o.op==='text'?{...o,value:String(p.limit)}:o):ICONS[p.kind].ops;
 return {op:'group',x:p.x,y:p.y,scale:1,ops};
}
// The finish: a white target ring on the route's end.
const FINISH={ops:[circle(0,0,8.5,{fill:BK}),circle(0,0,5.6,{stroke:W,width:2.4}),circle(0,0,2,{fill:W})]};

/* ---------- manoeuvre picture for the HUD's own manoeuvre window (road packet field 8) ---------- */
// White on transparent, 192×192 like the picture Denza Apps sends: the lane glyphs at 6× on a 32×32
// grid, so the manoeuvre window and the lane badge speak one language. The roundabout is drawn
// counter-clockwise from below, exit 1 to the right, 2 ahead, 3 to the left, passed exits as stubs.
const MANOEUVRE={size:192,scale:6};
const glyphOps=name=>[{op:'group',x:2,y:0,scale:1,ops:[shape(GLYPHS[name].stroke,{stroke:W,width:GLYPH.stroke}),shape(GLYPHS[name].head,{fill:W})]}];
function exitAngle(n,i){
 if(!n)return -42;
 if(n<=4)return [0,-90,-180,-225][i-1];
 return -225*(Math.min(i,8)-1)/(Math.min(n,8)-1);
}
function roundabout(n){
 const c=[16,15],at=(a,r)=>[+(c[0]+Math.cos(a*Math.PI/180)*r).toFixed(2),+(c[1]+Math.sin(a*Math.PI/180)*r).toFixed(2)];
 const ops=[circle(c[0],c[1],6,{stroke:W,width:3.2}),shape([['M',16,30.5],['L',16,21.5]],{stroke:W,width:GLYPH.stroke})];
 for(let i=1;i<Math.min(n,8);i++){const a=exitAngle(Math.min(n,8),i);ops.push(shape([['M',...at(a,7.6)],['L',...at(a,10.6)]],{stroke:W,width:1.8}));}
 const a=exitAngle(Math.min(n,8),Math.min(n,8));
 ops.push(shape([['M',...at(a,6)],['L',...at(a,9.4)]],{stroke:W,width:GLYPH.stroke}),shape(head(at(a,16),a),{fill:W}));
 return ops;
}
const MANOEUVRES={straight:glyphOps('straightahead'),left:glyphOps('left90'),right:glyphOps('right90'),
 'slight-left':glyphOps('left45'),'slight-right':glyphOps('right45'),'sharp-left':glyphOps('left135'),'sharp-right':glyphOps('right135'),
 'uturn-left':glyphOps('left180'),'uturn-right':glyphOps('right180'),
 finish:[circle(16,16,10,{stroke:W,width:3.2}),circle(16,16,4,{fill:W})],
 roundabout:Array.from({length:9},(_,n)=>roundabout(n))};

/* ---------- cursor ---------- */
// Yandex's faceted arrow in red-orange: lit left facet, shaded right, on a black underlay that cuts
// the route under it. Orange sits 35° from the jam red, so the car never reads as a traffic segment.
const cursor=(lit,shade)=>[shape([['M',150,120.5],['L',167.5,161],['L',150,153.6],['L',132.5,161],['Z']],{fill:BK}),
 shape([['M',150,124],['L',150,150],['L',136,157.5],['Z']],{fill:lit}),
 shape([['M',150,124],['L',164,157.5],['L',150,150],['Z']],{fill:shade})];
const CURSOR=cursor('#FF7A33','#E8480E');
const STOCK_CURSOR=cursor('#FFD52E','#E8A90C'); // Navigator's golden notification arrow, for comparison
const assetNames=[];

function cameraZoom(distance,latitude){
 const ahead=Math.max(130,Math.min(600,Number.isFinite(distance)?distance*1.2:350));
 return Math.max(14.6,Math.min(16.7,Math.log2(156543.03392*Math.cos(latitude*Math.PI/180)*118/ahead)));
}
function build(input={}){
 const commands=[];
 const text=value=>commands.push({op:'text',value,x:10,y:24,size:16,color:PALETTE.hint,align:'left'});
 if(input.guidanceActive!==true)return {mode:'off',drawMap:false,commands};
 if(input.rerouting===true){text('Маршрут…');return {mode:'rerouting',drawMap:false,commands};}
 if(input.routePresent!==true)return {mode:'off',drawMap:false,commands};
 if(input.locationFresh!==true){text('Нет позиции');return {mode:'waiting-location',drawMap:false,commands};}
 // No manoeuvre panel, distance or reserved header: the map uses all 180 pixels.
 const blocked=[EVENTS.cursor];
 if(input.finish&&input.finish.every(Number.isFinite))
  commands.push({op:'group',x:Math.round(input.finish[0]),y:Math.round(input.finish[1]),scale:1,ops:FINISH.ops});
 const placed=placeEvents(input.events,blocked);
 for(const p of placed.slice().reverse())commands.push(eventCommand(p));
 commands.push(...(input.stockPalette?STOCK_CURSOR:CURSOR));
 return {mode:'guidance',drawMap:true,commands,lanes:null,events:placed};
}

/* ---------- canvas executor (the module's HudPainter mirrors it op for op) ---------- */
function trace(ctx,d){
 ctx.beginPath();
 for(const c of d){
  if(c[0]==='M')ctx.moveTo(c[1],c[2]);else if(c[0]==='L')ctx.lineTo(c[1],c[2]);
  else if(c[0]==='Q')ctx.quadraticCurveTo(c[1],c[2],c[3],c[4]);else if(c[0]==='C')ctx.bezierCurveTo(c[1],c[2],c[3],c[4],c[5],c[6]);
  else if(c[0]==='Z')ctx.closePath();else throw new Error('Unknown path command '+c[0]);
 }
}
function paint(ctx,commands,assets={}){
 for(const c of commands){
  ctx.save();
  ctx.globalAlpha*=c.alpha==null?1:c.alpha;ctx.lineJoin='round';ctx.lineCap='round';
  if(c.op==='group'){ctx.translate(c.x,c.y);ctx.scale(c.scale,c.scale);paint(ctx,c.ops,assets);}
  else if(c.op==='roundRect'){ctx.beginPath();ctx.roundRect(c.x,c.y,c.w,c.h,c.r);ctx.fillStyle=c.fill;ctx.fill();}
  else if(c.op==='circle'){ctx.beginPath();ctx.arc(c.x,c.y,c.r,0,Math.PI*2);
   if(c.fill){ctx.fillStyle=c.fill;ctx.fill();}if(c.stroke){ctx.lineWidth=c.width;ctx.strokeStyle=c.stroke;ctx.stroke();}}
  else if(c.op==='shape'){trace(ctx,c.d);if(c.dash)ctx.setLineDash(c.dash);
   if(c.fill){ctx.fillStyle=c.fill;ctx.fill();}if(c.stroke){ctx.lineWidth=c.width;ctx.strokeStyle=c.stroke;ctx.stroke();}}
  else if(c.op==='text'){
   const size=c.size3&&c.value.length>2?c.size3:c.size;
   ctx.font=`500 ${size}px HudSans,sans-serif`;ctx.fillStyle=c.color;
   if(c.align==='center'){ctx.textAlign='center';ctx.textBaseline='middle';}else ctx.textBaseline='alphabetic';
   ctx.fillText(c.value,c.x,c.y);
  }
  else if(c.op==='drawable'){
   if(!assets[c.name])throw new Error('Missing stock drawable: '+c.name);
   ctx.filter=`brightness(${c.brightness})`;
   ctx.translate(c.x+c.w/2,c.y+c.h/2);ctx.rotate(c.rotation*Math.PI/180);ctx.drawImage(assets[c.name],-c.w/2,-c.h/2,c.w,c.h);
  }
  else throw new Error('Unknown drawing command: '+c.op);
  ctx.restore();
 }
}
// Everything the module needs, as plain JSON.
function theme(){
 return {size:SIZE,mapStyle,traffic:TRAFFIC,jamTypes:JAM_TYPES,palette:PALETTE,route:ROUTE,
  icons:ICONS,eventTags:EVENT_TAGS,eventPriority:EVENT_PRIORITY,events:EVENTS,finish:FINISH,cursor:CURSOR};
}
const api={SIZE,STOCK,TRAFFIC,JAM_TYPES,PALETTE,ROADS,ROUTE,GLYPH,GLYPHS,LANES,LANE_TEMPLATES,LANE_DIRECTIONS,ICONS,EVENT_TAGS,
 EVENTS,FINISH,CURSOR,MANOEUVRE,MANOEUVRES,mapStyle,laneNames,assetNames,cameraZoom,validLanes,laneRect,routeHits,laneSide,laneBadge,eventKind,placeEvents,
 build,paint,theme};
if(typeof module!=='undefined')module.exports=api;root.HudScene=api;
})(typeof globalThis!=='undefined'?globalThis:this);
