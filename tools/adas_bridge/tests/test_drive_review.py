"""drive_review on small synthetic drives written in the writers' exact row formats.

Live rows follow DryRunRecorder.note/LiveEgressGuard.noteDecision/LiveMapBridge.note, packet rows
DriveEvidenceStreams.packet/passive, journal rows AdasStateJournal.row, guidance rows DriveEvidence.sample;
payloads come from the stock.py oracle (StockMessages byte for byte) and AdasWire's encoders.
"""
import base64
import contextlib
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest

from tools.adas_bridge import drive_review as review
from tools.adas_bridge import stock

ROOT = Path(__file__).resolve().parents[3]
OFFSET = 1792759496318  # wall - elapsed of the 2026-10-24 drive
EPOCH = 4294967297
ARM, CONSUMER, PROCESS, READER = ("eda9dd18-a7bb-4ab8-a14f-04dd03e4dbe9", "f207803a-aed1-4fe2-8b39-3cd3b6f21b9d",
                                  "b2a05f8e-0000-4000-8000-000000000001", "aec58fd9-ad29-4c13-b596-807a63be7e3c")
NAVIGATOR = "2731f565-0029-47a0-a491-62c9a3531489"
AB = {"id": "drive-ab-historyspeed", "frame": "WGS84", "link.roadclass": "yandex", "link.formway": "yandex",
      "link.ownership": "yandex", "link.direction": "yandex", "link.laneNum": "yandex", "link.adminCode": "yandex",
      "region": "default", "position.ownership": "yandex", "traffic.roadClass": "yandex",
      "historySpeed": {"mode": "alternate", "values": ["yandex", 25]}, "speedLimit": "yandex", "startProtocol": "bridge",
      "naviSt": False, "instrumentNaviStatus": False, "overrides": ["historySpeed"]}


def _varint(value):
    value &= (1 << 64) - 1
    out = bytearray()
    while value > 0x7F:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)
    return bytes(out)


def _field(number, value):
    return _varint(number << 3) + _varint(value)


def _wrap(inner):
    return b"\x0a" + _varint(len(inner)) + inner


def pilot(dnp, icc, acc=0):  # AdasWire.pilot plus ACC
    return _wrap(_field(3, acc) + _field(4, icc) + _field(5, dnp) + _field(6, 0))


def mode(value):
    return _wrap(_field(3, value))


def selected(path, code):
    return _wrap(_field(3, path) + _field(4, code))


def match(path):
    return _wrap(_field(3, 0) + b"\x22" + _varint(len(_field(1, path))) + _field(1, path))


class Car:
    """One pulled evidence directory, written as Navigator writes it."""

    def __init__(self, root):
        self.root = Path(root)
        self.files = self.root / "files"
        self.evidence = self.files / "denza-drive-evidence"
        self.evidence.mkdir(parents=True)
        self.live, self.packets, self.journal, self.progress = [], [], [], []
        self.full = {}

    # --- denza-adas-live.jsonl
    def note(self, kind, t, **fields):
        self.live.append(dict({"kind": "input", "input": kind, "monotonicMs": t}, **fields))

    def session(self, kind, t, detail, arm=ARM, process=PROCESS):
        self.live.append({"kind": "input", "input": "liveSession." + kind, "monotonicMs": t, "detail": detail,
                          "consumerSession": CONSUMER, "processSession": process, "armId": arm})

    def decision(self, kind, t, reason, path=-1, topic=0, result=0):
        self.note("liveEgress." + kind, t, topic=topic, result=result, reason=reason, pathId=path, epoch=EPOCH)

    def send(self, t, topic, payload, path=-1):
        self.decision("attempt", t, "actual transport", path, topic)
        self.note("liveEgress.packet", t, topic=topic, result=0, payloadHex=payload.hex(), source="real_transport_result", epoch=EPOCH)

    def header(self, t, variant=None):
        row = {"kind": "header", "monotonicMs": t, "schema": "denza-adas-dry-run-v1", "frame": "WGS84", "locale": "EN",
               "roadSpeedLimit": True, "acceptSimulatedLocations": False, "emulatedAckDelayMs": -1, "adasType": 1,
               "publisher": "LIVE", "fieldDefaults": "FieldDefaults", "vehicleSpeedSource": "GNSS_STANDS_IN",
               "defaultFrame": "WGS84", "regionDefault": "0/1/0/0"}
        if variant:
            row["variant"] = variant
        self.live.append(row)

    def arm(self, t, variant=None, process=PROCESS, resumed=False, arm=ARM):
        detail = arm + (" resumed by process " + process + " after 1, route 1792837559 may be at ADAS" if resumed else " process " + process)
        self.session("armed", t, detail, arm, process)
        if variant:
            self.session("variant", t, json.dumps(variant), arm, process)
        self.header(t + 1, variant)
        self.decision("session", t + 1, "selection session ready on transport epoch " + str(EPOCH))

    def publish(self, t, route_id, path, links, route_type=1, ack=True, ack_delay=40):
        self.note("routeResult", t, routeId=route_id, pathId=path, amapRouteType=route_type, links=len(links), segments=1)
        self.decision("route", t, "new model route", path, path)
        data = {"code": 200, "data": [{"pathID": path, "linkCnt": len(links), "links": links, "pnts": [],
                                       "guideGroups": [{"segments": [{"linkBegIdx": 0, "crntSegmLinkCnt": len(links)}]}]}]}
        for chunk in stock.chunks(json.dumps(data), 1, OFFSET + t):
            self.send(t + 1, review.ROUTE, stock.route(chunk, 1), path)
        self.send(t + 2, review.PATH_SELECTED, stock.path_selected(path), path)
        if ack:
            self.note("liveFeedback", t + 2 + ack_delay, topic=review.SELECTED, payloadHex=selected(path, 200).hex(),
                      receivedAtElapsedMs=t + 2 + ack_delay, source="actual_ADAS_callback", queueDelayMs=0)
            self.decision("ack", t + 2 + ack_delay, "ACCEPTED", path, review.SELECTED, 1)
            self.passive(t + 2 + ack_delay, review.SELECTED, selected(path, 200))

    def tick(self, t, path, ownership=-1, road_class=-1, history=None, status=101):
        self.send(t, review.VEHICLE_LOCATION, stock.vehicle_location(37.5, 55.7, 37.5, 55.7, 60, 60, 90, path, 0, 0, 500, 20, ownership), path)
        self.send(t + 1, review.TRAFFIC_INFO, stock.traffic_info(0, -1, 0, 0, 0, 0, 0, 60, 0, 0, 0, 0, 0, 0, 0, False, 900, 60, road_class), path)
        self.send(t + 2, review.NAVIGATION_STATUS, stock.navigation_status(status), path)
        if history is not None:
            self.send(t + 3, review.SPEED_INTERSECTION, stock.speed_intersection(0, history, 0, 0, 0, -1, ""), path)

    def end(self, t, statuses=(100, 100, 100), reason="Сеанс отключён: arm file removed", complete=True):
        self.session("stopping", t, reason)
        self.note("navStop", t)
        self.decision("end", t, "navigation ended; stock end sequence " + json.dumps(list(statuses)).replace(",", ", "))
        self.decision("ending", t, reason)
        for i, value in enumerate(statuses):
            self.send(t + 1000 * (i + 1), review.NAVIGATION_STATUS, stock.navigation_status(value))
        last = t + 1000 * len(statuses)
        if complete:
            self.decision("idle", last, "stock end sequence sent; ADAS holds no route of ours")
        self.decision("closed", last + 1, "own guarded session only: " + reason)
        self.session("stopped", last + 2, reason.split(":")[0] + ": " + reason.split(": ", 1)[-1])
        return last + 2

    # --- denza-drive-evidence
    def passive(self, t, topic, payload, receiver="overlay"):
        key = (receiver, topic)
        if self.full.get(key) == payload and topic not in (review.SELECTED, review.PATH_MATCH):
            self.packets.append({"kind": "adas_passive_repeat", "receiver": receiver, "topic": format(topic, "x"),
                                 "timestamp": 0, "receivedAtElapsedMs": t})
            return
        self.full[key] = payload
        self.packets.append({"kind": "adas_passive_packet", "receiver": receiver, "receiverSession": None,
                             "sourceSessionId": NAVIGATOR, "capturedAtMs": OFFSET + t, "topic": format(topic, "x"),
                             "timestamp": 0, "receivedAtElapsedMs": t, "receivedUptimeMs": t, "payloadNull": False,
                             "bytes": len(payload), "base64": base64.b64encode(payload).decode(), "truncated": False})

    def state(self, t, what, receiver="overlay", **values):
        self.journal.append(dict({"kind": "adas_state", "what": what, "receiver": receiver, "elapsedMs": t,
                                  "capturedAtMs": OFFSET + t}, **values))

    def guidance(self, t, route_id="r1", location="FINE", source=NAVIGATOR, recorder=None):
        self.progress.append({"schema": "denza-yandex-guidance-v1", "capturedAtMs": OFFSET + t, "capturedUptimeMs": t,
                              "mode": "guidance", "locationClass": location, "freshLocation": location == "FINE",
                              "routeId": route_id, "kind": "guidance", "sourceSessionId": source,
                              "recorder": recorder or {"rawQueued": 0, "criticalQueued": 0, "rawDropped": 0, "criticalDropped": 0,
                                                       "rawPostFailures": 0, "criticalPostFailures": 0, "historyWriteErrors": 0,
                                                       "packetWriteErrors": 0, "repeatRows": 0, "journalWriteErrors": 0,
                                                       "journalBytes": 0, "journalDropped": 0},
                              "generation": 1})

    def write(self, live_split=None, extra=None):
        def dump(path, rows):
            path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
        if live_split:
            dump(self.files / "denza-adas-live.jsonl.1", self.live[:live_split])
            dump(self.files / "denza-adas-live.jsonl", self.live[live_split:])
        else:
            dump(self.files / "denza-adas-live.jsonl", self.live)
        for name, rows in (("adas-packets.jsonl", self.packets), ("adas-journal.jsonl", self.journal), ("progress.jsonl", self.progress)):
            if rows:
                dump(self.evidence / name, rows)
        for name, value in (extra or {}).items():
            (self.files / name).write_text(json.dumps(value), encoding="utf-8")
        return self.root


def link(roadclass=-1, ownership=-1, lanes=0, direction=0, formway=-1):
    return {"roadclass": roadclass, "formway": formway, "ownership": ownership, "laneNum": lanes, "direction": direction,
            "len": 100.0, "pntBegIdx": 0, "pntCnt": 2, "roadname": ""}


def run(root):
    with contextlib.redirect_stdout(io.StringIO()):
        result, clock = review.review(root)
    review.markdown(result, clock)  # The summary must always render.
    return result


class GoodDrive(unittest.TestCase):
    """One route, one path, 1 Hz, a DNP episode on our confirmed path, stock end statuses, a clean release."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        car = Car(cls.tmp.name)
        car.arm(1_000, AB)
        car.note("navStart", 1_500)
        car.note("variantApplied", 1_999, pathId=1792837559, routeId="r1", routeOrdinal=1, variantId="drive-ab-historyspeed", historySpeed=25)
        car.publish(2_000, "r1", 1792837559, [link(9, 0, 2, 1, 15), link(9, 0, 2, 1, 15)])
        for i in range(60):
            t = 3_000 + 1000 * i
            car.tick(t, 1792837559, ownership=0, road_class=9, history=25)
            car.guidance(t + 500)
        car.state(9_990, "noaMode", noaMode=2, was=None)
        car.passive(9_990, review.MODE, mode(2))
        for i in range(30):  # 3 s of DNP active at 10 Hz, then off
            car.passive(10_000 + 100 * i, review.PILOT, pilot(1, 2))
        car.passive(13_000, review.PILOT, pilot(0, 2))
        stop = car.end(63_100)
        for i in range(5):
            car.passive(stop + 1000 * (i + 1), review.PILOT, pilot(0, 0))
        car.state(1_000, "variant", receiver="live", armId=ARM, variant=AB)
        root = car.write(extra={"denza-adas-live-arm.json": {"schema": "denza-adas-live-arm-v1", "armId": ARM, "issuedAtElapsedMs": 900,
                                                             "expiresAtElapsedMs": 7_200_900, "variant": {"id": "drive-ab-historyspeed"}},
                                "denza-adas-live-state.json": {"schema": "denza-adas-live-state-v1", "armId": ARM, "elapsedMs": stop,
                                                               "state": "STOPPED", "callbacks": {"accepted": 900, "droppedOldest": 0,
                                                                                                  "postFailures": 0, "taskFailures": 0}}})
        cls.result = run(root)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_session(self):
        (s,) = self.result["sessions"]
        self.assertEqual(s["armId"], ARM)
        self.assertEqual(s["variant"]["id"], "drive-ab-historyspeed")
        self.assertEqual(s["variant"]["overrides"], ["historySpeed"])
        self.assertEqual(s["start"]["how"], "liveSession.armed")
        self.assertEqual(s["armIssued"]["elapsedMs"], 900)
        self.assertTrue(s["firstCause"]["detail"].startswith("Сеанс отключён"))
        self.assertEqual(s["endStatuses"]["values"], [100, 100, 100])
        self.assertTrue(s["endStatuses"]["sent"] and s["endStatuses"]["stockShape"])
        self.assertIs(s["release"]["released"], True)
        self.assertEqual(s["release"]["heldPathId"], 1792837559)

    def test_one_path_with_ack(self):
        (route,) = self.result["paths"]["routes"]
        self.assertEqual((route["routeId"], route["paths"], route["unexpectedNewPaths"], route["withAck200"]), ("r1", 1, 0, 1))
        item = route["items"][0]
        self.assertEqual(item["ack200"]["afterSelectionMs"], 40)
        self.assertTrue(item["ack200"]["ref"].endswith(".jsonl:" + item["ack200"]["ref"].rsplit(":", 1)[1]))

    def test_cadence(self):
        topics = {t["name"]: t for t in self.result["cadence"]["topics"]}
        for name in ("VehicleLocation", "TrafficInfo", "NavigationStatus", "SpeedIntersection"):
            self.assertEqual(topics[name]["medianMs"], 1000, name)
            self.assertTrue(topics[name]["cadenceOk"], name)
            self.assertEqual(topics[name]["over2500ms"], [], name)

    def test_semantics_from_yandex(self):
        pos = self.result["semantics"]["positions"]
        self.assertEqual((pos["sent"], pos["matched"], pos["ownershipMinus1"], pos["ownershipZeroOnWire"], pos["ownershipFromYandex"]),
                         (60, 60, 0, 60, 60))
        links = self.result["semantics"]["linksPerPath"][0]
        self.assertEqual(links["fields"]["roadclass"]["counts"], {"9": 2})
        self.assertEqual(links["fields"]["laneNum"]["zero"], 0)

    def test_dnp_episode_aligned(self):
        dnp = [e for e in self.result["adasState"]["episodes"] if e["function"] == "DNP" and e["value"] == 1]
        self.assertEqual(len(dnp), 1)
        ep = dnp[0]
        self.assertEqual((ep["start"]["elapsedMs"], ep["lengthMs"]), (10_000, 3_000))
        self.assertEqual(ep["noa"]["family"], "CNOA (city)")
        a = ep["aligned"]
        self.assertEqual((a["livePath"], a["livePathConfirmed"], a["variant"]), (1792837559, True, "drive-ab-historyspeed"))
        self.assertEqual(a["historySpeedSent"]["value"], 25)
        self.assertEqual((a["historySpeedVariant"]["value"], a["historySpeedVariant"]["routeOrdinal"]), (25, 1))
        self.assertEqual(a["roadUnderCar"]["link"]["roadclass"], 9)
        self.assertLessEqual(a["sinceLastActualSendMs"], 1000)
        self.assertEqual(self.result["adasState"]["episodes"][0]["start"]["wall"][:16], "2026-10-23T15:45")

    def test_health_and_side_channels(self):
        h = self.result["health"]
        self.assertEqual(h["navigatorRestarts"], 0)
        self.assertEqual(h["callbacks"]["stats"]["droppedOldest"], 0)
        self.assertFalse(self.result["sideChannels"]["naviSt"]["journalled"])
        self.assertNotIn("denza-adas-live.jsonl не найден", self.result["missing"])


class Build12ShapedDrive(unittest.TestCase):
    """The 2026-10-24 failure modes: 15 paths for one route, a 44 s gap, 5 Hz, an episode 77 s after the close."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        car = Car(cls.tmp.name)
        car.arm(1_000)
        car.note("navStart", 1_500)
        t = 2_000
        for n in range(15):
            path = 1792837559 + n
            car.publish(t, "01a16d6b", path, [link()] * (31 + n), route_type=5 if n else 1)
            for i in range(50):  # 5 Hz positions on the matched path, unmatched markers
                car.tick(t + 1_000 + 200 * i, path)
            if n == 0:
                car.decision("invalidated", t + 11_100, "prerequisites lost", -1, 0, -1200)
                for i in range(44):
                    car.guidance(t + 11_200 + 1000 * i)
                car.decision("rejected", t + 12_000, "no current route", -1, review.VEHICLE_LOCATION, -1200)
                t += 44_000 + 11_000
            else:
                t += 11_000
        last = t
        car.session("stopped", last, "Связь сеанса потеряна")
        car.decision("closed", last - 1, "own guarded session only")
        for i in range(10):
            car.passive(last + 77_000 + 100 * i, review.PILOT, pilot(1, 2) if i < 5 else pilot(0, 1))
        car.passive(last + 76_000, review.MODE, mode(2))
        cls.last, cls.paths = last, [1792837559 + n for n in range(15)]
        cls.result = run(car.write())

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_fifteen_paths_for_one_route(self):
        (route,) = self.result["paths"]["routes"]
        self.assertEqual((route["paths"], route["unexpectedNewPaths"]), (15, 14))
        reasons = [p["reason"] for p in route["items"]]
        self.assertEqual(reasons[0], "first_for_route")
        self.assertEqual(reasons[1], "after_invalidation")
        self.assertEqual(set(reasons[2:]), {"unexplained"})
        self.assertEqual(self.result["paths"]["publications"], 15)

    def test_gap_and_five_hz(self):
        gaps = self.result["pausesAndGaps"]["positionGapsOver2500ms"]
        self.assertEqual(len(gaps), 1)
        gap = gaps[0]
        self.assertEqual(gap["ms"], 45_200)
        self.assertEqual(gap["guidance"]["fine"], 44)
        self.assertTrue(gap["guidance"]["allFine"])
        self.assertEqual(gap["pathDropsInside"][0]["reason"], "prerequisites lost")
        self.assertEqual(gap["positionRejections"], {"no current route": 1})
        position = next(t for t in self.result["cadence"]["topics"] if t["name"] == "VehicleLocation")
        self.assertEqual(position["medianMs"], 200)
        self.assertFalse(position["cadenceOk"])
        self.assertEqual(len(position["over2500ms"]), 1)

    def test_matched_but_unmatched_markers(self):
        pos, tr = self.result["semantics"]["positions"], self.result["semantics"]["trafficInfo"]
        self.assertEqual((pos["matched"], pos["ownershipMinus1"], pos["ownershipVariantFilled"]), (750, 750, 0))
        self.assertEqual((tr["matched"], tr["roadClassMinus1"], tr["roadClassVariantFilled"]), (750, 750, 0))
        for item in self.result["semantics"]["linksPerPath"]:
            self.assertEqual(item["fields"]["roadclass"]["minus1"], item["links"])
            self.assertEqual(item["fields"]["laneNum"]["zero"], item["links"])

    def test_episode_after_the_publisher_closed(self):
        (ep,) = [e for e in self.result["adasState"]["episodes"] if e["function"] == "DNP"]
        self.assertEqual(ep["lengthMs"], 500)
        a = ep["aligned"]
        self.assertFalse(a["sessionLive"])
        self.assertIsNone(a["livePath"])
        self.assertEqual(a["sinceSessionStoppedMs"], 77_000)
        self.assertEqual(a["lastPathOfOursAtAdas"], self.paths[-1])
        self.assertIs(a["stoppedSession"]["endStatusesSent"], False)
        self.assertEqual(a["roadUnderCar"]["trafficRoadClass"], -1)
        (s,) = self.result["sessions"]
        self.assertIs(s["endStatuses"]["sent"], False)
        self.assertEqual(s["firstCause"]["detail"], "Связь сеанса потеряна")


class NewPathReasons(unittest.TestCase):
    def test_withdrawal_resend_retry_and_reroute(self):
        with tempfile.TemporaryDirectory() as tmp:
            car = Car(tmp)
            car.arm(1_000)
            car.note("navStart", 1_100)
            car.publish(2_000, "A", 1001, [link()])
            car.session("offlineWithdrawal", 3_000, "Офлайн-карта удалена")
            car.publish(4_000, "A", 1002, [link()])
            car.session("dnpSwitch", 5_000, "on: the stock resends its route")
            car.note("routeResend", 5_300, pathId=1002, reason="dnpSwitch on")
            car.publish(5_300, "A", 1003, [link()])
            car.note("routeRetry", 6_000, pathId=1003, attempt=1)
            car.publish(6_000, "A", 1004, [link()], ack=False)
            car.note("reroute", 7_000, routeType=2)
            car.publish(8_000, "B", 1005, [link()], route_type=2)
            car.note("navStop", 9_000)
            car.note("navStart", 9_500)
            car.publish(10_000, "B", 1006, [link()])
            result = run(car.write())
        routes = {r["routeId"]: r for r in result["paths"]["routes"]}
        self.assertEqual([p["reason"] for p in routes["A"]["items"]],
                         ["first_for_route", "offline_withdrawal", "dnp_switch_resend", "retry_unsent"])
        self.assertEqual(routes["A"]["unexpectedNewPaths"], 0)
        self.assertIsNone(routes["A"]["items"][3]["ack200"])
        b = routes["B"]["items"]
        self.assertEqual((b[0]["reason"], b[0]["routeKind"], b[0]["afterYandexReroute"]), ("first_for_route", "yaw reroute", True))
        self.assertEqual((b[1]["reason"], b[1]["expected"]), ("guidance_restart", False))


class VariantFilled(unittest.TestCase):
    def test_split_yandex_none_from_variant_filled(self):
        with tempfile.TemporaryDirectory() as tmp:
            car = Car(tmp)
            car.arm(1_000)
            car.note("navStart", 1_100)
            car.publish(2_000, "A", 1001, [link()])
            for i in range(3):
                car.tick(3_000 + 1000 * i, 1001)  # before the variant note: Yandex had nothing, -1 sent
            car.note("variantApplied", 5_900, pathId=1001, field="position.ownership", value=0,
                     where="sdVehicleLocation.currentRoadOwnership", variantId="H2c")
            car.note("variantApplied", 5_900, pathId=1001, field="traffic.roadClass", value="link:9",
                     where="TrafficInfo.roadClass", variantId="H2c")
            for i in range(4):
                car.tick(6_000 + 1000 * i, 1001, ownership=0, road_class=9)
            car.tick(10_000, -1)  # unmatched position: not counted as matched
            result = run(car.write())
        pos, tr = result["semantics"]["positions"], result["semantics"]["trafficInfo"]
        self.assertEqual((pos["sent"], pos["matched"], pos["ownershipMinus1"], pos["ownershipVariantFilled"]), (8, 7, 3, 4))
        self.assertEqual((tr["matched"], tr["roadClassMinus1"], tr["roadClassVariantFilled"]), (7, 3, 4))
        self.assertEqual(len(result["semantics"]["variantApplied"]["1001"]), 2)


class PausesAndSideChannels(unittest.TestCase):
    def test_pause_named_and_fine(self):
        with tempfile.TemporaryDirectory() as tmp:
            car = Car(tmp)
            car.arm(1_000)
            car.note("navStart", 1_100)
            car.publish(2_000, "A", 1001, [link()])
            car.tick(3_000, 1001)
            car.session("prerequisite", 3_500, "vehicle gates: stale")
            car.decision("suspended", 3_500, "inputs stale: observed 2600 ms ago, valid 2500 ms", 1001, 0, -1200)
            for i in range(4):
                car.guidance(3_600 + 1000 * i)
            car.decision("resumed", 7_500, "prerequisites back after: inputs stale", 1001)
            car.session("prerequisite", 7_500, "all prerequisites hold")
            car.tick(7_600, 1001)
            result = run(car.write())
        (pause,) = result["pausesAndGaps"]["pauses"]
        self.assertEqual((pause["prerequisite"][:12], pause["lengthMs"], pause["endedBy"]), ("inputs stale", 4_000, "liveEgress.resumed"))
        self.assertEqual((pause["guidance"]["samples"], pause["guidance"]["allFine"]), (4, True))
        self.assertEqual([p["detail"] for p in result["pausesAndGaps"]["prerequisiteChanges"]], ["vehicle gates: stale", "all prerequisites hold"])
        (gap,) = result["pausesAndGaps"]["positionGapsOver2500ms"]
        self.assertEqual(gap["pausesInside"], ["inputs stale: observed 2600 ms ago, valid 2500 ms"])

    def _navi(self, restored):
        with tempfile.TemporaryDirectory() as tmp:
            car = Car(tmp)
            car.arm(1_000)
            events = [{"n": 1, "op": "read", "purpose": "original", "elapsedMs": 900, "device": 1023, "fid": 1285554251, "ok": True, "value": 0},
                      {"n": 2, "op": "read", "purpose": "before write", "elapsedMs": 2100, "device": 1023, "fid": 1285554251, "ok": True, "value": 0},
                      {"n": 3, "op": "write", "purpose": "guidance start", "elapsedMs": 2101, "device": 1023, "fid": 1285554251, "value": 2, "ok": True}]
            if restored:
                events += [{"n": 4, "op": "write", "purpose": "restore: not guiding", "elapsedMs": 9000, "device": 1023, "fid": 1285554251, "value": 0, "ok": True},
                           {"n": 5, "op": "read", "purpose": "restore readback", "elapsedMs": 9001, "device": 1023, "fid": 1285554251, "ok": True, "value": 0}]
            for e in events[:3]:
                car.session("naviSt", e["elapsedMs"] + 200, json.dumps(e))
            for e in events:
                car.state(e["elapsedMs"] + 210, "naviSt", receiver="reader", armId=ARM, **e)
            ledger = {"schema": "denza-adas-navi-st-v1", "bootId": "b", "consumerSession": CONSUMER, "readerSession": READER, "armId": ARM,
                      "state": "IDLE" if restored else "WRITTEN", "original": 0, "dirty": not restored, "reason": "x",
                      "n": len(events), "events": events, "updatedAtElapsedMs": 9001}
            return run(car.write(extra={"denza-adas-navi-st.json": ledger}))["sideChannels"]["naviSt"]

    def test_navi_st_restored_and_not(self):
        good = self._navi(True)
        self.assertEqual((good["writes"], good["guidanceWrites"], good["restoreWrites"]), (2, 1, 1))
        self.assertEqual(good["verdict"], "restored_readback")
        self.assertEqual(len(good["events"][2]["refs"]), 3)  # live note, journal row and ledger: one event
        bad = self._navi(False)
        self.assertEqual(bad["verdict"], "not_restored")


class HealthAndInputs(unittest.TestCase):
    def test_restart_resume_rotation_crash(self):
        with tempfile.TemporaryDirectory() as tmp:
            car = Car(tmp)
            car.arm(1_000)
            car.note("navStart", 1_100)
            car.publish(2_000, "A", 1001, [link()])
            for i in range(30):
                car.tick(3_000 + 1000 * i, 1001)
                car.guidance(3_000 + 1000 * i, source=NAVIGATOR if i < 15 else "97a97d30-e6ca-4eb4-84b0-6d28a8ce59cc")
            car.arm(40_000, process="2731f565-1111-4000-8000-000000000002", resumed=True)
            car.passive(41_000, review.PILOT, pilot(0, 0))
            split = next(i for i, r in enumerate(car.live) if r.get("monotonicMs", 0) >= 20_000)
            root = car.write(live_split=split)
            # The ring rotated past its oldest generation: the rows before `split` (the drive's start) are gone.
            (car.files / "denza-adas-live.jsonl.1").write_text("".join(json.dumps(r) + "\n" for r in car.live[split:split + 50]))
            (car.files / "denza-adas-live.jsonl").write_text("".join(json.dumps(r) + "\n" for r in car.live[split + 50:]))
            (car.root / "logcat-crash.txt").write_text(
                "10-23 09:00:00.000 F/libc ( 1): Fatal signal 6 (SIGABRT) in tid 1 (ndex.yandexnavi)\n"
                "10-23 15:45:20.000 F/libc ( 2): Fatal signal 11 (SIGSEGV) in tid 2 (ndex.yandexnavi)\n")
            (car.files / "denza-adas-live-arm.json").write_text(json.dumps({"armId": ARM, "issuedAtElapsedMs": 500}))
            result = run(root)
        h = result["health"]
        self.assertEqual(h["navigatorRestarts"], 1)
        self.assertEqual(len(h["resumedArms"]), 1)
        live = next(s for s in h["rings"]["streams"] if s["stream"] == "live")
        self.assertTrue(live["lostDriveStart"] and live["rotated"])
        self.assertEqual(h["rings"]["driveStart"]["how"], "arm issued")
        markers = [m for m in h["crashMarkers"] if m.get("kind") == "crash"]
        self.assertEqual([m["duringDrive"] for m in markers], [False, True])

    def test_empty_and_unreadable(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "out"
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(review.main([tmp, "--out", str(out)]), 0)
            result = json.loads((out / "review.json").read_text())
            self.assertIn("denza-adas-live.jsonl не найден", result["missing"])
            self.assertEqual(result["sessions"], [])
            self.assertIn("## 8.", (out / "review.md").read_text())
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(review.main([str(Path(tmp) / "absent"), "--out", str(out)]), 2)

    def test_cut_line_duplicate_copy_and_tar(self):
        with tempfile.TemporaryDirectory() as tmp:
            car = Car(tmp)
            car.arm(1_000)
            car.note("navStart", 1_100)
            car.publish(2_000, "A", 1001, [link()])
            for i in range(5):
                car.tick(3_000 + 1000 * i, 1001)
            root = car.write()
            live = car.files / "denza-adas-live.jsonl"
            with live.open("a") as f:
                f.write('{"kind":"input","input":"liveEgr')  # a line cut by the pull
            copy = car.root / "second-pull"
            copy.mkdir()
            (copy / "denza-adas-live.jsonl").write_bytes(live.read_bytes())
            with tarfile.open(car.root / "evidence.tar", "w") as tar:
                tar.add(str(car.evidence / "adas-packets.jsonl"), arcname="files/denza-drive-evidence/adas-packets.jsonl")
            result = run(root)
        self.assertEqual(len(result["parseErrors"]), 1)
        self.assertEqual(sum(1 for f in result["inventory"] if f.get("duplicateOf")), 2)
        position = next(t for t in result["cadence"]["topics"] if t["name"] == "VehicleLocation")
        self.assertEqual(position["sentTotal"], 5)

    def test_repeats_and_journal_only_episode(self):
        with tempfile.TemporaryDirectory() as tmp:
            car = Car(tmp)
            for i in range(20):
                car.passive(1_000 + 100 * i, review.PILOT, pilot(2, 1) if i < 10 else pilot(0, 0))
            car.state(60_000, "pilot", dnp=1, icc=2, acc=0, takeover=False)
            car.state(61_000, "pilot", dnp=0, icc=2, acc=0, takeover=False)
            result = run(car.write())
        eps = {(e["function"], e["value"]): e for e in result["adasState"]["episodes"]}
        self.assertEqual(eps[("DNP", 2)]["observations"], 10)  # nine of them compact repeats
        self.assertEqual(eps[("DNP", 2)]["lengthMs"], 1_000)
        self.assertEqual((eps[("DNP", 1)]["lengthMs"], eps[("DNP", 1)]["start"]["source"]), (1_000, "journal"))
        self.assertFalse(eps[("DNP", 1)]["aligned"]["known"])


@unittest.skipUnless(os.environ.get("DENZA_INPUT_CORPUS") == "1", "retained corpus: set DENZA_INPUT_CORPUS=1 explicitly")
class Build12Capture(unittest.TestCase):
    """The retained 2026-10-24 build-12 rings (git-ignored) reproduce the audit's numbers."""

    def test_known_facts(self):
        result = run(ROOT / "captures/adas-live-drive-20261024")
        self.assertEqual(result["paths"]["publications"], 16)
        self.assertEqual(max(r["paths"] for r in result["paths"]["routes"]), 15)
        self.assertEqual(round(max(r["spanMs"] for r in result["paths"]["routes"]) / 1000), 601)
        position = next(t for t in result["cadence"]["topics"] if t["name"] == "VehicleLocation")
        self.assertEqual((position["medianMs"], position["maxMs"]), (200, 43_972))
        (dnp,) = [e for e in result["adasState"]["episodes"] if e["function"] == "DNP" and e["value"] == 1]
        self.assertEqual(dnp["aligned"]["sinceSessionStoppedMs"], 77_545)
        self.assertEqual(dnp["lengthMs"], 474)


@unittest.skipUnless(os.environ.get("DENZA_INPUT_CORPUS") == "1", "retained corpus: set DENZA_INPUT_CORPUS=1 explicitly")
class StockNativeCapture(unittest.TestCase):
    def test_live15_uses_current_arm_and_actual_published_route(self):
        result = run(ROOT / "captures/stock-sender-parity-20261025/product/live15")
        self.assertEqual(result["attribution"]["armId"], "edb4501b-1cb9-4e5f-b1c0-f9faba63843b")
        self.assertEqual(result["attribution"]["excludedLiveRows"], 2698)
        self.assertEqual(result["semantics"]["positions"]["sent"], 126)
        self.assertEqual(result["paths"]["publications"], 1)
        item = result["paths"]["routes"][0]["items"][0]
        self.assertEqual((item["pathId"], item["links"], item["wireRouteType"]), (1792935676744, 11, 50))
        self.assertEqual(item["ack200"]["afterSelectionMs"], 113)
        self.assertEqual(result["sessions"][0]["pathsConfirmed"], [1792935676744])


class StockNativeJournal(unittest.TestCase):
    def test_full_native_arm_uses_transport_windows_without_legacy_variant_or_navstop_claims(self):
        with tempfile.TemporaryDirectory() as tmp:
            car = Car(tmp)
            car.live.append({"kind": "input", "input": "liveSession.armed", "monotonicMs": 1900,
                             "armId": "native-full", "sender": "stocknative", "detail": "native-full"})
            self.native(car, "native-full", 2000, 222)
            car.live.pop()  # check1 is an in-progress capture, with the arm start retained.
            car.write()
            result, clock = review.review(tmp)
            text = review.markdown(result, clock)
            self.assertEqual(result["sessions"][0]["sender"], "stocknative")
            self.assertIsNone(result["sessions"][0]["variant"])
            self.assertFalse(any("вариант не записан" in item for item in result["missing"]))
            self.assertFalse(result["cadence"]["guidedWindowsInferred"])
            self.assertEqual(result["cadence"]["windowSource"], "native_route_transport_to_terminal_or_capture_end")
            window = result["cadence"]["guidedWindows"][0]
            self.assertEqual((window["from"]["elapsedMs"], window["to"]["elapsedMs"]), (2002, 3100))
            self.assertIn("отправитель **stocknative**", text)
            self.assertNotIn("вариант", text)
            self.assertNotIn("первого navStop", text)
            self.assertIn("adas-journal.jsonl не найден", result["missing"])
            self.assertTrue(any("crash/logcat" in item for item in result["missing"]))

    def test_native_route_ownership_sentinel_is_unknown_without_guessing_missing_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            car = Car(tmp)
            self.native(car, "native-own", 2000, 222)
            # Factory route JSON uses Integer.MIN_VALUE, while VehicleLocation uses -1.
            car.write()
            result = run(tmp)
            fields = result["semantics"]["linksPerPath"][0]["fields"]["ownership"]
            self.assertEqual((fields["unknown"], fields["nativeUnknown"], fields["minus1"], fields["missing"]), (1, 1, 0, 0))
            self.assertEqual(result["semantics"]["positions"]["ownershipMinus1"], 2)
            evidence = review.Evidence(tmp)
            drive = review.Drive(evidence, review.Clock(evidence, 3))
            drive.route_json[222]["route"]["links"].extend([link(ownership=-1), link(ownership=0), {}])
            fields = review.semantics(drive, drive.clock)["linksPerPath"][0]["fields"]["ownership"]
            self.assertEqual((fields["unknown"], fields["nativeUnknown"], fields["minus1"], fields["zero"], fields["missing"]), (2, 1, 1, 1, 1))

    def test_native_terminal_packet_closes_observed_window(self):
        with tempfile.TemporaryDirectory() as tmp:
            car = Car(tmp)
            self.native(car, "native-stop", 2000, 222)
            car.live.pop()
            car.live.append({"kind": "fire", "monotonicMs": 3150, "topic": review.NAVIGATION_STATUS,
                             "payloadHex": stock.navigation_status(100).hex()})
            car.live.append({"kind": "fire", "monotonicMs": 4150, "topic": review.NAVIGATION_STATUS,
                             "payloadHex": stock.navigation_status(100).hex()})
            result = run(car.write())
            self.assertEqual(result["cadence"]["guidedWindows"][0]["to"]["elapsedMs"], 3150)

    def test_native_source_lifetime_restart_preserves_route_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            car = Car(tmp)
            self.native(car, "native-restart", 2000, 222)
            self.native(car, "native-restart", 4000, 333)
            next(r for r in car.live if r.get("input") == "nativeInputPolicy" and r["pathId"] == 333)["nativePathReason"] = "source_lifetime_restart"
            car.write()
            items = run(tmp)["paths"]["routes"][0]["items"]
            self.assertEqual(len(items), 2)
            self.assertEqual(items[1]["reason"], "source_lifetime_restart")
            self.assertTrue(items[1]["expected"])

    def test_native_coordinate_policy_is_attributed_to_published_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            car = Car(tmp)
            self.native(car, "native-frame", 2000, 222)
            policy = next(r for r in car.live if r.get("input") == "nativeInputPolicy")
            fields = dict(coordinateFrame="NATIVE_GCJ_UNRESTRICTED_CANDIDATE", coordinateFrameMode="ALTERNATE_ROUTES",
                          coordinateRouteOrdinal=1, nativeRouteType=0, roadClassPolicy="EXPERIMENTAL_FUNCTIONAL")
            policy.update(fields)
            policy["attributeEvidence"] = {"rendererClassProjection": {"nativeClassCounts": {"9": 1}}}
            car.write()
            result = run(tmp)
            route = result["paths"]["routes"][0]["items"][0]
            for key, value in fields.items():
                self.assertEqual(route[key], value)
            retained = result["semantics"]["nativeInputPolicies"][0]
            self.assertEqual(retained["coordinateFrame"], fields["coordinateFrame"])
            self.assertEqual(retained["attributeEvidence"], policy["attributeEvidence"])
            evidence = review.Evidence(tmp)
            clock = review.Clock(evidence, 3)
            model = review.Drive(evidence, clock)
            alignment = review._align(model, clock, 3100, [])
            self.assertEqual(alignment["coordinatePolicy"]["coordinateFrame"], fields["coordinateFrame"])

    @staticmethod
    def native(car, arm, start, path):
        car.live.append({"kind": "input", "input": "liveSession.prerequisite", "monotonicMs": start,
                         "armId": arm, "sender": "stocknative", "detail": "all prerequisites hold"})
        car.note("nativeInputPolicy", start + 1, pathId=path, routeId="native-" + arm,
                 missingBooleanPolicy="explicit_false_test_policy", fields=[{"field": "ownership", "kind": "SDK_NULL"}], blockers=[])
        data = {"code": 200, "RouteType": 50, "data": [{"pathID": path, "links": [link(ownership=-2147483648)],
                                                         "guideGroups": [], "pnts": []}]}
        def fire(t, topic, payload):
            car.live.append({"kind": "fire", "monotonicMs": t, "topic": topic, "payloadHex": payload.hex()})
        for chunk in stock.chunks(json.dumps(data), 1, OFFSET + start):
            fire(start + 2, review.ROUTE, stock.route(chunk, 1))
        fire(start + 3, review.PATH_SELECTED, stock.path_selected(path))
        for t in (start + 100, start + 1100):
            fire(t, review.VEHICLE_LOCATION, stock.vehicle_location(37.5, 55.7, 37.5, 55.7, 0, 0, 90, path, 0, 0, 10, 10, -1))
        car.passive(start + 40, review.SELECTED, selected(path, 200))
        car.guidance(start + 100, route_id="native-" + arm)
        car.live.append({"kind": "input", "input": "liveSession.stopped", "monotonicMs": start + 1200,
                         "armId": arm, "sender": "stocknative", "detail": "closed"})

    def test_latest_native_arm_cannot_use_old_sends_and_aliases_keep_ack(self):
        with tempfile.TemporaryDirectory() as tmp:
            car = Car(tmp)
            car.arm(100)
            car.publish(200, "old", 111, [link()])
            car.tick(500, 111)
            self.native(car, "native-a", 2000, 222)
            self.native(car, "native-b", 4000, 333)
            car.passive(5300, review.SELECTED, selected(333, 200))  # after final live stop: still this arm
            car.write()
            for name in ("adas-packets.jsonl", "progress.jsonl"):
                source = car.evidence / name
                source.rename(car.files / ("denza-drive-evidence--" + name))
            result = run(tmp)
            self.assertEqual(result["attribution"]["armId"], "native-b")
            self.assertGreater(result["attribution"]["excludedLiveRows"], 0)
            self.assertEqual(result["semantics"]["positions"]["sent"], 2)
            self.assertEqual([r["pathId"] for r in result["semantics"]["linksPerPath"]], [333])
            route = result["paths"]["routes"][0]["items"][0]
            self.assertEqual((route["pathId"], route["wireRouteType"], route["ack200"]["afterSelectionMs"]), (333, 50, 37))
            self.assertEqual(len(result["semantics"]["nativeInputPolicies"]), 1)
            evidence = review.Evidence(tmp)
            model = review.Drive(evidence, review.Clock(evidence, 3))
            self.assertTrue(any(a["t"] == 5300 for a in model.answers))
            self.assertFalse(any(a["t"] < 4000 for a in model.answers))
            prior, _ = review.review(tmp, arm_id="native-a")
            self.assertEqual([r["pathId"] for r in prior["semantics"]["linksPerPath"]], [222])
            with self.assertRaises(review.InputError):
                review.review(tmp, arm_id="unknown")

    def test_native_terminal_status_is_observed_from_fire_and_timeout_is_not_complete(self):
        for complete in (True, False):
            with self.subTest(complete=complete), tempfile.TemporaryDirectory() as tmp:
                car = Car(tmp)
                self.native(car, "native-a", 2000, 222)
                car.live.pop()  # replace fixture's immediate stop by actual drain
                car.live.append({"kind": "input", "input": "liveSession.draining", "monotonicMs": 3200,
                                 "armId": "native-a", "sender": "stocknative", "detail": "original cancellation completion"})
                if complete:
                    car.live.append({"kind": "fire", "monotonicMs": 3210, "topic": review.NAVIGATION_STATUS,
                                     "payloadHex": stock.navigation_status(100).hex()})
                for kind, t in (("stopping", 3220), ("stopped", 3230)):
                    car.live.append({"kind": "input", "input": "liveSession." + kind, "monotonicMs": t,
                                     "armId": "native-a", "sender": "stocknative",
                                     "detail": "original terminal output sent" if complete else "drain timeout"})
                result = run(car.write())
                session = result["sessions"][0]
                self.assertEqual(session["pathsConfirmed"], [222])
                self.assertEqual(session["endStatuses"]["sent"], complete)
                self.assertEqual(session["endStatuses"]["values"], [100] if complete else [])

    def native_end(self, *, before=(), after=(), arrival=False, terminal_receipt=True):
        with tempfile.TemporaryDirectory() as tmp:
            car = Car(tmp)
            self.native(car, "native-a", 2000, 222)
            car.live.pop()
            for i, value in enumerate(before):
                car.live.append({"kind": "fire", "monotonicMs": 3150 + i, "topic": review.NAVIGATION_STATUS,
                                 "payloadHex": stock.navigation_status(value).hex()})
            car.live.append({"kind": "input", "input": "liveSession.draining", "monotonicMs": 3200,
                             "armId": "native-a", "sender": "stocknative",
                             "detail": "original arrival completion" if arrival else "original cancellation completion"})
            for i, value in enumerate(after):
                car.live.append({"kind": "fire", "monotonicMs": 3210 + i, "topic": review.NAVIGATION_STATUS,
                                 "payloadHex": stock.navigation_status(value).hex()})
            for kind, t in (("stopping", 3250), ("stopped", 3260)):
                car.live.append({"kind": "input", "input": "liveSession." + kind, "monotonicMs": t,
                                 "armId": "native-a", "sender": "stocknative",
                                 "detail": "Сеанс завершён:original terminal output sent" if terminal_receipt else "drain timeout"})
            return run(car.write())["sessions"][0]

    def test_partial_arrival_suffix_completes_without_inventing_missing_pulses(self):
        for before, after, full in (([110], [110, 110, 100], False),
                                    ([110, 110, 110], [100], False),
                                    ([], [110, 110, 110, 100], True)):
            with self.subTest(before=before, after=after):
                session = self.native_end(before=before, after=after, arrival=True)
                end = session["endStatuses"]
                self.assertTrue(end["sent"])
                self.assertTrue(end["compatibleSuffix"])
                self.assertEqual(end["fullSequenceObserved"], full)
                self.assertEqual(end["values"], after)
                self.assertEqual(session["endSequences"][0]["sent"], after)
        self.assertFalse(self.native_end(after=[110, 110], arrival=True)["endStatuses"]["sent"])
        self.assertFalse(self.native_end(after=[100, 110, 100], arrival=True)["endStatuses"]["sent"])

    def test_repeated_idle_before_ready_is_compatible_but_requires_terminal_receipt(self):
        session = self.native_end(after=[100, 100])
        self.assertTrue(session["endStatuses"]["sent"])
        self.assertTrue(session["endStatuses"]["fullSequenceObserved"])
        self.assertEqual(session["endStatuses"]["values"], [100, 100])
        self.assertFalse(self.native_end(after=[100, 100], terminal_receipt=False)["endStatuses"]["sent"])
        self.assertFalse(self.native_end(after=[100, 101, 100])["endStatuses"]["sent"])

    def test_dry_run_fire_is_never_transport_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            car = Car(tmp)
            car.arm(100)
            car.live.append({"kind": "fire", "monotonicMs": 200, "topic": review.PATH_SELECTED,
                             "payloadHex": stock.path_selected(123).hex()})
            result = run(car.write())
            self.assertEqual(result["cadence"]["topics"], [])


if __name__ == "__main__":
    unittest.main()
