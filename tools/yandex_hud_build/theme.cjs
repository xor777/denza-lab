#!/usr/bin/env node
// Prints the module's assets/denza-hud/theme.json: every colour, size and template from the shared scene.
const scene = require('../../research/yandex-hud-renderer/hud-scene.js');
process.stdout.write(JSON.stringify(scene.theme()));
