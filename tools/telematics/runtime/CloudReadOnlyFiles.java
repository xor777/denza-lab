package dev.denza.tools.runtime;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** One isolated connection's bounded, read-only view of stock startup files. */
final class CloudReadOnlyFiles implements AutoCloseable {
    static final String CACHE_PATH="/data/cloudservice/div15_msg_info_542_vector.dat";
    static final String TCP_512_PATH="/data/cloudservice/tcp_512.dat";
    static final String CONFIG_PATH="/data/logs/gblog/cloud_cfg_info";
    static final int MAX_READ=512, MAX_BYTES=64*1024;
    static final int MAX_PATH_LENGTH=Math.max(CACHE_PATH.length(),Math.max(TCP_512_PATH.length(),CONFIG_PATH.length()));
    private static final int ENOENT=-2, EIO=-5, EBADF=-9, EACCES=-13, EINVAL=-22, EMFILE=-24, EFBIG=-27, EROFS=-30;

    interface Opener { InputStream open(String path) throws IOException; }
    interface AccessChecker { int access(String path,int mode) throws IOException; }
    private static final class Handle {
        final InputStream input;
        int bytesRead;
        boolean eof;
        Handle(InputStream input){this.input=input;}
    }
    private final Opener opener;
    private final AccessChecker accessChecker;
    private final Map<Integer,Handle> handles=new HashMap<>();
    private int nextFd=1;
    private boolean closed;

    CloudReadOnlyFiles(){this(path->Files.newInputStream(Paths.get(path),
        StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS),CloudReadOnlyFiles::realAccess);}
    /** Host-test seam: virtual path admission remains fixed, backing I/O is injectable. */
    CloudReadOnlyFiles(Opener opener){this(opener,CloudReadOnlyFiles::realAccess);}
    CloudReadOnlyFiles(Opener opener,AccessChecker accessChecker){
        this.opener=opener;this.accessChecker=accessChecker;
    }

    private static boolean admitted(String path){
        return CACHE_PATH.equals(path)||TCP_512_PATH.equals(path)||CONFIG_PATH.equals(path);
    }
    private static int realAccess(String path,int mode)throws IOException{
        BasicFileAttributes attributes=Files.readAttributes(Paths.get(path),BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        if(attributes.isSymbolicLink())return EACCES;
        return mode==0||Files.isReadable(Paths.get(path))?0:EACCES;
    }
    synchronized int access(String path,int mode){
        if(closed)return EBADF;
        if(!admitted(path)||mode==1||mode==2||mode==3||mode==5||mode==6||mode==7)return EACCES;
        if(mode!=0&&mode!=4)return EINVAL;
        try{return accessChecker.access(path,mode);}
        catch(NoSuchFileException|FileNotFoundException missing){return ENOENT;}
        catch(AccessDeniedException denied){return EACCES;}
        catch(IOException failure){return EIO;}
    }
    synchronized int remove(String path){return admitted(path)?EROFS:EACCES;}
    synchronized int write(int fd,byte[] bytes){return EROFS;}

    synchronized int open(String path,String mode)throws IOException{
        if(closed)return EBADF;
        if(!admitted(path))return EACCES;
        if(!(mode.equals("r")||mode.equals("rb")))return EACCES;
        if(handles.size()>=2||nextFd==Integer.MAX_VALUE)return EMFILE;
        try{
            InputStream input=new BufferedInputStream(opener.open(path),1024);
            int fd=nextFd++;handles.put(fd,new Handle(input));return fd;
        }catch(NoSuchFileException|FileNotFoundException missing){return ENOENT;}
        catch(AccessDeniedException denied){return EACCES;}
        catch(IOException failure){return EIO;}
    }
    synchronized ReadResult read(int fd,int count)throws IOException{
        if(count<0||count>MAX_READ)throw new CloudNativePipe.ProtocolFailure();
        Handle handle=handles.get(fd);
        if(closed||handle==null)return new ReadResult(EBADF,null);
        if(count==0)return new ReadResult(0,new byte[0]);
        if(count>MAX_BYTES-handle.bytesRead)return new ReadResult(EFBIG,null);
        byte[] bytes=new byte[count];int length=0;
        try{
            while(length<count){
                int part=handle.input.read(bytes,length,count-length);
                if(part<0){handle.eof=true;break;}
                if(part==0)return new ReadResult(EIO,null);
                length+=part;handle.bytesRead+=part;
            }
            if(length==count)return new ReadResult(0,bytes);
            byte[] exact=new byte[length];System.arraycopy(bytes,0,exact,0,length);
            return new ReadResult(0,exact);
        }catch(IOException failure){return new ReadResult(EIO,null);}
    }
    synchronized int eof(int fd){
        Handle handle=handles.get(fd);
        return closed||handle==null?EBADF:(handle.eof?1:0);
    }
    synchronized int close(int fd){
        if(closed)return EBADF;
        Handle handle=handles.get(fd);
        if(handle==null)return EBADF;
        try{handle.input.close();handles.remove(fd);return 0;}catch(IOException failure){return EIO;}
    }
    @Override public synchronized void close()throws IOException{
        closed=true;IOException failure=null;
        for(Iterator<Map.Entry<Integer,Handle>> entries=handles.entrySet().iterator();entries.hasNext();){
            Handle handle=entries.next().getValue();
            try{handle.input.close();entries.remove();}
            catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        }
        if(failure!=null)throw failure;
    }
    static final class ReadResult {
        final int status;
        final byte[] bytes;
        ReadResult(int status,byte[] bytes){this.status=status;this.bytes=bytes;}
    }
}
