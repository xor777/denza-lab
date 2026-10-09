package dev.denza.apps;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import dev.denza.apps.core.DenzaRuntimeCoordinator;
import dev.denza.apps.feature.hud.HudGuidanceAccessibilityMonitor;
import dev.denza.apps.feature.media.MediaButtonEnvironment;
import dev.denza.apps.feature.media.MediaKeyDiagnostics;
import dev.denza.apps.feature.media.MediaKeyExperiment;
import dev.denza.apps.feature.media.MediaKeySnapshot;
import dev.denza.apps.feature.media.MediaResumeController;
import dev.denza.apps.feature.navigation.NavigationSettings;
import dev.denza.apps.feature.navigation.SteeringWheelKeyInterceptor;
import dev.denza.apps.feature.simulcast.SimulcastDialogOverlay;
import dev.denza.apps.feature.speaker.SpeakerCoverService;
import dev.denza.apps.feature.weather.WeatherAdapterScheduler;

/**
 * The app's one accessibility service, which several features ride on: the projection's overlay
 * over the stock DiShare dialog ({@link SimulcastDialogOverlay}), HUD guidance, the steering
 * wheel's Play/Pause and ★ keys, the speakers' foreground app, the stock weather app coming up,
 * the wireless-debugging dialog and runtime recovery. Its class name is recorded in the car's
 * {@code enabled_accessibility_services}, so it keeps it.
 */
public class SimulcastAccessibilityService extends AccessibilityService {
    private static final String TAG = "DenzaSimulcastA11y";

    private static volatile boolean connected;
    private static volatile SimulcastAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SteeringWheelKeyInterceptor steeringWheelKeyInterceptor =
            new SteeringWheelKeyInterceptor();
    private final SimulcastDialogOverlay overlay = new SimulcastDialogOverlay();
    private HudGuidanceAccessibilityMonitor hudGuidanceMonitor;
    // Published here and read by the support report, which is built off the main looper.
    private volatile MediaResumeController mediaResumeController;
    private MediaButtonEnvironment mediaButtonEnvironment;

    @Override
    protected void onServiceConnected() {
        connected = true;
        instance = this;
        overlay.attach(this);
        hudGuidanceMonitor = new HudGuidanceAccessibilityMonitor(this);
        hudGuidanceMonitor.attach();
        // On in a normal build. The switch exists so a build without the key filter can be made
        // without touching anything else; see MediaKeyExperiment.
        if (MediaKeyExperiment.INTERCEPT_KEYS) {
            mediaButtonEnvironment = new MediaButtonEnvironment(this);
            mediaResumeController = new MediaResumeController(this);
            mediaResumeController.start();
        }
        Log.i(TAG, "service connected");
        // The projection, HUD guidance and the wheel button read whether this service is connected.
        StateMarks.INSTANCE.accessibilityChanged("a11y connected");
        // The system can recreate this long-lived process without reopening MainActivity
        // (notably after an APK replacement). Recover desired runtimes here so a persisted
        // split toggle never remains visually on while its router is absent.
        DenzaRuntimeCoordinator.INSTANCE.recover(this);
        overlay.scheduleRefresh();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        dev.denza.apps.feature.adb.AdbRestore.onWifiDialog(this, event);
        CharSequence eventPackage = event.getPackageName();
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && eventPackage != null) {
            SpeakerCoverService.onForegroundPackage(eventPackage.toString());
        }
        if (eventPackage != null && "com.byd.weatherdata".contentEquals(eventPackage)) {
            WeatherAdapterScheduler.onNativeWeatherVisible(this);
        }
        // The dedicated split accessibility service owns stock-picker replacement. It must
        // install its interaction blocker before scheduling the asynchronous shell mutation;
        // forwarding the same event here can win that race without the blocker.
        HudGuidanceAccessibilityMonitor hudMonitor = hudGuidanceMonitor;
        if (hudMonitor != null) {
            hudMonitor.onAccessibilityEvent(event);
        }
        overlay.onAccessibilityEvent(event);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean mediaPress = event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    || code == KeyEvent.KEYCODE_MEDIA_PLAY
                    || code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == 386);
        if (mediaResumeController != null && mediaResumeController.onKeyEvent(
                event, !mediaPress || (mediaButtonEnvironment != null
                        && mediaButtonEnvironment.allowsNewPress()))) {
            return true;
        }
        boolean enabled = NavigationSettings.INSTANCE.steeringWheelButtonEnabled(this);
        boolean consumed = steeringWheelKeyInterceptor.onKeyEvent(
                enabled,
                event.getKeyCode(),
                event.getAction(),
                event.getRepeatCount(),
                () -> DenzaAppRepository.INSTANCE.performNavigationActionFromSteeringWheel(this));
        if (consumed && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            Log.i(TAG, "steering-wheel navigation action accepted");
        }
        return consumed;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        if (instance == this) {
            instance = null;
        }
        steeringWheelKeyInterceptor.reset();
        tearDownMediaResume();
        tearDownHudGuidance();
        overlay.detach();
        StateMarks.INSTANCE.accessibilityChanged("a11y gone");
        DenzaAppRepository.INSTANCE.recoverNavigationSteeringWheelAccess(this);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connected = false;
        if (instance == this) {
            instance = null;
        }
        steeringWheelKeyInterceptor.reset();
        tearDownMediaResume();
        tearDownHudGuidance();
        overlay.detach();
        StateMarks.INSTANCE.accessibilityChanged("a11y gone");
        DenzaAppRepository.INSTANCE.recoverNavigationSteeringWheelAccess(this);
        super.onDestroy();
    }

    public static boolean isConnected() {
        return connected;
    }

    /**
     * The media key's own state, taken from the live controller rather than from a second copy of
     * it. No bound service, or a service without a controller, is reported as absent.
     */
    static MediaKeySnapshot mediaKeySnapshot() {
        SimulcastAccessibilityService service = instance;
        MediaResumeController controller = service == null ? null : service.mediaResumeController;
        Boolean listening = controller == null ? null : Boolean.valueOf(controller.isListening());
        String remembered = controller == null ? null : controller.rememberedPackage();
        return MediaKeyDiagnostics.snapshot(listening, remembered);
    }

    static void requestMediaResumeRefresh() {
        ServiceInstanceHop.post(() -> instance, SimulcastAccessibilityService::postToMain,
                service -> {
                    if (service.mediaResumeController != null) {
                        service.mediaResumeController.start();
                    }
                });
    }

    private void tearDownMediaResume() {
        if (mediaResumeController != null) mediaResumeController.stop();
        mediaResumeController = null;
        mediaButtonEnvironment = null;
    }

    /**
     * The HUD monitor's state belongs to the main thread, and this is also called from the access
     * repair's executor (DenzaAppRepository.setHudGuidanceEnabled after a repair), so it hops there
     * like {@link #requestMediaResumeRefresh()}.
     */
    static void requestHudGuidanceRefresh() {
        ServiceInstanceHop.post(() -> instance, SimulcastAccessibilityService::postToMain,
                service -> {
                    HudGuidanceAccessibilityMonitor monitor = service.hudGuidanceMonitor;
                    if (monitor != null) {
                        monitor.onSettingChanged();
                    }
                });
    }

    private static void postToMain(SimulcastAccessibilityService service, Runnable call) {
        service.handler.post(call);
    }

    private void tearDownHudGuidance() {
        HudGuidanceAccessibilityMonitor monitor = hudGuidanceMonitor;
        hudGuidanceMonitor = null;
        if (monitor != null) {
            monitor.detach();
        }
    }
}
