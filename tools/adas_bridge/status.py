#!/usr/bin/env python3
"""Passive stock logcat -> ADAS status observations. No publisher or driving controls.

Examples:
  python3 -m tools.adas_bridge.status --input saved.log --jsonl
  cat saved.log | python3 -m tools.adas_bridge.status --input -
  python3 -m tools.adas_bridge.status --adb-serial SERIAL --seconds 60

An explicit serial enables only `adb -s SERIAL logcat`; no buffer clearing,
shell, bind, install, route publication or vehicle/FID read is performed here.
Silence is UNKNOWN/STale according to the observer's configurable log-age policy,
not an ECU heartbeat contract. Route selection never establishes NOA availability.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import gzip
import json
import os
import re
import selectors
import subprocess
import sys
import time


TAGS = ("PilotStatusProxy", "PilotAlarmAndNoticeInfoProxy", "DriveRouteManager",
        "PathMatchProxy", "PathSelectedProxy", "NewBroadcastInfoProxy", "BydAutoNoaProxy", "HandJsonNoaUtil")
THREAD = re.compile(r"^(?:(\d{4})-)?(\d{2})-(\d{2}) (\d{2}:\d{2}:\d{2}\.\d{3,6})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+?)\s*: ?(.*)$")
EPOCH = re.compile(r"^(\d{9,12}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+?)\s*: ?(.*)$")
PILOT = re.compile(r"^(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+last:")
ALARM = re.compile(r"^(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+last:")
SELECTION = re.compile(r"MMTPathSelected curPathId:(-?\d+)\s+selectPathID:(-?\d+)")
PATH_MATCH = re.compile(r"EHPtoNav\{pathID=(-?\d+), totalNOADistance=(-?\d+), routeBeginIdx=(-?\d+), routeEndIdx=(-?\d+), statusCode=(-?\d+)\}")
# Source: original English ADS dictionary, retained evidence in
# captures/stock-map-independent-20261023/drive-audit-noa-raw-events.json.
TOR_TEXT = {501: "Available to enable HNOA", 502: "Available to enable CNOA",
            505: "CNOA activated; Focus on driving", 508: "CNOA ON", 521: "Degraded to ICC",
            539: "About to overtake to the left", 1302: "Please keep your hands on the steering wheel"}
PILOT_STATES = {0: "off_or_not_offered", 1: "active", 2: "available"}


def parse_line(line, *, year=2026, utc_offset_minutes=180):
    """Decode an actual Logger line, retaining literal values and the source PID."""
    match = EPOCH.match(line.rstrip("\r\n"))
    if match:
        stamp, pid, tid, level, tag, body = match.groups()
        at = int(float(stamp) * 1000)
        timestamp = datetime.fromtimestamp(at/1000, timezone.utc).isoformat(timespec="milliseconds")
    else:
        match = THREAD.match(line.rstrip("\r\n"))
        if not match:
            return None
        explicit_year, month, day, clock, pid, tid, level, tag, body = match.groups()
        try:
            date = datetime.strptime(f"{explicit_year or year}-{month}-{day} {clock}", "%Y-%m-%d %H:%M:%S.%f")
            date = date.replace(tzinfo=timezone(timedelta(minutes=utc_offset_minutes)))
        except ValueError:
            return None
        at, timestamp = int(date.timestamp()*1000), date.isoformat(timespec="milliseconds")
    tag = re.sub(r"^[VDIWEF]/", "", tag.strip())
    body = re.sub(r"^\[[^\]]*\]\s*", "", body)
    event = {"schema": "denza-adas-status-event-v1", "capturedAtMs": at, "timestamp": timestamp,
             "sourcePid": int(pid), "sourceTag": tag, "source": "stock_logcat",
             "observationOnly": True}
    values = None
    if tag == "PilotStatusProxy" and (m := PILOT.match(body)):
        values = dict(zip(("dnp", "icc", "acc"), map(int, m.groups())))
        event.update(event="pilot_status", service=13, eventId="0x8002", fields=values,
                     classified={key: PILOT_STATES.get(value, "unknown") for key, value in values.items()})
    elif tag == "PilotAlarmAndNoticeInfoProxy" and (m := ALARM.match(body)):
        event.update(event="alarm_notice", service=13, eventId="0x8003",
                     fields=dict(zip(("pilotAlarmReason", "pilotNotice", "noticeDistance", "alarmStage"), map(int,m.groups()))),
                     classified="raw_alarm_notice_fields; not an availability verdict")
    elif tag == "NewBroadcastInfoProxy" and (m := re.search(r"noaMode:(-?\d+)", body)):
        mode = int(m.group(1))
        event.update(event="noa_mode", service=13, eventId="0x8005", fields={"noaMode": mode},
                     classified={1: "highway", 2: "city"}.get(mode, "unknown"))
    elif tag == "DriveRouteManager" and (m := SELECTION.search(body)):
        current, confirmed = map(int, m.groups())
        event.update(event="path_selected", service=44, eventId="0x8002",
                     fields={"currentPathID": current, "pathIDResp": confirmed, "statusCode": 200},
                     classified="selection_confirmed" if current == confirmed else "different_path_confirmed",
                     evidenceScope="Stock callback is gated by statusCode 200; selection is not NOA eligibility")
    elif tag == "DriveRouteManager" and "MMTPathSelectedError" in body:
        event.update(event="path_selected_error", service=44, eventId="0x8002",
                     fields={"statusCode": None}, classified="selection_error; code not logged")
    elif tag == "PathMatchProxy" and (m := PATH_MATCH.search(body)):
        event.update(event="path_match", service=44, eventId="0x8001",
                     fields=dict(zip(("pathID", "totalNOADistance", "routeBeginIdx", "routeEndIdx", "statusCode"),map(int,m.groups()))),
                     classified="route_match_fields_received; eligibility semantics unverified")
    elif tag == "PathMatchProxy" and (m := re.search(r"parseStruct size:(\d+) code:(-?\d+)",body)):
        event.update(event="path_match_summary", service=44, eventId="0x8001",
                     fields={"routeInfoCount": int(m.group(1)), "code": int(m.group(2))}, classified="raw_summary")
    elif tag == "BydAutoNoaProxy" and (m := re.search(r"ADAS_(DNP_STATE|ACC_MODE) value:(-?\d+)",body)):
        key, value = m.group(1), int(m.group(2))
        # These are separate enums: recorded ADAS_DNP_STATE 3/6 accompanies
        # SOME/IP DNP 2/1. Never run their values through PILOT_STATES.
        event.update(event="fid_observation", fields={"name": "ADAS_"+key, "value": value,
                     "fid": "0x29C00020" if key == "DNP_STATE" else "0x29C0000C",
                     "sdkName": "ADAS_"+key if key == "DNP_STATE" else "ADAS_ACC_MODE_ARHUD"},
                     classified="raw_separate_fid_enum")
    elif tag == "HandJsonNoaUtil" and (m := re.search(r"takeoverLevel:(-?\d+),\s*takeoverId:(-?\d+)",body)):
        level, tor = map(int,m.groups())
        event.update(event="takeover_notice", fields={"takeoverLevel": level, "takeoverId": tor},
                     classified=TOR_TEXT.get(tor,"unknown_TOR"),
                     evidenceScope="Notification text only; does not update current pilot state")
    else:
        return None
    return event


class StatusTracker:
    """Per-stock-process latest observations; observer age is not ECU expiry."""
    def __init__(self, stale_after_ms=30000):
        if type(stale_after_ms) is not int or stale_after_ms <= 0:
            raise ValueError("stale age must be a positive integer")
        self.stale_after_ms, self.by_pid, self.latest_pid, self.now = stale_after_ms, {}, None, None

    def accept(self, event):
        event_time, event_pid = event["capturedAtMs"], event["sourcePid"]
        # Log delivery can be delayed or interleaved across a process restart.
        # Keep historical per-PID observations, but an older line cannot switch
        # the current view back to the previous process. Ties retain its owner.
        if self.latest_pid is None or self.now is None or event_time > self.now:
            self.latest_pid = event_pid
        self.now = max(self.now if self.now is not None else event_time, event_time)
        state = self.by_pid.setdefault(event_pid,{})
        key = event["event"]
        previous = state.get(key)
        if previous is None or event["capturedAtMs"] >= previous["capturedAtMs"]:
            state[key] = event
        return dict(event,status=self.view(self.now))

    def view(self, now_ms):
        state = self.by_pid.get(self.latest_pid,{})
        def observed(key, max_age=None):
            event = state.get(key)
            if event is None:
                return None,{"freshness":"unknown","ageMs":None}
            age = now_ms-event["capturedAtMs"]
            fresh = 0 <= age <= (max_age or self.stale_after_ms)
            return event if fresh else None,{"freshness":"observed" if fresh else "stale","ageMs":age,
                                            "observedAt":event["timestamp"],"lastFields":event["fields"]}
        pilot, pilot_age = observed("pilot_status")
        mode, mode_age = observed("noa_mode",self.stale_after_ms*2)
        selected, selected_age = observed("path_selected")
        error = state.get("path_selected_error")
        if error and (selected is None or error["capturedAtMs"] >= selected["capturedAtMs"]):
            selected = None
            selected_age = {"freshness":"unknown","ageMs":now_ms-error["capturedAtMs"],"lastErrorAt":error["timestamp"]}
        return {"sourcePid":self.latest_pid,
                "noa":dict(pilot_age,state="unknown" if pilot is None else pilot["classified"]["dnp"]),
                "icc":dict(pilot_age,state="unknown" if pilot is None else pilot["classified"]["icc"]),
                "acc":dict(pilot_age,state="unknown" if pilot is None else pilot["classified"]["acc"]),
                "noaFamily":dict(mode_age,state="unknown" if mode is None else mode["classified"]),
                "routeSelection":dict(selected_age,state="unknown" if selected is None else selected["classified"]),
                "freshnessPolicy":{"source":"observer log age, not ECU heartbeat contract","pilotMaxAgeMs":self.stale_after_ms,
                                   "modeMaxAgeMs":self.stale_after_ms*2},"noaEligibilityFromSelection":False}


def adb_command(adb, serial):
    if not isinstance(serial,str) or not re.fullmatch(r"[A-Za-z0-9_.:-]+",serial):
        raise ValueError("an explicit adb serial is required")
    filters = [prefix+tag+":V" for tag in TAGS for prefix in ("","D/","I/","V/","W/","E/")]
    return [adb,"-s",serial,"logcat","-v","epoch","-b","main,system","-T","1","*:S"]+filters


def display(record,jsonl):
    if jsonl:
        print(json.dumps(record,ensure_ascii=False,allow_nan=False),flush=True)
    else:
        state=record["status"]
        def label(key):
            item=state[key]
            return item["state"]+(" (stale)" if item["freshness"]=="stale" else "")
        print(f'{record["timestamp"]} {record["event"]} '
              f'NOA={label("noa")} family={label("noaFamily")} ICC={label("icc")} '
              f'ACC={label("acc")} selection={label("routeSelection")} '
              f'fields={json.dumps(record.get("fields",{}),ensure_ascii=False)}',flush=True)


def live_lines(command, seconds):
    """Yield lines and timer ticks; terminate only this read-only logcat subprocess."""
    process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,bufsize=0)
    selector=selectors.DefaultSelector()
    selector.register(process.stdout,selectors.EVENT_READ,"stdout")
    selector.register(process.stderr,selectors.EVENT_READ,"stderr")
    pending=bytearray(); error_tail=bytearray()
    deadline=time.monotonic()+seconds
    try:
        while time.monotonic()<deadline:
            ready=selector.select(min(1,max(0,deadline-time.monotonic())))
            if ready:
                for key,_ in ready:
                    chunk=os.read(key.fileobj.fileno(),65536)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    if key.data=="stderr":
                        error_tail.extend(chunk); error_tail=error_tail[-1000:]
                        continue
                    pending.extend(chunk)
                    while b"\n" in pending:
                        raw,_,remainder=pending.partition(b"\n"); pending=bytearray(remainder)
                        yield raw.decode("utf-8",errors="replace"),int(time.time()*1000)
                    if len(pending)>1024*1024:
                        raise ValueError("logcat line exceeds diagnostic size bound")
                if process.poll() is not None:
                    if pending:
                        yield pending.decode("utf-8",errors="replace"),int(time.time()*1000)
                        pending.clear()
                    raise OSError("logcat exited: "+error_tail.decode(errors="replace"))
            else:
                yield None,int(time.time()*1000)
    finally:
        selector.close()
        if process.poll() is None:
            process.terminate()
            try:process.wait(timeout=3)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=3)
        process.stdout.close();process.stderr.close()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    source=parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--input",help="saved threadtime/epoch log, .gz, or - for streamed stdin")
    source.add_argument("--adb-serial",help="explicit serial; only read-only logcat is executed")
    parser.add_argument("--adb",default="adb")
    parser.add_argument("--seconds",type=float,default=60)
    parser.add_argument("--year",type=int,default=datetime.now().year)
    parser.add_argument("--utc-offset-minutes",type=int,default=180,help="threadtime logs: Moscow +180 by default; epoch logs unaffected")
    parser.add_argument("--stale-after-ms",type=int,default=30000,help="observer policy, not an ECU heartbeat contract")
    parser.add_argument("--jsonl",action="store_true")
    parser.add_argument("--max-events",type=int)
    args=parser.parse_args(); tracker=StatusTracker(args.stale_after_ms); count=0; previous_view=None
    if not 0<args.seconds<=3600:parser.error("seconds must be in (0,3600]")
    try:
        if args.adb_serial:
            rows=live_lines(adb_command(args.adb,args.adb_serial),args.seconds)
            stream=None
        else:
            stream=sys.stdin if args.input=="-" else (gzip.open(args.input,"rt",errors="replace") if args.input.endswith(".gz") else open(args.input,errors="replace"))
            rows=((line,None) for line in stream)
        initial_now=int(time.time()*1000) if args.adb_serial else 0
        previous_view=tracker.view(initial_now)
        display({"event":"observation_started","timestamp":datetime.fromtimestamp(initial_now/1000,timezone.utc).isoformat() if initial_now else "unknown",
                 "source":"adb_logcat" if args.adb_serial else "saved_or_streamed_logcat",
                 "clockPolicy":"current host wall time" if args.adb_serial else "recorded event time; no assertion of current freshness",
                 "readerState":"waiting_for_stock_status_logs","status":previous_view},args.jsonl)
        try:
            for line,now in rows:
                event=None if line is None else parse_line(line,year=args.year,utc_offset_minutes=args.utc_offset_minutes)
                if event:
                    record=tracker.accept(event)
                    if now is not None:record["status"]=tracker.view(now)
                    display(record,args.jsonl); previous_view=record["status"]; count+=1
                    if args.max_events and count>=args.max_events:break
                elif now is not None and tracker.latest_pid is not None:
                    view=tracker.view(now)
                    # Timer output only on freshness transitions, not on every changing age value.
                    signature=lambda value:tuple((key,value[key]["state"],value[key]["freshness"]) for key in ("noa","noaFamily","icc","acc","routeSelection"))
                    if previous_view is None or signature(view)!=signature(previous_view):
                        display({"event":"observation_age","timestamp":datetime.fromtimestamp(now/1000,timezone.utc).isoformat(),"status":view},args.jsonl)
                        previous_view=view
        finally:
            if args.adb_serial:rows.close()
            elif stream is not sys.stdin:stream.close()
    except (OSError,ValueError,KeyboardInterrupt) as error:
        if isinstance(error,KeyboardInterrupt):return 130
        parser.exit(2,f"passive status reader failed: {error}\n")
    return 0


if __name__=="__main__":
    raise SystemExit(main())
