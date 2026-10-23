"""Amap semantics of a NaviToEHPData route and the transition kinds it covers.

Enums are the SDK's own (com.autonavi.gbl.common.path.model.{RoadClass,Formway,
LinkType,MainAction,AssistantAction} in the BydLaunchermap decompile). `transitions()`
finds, in any route payload (factory stock, stock-on-OSM or the bridge), the places
the ADAS bench must cover: road-type changes, ramps, interchanges, side roads,
forks, signalised junctions by turn, roundabouts, tunnels/bridges, tolls, lane-count
changes, short links, start and arrival kinds. Each hit carries the link index and
the along-route distance so traces can be aligned around it.
"""
from __future__ import annotations

import math

ROAD_CLASS = {-1: 'NULL', 0: 'Freeway', 1: 'NationalRoad', 2: 'ProvinceRoad', 3: 'CountyRoad', 4: 'RuralRoad',
              5: 'InCountyRoad', 6: 'CitySpeedway', 7: 'MainRoad', 8: 'SecondaryRoad', 9: 'CommonRoad', 10: 'NonNaviRoad'}
FORMWAY = {-1: 'NULL', 1: 'DivisedLink', 2: 'CrossLink', 3: 'JCT', 4: 'RoundCircle', 5: 'ServiceRoad', 6: 'SlipRoad',
           7: 'SideRoad', 8: 'SlipJCT', 9: 'ExitLink', 10: 'EntranceLink', 11: 'TurnRightLineA', 12: 'TurnRightLineB',
           13: 'TurnLeftLineA', 14: 'TurnLeftLineB', 15: 'CommonLink', 16: 'TurnLeftRightLine', 53: 'ServiceJCTRoad',
           56: 'ServiceSlipRoad', 58: 'ServiceSlipJCTRoad'}
LINK_TYPE = {-1: 'Null', 0: 'Common', 1: 'Ferry', 2: 'Tunnel', 3: 'Bridge', 4: 'ElevatedRd'}
MAIN_ACTION = {0: 'NULL', 1: 'TurnLeft', 2: 'TurnRight', 3: 'SlightLeft', 4: 'SlightRight', 5: 'TurnHardLeft',
               6: 'TurnHardRight', 7: 'UTurn', 8: 'Continue', 9: 'MergeLeft', 10: 'MergeRight', 11: 'EntryRing',
               12: 'LeaveRing', 13: 'Slow', 14: 'PlugContinue'}
ASSIST_TOLL, ASSIST_ARRIVE = 34, 36
# The owner's coverage matrix (2026-10-23), as kinds() names them.
TARGET_KINDS = ['expressway->exit->arterial', 'arterial->entry->expressway', 'expressway->interchange->expressway',
                'main->side-road', 'side-road->main', 'parallel-road', 'mix-fork', 'multi-out-fork',
                'signal:straight', 'signal:left', 'signal:right', 'signal:u-turn', 'u-turn',
                'roundabout-entry', 'roundabout-exit', 'arterial->secondary', 'secondary->ordinary',
                'ordinary->minor', 'minor->ordinary', 'ordinary->secondary', 'secondary->arterial',
                'tunnel', 'bridge', 'elevatedrd', 'toll', 'lanes-merge', 'lanes-split', 'short-segment',
                'start:expressway', 'start:arterial', 'start:secondary', 'start:ordinary', 'start:minor',
                'arrival:expressway', 'arrival:arterial', 'arrival:secondary', 'arrival:ordinary', 'arrival:minor']
RAMP_FORMWAYS = {3, 6, 8, 9, 10, 53, 56, 58}
TURN_LANE_FORMWAYS = {11, 12, 13, 14, 16}


def road_group(link):
    """Coarse road type used to pair Beijing and Moscow roads (class first, then form)."""
    rc, fw = link.get('roadclass'), link.get('formway')
    if fw in RAMP_FORMWAYS:
        return 'ramp'
    if fw == 4:
        return 'roundabout'
    if rc in (0, 6):
        return 'expressway'
    if rc in (1, 2, 7):
        return 'arterial'
    if rc == 8:
        return 'secondary'
    if rc == 9:
        return 'ordinary'
    if rc in (3, 4, 5, 10):
        return 'minor'
    return 'unknown'


def _cumulative(links):
    out, total = [], 0.0
    for link in links:
        out.append(total)
        total += link.get('len') or 0
    return out, total


def transitions(route):
    """List of {kind, link, distM, detail} for one NaviToEHPData dict."""
    links = route.get('links') or []
    if not links:
        return []
    starts, total = _cumulative(links)
    hits = []

    def hit(kind, index, **detail):
        hits.append(dict(kind=kind, link=index, distM=round(starts[index], 1), **detail))

    groups = [road_group(l) for l in links]
    segment_end_action = {}
    for group in route.get('guideGroups') or []:
        for segment in group.get('segments') or []:
            if segment.get('linkBegIdx') is not None and segment.get('crntSegmLinkCnt'):
                segment_end_action[segment['linkBegIdx'] + segment['crntSegmLinkCnt'] - 1] = segment.get('navigationMainAction')
    hit('start:' + groups[0], 0)
    hit('arrival:' + groups[-1], len(links) - 1)
    # Road-type changes, collapsing ramps between two main roads into one transition.
    i = 0
    while i < len(links) - 1:
        a = groups[i]
        j = i + 1
        while j < len(links) and groups[j] == a:
            j += 1
        if j >= len(links):
            break
        b = groups[j]
        if b == 'ramp':
            k = j
            while k < len(links) and groups[k] == 'ramp':
                k += 1
            after = groups[k] if k < len(links) else 'end'
            kinds = {links[x].get('formway') for x in range(j, k)}
            via = 'interchange' if kinds & {3, 8, 53, 58} else 'exit' if 9 in kinds else 'entry' if 10 in kinds else 'ramp'
            hit(f'{a}->{via}->{after}', j, rampLinks=k - j)
            i = k
            continue
        hit(f'{a}->{b}', j)
        i = j
    for index, link in enumerate(links):
        previous = links[index - 1] if index else None
        if previous is not None:
            if link.get('formway') == 7 and previous.get('formway') != 7:
                hit('main->side-road', index)
            if previous.get('formway') == 7 and link.get('formway') != 7:
                hit('side-road->main', index)
            if link.get('formway') == 4 and previous.get('formway') != 4:
                hit('roundabout-entry', index)
            if previous.get('formway') == 4 and link.get('formway') != 4:
                hit('roundabout-exit', index)
            if link.get('linktype') in (2, 3, 4) and previous.get('linktype') != link.get('linktype'):
                hit(LINK_TYPE[link['linktype']].lower(), index)
            if (link.get('laneNum') or 0) and (previous.get('laneNum') or 0) and link['laneNum'] != previous['laneNum']:
                hit('lanes-' + ('split' if link['laneNum'] > previous['laneNum'] else 'merge'), index,
                    lanes=[previous['laneNum'], link['laneNum']])
            if link.get('formway') in TURN_LANE_FORMWAYS and previous.get('formway') not in TURN_LANE_FORMWAYS:
                hit('turn-lane-link', index, formway=FORMWAY.get(link['formway']))
        if link.get('hasParallel'):
            if not previous or not previous.get('hasParallel'):
                hit('parallel-road', index)
        if link.get('hasMixFork') and (not previous or not previous.get('hasMixFork')):
            hit('mix-fork', index)
        if link.get('hasMultiOut') and (not previous or not previous.get('hasMultiOut')):
            hit('multi-out-fork', index)
        if link.get('hasTrafficLight'):
            # The turn at a light is the guide segment's end action when the light ends that segment
            # (link-level mainAction is mostly MainActionNULL in factory exports).
            action = MAIN_ACTION.get(segment_end_action.get(index), 'Continue')
            turn = {'TurnLeft': 'left', 'TurnHardLeft': 'left', 'SlightLeft': 'left', 'TurnRight': 'right',
                    'TurnHardRight': 'right', 'SlightRight': 'right', 'UTurn': 'u-turn'}.get(action, 'straight')
            hit('signal:' + turn, index)
        if link.get('assistantAction') == ASSIST_TOLL:
            hit('toll', index)
    for group in route.get('guideGroups') or []:
        for segment in group.get('segments') or []:
            if (segment.get('navigationLen') or 0) < 60 and segment.get('linkBegIdx') is not None:
                hit('short-segment', min(segment['linkBegIdx'], len(links) - 1), lenM=segment.get('navigationLen'))
            if segment.get('navigationAssitAction') == ASSIST_TOLL:
                hit('toll', min(segment['linkBegIdx'], len(links) - 1))
            if segment.get('navigationMainAction') == 7:
                hit('u-turn', min(segment['linkBegIdx'] + segment['crntSegmLinkCnt'] - 1, len(links) - 1))
    return hits


def kinds(route):
    return sorted({h['kind'] for h in transitions(route)})


def greedy(routes, limit, *, max_len=None):
    """routes: {id: route}. Pick up to `limit` ids maximising new kinds, the owner's matrix
    (TARGET_KINDS) first, per square root of route km so traces stay drivable; ties: shorter."""
    def length(route):
        return sum(l.get('len') or 0 for l in route.get('links') or []) or 1.0
    covered, chosen = set(), []
    pool = {k: v for k, v in routes.items() if max_len is None or length(v) <= max_len}
    targets = set(TARGET_KINDS)

    def score(rid):
        new = set(kinds(pool[rid])) - covered
        return (10 * len(new & targets) + len(new - targets)) / math.sqrt(length(pool[rid]) / 1000.0)
    while pool and len(chosen) < limit:
        best = max(pool, key=lambda r: (score(r), -length(pool[r])))
        gain = set(kinds(pool[best])) - covered
        if not gain & targets:
            break
        chosen.append((best, sorted(gain)))
        covered |= gain
        del pool[best]
    return chosen, sorted(covered)
