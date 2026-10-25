package android.content;
import android.content.pm.PackageManager;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
public class Context {
    public static final int MODE_PRIVATE=0;
    public final File files;public final PackageManager pm=new PackageManager();
    public boolean commitOk=true;
    private final Map<String,Long> values=new HashMap<>();
    public Context(File files){this.files=files;files.mkdirs();}
    public Context getApplicationContext(){return this;}
    public File getFilesDir(){return files;}
    public android.content.res.AssetManager getAssets(){return new android.content.res.AssetManager(new File(files,"test-assets"));}
    public PackageManager getPackageManager(){return pm;}
    public ContentResolver getContentResolver(){return new ContentResolver();}
    public SharedPreferences getSharedPreferences(String name,int mode){return new SharedPreferences(){
        public long getLong(String k,long f){return values.getOrDefault(k,f);}
        public Editor edit(){return new Editor(){String key;long value;public Editor putLong(String k,long v){key=k;value=v;return this;}public boolean commit(){if(commitOk)values.put(key,value);return commitOk;}};}
    };}
}
