/* Designed situations, not recorded Yandex roads or live traffic. Coordinates are HUD pixels.
 * route starts at the cursor tip; arrow is the white manoeuvre arrow MapKit draws on the line;
 * events are road events already projected onto the route, nearest first. */
(function (root) {
    const road = (points, minor = false) => ({points, minor});
    const lane = (directions, highlight = null, kind) => ({directions, highlight, kind});
    const straightRoads = [road([[143,185],[143,90],[170,58],[183,45]]),
        road([[157,185],[157,95],[184,62],[198,47]]),
        road([[2,108],[140,108],[193,100],[301,100]]),
        road([[64,180],[67,111],[74,60]],true), road([[228,180],[231,105],[235,58]],true)];
    const crossRoads = [road([[144,182],[144,61]]),road([[156,182],[156,61]]),
        road([[-5,87],[305,87]]),road([[-5,99],[305,99]]),
        road([[48,181],[48,105]],true),road([[253,105],[253,181]],true)];
    const ring = [];
    for(let i=0;i<=48;i++) {const t=i/48*Math.PI*2;ring.push([150+Math.cos(t)*32,100+Math.sin(t)*29]);}
    const common = {guidanceActive:true, routePresent:true, locationFresh:true};
    const cases = [
        {id:'straight',title:'Прямо',note:'Свободно · камера 60 впереди',roads:straightRoads,
            route:[[150,143],[150,94],[177,61],[192,43]],
            maneuver:{kind:'straight',distance:900}, lanes:[lane(['left']),lane(['straight'],'straight'),lane(['straight','right'],'straight')],
            events:[{kind:'camera',x:150,y:96,limit:60}]},
        {id:'right',title:'Поворот направо',note:'Плотно · правая полоса · полосы уходят влево',roads:crossRoads,
            route:[[150,143],[150,112],[151,102],[160,93],[305,93]],arrow:[[150,118],[150,104],[158,94],[190,93]],
            maneuver:{kind:'right',distance:200},lanes:[lane(['left']),lane(['straight']),lane(['straight','right'],'right')]},
        {id:'left',title:'Поворот в заторе',note:'Затор · ДТП на повороте · левые полосы',roads:crossRoads,
            route:[[150,143],[150,111],[148,102],[139,93],[-5,93]],arrow:[[150,118],[150,104],[142,94],[110,93]],
            maneuver:{kind:'left',distance:80},lanes:[lane(['left'],'left'),lane(['left','straight'],'left'),lane(['straight'])],
            events:[{kind:'accident',x:60,y:93}]},
        {id:'fork',title:'Съезд с магистрали',note:'Свободный съезд · правая полоса',
            roads:[road([[135,183],[135,120],[114,50]]),road([[151,183],[151,116],[130,50]]),
                road([[150,129],[176,107],[209,79],[267,56]]),road([[162,140],[188,120],[221,91],[281,69]])],
            route:[[150,143],[166,123],[207,87],[280,57]],maneuver:{kind:'fork-right',distance:350},
            lanes:[lane(['straight']),lane(['straight']),lane(['straight']),lane(['slight-right'],'slight-right')]},
        {id:'roundabout',title:'Кольцо',note:'Маршрут по кольцу · стрелка съезда',
            roads:[road(ring),road([[150,180],[150,130]]),road([[150,70],[150,50]]),road([[0,100],[117,100]]),road([[182,100],[300,100]])],
            route:[[150,143],[150,129],[165,125],[177,115],[182,100],[177,84],[163,73],[150,71],[135,75],[123,87],[118,100],[0,100]],
            arrow:[[150,71],[135,75],[123,87],[118,100],[96,100]],maneuver:{kind:'roundabout',distance:120,exit:3},lanes:[]},
        {id:'uturn',title:'Разворот',note:'Разворот по левой полосе',
            roads:[road([[135,180],[135,78],[140,64],[150,59],[160,64],[165,78],[165,180]]),road([[149,180],[149,78],[151,78],[151,180]]),road([[167,117],[300,117]],true)],
            route:[[150,143],[157,124],[158,83],[155,72],[145,72],[141,82],[141,162]],arrow:[[158,92],[158,83],[155,72],[145,72],[141,82],[141,100]],
            maneuver:{kind:'uturn-left',distance:60},lanes:[lane(['uturn-left','left'],'uturn-left'),lane(['straight']),lane(['straight','right'])]},
        {id:'arrival',title:'Прибытие',note:'Конец маршрута · белая мишень',roads:straightRoads,
            route:[[150,143],[150,95],[170,70]],maneuver:{kind:'finish',distance:40},lanes:[], finish:[170,70]},
        {id:'six-lanes',title:'Шесть полос',note:'Шесть полос · две правые ведут по маршруту',roads:crossRoads,
            route:[[150,143],[150,109],[164,93],[305,93]],arrow:[[150,118],[150,107],[162,94],[192,93]],maneuver:{kind:'right',distance:500},
            lanes:[lane(['left']),lane(['straight']),lane(['straight']),lane(['straight']),lane(['right'],'right'),lane(['right'],'right')]},
        {id:'mixed-traffic',title:'Пробки по маршруту',note:'Свободно → плотно → затор → сильный затор',roads:straightRoads,
            route:[[150,143],[150,122],[150,97],[164,78],[180,58],[195,25],[202,-10]],
            traffic:['FREE','FREE','LIGHT','HARD','VERY_HARD','VERY_HARD'],lanes:[lane(['straight'],'straight'),lane(['right'])]},
        {id:'heavy-traffic',title:'Сильный затор',note:'Сильный затор · ремонт на маршруте',roads:crossRoads,
            route:[[150,143],[150,110],[140,93],[80,93],[-10,93]],traffic:['VERY_HARD','VERY_HARD','VERY_HARD','VERY_HARD'],
            lanes:[lane(['left'],'left'),lane(['straight'])],events:[{kind:'works',x:100,y:93}]},
        {id:'unknown-traffic',title:'Нет данных о пробках',note:'Голубой маршрут · не выдаём за свободный',roads:straightRoads,
            route:[[150,143],[150,94],[177,61],[192,43]],traffic:['UNKNOWN','UNKNOWN','UNKNOWN'],lanes:[]},
        {id:'speed-bumps',title:'Лежачие полицейские',note:'Двор · два лежачих на маршруте',roads:crossRoads,
            route:[[150,143],[150,61]],traffic:['FREE'],lanes:[],
            events:[{kind:'bump',x:150,y:100},{kind:'bump',x:150,y:56}]},
        {id:'enforcement',title:'Контроль',note:'Камера 40 · пост ДПС · полоса для автобусов',roads:straightRoads,
            route:[[150,143],[150,94],[177,61],[192,43]],traffic:['LIGHT','LIGHT','LIGHT'],
            lanes:[lane(['straight'],'straight'),lane(['straight'],'straight'),lane([],null,'BUS_LANE')],
            events:[{kind:'camera',x:150,y:94,limit:40},{kind:'police',x:177,y:61}]},
        {id:'railway',title:'Переезд и опасность',note:'Ж/д переезд · опасный участок',roads:crossRoads,
            route:[[150,143],[150,61]],traffic:['FREE'],lanes:[],
            events:[{kind:'railway',x:150,y:105},{kind:'danger',x:150,y:68}]},
        {id:'rerouting',title:'Перестроение',note:'Старые маршрут и полосы убраны',rerouting:true,roads:[],route:[]},
        {id:'position-lost',title:'Позиция потеряна',note:'Без замороженной карты и старого манёвра',locationFresh:false,roads:straightRoads,route:[],maneuver:{kind:'right',distance:80}},
        {id:'preview',title:'Маршрут выбран',note:'Ведение ещё не запущено · проекция выключена',guidanceActive:false,roads:straightRoads,route:[[150,143],[150,50]],maneuver:{kind:'straight',distance:500}},
        {id:'finished',title:'Маршрут завершён',note:'Проекция очищена целиком',guidanceActive:false,routePresent:false,roads:[],route:[]}
    ].map(c => {
        const f=Object.assign({},common,c);
        if(!f.traffic) f.traffic=Array(Math.max(0,f.route.length-1)).fill(f.id==='right'?'LIGHT':f.id==='left'?'HARD':'FREE');
        // Roads continue beyond the top edge; only the lane badge overlays them.
        f.roads=f.roads.map(r=>({...r,points:r.points.map(p=>p.slice())}));
        for(const r of f.roads){
            const p=r.points[r.points.length-1],q=r.points[r.points.length-2];
            if(p[1]<=62 && p[1]<q[1]){const t=(-12-p[1])/(p[1]-q[1]);r.points.push([p[0]+(p[0]-q[0])*t,-12]);}
        }
        return f;
    });
    if(typeof module!=='undefined') module.exports=cases;
    root.HUD_FIXTURES=cases;
})(typeof globalThis!=='undefined'?globalThis:this);
