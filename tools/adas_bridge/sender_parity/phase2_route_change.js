/* Controlled ORIGINAL receipts using two actual SDK route results. No packet/state oracle. */
const P2_ROUTE_CHANGE_STEPS=[{"name":"retain-first-original-route","trigger":"first actual native route/progress and completed G"},{"name":"observe-second-original-G","trigger":"different actual request/path and completed G plus decoded selection ACK"},{"name":"deliver-old-native-progress","trigger":"retained SDK path and original progress input remain valid"},{"name":"observe-original-K","trigger":"actual SOMEIP check-path timer processing exit"},{"name":"select-retained-old-path","trigger":"original K completed"},{"name":"observe-original-C-old","trigger":"actual changed-path processing exit for retained old ID"},{"name":"restore-current-native-path","trigger":"old changed-path processing completed"},{"name":"observe-original-C-current","trigger":"actual changed-path processing exit for current native ID"},{"name":"deliver-stale-native-selection-ack","trigger":"current path restored; retained other actual path used as stale selection"},{"name":"observe-original-I-after-stale-ack","trigger":"actual current-path resend processing exit"},{"name":"deliver-native-selection-error","trigger":"stale-selection resend completed"},{"name":"observe-original-I-after-error","trigger":"actual current-path error resend processing exit"}];
const p2RouteChangeEntries=new Map(),p2RouteChangeCalls=[];
const p2RouteChangePrevious=typeof p2ObservedCall==='function'?p2ObservedCall:null;
function p2RouteChangeCompact(value){
  if(!value||typeof value!=='object')return value;
  if(value.id!==undefined)return {type:'object',id:value.id,class:value.class,native_pointer:value.native_pointer===undefined?null:value.native_pointer};
  return JSON.parse(JSON.stringify(value));
}
// This observer runs under the recorder guard. Plain JS only: no Java/SDK calls.
function p2RouteChangeObserve(row){
  if(p2RouteChangePrevious)p2RouteChangePrevious(row);
  const base=String(row.method||'').split('(')[0];
  if(!['k.h.e0.c.g.G','k.h.e0.c.g.K','k.h.e0.c.g.C','k.h.e0.c.g.I','k.h.e0.c.g.g','k.h.e0.c.g.w','k.h.e0.c.g.l','k.h.e0.c.g.onChangeNaviPath'].includes(base))return;
  if(row.kind==='call_enter'){
    p2RouteChangeEntries.set(row.call_id,{method:base,source_seq:row.seq,call_id:row.call_id,drive_ref:row.receiver,
      args:(row.args||[]).map(p2RouteChangeCompact),navi_path_id:base==='k.h.e0.c.g.l'&&row.args[0].fields&&row.args[0].fields.pathID?row.args[0].fields.pathID.decimal:null});
  }else{
    const entry=p2RouteChangeEntries.get(row.call_id);if(!entry)throw new Error('route-change observed exit without entry');
    p2RouteChangeEntries.delete(row.call_id);
    p2RouteChangeCalls.push({entry,method:base,source_seq:row.seq,call_id:row.call_id,drive_ref:entry.drive_ref,
      args:(row.args_after||[]).map(p2RouteChangeCompact),error:row.error});
    // The retained first route is selected before this bounded recent-call window rolls.
    while(p2RouteChangeCalls.length>512)p2RouteChangeCalls.shift();
  }
}
var p2ObservedCall=p2RouteChangeObserve;
function p2AttachRouteChange(config){
  config=config||{};
  const seed=config.seed||config.route,waitMs=config.waitMs===undefined?360000:Number(config.waitMs);
  if(typeof seed!=='function'||!Number.isFinite(waitMs)||waitMs<10000||waitMs>360000)
    throw new Error('bounded actual route/progress providers required');
  const note=(kind,fields)=>report(kind,Object.assign({case:'route-change'},fields||{}));
  const held=[],deadline=Date.now()+waitMs;let stopped=false,busy=false,timer=null,stage=0,old=null,current=null,drive=null,mmt=null;
  let oldProgress=null,oldChange=null,currentChange=null,staleAck=null,selectionError=null,afterSeq=-1,stageDeadline=deadline,waits=0,lastWaitAt=-Infinity;
  const same=(a,b)=>a&&b&&a.id===b.id;
  const value=v=>v&&v.decimal;
  const samePath=(a,b)=>same(a,b)||(a&&b&&a.class===b.class&&a.native_pointer!==null&&a.native_pointer!==undefined&&a.native_pointer===b.native_pointer);
  const find=(method,predicate,after)=>p2RouteChangeCalls.slice().reverse().find(row=>row.method==='k.h.e0.c.g.'+method&&row.source_seq>(after===undefined?-1:after)&&row.error===null&&predicate(row));
  const evidence=row=>({source_seq:row.source_seq,call_id:row.call_id,drive_ref:row.drive_ref});
  const context=state=>({route_ref:state.route_ref,path_ref:state.path_ref,source_path_ref:state.source_path_ref,navi_ref:state.navi_ref,
    path_id:state.path_id,request_id:state.request_id,input_evidence:state.input_evidence,...(state.sdk_copy?{sdk_copy:state.sdk_copy}:{})});
  function step(name,row,extra){note('native_callback_fixture_step',Object.assign({name,status:'delivered',
    ...(old?{old:context(old)}:{}),...(current?{current:context(current)}:{}),evidence:evidence(row)},extra||{}));}
  function fail(error){if(stopped)return;stopped=true;if(timer!==null)clearTimeout(timer);
    note('native_callback_fixture_error',{stage:P2_ROUTE_CHANGE_STEPS[Math.min(stage,P2_ROUTE_CHANGE_STEPS.length-1)].name,error:String(error),stack:error.stack||null});}
  function waiting(reason,details){
    const at=Date.now();if(waits>=12||at-lastWaitAt<5000)return;
    lastWaitAt=at;note('native_callback_fixture_wait',Object.assign({stage:P2_ROUTE_CHANGE_STEPS[stage].name,
      reason,wait_seq:++waits,at_ms:at,...(drive?{drive_ref:p2Ref(drive)}:{})},details||{}));
  }
  function originalInput(ref){
    // The recorder's Java IdentityHashMap already strongly retains observed
    // original input objects. A synchronized key-array snapshot avoids iterator
    // invalidation while other original threads add identities. Never rebuild
    // an input from its serialized fields or from a sender output.
    const keys=p2J.identities.keySet().toArray();
    for(const key of keys)if(String(p2J.identities.get(key))===String(ref.id)){
      const actual=Java.cast(key,Java.use(ref.class));
      if(!same(p2Ref(actual),ref))throw new Error('original input registry identity differs');
      return actual;
    }
    throw new Error('observed original SDK input missing from identity registry: '+ref.id);
  }
  function retain(value){const result=Java.retain(value);held.push(result);return result;}
  function resolveDrive(){
    if(config.drive)return typeof config.drive==='function'?config.drive():config.drive;
    const owner=Java.use('k.h.e0.b.d').e();
    for(const field of owner.getClass().getDeclaredFields())if(String(field.getType().getName())==='k.h.e0.c.g'){
      field.setAccessible(true);const actual=field.get(owner);if(actual!==null)return Java.cast(actual,Java.use('k.h.e0.c.g'));
    }
    return null;
  }
  function candidate(requireG=true){
    const route=seed();if(!route){waiting('native route provider not ready');return null;}
    const path=config.path?config.path():route.getCurrentFocusPath();if(!path||!path.isValid()){waiting('native focus path not valid');return null;}
    const pathId=String(path.getPathID()),routeRef=p2Ref(route),sourceRef=p2Ref(path),request=String(route.getRequestId());
    const details={source_path_ref:sourceRef,path_id:pathId,route_ref:routeRef};
    if(requireG&&old&&(pathId===old.path_id||request===old.request_id)){waiting('distinct second original route not ready',details);return null;}
    const g=find('G',row=>same(row.args[0],routeRef)&&same(row.drive_ref,p2Ref(drive)));
    const input=find('l',row=>row.entry.navi_path_id===pathId&&samePath(row.args[1],sourceRef)&&same(row.drive_ref,p2Ref(drive)));
    if((requireG&&!g)||!input){waiting(!input?'matching original g.l input not observed':'matching original G exit not observed',details);return null;}
    const navi=originalInput(input.entry.args[0]),naviRef=p2Ref(navi);
    if(String(navi.pathID.value)!==pathId){waiting('observed original NaviInfo changed path before retention',Object.assign(details,{recorded_navi_ref:naviRef}));return null;}
    return {route,path,navi,route_ref:routeRef,path_ref:sourceRef,source_path_ref:sourceRef,navi_ref:naviRef,
      path_id:pathId,request_id:request,input_evidence:{source_seq:input.entry.source_seq,call_id:input.call_id},g};
  }
  function originalReturned(method,args,after){
    const ref=p2Ref(drive);return find(method,row=>same(row.drive_ref,ref)&&args.every((arg,index)=>typeof arg==='string'?value(row.args[index])===arg:same(row.args[index],arg)),after);
  }
  function tick(){
    if(stopped||busy)return;if(Date.now()>deadline||Date.now()>stageDeadline){fail(new Error('actual original route/processing precondition timed out'));return;}
    busy=true;
    try { Java.perform(()=>{
      if(stopped){busy=false;return;}
      if(Date.now()>deadline||Date.now()>stageDeadline){busy=false;fail(new Error('actual original route/processing precondition timed out'));return;}
      try {
        // perform() waits for the app class loader. The first fixture timer can
        // fire before Android has published its main Looper; do not construct
        // Frida's wakeup Handler until that original bootstrap is complete.
        if(Java.use('android.os.Looper').getMainLooper()===null){
          busy=false;timer=setTimeout(tick,250);return;
        }
        Java.scheduleOnMainThread(()=>{
      try{
        if(stopped)return;
        if(Date.now()>deadline||Date.now()>stageDeadline)throw new Error('actual original route/processing precondition timed out');
        if(!drive){const found=resolveDrive();if(!found){waiting('navigation-owned drive not ready');return;}drive=retain(found);mmt=config.mmt?(typeof config.mmt==='function'?config.mmt():config.mmt):Java.use('k.h.a0.c.c.a.e0').A();mmt=retain(mmt);}
        if(stage===0){
          if(!old){
          const first=candidate(false);if(!first)return;
          const sourcePath=first.path,sourceLength=String(sourcePath.getLength());first.route=retain(first.route);first.navi=retain(first.navi);first.path=retain(Java.use('com.autonavi.gbl.common.path.option.PathInfo').$new(sourcePath));
          const copiedId=String(first.path.getPathID()),copiedLength=String(first.path.getLength());
          if(copiedId!==first.path_id||copiedLength!==sourceLength||!first.path.isValid())throw new Error('original SDK path copy did not preserve actual native route');
          first.path_ref=p2Ref(first.path);first.sdk_copy={constructor:'com.autonavi.gbl.common.path.option.PathInfo(PathInfo)',source_ref:first.source_path_ref,
            input_ref:first.path_ref,source_path_id:first.path_id,input_path_id:copiedId,source_length:sourceLength,input_length:copiedLength};
          old=first;note('native_callback_fixture_input',{input_kind:'sdk-path-copy',...old.sdk_copy});
          }
          const completed=find('G',row=>same(row.args[0],old.route_ref)&&same(row.drive_ref,p2Ref(drive)));
          if(!completed){waiting('retained first route original G exit not observed',{route_ref:old.route_ref,source_path_ref:old.source_path_ref,path_id:old.path_id,recorded_navi_ref:old.navi_ref,selected_input_evidence:old.input_evidence});return;}old.g=completed;
          step(P2_ROUTE_CHANGE_STEPS[0].name,old.g);afterSeq=old.g.source_seq;stage=1;
        }else if(stage===1){
          const next=candidate();if(!next)return;if(next.path_id===old.path_id||next.request_id===old.request_id||next.g.source_seq<=afterSeq){waiting('distinct second original route not ready',{route_ref:next.route_ref,path_id:next.path_id,recorded_navi_ref:next.navi_ref});return;}
          const ack=originalReturned('g',[next.path_id],next.g.source_seq);if(!ack){waiting('decoded selection ACK after second G not observed',{path_id:next.path_id,recorded_navi_ref:next.navi_ref});return;}
          current=next;current.route=retain(next.route);current.path=retain(next.path);current.navi=retain(next.navi);
          step(P2_ROUTE_CHANGE_STEPS[1].name,next.g,{ack_evidence:evidence(ack)});stage=2;
        }else if(stage===2){
          if(!old.path.isValid()||String(old.path.getPathID())!==old.path_id||String(old.navi.pathID.value)!==old.path_id)
            throw new Error('retained original SDK path/progress input no longer valid');
          const before=Math.max(...p2RouteChangeCalls.map(row=>row.source_seq),-1);
          drive.l(old.navi,old.path);oldProgress=originalReturned('l',[old.navi_ref,old.path_ref],before);
          if(!oldProgress)throw new Error('actual old-input g.l exit not observed');
          step(P2_ROUTE_CHANGE_STEPS[2].name,oldProgress);stage=3;stageDeadline=Math.min(deadline,Date.now()+80000);
        }else if(stage===3){
          const done=originalReturned('K',[old.path_id,old.path_ref],oldProgress.source_seq);if(!done)return;
          step(P2_ROUTE_CHANGE_STEPS[3].name,done);afterSeq=done.source_seq;stage=4;stageDeadline=Math.min(deadline,Date.now()+80000);
        }else if(stage===4){
          drive.onChangeNaviPath(new Int64(current.path_id),new Int64(old.path_id));oldChange=originalReturned('onChangeNaviPath',[current.path_id,old.path_id],afterSeq);
          if(!oldChange)throw new Error('actual old-path public change exit not observed');
          step(P2_ROUTE_CHANGE_STEPS[4].name,oldChange);stage=5;
        }else if(stage===5){
          const done=originalReturned('C',[old.path_id],oldChange.entry.source_seq);if(!done)return;
          step(P2_ROUTE_CHANGE_STEPS[5].name,done);afterSeq=done.source_seq;stage=6;stageDeadline=Math.min(deadline,Date.now()+80000);
        }else if(stage===6){
          const latest=candidate();if(!latest||latest.path_id!==current.path_id||latest.request_id!==current.request_id)throw new Error('native current route changed before restoration');
          drive.onChangeNaviPath(new Int64(old.path_id),new Int64(current.path_id));currentChange=originalReturned('onChangeNaviPath',[old.path_id,current.path_id],afterSeq);
          if(!currentChange)throw new Error('actual current-path public change exit not observed');
          step(P2_ROUTE_CHANGE_STEPS[6].name,currentChange);stage=7;
        }else if(stage===7){
          const done=originalReturned('C',[current.path_id],currentChange.entry.source_seq);if(!done)return;
          step(P2_ROUTE_CHANGE_STEPS[7].name,done);afterSeq=done.source_seq;stage=8;stageDeadline=Math.min(deadline,Date.now()+80000);
        }else if(stage===8){
          drive.g(new Int64(old.path_id));staleAck=originalReturned('g',[old.path_id],afterSeq);
          if(!staleAck)throw new Error('actual stale-path selection callback exit not observed');
          step(P2_ROUTE_CHANGE_STEPS[8].name,staleAck);stage=9;
        }else if(stage===9){
          const done=originalReturned('I',[current.path_id],staleAck.entry.source_seq);if(!done)return;
          step(P2_ROUTE_CHANGE_STEPS[9].name,done);afterSeq=done.source_seq;stage=10;stageDeadline=Math.min(deadline,Date.now()+80000);
        }else if(stage===10){
          drive.w();selectionError=originalReturned('w',[],afterSeq);
          if(!selectionError)throw new Error('actual selection-error callback exit not observed');
          step(P2_ROUTE_CHANGE_STEPS[10].name,selectionError);stage=11;
        }else{
          const done=originalReturned('I',[current.path_id],selectionError.entry.source_seq);if(!done)return;
          step(P2_ROUTE_CHANGE_STEPS[11].name,done);stopped=true;
          note('native_callback_fixture_end',{status:'scheduled-inputs-complete',old:context(old),current:context(current)});
          if (typeof p2CallbackInputCompleted !== 'undefined') p2CallbackInputCompleted = true;
        }
      }catch(error){fail(error);}finally{busy=false;if(!stopped)timer=setTimeout(tick,250);}
        });
      }catch(error){busy=false;fail(error);}
    }); }catch(error){busy=false;fail(error);}
  }
  note('native_callback_fixture_plan',{schema:'denza-native-callback-case-v1',origin:'actual native SDK route/progress inputs and SDK PathInfo copy',
    route_change_policy:'actual-native-two-route-v1',location:null,steps:P2_ROUTE_CHANGE_STEPS});
  note('native_callback_fixture_attached',{route_change_policy:'actual-native-two-route-v1',wait_ms:waitMs});
  timer=setTimeout(tick,0);
  return {case:'route-change',held,cancel(){stopped=true;if(timer!==null)clearTimeout(timer);}};
}
