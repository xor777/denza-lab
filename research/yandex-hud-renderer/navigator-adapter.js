/* Pinned Navigator 29.8.1 bridge. All MapKit access stays on Android's main thread. */
'use strict';
const HudNavigator = (() => {
    const directions = {
        STRAIGHT_AHEAD: 'straight', LEFT90: 'left', RIGHT90: 'right',
        LEFT45: 'slight-left', RIGHT45: 'slight-right',
        LEFT135: 'hard-left', RIGHT135: 'hard-right',
        LEFT180: 'uturn-left', RIGHT180: 'uturn-right'
    };
    const list = value => Array.from({length: value.size()}, (_, i) => value.get(i));
    let classes;
    let listenerClass, nextListenerId = 0;
    const listeners = new Map();
    function observeConditions(route, changed) {
        if (!listenerClass) {
            const notify = function () { const callback = listeners.get(this.id.value); if (callback) callback(); };
            listenerClass = Java.registerClass({
                name: 'dev.denza.hud.probe.Conditions_' + Date.now().toString(16),
                implements: [Java.use('com.yandex.mapkit.directions.driving.ConditionsListener')],
                fields: {id: 'int'},
                methods: {onConditionsUpdated: notify, onConditionsOutdated: notify}
            });
        }
        const id = ++nextListenerId;
        const listener = listenerClass.$new();
        listener.id.value = id;
        const retainedRoute = Java.retain(route);
        listeners.set(id, changed);
        const unsubscribe = () => {
            // Remove the callback first, including when native unsubscription fails.
            listeners.delete(id);
            try { retainedRoute.removeConditionsListener(listener); }
            finally { listener.$dispose(); retainedRoute.$dispose(); }
        };
        try { retainedRoute.addConditionsListener(listener); }
        catch (error) { unsubscribe(); throw error; }
        return unsubscribe;
    }
    function api() {
        return classes || (classes = {
            Lane: Java.use('com.yandex.mapkit.directions.driving.Lane'),
            LaneSign: Java.use('com.yandex.mapkit.navigation.automotive.UpcomingLaneSign')
        });
    }

    function read(guidance, freshness, now) {
        const input = {guidanceActive: false, routePresent: false, locationFresh: false, lanes: []};
        if (!guidance || !guidance.isValid()) return {input};
        const route = guidance.route();
        const automotive = guidance.navigation().getGuidance();
        const status = String(automotive.getRouteStatus());
        input.guidanceActive = !!guidance.isGuidanceResumed() && !!route && status !== 'ROUTE_FINISHED';
        input.routePresent = !!route;
        if (!input.guidanceActive) {
            freshness.stamp = null;
            return {input, route, status};
        }
        input.rerouting = status === 'NOT_ON_ROUTE' || status === 'ROUTE_LOST';
        const classified = guidance.getLocation();
        const location = classified ? classified.getLocation() : null;
        const locationClass = classified ? String(classified.getLocationClass()) : 'NONE';
        if (location) {
            const stamp = String(location.getAbsoluteTimestamp());
            if (stamp !== freshness.stamp) {
                freshness.stamp = stamp;
                freshness.changedAt = now;
            }
            input.locationFresh = (locationClass === 'FINE' || locationClass === 'EXTRAPOLATED') &&
                now - freshness.changedAt <= 3000;
        }
        const position = guidance.getRoutePosition();
        input.locationFresh = input.locationFresh && !!position;
        const result = {input, route, location, position, status, locationClass};
        if (!input.locationFresh || input.rerouting) return result;

        // Distance affects only camera framing. No maneuver annotations or glyphs are needed.
        const next = guidance.getNextManeuver();
        result.cameraDistance = next ? next.getDistance() : undefined;
        const boxedDistance = guidance.getNextLaneSignDistance();
        const distance = boxedDistance === null ? null : boxedDistance.doubleValue();
        if (distance === null || distance < 0 || distance > 800) return result;
        const signs = automotive.getWindshield().getLaneSigns();
        if (signs.size() === 0) return result;
        const sign = Java.cast(signs.get(0), api().LaneSign);
        input.lanes = list(sign.getLaneSign().getLanes()).map(value => {
            const lane = Java.cast(value, api().Lane);
            const highlighted = lane.getHighlightedDirection();
            return {
                kind: String(lane.getLaneKind()),
                directions: list(lane.getDirections()).map(d => directions[String(d)] || 'unsupported'),
                highlight: highlighted ? (directions[String(highlighted)] || 'unsupported') : null
            };
        });
        return result;
    }

    function traffic(route, cache, now, count, id) {
        count = count ?? route.getGeometry().getPoints().size() - 1;
        id = id ?? String(route.getRouteId());
        const outdated = !!route.isAreConditionsOutdated();
        if (cache.id === id && cache.count === count && cache.outdated === outdated && now < cache.nextRead) {
            return cache;
        }
        const jams = route.getJamSegments();
        const jamCount = jams.size();
        const valid = !outdated && jamCount === count;
        const Segment = Java.use('com.yandex.mapkit.navigation.JamSegment');
        const indices = [];
        const types = {};
        for (let i = 0; i < count; i++) {
            const raw = valid ? String(Java.cast(jams.get(i), Segment).getJamType()) : 'UNKNOWN';
            const index = Math.max(0, HudScene.JAM_TYPES.indexOf(raw));
            const type = HudScene.JAM_TYPES[index];
            indices.push(index);
            types[type] = (types[type] || 0) + 1;
        }
        const signature = indices.join(''); // Six single-digit palette indices; unambiguous.
        if (cache.signature !== signature || cache.id !== id) {
            const colors = Java.use('java.util.ArrayList').$new();
            const Integer = Java.use('java.lang.Integer');
            const palette = HudScene.JAM_TYPES.map((_, i) => Integer.valueOf(i));
            try {
                for (const index of indices) colors.add(palette[index]);
            } catch (error) {
                colors.$dispose();
                throw error;
            } finally {
                for (const value of palette) if (value.$dispose) value.$dispose();
            }
            if (cache.colors) cache.colors.$dispose();
            cache.colors = colors;
            cache.revision = (cache.revision || 0) + 1;
        }
        Object.assign(cache, {id, count, outdated, nextRead: now + (cache.eventDriven ? 30000 : 1000), signature, types, valid, jamCount});
        return cache;
    }

    // One native geometry per route, original segment indices for its entire lifetime.
    // hide(Subpolyline) advances the visible start without rebuilding or resetting colors.
    function createRouteLayer(objects, baseObjects) {
        const Position = Java.use('com.yandex.mapkit.geometry.PolylinePosition');
        const Subpolyline = Java.use('com.yandex.mapkit.geometry.Subpolyline');
        const start = Position.$new(0, 0);
        let line = null, routeId = null, segmentCount = 0, uploadedRevision = 0, lastProgress = null;
        let cache = {}, unsubscribe = null;
        const stats = {geometryBuilds: 0, trafficUploads: 0, progressUpdates: 0, conditionsEvents: 0};

        function clear() {
            const oldLine = line, oldColors = cache.colors, oldUnsubscribe = unsubscribe;
            line = null;
            routeId = null;
            lastProgress = null;
            uploadedRevision = 0;
            cache = {};
            unsubscribe = null;
            const errors = [];
            for (const release of [
                () => { if (oldUnsubscribe) oldUnsubscribe(); },
                () => { if (oldLine) baseObjects.remove(oldLine); },
                () => { if (oldLine) oldLine.$dispose(); },
                () => { if (oldColors) oldColors.$dispose(); }
            ]) {
                try { release(); } catch (error) { errors.push(String(error)); }
            }
            if (errors.length) throw new Error(errors.join('; '));
        }

        function update(route, position, now) {
            const id = String(route.getRouteId());
            if (id !== routeId) {
                clear();
                const geometry = route.getGeometry();
                segmentCount = geometry.getPoints().size() - 1;
                if (segmentCount < 1) return false;
                line = objects.addPolyline(geometry);
                HudScene.JAM_TYPES.forEach((type, index) =>
                    line.setPaletteColor(index, (0xff000000 | parseInt(HudScene.TRAFFIC[type].slice(1), 16)) | 0));
                line.setStrokeWidth(5);
                line.setOutlineColor(-16777216);
                line.setOutlineWidth(1);
                cache.eventDriven = true;
                unsubscribe = observeConditions(route, () => { cache.nextRead = 0; stats.conditionsEvents++; });
                routeId = id;
                stats.geometryBuilds++;
            }
            const segment = position.getSegmentIndex(), fraction = position.getSegmentPosition();
            if (!Number.isInteger(segment) || segment < 0 || segment >= segmentCount ||
                !Number.isFinite(fraction) || fraction < 0 || fraction > 1) {
                clear();
                return false;
            }
            traffic(route, cache, now, segmentCount, id);
            if (cache.revision !== uploadedRevision) {
                line.setStrokeColors(cache.colors);
                uploadedRevision = cache.revision;
                stats.trafficUploads++;
            }
            const progress = segment + ':' + fraction;
            if (progress !== lastProgress) {
                const hidden = Subpolyline.$new(start, position);
                try { line.hide.overload('com.yandex.mapkit.geometry.Subpolyline').call(line, hidden); }
                finally { hidden.$dispose(); }
                lastProgress = progress;
                stats.progressUpdates++;
            }
            return true;
        }

        return {
            update, clear, stats,
            traffic: () => ({valid: cache.valid, outdated: cache.outdated, count: cache.count,
                jamCount: cache.jamCount, types: cache.types}),
            dispose() { try { clear(); } finally { start.$dispose(); } }
        };
    }

    return {read, traffic, createRouteLayer, directions};
})();
if (typeof module !== 'undefined') module.exports = HudNavigator;
