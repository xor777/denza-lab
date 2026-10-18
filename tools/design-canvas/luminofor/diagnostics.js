(function(root){
  const L=root.DIAGNOSTIC_LAYOUT;
  function draw(c,f){
    const tabId=f.tab||'overview', selected=f.selected||'body', page=f.page||0, code=f.code||0, detail=!!f.detail, statusDetail=!!f.statusDetail;
    const readable=['body','lbms','lowv'].includes(selected);
    const ecu=selected==='lbms'||selected==='lowv'?'76A':'728';
    if(ecu==='76A') f={...f,...(f.lbms||{records:[],dtcRead:false,dtcTime:'Ещё не прочитаны',availability:0})};
    const availability=f.availability===undefined?255:f.availability;
    const format=f.format||0;
    function describe(r){
      if(format!==0)return {display:r.code,meaning:['Расшифровка этого формата','пока не поддерживается'],subtype:'Подтип не интерпретируется',source:['Сохранён исходный код ECU'],summary:'Значение пока неизвестно'};
      const first=parseInt(r.code.slice(0,2),16),base='PCBU'[first>>6]+((first>>4)&3)+r.code.slice(1,4),suffix=r.code.slice(-2);
      const names={U0121:['Потеря связи с блоком ABS'],U029D:['Потеря связи с датчиком','NOx «A»'],U0229:['Потеря связи с блоком','обогрева руля']};
      return {display:base+'-'+suffix,meaning:names[base]||['Назначение кода BYD','пока не установлено'],subtype:({'00':'Подтип не указан','02':'Общий сбой сигнала','06':'Сбой алгоритма','87':'Нет сообщения'})[suffix]||'Подтип не расшифрован',source:base==='U0229'?['Общий справочник Autel','Применимость к BYD не проверена']:names[base]?['Общее значение SAE','Применимость к BYD не проверена']:['Словарь этого ECU не получен','Тип отказа: общее значение SAE'],failureType:suffix,summary:base==='U0121'?'Связь с ABS · справочно':base==='U029D'?'Связь с NOx A · справочно':base==='U0229'?'Обогрев руля · связь · справочно':'Назначение BYD неизвестно'};
    }
    const tab=L.tabs.find(t=>t.id===tabId), C=L.colors;
    const scale=Math.min(1280/1024,680/608);
    c.fillStyle=C.bg;c.fillRect(0,0,1280,680);
    c.save();c.translate((1280-1024*scale)/2,(680-608*scale)/2);c.scale(scale,scale);
    function rect(x,y,w,h,fill,stroke=null,r=0){c.fillStyle=C[fill];c.beginPath();c.roundRect(x,y,w,h,r);c.fill();if(stroke){c.strokeStyle=C[stroke];c.lineWidth=1;c.stroke();}}
    function text(v,x,y,size=12,tone='ink',max=1000,bold=false){c.font=`${bold?500:400} ${size}px Roboto`;c.fillStyle=C[tone];if(c.measureText(v).width>max){while(v.length&&c.measureText(v+'…').width>max)v=v.slice(0,-1);v+='…';}c.fillText(v,x,y);}
    text('Диагностика автомобиля',20,34,23,'ink',1000,true);
    text(`${f.link}   ·   ${f.profile}   ·   ${f.park}`,20,59,12,'muted',720);
    rect(770,12,174,48,f.running?'panel':'select',f.running?'line':'blue',4);text(f.running?'Чтение…':(readable?`DTC · 0x${ecu}`:'Охват DTC'),791,42,16,f.running?'muted':'ink');
    rect(956,12,48,48,'panel','line',4);text('×',969,45,29);
    L.tabs.forEach((t,i)=>{const active=t.id===tabId;rect(20+i*123,78,123,46,active?'select':'bg');text(t.label,32+i*123,107,14,active?'ink':'muted');if(active)rect(20+i*123,122,123,2,'blue');});
    rect(20,130,700,1,'line');text(tab.title,20,155,13,'muted',700);
    c.save();c.translate(20,140);c.scale(1.06,1);
    tab.shapes.forEach(s=>{const picked=s.part===selected;if(s.kind==='rect')rect(s.x,s.y,s.w,s.h,picked?'select':'part',picked?'blue':'line',s.r||0);else{c.strokeStyle=C[s.style==='wire'?'blue':'line'];c.lineWidth=s.style==='wire'?2:1.2;c.stroke(new Path2D(s.d));}});c.restore();
    tab.nodes.forEach(n=>{const v=f.nodes[n.id]||{value:'—',caption:'Нет данных',tone:'muted'};const x=20+n.x*1.06,y=140+n.y;rect(x,y,159,68,n.id===selected?'select':'panel',n.id===selected?'blue':'line',3);text(L.nodes[n.id].label,x+10,y+17,9,'muted',139);text(v.value,x+10,y+40,18,v.tone,139);text(v.caption,x+10,y+57,10,'muted',139);});
    rect(742,140,262,380,'panel','line',4);text(L.nodes[selected].title,754,164,16,'ink',238,true);
    const node=f.nodes[selected]||{value:'—',tone:'muted',rows:[],notes:[]};
    if((selected==='body'||selected==='lbms')&&detail&&f.records[code]){
      const r=f.records[code],d=describe(r);
      rect(754,174,238,36,'select','blue',3);text('‹  К списку DTC',766,198,13);
      text(d.display,754,241,26,'ink',238,true);
      if(statusDetail){
        text(`Статус ${r.status.toString(16).toUpperCase().padStart(2,'0')} · поддержка ${availability.toString(16).toUpperCase().padStart(2,'0')}`,754,264,11,'muted',238);
        ['Отказ сейчас','Отказ в этом цикле','Ожидает подтверждения','Подтверждённая запись','Не проверен после очистки','Отказ после очистки','Не проверен в этом цикле','Запрошена лампа'].forEach((label,i)=>{
          text(`${i} · ${label}`,754,290+i*21,10,'muted',164);
          text(!(availability&(1<<i))?'Не поддержан':r.status&(1<<i)?'Да':'Нет',919,290+i*21,10,'ink',73);
        });
        text(f.dtcTime,754,454,10,'muted',238);text('Биты независимы · лампа ≠ причина',754,468,10,'muted',238);
      }else{
        text(`Исходный код ${r.code} · формат ${format.toString(16).toUpperCase().padStart(2,'0')}`,754,266,11,'muted',238);
        d.meaning.forEach((v,i)=>text(v,754,300+i*20,14,'ink',238));
        text(`Тип отказа ${d.failureType||'—'} · ${d.subtype}`,754,350,12,'ink',238);
        d.source.forEach((v,i)=>text(v,754,385+i*20,11,'muted',238));
        text(ecu==='728'?`ECU 0x${ecu} · имя не доказано`:'LBMS · низковольтная батарея',754,438,11,'ink',238);text(f.dtcTime,754,458,11,'muted',238);
      }
      rect(754,478,238,32,'select','blue',3);text(statusDetail?'‹  Значение кода':'Все биты статуса  ›',766,499,12);
    }else if((selected==='body'||selected==='lbms')){
      text(f.dtcTime,754,187,11,'muted',238);
      text(f.dtcRead?`Текущих ${availability&1?f.records.filter(r=>r.status&1).length:'—'} · сохранённых ${availability&8?f.records.filter(r=>r.status&8).length:'—'}`:'ECU ещё не опрошен',754,211,12,'ink',238);
      text('Код и значение · нажмите для деталей',754,233,11,'muted',238);
      f.records.slice(page*5,page*5+5).forEach((r,i)=>{let y=241+i*43;const d=describe(r);if(page*5+i===code)rect(750,y,246,43,'select');text(d.display,758,y+16,13,(r.status&129)?'amber':'ink');text((r.status&1)?'Текущий отказ':(r.status&4)?'Ожидает подтверждения':(r.status&8)?'Сохранённый':'Статус проверки',856,y+16,10,(r.status&129)?'amber':'muted',136);text(d.summary,758,y+34,11,'muted',234);});
      if(!f.dtcRead){text(`Запрос 0x${ecu==='76A'?'762':'720'} → 0x${ecu}`,754,280,12,'muted');text('Остальные ECU не опрошены',754,305,12,'muted');}
      if(f.dtcRead&&!f.records.length){text('0 записей по маске 0xFF',754,280,13);text('Только этот ECU, этот снимок',754,305,11,'muted');}
      if(f.dtcRead&&!f.records.length){text(`Ожидают: ${availability&4?'0':'бит не поддержан'}`,754,343,11,'muted');text(`Запрос лампы: ${availability&128?'0':'бит не поддержан'}`,754,366,11,'muted');}
      if(f.records.length>5){if(page>0)text('‹',770,507,23);text(`${page+1} / ${Math.ceil(f.records.length/5)}`,858,504,12,'muted');if((page+1)*5<f.records.length)text('›',962,507,23);}else{
      text(ecu==='728'?'Общий справочник; OEM не получен':'LBMS · низковольтная батарея',754,481,11,'muted',238);if(f.dtcRead)text(`Поддержано ${availability.toString(16).toUpperCase().padStart(2,'0')} · формат ${format.toString(16).toUpperCase().padStart(2,'0')}`,754,503,10,'muted',238);}
    }else{
      text(node.value,754,200,24,node.tone,238);(node.rows||[]).slice(page*5,page*5+5).forEach((r,i)=>{text(r[0],754,236+i*33,11,'muted',125);text(r[1],879,236+i*33,12,'ink',113);});
      (node.notes||[]).slice(0,3).forEach((v,i)=>text(v,754,((node.rows||[]).length>5?412:442)+i*20,11,'muted',238));
      if((node.rows||[]).length>5){if(page>0)text('‹',770,507,23);text(`${page+1} / ${Math.ceil(node.rows.length/5)}`,858,504,12,'muted');if((page+1)*5<node.rows.length)text('›',962,507,23);}
      if(!(node.rows||[]).length&&!(node.notes||[]).length){text('Источник ещё не подключён',754,244,12,'muted');text('Значение неизвестно',754,271,12,'muted');}
    }
    text('Связи на схеме — функциональные',20,513,11,'muted');rect(20,532,204,44,'panel','line',3);text('Обновить показания',33,560,13,f.running?'muted':'ink');text(f.lampTime,242,549,11,'muted',470);text(f.message,242,570,11,'muted',470);
    if((selected==='body'||selected==='lbms')&&f.records[code]){const r=f.records[code];const bit=mask=>!(availability&mask)?'н/д':r.status&mask?'да':'нет';text(`${r.code}  ·  status 0x${r.status.toString(16).toUpperCase().padStart(2,'0')}`,742,543,12);text(`Отказ ${bit(1)} · ожидает ${bit(4)}`,742,560,10,'muted');text(`Подтверждён ${bit(8)} · запрос лампы ${bit(128)}`,742,577,10,'muted');text(`Отказ после очистки: ${bit(32)}`,742,594,10,'muted');}else text('Серый: без оценки исправности',742,551,11,'muted',262);
    text(f.dtcRead?`Полный ответ ECU 0x${ecu} · запрос 0x19 · маска 0xFF · охват автомобиля не установлен.`:'DTC ещё не прочитаны · доступны два адреса · охват автомобиля не установлен.',20,599,11,'muted',700);
    c.restore();
  }
  root.DiagnosticBoard={draw};
})(window);
