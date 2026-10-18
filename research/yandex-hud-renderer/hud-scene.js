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
// Very hard is Yandex's own dark red (#932100) a notch brighter, by the owner's choice (2026-10-18):
// magenta read as something else than a jam. It is the one deliberately dark class on the glass.
const TRAFFIC={UNKNOWN:'#00C8FF',BLOCKED:'#D0D4DC',FREE:'#00F050',LIGHT:'#FFE600',HARD:'#FF1F3D',VERY_HARD:'#B01E00'};
const PALETTE={black:'#000000',white:'#FFFFFF',road:'#B4B8BF',minor:'#7C8087',
 plate:'#0E3FA3',amber:'#FFB21A',red:'#FF3B30',orange:'#FF7A1A',blue:'#3D8BFF',hint:'#FFD84A'};
// Roads: about 72 % white and 1.35× MapKit's width, driveways and yards about 49 %. On the owner's
// glass (2026-10-14) roads at 42 %/22 % were almost invisible, 80 %/54 % read well; after a week of
// driving (2026-10-18) the owner asked for them about 10 % quieter. The route still leads by colour,
// width and its black gap.
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

/* ---------- road events (v4) ---------- */
// v3's rule stays: each event where Yandex puts it, at most four, none over the cursor. The icons are
// Navigator's own where it has them and read on the glass - the route's speed bump and railway
// crossing, the event plates for accidents, works, dangers, school, patrols and special cameras - drawn
// by resource name inside Navigator (the 'drawable' op; nothing copied), clipped to the plate without
// the pin's tail. A camera is a white disc in a red ring, as Navigator draws it on its guidance map; a
// camera with a limit carries the limit as the real sign has it, black digits on white in a red ring.
// Navigator stands its pin straight above the point, which on its tilted 3D map rises off the road;
// on this flat map that would lie on the road ahead. So an icon stands beside its point - right, as
// signs stand, else left, above or below, wherever the route drawn on screen is not - joined to a dot
// on the road by a short line. The module hands icons to MapKit as placemarks, which turn with the map.
const W=PALETTE.white,R='#FF2A2A',BK=PALETTE.black;
const circle=(x,y,r,style)=>({op:'circle',x,y,r,...style});
const shape=(d,style)=>({op:'shape',d,...style});
const box=(x,y,w,h,r,style)=>({op:'roundRect',x,y,w,h,r,...style});
const camGlyph=(cx,cy,k,color)=>[box(cx-6.4*k,cy-3.8*k,12.8*k,8.6*k,1.6*k,{fill:color}),box(cx-2.6*k,cy-5.8*k,5.2*k,2.6*k,.8*k,{fill:color}),
 circle(cx,cy+.5*k,2.9*k,{fill:W}),circle(cx,cy+.5*k,1.6*k,{fill:color})];
// Icons are centred on 0,0; size is their box. Every icon is one plate about 34 px across, the limit
// sign 38: on the glass a mixed set read as some huge and some guessed at (owner, 2026-10-18, after a
// drive), the speed bump among the guessed.
const ICONS={
 camera:{size:[34,34],ops:[circle(0,0,17.2,{fill:BK}),circle(0,0,16,{fill:W}),circle(0,0,14,{stroke:R,width:3.8}),...camGlyph(0,0,1.42,BK)]},
 // A speed camera with a limit is its limit sign, one plate: the camera beside it doubled every
 // camera on the glass, and where it stands on the route already says the limit is enforced there.
 'camera-limit':{size:[38,38],ops:[circle(0,0,19.2,{fill:BK}),circle(0,0,18,{fill:W}),circle(0,0,15.3,{stroke:R,width:5.4}),
  {op:'text',value:'{limit}',x:0,y:0,size:19,size3:13.5,weight:700,family:'HudSign',color:BK,align:'center'}]},
 closed:{size:[34,34],ops:[circle(0,0,17.2,{fill:BK}),circle(0,0,16,{fill:R}),box(-10,-2.8,20,5.6,1.2,{fill:W})]}};
// Navigator's plates: pins are 60×68 with the plate in 8..52 × 6..50; the route's marks 28×32, plate 2..26 × 0..24.
const plate=(name,w,h,view,clip)=>{const k=w/view[0];
 return {size:[(clip[2])*k,(clip[3])*k],ops:[{op:'drawable',name,x:-(clip[0]+clip[2]/2)*k,y:-(clip[1]+clip[3]/2)*k,w,h:view[1]*k,rotation:0,brightness:1,
  clip:[-(clip[2]/2)*k,-(clip[3]/2)*k,clip[2]*k,clip[3]*k]}]};};
// Scaled so each plate is 34 px: Navigator's pins (plate 46 of 60) and route marks (plate 25 of 28).
// A camera pin carries Navigator's camera badge, a 12-unit circle on the plate's top right corner, so
// its clip takes the badge in whole.
const pin=name=>plate(name,44.35,50.26,[60,68],[7,5,46,46]);
const cameraPin=name=>plate(name,44.35,50.26,[60,68],[7,0,53,51]);
const routeMark=name=>plate(name,38.08,43.52,[28,32],[1.5,0,25,24.5]);
Object.assign(ICONS,{bump:routeMark('mapkit_styling_automotive_route_speed_bump'),railway:routeMark('mapkit_styling_automotive_route_railway_crossing'),
 accident:pin('pin_alerts_accident'),works:pin('pin_alerts_road_works'),danger:pin('pin_alerts_danger'),overtaking:pin('pin_alerts_overtaking'),
 crossroad:pin('pin_alerts_crossroad'),school:pin('pin_alerts_school_ahead'),drawbridge:pin('pin_alerts_drawbridge'),police:pin('pin_alerts_police'),
 'lane-camera':cameraPin('pin_alerts_lane_control'),'crossroad-camera':cameraPin('pin_alerts_cross_road_control'),
 'no-stopping-camera':cameraPin('pin_alerts_no_stopping_control'),'mobile-camera':pin('pin_alerts_mobile_control')});
// Navigator's EventTag → icon. Chat, feedback and "other" carry nothing to act on and stay off the glass.
const EVENT_TAGS={SPEED_CONTROL:'camera',TRAFFIC_CONTROL:'camera',ROAD_MARKING_CONTROL:'camera',MOBILE_CONTROL:'mobile-camera',
 LANE_CONTROL:'lane-camera',CROSS_ROAD_CONTROL:'crossroad-camera',NO_STOPPING_CONTROL:'no-stopping-camera',
 POLICE:'police',POLICE_PATROL:'police',ACCIDENT:'accident',CLOSED:'closed',RECONSTRUCTION:'works',DANGER:'danger',
 PEDESTRIAN_DANGER:'danger',OVERTAKING_DANGER:'overtaking',CROSS_ROAD_DANGER:'crossroad',SCHOOL:'school',DRAWBRIDGE:'drawbridge'};
// One event can carry several tags: the first of these wins.
const EVENT_PRIORITY=['camera','mobile-camera','lane-camera','crossroad-camera','no-stopping-camera','closed','accident','works',
 'police','school','crossroad','overtaking','drawbridge','danger'];
function eventKind(tags){
 const kinds=(tags||[]).map(t=>EVENT_TAGS[t]).filter(Boolean);
 return EVENT_PRIORITY.find(k=>kinds.includes(k))||null;
}
const assetNames=Object.values(ICONS).flatMap(i=>i.ops.filter(o=>o.op==='drawable').map(o=>o.name));
// gap: from the point to the icon's near edge; sides in the order tried.
// One icon a place, two places at most: events each within merge metres of the one before (a place
// spans at most span metres) are one place, shown by its most important event (RANK); only the nearest
// two places are drawn, both at full strength. Clusters of icons were what annoyed on the glass
// (owner, 2026-10-18); the cut alone is enough, a faded second icon is not wanted.
const EVENTS={max:2,merge:50,span:120,edge:4,cursor:[134,120,32,44],cover:.2,gap:7,sides:['right','right-high','left','left-high','above','below'],
 lead:{width:1.6,halo:3.6},dot:[2.6,1.7]};
const RANK=['camera-limit','camera','lane-camera','crossroad-camera','no-stopping-camera','mobile-camera','closed','accident','railway',
 'bump','works','school','crossroad','overtaking','drawbridge','police','danger'];
const overlaps=(a,b,pad=0)=>a[0]<b[0]+b[2]+pad&&b[0]<a[0]+a[2]+pad&&a[1]<b[1]+b[3]+pad&&b[1]<a[1]+a[3]+pad;
const covered=(a,b)=>{const w=Math.min(a[0]+a[2],b[0]+b[2])-Math.max(a[0],b[0]),h=Math.min(a[1]+a[3],b[1]+b[3])-Math.max(a[1],b[1]);
 return w>0&&h>0&&w*h>EVENTS.cover*Math.min(a[2]*a[3],b[2]*b[3]);};
const routeIn=(route,r)=>(route||[]).reduce((n,[x,y])=>n+(x>=r[0]-2&&x<=r[0]+r[2]+2&&y>=r[1]-2&&y<=r[1]+r[3]+2?1:0),0);
function sideBox(x,y,[w,h],side){
 const g=EVENTS.gap;
 // Beside the point the icon rises above it, its lower edge 8 px under the point; '-high' lifts it
 // wholly above the point, which is what clears the cursor for an event just ahead of the car. Near
 // the top edge a side icon slides down into the frame rather than giving up its side.
 const top=t=>Math.max(0,t);
 switch(side){
  case 'right':return [x+g,top(y-h+8),w,h];
  case 'right-high':return [x+g,top(y-h-2),w,h];
  case 'left':return [x-g-w,top(y-h+8),w,h];
  case 'left-high':return [x-g-w,top(y-h-2),w,h];
  case 'above':return [x-w/2,y-g-h,w,h];
  default:return [x-w/2,y+g,w,h];
 }
}
// Events in route order (distance in metres; without one, the order given) become places; each place
// takes the sides in order and the first that is on screen, off the cursor, off the route and not under
// the icon placed before it; failing that, the side with the least route under it.
const iconKind=e=>e.kind==='camera'&&e.limit>0?'camera-limit':e.kind;
function placesOf(events){
 const known=(events||[]).map((e,i)=>({...e,kind:iconKind(e),at:Number.isFinite(e.distance)?e.distance:i*1e6}))
  .filter(e=>ICONS[e.kind]&&Number.isFinite(e.x)&&Number.isFinite(e.y)).sort((a,b)=>a.at-b.at);
 const places=[];
 for(const e of known){
  const last=places[places.length-1];
  if(last&&e.at-last.end<=EVENTS.merge&&e.at-last.at<=EVENTS.span){
   last.end=e.at;if(RANK.indexOf(e.kind)<RANK.indexOf(last.best.kind))last.best=e;continue;
  }
  places.push({at:e.at,end:e.at,best:e});
 }
 return places.map(p=>p.best);
}
function placeEvents(events,blocked,route){
 const placed=[];
 for(const e of placesOf(events)){
  if(placed.length>=EVENTS.max)break;
  const kind=e.kind,icon=ICONS[kind],x=e.x,y=e.y;
  if(x<EVENTS.edge||x>SIZE.width-EVENTS.edge||y<EVENTS.edge||y>SIZE.height-EVENTS.edge)continue;
  let best=null;
  for(const [i,side] of EVENTS.sides.entries()){
   const b=sideBox(x,y,icon.size,side);
   if(b[0]<0||b[1]<0||b[0]+b[2]>SIZE.width||b[1]+b[3]>SIZE.height)continue;
   if(blocked.some(c=>overlaps(b,c,2))||placed.some(p=>covered(b,p.box)))continue;
   const score=routeIn(route,b)*10+i;
   if(!best||score<best.score)best={side,box:b,score};
   if(score===i)break;
  }
  if(best)placed.push({kind,x,y,side:best.side,box:best.box,limit:e.limit});
 }
 return placed;
}
function eventCommands(p){
 const [bx,by,bw,bh]=p.box,cx=bx+bw/2,cy=by+bh/2;
 const right=p.side.startsWith('right'),left=p.side.startsWith('left');
 const ex=right?bx:left?bx+bw:cx,ey=p.side==='above'?by+bh:p.side==='below'?by:Math.min(Math.max(p.y,by+4),by+bh-4);
 const lead=[['M',p.x,p.y],['L',ex,ey]];
 const ops=p.kind==='camera-limit'?ICONS[p.kind].ops.map(o=>o.op==='text'?{...o,value:String(p.limit)}:o):ICONS[p.kind].ops;
 return [shape(lead,{stroke:BK,width:EVENTS.lead.halo}),shape(lead,{stroke:W,width:EVENTS.lead.width}),
  circle(p.x,p.y,EVENTS.dot[0],{fill:BK}),circle(p.x,p.y,EVENTS.dot[1],{fill:W}),{op:'group',x:cx,y:cy,scale:1,ops}];
}
// The finish: a white target ring on the route's end.
const FINISH={ops:[circle(0,0,8.5,{fill:BK}),circle(0,0,5.6,{stroke:W,width:2.4}),circle(0,0,2,{fill:W})]};

/* ---------- lane cells for the HUD's own lane slot (field 7) ---------- */
// The slot shows BYD's own 37×60 pixel artwork: the lane's arrows grey, the one to take cyan. Every
// lane gets a cell, and every cell is BYD's pixels: a lane shift is BYD's shifted-lane picture ('d',
// mirrored for the right), whose grey copy is its cyan one recoloured; a turn from the far lane is the
// plain turn; an arrow Navigator does not know is BYD's empty lane (22); a combination BYD never drew
// drops its U-turns, then its sharpest branch, until BYD has it. A tram lane is BYD's bus with a
// pantograph on the roof and a taxi lane BYD's checker, both laid in BYD's own 1-px grid.
const LANE_ART={cell:[37,60],grey:'#91959C',cyan:'#15C6FF',
 shift:'landfront_hud_d',empty:'landback_hud_22',bus:['landback_hud_15','landfront_hud_15'],
 // [x,y,w,h] in cell pixels, filled in the lane's colour without smoothing.
 pantograph:[[12,13,13,2],[17,15,3,1],[16,16,2,1],[19,16,2,1],[15,17,2,1],[20,17,2,1],[14,18,2,1],[21,18,2,1],[13,19,2,2],[22,19,2,2]],
 checker:[[3,22,4,4],[11,22,4,4],[19,22,4,4],[27,22,4,4],[7,26,4,4],[15,26,4,4],[23,26,4,4],[31,26,4,4],
  [3,30,4,4],[11,30,4,4],[19,30,4,4],[27,30,4,4]],
 // Bits a combination loses, first to last, while BYD has no picture for it.
 dropOrder:[16,8,4,2]};

/* ---------- position and route states ---------- */
// Satellites come and go in a city; Navigator keeps guiding on its extrapolated position. The window
// never empties and never prints a word: a stale position holds the picture as it is, and after 30 s
// the map dims to a third with the icons gone (where they are is a guess by then) while the car stays.
// While Navigator rebuilds the route the old line dims to a third and its icons wait for the new one.
const STATES={dimAfter:30000,mapDim:.35,routeDim:.35};

/* ---------- manoeuvre picture for the HUD's own manoeuvre window (road packet field 8) ---------- */
// White on transparent, 192×192 like the picture Denza Apps sends: the lane glyphs at 6× on a 32×32
// grid, so the manoeuvre window and the lane badge speak one language. The roundabout is drawn
// counter-clockwise from below, exit 1 to the right, 2 ahead, 3 to the left, passed exits as stubs.
const MANOEUVRE={size:192,scale:6};
const MW=PALETTE.white;
const glyphOps=name=>[{op:'group',x:2,y:0,scale:1,ops:[shape(GLYPHS[name].stroke,{stroke:MW,width:GLYPH.stroke}),shape(GLYPHS[name].head,{fill:MW})]}];
function exitAngle(n,i){
 if(!n)return -42;
 if(n<=4)return [0,-90,-180,-225][i-1];
 return -225*(Math.min(i,8)-1)/(Math.min(n,8)-1);
}
function roundabout(n){
 const c=[16,15],at=(a,r)=>[+(c[0]+Math.cos(a*Math.PI/180)*r).toFixed(2),+(c[1]+Math.sin(a*Math.PI/180)*r).toFixed(2)];
 const ops=[circle(c[0],c[1],6,{stroke:MW,width:3.2}),shape([['M',16,30.5],['L',16,21.5]],{stroke:MW,width:GLYPH.stroke})];
 for(let i=1;i<Math.min(n,8);i++){const a=exitAngle(Math.min(n,8),i);ops.push(shape([['M',...at(a,7.6)],['L',...at(a,10.6)]],{stroke:MW,width:1.8}));}
 const a=exitAngle(Math.min(n,8),Math.min(n,8));
 ops.push(shape([['M',...at(a,6)],['L',...at(a,9.4)]],{stroke:MW,width:GLYPH.stroke}),shape(head(at(a,16),a),{fill:MW}));
 return ops;
}
const MANOEUVRES={straight:glyphOps('straightahead'),left:glyphOps('left90'),right:glyphOps('right90'),
 'slight-left':glyphOps('left45'),'slight-right':glyphOps('right45'),'sharp-left':glyphOps('left135'),'sharp-right':glyphOps('right135'),
 'uturn-left':glyphOps('left180'),'uturn-right':glyphOps('right180'),
 finish:[circle(16,16,10,{stroke:MW,width:3.2}),circle(16,16,4,{fill:MW})],
 roundabout:Array.from({length:9},(_,n)=>roundabout(n))};

/* ---------- cursor ---------- */
// Yandex's faceted arrow in red-orange: lit left facet, shaded right, on a black underlay that cuts
// the route under it. Orange sits 35° from the jam red, so the car never reads as a traffic segment.
const cursor=(lit,shade)=>[shape([['M',150,120.5],['L',167.5,161],['L',150,153.6],['L',132.5,161],['Z']],{fill:BK}),
 shape([['M',150,124],['L',150,150],['L',136,157.5],['Z']],{fill:lit}),
 shape([['M',150,124],['L',164,157.5],['L',150,150],['Z']],{fill:shade})];
const CURSOR=cursor('#FF7A33','#E8480E');
const STOCK_CURSOR=cursor('#FFD52E','#E8A90C'); // Navigator's golden notification arrow, for comparison

function cameraZoom(distance,latitude){
 const ahead=Math.max(130,Math.min(600,Number.isFinite(distance)?distance*1.2:350));
 return Math.max(14.6,Math.min(16.7,Math.log2(156543.03392*Math.cos(latitude*Math.PI/180)*118/ahead)));
}
function build(input={}){
 const commands=[];
 if(input.guidanceActive!==true||input.routePresent!==true)return {mode:'off',drawMap:false,commands};
 const stale=Number.isFinite(input.staleMs)?Math.max(0,input.staleMs):0;
 const mode=input.rerouting===true?'rerouting':stale>STATES.dimAfter?'lost':stale>0?'holding':'guidance';
 let placed=[];
 if(mode==='guidance'||mode==='holding'){
  if(input.finish&&input.finish.every(Number.isFinite))commands.push({op:'group',x:input.finish[0],y:input.finish[1],scale:1,ops:FINISH.ops});
  placed=placeEvents(input.events,[EVENTS.cursor],input.route);
  for(const p of placed.slice().reverse())commands.push(...eventCommands(p));
 }
 commands.push(...(input.stockPalette?STOCK_CURSOR:CURSOR));
 return {mode,drawMap:true,mapAlpha:mode==='lost'?STATES.mapDim:1,routeAlpha:mode==='rerouting'?STATES.routeDim:1,commands,events:placed};
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
  else if(c.op==='roundRect'){ctx.beginPath();ctx.roundRect(c.x,c.y,c.w,c.h,c.r);
   if(c.fill){ctx.fillStyle=c.fill;ctx.fill();}if(c.stroke){ctx.lineWidth=c.width;ctx.strokeStyle=c.stroke;ctx.stroke();}}
  else if(c.op==='circle'){ctx.beginPath();ctx.arc(c.x,c.y,c.r,0,Math.PI*2);
   if(c.fill){ctx.fillStyle=c.fill;ctx.fill();}if(c.stroke){ctx.lineWidth=c.width;ctx.strokeStyle=c.stroke;ctx.stroke();}}
  else if(c.op==='shape'){trace(ctx,c.d);if(c.dash)ctx.setLineDash(c.dash);if(c.cap)ctx.lineCap=c.cap;
   if(c.fill){ctx.fillStyle=c.fill;ctx.fill();}if(c.stroke){ctx.lineWidth=c.width;ctx.strokeStyle=c.stroke;ctx.stroke();}}
  else if(c.op==='text'){
   const size=c.size3&&c.value.length>2?c.size3:c.size;
   ctx.font=`${c.weight||500} ${size}px ${c.family||'HudSans'},sans-serif`;ctx.fillStyle=c.color;
   ctx.textAlign='left';ctx.textBaseline='alphabetic';
   if(c.align==='center'){
    // Centred on the ink of the glyphs, not on the font's ascent and descent: those differ from face
    // to face and put a sign's digits off centre (owner, 2026-10-18). HudPainter does the same.
    const m=ctx.measureText(c.value);
    ctx.fillText(c.value,c.x-(m.actualBoundingBoxRight-m.actualBoundingBoxLeft)/2,c.y+(m.actualBoundingBoxAscent-m.actualBoundingBoxDescent)/2);
   }else ctx.fillText(c.value,c.x,c.y);
  }
  else if(c.op==='drawable'){
   if(!assets[c.name])throw new Error('Missing stock drawable: '+c.name);
   if(c.clip){ctx.beginPath();ctx.rect(...c.clip);ctx.clip();}
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
  icons:ICONS,eventTags:EVENT_TAGS,eventPriority:EVENT_PRIORITY,events:EVENTS,states:STATES,finish:FINISH,cursor:CURSOR,laneArt:LANE_ART};
}
const api={SIZE,STOCK,TRAFFIC,JAM_TYPES,PALETTE,ROADS,ROUTE,GLYPH,GLYPHS,LANES,LANE_TEMPLATES,LANE_DIRECTIONS,
 ICONS,LANE_ART,EVENT_TAGS,EVENT_PRIORITY,EVENTS,STATES,FINISH,CURSOR,MANOEUVRE,MANOEUVRES,mapStyle,laneNames,assetNames,
 cameraZoom,validLanes,laneRect,routeHits,laneSide,laneBadge,eventKind,placeEvents,eventCommands,sideBox,
 build,paint,theme};
if(typeof module!=='undefined')module.exports=api;root.HudScene=api;
})(typeof globalThis!=='undefined'?globalThis:this);
