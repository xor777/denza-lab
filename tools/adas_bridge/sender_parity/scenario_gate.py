"""Source-backed receipt closure for the explicitly declared factory-drive fixture.

This gate checks original observation completeness. It never synthesizes replay
inputs, requires pending timer dispatch, or infers a callback from its output.
"""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path

SCHEMA = 'denza-stock-sender-scenario-contract-v1'
D, G, E = 'k.h.e0.b.d', 'k.h.e0.c.g', 'k.h.a0.c.c.a.e0'
ROUTE = 'com.byd.map.signal.GaoDeSignalManager.setRouteCarResultData(com.autosdk.bussiness.navi.route.model.RouteCarResultData)'
SERVICES = ['3096254809047040', '3096409430228992', '3096418020294656', '3239172026531840']
PROOFS = {
    'k/h/e0/b/d.java': '9e22a870cd963f557e14a962e06f9a0a570cfcf64cc625c6b31d8e24aaab206f',
    'k/h/e0/c/g.java': '1d29d9866d71976bd1dd3d9145a7e632481a6fd3cc6ac18359cc75b695f78ea1',
    'k/h/a0/c/c/a/e0.java': 'c5fcfe9dc26fd82c6e7be2e4d906faac0d57cf1e152fa3823a086ea9ecba1e73',
    'com/byd/map/signal/GaoDeSignalManager.java': '33b86e234f5dc32aede949110df87ef11d570534da90e3b8d417e9e47b94f11b',
}
_CONTRACT = dict(schema=SCHEMA, scenario='factory-drive-v1', startup_clipping=False,
    source_sha256=PROOFS, obligations=[
        dict(id='navigation-created', trigger=D+'.i()', required=['k.h.e0.b.f.r()', G+'.$init()'], source='k/h/e0/b/d.java:131-139'),
        dict(id='map-state-100', trigger=E+'.p(int)', argument=100,
             required=['same runnable removal', 'MMT accepted post at consumed uptime (post0)'], source='k/h/a0/c/c/a/e0.java:1417-1423',
             qualification='completed startup invocation before first navigationCreated d.i; later status resets cannot substitute'),
        dict(id='registered-route-result', trigger=ROUTE, required=[G+'.t(com.autosdk.bussiness.navi.route.model.RouteCarResultData)'],
             source='com/byd/map/signal/GaoDeSignalManager.java:765-770', qualification='nonnull route inside proved created/destroyed lifetime; same original route and observer identities'),
        dict(id='server-initialized', trigger=D+'.o()', required=SERVICES, source='k/h/e0/b/d.java:224-229'),
        dict(id='navigation-destroyed', trigger=D+'.j()', required=[G+'.onNavStop()'], source='k/h/e0/b/d.java:176-182'),
    ])


def factory_drive_contract() -> dict:
    """Select this fixed contract before launching the original fixture."""
    return copy.deepcopy(_CONTRACT)


def canonical(contract: dict) -> str:
    return json.dumps(contract, sort_keys=True, separators=(',', ':'), ensure_ascii=True)


def declaration(contract: dict) -> str:
    """Embed in the immutable actually loaded agent before the original run."""
    if contract != _CONTRACT:
        raise ValueError('unknown or modified scenario contract')
    return 'const P2_SCENARIO_CONTRACT=' + canonical(contract) + ';'


def validate_declaration(contract: dict, artifacts: list[dict], agent: Path) -> list[dict]:
    """Bind the manifest table to its artifact and pre-run loaded agent bytes."""
    try:
        literal = declaration(contract)
        matches = [a for a in artifacts if a.get('role') == 'scenario_contract']
        if len(matches) != 1:
            raise ValueError('exactly one pre-run scenario_contract artifact required')
        artifact = matches[0]
        path = Path(artifact['path'])
        if not path.is_absolute():
            raise ValueError('scenario contract artifact path must be absolute')
        raw = path.read_bytes()
        if hashlib.sha256(raw).hexdigest() != artifact.get('sha256') or json.loads(raw) != contract:
            raise ValueError('scenario contract artifact hash/table differs')
        if agent.read_bytes().count(literal.encode()) != 1:
            raise ValueError('selected contract declaration missing/duplicated in actual immutable agent')
        return []
    except (ValueError, TypeError, KeyError, OSError) as exc:
        return [dict(scenario_declaration_invalid=str(exc))]


def _audit(rows: list[dict], contract: dict) -> dict:
    """Audit the original receipt DAG, returning gaps and exact satisfying seqs."""
    if contract != _CONTRACT:
        return dict(scenario=None, gaps=[dict(scenario_contract_unknown_or_modified=True)], receipts=[])
    gaps, receipts = [], []
    entries = [r for r in rows if r.get('kind') == 'call_enter']
    calls = {r.get('call_id'): r for r in entries}
    exits = {r.get('call_id'): r for r in rows if r.get('kind') == 'call_exit'}

    def matches(method):
        return [r for r in entries if r.get('method') == method]

    def descendant(row, parent):
        cid, seen = row.get('call_id'), set()
        # Observations carry their active owner; call entries start at parent.
        if row.get('kind') == 'call_enter':
            cid = row.get('parent_call_id')
        while cid is not None and cid not in seen:
            if cid == parent['call_id']:
                return parent['seq'] < row['seq'] < exits.get(parent['call_id'], {}).get('seq', -1)
            seen.add(cid)
            cid = calls.get(cid, {}).get('parent_call_id')
        return False

    def identity(v):
        return (v.get('id'), v.get('class')) if isinstance(v, dict) and isinstance(v.get('id'), str) else None

    def need(condition, obligation, trigger=None, detail=None, evidence=()):
        item = dict(obligation=obligation, trigger_seq=trigger.get('seq') if trigger else None)
        if condition:
            receipts.append(dict(item, source_seqs=[r['seq'] for r in evidence]))
        else:
            gaps.append(dict(scenario_receipt_missing=dict(item, detail=detail)))

    def complete(row):
        end = exits.get(row.get('call_id'))
        return end is not None and end.get('error') is None and end['seq'] > row['seq']

    created, destroyed = matches(D+'.i()'), matches(D+'.j()')
    for name, triggers in [('navigation-created', created), ('navigation-destroyed', destroyed),
                           ('server-initialized', matches(D+'.o()'))]:
        need(bool(triggers), name, detail='declared fixture trigger absent')
    lifetimes = []
    for start in created:
        reads = [r for r in rows if r.get('kind') == 'getter' and r.get('method') == 'k.h.e0.b.f.r()' and descendant(r, start)]
        constructors = [r for r in matches(G+'.$init()') if descendant(r, start) and complete(r)]
        need(complete(start) and bool(reads) and bool(constructors), 'navigation-created', start,
             'requires actual nested NoaState.r return and completed original g constructor', reads + constructors)
        ends = [r for r in destroyed if r['seq'] > exits.get(start['call_id'], start)['seq']]
        if constructors and ends:
            lifetimes.append((start, min(ends, key=lambda r: r['seq']), identity(constructors[-1].get('receiver'))))
    for end in destroyed:
        lifetime = next((item for item in lifetimes if item[1] is end), None)
        children = [r for r in matches(G+'.onNavStop()') if descendant(r, end) and complete(r)
                    and lifetime and identity(r.get('receiver')) == lifetime[2]]
        need(complete(end) and bool(children), 'navigation-destroyed', end,
             'same observer created by navigation lifecycle must receive onNavStop', children)
    for init in matches(D+'.o()'):
        offers = [r for r in rows if r.get('kind') == 'output' and r.get('operation') == 'start_service' and descendant(r, init)]
        need(complete(init) and [r.get('topic') for r in offers] == SERVICES, 'server-initialized', init,
             'all four unconditional service offers in original source order', offers)
    routes = [r for r in matches(ROUTE) if r.get('args') and r['args'][0].get('type') != 'null']
    need(bool(routes), 'registered-route-result', detail='declared nonnull route fixture trigger absent')
    for route in routes:
        lifetime = next((item for item in lifetimes
                         if exits.get(item[0]['call_id'], item[0])['seq'] < route['seq'] < item[1]['seq']), None)
        rid = identity(route['args'][0])
        children = [r for r in matches(G+'.t(com.autosdk.bussiness.navi.route.model.RouteCarResultData)')
                    if descendant(r, route) and complete(r) and lifetime and identity(r.get('receiver')) == lifetime[2]
                    and rid is not None and r.get('args') and identity(r['args'][0]) == rid]
        need(complete(route) and bool(children), 'registered-route-result', route,
             'same original route identity must reach registered g.t within navigation lifetime', children)
    first_creation = min((r['seq'] for r in created), default=-1)
    statuses = [r for r in matches(E+'.p(int)') if r.get('args') and r['args'][0] == dict(type='i32', value=100)
                and r['seq'] < first_creation and exits.get(r['call_id'], {}).get('seq', first_creation) < first_creation]
    need(bool(statuses), 'map-state-100', detail='completed startup p(100) before first navigationCreated absent')
    for status in statuses:
        posts = [r for r in rows if r.get('kind') == 'timer_post_enter' and descendant(r, status)
                 and r.get('queue_id') == 'MMT' and r.get('runnable', {}).get('class') == E+'$d']
        valid = []
        for post in posts:
            clocks = [r for r in rows if r.get('kind') == 'clock' and r.get('method') == 'android.os.SystemClock.uptimeMillis()'
                      and descendant(r, status) and r['seq'] < post['seq'] and r.get('thread') == post.get('thread')]
            cancels = [r for r in rows if r.get('kind') == 'timer_cancel' and descendant(r, status) and r['seq'] < post['seq']
                       and r.get('method', '').startswith('android.os.Handler.removeCallbacks(')
                       and identity(r.get('handler')) == identity(post.get('handler'))
                       and r.get('args') and identity(r['args'][0]) == identity(post.get('runnable'))]
            accepted = [r for r in rows if r.get('kind') == 'timer_post_result' and r.get('post_id') == post.get('post_id')
                        and r.get('result') is True and descendant(r, status)]
            if (clocks and cancels and accepted and clocks[-1].get('result', {}).get('type') == 'i64'
                    and clocks[-1]['result']['decimal'] == post.get('due_uptime_ms')):
                valid += [cancels[-1], clocks[-1], post, accepted[0]]
        need(complete(status) and bool(valid), 'map-state-100', status,
             'same L3 runnable removal and accepted MMT post0 at actually consumed uptime; dispatch may remain pending', valid)
    return dict(scenario='factory-drive-v1', gaps=gaps, receipts=receipts)


def audit(rows: list[dict], contract: dict) -> dict:
    """Malformed original records remain incomplete diagnostics, never exceptions."""
    try:
        if any(not isinstance(row, dict) or type(row.get('seq')) is not int for row in rows):
            raise ValueError('original integer sequence required on every scenario receipt')
        return _audit(rows, contract)
    except (ValueError, TypeError, KeyError) as exc:
        return dict(scenario='factory-drive-v1', gaps=[dict(scenario_ledger_invalid=str(exc))], receipts=[])


def declared_audit(rows: list[dict], metadata: dict, artifacts: list[dict], agent: Path | None) -> dict | None:
    """Old undeclared captures retain their old scope; a declaration cannot vanish."""
    agent_declared = agent is not None and b'const P2_SCENARIO_CONTRACT=' in agent.read_bytes()
    if 'scenario_contract' not in metadata and not agent_declared and not any(a.get('role') == 'scenario_contract' for a in artifacts):
        return None
    contract = metadata.get('scenario_contract')
    report = audit(rows, contract)
    if agent is None:
        report['gaps'].append(dict(scenario_declaration_invalid='verified original agent unavailable'))
    else:
        report['gaps'] += validate_declaration(contract, artifacts, agent)
    return report
