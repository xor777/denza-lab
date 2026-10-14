'use strict';

// Research only: runs inside the owner's pinned Navigator on a disposable emulator.
// No hooks, Activity/task moves, host-validation patches, global MapKit lifecycle
// calls, API-key extraction, IVI screen capture, vehicle setters or HUD bus writes.
// --simulate is the only guidance mutation, confined to the host-checked emulator.
let state = null;

function mainThread(fn) {
    return new Promise((resolve, reject) => Java.perform(() => {
        Java.scheduleOnMainThread(() => {
            try { resolve(fn()); } catch (e) { reject(new Error(String(e.stack || e))); }
        });
    }));
}

function choose(name) {
    return new Promise((resolve, reject) => Java.perform(() => {
        const found = [];
        try {
            Java.choose(name, {
                onMatch: instance => found.push(Java.retain(instance)),
                onComplete: () => resolve(found)
            });
        } catch (e) { reject(e); }
    }));
}

const hudStyle = [
    {elements: ['geometry'], stylers: {color: '#000000'}},
    {elements: ['label'], stylers: {visibility: 'off'}},
    {tags: {any: ['road']}, elements: ['geometry'], stylers: {color: '#58636a'}},
    {tags: {any: ['road']}, elements: ['geometry.outline'], stylers: {color: '#000000'}},
    {tags: {any: ['road.minor']}, elements: ['geometry'], stylers: {color: '#354047'}}
];

rpc.exports = {
    async start(simulate, transparent) {
        if (state) throw new Error('Already running');
        const windows = await choose('com.yandex.mapkit.map.internal.MapWindowBinding');
        const guidance = await choose('com.yandex.navikit.guidance.internal.GuidanceBinding');
        return mainThread(() => {
            const main = windows.filter(w => w.isValid() && w.width() > 300 && w.height() > 180);
            if (main.length !== 1) throw new Error('Need exactly one live main map; found ' + main.length);
            const Factory = Java.use('com.yandex.mapkit.MapKitFactory');
            const offscreen = Factory.getInstance().createOffscreenMapWindow(300, 180, 1.0);
            const window = offscreen.getMapWindow();
            const map = window.getMap();
            // Frida does not expose inherited interface methods through the
            // RootMapObjectCollection return type in this APK.
            const objects = Java.cast(map.getMapObjects(), Java.use('com.yandex.mapkit.map.MapObjectCollection'));
            const baseObjects = Java.cast(objects, Java.use('com.yandex.mapkit.map.BaseMapObjectCollection'));
            map.setNightModeEnabled(true);
            map.set2DMode(true);
            map.setModelsEnabled(false);
            map.setIndoorEnabled(false);
            // A black map slot is optically transparent on the owner's HUD.
            // The alpha switch is a failing diagnostic: run-07 made the native
            // map (including its route) invisible, leaving only our Canvas arrow.
            const style = JSON.parse(JSON.stringify(hudStyle));
            if (transparent) {
                map.setTransparentBackgroundEnabled(true);
                style[0].stylers.color = '#00000000';
                style[3].stylers.color = '#00000000';
            }
            if (!map.setMapStyle(JSON.stringify(style))) throw new Error('HUD style rejected');
            window.setMaxFps(10);
            window.setFocusPoint(Java.use('com.yandex.mapkit.ScreenPoint').$new(150, 132));
            const candidates = guidance.filter(g => g.isValid());
            if (candidates.length > 1) throw new Error('Ambiguous guidance instances');
            state = {offscreen, window, map, objects, baseObjects, main: main[0], windows, guidance,
                active: candidates[0] || null, line: null, frame: 0, simulation: false};
            if (simulate) {
                if (!state.active || !state.active.route()) throw new Error('Start a route in Navigator first');
                if (String(state.active.getSimulationStatus()) !== 'IDLE')
                    throw new Error('Existing simulation must be stopped before this trial');
                state.active.startSimulationWithExistingRoute();
                state.simulation = true;
            }
            return {width: 300, height: 180, guidanceInstances: candidates.length,
                engine: String(Factory.getInstance().getVersion()), source: 'independent-map-window',
                transparentRequested: !!transparent};
        });
    },

    async frame() {
        return mainThread(() => {
            if (!state) throw new Error('Not running');
            const s = state;
            const begin = Date.now();
            const Camera = Java.use('com.yandex.mapkit.map.CameraPosition');
            let route = null;
            let location = null;
            let routePosition = null;
            if (s.active && s.active.isValid()) {
                route = s.active.route();
                routePosition = s.active.getRoutePosition();
                const classified = s.active.getLocation();
                if (classified) location = classified.getLocation();
            }
            let mode = 'base-map-only';
            if (route && location) {
                mode = 'navigator-route';
                const geometry = route.getGeometry();
                if (!s.line) {
                    s.line = s.objects.addPolyline(geometry);
                    s.line.setStrokeColor(0xff70f4ff | 0);
                    s.line.setStrokeWidth(5);
                    s.line.setOutlineColor(-16777216);
                    s.line.setOutlineWidth(1);
                } else {
                    s.line.setGeometry(geometry);
                }
                const heading = location.getHeading();
                s.map.move(Camera.$new(location.getPosition(), 17.0,
                    heading ? heading.doubleValue() : 0.0, 0.0));
            } else {
                if (s.line) { s.baseObjects.remove(s.line); s.line = null; }
                // Explicit baseline only: never label an independently built or
                // simulated polyline as Navigator's current route.
                const camera = s.main.getMap().getCameraPosition();
                s.map.move(Camera.$new(camera.getTarget(), 17.0, camera.getAzimuth(), 0.0));
            }
            let bitmap = s.offscreen.captureScreenshot();
            if (!bitmap || bitmap.getWidth() !== 300 || bitmap.getHeight() !== 180)
                throw new Error('Invalid offscreen frame');
            if (mode === 'navigator-route') {
                if (!bitmap.isMutable()) {
                    const copy = bitmap.copy(Java.use('android.graphics.Bitmap$Config').ARGB_8888.value, true);
                    bitmap.recycle();
                    bitmap = copy;
                }
                // Our car marker, composed from vectors, not read from the IVI.
                const canvas = Java.use('android.graphics.Canvas').$new(bitmap);
                const paint = Java.use('android.graphics.Paint').$new(1);
                const path = Java.use('android.graphics.Path').$new();
                path.moveTo(150, 122); path.lineTo(158, 141);
                path.lineTo(150, 137); path.lineTo(142, 141); path.close();
                paint.setColor(-16777216);
                paint.setStrokeWidth(4);
                paint.setStyle(Java.use('android.graphics.Paint$Style').STROKE.value);
                canvas.drawPath(path, paint);
                paint.setColor(-1);
                paint.setStyle(Java.use('android.graphics.Paint$Style').FILL.value);
                canvas.drawPath(path, paint);
            }
            const stream = Java.use('java.io.ByteArrayOutputStream').$new();
            if (!bitmap.compress(Java.use('android.graphics.Bitmap$CompressFormat').PNG.value, 100, stream))
                throw new Error('PNG encoding failed');
            const raw = stream.toByteArray();
            // Encode in Java, avoiding one JNI round-trip for every PNG byte.
            const png = String(Java.use('android.util.Base64').encodeToString(raw, 2));
            bitmap.recycle();
            stream.close();
            const result = {kind: 'frame', index: s.frame++, mode, elapsedMs: Date.now() - begin,
                bytes: raw.length, routePresent: !!route, locationPresent: !!location,
                routeSegment: routePosition ? routePosition.getSegmentIndex() : null};
            send({kind: 'png', frame: result, base64: png});
            return result;
        });
    },

    async stop() {
        return mainThread(() => {
            if (!state) return;
            if (state.simulation) state.active.stopSimulation();
            state.baseObjects.clear();
            // Release only our window and retained Java wrappers. The original
            // app continues to own its MapKit/Guidance lifetime.
            for (const item of state.windows.concat(state.guidance)) item.$dispose();
            state.map.$dispose();
            state.objects.$dispose();
            state.baseObjects.$dispose();
            state.window.$dispose();
            state.offscreen.$dispose();
            state = null;
        });
    },

    async cancelRouteForTest() {
        return mainThread(() => {
            if (!state || !state.simulation) throw new Error('Cancellation test requires our emulator simulation');
            state.active.stopSimulation();
            state.simulation = false;
            state.active.stop();
        });
    }
};
