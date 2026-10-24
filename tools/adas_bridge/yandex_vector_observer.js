// Passive, exact-build vmap3 protobuf observer for Navigator 29.8.1 (739494300).
// Original APK SHA256: 3b0dec3277f261fd2a2e8b6c0ccb287b1c2bf629b5333bff213fb2f6425024b3
// libmaps-mobile.so SHA256: c2842633cc3db8301f252aff52df86aab4c64a8b10b826f80f4f922a327d340f
// ELF VA 0x17bbaa0; first PT_LOAD VA/file offset zero. x0={begin,end}, x8=output.
// Outer 0x17bb6c8 supplies x0=TileId {uint32 x,y,z}; nested per-thread context
// associates that identity with the decoded bytes, including compressed inputs.
// 0x17bb954 calls the decoder after optional decompression. We copy input only during
// naturally occurring calls, and emit it only when output+0x10 says parsing succeeded.
// Save Frida message callback's binary data as a .bin; decode on the host with:
// python3 -m tools.adas_bridge.yandex_vector_tile TILE.bin --out vector.json
// A prefix guard does not establish the complete loaded image's identity.
// The operator must verify the APK/library identity separately before attaching.
// Hard bounds: 8 inputs, 8 MiB combined, 60 seconds. No native calls or writes.
// Zero calls do not prove absence: another renderer format, e.g. vmap3fb, may run.
'use strict';

const module = Process.findModuleByName('libmaps-mobile.so');
if (module === null) throw new Error('libmaps-mobile.so missing');
const offset = 0x17bbaa0;
const address = module.base.add(offset);
const hex = b => Array.from(new Uint8Array(b)).map(x => ('0' + x.toString(16)).slice(-2)).join('');
const expectedPrefix = 'ff4301d1fd7b02a9f65703a9f44f04a9';
if (hex(address.readByteArray(16)) !== expectedPrefix) throw new Error('vmap3 decoder byte mismatch');
const contextOffset = 0x17bb6c8;
const contextAddress = module.base.add(contextOffset);
const expectedContextPrefix = 'ff8301d1fd7b04a9f44f05a9fd030191';
if (hex(contextAddress.readByteArray(16)) !== expectedContextPrefix) throw new Error('vmap3 context byte mismatch');
const layerOffset = 0x17c1bdc;
const layerAddress = module.base.add(layerOffset);
const expectedLayerPrefix = 'fd7bbaa9fc6f01a9fa6702a9f85f03a9';
if (hex(layerAddress.readByteArray(16)) !== expectedLayerPrefix) throw new Error('vmap3 layer byte mismatch');
const captureWindow = globalThis.DENZA_VECTOR_CAPTURE_WINDOW || null;
if (captureWindow) {
  for (const key of ['z', 'minX', 'maxX', 'minY', 'maxY']) {
    if (!Number.isInteger(captureWindow[key]) || captureWindow[key] < 0) throw new Error('Invalid capture window');
  }
  if (captureWindow.z > 30 || captureWindow.minX > captureWindow.maxX || captureWindow.minY > captureWindow.maxY
      || captureWindow.maxX >= Math.pow(2, captureWindow.z) || captureWindow.maxY >= Math.pow(2, captureWindow.z)) {
    throw new Error('Capture window outside tile pyramid');
  }
  Object.freeze(captureWindow);
}

const limits = Object.freeze({maxTiles: 8, maxBytes: 8388608, durationMs: 60000,
  minZoom: 14, minBytes: 32768, captureWindow});
const stats = {calls: 0, copied: 0, copiedBytes: 0, emitted: 0, emittedBytes: 0,
  contextCalls: 0, invalidContext: 0, missingContext: 0, contextMismatch: 0,
  layerCalls: 0, invalidLayer: 0, missingLayer: 0, layerMismatch: 0,
  skippedZoom: 0, skippedSmall: 0, skippedWindow: 0,
  rejectedSize: 0, parseFailed: 0, readErrors: 0, stopped: false};
const contexts = new Map();
const layers = new Map();
let listener;
let contextListener;
let layerListener;
let timer;
function stop(reason) {
  if (!stats.stopped) {
    stats.stopped = true;
    if (listener) listener.detach();
    if (contextListener) contextListener.detach();
    if (layerListener) layerListener.detach();
    if (timer) clearTimeout(timer);
    send({kind: 'vector_tile_observer_stop', reason, stats});
  }
  return stats;
}
function tileId(p) {
  const x = p.readU32(), y = p.add(4).readU32(), z = p.add(8).readU32();
  if (z > 30 || x >= Math.pow(2, z) || y >= Math.pow(2, z)) throw new Error('Invalid tile coordinates');
  return {x, y, z, rawHex: hex(p.readByteArray(12))};
}
layerListener = Interceptor.attach(layerAddress, {
  onEnter(args) {
    if (stats.stopped) return;
    stats.layerCalls++;
    const stack = layers.get(this.threadId) || [];
    this.frame = {renderTileId: null, sourceTileId: null};
    stack.push(this.frame);
    layers.set(this.threadId, stack);
    try {
      this.frame.renderTileId = tileId(args[0].add(0x18));
      this.frame.sourceTileId = tileId(args[0].add(0x38));
    } catch (error) { stats.invalidLayer++; }
  },
  onLeave() {
    if (!this.frame) return;
    const stack = layers.get(this.threadId);
    if (!stack || stack[stack.length - 1] !== this.frame) {
      stats.layerMismatch++;
      layers.delete(this.threadId);
      return;
    }
    stack.pop();
    if (!stack.length) layers.delete(this.threadId);
  }
});
contextListener = Interceptor.attach(contextAddress, {
  onEnter(args) {
    if (stats.stopped) return;
    stats.contextCalls++;
    const stack = contexts.get(this.threadId) || [];
    const layerStack = layers.get(this.threadId);
    const layer = layerStack && layerStack[layerStack.length - 1];
    this.frame = {tileId: null, layer: layer || null};
    stack.push(this.frame);
    contexts.set(this.threadId, stack);
    try {
      this.frame.tileId = tileId(args[0]);
    } catch (error) {
      stats.invalidContext++;
    }
  },
  onLeave() {
    if (!this.frame) return;
    const stack = contexts.get(this.threadId);
    if (!stack || stack[stack.length - 1] !== this.frame) {
      stats.contextMismatch++;
      contexts.delete(this.threadId);
      return;
    }
    stack.pop();
    if (stack.length === 0) contexts.delete(this.threadId);
  }
});
listener = Interceptor.attach(address, {
  onEnter(args) {
    if (stats.stopped || stats.copied >= limits.maxTiles) return;
    stats.calls++;
    try {
      const stack = contexts.get(this.threadId);
      const frame = stack && stack[stack.length - 1];
      if (!frame || !frame.tileId) {
        stats.missingContext++;
        return;
      }
      if (frame.tileId.z < limits.minZoom) {
        stats.skippedZoom++;
        return;
      }
      const tile = frame.tileId;
      if (captureWindow && (tile.z !== captureWindow.z || tile.x < captureWindow.minX
          || tile.x > captureWindow.maxX || tile.y < captureWindow.minY || tile.y > captureWindow.maxY)) {
        stats.skippedWindow++;
        return;
      }
      this.tileId = frame.tileId;
      this.layer = frame.layer;
      if (!this.layer) stats.missingLayer++;
      const begin = args[0].readPointer();
      const end = args[0].add(Process.pointerSize).readPointer();
      if (begin.isNull() || end.compare(begin) <= 0 || end.sub(begin).compare(ptr(limits.maxBytes)) > 0) {
        stats.rejectedSize++;
        return;
      }
      const size = end.sub(begin).toUInt32();
      if (size < limits.minBytes) {
        stats.skippedSmall++;
        return;
      }
      if (stats.copiedBytes + size > limits.maxBytes) {
        stats.rejectedSize++;
        return;
      }
      this.result = this.context.x8;
      this.bytes = begin.readByteArray(size);
      this.size = size;
      this.number = ++stats.copied;
      stats.copiedBytes += size;
      this.callerOffset = this.returnAddress.sub(module.base).toString();
    } catch (error) {
      stats.readErrors++;
      if (stats.readErrors <= 4) send({kind: 'vector_tile_observer_error', error: String(error)});
    }
  },
  onLeave() {
    if (!this.bytes) return;
    try {
      if (this.result.add(0x10).readU8() !== 1) {
        stats.parseFailed++;
      } else {
        stats.emitted++;
        stats.emittedBytes += this.size;
        send({kind: 'vector_tile_input', number: this.number, size: this.size,
          format: 'yandex.maps.proto.renderer.vmap3.Tile', decoderOffset: '0x17bbaa0',
          callerOffset: this.callerOffset, contextOffset: '0x17bb6c8',
          tileId: this.tileId, renderTileId: this.layer && this.layer.renderTileId,
          layerSourceTileId: this.layer && this.layer.sourceTileId,
          layerOffset: '0x17c1bdc', threadId: this.threadId, nativeParseSucceeded: true}, this.bytes);
      }
    } catch (error) {
      stats.readErrors++;
      if (stats.readErrors <= 4) send({kind: 'vector_tile_observer_error', error: String(error)});
    }
    this.bytes = null;
    if (stats.copied >= limits.maxTiles || stats.copiedBytes >= limits.maxBytes) stop('capture_budget');
  }
});
timer = setTimeout(() => stop('duration'), limits.durationMs);
rpc.exports = {stats: () => stats, stop: () => stop('rpc')};
send({kind: 'vector_tile_observer_start', address: address.toString(), decoderOffset: '0x17bbaa0',
  expectedPrefix, contextOffset: '0x17bb6c8', expectedContextPrefix,
  layerOffset: '0x17c1bdc', expectedLayerPrefix, limits});
