package dev.denza.tools.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONObject;

/** Strict legacy discovery and awake-alpha control request shapes. */
final class CloudControlRequest {
    final long id;
    final String op;
    final int protocol;
    final CloudRuntimeSupervisor.Identity pair;
    final String ownerId,serviceInstance;
    final long renewSeq;
    private CloudControlRequest(long id,String op,int protocol,CloudRuntimeSupervisor.Identity pair,
            String ownerId,String serviceInstance,long renewSeq){
        this.id=id;this.op=op;this.protocol=protocol;this.pair=pair;
        this.ownerId=ownerId;this.serviceInstance=serviceInstance;this.renewSeq=renewSeq;
    }
    static CloudControlRequest parse(String raw)throws IOException {
        if(raw==null||raw.getBytes(StandardCharsets.UTF_8).length>4096||raw.indexOf('\n')>=0)
            throw new IOException("control_request_bound");
        try{CloudRuntimeProtocol.assertFlatUniqueKeys(raw);}catch(Exception failure){throw new IOException("control_request_shape");}
        try{
            JSONObject value=new JSONObject(raw);
            Object id=value.opt("id"),op=value.opt("op"),version=value.opt("protocol");
            if(!(id instanceof Number)||!(op instanceof String))throw new IOException("control_request_type");
            long sequence=Long.parseLong(id.toString());
            if(sequence<=0)throw new IOException("control_request_id");
            String verb=(String)op;
            if(version!=null&&!(version instanceof Number))throw new IOException("control_request_protocol");
            int protocol=version==null?2:Integer.parseInt(version.toString());
            if(protocol!=2&&protocol!=3)throw new IOException("control_request_protocol");
            if(protocol==2&&!Arrays.asList("PROBE","STATUS","STOP").contains(verb))
                throw new IOException("legacy_control_op");
            if(protocol==3&&!Arrays.asList("PROBE","ATTACH","START","RENEW","STATUS","STOP").contains(verb))
                throw new IOException("control_request_op");
            Set<String> keys=new HashSet<>();for(Iterator<String> it=value.keys();it.hasNext();)keys.add(it.next());
            Set<String> expected=new HashSet<>(Arrays.asList("id","op"));
            if(protocol==3)expected.add("protocol");
            CloudRuntimeSupervisor.Identity pair=null;String ownerId=null,serviceInstance=null;long renewSeq=0;
            if(verb.equals("START")){
                expected.add("iccid");expected.add("imsi");expected.add("profile");
                expected.add("service_instance");expected.add("renew_seq");
                Object iccid=value.opt("iccid"),imsi=value.opt("imsi");
                if(!(iccid instanceof String)||!(imsi instanceof String))throw new IOException("control_identity_type");
                pair=new CloudRuntimeSupervisor.Identity((String)iccid,(String)imsi);
                if(!"awake-alpha-v1".equals(value.opt("profile")))throw new IOException("control_profile");
            }
            if(verb.equals("ATTACH")||verb.equals("RENEW")||(protocol==3&&verb.equals("STOP"))){
                expected.add("owner_id");
                Object owner=value.opt("owner_id");
                if(!(owner instanceof String)||!((String)owner).matches(
                    verb.equals("STOP")?"([0-9a-f]{32})?":"[0-9a-f]{32}"))
                    throw new IOException("control_owner_id");
                ownerId=(String)owner;
            }
            if(verb.equals("START")||verb.equals("ATTACH")||verb.equals("RENEW")||
               (protocol==3&&verb.equals("STOP"))){
                Object service=value.opt("service_instance");
                if(!(service instanceof String)||!((String)service).matches(
                    verb.equals("STOP")?"([0-9a-f]{32})?":"[0-9a-f]{32}"))
                    throw new IOException("control_service_instance");
                serviceInstance=(String)service;
                expected.add("service_instance");
            }
            if(verb.equals("START")||verb.equals("RENEW")){
                expected.add("renew_seq");
                Object number=value.opt("renew_seq");
                if(!(number instanceof Number))throw new IOException("control_renew_seq");
                renewSeq=Long.parseLong(number.toString());
                if(renewSeq<=0||(verb.equals("START")&&renewSeq!=1))throw new IOException("control_renew_seq");
            }
            if(!keys.equals(expected))throw new IOException("control_request_keys");
            return new CloudControlRequest(sequence,verb,protocol,pair,ownerId,serviceInstance,renewSeq);
        }catch(IOException error){throw error;}
        catch(Exception failure){throw new IOException("control_request_invalid");}
    }
}
