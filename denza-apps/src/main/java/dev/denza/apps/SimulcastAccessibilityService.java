package dev.denza.apps;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    // Dark fill for the central card's inner screen, covering the stock preview.
    private static final int CENTRAL_BG = Color.rgb(0x20, 0x24, 0x2c);
    private static final float ROW_CORNER_DP = 20f;

    // Native icon metrics on this layout family: 128px (64dp) icons, 152px (76dp) pitch.
    private static final float ICON_DP = 64f;
    private static final float GAP_DP = 12f;
    private static final float DRAG_SLOP_DP = 12f;
    private static final int ICON_FALLBACK_BG = Color.rgb(0x26, 0x32, 0x40);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Target> targetCache = new HashMap<>();
    private final Runnable refreshRunnable = this::refresh;

    private WindowManager windowManager;
    private DrawView drawView;
    private View rowPlateView;
    private View centralIconPlateView;
    private final List<SlotView> slotViews = new ArrayList<>();
    private View centralView;

    private SimulcastDialogGeometry geometry;
    private final List<Slot> slots = new ArrayList<>();
    private Rect panelBounds;
    private Rect eraseBounds;
    private Rect centralIconBounds;
    private Target selectedTarget;

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
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        Log.i(TAG, "service connected");
        scheduleRefresh();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
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
    public void onDestroy() {
        handler.removeCallbacks(refreshRunnable);
        tearDown();
        super.onDestroy();
    }

    private void scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, 60L);
    }

    private void refresh() {
        AccessibilityNodeInfo root = findDiShareRoot();
        // Mid-drag: keep the current overlay; only a vanished dialog cancels it.
        if (dragging) {
            if (root == null) {
                cancelDrag();
                tearDown();
            } else {
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
            tearDown();
            return;
        }
        // Ignore events from our own overlay windows / native row scrolling: only
        // re-apply when the dialog's stable geometry actually changed.
        if (drawView != null && geo.sameAs(geometry)) {
            return;
        }
        geometry = geo;
        rebuild(geo);
        layoutInputWindows();
        if (drawView != null) {
            drawView.invalidate();
        }
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
    private void rebuild(SimulcastDialogGeometry geo) {
        slots.clear();
        panelBounds = null;
        eraseBounds = null;
        centralIconBounds = null;
        if (!geo.isAppPickerOpen() || geo.appList == null) {
            return;
        }
        List<String> selected = SimulcastApps.getSelected(this);
        int count = Math.min(selected.size(), SimulcastApps.MAX_SELECTED);
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

        if (geo.central != null) {
            // Cover the inner screen area of the native central card (leaving its frame
            // visible) so the stock preview is replaced by the selected app's icon.
            Rect c = geo.central;
            int insetX = Math.round(c.width() * 0.065f);
            int topInset = Math.round(c.height() * 0.10f);
            int bottomInset = Math.round(c.height() * 0.03f);
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

    private void layoutInputWindows() {
        removeInputWindows();
        if (windowManager == null) {
            return;
        }
        if (panelBounds != null && !slots.isEmpty()) {
            rowPlateView = new RowPlateView(this);
            // Translucent (not OPAQUE) so the rounded panel's corners are transparent;
            // the plate is touchable, so the firmware keeps it fully opaque anyway.
            addPlateWindow(rowPlateView, panelBounds, PixelFormat.TRANSLUCENT);
        }
        if (centralIconBounds != null && selectedTarget != null) {
            centralIconPlateView = new CentralIconPlateView(this);
            addPlateWindow(centralIconPlateView, centralIconBounds, PixelFormat.TRANSLUCENT);
        }
        for (Slot slot : slots) {
            SlotView view = new SlotView(this, slot.target);
            addInputWindow(view, slot.bounds);
            slotViews.add(view);
        }
        if (centralIconBounds != null && geometry != null && geometry.central != null
                && !slots.isEmpty()) {
            centralView = new CentralView(this);
            addInputWindow(centralView, geometry.central);
        }
        // The drag ghost / drop hints must sit above the plates, so (re-)add the
        // full-screen draw layer last on every relayout.
        raiseDragLayer();
    }

    private void raiseDragLayer() {
        if (drawView != null) {
            removeView(drawView);
            drawView = null;
        }
        ensureDrawView();
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

    private void removeInputWindows() {
        if (rowPlateView != null) {
            removeView(rowPlateView);
            rowPlateView = null;
        }
        if (centralIconPlateView != null) {
            removeView(centralIconPlateView);
            centralIconPlateView = null;
        }
        for (SlotView v : slotViews) {
            removeView(v);
        }
        slotViews.clear();
        if (centralView != null) {
            removeView(centralView);
            centralView = null;
        }
    }

    private void removeView(View v) {
        try {
            windowManager.removeView(v);
        } catch (RuntimeException ignored) {
        }
    }

    private void tearDown() {
        removeInputWindows();
        if (drawView != null) {
            removeView(drawView);
            drawView = null;
        }
        slots.clear();
        geometry = null;
        panelBounds = null;
        eraseBounds = null;
        centralIconBounds = null;
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
        hoverReceiver = geometry == null ? null : geometry.receiverAt(rawX, rawY);
        invalidateOverlayViews();
    }

    private void onUp(float rawX, float rawY) {
        if (dragging) {
            String receiver = geometry == null ? null : geometry.receiverAt(rawX, rawY);
            Target target = dragTarget;
            cancelDrag();
            if (receiver != null && target != null) {
                launch(target, receiver);
                tearDown();
                return;
            }
        } else if (downFromRow && downTarget != null) {
            // Tap on a row icon selects it; its icon appears on the central screen.
            selectedTarget = downTarget;
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
        Target target = new Target(packageName, loadLabel(packageName), loadIcon(packageName));
        targetCache.put(packageName, target);
        return target;
    }

    private Drawable loadIcon(String packageName) {
        try {
            return getPackageManager().getApplicationIcon(packageName).mutate();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private RectF localRect(Rect bounds, Rect origin) {
        RectF out = new RectF(bounds);
        out.offset(-origin.left, -origin.top);
        return out;
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
        String letter = label.isEmpty() ? "?" : label.substring(0, 1).toUpperCase();
        canvas.drawText(letter, bounds.centerX(), baseline, text);
        canvas.restoreToCount(save);
    }

    private static final class Target {
        final String packageName;
        final String label;
        final Drawable icon;

        Target(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
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

        DrawView(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // Drop-zone hints while dragging.
            if (dragging && geometry != null) {
                drawHint(canvas, geometry.hud, "screen_hud".equals(hoverReceiver));
                drawHint(canvas, geometry.fse, "screen_fse".equals(hoverReceiver));
            }
            // Floating dragged icon.
            if (dragging && dragTarget != null) {
                float size = dp(86);
                RectF b = new RectF(dragX - size / 2f, dragY - size / 2f,
                        dragX + size / 2f, dragY + size / 2f);
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(Color.argb(70, 0, 0, 0));
                canvas.drawCircle(dragX + dp(3), dragY + dp(5), size * 0.55f, fill);
                Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
                text.setTypeface(Typeface.DEFAULT_BOLD);
                text.setTextAlign(Paint.Align.CENTER);
                drawIcon(canvas, fill, text, dragTarget, b, 0.96f);
            }
        }

        private void drawHint(Canvas canvas, Rect target, boolean active) {
            if (target == null) {
                return;
            }
            RectF b = new RectF(target);
            b.inset(-dp(10), -dp(10));
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(active ? Color.argb(70, 31, 194, 142) : Color.argb(20, 255, 255, 255));
            canvas.drawRoundRect(b, dp(14), dp(14), fill);
            if (active) {
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(dp(2));
                fill.setColor(Color.argb(210, 31, 194, 142));
                canvas.drawRoundRect(b, dp(14), dp(14), fill);
            }
        }
    }

    /** Opaque window that fully covers the native DiShare app list. */
    private final class RowPlateView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        RowPlateView(Context context) {
            super(context);
            setWillNotDraw(false);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // Only the rounded panel — the window is translucent, so the corners stay
            // transparent (no dark squares) and the dialog body shows through there.
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(ROW_PANEL);
            float r = dp(ROW_CORNER_DP);
            canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), r, r, fill);
            if (panelBounds == null) {
                return;
            }
            for (Slot slot : slots) {
                float alpha = (dragging && downFromRow && slot.target == dragTarget) ? 0.35f : 1f;
                drawIcon(canvas, fill, text, slot.target, localRect(slot.bounds, panelBounds), alpha);
            }
        }
    }

    /** Opaque source-app icon on the central screen. */
    private final class CentralIconPlateView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        CentralIconPlateView(Context context) {
            super(context);
            setWillNotDraw(false);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // Opaque dark fill replaces the stock central preview, then the selected
            // app's icon centered on it — reads as "this app on the source screen".
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(CENTRAL_BG);
            float r = dp(12);
            canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), r, r, fill);
            if (selectedTarget == null) {
                return;
            }
            float size = Math.min(getWidth(), getHeight()) * 0.5f;
            float left = (getWidth() - size) / 2f;
            float top = (getHeight() - size) / 2f;
            float alpha = (dragging && !downFromRow) ? 0.4f : 1f;
            drawIcon(canvas, fill, text, selectedTarget,
                    new RectF(left, top, left + size, top + size), alpha);
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
                    onUp(event.getRawX(), event.getRawY());
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    cancelDrag();
                    invalidateOverlayViews();
                    return true;
                default:
                    return true;
            }
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
                    onUp(event.getRawX(), event.getRawY());
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    cancelDrag();
                    invalidateOverlayViews();
                    return true;
                default:
                    return true;
            }
        }
    }
}
