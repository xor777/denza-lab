/* Reads pinned Navigator 29.8.1 objects. Loaded after hud-scene.js in Frida. */
'use strict';
const HudNavigator = (() => {
    const actions = {STRAIGHT:'straight', LEFT:'left', RIGHT:'right', SLIGHT_LEFT:'slight-left',
        SLIGHT_RIGHT:'slight-right', HARD_LEFT:'hard-left', HARD_RIGHT:'hard-right',
        FORK_LEFT:'fork-left', FORK_RIGHT:'fork-right', EXIT_LEFT:'fork-left', EXIT_RIGHT:'fork-right',
        UTURN_LEFT:'uturn-left', UTURN_RIGHT:'uturn-right', ENTER_ROUNDABOUT:'roundabout',
        LEAVE_ROUNDABOUT:'roundabout', FINISH:'finish', WAYPOINT:'waypoint'};
    const directions = {STRAIGHT_AHEAD:'straight', LEFT90:'left', RIGHT90:'right', LEFT45:'slight-left',
        RIGHT45:'slight-right', LEFT135:'hard-left', RIGHT135:'hard-right', LEFT180:'uturn-left', RIGHT180:'uturn-right'};
    const list = l => Array.from({length:l.size()}, (_,i) => l.get(i));
    const unbox = n => n === null ? null : n.doubleValue();
    function read(g, freshness, now) {
        let input = {guidanceActive:false, routePresent:false, locationFresh:false, lanes:[]};
        if (!g || !g.isValid()) return {input};
        const route=g.route(), auto=g.navigation().getGuidance();
        const status=String(auto.getRouteStatus());
        input.guidanceActive=!!g.isGuidanceResumed() && !!route && status!=='ROUTE_FINISHED';
        input.routePresent=!!route;
        if (!input.guidanceActive) return {input, route, status};
        input.rerouting=['NOT_ON_ROUTE','ROUTE_LOST'].includes(status);
        const classified=g.getLocation(), location=classified?classified.getLocation():null;
        const locationClass=classified?String(classified.getLocationClass()):'NONE';
        if(location) {
            const stamp=String(location.getAbsoluteTimestamp());
            if(stamp!==freshness.stamp) {freshness.stamp=stamp;freshness.changedAt=now;}
            input.locationFresh=['FINE','EXTRAPOLATED'].includes(locationClass) && now-freshness.changedAt<=3000;
        }
        const position=g.getRoutePosition();
        // No geometry matching means no reliable camera/remaining-route marker.
        input.locationFresh=input.locationFresh && !!position;
        if(!input.locationFresh || input.rerouting) return {input,route,location,position,status,locationClass};
        const windshield=auto.getWindshield(), upcoming=windshield.getManoeuvres(), next=g.getNextManeuver();
        if(next && upcoming.size()>0) {
            const upcomingItem=Java.cast(upcoming.get(0), Java.use('com.yandex.mapkit.navigation.automotive.UpcomingManoeuvre'));
            const annotation=upcomingItem.getAnnotation(), action=String(annotation.getAction());
            const kind=actions[action];
            // These two APIs must describe the same maneuver point; omit a mismatched hint.
            const a=next.getPoint(), b=upcomingItem.getPosition().getPoint();
            const delta=Math.hypot(a.getLatitude()-b.getLatitude(),a.getLongitude()-b.getLongitude());
            if(kind && delta<0.00002) input.maneuver={kind,distance:next.getDistance(),sourceAction:action};
        }
        const laneDistance=unbox(g.getNextLaneSignDistance()), signs=windshield.getLaneSigns();
        if(laneDistance!==null && laneDistance>=0 && laneDistance<=800 && signs.size()>0) {
            const sign=Java.cast(signs.get(0), Java.use('com.yandex.mapkit.navigation.automotive.UpcomingLaneSign'));
            input.lanes=list(sign.getLaneSign().getLanes()).map(l=>Java.cast(l,Java.use('com.yandex.mapkit.directions.driving.Lane'))).map(l=>({
                kind:String(l.getLaneKind()), directions:list(l.getDirections()).map(d=>directions[String(d)] || 'unsupported'),
                highlight:l.getHighlightedDirection() ? (directions[String(l.getHighlightedDirection())] || 'unsupported') : null
            }));
        }
        return {input,route,location,position,status,locationClass,laneDistance};
    }
    function traffic(route, cache, now) {
        const count=route.getGeometry().getPoints().size()-1, id=String(route.getRouteId());
        const outdated=!!route.isAreConditionsOutdated();
        if(cache.id!==id || cache.count!==count || cache.outdated!==outdated || now-cache.at>=1000){
            const jams=route.getJamSegments(), valid=!outdated && jams.size()===count;
            const colors=Java.use('java.util.ArrayList').$new(), Integer=Java.use('java.lang.Integer');
            const types={}, Segment=Java.use('com.yandex.mapkit.navigation.JamSegment');
            for(let i=0;i<count;i++){
                const type=valid?String(Java.cast(jams.get(i),Segment).getJamType()):'UNKNOWN';
                const index=Math.max(0,HudScene.JAM_TYPES.indexOf(type));
                colors.add(Integer.valueOf(index));types[type]=(types[type]||0)+1;
            }
            if(cache.colors)cache.colors.$dispose();
            Object.assign(cache,{id,count,outdated,at:now,colors,types,valid,jamCount:jams.size()});
        }
        return cache;
    }
    function remainingGeometry(route, position) {
        const points=route.getGeometry().getPoints(), i=position.getSegmentIndex(), t=position.getSegmentPosition();
        if(i<0 || i+1>=points.size() || t<0 || t>1) return null;
        const Point=Java.use('com.yandex.mapkit.geometry.Point');
        const a=Java.cast(points.get(i),Point), b=Java.cast(points.get(i+1),Point);
        const rest=Java.use('java.util.ArrayList').$new();
        rest.add(Java.use('com.yandex.mapkit.geometry.Point').$new(
            a.getLatitude()+(b.getLatitude()-a.getLatitude())*t,
            a.getLongitude()+(b.getLongitude()-a.getLongitude())*t));
        rest.addAll(points.subList(i+1,points.size()));
        return Java.use('com.yandex.mapkit.geometry.Polyline').$new(rest);
    }
    return {read, remainingGeometry, traffic, actions, directions};
})();
if(typeof module!=='undefined') module.exports=HudNavigator;
