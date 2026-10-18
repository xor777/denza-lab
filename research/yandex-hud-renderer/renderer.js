'use strict';

// Emulator research only. Owns one offscreen window, never Navigator's global
// lifecycle, primary map, location source or route calculation.
let state = null;
let phase = 'idle';
let frameBusy = false;

function mainThread(fn) {
    return new Promise((resolve, reject) => Java.perform(() => {
        Java.scheduleOnMainThread(() => {
            try { resolve(fn()); } catch (error) { reject(error); }
        });
    }));
}

function workerThread(fn) {
    return new Promise((resolve, reject) => setImmediate(() => Java.perform(() => {
        try { resolve(fn()); } catch (error) { reject(error); }
    })));
}

function choose(name) {
    return new Promise((resolve, reject) => Java.perform(() => {
        const found = [];
        try {
            Java.choose(name, {
                onMatch: instance => found.push(Java.retain(instance)),
                onComplete: () => resolve(found)
            });
        } catch (error) {
            for (const instance of found) instance.$dispose();
            reject(error);
        }
    }));
}

// Partial starts and normal stops share exactly the same release path. A failed
// release never prevents the remaining owned resources from being released.
function release(session) {
    const errors = [];
    const attempt = (name, fn) => {
        try { fn(); } catch (error) { errors.push(name + ': ' + String(error)); }
    };
    if (session.simulation) attempt('simulation', () => session.active.stopSimulation());
    if (session.layer) attempt('route layer', () => session.layer.dispose());
    if (session.painter) attempt('painter', () => session.painter.dispose());
    if (session.baseObjects) attempt('map objects', () => session.baseObjects.clear());
    for (const item of session.owned.reverse()) attempt('wrapper', () => item.$dispose());
    session.owned.length = 0;
    return errors;
}

async function start(simulate, transparent) {
    if (phase !== 'idle') throw new Error('Renderer is ' + phase);
    phase = 'starting';
    const session = {owned: [], simulation: false, frame: 0, freshness: {}, zoom: null, cameraAt: null};
    const own = value => { session.owned.push(value); return value; };
    try {
        const windows = await choose('com.yandex.mapkit.map.internal.MapWindowBinding');
        session.owned.push(...windows);
        const candidates = await choose('com.yandex.navikit.guidance.internal.GuidanceBinding');
        session.owned.push(...candidates);
        const result = await mainThread(() => {
            const main = windows.filter(window => window.isValid() && window.width() > 300 && window.height() > 180);
            if (main.length !== 1) throw new Error('Need exactly one live main map; found ' + main.length);
            const guidance = candidates.filter(value => value.isValid());
            if (guidance.length > 1) throw new Error('Ambiguous guidance instances');
            session.active = guidance[0] || null;
            if (simulate) {
                if (!session.active || !session.active.route()) throw new Error('Start a route in Navigator first');
                if (String(session.active.getSimulationStatus()) !== 'IDLE') throw new Error('Existing simulation must be stopped');
            }
            const Factory = Java.use('com.yandex.mapkit.MapKitFactory');
            session.clock = Java.use('android.os.SystemClock');
            session.Camera = Java.use('com.yandex.mapkit.map.CameraPosition');
            session.offscreen = own(Factory.getInstance().createOffscreenMapWindow(HudScene.SIZE.width, HudScene.SIZE.height, 1));
            session.window = own(session.offscreen.getMapWindow());
            session.map = own(session.window.getMap());
            // Frida does not expose inherited methods through RootMapObjectCollection.
            const objects = own(Java.cast(session.map.getMapObjects(), Java.use('com.yandex.mapkit.map.MapObjectCollection')));
            session.baseObjects = own(Java.cast(objects, Java.use('com.yandex.mapkit.map.BaseMapObjectCollection')));
            session.map.setNightModeEnabled(true);
            session.map.set2DMode(true);
            session.map.setModelsEnabled(false);
            session.map.setIndoorEnabled(false);
            const style = JSON.parse(JSON.stringify(HudScene.mapStyle));
            if (transparent) {
                // Negative-test reproducer: native alpha made map and route invisible.
                session.map.setTransparentBackgroundEnabled(true);
                style[0].stylers.color = '#00000000';
                style[3].stylers.color = '#00000000';
            }
            if (!session.map.setMapStyle(JSON.stringify(style))) throw new Error('HUD style rejected');
            session.window.setMaxFps(10);
            session.window.setFocusPoint(Java.use('com.yandex.mapkit.ScreenPoint').$new(...HudScene.SIZE.focus));
            session.layer = HudNavigator.createRouteLayer(objects, session.baseObjects);
            session.painter = createHudAndroidPainter(HudScene.SIZE.width, HudScene.SIZE.height);
            if (simulate) {
                // Mark before entering native code so even a partial start gets stopped.
                session.simulation = true;
                session.active.startSimulationWithExistingRoute();
            }
            return {width: HudScene.SIZE.width, height: HudScene.SIZE.height,
                guidanceInstances: guidance.length, engine: String(Factory.getInstance().getVersion()),
                source: 'independent-map-window', transparentRequested: !!transparent,
                mapThread: String(Java.use('java.lang.Thread').currentThread().getName())};
        });
        state = session;
        phase = 'running';
        return result;
    } catch (error) {
        const cleanup = await mainThread(() => release(session));
        phase = 'idle';
        throw new Error(String(error) + (cleanup.length ? '; cleanup: ' + cleanup.join('; ') : ''));
    }
}

function prepareFrame(session) {
    const began = Date.now();
    const stages = {};
    let previous = began;
    const mark = name => { const now = Date.now(); stages[name] = now - previous; previous = now; };
    const now = Number(session.clock.elapsedRealtime());
    const data = HudNavigator.read(session.active, session.freshness, now);
    let scene = HudScene.build(data.input);
    mark('read');
    if (scene.drawMap && !session.layer.update(data.route, data.position, now)) {
        data.input.locationFresh = false;
        scene = HudScene.build(data.input);
    }
    mark('route');
    let screenshot = null;
    try {
        if (scene.drawMap) {
            const point = data.location.getPosition();
            const heading = data.location.getHeading();
            const target = HudScene.cameraZoom(data.cameraDistance, point.getLatitude());
            // Same nominal speed as 0.08/frame at 10 Hz, independent of delayed frames.
            const step = Math.min(250, Math.max(0, now - session.cameraAt)) * 0.0008;
            session.zoom = session.zoom === null ? target : session.zoom + Math.max(-step, Math.min(step, target - session.zoom));
            session.cameraAt = now;
            const camera = session.Camera.$new(point, session.zoom, heading ? heading.doubleValue() : 0, 0);
            try { session.map.move(camera); } finally { camera.$dispose(); }
            mark('camera');
            screenshot = session.offscreen.captureScreenshot();
            if (!screenshot || screenshot.getWidth() !== HudScene.SIZE.width || screenshot.getHeight() !== HudScene.SIZE.height) {
                throw new Error('Invalid offscreen frame');
            }
        } else {
            session.layer.clear();
            session.zoom = null;
            session.cameraAt = null;
        }
        mark('capture');
        session.painter.prepare(scene.commands);
        session.painter.compose(screenshot);
        mark('overlay');
    } finally {
        if (screenshot) { try { screenshot.recycle(); } finally { screenshot.$dispose(); } }
    }
    return {scene, result: {kind: 'frame', index: session.frame++, mode: scene.mode,
        guidance: data.input, routeStatus: data.status, locationClass: data.locationClass,
        stages, mainThreadMs: Date.now() - began, routePresent: !!data.route, locationPresent: !!data.location,
        routeSegment: data.position ? data.position.getSegmentIndex() : null,
        traffic: scene.drawMap ? session.layer.traffic() : null}};
}

async function frame() {
    if (phase !== 'running') throw new Error('Renderer is ' + phase);
    if (frameBusy) throw new Error('Frame already in flight');
    frameBusy = true;
    const session = state;
    const begin = Date.now();
    try {
        const prepared = await mainThread(() => prepareFrame(session));
        const encoded = await workerThread(() => {
            const beginEncode = Date.now();
            const png = session.painter.encode(!prepared.scene.drawMap);
            prepared.result.stages.encode = Date.now() - beginEncode;
            if (!session.encodeThread) session.encodeThread = String(Java.use('java.lang.Thread').currentThread().getName());
            return png;
        });
        const result = Object.assign(prepared.result, {elapsedMs: Date.now() - begin, bytes: encoded.bytes,
            encodeThread: session.encodeThread,
            work: {...session.layer.stats, ...session.painter.stats}});
        send({kind: 'png', frame: result, base64: encoded.base64});
        return result;
    } finally { frameBusy = false; }
}

async function stop() {
    if (phase === 'idle') return {stopped: true};
    if (phase !== 'running' || frameBusy) throw new Error('Renderer busy; wait for the current operation');
    phase = 'stopping';
    const session = state;
    state = null;
    try {
        const errors = await mainThread(() => release(session));
        if (errors.length) throw new Error(errors.join('; '));
        return {stopped: true, work: {...session.layer.stats, ...session.painter.stats}};
    } finally { phase = 'idle'; }
}

async function cancelRouteForTest() {
    if (phase !== 'running' || frameBusy || !state.simulation) throw new Error('Requires our idle emulator simulation');
    return mainThread(() => {
        state.active.stopSimulation();
        state.simulation = false;
        state.active.stop();
    });
}

rpc.exports = {start, frame, stop, cancelRouteForTest};
