// Host check of per-thread and nested capture ownership. Does not run target code.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function fixture() {
  const memory = new Map(), hooks = new Map(), events = [], detached = [];
  let next = 0x40000000;
  class Pointer {
    constructor(value) { this.value = Number(value); }
    add(n) { return new Pointer(this.value + Number(n)); }
    sub(n) { return new Pointer(this.value - (n instanceof Pointer ? n.value : Number(n))); }
    compare(p) { return Math.sign(this.value - p.value); }
    isNull() { return this.value === 0; }
    toUInt32() { return this.value >>> 0; }
    toString() { return '0x' + this.value.toString(16); }
    readByteArray(n) {
      return Uint8Array.from({length: n}, (_, i) => {
        if (!memory.has(this.value + i)) throw Error('Unmapped memory');
        return memory.get(this.value + i);
      }).buffer;
    }
    readU8() { return new DataView(this.readByteArray(1)).getUint8(0); }
    readU32() { return new DataView(this.readByteArray(4)).getUint32(0, true); }
    readPointer() { return new Pointer(new DataView(this.readByteArray(8)).getBigUint64(0, true)); }
  }
  const ptr = value => new Pointer(value);
  function write(p, bytes) { bytes.forEach((b, i) => memory.set(p.value + i, b)); }
  function allocate(n) { const p = ptr(next); next += n + 64; write(p, Buffer.alloc(n)); return p; }
  function words(p, values) {
    const b = Buffer.alloc(values.length * 4);
    values.forEach((v, i) => b.writeUInt32LE(v, i * 4)); write(p, b);
  }
  function pointerAt(p, value) { const b = Buffer.alloc(8); b.writeBigUInt64LE(BigInt(value.value)); write(p, b); }
  const base = ptr(0x10000000);
  for (const [offset, prefix] of [
    [0x17bbaa0, 'ff4301d1fd7b02a9f65703a9f44f04a9'],
    [0x17bb6c8, 'ff8301d1fd7b04a9f44f05a9fd030191'],
    [0x17c1bdc, 'fd7bbaa9fc6f01a9fa6702a9f85f03a9']]) write(base.add(offset), Buffer.from(prefix, 'hex'));
  const sandbox = {Process: {pointerSize: 8, findModuleByName: () => ({base})}, ptr,
    Interceptor: {attach(p, handler) {
      hooks.set(p.value - base.value, handler);
      return {detach() { detached.push(p.value - base.value); }};
    }}, send: (payload, bytes) => events.push({payload, bytes}),
    setTimeout: () => 1, clearTimeout: () => {}, rpc: {exports: {}}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../yandex_vector_observer.js'), 'utf8'), sandbox);
  function enter(offset, tid, input) {
    const state = {threadId: tid}; hooks.get(offset).onEnter.call(state, [input]);
    return () => hooks.get(offset).onLeave.call(state);
  }
  function layer(tid, render, source = render) {
    const p = allocate(0x50); words(p.add(0x18), render); words(p.add(0x38), source);
    return enter(0x17c1bdc, tid, p);
  }
  function context(tid, xyz) { const p = allocate(12); words(p, xyz); return enter(0x17bb6c8, tid, p); }
  function decode(tid, size = 32768, successful = true) {
    const bytes = allocate(size), span = allocate(16), result = allocate(24);
    pointerAt(span, bytes); pointerAt(span.add(8), bytes.add(size)); write(result.add(16), [successful ? 1 : 0]);
    const state = {threadId: tid, context: {x8: result}, returnAddress: base.add(0x17bb9c0)};
    hooks.get(0x17bbaa0).onEnter.call(state, [span]);
    hooks.get(0x17bbaa0).onLeave.call(state);
  }
  return {layer, context, decode, events, detached, stats: () => sandbox.rpc.exports.stats()};
}

const f = fixture();
const l1 = f.layer(1, [20000, 10000, 15]), c1 = f.context(1, [20000, 10000, 15]);
const l2 = f.layer(2, [20001, 10000, 15]), c2 = f.context(2, [20001, 10000, 15]);
f.decode(1); f.decode(2); c2(); l2();
const nestedLayer = f.layer(1, [40000, 20000, 16], [20000, 10000, 15]);
const nested = f.context(1, [20000, 10000, 15]); f.decode(1); nested(); nestedLayer();
f.decode(1);
const invalid = f.context(1, [0, 0, 31]); f.decode(1); invalid(); // Shadows outer valid context.
f.decode(1, 16); f.decode(1, 32768, false);
c1(); l1(); f.decode(1); // No stale cross-invocation association.
const captured = f.events.filter(e => e.bytes).map(e => JSON.parse(JSON.stringify(e.payload)));
assert.equal(captured.length, 4);
assert.equal(captured[0].tileId.x, 20000); assert.equal(captured[1].tileId.x, 20001);
assert.equal(captured[2].tileId.z, 15); assert.equal(captured[2].renderTileId.z, 16);
assert.equal(captured[2].layerSourceTileId.z, 15); assert.equal(captured[3].renderTileId.z, 15);
assert.equal(f.stats().invalidContext, 1); assert.equal(f.stats().missingContext, 2);
assert.equal(f.stats().skippedSmall, 1); assert.equal(f.stats().parseFailed, 1);
assert.equal(f.stats().contextMismatch, 0); assert.equal(f.stats().layerMismatch, 0);

const bounded = fixture();
const l = bounded.layer(3, [20000, 10000, 15]), c = bounded.context(3, [20000, 10000, 15]);
for (let i = 0; i < 20; i++) bounded.decode(3);
c(); l();
assert.equal(bounded.stats().copied, 8); assert.equal(bounded.stats().emitted, 8);
assert.equal(bounded.stats().stopped, true); assert.equal(bounded.detached.length, 3);
console.log('Vector observer: thread isolation, nesting, overzoom identity, invalid context, native failure, size and capture bounds passed');
