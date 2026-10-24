package android.provider;
import android.content.ContentResolver;
public final class Settings {
    public static final class Global {
        public static final String BOOT_COUNT="boot_count";
        public static int getInt(ContentResolver resolver,String key){throw new UnsupportedOperationException("test must inject boot identity");}
    }
}
