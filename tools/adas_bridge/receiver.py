"""A deterministic in-memory sequence checker, not an ADAS emulator.

Checks route→selection→confirmation→progress order, reroute generations, clock
expiry, terminal status timing. Synthetic confirms are never car evidence.
"""
from __future__ import annotations

from .bridge import ContractError, map_progress, signed64


class FakeReceiver:
    def __init__(self):
        self.route = None
        self.selected = None
        self.confirmed = None
        self.generation = -1
        self.ended_at = None
        self.last_sample_ms = None
        self.last_sequence = None
        self.publication_epoch = 0
        self.minimum_sample_ms = None
        self.trace = []

    def offer_route(self, route):
        generation = route.get("generation")
        if type(generation) is not int or generation <= self.generation:
            raise ContractError("route generation must increase (reroutes invalidate old selection)")
        signed64(route["candidateNaviToEHPData"]["pathID"])
        self.route = route
        self.generation = generation
        self.publication_epoch += 1
        self.minimum_sample_ms = None
        self.selected = self.confirmed = self.ended_at = self.last_sample_ms = self.last_sequence = None
        self.trace.append({"event": "route", "generation": generation,
                           "sourceSessionId": route.get("sourceSessionId"), "publicationEpoch": self.publication_epoch})

    def offer_binder_route(self, data, diagnostic_route):
        from .wire import decode_binder_route
        from .bridge import validate_payload
        import copy
        import json
        record = decode_binder_route(data)
        envelope = json.loads(record["wireJson"])
        if envelope.get("length") != 1 or envelope.get("currentIndex") != 0:
            raise ContractError("fake receiver expects a complete single-chunk bench route")
        payload = json.loads(envelope["payload"])
        errors = validate_payload(payload)
        if errors:
            raise ContractError("invalid bench route: " + "; ".join(errors))
        if len(payload["data"]) != 1 or payload["data"][0]["pathID"] != diagnostic_route["candidateNaviToEHPData"]["pathID"]:
            raise ContractError("wire path does not match diagnostic route")
        route = copy.deepcopy(diagnostic_route)
        route["candidateNaviToEHPData"] = payload["data"][0]
        self.offer_route(route)
        self.trace.append({"event": "decoded_binder_route", "bytes": len(data), "incompleteDraft": True})

    def republish_cached_route(self, *, now_ms):
        if self.route is None:
            raise ContractError("no cached route")
        self.publication_epoch += 1
        self.minimum_sample_ms = now_ms
        self.selected = self.confirmed = self.ended_at = None
        self.trace.append({"event": "republish_cached_route", "generation": self.generation,
                           "publicationEpoch": self.publication_epoch, "atMs": now_ms})

    def select(self, path_id):
        if self.route is None or path_id != self.route["candidateNaviToEHPData"]["pathID"]:
            raise ContractError("selection must follow matching route")
        self.selected = path_id
        self.trace.append({"event": "selection", "pathID": path_id, "publicationEpoch": self.publication_epoch})

    def select_binder(self, data):
        from .dynamic import decode_struct, SELECTION
        self.select(decode_struct(data, SELECTION)["pathIDReq"])
        self.trace.append({"event": "decoded_binder_selection", "bytes": len(data),
                           "publicationEpoch": self.publication_epoch})

    def consume_dynamic(self, bundle):
        from .dynamic import decode_struct, POSITION, TRAFFIC, STATUS
        if self.confirmed is None or self.ended_at is not None:
            raise ContractError("dynamic bytes require confirmed active fake route")
        schemas = {"sdVehicleLocation": POSITION, "TrafficInfo": TRAFFIC, "NavigationStatus": STATUS}
        for packet in bundle["packets"]:
            decoded = decode_struct(bytes.fromhex(packet["binderProtobufHex"]), schemas[packet["name"]])
            if packet["name"] == "sdVehicleLocation" and decoded["curSDRouteID"] != self.confirmed:
                raise ContractError("dynamic protobuf position belongs to another path")
            if packet["name"] == "NavigationStatus" and decoded["navigationStatus"] != 101:
                raise ContractError("unexpected active dynamic navigation status")
        self.trace.append({"event": "decoded_binder_dynamic", "names": [row["name"] for row in bundle["packets"]],
                           "bytes": sum(row["bytes"] for row in bundle["packets"]),
                           "publicationEpoch": self.publication_epoch, "incompleteDraft": True})

    def confirm(self, path_id, *, publication_epoch=None):
        if publication_epoch is not None and publication_epoch != self.publication_epoch:
            raise ContractError("confirmation belongs to old publication epoch")
        if path_id != self.selected or self.selected is None:
            raise ContractError("confirmation does not match pending selection")
        self.confirmed = path_id
        self.trace.append({"event": "fake_confirmation", "pathID": path_id, "publicationEpoch": self.publication_epoch})

    def progress(self, sample, *, now_ms, clock="wall"):
        if self.confirmed is None or self.ended_at is not None:
            raise ContractError("progress needs confirmed, active route")
        timestamp = sample.get("capturedUptimeMs" if clock == "uptime" else "capturedAtMs")
        if (sample.get("publicationEpoch", self.publication_epoch) != self.publication_epoch or
                (self.minimum_sample_ms is not None and type(timestamp) is int and timestamp < self.minimum_sample_ms)):
            return {"accepted": False, "publicationReady": False, "reason": "old_publication_epoch"}
        if self.last_sample_ms is not None and (type(timestamp) is not int or timestamp <= self.last_sample_ms):
            raise ContractError("replayed/out-of-order sample")
        sequence = sample.get("sequence")
        if sample.get("schema") == "denza-yandex-guidance-v1" and (type(sequence) is not int or sequence < 0 or (self.last_sequence is not None and sequence <= self.last_sequence)):
            raise ContractError("missing/replayed guidance sequence")
        result = map_progress(self.route, sample, now_ms=now_ms, clock=clock)
        if not result["accepted"]:
            # Fail closed in the offline state machine; explicit lifecycle status.
            self.end(now_ms=now_ms)
            return result
        self.last_sample_ms = timestamp
        self.last_sequence = sequence
        self.trace.append({"event": "offline_progress", "result": result})
        return result

    def end(self, *, now_ms):
        if self.ended_at is None:
            self.ended_at = now_ms
            self.publication_epoch += 1
        self.confirmed = self.selected = None
        self.trace.append({"event": "end", "status": 110, "atMs": now_ms})

    def status(self, *, now_ms):
        if self.ended_at is not None:
            return 110 if now_ms-self.ended_at < 3000 else 100
        return 101 if self.confirmed is not None else 100
