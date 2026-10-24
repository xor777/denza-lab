package dev.denza.hud.probe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
/** Recorder boundary only; production coordinator decides which callbacks arrive here. */
final class DriveEvidence {
    static final List<Record> packets=new ArrayList<>();
    static final List<String> states=new ArrayList<>();
    static final class Record {
        final String receiver,session;final long topic,received;final byte[] bytes;
        Record(String receiver,String session,long topic,long received,byte[] bytes){
            this.receiver=receiver;this.session=session;this.topic=topic;this.received=received;this.bytes=bytes;
        }
    }
    static final List<String> feed=new ArrayList<>();
    /** The change journal's variant and NaviSt rows, as armId:json. */
    static final List<String> variants=new ArrayList<>(),naviSt=new ArrayList<>(),instrumentNaviStatus=new ArrayList<>();
    static void passiveState(String receiver,String session,String event,String detail){states.add(receiver+":"+session+":"+event);}
    static void feedState(String state,String reason,String detail){String row=state+":"+reason;if(feed.isEmpty()||!feed.get(feed.size()-1).equals(row))feed.add(row);}
    static void variant(String armId,Map<String,Object> effective){variants.add(armId+":"+dev.denza.adaslink.Json.write(effective));}
    static void naviSt(String armId,Map<String,Object> event){naviSt.add(armId+":"+dev.denza.adaslink.Json.write(event));}
    static void instrumentNaviStatus(String armId,Map<String,Object> event){instrumentNaviStatus.add(armId+":"+dev.denza.adaslink.Json.write(event));}
    static void passive(String receiver,String session,long topic,long stamp,long received,byte[] bytes){
        packets.add(new Record(receiver,session,topic,received,bytes));
    }
}
