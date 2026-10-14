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
        // Fixture geometry substitutes for the native MapKit layer only on this stand.
        // The navigation overlay below is the same command stream used on Android.
        HudScene.paint(ctx, fixture.roads.map(r=>({op:'path',points:r.points,
            color:r.minor?HudScene.PALETTE.minor:HudScene.PALETTE.road,width:r.minor?1.8:2.7})));
        if(fixture.route.length) {
            HudScene.paint(ctx,[{op:'path',points:fixture.route,color:'#000',width:9}]);
            const colors=input.stockPalette?HudScene.STOCK:HudScene.TRAFFIC;
            for(let i=0;i<fixture.route.length-1;i++) HudScene.paint(ctx,[{op:'path',points:fixture.route.slice(i,i+2),color:colors[fixture.traffic[i]||'UNKNOWN'],width:5}]);
        }
        if(fixture.destination) {
            const [x,y]=fixture.destination;
            ctx.fillStyle='#000';ctx.beginPath();ctx.arc(x,y,7,0,Math.PI*2);ctx.fill();
            ctx.strokeStyle=HudScene.PALETTE.hint;ctx.lineWidth=3;ctx.beginPath();ctx.arc(x,y,4,0,Math.PI*2);ctx.stroke();
        }
    }
    HudScene.paint(ctx,scene.commands,stockAssets);
    canvas.dataset.mode=scene.mode;
    return scene;
}
window.drawFixture=drawFixture;
(async function(){
    await document.fonts.ready;
    for(const name of HudScene.assetNames){
        if(!window.HUD_ASSET_URLS[name])throw new Error('Generate the stand with tools/yandex_hud_stand_capture.cjs to load stock resources');
        const img=new Image();img.src=window.HUD_ASSET_URLS[name];await img.decode();stockAssets[name]=img;
    }
    const select=document.getElementById('scene');
    for(const f of HUD_FIXTURES) {const option=document.createElement('option');option.value=f.id;option.textContent=f.title;select.append(option);}
    select.value=HUD_FIXTURES.some(f=>f.id===params.get('scene'))?params.get('scene'):'right';
    let override;
    function update(){
        const f=HUD_FIXTURES.find(f=>f.id===select.value), scene=drawFixture(document.getElementById('frame'),f,override);
        const colors=document.getElementById('palette').value==='stock'?HudScene.STOCK:HudScene.TRAFFIC;
        const legend=document.getElementById('traffic-legend');legend.replaceChildren();
        for(const [key,label] of [['FREE','Свободно'],['LIGHT','Плотно'],['HARD','Затор'],['VERY_HARD','Сильный затор']]){
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
