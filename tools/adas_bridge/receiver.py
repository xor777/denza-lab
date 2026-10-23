"""A deterministic in-memory sequence checker, not an ADAS emulator.

Follows the stock map's own lifecycle (MmtSomeIpDataManager, DriveRouteManager): a route is
published once per pathId, positions flow whether or not ADAS has answered, a transient loss or a
stale, duplicate or out-of-order sample only holds the last position (frozen, as the stock's off
route) and never ends the navigation (DYN-02/04/10), progress never moves back on one path
(DYN-01), 110 follows an arrival only, for three seconds (DYN-09). Synthetic confirms are never car
evidence.
"""
from __future__ import annotations

import json

from .bridge import ContractError, map_progress, signed64


class FakeReceiver:
    def __init__(self):
        self.route = None
        self.selected = None
        self.confirmed = None
        self.ended_at = None
        self.arrived = False
        self.last_sample_ms = None
        self.last_sequence = None
        self.last_passed = {}
        self.held = None
        self.publication_epoch = 0
        self.trace = []

    @property
    def active(self):
        return self.route is not None and self.ended_at is None

    def path_id(self):
        return None if self.route is None else self.route["candidateNaviToEHPData"]["pathID"]

    def offer_route(self, route):
        path = signed64(route["candidateNaviToEHPData"]["pathID"])
        self.route = route
        self.publication_epoch += 1
        self.selected = self.confirmed = self.ended_at = None
        self.arrived = False
        self.held = None
        self.trace.append({"event": "route", "pathID": path, "generation": route.get("generation"),
                           "sourceSessionId": route.get("sourceSessionId"), "publicationEpoch": self.publication_epoch})

    def offer_binder_route(self, binders, diagnostic_route):
        from .wire import decode_binder_route, reassemble_chunks
        from .bridge import validate_payload
        import copy
        if isinstance(binders, (bytes, bytearray)):
            binders = [binders]
        details = [json.loads(decode_binder_route(data)["wireJson"]) for data in binders]
        payload = json.loads(reassemble_chunks(details))
        errors = validate_payload(payload)
        if errors:
            raise ContractError("invalid bench route: " + "; ".join(errors[:5]))
        if len(payload["data"]) != 1 or payload["data"][0]["pathID"] != diagnostic_route["candidateNaviToEHPData"]["pathID"]:
            raise ContractError("wire path does not match diagnostic route")
        route = copy.deepcopy(diagnostic_route)
        route["candidateNaviToEHPData"] = payload["data"][0]
        self.offer_route(route)
        self.trace.append({"event": "decoded_binder_route", "chunks": len(binders), "bytes": sum(len(b) for b in binders)})

    def select(self, path_id):
        if self.route is None or path_id != self.path_id():
            raise ContractError("selection must follow matching route")
        self.selected = path_id
        self.trace.append({"event": "selection", "pathID": path_id, "publicationEpoch": self.publication_epoch})

    def select_binder(self, data):
        from .dynamic import decode_struct, SELECTION
        self.select(decode_struct(data, SELECTION)["pathIDReq"])
        self.trace.append({"event": "decoded_binder_selection", "bytes": len(data), "publicationEpoch": self.publication_epoch})

    def confirm(self, path_id, *, publication_epoch=None):
        if publication_epoch is not None and publication_epoch != self.publication_epoch:
            raise ContractError("confirmation belongs to old publication epoch")
        if path_id != self.selected or self.selected is None:
            raise ContractError("confirmation does not match pending selection")
        self.confirmed = path_id
        self.trace.append({"event": "fake_confirmation", "pathID": path_id, "publicationEpoch": self.publication_epoch})

    def consume_dynamic(self, bundle):
        from .dynamic import decode_struct, POSITION, STATUS
        if not self.active:
            raise ContractError("dynamic bytes require an active route")
        for packet in bundle["packets"]:
            data = bytes.fromhex(packet["binderProtobufHex"])
            if packet["name"] == "sdVehicleLocation" and decode_struct(data, POSITION)["curSDRouteID"] != self.path_id():
                raise ContractError("dynamic protobuf position belongs to another path")
            if packet["name"] == "NavigationStatus" and decode_struct(data, STATUS)["navigationStatus"] != 101:
                raise ContractError("unexpected active dynamic navigation status")
        self.trace.append({"event": "decoded_binder_dynamic", "names": [row["name"] for row in bundle["packets"]],
                           "bytes": sum(row["bytes"] for row in bundle["packets"]),
                           "confirmed": self.confirmed is not None, "publicationEpoch": self.publication_epoch})

    def progress(self, sample, *, now_ms, clock="wall"):
        rejected = {"accepted": False, "publicationReady": False}
        if not self.active:
            return dict(rejected, reason="no_active_route")
        timestamp = sample.get("capturedUptimeMs" if clock == "uptime" else "capturedAtMs")
        sequence = sample.get("sequence")
        # Duplicates and replays first: a re-read of an unchanged sample changes nothing (DYN-02).
        if self.last_sample_ms is not None and (type(timestamp) is not int or timestamp <= self.last_sample_ms):
            return dict(rejected, reason="out_of_order_or_duplicate", held=self.held)
        if sample.get("schema") == "denza-yandex-guidance-v1" and (
                type(sequence) is not int or sequence < 0 or (self.last_sequence is not None and sequence <= self.last_sequence)):
            return dict(rejected, reason="out_of_order_or_duplicate", held=self.held)
        result = map_progress(self.route, sample, now_ms=now_ms, clock=clock)
        if not result["accepted"]:
            # Transient: the position stays where it was; navigation goes on (DYN-02, DYN-04).
            return dict(result, held=self.held)
        last = self.last_passed.get(self.path_id())
        if last is not None and result["passedM"] < last:
            self.trace.append({"event": "progress_behind", "passedM": result["passedM"], "lastPassedM": last})
            return dict(rejected, reason="behind_last_progress", held=self.held)
        self.last_passed[self.path_id()] = result["passedM"]
        self.last_sample_ms = timestamp
        self.last_sequence = sequence
        self.held = result
        self.trace.append({"event": "offline_progress", "result": result})
        return result

    def end(self, *, now_ms, arrived=False):
        if not self.active:
            self.trace.append({"event": "end_without_active_route", "atMs": now_ms})
            return
        self.ended_at = now_ms
        self.arrived = bool(arrived)
        self.publication_epoch += 1
        self.confirmed = self.selected = None
        self.trace.append({"event": "end", "arrived": self.arrived, "atMs": now_ms})

    def status(self, *, now_ms):
        if self.active:
            return 101
        if self.ended_at is not None and self.arrived and now_ms - self.ended_at < 3000:
            return 110
        return 100
