#!/usr/bin/env node
// Local deterministic stand export and browser acceptance. Requires Playwright + Chrome.
const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const assert = require('node:assert/strict');
const {execFileSync}=require('node:child_process');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '..');
const dir = path.join(root,'research/yandex-hud-renderer');
const out = path.resolve(process.argv[2] || 'captures/yandex-hud-design-20261014');
fs.mkdirSync(out, {recursive:true});
let html=fs.readFileSync(path.join(dir,'stand/index.html'),'utf8');
for(const src of ['../hud-scene.js','fixtures.js','stand.js']) {
    const code=fs.readFileSync(path.resolve(dir,'stand',src),'utf8');
    html=html.replace(`<script src="${src}"></script>`,`<script>${code.replace(/<\/script/gi,'<\\/script')}</script>`);
}
const font=fs.readFileSync(path.join(root,'tools/design-canvas/luminofor/fonts/Roboto-500.ttf')).toString('base64');
html=html.replace('../../../tools/design-canvas/luminofor/fonts/Roboto-500.ttf',`data:font/ttf;base64,${font}`);
const assets=execFileSync('python3',[path.join(root,'tools/yandex_hud_stock_assets.py')],{encoding:'utf8'});
html=html.replace('window.HUD_ASSET_URLS={};',`window.HUD_ASSET_URLS=${assets};`);
const standalone=path.join(out,'stand.html');fs.writeFileSync(standalone,html);
(async()=>{
    const browser=await chromium.launch({executablePath:process.env.DC_CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});
    try {
        const page=await browser.newPage({viewport:{width:1248,height:1000},deviceScaleFactor:1});
        const errors=[];page.on('pageerror',e=>errors.push(String(e)));
        await page.goto(pathToFileURL(standalone).href);
        await page.waitForFunction(()=>window.HUD_READY===true);
        const records=await page.evaluate(()=>HUD_FIXTURES.map(f=>{
            const c=document.getElementById(`case-${f.id}`), ctx=c.getContext('2d');
            const data=ctx.getImageData(0,0,300,180).data;
            let lit=0;const colors=Object.fromEntries(Object.keys(HudScene.TRAFFIC).map(k=>[k,0]));
            const rgb=Object.fromEntries(Object.entries(HudScene.TRAFFIC).map(([k,v])=>[k,[1,3,5].map(i=>parseInt(v.slice(i,i+2),16))]));
            for(let i=0;i<data.length;i+=4){
                if(data[i]||data[i+1]||data[i+2])lit++;
                for(const [k,c] of Object.entries(rgb))if(c.every((v,j)=>Math.abs(data[i+j]-v)<3))colors[k]++;
            }
            return {id:f.id,mode:c.dataset.mode,lit,colors,png:c.toDataURL().split(',')[1]};
        }));
        for(const r of records) {fs.writeFileSync(path.join(out,`${r.id}.png`),Buffer.from(r.png,'base64'));delete r.png;}
        for(const id of ['preview','finished']) assert.equal(records.find(r=>r.id===id).lit,0);
        for(const r of records.filter(r=>r.mode==='guidance')) assert(r.lit>1500,`${r.id} must draw roads and hints`);
        for(const k of ['FREE','LIGHT','HARD','VERY_HARD'])assert(records.find(r=>r.id==='mixed-traffic').colors[k]>20,k+' missing from mixed traffic');
        assert.equal(records.find(r=>r.id==='unknown-traffic').colors.FREE,0);
        assert(await page.evaluate(()=>{const d=document.getElementById('case-right').getContext('2d').getImageData(144,20,1,1).data;return d[0]>100;}),'road must extend into former header');
        await page.selectOption('#scene','right');
        await page.click('#toggle');assert.equal(await page.locator('#frame').getAttribute('data-mode'),'off');
        assert.equal(await page.evaluate(()=>{const d=document.getElementById('frame').getContext('2d').getImageData(0,0,300,180).data;return d.some((v,i)=>i%4!==3&&v>0);}),false);
        await page.click('#toggle');assert.equal(await page.locator('#frame').getAttribute('data-mode'),'guidance');
        const enhanced=await page.locator('#frame').evaluate(c=>c.toDataURL());
        await page.selectOption('#palette','stock');
        const original=await page.locator('#frame').evaluate(c=>c.toDataURL());
        assert.notEqual(original,enhanced,'palette selector must change rendered pixels');
        fs.writeFileSync(path.join(out,'right-stock.png'),Buffer.from(original.split(',')[1],'base64'));
        await page.selectOption('#palette','hud');
        assert.equal(await page.locator('#frame').evaluate(c=>c.toDataURL()),enhanced);
        const downloadPromise=page.waitForEvent('download');await page.click('#export');
        const download=await downloadPromise;await download.saveAs(path.join(out,'export-button.png'));
        assert.deepEqual(fs.readFileSync(path.join(out,'right.png')),fs.readFileSync(path.join(out,'export-button.png')));
        await page.screenshot({path:path.join(out,'stand.png'),fullPage:true});
        await page.goto(pathToFileURL(standalone).href+'?atlas');await page.waitForFunction(()=>window.HUD_READY);
        await page.screenshot({path:path.join(out,'situations.png'),fullPage:true});
        await page.evaluate(()=>{for(const f of HUD_FIXTURES.slice(9))document.getElementById('case-'+f.id).parentElement.remove();});
        await page.screenshot({path:path.join(out,'situations-nine.png'),fullPage:true});
        assert.deepEqual(errors,[]);
        fs.writeFileSync(path.join(out,'report.json'),JSON.stringify({records,checks:['15 deterministic frames','preview and finish all-black','stop clears every pixel','restart restores guidance','export is identical to native-size frame','palette switch changes pixels and restores them','no browser exceptions']},null,2)+'\n');
        console.log(JSON.stringify({out,frames:records.length,errors}));
    } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
