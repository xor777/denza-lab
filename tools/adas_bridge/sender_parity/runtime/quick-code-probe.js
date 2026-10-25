// Optional read-only probe for the exact old frida-java-bridge 6.3.9 layout.
// Retain already-hooked overload objects; this code never loads an app class,
// calls a native getter, writes an ArtMethod, or installs a hook.
globalThis.DenzaQuickCodeProbe = (() => {
  const methods = new Map();
  function codeLocation(address) {
    const range = Process.findRangeByAddress(address), module = Process.findModuleByAddress(address);
    return {address: String(address), symbol: String(DebugSymbol.fromAddress(address)),
      module: module === null ? null : {name: module.name, path: module.path, base: String(module.base)},
      range: range === null ? null : {base: String(range.base), size: range.size,
        protection: range.protection, file: range.file || null}};
  }
  function flags(mangler, quickOffset, api) {
    const original = mangler.originalMethod.accessFlags >>> 0;
    const replacementExpected = ((original & ~(0x00200000 | 0x00080000 | 0x00100000)) |
      0x0100 | api.kAccCompileDontBother) >>> 0;
    const removed = (0x40000000 | 0x08000000 | 0x00100000 | ((original & 0x0100) === 0 ? 0x00080000 : 0)) >>> 0;
    const expected = ((original & ~removed) | api.kAccCompileDontBother) >>> 0;
    let offset, source, lookup = null;
    if (typeof globalThis.DenzaBridgeArtProbe === 'function') {
      lookup = globalThis.DenzaBridgeArtProbe(mangler.hookedMethodId);
      offset = lookup.offsets.accessFlags;
      source = 'exact bridge getArtMethodSpec';
    } else {
      const matches = [];
      for (let at = 0; at < quickOffset - Process.pointerSize; at += 4)
        if (mangler.replacementMethodId.add(at).readU32() === replacementExpected) matches.push(at);
      if (matches.length !== 1) return {error: 'Ambiguous accessFlags offset', matches,
        original, expected, replacement_expected: replacementExpected};
      offset = matches[0]; source = 'unique replacement clone native flags before JNI pointer';
    }
    const current = mangler.hookedMethodId.add(offset).readU32();
    return {offset, offset_source: source, install_original: original, expected_hooked: expected,
      current, changed_from_expected: (current ^ expected) >>> 0,
      restored_removed_bits: (current & removed) >>> 0,
      fast_interpreter_to_interpreter: !!(current & 0x40000000),
      skip_access_checks: !!(current & 0x00080000), single_implementation: !!(current & 0x08000000),
      nterp_entry_fast: !!(current & 0x00100000), nterp_invoke_fast: !!(current & 0x00200000),
      compile_dont_bother: !!(current & api.kAccCompileDontBother), bridge_lookup: lookup};
  }
  return {
    retain(label, overload) { methods.set(label, overload); },
    inventory() {
      const api = Java.api, module = api.module;
      const old = /^_ZN3art11interpreter6DoCallILb[0-1]ELb[0-1]EEEbPNS_9ArtMethodEPNS_6ThreadERNS_11ShadowFrameEPKNS_11InstructionEtPNS_6JValueE$/;
      const modern = /^_ZN3art11interpreter6DoCallILb[0-1]EEEbPNS_9ArtMethodEPNS_6ThreadERNS_11ShadowFrameEPKNS_11InstructionEtbPNS_6JValueE$/;
      const unique = new Map();
      for (const entry of [...module.enumerateExports(), ...module.enumerateSymbols()]) {
        if (entry.name.includes('interpreter6DoCall')) unique.set(entry.name + ':' + entry.address, entry);
      }
      return {module: {name: module.name, path: module.path, base: String(module.base), size: module.size},
        api34_apex_heuristic: api.isApiLevel34OrApexEquivalent,
        nterp: String(api.artNterpEntryPoint), quick_to_interpreter: String(api.artQuickToInterpreterBridge),
        do_call: Array.from(unique.values(), entry => ({name: entry.name, address: String(entry.address),
          matches_legacy: old.test(entry.name), matches_api34: modern.test(entry.name)})),
        visibility_fixup_symbols: [
          '_ZN3art11ClassLinker26VisiblyInitializedCallback22MarkVisiblyInitializedEPNS_6ThreadE',
          '_ZN3art11ClassLinker26VisiblyInitializedCallback29AdjustThreadVisibilityCounterEPNS_6ThreadEl'
        ].map(name => ({name, address: String(module.findSymbolByName(name))}))};
    },
    snapshot(stage) {
      const api = Java.api;
      const rows = [];
      for (const [label, overload] of methods) {
        try {
          const mangler = overload._r && overload._r._m;
          if (!mangler || !mangler.replacementMethodId) {
            rows.push({label, error: 'No current ART replacement mangler'});
            continue;
          }
          const target = api.artClassLinker.quickGenericJniTrampoline;
          const matches = [];
          // The bridge itself scans 64 bytes to discover ArtMethod fields.
          for (let offset = 0; offset < 64; offset += Process.pointerSize) {
            if (mangler.replacementMethodId.add(offset).readPointer().equals(target))
              matches.push(offset);
          }
          if (matches.length !== 1) {
            rows.push({label, error: 'Ambiguous replacement quickCode offset', matches});
            continue;
          }
          const offset = matches[0];
          const current = mangler.hookedMethodId.add(offset).readPointer();
          const original = mangler.originalMethod.quickCode;
          rows.push({label, method: String(mangler.hookedMethodId),
            replacement: String(mangler.replacementMethodId), quick_code_offset: offset,
            current_quick_code: String(current), original_quick_code: String(original),
            current_code_location: codeLocation(current), original_code_location: codeLocation(original),
            access_flags: flags(mangler, offset, api),
            is_nterp: api.artNterpEntryPoint !== null && current.equals(api.artNterpEntryPoint),
            is_quick_to_interpreter: current.equals(api.artQuickToInterpreterBridge),
            equals_install_original: current.equals(original)});
        } catch (error) { rows.push({label, error: String(error)}); }
      }
      return {stage, architecture: Process.arch, pointer_size: Process.pointerSize,
        nterp: String(api.artNterpEntryPoint),
        quick_to_interpreter: String(api.artQuickToInterpreterBridge), rows};
    }
  };
})();
