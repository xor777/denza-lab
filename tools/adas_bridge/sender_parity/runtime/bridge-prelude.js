// Build as IIFE with the pinned frida-java-bridge package, then prefix the agent.
import Java from 'frida-java-bridge';

globalThis.Java = Java;

// Only the two legacy APIs used by the unchanged private runner/observers.
if (typeof Module.findExportByName !== 'function') {
  Module.findExportByName = function (moduleName, symbol) {
    if (moduleName === null) return Module.findGlobalExportByName(symbol);
    const module = Process.findModuleByName(moduleName);
    return module === null ? null : module.findExportByName(symbol);
  };
}
if (typeof Module.getExportByName !== 'function') {
  Module.getExportByName = function (moduleName, symbol) {
    return moduleName === null
      ? Module.getGlobalExportByName(symbol)
      : Process.getModuleByName(moduleName).getExportByName(symbol);
  };
}
