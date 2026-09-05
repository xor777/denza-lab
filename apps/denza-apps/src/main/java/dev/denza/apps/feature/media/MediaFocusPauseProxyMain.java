package dev.denza.apps.feature.media;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Looper;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Shell-UID, one-shot removal of suspended media predecessors; never starts or stops a player. */
public final class MediaFocusPauseProxyMain {
    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable error) {
            System.out.println("DENZA_MEDIA_FOCUS_ERROR " + error.getClass().getSimpleName());
        }
        System.exit(0);
    }

    private static void run(String[] args) throws Exception {
        if (args.length < 4 || args.length > 34 || args.length % 2 != 0) {
            throw new IllegalArgumentException("Expected current package/uid and predecessors");
        }
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
                    || Integer.parseInt(args[i + 1]) <= 0) throw new IllegalArgumentException();
        }
        Class<?> vm = Class.forName("dalvik.system.VMRuntime");
        Object runtime = vm.getMethod("getRuntime").invoke(null);
        vm.getMethod("setHiddenApiExemptions", String[].class)
                .invoke(runtime, (Object) new String[]{"L"});
        Looper.prepareMainLooper();
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Object thread = threadClass.getMethod("systemMain").invoke(null);
        Context context = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
        Class<?> mediaServiceManager = Class.forName("android.media.MediaServiceManager");
        Class.forName("android.media.MediaFrameworkPlatformInitializer")
                .getMethod("setMediaServiceManager", mediaServiceManager)
                .invoke(null, mediaServiceManager.getConstructor().newInstance());
        MediaSessionManager sessions = context.getSystemService(MediaSessionManager.class);
        Method getAudio = Class.forName("android.media.AudioManager").getDeclaredMethod("getService");
        getAudio.setAccessible(true);
        Object audio = getAudio.invoke(null);
        Class<?> audioService = Class.forName("android.media.IAudioService");
        Method getStack = audioService.getMethod("getFocusStack");
        Method unregister = audioService.getMethod("unregisterAudioFocusClient", String.class);
        String currentPackage = args[0];
        int currentUid = Integer.parseInt(args[1]);
        int removed = 0;

        for (int i = 2; i < args.length; i += 2) {
            String previousPackage = args[i];
            int previousUid = Integer.parseInt(args[i + 1]);
            if (previousUid / 100000 != currentUid / 100000 || previousPackage.equals(currentPackage)) {
                throw new IllegalStateException("Ambiguous predecessor identity");
            }
            if (!sessionState(context, sessions, currentPackage, currentUid, PlaybackState.STATE_PLAYING)) {
                throw new IllegalStateException("Current player changed");
            }
            List<?> stack = (List<?>) getStack.invoke(audio);
            if (stack.isEmpty()) continue;
            requireCurrentFocus(stack, currentPackage, currentUid);
            List<String> clients = new ArrayList<>();
            for (int index = 0; index < stack.size() - 1; index++) {
                Object info = stack.get(index);
                if (matches(info, previousPackage, previousUid) && media(info)) {
                    if (!suspendedMedia(info)) throw new IllegalStateException("Predecessor focus changed");
                    clients.add((String) call(info, "getClientId"));
                }
            }
            for (String client : clients) {
                List<?> fresh = (List<?>) getStack.invoke(audio);
                requireCurrentFocus(fresh, currentPackage, currentUid);
                for (int index = 0; index < fresh.size() - 1; index++) {
                    Object info = fresh.get(index);
                    if (client.equals(call(info, "getClientId"))
                            && matches(info, previousPackage, previousUid)) {
                        if (!suspendedMedia(info)
                                || !sessionState(context, sessions, currentPackage, currentUid, PlaybackState.STATE_PLAYING)
                                || !sessionState(context, sessions, previousPackage, previousUid, PlaybackState.STATE_PAUSED)) {
                            throw new IllegalStateException("Session or focus changed");
                        }
                        unregister.invoke(audio, client);
                        removed++;
                        break;
                    }
                }
            }
        }
        System.out.println("DENZA_MEDIA_FOCUS_READY removed=" + removed);
    }

    private static boolean sessionState(Context context, MediaSessionManager manager,
            String pkg, int uid, int expected) throws Exception {
        if (context.getPackageManager().getApplicationInfo(pkg, 0).uid != uid) return false;
        boolean found = false;
        for (MediaController controller : manager.getActiveSessions(null)) {
            if (!pkg.equals(controller.getPackageName())) continue;
            PlaybackState state = controller.getPlaybackState();
            // Multiple conflicting sessions in one package are not a safe package-level target.
            if (state == null || state.getState() != expected) return false;
            found = true;
        }
        return found;
    }

    private static void requireCurrentFocus(List<?> stack, String pkg, int uid) throws Exception {
        if (stack.isEmpty() || !matches(stack.get(stack.size() - 1), pkg, uid)
                || ((Integer) call(stack.get(stack.size() - 1), "getLossReceived")) != 0
                || !media(stack.get(stack.size() - 1))) {
            throw new IllegalStateException("Focus owner changed");
        }
    }

    private static boolean suspendedMedia(Object info) throws Exception {
        return ((Integer) call(info, "getLossReceived")) == -2 && media(info);
    }

    private static boolean media(Object info) throws Exception {
        AudioAttributes attributes = (AudioAttributes) call(info, "getAttributes");
        return attributes.getUsage() == AudioAttributes.USAGE_MEDIA;
    }

    private static boolean matches(Object info, String pkg, int uid) throws Exception {
        return pkg.equals(call(info, "getPackageName"))
                && ((Integer) call(info, "getClientUid")) == uid;
    }

    private static Object call(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }
}
