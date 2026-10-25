package android.content.res;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
/** Harness assets are isolated within each test Context directory. */
public final class AssetManager {
    private final File root;
    public AssetManager(File root){this.root=root;}
    public InputStream open(String name)throws IOException{return new FileInputStream(new File(root,name));}
}
