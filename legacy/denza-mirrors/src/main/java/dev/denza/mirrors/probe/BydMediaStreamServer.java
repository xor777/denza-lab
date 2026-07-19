package dev.denza.mirrors.probe;

import android.content.Context;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;

final class BydMediaStreamServer {
    private static final String TAG = "DenzaMediaStream";
    private static final String DISHARE_APK = "/system/app/DiShare/DiShare.apk";
    private static final String[] NATIVE_LIBS = {
            "libmediasdk_common.so",
            "libmediasdk_jni.so",
            "libstream_test.so"
    };
    private static LoadedClasses loadedClasses;

    private Class<?> avConfigClass;
    private Class<?> transConfigClass;
    private Class<?> sessionServerClass;
    private Object server;
    private Method avSetString;
    private Method avSetInteger;
    private Method transSetString;
    private Method transSetInteger;
    private Method initialize;
    private Method createInputWindow;
    private Method addReceiver;
    private Method start;
    private Method stop;
    private Method release;
    private Method requestKeyFrame;
    private Surface inputSurface;
    private int receiverCounter;

    Surface initialize(Context context, int width, int height) throws Exception {
        if (inputSurface != null) {
            return inputSurface;
        }
        Context appContext = context.getApplicationContext();
        LoadedClasses classes = loadClasses(appContext);
        avConfigClass = classes.avConfigClass;
        transConfigClass = classes.transConfigClass;
        sessionServerClass = classes.sessionServerClass;
        avSetString = classes.avSetString;
        avSetInteger = classes.avSetInteger;
        transSetString = classes.transSetString;
        transSetInteger = classes.transSetInteger;
        initialize = classes.initialize;
        createInputWindow = classes.createInputWindow;
        addReceiver = classes.addReceiver;
        start = classes.start;
        stop = classes.stop;
        release = classes.release;
        requestKeyFrame = classes.requestKeyFrame;

        server = sessionServerClass.getConstructor().newInstance();
        Object avConfig = avConfigClass.getConstructor().newInstance();
        setAvString(avConfig, "mime", "video/avc");
        setAvInt(avConfig, "width", width);
        setAvInt(avConfig, "height", height);
        setAvInt(avConfig, "color-format", 0x7f000789);
        setAvInt(avConfig, "bitrate", 0xb71b00);
        setAvInt(avConfig, "bitrate-mode", 2);
        setAvInt(avConfig, "create-input-buffers-suspended", 1);
        setAvInt(avConfig, "frame-rate", 30);
        setAvInt(avConfig, "max-fps-to-encoder", 30);
        setAvInt(avConfig, "repeat-previous-frame-after", 0x8235);
        setAvInt(avConfig, "i-frame-interval", -1);
        setAvInt(avConfig, "operating-rate", 120);
        setAvInt(avConfig, "profile", 8);
        setAvInt(avConfig, "level", 0x1000);
        setAvInt(avConfig, "video-qp-i-min", 10);
        setAvInt(avConfig, "video-qp-i-max", 36);
        setAvInt(avConfig, "video-qp-p-min", 10);
        setAvInt(avConfig, "video-qp-p-max", 36);

        Object transConfig = transConfigClass.getConstructor().newInstance();
        initialize.invoke(server, avConfig, transConfig);
        inputSurface = (Surface) createInputWindow.invoke(server);
        Log.i(TAG, "initialized width=" + width + " height=" + height
                + " surface=" + inputSurface);
        return inputSurface;
    }

    int addReceiver(String clientIp, int clientPort, int protocol, int bufferSize) throws Exception {
        if (server == null) {
            return -1;
        }
        Object transConfig = transConfigClass.getConstructor().newInstance();
        transSetString.invoke(transConfig, "clientIp", clientIp);
        transSetInteger.invoke(transConfig, "clientPort", clientPort);
        transSetInteger.invoke(transConfig, "protocol", protocol);
        transSetInteger.invoke(transConfig, "bufSize", bufferSize);
        addReceiver.invoke(server, transConfig);
        int receiverId = ++receiverCounter;
        Log.i(TAG, "add receiver " + clientIp + ":" + clientPort
                + " protocol=" + protocol + " buffer=" + bufferSize
                + " id=" + receiverId);
        return receiverId;
    }

    void start() throws Exception {
        if (server != null) {
            start.invoke(server);
        }
    }

    void requestKeyFrame() {
        if (server == null) {
            return;
        }
        try {
            requestKeyFrame.invoke(server);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.i(TAG, "request key frame failed", e);
        }
    }

    void stop() {
        if (server == null) {
            return;
        }
        try {
            stop.invoke(server);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.i(TAG, "stop failed", e);
        }
    }

    void release() {
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (server == null) {
            return;
        }
        try {
            release.invoke(server);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.i(TAG, "release failed", e);
        } finally {
            server = null;
            receiverCounter = 0;
        }
    }

    private void setAvString(Object target, String key, String value) throws Exception {
        avSetString.invoke(target, key, value);
    }

    private void setAvInt(Object target, String key, int value) throws Exception {
        avSetInteger.invoke(target, key, value);
    }

    private static void extractNativeLibraries(File libDir) throws IOException {
        if (!libDir.exists() && !libDir.mkdirs()) {
            throw new IOException("mkdir failed " + libDir);
        }
        try (ZipFile zipFile = new ZipFile(DISHARE_APK)) {
            for (String library : NATIVE_LIBS) {
                String entryName = "lib/arm64-v8a/" + library;
                ZipEntry entry = zipFile.getEntry(entryName);
                if (entry == null) {
                    continue;
                }
                File output = new File(libDir, library);
                if (output.isFile() && output.length() == entry.getSize()) {
                    continue;
                }
                try (InputStream input = zipFile.getInputStream(entry);
                     FileOutputStream fileOutput = new FileOutputStream(output, false)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        fileOutput.write(buffer, 0, read);
                    }
                }
                if (!output.setReadable(true, false)) {
                    Log.i(TAG, "setReadable failed for " + output);
                }
                if (!output.setExecutable(true, false)) {
                    Log.i(TAG, "setExecutable failed for " + output);
                }
            }
        }
    }

    private static synchronized LoadedClasses loadClasses(Context appContext) throws Exception {
        if (loadedClasses != null) {
            return loadedClasses;
        }
        File libDir = appContext.getDir("dishare_libs", Context.MODE_PRIVATE);
        extractNativeLibraries(libDir);
        DexClassLoader loader = new DexClassLoader(
                DISHARE_APK,
                appContext.getCodeCacheDir().getAbsolutePath(),
                libDir.getAbsolutePath(),
                appContext.getClassLoader());
        LoadedClasses classes = new LoadedClasses();
        classes.avConfigClass = loader.loadClass("com.byd.media.mediastream.AVConfig");
        classes.transConfigClass = loader.loadClass("com.byd.media.mediastream.TransConfig");
        classes.sessionServerClass = loader.loadClass("com.byd.media.mediastream.SessionServer");
        classes.avSetString = classes.avConfigClass.getMethod("setString",
                String.class, String.class);
        classes.avSetInteger = classes.avConfigClass.getMethod("setInteger",
                String.class, int.class);
        classes.transSetString = classes.transConfigClass.getMethod("setString",
                String.class, String.class);
        classes.transSetInteger = classes.transConfigClass.getMethod("setInteger",
                String.class, int.class);
        classes.initialize = classes.sessionServerClass.getMethod("initialize",
                classes.avConfigClass, classes.transConfigClass);
        classes.createInputWindow = classes.sessionServerClass.getMethod("createInputWindow");
        classes.addReceiver = classes.sessionServerClass.getMethod("addReceiver",
                classes.transConfigClass);
        classes.start = classes.sessionServerClass.getMethod("start");
        classes.stop = classes.sessionServerClass.getMethod("stop");
        classes.release = classes.sessionServerClass.getMethod("release");
        classes.requestKeyFrame = classes.sessionServerClass.getMethod("requestKeyFrame");
        loadedClasses = classes;
        return classes;
    }

    private static final class LoadedClasses {
        Class<?> avConfigClass;
        Class<?> transConfigClass;
        Class<?> sessionServerClass;
        Method avSetString;
        Method avSetInteger;
        Method transSetString;
        Method transSetInteger;
        Method initialize;
        Method createInputWindow;
        Method addReceiver;
        Method start;
        Method stop;
        Method release;
        Method requestKeyFrame;
    }
}
