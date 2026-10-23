#!/usr/bin/env python3
"""Run a local JSON event list through FakeReceiver. Does not open a transport."""
import argparse
import json
from pathlib import Path

from .bridge import ContractError, read_json
from .receiver import FakeReceiver


def replay(events, *, base_dir=Path(".")):
    receiver = FakeReceiver()
    results = []
    for i, event in enumerate(events):
        try:
            operation = event["event"]
            if operation == "route":
                receiver.offer_route(read_json(base_dir / event["convertedFile"]))
            elif operation == "selection":
                receiver.select(event["pathID"])
            elif operation == "fake_confirmation":
                receiver.confirm(event["pathID"], publication_epoch=event.get("publicationEpoch"))
            elif operation == "republish_cached_route":
                receiver.republish_cached_route(now_ms=event["nowMs"])
            elif operation == "progress":
                results.append({"eventIndex": i, "result": receiver.progress(event["sample"], now_ms=event["nowMs"], clock=event.get("clock", "wall"))})
            elif operation == "end":
                receiver.end(now_ms=event["nowMs"])
            elif operation == "status":
                results.append({"eventIndex": i, "status": receiver.status(now_ms=event["nowMs"])})
            else:
                raise ContractError(f"unknown event {operation!r}")
        except (ContractError, KeyError, TypeError) as exc:
            raise ContractError(f"event {i}: {exc}") from exc
    return {"fakeReceiverOnly": True, "receiverAcceptanceEstablished": False,
            "results": results, "trace": receiver.trace}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("events")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    try:
        result = replay(read_json(args.events), base_dir=Path(args.events).resolve().parent)
        Path(args.out).write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n")
        print(json.dumps({"out": args.out, "fakeReceiverOnly": True, "traceEvents": len(result["trace"])}))
    except (ValueError, KeyError, TypeError) as exc:
        parser.exit(2, f"invalid offline sequence: {exc}\n")


if __name__ == "__main__":
    main()
