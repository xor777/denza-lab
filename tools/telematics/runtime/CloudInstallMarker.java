package dev.denza.tools.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONObject;

/** Non-secret, atomic app intent. A missing/replaced marker never authorizes a session. */
public final class CloudInstallMarker {
    public final String installId, desired;
    public final long generation;
    private CloudInstallMarker(String id,long generation,String desired) {
        this.installId=id;this.generation=generation;this.desired=desired;
    }
    public static CloudInstallMarker read(Path path) throws IOException {
        if(path==null || !path.isAbsolute() || !"install.json".equals(path.getFileName().toString()) ||
           !path.normalize().equals(path) ||
           !path.toString().contains("/Android/data/dev.denza.apps/files/cloud/install.json"))
            throw new IOException("install_marker_path");
        final byte[] bytes;
        try {
            BasicFileAttributes stat=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!stat.isRegularFile() || stat.size()<2 || stat.size()>512)throw new IOException("install_marker_shape");
            try(FileChannel channel=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
                ByteBuffer buffer=ByteBuffer.allocate(513);
                while(buffer.hasRemaining() && channel.read(buffer)>0){}
                if(buffer.position()>512)throw new IOException("install_marker_bound");
                bytes=Arrays.copyOf(buffer.array(),buffer.position());
            }
        }catch(RuntimeException failure){throw new IOException("install_marker_unavailable",failure);}
        if(bytes.length<2 || bytes.length>512)throw new IOException("install_marker_bound");
        String raw=new String(bytes,StandardCharsets.UTF_8);
        if(!Arrays.equals(bytes,raw.getBytes(StandardCharsets.UTF_8)))throw new IOException("install_marker_utf8");
        try{CloudRuntimeProtocol.assertFlatUniqueKeys(raw);}catch(Exception invalid){throw new IOException("install_marker_shape");}
        final JSONObject value;
        try { value=new JSONObject(raw); }
        catch(Exception failure){throw new IOException("install_marker_json");}
        Set<String> keys=new HashSet<>();for(Iterator<String> it=value.keys();it.hasNext();)keys.add(it.next());
        if(!keys.equals(new HashSet<>(Arrays.asList("protocol","install_id","generation","desired"))))
            throw new IOException("install_marker_keys");
        Object version=value.opt("protocol"),id=value.opt("install_id"),counter=value.opt("generation"),desired=value.opt("desired");
        if(!(version instanceof Number) || !"2".equals(version.toString()) ||
           !(id instanceof String) || !((String)id).matches("[0-9a-f]{32}") ||
           !(counter instanceof Number) || !(desired instanceof String))
            throw new IOException("install_marker_values");
        final long generation;
        try{generation=Long.parseLong(counter.toString());}
        catch(NumberFormatException failure){throw new IOException("install_marker_generation");}
        if(generation<=0 || !(desired.equals("off")||desired.equals("custom")))
            throw new IOException("install_marker_values");
        return new CloudInstallMarker((String)id,generation,(String)desired);
    }
}
