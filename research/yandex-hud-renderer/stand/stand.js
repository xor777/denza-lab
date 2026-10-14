'use strict';
const stockAssets = {};
const params = new URLSearchParams(location.search);
if(params.has('atlas')) document.documentElement.classList.add('atlas-only');
if(params.has('frame')) document.documentElement.classList.add('frame-only');
function drawFixture(canvas, fixture, activeOverride) {
    const input = Object.assign({},fixture,{stockPalette:document.getElementById('palette').value==='stock'});
    if(typeof activeOverride==='boolean') input.guidanceActive=activeOverride;
    const scene = HudScene.build(input), ctx=canvas.getContext('2d');
    ctx.clearRect(0,0,300,180); ctx.fillStyle='#000';ctx.fillRect(0,0,300,180);
    if(scene.drawMap) {
        // Fixture geometry stands in for the native MapKit layer only on this stand: roads in the map
        // style's colours, the route at ROUTE width with its black gap, MapKit's white manoeuvre arrow.
        // The overlay painted after it is the same command stream the module draws.
        const P=HudScene.PALETTE,R=HudScene.ROUTE,line=(points,color,width)=>({op:'shape',d:points.map((p,i)=>[i?'L':'M',...p]),stroke:color,width});
        HudScene.paint(ctx, fixture.roads.map(r=>line(r.points,P.black,r.minor?4:7)));
        HudScene.paint(ctx, fixture.roads.map(r=>line(r.points,r.minor?P.minor:P.road,r.minor?2.5:5)));
        if(fixture.route.length) {
            HudScene.paint(ctx,[line(fixture.route,P.black,R.width+2*R.outline)]);
            const colors=input.stockPalette?HudScene.STOCK:HudScene.TRAFFIC;
            for(let i=0;i<fixture.route.length-1;i++) HudScene.paint(ctx,[line(fixture.route.slice(i,i+2),colors[fixture.traffic[i]||'UNKNOWN'],R.width)]);
        }
        if(fixture.arrow) {
            const a=fixture.arrow,[x1,y1]=a[a.length-2],[x2,y2]=a[a.length-1],t=Math.atan2(y2-y1,x2-x1),h=R.arrow.triangleHeight;
            const tip=[x2+Math.cos(t)*h,y2+Math.sin(t)*h],n=[-Math.sin(t)*h*.75,Math.cos(t)*h*.75];
            const head=[['M',...tip],['L',x2+n[0],y2+n[1]],['L',x2-n[0],y2-n[1]],['Z']];
            HudScene.paint(ctx,[line(a,P.black,R.width-1+2*R.arrow.outlineWidth),{op:'shape',d:head,fill:P.black,stroke:P.black,width:2*R.arrow.outlineWidth},
                line(a,R.arrow.fill,R.width-1),{op:'shape',d:head,fill:R.arrow.fill}]);
        }
    }
    HudScene.paint(ctx,scene.commands,stockAssets);
    canvas.dataset.mode=scene.mode;
    return scene;
}
window.drawFixture=drawFixture;
(async function(){
    await document.fonts.ready;
    const select=document.getElementById('scene');
    for(const f of HUD_FIXTURES) {const option=document.createElement('option');option.value=f.id;option.textContent=f.title;select.append(option);}
    select.value=HUD_FIXTURES.some(f=>f.id===params.get('scene'))?params.get('scene'):'right';
    let override;
    function update(){
        const f=HUD_FIXTURES.find(f=>f.id===select.value), scene=drawFixture(document.getElementById('frame'),f,override);
        const colors=document.getElementById('palette').value==='stock'?HudScene.STOCK:HudScene.TRAFFIC;
        const legend=document.getElementById('traffic-legend');legend.replaceChildren();
        for(const [key,label] of [['FREE','Свободно'],['LIGHT','Плотно'],['HARD','Затор'],['VERY_HARD','Сильный затор'],['UNKNOWN','Нет данных']]){
            const span=document.createElement('span'), swatch=document.createElement('i');swatch.className='swatch';swatch.style.background=colors[key];span.append(swatch,document.createTextNode(label));legend.append(span);
        }
        document.getElementById('title').textContent=f.title;
        document.getElementById('note').textContent=f.note;
        document.getElementById('state').textContent=scene.mode==='off'?'ВЕДЕНИЕ ВЫКЛЮЧЕНО':'ВЕДЕНИЕ ВКЛЮЧЕНО';
        document.getElementById('toggle').textContent=(override??f.guidanceActive)?'Остановить ведение':'Запустить ведение';
    }
    document.getElementById('palette').onchange=()=>{update();for(const f of HUD_FIXTURES)drawFixture(document.getElementById('case-'+f.id),f);};
    select.onchange=()=>{override=undefined;update();};
    document.getElementById('toggle').onclick=()=>{const f=HUD_FIXTURES.find(f=>f.id===select.value);override=!(override??f.guidanceActive);update();};
    document.getElementById('export').onclick=()=>{const link=document.createElement('a');link.download=`hud-${select.value}.png`;link.href=document.getElementById('frame').toDataURL();link.click();};
    for(const f of HUD_FIXTURES){
        const article=document.createElement('article');article.className='case';
        const canvas=document.createElement('canvas');canvas.width=300;canvas.height=180;canvas.setAttribute('aria-label',f.title);canvas.id=`case-${f.id}`;
        const title=document.createElement('h2');title.textContent=f.title;
        const note=document.createElement('p');note.textContent=f.note;
        article.append(canvas,title,note);document.getElementById('gallery').append(article);drawFixture(canvas,f);
    }
    update();window.HUD_READY=true;
})();
