/* The YandexHUD map with the stock HUD camera (hud-link StockCamera with the calibrated MapKit
 * optics, see README «Stock camera»). Opt-in profile. Colours, route, arrows and cursor are our own
 * design: drivers preferred them to the stock fills tried first (2026-10-22). */
(function(root) {
'use strict';
/** An op moved down by dy: its path points, circle centre or text anchor. */
function shift(op, dy) {
    const moved = Object.assign({}, op);
    if (op.d) moved.d = op.d.map(step => step.map((value, i) => i > 0 && i % 2 === 0 ? value + dy : value));
    if (typeof op.y === 'number') moved.y = op.y + dy;
    if (typeof op.cy === 'number') moved.cy = op.cy + dy;
    if (op.ops) moved.ops = op.ops.map(inner => shift(inner, dy));
    return moved;
}
function theme(base) {
    const value = JSON.parse(JSON.stringify(base.theme()));
    value.profile = 'stock-prototype';
    // The stock camera keeps the car at (150,135) of the frame, our design at (150,143): the cursor
    // and the box icons keep off move up with it.
    const dy = 135 - value.size.focus[1];
    value.size.focus = [150, 135];
    value.cursor = value.cursor.map(op => shift(op, dy));
    value.events.cursor = [value.events.cursor[0], value.events.cursor[1] + dy, value.events.cursor[2], value.events.cursor[3]];
    // Not stock: the stock camera backs off up to 1.1 km before a turn and stays there in a jam;
    // drivers wanted the map close when slow, and the whole scale half a level closer.
    value.camera = {law:'stock', maxFps:5, policy:{closeZoom:17, closeSpeed:20, freeSpeed:60, zoomOffset:.5}};
    return value;
}
const api = {theme};
if (typeof module !== 'undefined') module.exports = api;
root.HudStockPrototype = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
