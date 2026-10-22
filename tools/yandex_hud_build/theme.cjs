#!/usr/bin/env node
// Prints the module's assets/denza-hud/theme.json: every colour, size and template from the shared scene.
const scene = require('../../research/yandex-hud-renderer/hud-scene.js');
const profile = process.argv[2] || 'current';
if (!['current', 'stock-prototype'].includes(profile)) throw new Error('Unknown renderer profile: ' + profile);
const theme = profile === 'stock-prototype'
    ? require('../../research/yandex-hud-renderer/stock-prototype.js').theme(scene) : scene.theme();
process.stdout.write(JSON.stringify(theme));
