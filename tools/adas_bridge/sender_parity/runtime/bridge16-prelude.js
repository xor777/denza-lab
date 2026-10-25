// Frida 16.7.19 bundles Java through a lazy configurable accessor. Replace that
// accessor before its first read, so only this explicitly pinned bridge starts.
const before = Object.getOwnPropertyDescriptor(globalThis, 'Java');
if (before === undefined || !before.configurable || typeof before.get !== 'function' ||
    Object.prototype.hasOwnProperty.call(before, 'value')) {
  throw new Error('Expected unused Frida 16 lazy Java accessor; refusing a second bridge');
}
const importedBridge = require('frida-java-bridge');
const JavaBridge = importedBridge.default ?? importedBridge;
const afterImport = Object.getOwnPropertyDescriptor(globalThis, 'Java');
if (afterImport === undefined || afterImport.get !== before.get ||
    Object.prototype.hasOwnProperty.call(afterImport, 'value')) {
  throw new Error('Built-in Java accessor changed while importing explicit bridge');
}
Object.defineProperty(globalThis, 'Java', {
  value: JavaBridge, enumerable: true, configurable: false, writable: false
});
globalThis.DenzaBridgeSelection = Object.freeze({
  schema: 'denza-explicit-java-bridge-v1',
  frida_version: '16.7.19', bridge_version: '6.3.9',
  builtin_java_was_unused_accessor: true,
  explicit_java_is_global: globalThis.Java === JavaBridge
});
