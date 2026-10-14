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
            const style = JSON.parse(JSON.stringify(HudScene.mapStyle));
            if (transparent) {
                map.setTransparentBackgroundEnabled(true);
                style[0].stylers.color = '#00000000';
                style[3].stylers.color = '#00000000';
            }
            if (!map.setMapStyle(JSON.stringify(style))) throw new Error('HUD style rejected');
            window.setMaxFps(10);
            window.setFocusPoint(Java.use('com.yandex.mapkit.ScreenPoint').$new(150, 143));
            const candidates = guidance.filter(g => g.isValid());
            if (candidates.length > 1) throw new Error('Ambiguous guidance instances');
            state = {offscreen, window, map, objects, baseObjects, main: main[0], windows, guidance,
                active: candidates[0] || null, line: null, frame: 0, simulation: false, freshness: {}, zoom: null, traffic: {}};
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
            const data = HudNavigator.read(s.active, s.freshness, Number(Java.use('android.os.SystemClock').elapsedRealtime()));
            const {route, location, position: routePosition} = data;
            const scene = HudScene.build(data.input);
            let bitmap;
            if (scene.drawMap) {
                const geometry = HudNavigator.remainingGeometry(route, routePosition);
                if (!geometry) throw new Error('Invalid remaining-route geometry');
                if (!s.line) {
                    s.line = s.objects.addPolyline(geometry);
                    HudScene.JAM_TYPES.forEach((type,index)=>s.line.setPaletteColor(index,(0xff000000|parseInt(HudScene.TRAFFIC[type].slice(1),16))|0));
                    s.line.setStrokeWidth(5);
                    s.line.setOutlineColor(-16777216);
                    s.line.setOutlineWidth(1);
                } else s.line.setGeometry(geometry);
                const traffic = HudNavigator.traffic(route, s.traffic, Number(Java.use('android.os.SystemClock').elapsedRealtime()));
                s.line.setStrokeColors(traffic.colors.subList(routePosition.getSegmentIndex(),traffic.count));
                const heading = location.getHeading();
                const targetZoom = HudScene.cameraZoom(data.input.maneuver?.distance, location.getPosition().getLatitude());
                s.zoom = s.zoom === null ? targetZoom : s.zoom + Math.max(-0.08, Math.min(0.08, targetZoom - s.zoom));
                s.map.move(Camera.$new(location.getPosition(), s.zoom,
                    heading ? heading.doubleValue() : 0.0, 0.0));
                bitmap = s.offscreen.captureScreenshot();
                if (!bitmap || bitmap.getWidth() !== 300 || bitmap.getHeight() !== 180)
                    throw new Error('Invalid offscreen frame');
                if (!bitmap.isMutable()) {
                    const copy = bitmap.copy(Java.use('android.graphics.Bitmap$Config').ARGB_8888.value, true);
                    bitmap.recycle(); bitmap = copy;
                }
            } else {
                if (s.line) { s.baseObjects.remove(s.line); s.line = null; }
                s.zoom = null;
                // A route preview, cancellation, completed route or stale position
                // never continues capturing the map or retains its previous pixels.
                bitmap = Java.use('android.graphics.Bitmap').createBitmap(300, 180,
                    Java.use('android.graphics.Bitmap$Config').ARGB_8888.value);
                bitmap.eraseColor(-16777216);
            }
            paintHudAndroid(bitmap, scene.commands);
            const stream = Java.use('java.io.ByteArrayOutputStream').$new();
            if (!bitmap.compress(Java.use('android.graphics.Bitmap$CompressFormat').PNG.value, 100, stream))
                throw new Error('PNG encoding failed');
            const raw = stream.toByteArray();
            // Encode in Java, avoiding one JNI round-trip for every PNG byte.
            const png = String(Java.use('android.util.Base64').encodeToString(raw, 2));
            bitmap.recycle();
            stream.close();
            const result = {kind: 'frame', index: s.frame++, mode: scene.mode, guidance: data.input, routeStatus: data.status, locationClass: data.locationClass, elapsedMs: Date.now() - begin,
                bytes: raw.length, routePresent: !!route, locationPresent: !!location,
                routeSegment: routePosition ? routePosition.getSegmentIndex() : null,
                traffic: scene.drawMap ? {valid:s.traffic.valid, outdated:s.traffic.outdated, count:s.traffic.count, jamCount:s.traffic.jamCount, types:s.traffic.types} : null};
            send({kind: 'png', frame: result, base64: png});
            return result;
        });
    },

    async stop() {
        return mainThread(() => {
            if (!state) return;
            if (state.simulation) state.active.stopSimulation();
            if (state.traffic.colors) state.traffic.colors.$dispose();
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
