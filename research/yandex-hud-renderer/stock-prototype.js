/* Experimental BYD-like presentation of Yandex's own map/route, not a recovered BYD renderer.
 * Opt-in only. The camera is the stock HUD's (hud-link StockCamera with the calibrated MapKit
 * optics, see README «Stock camera»); colours, cursor and widths are visual starting points. */
(function(root) {
'use strict';
function theme(base) {
    const value = JSON.parse(JSON.stringify(base.theme()));
    value.profile = 'stock-prototype';
    value.size.focus = [150, 135];
    // These traffic fills are from Device_7/route_path_layer online_highlight.
    // BLOCKED has no confirmed one-to-one stock equivalent; keep its Yandex warning colour.
    value.traffic = {UNKNOWN:'#2E69E1', BLOCKED:'#D0D4DC', FREE:'#14A064',
        LIGHT:'#A98609', HARD:'#C10A2F', VERY_HARD:'#890F2F'};
    value.route.width = 9; // Visual starting point in MapKit pixels, not converted GBL units.
    value.route.outline = 1.5;
    value.route.gradient = 0;
    value.route.arrow.fill = '#F0F9FF';
    value.route.arrow.outline = '#2C428A';
    // The stock HUD camera; widths and icons above stay in frame pixels, the module doubles them
    // for its 600x540 window. policy: the adapter choices StockCamera.Policy names, if any change.
    value.camera = {law:'stock', maxFps:5};
    // Approximation of the measured blue stock cursor silhouette, not the skeleton model.
    value.cursor = [{op:'shape',d:[['M',150,121],['L',166,150],['L',150,145],['L',134,150],['Z']],fill:'#0066FF'}];
    value.events.cursor = [132,119,36,33];
    value.prototypeNotes = {
        measured:'300x180 output, blue cursor, approximately (150,135) anchor; stock device target 5fps',
        extracted:'traffic fills, no route gradient, HUD zoom caps 14..17',
        approximated:'route width9/outline1.5, cursor vector; camera: road class from highway+limit, curvature window, display rect, no ComplexRoad',
        retained:'existing Yandex road shade, event icons, native manoeuvre and lane slots',
        limitation:'No complete stock road/route frame exists for pixel matching; GBL pitch and MapKit tilt are not assumed equivalent'
    };
    return value;
}
const api = {theme};
if (typeof module !== 'undefined') module.exports = api;
root.HudStockPrototype = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
