#!/usr/bin/env python3
"""Replay a bounded HU filesystem metadata observation, without contacting it."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "research/vehicle-diagnostics/diagnostic-source-cache-presence-20261021.json"
BASE = ROOT / "captures/diagnostics-transport-followup-20261021/cache-presence"
PRIVATE = ["/data/vhds/trigger_list_cfg/triggerlistconfig.txt",
           "/data/vhds/trigger_list_cfg/allone.txt",
           "/cancollect/dataCollect/canConfigureJson",
           "/cancollect/dataCollect/vehicleCode"]
SCRIPTS = "/sdcard/Android/data/com.byd.spotinspection/files/Scripts"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    report = json.loads(REPORT.read_text())
    require(report["schema"] == "denza-lab.diagnostic-source-cache-presence.v1", "schema")
    for source in report["sources"]:
        raw = (ROOT / source["path"]).read_bytes()
        require(len(raw) == source["bytes"] and hashlib.sha256(raw).hexdigest() == source["sha256"], "source " + source["path"])
    meta = json.loads((BASE / "result.json").read_text())
    listing = json.loads((BASE / "script-files.json").read_text())
    require(meta["serial"] == "127.0.0.1:5555" and meta["status"] == "metadata_attempted", "existing serial")
    require(meta["timeUtc"] == report["timeUtc"], "capture time")
    require(len(meta["commands"]) == 2, "bounded metadata commands")
    state, stat = meta["commands"]
    require(state == {"argv": ["adb", "-s", meta["serial"], "get-state"],
                      "exit": 0, "stdout": "device\n", "stderr": ""}, "transport state")
    script = "getprop persist.sys.AutoType\ngetprop sys.car.protocol\nstat -c '%n|%F|%s|%a|%u|%g|%Y' " + " ".join(PRIVATE + [SCRIPTS]) + "\n"
    require(stat["argv"] == ["adb", "-s", meta["serial"], "shell", "-T", script], "only fixed getprop/stat")
    require(stat["exit"] == 1, "stat failed on protected paths")
    rows = stat["stdout"].splitlines()
    require(len(rows) == 3 and rows[:2] == ["170", "CANFD"], "reported HU properties")
    directory = rows[2].split("|")
    require(directory[:2] == [SCRIPTS, "directory"] and len(directory) == 7, "script directory metadata")
    expected_errors = "".join(f"stat: '{path}': Permission denied\n" for path in PRIVATE)
    require(stat["stderr"] == expected_errors, "four permission denials, not absence")
    require(report["protectedFiles"] == [{"path": path, "result": "permission_denied",
                                         "exists": None, "contentsRead": False} for path in PRIVATE], "existence stays unknown")
    find = "find " + SCRIPTS + " -maxdepth 2 -type f -exec stat -c '%n|%s|%a|%Y' {} \\;"
    require(listing == {"argv": ["adb", "-s", meta["serial"], "shell", "-T", find],
                        "exit": 0, "stdout": "", "stderr": ""}, "bounded successful empty file listing")
    require(report["scriptDirectory"] == {"path": SCRIPTS, "metadata": rows[2],
                                           "maxDepth": 2, "regularFileRows": 0,
                                           "enumerationExit": 0, "applicableScriptRecovered": False}, "listing projection")
    require(report["adbInvocations"] == 3 and report["ecuRequests"] == 0 and
            report["fileContentsRead"] == 0 and report["vehicleMutations"] == 0, "metadata-only scope")
    require(report["currentCollectionTableRecovered"] is False and
            report["qualifiedFactoryDefinitions"] == [], "no table or dictionary promotion")
    print("PASS adb_invocations=3 ecu_requests=0 protected_paths_denied=4 script_files_depth2=0 definitions=0")


if __name__ == "__main__":
    main()
