'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
global.HudScene = require('./hud-scene.js');
const adapter = require('./navigator-adapter.js');

function environment() {
    class List {
        constructor(items = []) { this.items = items; }
        size() { return this.items.length; }
        get(i) { return this.items[i]; }
        add(value) { this.items.push(value); }
        $dispose() { this.disposed = true; }
    }
    const lines = [], removed = [];
    const position = (segment, fraction) => ({
        getSegmentIndex: () => segment, getSegmentPosition: () => fraction, $dispose() {}
    });
    global.Java = {
        cast: value => value,
        retain: value => value,
        registerClass: spec => ({$new: () => ({id: {value: 0}, ...spec.methods, $dispose() {}})}),
        use: name => {
            if (name.endsWith('ArrayList')) return {$new: () => new List()};
            if (name.endsWith('Integer')) return {valueOf: value => value};
            if (name.endsWith('PolylinePosition')) return {$new: position};
            if (name.endsWith('Subpolyline')) return {$new: (begin, end) => ({begin, end, $dispose() {}})};
            return {};
        }
    };
    function route(id, types = ['FREE', 'LIGHT', 'HARD']) {
        return {
            id, types, outdated: false, reads: 0, geometryReads: 0, listeners: new Set(),
            getRouteId() { return this.id; },
            getGeometry() { this.geometryReads++; return {getPoints: () => new List([0,1,2,3])}; },
            isAreConditionsOutdated() { return this.outdated; },
            getJamSegments() { this.reads++; return new List(this.types.map(type => ({getJamType: () => type}))); },
            addConditionsListener(listener) { this.listeners.add(listener); },
            removeConditionsListener(listener) { this.listeners.delete(listener); },
            changed() { for (const listener of this.listeners) listener.onConditionsUpdated(); },
            $dispose() { this.disposed = true; }
        };
    }
    const objects = {addPolyline: geometry => {
        const line = {geometry, uploads: 0, hidden: [],
            setPaletteColor() {}, setStrokeWidth() {}, setOutlineWidth() {}, setOutlineColor() {},
            setStrokeColors(colors) { this.colors = [...colors.items]; this.uploads++; },
            hide: {overload: () => ({call: (self, hidden) => self.hidden.push(hidden.end)})},
            $dispose() { this.disposed = true; }};
        lines.push(line);
        return line;
    }};
    const base = {remove: line => removed.push(line)};
    return {route, position, lines, removed, base, layer: adapter.createRouteLayer(objects, base)};
}

test('moving a route never rebuilds its geometry or resends unchanged traffic', () => {
    const env = environment(), route = env.route('A');
    try {
        for (let i = 0; i < 200; i++) assert(env.layer.update(route, env.position(1, i / 200), i * 100));
        assert.equal(route.geometryReads, 1);
        assert.equal(route.reads, 1);
        assert.equal(env.lines.length, 1);
        assert.equal(env.lines[0].uploads, 1);
        assert.deepEqual(env.lines[0].colors, [2, 3, 4]);
        assert.equal(env.lines[0].hidden.length, 200);
        assert.equal(env.lines[0].hidden[199].getSegmentPosition(), .995);
    } finally { env.layer.dispose(); }
    assert.equal(route.listeners.size, 0);
});

test('conditions event updates immediately; stale data clears colors even without an event', () => {
    const env = environment(), route = env.route('A');
    try {
        env.layer.update(route, env.position(0, 0), 0);
        route.types = ['VERY_HARD', 'VERY_HARD', 'FREE'];
        route.changed();
        env.layer.update(route, env.position(0, .1), 10);
        assert.deepEqual(env.lines[0].colors, [5, 5, 2]);
        route.outdated = true;
        env.layer.update(route, env.position(0, .2), 20);
        assert.deepEqual(env.lines[0].colors, [0, 0, 0]);
        assert.equal(env.layer.traffic().valid, false);
        route.outdated = false;
        env.layer.update(route, env.position(0, .3), 30);
        assert.deepEqual(env.lines[0].colors, [5, 5, 2]);
        // A lost native notification still recovers through a bounded fallback refresh.
        route.types = ['HARD', 'FREE', 'FREE'];
        env.layer.update(route, env.position(0, .4), 30031);
        assert.deepEqual(env.lines[0].colors, [4, 2, 2]);
    } finally { env.layer.dispose(); }
});

test('replacement detaches old listener, resets palette and keeps original segment indices', () => {
    const env = environment(), a = env.route('A'), b = env.route('B', ['LIGHT', 'HARD', 'VERY_HARD']);
    try {
        env.layer.update(a, env.position(2, .8), 0);
        const oldListener = [...a.listeners][0];
        env.layer.update(b, env.position(0, .1), 10);
        assert.equal(a.listeners.size, 0);
        assert.equal(b.listeners.size, 1);
        assert(env.lines[0].disposed);
        assert.deepEqual(env.lines[1].colors, [3, 4, 5]);
        const reads = b.reads;
        oldListener.onConditionsUpdated();
        env.layer.update(b, env.position(0, .2), 20);
        assert.equal(b.reads, reads, 'late callback from an old route must be ignored');
        env.layer.clear();
        env.layer.clear();
        assert.equal(b.listeners.size, 0);
        env.layer.update(b, env.position(0, .3), 30);
        assert.equal(env.lines.length, 3);
    } finally { env.layer.dispose(); }
});

test('invalid progress removes previous route; cleanup continues after a native release failure', () => {
    const env = environment(), route = env.route('A');
    try {
        for (const p of [env.position(-1, 0), env.position(3, 0), env.position(0, NaN), env.position(0, 1.01)]) {
            env.layer.update(route, env.position(0, .1), 0);
            assert.equal(env.layer.update(route, p, 1), false);
            assert.equal(route.listeners.size, 0);
            assert(env.lines.at(-1).disposed);
        }
        env.layer.update(route, env.position(0, 0), 2);
        env.base.remove = () => { throw new Error('native remove failed'); };
        assert.throws(() => env.layer.clear(), /native remove failed/);
        assert.equal(route.listeners.size, 0);
        assert(env.lines.at(-1).disposed);
    } finally { env.layer.dispose(); }
});
