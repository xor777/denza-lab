package dev.denza.tools.runtime;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import static dev.denza.tools.runtime.CloudRuntimeSupervisor.*;

public final class CloudReadOnlyFilesTest {
    private static void check(boolean condition){if(!condition)throw new AssertionError();}
    private static String hex(String value){return CloudNativeConnection.hex(value.getBytes(StandardCharsets.US_ASCII));}
    private static CloudPrimitiveBridge bridge(CloudReadOnlyFiles files){
        return new CloudPrimitiveBridge(new CloudPrimitiveBridge.Platform(){
            public int getInt(int device,int fid){throw new AssertionError();}
            public CloudPlatform.BufferResult getBuffer(int device,int fid){throw new AssertionError();}
            public float getFloat(int device,int fid){throw new AssertionError();}
            public String property(String key){throw new AssertionError();}
            public void setInt(int device,int fid,int value){throw new AssertionError();}
            public void setProperty(String key,String value){throw new AssertionError();}
            public byte[][] dnsLookup(String host){throw new AssertionError();}
            public String waitForEvent(long deadline){throw new AssertionError();}
        },bytes->{},new HashMap<>(),files);
    }
    private static String call(CloudPrimitiveBridge bridge,String verb,String... args)throws Exception{
        return bridge.call(verb,List.of(args));
    }
    private static void protocol()throws Exception{
        Path temp=Files.createTempFile("cloud-readonly-",".dat");
        try{
            Files.write(temp,new byte[]{1,2,3});
            try(CloudReadOnlyFiles files=new CloudReadOnlyFiles(path->Files.newInputStream(temp))){
                CloudPrimitiveBridge bridge=bridge(files);
                String path=hex(CloudReadOnlyFiles.CACHE_PATH);
                check(call(bridge,"FILE_OPEN",hex("/data/cloudservice/../other"),hex("rb")).equals("VALUE -13"));
                check(call(bridge,"FILE_OPEN",path,hex("w")).equals("VALUE -13"));
                check(call(bridge,"FILE_OPEN",path,hex("r")).equals("VALUE 1"));
                check(call(bridge,"FILE_OPEN",path,hex("rb")).equals("VALUE 2"));
                check(call(bridge,"FILE_OPEN",path,hex("rb")).equals("VALUE -24"));
                check(call(bridge,"FILE_EOF","1").equals("VALUE 0"));
                check(call(bridge,"FILE_READ","1","0").equals("BUFFER 0 -"));
                check(call(bridge,"FILE_EOF","1").equals("VALUE 0"));
                check(call(bridge,"FILE_READ","1","3").equals("BUFFER 0 010203"));
                check(call(bridge,"FILE_EOF","1").equals("VALUE 0"));
                check(call(bridge,"FILE_READ","1","1").equals("BUFFER 0 -"));
                check(call(bridge,"FILE_EOF","1").equals("VALUE 1"));
                check(call(bridge,"FILE_CLOSE","1").equals("VALUE 0"));
                check(call(bridge,"FILE_READ","1","1").equals("BUFFER -9 -"));
                check(call(bridge,"FILE_EOF","1").equals("VALUE -9"));
                check(call(bridge,"FILE_CLOSE","1").equals("VALUE -9"));
                check(call(bridge,"FILE_OPEN",path,hex("rb")).equals("VALUE 3"));
                try{call(bridge,"FILE_READ","2","513");throw new AssertionError();}
                catch(CloudNativePipe.ProtocolFailure expected){}
            }
        }finally{Files.deleteIfExists(temp);}
    }
    private static void errorsAndBounds()throws Exception{
        String path=hex(CloudReadOnlyFiles.CACHE_PATH),mode=hex("rb");
        try(CloudReadOnlyFiles absent=new CloudReadOnlyFiles(source->{throw new FileNotFoundException("missing");});
            CloudReadOnlyFiles denied=new CloudReadOnlyFiles(source->{throw new AccessDeniedException("denied");})){
            check(call(bridge(absent),"FILE_OPEN",path,mode).equals("VALUE -2"));
            check(call(bridge(denied),"FILE_OPEN",path,mode).equals("VALUE -13"));
        }
        byte[] data=new byte[CloudReadOnlyFiles.MAX_BYTES+1];
        for(int i=0;i<data.length;i++)data[i]=(byte)i;
        try(CloudReadOnlyFiles files=new CloudReadOnlyFiles(source->new ByteArrayInputStream(data))){
            CloudPrimitiveBridge bridge=bridge(files);
            check(call(bridge,"FILE_OPEN",path,mode).equals("VALUE 1"));
            for(int i=0;i<CloudReadOnlyFiles.MAX_BYTES/512;i++){
                String result=call(bridge,"FILE_READ","1","512");
                check(result.startsWith("BUFFER 0 ")&&result.length()=="BUFFER 0 ".length()+1024);
            }
            check(call(bridge,"FILE_READ","1","1").equals("BUFFER -27 -"));
            check(call(bridge,"FILE_EOF","1").equals("VALUE 0"));
        }
        try(CloudReadOnlyFiles files=new CloudReadOnlyFiles(source->new FilterInputStream(new ByteArrayInputStream(new byte[]{7,8,9})){
            @Override public int read(byte[] bytes,int offset,int length)throws java.io.IOException{
                return super.read(bytes,offset,Math.min(length,1));
            }
        })){
            CloudPrimitiveBridge bridge=bridge(files);
            check(call(bridge,"FILE_OPEN",path,mode).equals("VALUE 1"));
            check(call(bridge,"FILE_READ","1","3").equals("BUFFER 0 070809"));
            check(call(bridge,"FILE_EOF","1").equals("VALUE 0"));
            check(call(bridge,"FILE_READ","1","2").equals("BUFFER 0 -"));
            check(call(bridge,"FILE_EOF","1").equals("VALUE 1"));
        }
    }
    private static void accessAndReadOnlyPolicy()throws Exception{
        Path directory=Files.createTempDirectory("cloud-file-policy-");
        Path cache=directory.resolve("cache.dat"),tcp=directory.resolve("tcp.dat"),config=directory.resolve("config");
        Files.write(cache,new byte[]{4,5});Files.write(tcp,new byte[]{6});Files.createDirectory(config);
        HashMap<String,Path> backing=new HashMap<>();
        backing.put(CloudReadOnlyFiles.CACHE_PATH,cache);
        backing.put(CloudReadOnlyFiles.TCP_512_PATH,tcp);
        backing.put(CloudReadOnlyFiles.CONFIG_PATH,config);
        try(CloudReadOnlyFiles files=new CloudReadOnlyFiles(
                path->Files.newInputStream(backing.get(path)),
                (path,mode)->{
                    Path target=backing.get(path);
                    if(!Files.exists(target))throw new FileNotFoundException(path);
                    return mode==0||Files.isReadable(target)?0:-13;
                })){
            CloudPrimitiveBridge bridge=bridge(files);
            String cachePath=hex(CloudReadOnlyFiles.CACHE_PATH);
            String tcpPath=hex(CloudReadOnlyFiles.TCP_512_PATH);
            String configPath=hex(CloudReadOnlyFiles.CONFIG_PATH);
            for(String path:new String[]{cachePath,tcpPath,configPath}){
                check(call(bridge,"FILE_ACCESS",path,"0").equals("VALUE 0"));
                check(call(bridge,"FILE_ACCESS",path,"4").equals("VALUE 0"));
                check(call(bridge,"FILE_ACCESS",path,"2").equals("VALUE -13"));
            }
            check(call(bridge,"FILE_ACCESS",hex("/data/cloudservice/../other"),"0").equals("VALUE -13"));
            check(call(bridge,"FILE_ACCESS",cachePath,"6").equals("VALUE -13"));
            check(call(bridge,"FILE_OPEN",tcpPath,hex("rb")).equals("VALUE 1"));
            check(call(bridge,"FILE_READ","1","1").equals("BUFFER 0 06"));
            check(call(bridge,"FILE_OPEN",cachePath,hex("wb")).equals("VALUE -13"));
            check(call(bridge,"FILE_WRITE","1","deadbeef").equals("VALUE -30"));
            check(call(bridge,"FILE_REMOVE",tcpPath).equals("VALUE -30"));
            check(call(bridge,"FILE_REMOVE",hex("/data/cloudservice/../other")).equals("VALUE -13"));
            check(Files.readAllBytes(tcp)[0]==6);
            Files.delete(tcp);
            check(call(bridge,"FILE_ACCESS",tcpPath,"0").equals("VALUE -2"));
            check(call(bridge,"FILE_OPEN",tcpPath,hex("r")).equals("VALUE -2"));
        }finally{
            Files.deleteIfExists(cache);Files.deleteIfExists(tcp);Files.deleteIfExists(config);Files.deleteIfExists(directory);
        }
        try(CloudReadOnlyFiles denied=new CloudReadOnlyFiles(path->{throw new AccessDeniedException(path);},
                (path,mode)->{throw new AccessDeniedException(path);})){
            CloudPrimitiveBridge bridge=bridge(denied);
            check(call(bridge,"FILE_ACCESS",hex(CloudReadOnlyFiles.CONFIG_PATH),"0").equals("VALUE -13"));
        }
    }
    private static void connectionCleanup()throws Exception{
        final boolean[] streamClosed={false};
        CloudReadOnlyFiles files=new CloudReadOnlyFiles(path->new ByteArrayInputStream(new byte[]{1}){
            @Override public void close(){streamClosed[0]=true;}
        });
        check(files.open(CloudReadOnlyFiles.CACHE_PATH,"r")==1);
        CloudNativeConnection.Dependencies dependencies=new CloudNativeConnection.Dependencies(){
            public void verifyFirmware()throws Exception{throw new CloudSessionLoop.Unavailable();}
            public Process launch(Path executable){throw new AssertionError();}
            public CloudPlatform platform(Scope scope,Identity pair){throw new AssertionError();}
            public void initializeTls(){throw new AssertionError();}
            public CloudTransport transport(){throw new AssertionError();}
        };
        CloudNativeConnection connection=new CloudNativeConnection(Path.of("unused"),
            new Identity("89860700000000000000","460010000000000"),1,
            ()->1000,null,new CloudNativeConnection.Registration(),dependencies,files);
        try{connection.establish(new CloudSessionLoop.Progress(){
            public void pulse(){}public void stage(Stage stage,Code code){}
        });throw new AssertionError();}catch(CloudSessionLoop.Unavailable expected){}
        connection.close();
        check(streamClosed[0]&&files.eof(1)==-9);
        connection.close();
    }
    public static void main(String[] args)throws Exception{
        protocol();errorsAndBounds();accessAndReadOnlyPolicy();connectionCleanup();
        System.out.println("CloudReadOnlyFilesTest OK");
    }
}
