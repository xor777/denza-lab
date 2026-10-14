'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const hud = require('./hud-scene.js');
const source = fs.readFileSync(require.resolve('./renderer.js'), 'utf8');

function harness() {
    const flags = {style: true, layerReleaseError: false};
    const released = [], frames = [];
    const wrapper = (name, methods = {}) => ({...methods, $dispose: () => released.push(name)});
    const objects = wrapper('objects', {clear() { released.push('clear'); }});
    const map = wrapper('map', {
        getMapObjects: () => objects,
        setNightModeEnabled() {}, set2DMode() {}, setModelsEnabled() {}, setIndoorEnabled() {},
        setMapStyle: () => flags.style
    });
    const window = wrapper('window', {getMap: () => map, setMaxFps() {}, setFocusPoint() {}});
    const offscreen = wrapper('offscreen', {getMapWindow: () => window});
    const main = wrapper('main-reference', {isValid: () => true, width: () => 2560, height: () => 1536});
    const guidance = wrapper('guidance-reference', {
        isValid: () => true, route: () => ({}), getSimulationStatus: () => 'IDLE',
        startSimulationWithExistingRoute() {}, stopSimulation() { released.push('simulation'); }
    });
    const Java = {
        perform: fn => fn(), scheduleOnMainThread: fn => fn(), retain: value => value, cast: value => value,
        choose: (name, visitor) => { visitor.onMatch(name.includes('MapWindow') ? main : guidance); visitor.onComplete(); },
        use: name => {
            if (name.endsWith('MapKitFactory')) return {getInstance: () => ({createOffscreenMapWindow: () => offscreen, getVersion: () => 'test'})};
            if (name.endsWith('SystemClock')) return {elapsedRealtime: () => 100};
            if (name.endsWith('Thread')) return {currentThread: () => ({getName: () => 'test'})};
            return {$new: () => wrapper('temporary')};
        }
    };
    const context = {
        Java, HudScene: hud, rpc: {}, setImmediate, send: value => frames.push(value),
        HudNavigator: {
            read: () => ({input: {guidanceActive: false}}),
            createRouteLayer: () => ({stats: {}, clear() {}, dispose() {
                released.push('layer');
                if (flags.layerReleaseError) throw new Error('release fault');
            }})
        },
        createHudAndroidPainter: () => ({stats: {}, prepare() {}, compose() {}, encode: () => ({bytes: 1, base64: 'AA=='}),
            dispose() { released.push('painter'); }})
    };
    vm.runInNewContext(source, context);
    return {api: context.rpc.exports, flags, released, frames};
}

test('partial initialization rolls back and permits restart; duplicate start is rejected', async () => {
    const env = harness();
    env.flags.style = false;
    await assert.rejects(env.api.start(false, false), /style rejected/);
    for (const resource of ['main-reference', 'guidance-reference', 'offscreen', 'window', 'map']) {
        assert(env.released.includes(resource), resource);
    }
    env.flags.style = true;
    const starting = env.api.start(true, false);
    await assert.rejects(env.api.start(false, false), /starting/);
    await starting;
    await env.api.stop();
    assert(env.released.includes('simulation'));
    await env.api.stop();
});

test('a frame owns its buffer through worker encoding; no concurrent frame or disposal', async () => {
    const env = harness();
    await assert.rejects(env.api.frame(), /idle/);
    await env.api.start(false, false);
    const pending = env.api.frame();
    await assert.rejects(env.api.frame(), /in flight/);
    await assert.rejects(env.api.stop(), /busy/);
    await pending;
    assert.equal(env.frames.length, 1);
    assert.equal(env.frames[0].frame.mode, 'off');
    await env.api.stop();
});

test('failed release does not skip other resources or leave lifecycle stuck', async () => {
    const env = harness();
    await env.api.start(false, false);
    env.flags.layerReleaseError = true;
    await assert.rejects(env.api.stop(), /release fault/);
    assert(env.released.includes('painter'));
    assert(env.released.includes('offscreen'));
    env.flags.layerReleaseError = false;
    await env.api.start(false, false);
    await env.api.stop();
});
