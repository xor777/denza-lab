package dev.denza.apps;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import dev.denza.disharebridge.DiShareScreens;
import dev.denza.apps.feature.hud.HudGuidanceAccessibilityMonitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Seamless Simulcast overlay driven by accessibility. Watches the native
 * {@code com.byd.dishare/.app.ui.ShareDialogActivity} window, reads its live node
 * bounds via {@link SimulcastDialogGeometry}, and reproduces the native picker with
 * the user's chosen apps so it looks native.
 *
 * <p>Interaction mirrors native: tap an app in the row to select it (its icon shows
 * on the central source screen), then drag from the central screen onto a receiver
 * (HUD / passenger) to start casting. Dragging directly from a row icon also works.
 *
 * <p>Rendering: a full-screen {@code FLAG_NOT_TOUCHABLE} {@link DrawView} paints
 * everything (so every untouched pixel passes through to the native dialog), while
 * small touchable input windows over our icons / the central screen capture gestures.
 * The native row is erased and redrawn so a 3-app selection looks like a native
 * 3-app row, not 3 of 5 stock slots. Geometry is anchored to the stable row
 * container (not the scrolling stock icons), so our row does not jump when the
 * native list scrolls.
 */
public class SimulcastAccessibilityService extends AccessibilityService {
    private static final String TAG = "DenzaSimulcastA11y";
    private static final String DISHARE_PKG = "com.byd.dishare";

    /** Source size so the app renders at native resolution, not 1024x576. */
    private static final int SHARE_VIDEO_WIDTH = 2560;
    private static final int SHARE_VIDEO_HEIGHT = 1440;

    // Native row colours from decompiled DiShare (night theme). We keep the native
    // hue but make our replacement panel opaque so stock app icons never bleed through.
    private static final int DIALOG_BG = Color.rgb(0x15, 0x18, 0x1f);
    private static final int ROW_PANEL = Color.rgb(0x37, 0x3c, 0x49);
    private static final float ROW_CORNER_DP = 20f;

    // Native icon metrics on this layout family: 128px (64dp) icons, 152px (76dp) pitch.
    private static final float ICON_DP = 64f;
    private static final float GAP_DP = 12f;
    private static final float DRAG_SLOP_DP = 12f;
    private static final long REFRESH_DELAY_MS = 16L;
    private static final long GEOMETRY_STABLE_INTERVAL_MS = 100L;
    private static final int GEOMETRY_EPSILON_PX = 2;
    private static final long DIALOG_DISAPPEAR_GRACE_MS = 320L;
    private static final long SCREEN_QUERY_RETRY_MS = 2000L;
    private static final long SCREEN_QUERY_REFRESH_MS = 30000L;
    private static final int ICON_FALLBACK_BG = Color.rgb(0x26, 0x32, 0x40);

    private static volatile boolean connected;
    private static volatile SimulcastAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Target> targetCache = new HashMap<>();
    private final Runnable refreshRunnable = this::refreshSafely;

    private WindowManager windowManager;
    private DrawView drawView;
    private View rowPlateView;
    private CentralIconPlateView centralIconPlateView;
    private final Map<String, SlotView> slotViews = new HashMap<>();
    private View centralView;
    private SimulcastWindowReconciler windowReconciler;
    private final SimulcastGeometryStabilizer<SimulcastDialogGeometry> geometryStabilizer =
            new SimulcastGeometryStabilizer<>(
                    GEOMETRY_STABLE_INTERVAL_MS,
                    GEOMETRY_EPSILON_PX,
                    SimulcastDialogGeometry::equivalentWithin);

    private SimulcastDialogGeometry geometry;
    private final List<Slot> slots = new ArrayList<>();
    private Rect panelBounds;
    private Rect eraseBounds;
    private Rect centralIconBounds;
    private Target selectedTarget;
    private List<String> appliedPackages = Collections.emptyList();
    private Set<String> availableReceivers = Collections.emptySet();
    private boolean screenAvailabilityConfirmed;
    private boolean screenQueryRunning;
    private long lastScreenQueryMs;
    private int screenGeneration;
    private int appliedScreenGeneration = -1;
    private long missingDialogSinceMs;
    private boolean dialogObserved;
    private HudGuidanceAccessibilityMonitor hudGuidanceMonitor;

    // Gesture state shared between input windows and the painter.
    private boolean dragging;
    private Target downTarget;
    private boolean downFromRow;
    private float downX;
    private float downY;
    private Target dragTarget;
    private float dragX;
    private float dragY;
    private String hoverReceiver;

    @Override
    protected void onServiceConnected() {
        connected = true;
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        windowReconciler = new SimulcastWindowReconciler(new OverlayWindowHost());
        hudGuidanceMonitor = new HudGuidanceAccessibilityMonitor(this);
        hudGuidanceMonitor.attach();
        Log.i(TAG, "service connected");
        DenzaAppRepository.INSTANCE.refresh();
        scheduleRefresh();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        HudGuidanceAccessibilityMonitor hudMonitor = hudGuidanceMonitor;
        if (hudMonitor != null) {
            hudMonitor.onAccessibilityEvent(event);
        }
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }
        scheduleRefresh();
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
        handler.removeCallbacks(refreshRunnable);
        tearDownHudGuidance();
        tearDown();
        DenzaAppRepository.INSTANCE.refresh();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connected = false;
        if (instance == this) {
            instance = null;
        }
        handler.removeCallbacks(refreshRunnable);
        tearDownHudGuidance();
        tearDown();
        DenzaAppRepository.INSTANCE.refresh();
        super.onDestroy();
    }

    static boolean isConnected() {
        return connected;
    }

    static void requestHudGuidanceRefresh() {
        SimulcastAccessibilityService service = instance;
        if (service == null) {
            return;
        }
        HudGuidanceAccessibilityMonitor monitor = service.hudGuidanceMonitor;
        if (monitor != null) {
            monitor.onSettingChanged();
        }
    }

    private void tearDownHudGuidance() {
        HudGuidanceAccessibilityMonitor monitor = hudGuidanceMonitor;
        hudGuidanceMonitor = null;
        if (monitor != null) {
            monitor.detach();
        }
    }

    private void scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, REFRESH_DELAY_MS);
    }

    private void refreshSafely() {
        try {
            refresh();
        } catch (RuntimeException error) {
            Log.e(TAG, "overlay refresh failed", error);
            tearDown();
            handler.postDelayed(refreshRunnable, SCREEN_QUERY_RETRY_MS);
        }
    }

    private void refresh() {
        AccessibilityNodeInfo root = findDiShareRoot();
        // Mid-drag: keep the current overlay through transient accessibility gaps.
        // Only a dialog missing for the full close grace cancels the gesture.
        if (dragging) {
            if (root == null) {
                long now = SystemClock.elapsedRealtime();
                if (missingDialogSinceMs == 0L) {
                    missingDialogSinceMs = now;
                }
                long elapsed = now - missingDialogSinceMs;
                if (elapsed < DIALOG_DISAPPEAR_GRACE_MS) {
                    handler.postDelayed(
                            refreshRunnable,
                            DIALOG_DISAPPEAR_GRACE_MS - elapsed);
                    return;
                }
                cancelDrag();
                tearDown();
            } else {
                missingDialogSinceMs = 0L;
                root.recycle();
            }
            return;
        }
        if (!SimulcastIntegration.isEnabled(this)) {
            if (root != null) {
                root.recycle();
            }
            tearDown();
            return;
        }
        SimulcastDialogGeometry geo = SimulcastDialogGeometry.from(root);
        if (root != null) {
            root.recycle();
        }
        if (geo == null || !geo.isAppPickerOpen()) {
            long now = SystemClock.elapsedRealtime();
            if (dialogObserved) {
                if (missingDialogSinceMs == 0L) {
                    missingDialogSinceMs = now;
                }
                long elapsed = now - missingDialogSinceMs;
                if (elapsed < DIALOG_DISAPPEAR_GRACE_MS) {
                    handler.postDelayed(
                            refreshRunnable,
                            DIALOG_DISAPPEAR_GRACE_MS - elapsed);
                    return;
                }
            }
            tearDown();
            return;
        }
        missingDialogSinceMs = 0L;
        boolean newDialog = !dialogObserved;
        if (newDialog) {
            dialogObserved = true;
            availableReceivers = Collections.emptySet();
            screenAvailabilityConfirmed = false;
            lastScreenQueryMs = 0L;
            screenGeneration++;
        }
        ensureScreenAvailability();
        SimulcastScreenDiagnostics.recordAccessibilityLayout(
                geo.receivers, availableReceivers, screenAvailabilityConfirmed);
        List<String> selectedPackages = selectedPackages();
        boolean compositionChanged = !selectedPackages.equals(appliedPackages);

        SimulcastDialogGeometry stableGeometry =
                geometryStabilizer.offer(SystemClock.elapsedRealtime(), geo);
        if (stableGeometry == null) {
            // Receiver availability is independent from window geometry. Update its
            // visual state immediately when the already-applied geometry is still
            // stable, without moving or rebuilding any windows.
            if (geometry != null
                    && geo.equivalentWithin(geometry, GEOMETRY_EPSILON_PX)
                    && compositionChanged) {
                rebuild(geometry, selectedPackages);
                reconcileInputWindows();
                appliedPackages = selectedPackages;
                invalidateOverlayViews();
            }
            if (geometry != null
                    && geo.equivalentWithin(geometry, GEOMETRY_EPSILON_PX)
                    && appliedScreenGeneration != screenGeneration) {
                appliedScreenGeneration = screenGeneration;
                invalidateOverlayViews();
            }
            handler.removeCallbacks(refreshRunnable);
            handler.postDelayed(refreshRunnable, GEOMETRY_STABLE_INTERVAL_MS);
            return;
        }

        boolean geometryChanged = !stableGeometry.sameLayoutAs(geometry);
        if (geometryChanged || compositionChanged || drawView == null) {
            geometry = stableGeometry;
            rebuild(stableGeometry, selectedPackages);
            reconcileInputWindows();
            appliedPackages = selectedPackages;
        }
        appliedScreenGeneration = screenGeneration;
        invalidateOverlayViews();
    }

    private void ensureScreenAvailability() {
        long now = System.currentTimeMillis();
        long minimumDelay = screenAvailabilityConfirmed
                ? SCREEN_QUERY_REFRESH_MS : SCREEN_QUERY_RETRY_MS;
        if (screenQueryRunning || now - lastScreenQueryMs < minimumDelay) {
            return;
        }
        screenQueryRunning = true;
        lastScreenQueryMs = now;
        DiShareScreens.query(this, DISHARE_PKG, new DiShareScreens.Callback() {
            @Override
            public void onScreens(List<DiShareScreens.Screen> screens) {
                SimulcastScreenDiagnostics.recordDiShareScreens(screens);
                HashSet<String> ids = new HashSet<>();
                for (DiShareScreens.Screen screen : screens) {
                    if (screen.available && screen.screenId != null) {
                        ids.add(screen.screenId);
                    }
                }
                availableReceivers = Collections.unmodifiableSet(ids);
                screenAvailabilityConfirmed = true;
                screenQueryRunning = false;
                screenGeneration++;
                if (geometry != null) {
                    SimulcastScreenDiagnostics.recordAccessibilityLayout(
                            geometry.receivers, availableReceivers, screenAvailabilityConfirmed);
                }
                Log.i(TAG, "screens=" + screens
                        + " available receivers=" + availableReceivers);
                scheduleRefresh();
            }

            @Override
            public void onFailed(String message) {
                SimulcastScreenDiagnostics.recordDiShareFailure(message);
                screenQueryRunning = false;
                if (!screenAvailabilityConfirmed) {
                    availableReceivers = Collections.emptySet();
                }
                screenGeneration++;
                Log.w(TAG, "screen query failed " + message);
                handler.removeCallbacks(refreshRunnable);
                handler.postDelayed(refreshRunnable, SCREEN_QUERY_RETRY_MS);
            }
        });
    }

    private AccessibilityNodeInfo findDiShareRoot() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) {
            return null;
        }
        for (AccessibilityWindowInfo w : windows) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) {
                continue;
            }
            CharSequence pkg = root.getPackageName();
            if (pkg != null && DISHARE_PKG.contentEquals(pkg)) {
                return root;
            }
            root.recycle();
        }
        return null;
    }

    /**
     * Lay out the selected apps as a centered row anchored to the stable row
     * container, with fixed native-sized icons, plus the central preview icon and the
     * erase/panel regions. Nothing here reads the scrolling stock icons.
     */
    private List<String> selectedPackages() {
        List<String> selected = SimulcastApps.getSelected(this);
        int count = Math.min(selected.size(), SimulcastApps.MAX_SELECTED);
        return Collections.unmodifiableList(new ArrayList<>(selected.subList(0, count)));
    }

    private void rebuild(SimulcastDialogGeometry geo, List<String> selected) {
        slots.clear();
        panelBounds = null;
        eraseBounds = null;
        centralIconBounds = null;
        if (!geo.isAppPickerOpen() || geo.appList == null) {
            return;
        }
        int count = selected.size();
        if (count == 0) {
            return;
        }
        int icon = dp(ICON_DP);
        int gap = dp(GAP_DP);
        int maxRowWidth = Math.max(dp(180), geo.appList.width() - dp(48));
        int totalWidth = count * icon + (count - 1) * gap;
        if (totalWidth > maxRowWidth) {
            gap = dp(8);
            icon = Math.max(dp(46), (maxRowWidth - (count - 1) * gap) / count);
            totalWidth = count * icon + (count - 1) * gap;
        }
        int left = geo.appList.centerX() - totalWidth / 2;
        int top = geo.appList.centerY() - icon / 2;
        for (int i = 0; i < count; i++) {
            Target target = targetFor(selected.get(i));
            slots.add(new Slot(target, new Rect(left, top, left + icon, top + icon)));
            left += icon + gap;
        }
        if (selectedTarget == null || !selected.contains(selectedTarget.packageName)) {
            selectedTarget = slots.get(0).target;
        }
        int padX = Math.round(icon * 0.18f);
        int padY = Math.round(icon * 0.20f);
        Rect first = slots.get(0).bounds;
        Rect last = slots.get(slots.size() - 1).bounds;
        Rect compactPanel = new Rect(first.left - padX, first.top - padY,
                last.right + padX, first.bottom + padY);
        // Cover the native row container as one opaque plate. This avoids all
        // package-specific icon tricks: transparent launcher icons can only show our
        // plate, never the stock DiShare icons underneath.
        panelBounds = new Rect(geo.appList);
        panelBounds.union(compactPanel);
        panelBounds.inset(-dp(8), -dp(8));
        eraseBounds = new Rect(geo.appList);
        eraseBounds.union(panelBounds);
        eraseBounds.inset(-dp(20), -dp(14));

        if (geo.centralContent != null) {
            // This is the actual native screen_card_view inside the blue selected
            // frame. Using it directly keeps every overlay pixel within the frame.
            centralIconBounds = new Rect(geo.centralContent);
        } else if (geo.central != null) {
            // Cover the inner screen area of the native central card (leaving its frame
            // visible). This percentage fallback is only for unknown layout families.
            Rect c = geo.central;
            int insetX = Math.round(c.width() * 0.065f);
            int topInset = Math.round(c.height() * 0.11f);
            int bottomInset = Math.round(c.height() * 0.073f);
            centralIconBounds = new Rect(c.left + insetX, c.top + topInset,
                    c.right - insetX, c.bottom - bottomInset);
        }
    }

    private void ensureDrawView() {
        if (drawView != null) {
            return;
        }
        drawView = new DrawView(this);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        try {
            windowManager.addView(drawView, params);
        } catch (RuntimeException e) {
            Log.w(TAG, "add draw overlay failed", e);
            drawView = null;
        }
    }

    private void reconcileInputWindows() {
        if (windowManager == null || windowReconciler == null) {
            return;
        }
        List<SimulcastWindowReconciler.WindowSpec> plan = new ArrayList<>();
        if (panelBounds != null && !slots.isEmpty()) {
            plan.add(windowSpec(
                    "row",
                    SimulcastWindowReconciler.Kind.ROW_PLATE,
                    panelBounds));
        }
        if (centralIconBounds != null && selectedTarget != null) {
            plan.add(windowSpec(
                    "central-icon",
                    SimulcastWindowReconciler.Kind.CENTRAL_ICON,
                    centralIconBounds));
        }
        for (Slot slot : slots) {
            plan.add(windowSpec(
                    slotId(slot.target.packageName),
                    SimulcastWindowReconciler.Kind.SLOT,
                    slot.bounds));
        }
        if (centralIconBounds != null && geometry != null && geometry.central != null
                && !slots.isEmpty()) {
            plan.add(windowSpec(
                    "central-touch",
                    SimulcastWindowReconciler.Kind.CENTRAL_TOUCH,
                    geometry.central));
        }
        windowReconciler.apply(plan);
        if (centralIconPlateView != null) {
            centralIconPlateView.showTarget(selectedTarget);
        }
    }

    private void raiseDragLayer() {
        if (drawView != null) {
            removeView(drawView);
            drawView = null;
        }
        ensureDrawView();
    }

    private SimulcastWindowReconciler.WindowSpec windowSpec(
            String id,
            SimulcastWindowReconciler.Kind kind,
            Rect bounds) {
        return new SimulcastWindowReconciler.WindowSpec(
                id,
                kind,
                bounds.left,
                bounds.top,
                bounds.width(),
                bounds.height());
    }

    private String slotId(String packageName) {
        return "slot:" + packageName;
    }

    private Slot findSlot(String id) {
        for (Slot slot : slots) {
            if (slotId(slot.target.packageName).equals(id)) {
                return slot;
            }
        }
        return null;
    }

    private void addPlateWindow(View view, Rect bounds, int format) {
        // IMPORTANT: the plate must be TOUCHABLE. BYD firmware force-dims
        // FLAG_NOT_TOUCHABLE overlays to alpha 0.8 (ignoring an explicit alpha=1),
        // which let the stock icons bleed through. A touchable window stays opaque;
        // its view consumes touches so the native row underneath can't be scrolled.
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                bounds.width(),
                bounds.height(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                format);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = bounds.left;
        params.y = bounds.top;
        params.alpha = 1f;
        try {
            windowManager.addView(view, params);
        } catch (RuntimeException e) {
            Log.w(TAG, "add plate window failed", e);
        }
    }

    private void addInputWindow(View view, Rect bounds) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                bounds.width(),
                bounds.height(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = bounds.left;
        params.y = bounds.top;
        try {
            windowManager.addView(view, params);
        } catch (RuntimeException e) {
            Log.w(TAG, "add input window failed", e);
        }
    }

    private void updateWindow(View view, SimulcastWindowReconciler.WindowSpec spec) {
        if (view == null) {
            return;
        }
        try {
            WindowManager.LayoutParams params =
                    (WindowManager.LayoutParams) view.getLayoutParams();
            if (params == null) {
                return;
            }
            params.width = spec.width;
            params.height = spec.height;
            params.x = spec.left;
            params.y = spec.top;
            windowManager.updateViewLayout(view, params);
        } catch (RuntimeException error) {
            Log.w(TAG, "update overlay window failed id=" + spec.id, error);
        }
    }

    private void removeInputWindows() {
        if (rowPlateView != null) {
            removeView(rowPlateView);
            rowPlateView = null;
        }
        if (centralIconPlateView != null) {
            removeView(centralIconPlateView);
            centralIconPlateView = null;
        }
        for (SlotView v : slotViews.values()) {
            removeView(v);
        }
        slotViews.clear();
        if (centralView != null) {
            removeView(centralView);
            centralView = null;
        }
    }

    private final class OverlayWindowHost implements SimulcastWindowReconciler.Host {
        @Override
        public void add(SimulcastWindowReconciler.WindowSpec spec) {
            Rect bounds = new Rect(
                    spec.left,
                    spec.top,
                    spec.left + spec.width,
                    spec.top + spec.height);
            switch (spec.kind) {
                case ROW_PLATE:
                    rowPlateView = new RowPlateView(SimulcastAccessibilityService.this);
                    // Translucent corners let the native dialog body show through.
                    addPlateWindow(rowPlateView, bounds, PixelFormat.TRANSLUCENT);
                    break;
                case CENTRAL_ICON:
                    centralIconPlateView =
                            new CentralIconPlateView(SimulcastAccessibilityService.this);
                    centralIconPlateView.showTarget(selectedTarget);
                    addPlateWindow(
                            centralIconPlateView,
                            bounds,
                            PixelFormat.TRANSLUCENT);
                    break;
                case SLOT:
                    Slot slot = findSlot(spec.id);
                    if (slot != null) {
                        SlotView view =
                                new SlotView(SimulcastAccessibilityService.this, slot.target);
                        addInputWindow(view, bounds);
                        slotViews.put(spec.id, view);
                    }
                    break;
                case CENTRAL_TOUCH:
                    centralView = new CentralView(SimulcastAccessibilityService.this);
                    addInputWindow(centralView, bounds);
                    break;
            }
        }

        @Override
        public void update(SimulcastWindowReconciler.WindowSpec spec) {
            switch (spec.kind) {
                case ROW_PLATE:
                    updateWindow(rowPlateView, spec);
                    break;
                case CENTRAL_ICON:
                    updateWindow(centralIconPlateView, spec);
                    break;
                case SLOT:
                    updateWindow(slotViews.get(spec.id), spec);
                    break;
                case CENTRAL_TOUCH:
                    updateWindow(centralView, spec);
                    break;
            }
        }

        @Override
        public void remove(SimulcastWindowReconciler.WindowSpec spec) {
            switch (spec.kind) {
                case ROW_PLATE:
                    removeView(rowPlateView);
                    rowPlateView = null;
                    break;
                case CENTRAL_ICON:
                    removeView(centralIconPlateView);
                    centralIconPlateView = null;
                    break;
                case SLOT:
                    SlotView slotView = slotViews.remove(spec.id);
                    if (slotView != null) {
                        removeView(slotView);
                    }
                    break;
                case CENTRAL_TOUCH:
                    removeView(centralView);
                    centralView = null;
                    break;
            }
        }

        @Override
        public void raiseDrawLayer() {
            SimulcastAccessibilityService.this.raiseDragLayer();
        }
    }

    private void removeView(View v) {
        if (v == null || windowManager == null) {
            return;
        }
        try {
            windowManager.removeView(v);
        } catch (RuntimeException ignored) {
        }
    }

    private void tearDown() {
        removeInputWindows();
        if (windowReconciler != null) {
            windowReconciler.reset();
        }
        if (drawView != null) {
            removeView(drawView);
            drawView = null;
        }
        slots.clear();
        geometry = null;
        panelBounds = null;
        eraseBounds = null;
        centralIconBounds = null;
        appliedPackages = Collections.emptyList();
        missingDialogSinceMs = 0L;
        dialogObserved = false;
        geometryStabilizer.reset();
        appliedScreenGeneration = -1;
        dragging = false;
        downTarget = null;
        dragTarget = null;
        hoverReceiver = null;
    }

    private void invalidateOverlayViews() {
        if (drawView != null) {
            drawView.invalidate();
        }
        if (rowPlateView != null) {
            rowPlateView.invalidate();
        }
        if (centralIconPlateView != null) {
            centralIconPlateView.invalidate();
        }
    }

    // ----- gesture handling (called from input windows) -----

    private void onDown(Target target, boolean fromRow, float rawX, float rawY) {
        downTarget = target;
        downFromRow = fromRow;
        downX = rawX;
        downY = rawY;
        dragging = false;
        dragTarget = null;
        hoverReceiver = null;
    }

    private void onMove(float rawX, float rawY) {
        if (!dragging) {
            float slop = dp(DRAG_SLOP_DP);
            if (Math.hypot(rawX - downX, rawY - downY) < slop) {
                return;
            }
            dragging = true;
            dragTarget = downTarget;
        }
        dragX = rawX;
        dragY = rawY;
        hoverReceiver = geometry == null ? null
                : geometry.receiverAt(rawX, rawY, availableReceivers);
        invalidateOverlayViews();
    }

    private void onUp(float rawX, float rawY) {
        if (dragging) {
            String receiver = geometry == null ? null
                    : geometry.receiverAt(rawX, rawY, availableReceivers);
            Target target = dragTarget;
            cancelDrag();
            if (receiver != null && target != null) {
                launch(target, receiver);
                tearDown();
                return;
            }
        }
        cancelDrag();
        invalidateOverlayViews();
    }

    private void cancelDrag() {
        dragging = false;
        downTarget = null;
        dragTarget = null;
        hoverReceiver = null;
    }

    private void launch(Target target, String receiver) {
        Log.i(TAG, "cast " + target.packageName + " -> " + receiver);
        SimulcastOverlayService.startTarget(this, target.packageName, receiver,
                SHARE_VIDEO_WIDTH, SHARE_VIDEO_HEIGHT);
    }

    private Target targetFor(String packageName) {
        Target cached = targetCache.get(packageName);
        if (cached != null) {
            return cached;
        }
        Drawable icon = loadIcon(packageName);
        Target target = new Target(
                packageName,
                loadLabel(packageName),
                icon,
                extractAccentColor(icon));
        targetCache.put(packageName, target);
        return target;
    }

    private int extractAccentColor(Drawable icon) {
        if (icon == null) {
            return Color.rgb(0x38, 0x78, 0xa8);
        }
        final int sampleSize = 32;
        Bitmap bitmap = Bitmap.createBitmap(
                sampleSize,
                sampleSize,
                Bitmap.Config.ARGB_8888);
        Rect oldBounds = icon.copyBounds();
        try {
            Canvas sample = new Canvas(bitmap);
            icon.setBounds(0, 0, sampleSize, sampleSize);
            icon.draw(sample);
            double red = 0;
            double green = 0;
            double blue = 0;
            double totalWeight = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < sampleSize; y++) {
                for (int x = 0; x < sampleSize; x++) {
                    int color = bitmap.getPixel(x, y);
                    int alpha = Color.alpha(color);
                    if (alpha < 48) {
                        continue;
                    }
                    Color.colorToHSV(color, hsv);
                    if (hsv[1] < 0.08f || hsv[2] < 0.10f) {
                        continue;
                    }
                    double weight = (alpha / 255.0)
                            * (0.35 + hsv[1] * 1.65)
                            * (0.55 + hsv[2] * 0.45);
                    red += Color.red(color) * weight;
                    green += Color.green(color) * weight;
                    blue += Color.blue(color) * weight;
                    totalWeight += weight;
                }
            }
            if (totalWeight < 1.0) {
                return Color.rgb(0x38, 0x78, 0xa8);
            }
            int sampled = Color.rgb(
                    (int) Math.round(red / totalWeight),
                    (int) Math.round(green / totalWeight),
                    (int) Math.round(blue / totalWeight));
            Color.colorToHSV(sampled, hsv);
            hsv[1] = Math.max(0.48f, hsv[1]);
            hsv[2] = Math.max(0.52f, Math.min(0.78f, hsv[2]));
            return Color.HSVToColor(hsv);
        } catch (RuntimeException error) {
            Log.w(TAG, "icon colour sampling failed", error);
            return Color.rgb(0x38, 0x78, 0xa8);
        } finally {
            icon.setBounds(oldBounds);
            bitmap.recycle();
        }
    }

    private int tone(int color, float saturation, float value) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(0f, Math.min(1f, saturation));
        hsv[2] = Math.max(0f, Math.min(1f, value));
        return Color.HSVToColor(hsv);
    }

    private Drawable loadIcon(String packageName) {
        try {
            return getPackageManager().getApplicationIcon(packageName).mutate();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void localRect(RectF out, Rect bounds, Rect origin) {
        out.set(bounds);
        out.offset(-origin.left, -origin.top);
    }

    private String loadLabel(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void drawIcon(Canvas canvas, Paint fill, Paint text, Target target, RectF bounds,
            float alpha) {
        // Draw the real launcher icon directly — it already has its own shape, like the
        // native row. The opaque plate behind provides the background, so no tile,
        // border or corner-clipping is needed.
        if (target != null && target.icon != null) {
            target.icon.setBounds(Math.round(bounds.left), Math.round(bounds.top),
                    Math.round(bounds.right), Math.round(bounds.bottom));
            target.icon.setAlpha(Math.round(alpha * 255f));
            target.icon.draw(canvas);
            target.icon.setAlpha(255);
            return;
        }
        // Fallback only when a launcher icon is unavailable.
        int save = canvas.saveLayerAlpha(bounds, Math.round(alpha * 255f));
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(ICON_FALLBACK_BG);
        float radius = bounds.width() * 0.22f;
        canvas.drawRoundRect(bounds, radius, radius, fill);
        text.setColor(Color.WHITE);
        text.setTextSize(bounds.height() * 0.45f);
        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = bounds.centerY() - (fm.ascent + fm.descent) / 2f;
        String label = target == null ? "?" : target.label;
        String letter = label.isEmpty()
                ? "?"
                : label.substring(0, 1).toUpperCase(Locale.getDefault());
        canvas.drawText(letter, bounds.centerX(), baseline, text);
        canvas.restoreToCount(save);
    }

    private static final class Target {
        final String packageName;
        final String label;
        final Drawable icon;
        final int accentColor;

        Target(String packageName, String label, Drawable icon, int accentColor) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.accentColor = accentColor;
        }
    }

    private static final class Slot {
        final Target target;
        final Rect bounds;

        Slot(Target target, Rect bounds) {
            this.target = target;
            this.bounds = bounds;
        }
    }

    /** Full-screen painter. Never touchable, so the native dialog stays usable. */
    private final class DrawView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint screenText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF dragBounds = new RectF();
        private final RectF hintBounds = new RectF();

        DrawView(Context context) {
            super(context);
            setWillNotDraw(false);
            iconText.setTypeface(Typeface.DEFAULT_BOLD);
            iconText.setTextAlign(Paint.Align.CENTER);
            screenText.setColor(Color.argb(220, 224, 232, 236));
            screenText.setTextAlign(Paint.Align.CENTER);
            screenText.setTypeface(Typeface.DEFAULT_BOLD);
            screenText.setTextSize(dp(18));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // Drop-zone hints while dragging.
            if (dragging && geometry != null) {
                for (Map.Entry<String, Rect> entry
                        : geometry.availableReceiverBounds(availableReceivers).entrySet()) {
                    drawHint(canvas, entry.getValue(), entry.getKey().equals(hoverReceiver));
                }
                if (!screenAvailabilityConfirmed) {
                    drawScreenCheck(canvas);
                }
            }
            // Floating dragged icon.
            if (dragging && dragTarget != null) {
                float size = dp(86);
                dragBounds.set(
                        dragX - size / 2f,
                        dragY - size / 2f,
                        dragX + size / 2f,
                        dragY + size / 2f);
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(Color.argb(70, 0, 0, 0));
                canvas.drawCircle(dragX + dp(3), dragY + dp(5), size * 0.55f, fill);
                drawIcon(canvas, fill, iconText, dragTarget, dragBounds, 0.96f);
            }
        }

        private void drawHint(Canvas canvas, Rect target, boolean active) {
            if (target == null) {
                return;
            }
            hintBounds.set(target);
            hintBounds.inset(-dp(10), -dp(10));
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(active ? Color.argb(70, 31, 194, 142) : Color.argb(20, 255, 255, 255));
            canvas.drawRoundRect(hintBounds, dp(14), dp(14), fill);
            if (active) {
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(dp(2));
                fill.setColor(Color.argb(210, 31, 194, 142));
                canvas.drawRoundRect(hintBounds, dp(14), dp(14), fill);
            }
        }

        private void drawScreenCheck(Canvas canvas) {
            if (geometry == null || geometry.central == null) {
                return;
            }
            canvas.drawText("Проверяю экраны…", geometry.central.centerX(),
                    geometry.central.bottom + dp(28), screenText);
        }
    }

    /** Opaque window that fully covers the native DiShare app list. */
    private final class RowPlateView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF viewBounds = new RectF();
        private final RectF iconBounds = new RectF();

        RowPlateView(Context context) {
            super(context);
            setWillNotDraw(false);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            viewBounds.set(0f, 0f, width, height);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // Only the rounded panel — the window is translucent, so the corners stay
            // transparent (no dark squares) and the dialog body shows through there.
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(ROW_PANEL);
            float r = dp(ROW_CORNER_DP);
            canvas.drawRoundRect(viewBounds, r, r, fill);
            if (panelBounds == null) {
                return;
            }
            for (Slot slot : slots) {
                float alpha = (dragging && downFromRow && slot.target == dragTarget) ? 0.35f : 1f;
                localRect(iconBounds, slot.bounds, panelBounds);
                drawIcon(canvas, fill, text, slot.target, iconBounds, alpha);
            }
        }
    }

    /** Opaque source-app icon on the central screen. */
    private final class CentralIconPlateView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF cardBounds = new RectF();
        private final RectF iconBounds = new RectF();
        private Target displayedTarget;
        private Shader cardShader;

        CentralIconPlateView(Context context) {
            super(context);
            setWillNotDraw(false);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextAlign(Paint.Align.CENTER);
        }

        void showTarget(Target target) {
            displayedTarget = target;
            int accent = target == null
                    ? Color.rgb(0x38, 0x78, 0xa8)
                    : target.accentColor;
            GradientDrawable background = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[] {
                            tone(accent, 0.62f, 0.42f),
                            tone(accent, 0.72f, 0.16f),
                    });
            background.setCornerRadius(dp(10));
            background.setStroke(dp(1), Color.argb(42, 255, 255, 255));
            setBackground(background);
            rebuildSizeDependentDrawing();
            invalidate();
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            rebuildSizeDependentDrawing();
        }

        private void rebuildSizeDependentDrawing() {
            int width = getWidth();
            int height = getHeight();
            cardBounds.set(0f, 0f, width, height);
            if (width <= 0 || height <= 0) {
                cardShader = null;
                iconBounds.setEmpty();
                return;
            }
            int accent = displayedTarget == null
                    ? Color.rgb(0x38, 0x78, 0xa8)
                    : displayedTarget.accentColor;
            cardShader = new RadialGradient(
                    width * 0.52f,
                    height * 0.48f,
                    Math.min(width, height) * 0.62f,
                    Color.argb(92, Color.red(accent), Color.green(accent), Color.blue(accent)),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP);
            float size = Math.min(width, height) * 0.56f;
            float left = (width - size) / 2f;
            float top = (height - size) / 2f;
            iconBounds.set(left, top, left + size, top + size);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // Use a restrained palette derived from the current app icon. The darker
            // tones keep every icon readable while making the source card feel like a
            // deliberate app-specific surface instead of a black placeholder.
            fill.setStyle(Paint.Style.FILL);
            float r = dp(10);
            fill.setShader(cardShader);
            canvas.drawRoundRect(cardBounds, r, r, fill);
            fill.setShader(null);
            if (displayedTarget == null) {
                return;
            }
            float alpha = (dragging && !downFromRow) ? 0.4f : 1f;
            drawIcon(canvas, fill, text, displayedTarget, iconBounds, alpha);
        }
    }

    /** Small transparent input window over one row icon: tap to select, drag to cast. */
    private final class SlotView extends View {
        private final Target target;

        SlotView(Context context, Target target) {
            super(context);
            this.target = target;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    onDown(target, true, event.getRawX(), event.getRawY());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    onMove(event.getRawX(), event.getRawY());
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean wasDragging = dragging;
                    onUp(event.getRawX(), event.getRawY());
                    if (!wasDragging) {
                        performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    cancelDrag();
                    invalidateOverlayViews();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            selectedTarget = target;
            if (centralIconPlateView != null) {
                centralIconPlateView.showTarget(selectedTarget);
            }
            invalidateOverlayViews();
            return true;
        }
    }

    /** Input window over the central source screen: drag the selected app to a receiver. */
    private final class CentralView extends View {
        CentralView(Context context) {
            super(context);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    onDown(selectedTarget, false, event.getRawX(), event.getRawY());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    onMove(event.getRawX(), event.getRawY());
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean wasDragging = dragging;
                    onUp(event.getRawX(), event.getRawY());
                    if (!wasDragging) {
                        performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    cancelDrag();
                    invalidateOverlayViews();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
