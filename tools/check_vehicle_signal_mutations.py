#!/usr/bin/env python3
"""Host-only behavioural mutation checks of the real typed hub; no car or checkout edits.

Requires the Kotlin/JUnit dependencies already resolved by the Android Gradle build and JDK 17.
Each mutant is compiled independently in a temporary directory. Compile errors/timeouts are
INVALID, never a killed mutant. Reports include exact source hashes and individual JUnit output.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SIGNALS = Path("apps/denza-apps/src/main/java/dev/denza/apps/feature/vehicle/signal")
TESTS = Path("apps/denza-apps/src/test/java/dev/denza/apps/feature/vehicle/signal")
PRODUCTION = [SIGNALS / name for name in (
    "VehicleSignals.kt", "VehicleSignalHub.kt", "TurnSignalEventProtocol.kt",
)]
TEST_FILES = [TESTS / name for name in (
    "VehicleSignalHubTest.kt", "TurnSignalEventProtocolTest.kt", "VehicleSignalReliabilityTest.kt",
)]
HUB = SIGNALS / "VehicleSignalHub.kt"
PROTOCOL = SIGNALS / "TurnSignalEventProtocol.kt"
MUTATIONS = [
    ("receipt-renews-freshness", PROTOCOL,
     "                        observedAtElapsedMs,\n                        observedAtElapsedMs,",
     "                        observedAtElapsedMs,\n                        publishedAtElapsedMs,"),
    ("accept-invalid-protocol-clock", PROTOCOL,
     "observedAtNanos < 0L || observedAtElapsedMs > publishedAtElapsedMs", "false"),
    ("accept-future-state-clock", HUB, "state.verifiedAtElapsedMs > nowElapsedMs", "false"),
    ("disable-read-expiry", HUB,
     "nowElapsedMs - state.verifiedAtElapsedMs > demand.maxVerificationAgeMs", "false"),
    ("inline-source-delivery", HUB, "deliveryWorker.execute(::submitDrain)", "submitDrain()"),
    ("silent-executor-rejection", HUB,
     '''                        queue.addLast(VehicleSignalEventNotice.Unavailable(
                            VehicleSignalMissingReason.AMBIGUOUS,
                            "consumer executor rejected event delivery",
                        ))''', "                        // mutant: silently discard the gap"),
    ("strand-traffic-during-rejection", HUB,
     "val changed = offeredVersion != version", "val changed = false"),
    ("idle-retry-loop", HUB,
     "val changed = offeredVersion != version", "val changed = true"),
    ("silent-demand-restart", HUB,
     "if (previous != null && requested.isNotEmpty()) {",
     "if (previous != null && requested.isEmpty()) {"),
    ("foreign-key-unavailability", HUB,
     "if (update.key != null && update.key !in activation.keys) return", "// mutant: no source ownership check"),
    ("listener-exception-strands-mailbox", HUB,
     "} catch (_: Exception) {\n                    // One consumer callback",
     "} catch (_: RuntimeException) {\n                    // One consumer callback"),
    ("remove-activation-fence", HUB,
     "if (activation?.id != activationId) return", "if (activation == null) return"),
    ("unbounded-event-mailbox", HUB,
     "const val EVENT_MAILBOX_CAPACITY = 16", "const val EVENT_MAILBOX_CAPACITY = 100000"),
    ("unbounded-delivery-lanes", HUB,
     "check(deliverySlots.tryAcquire())", "check(true)"),
    ("deliver-after-close", HUB,
     "                closed = true\n                queue.clear()",
     "                // mutant: retain queued callbacks after close"),
    ("accept-sequence-gap", PROTOCOL,
     "if (sequence != lastSequence + 1L) {", "if (false) {"),
    ("snapshot-becomes-transient-event", HUB,
     "                    replaceSampleLocked(source, update)\n                    emptyList()",
     '''                    replaceSampleLocked(source, update)
                    @Suppress("UNCHECKED_CAST")
                    val typed = update as VehicleSignalSourceUpdate.Sample<Any>
                    eventDeliveriesLocked(source, VehicleSignalSourceUpdate.Event(
                        typed.key, typed.value, typed.observedAtElapsedMs,
                        typed.verifiedAtElapsedMs, typed.sourceEpoch, typed.sequence,
                    ))'''),
]
CONTROL = ("diagnostic-only-control", HUB, '"connection starting"', '"connection pending"')


def run(command, cwd, timeout):
    return subprocess.run(command, cwd=cwd, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=timeout)


def classpath():
    cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    cache = cache / "caches/modules-2/files-2.1"
    dependencies = (
        ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.3.21"),
        ("org.jetbrains.kotlin", "kotlin-stdlib", "2.3.21"),
        ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.3.21"),
        ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
        ("org.jetbrains", "annotations", "13.0"),
        ("junit", "junit", "4.13.2"),
        ("org.hamcrest", "hamcrest-core", "1.3"),
    )
    jars = []
    for group, artifact, version in dependencies:
        matches = list((cache / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
        if len(matches) != 1:
            raise SystemExit(f"Resolve Gradle dependencies first; missing/ambiguous {artifact}:{version}")
        jars.append(str(matches[0]))
    return os.pathsep.join(jars)


def evaluate(name, sources, java, cp, report):
    with tempfile.TemporaryDirectory(prefix="denza-signal-mutant-") as temporary:
        directory = Path(temporary)
        files = []
        for relative, content in sources.items():
            destination = directory / relative.name
            destination.write_text(content)
            files.append(str(destination))
        classes = directory / "classes"
        try:
            compiled = run([java, "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                            "-no-stdlib", "-no-reflect", "-classpath", cp, "-d", str(classes),
                            *files], directory, 90)
            output = compiled.stdout
            if compiled.returncode:
                status = "INVALID"
            else:
                tests = run([java, "-cp", str(classes) + os.pathsep + cp, "org.junit.runner.JUnitCore",
                             *("dev.denza.apps.feature.vehicle.signal." + path.stem
                               for path in TEST_FILES)], directory, 20)
                output += tests.stdout
                if tests.returncode == 0 and "OK (" in tests.stdout:
                    status = "PASS"
                elif tests.returncode == 1 and "FAILURES!!!" in tests.stdout:
                    status = "FAIL"
                else:
                    status = "INVALID"
        except subprocess.TimeoutExpired as error:
            status, output = "INVALID", f"timeout: {error}"
        (report / f"{name}.txt").write_text(output)
        print(f"{name}: {status}", flush=True)
        return {"name": name, "status": status}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--red-ref", help="Also prove current tests fail against this committed production base")
    parser.add_argument("--repeat", type=int, default=1, help="Repeat the unmutated baseline (concurrency checks)")
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/vehicle-signal-mutations")
    args = parser.parse_args()
    if args.repeat < 1:
        parser.error("--repeat must be positive")
    report = args.output.resolve()
    report.mkdir(parents=True, exist_ok=True)
    java_home = os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home")
    java, cp = str(Path(java_home) / "bin/java"), classpath()
    sources = {path: (ROOT / path).read_text() for path in PRODUCTION + TEST_FILES}
    results = []
    if args.red_ref:
        red = dict(sources)
        for path in PRODUCTION:
            result = run(["git", "show", f"{args.red_ref}:{path}"], ROOT, 10)
            if result.returncode:
                raise SystemExit(result.stdout)
            red[path] = result.stdout
        results.append(evaluate("red-base", red, java, cp, report) | {"expected": "FAIL"})
    for repetition in range(args.repeat):
        results.append(evaluate(f"green-{repetition + 1}", sources, java, cp, report) | {"expected": "PASS"})
        if results[-1]["status"] != "PASS":
            raise SystemExit("Unmutated baseline failed; mutants were not run")
    for name, path, before, after in [*MUTATIONS, CONTROL]:
        copies = dict(sources)
        expected_count = 2 if name == CONTROL[0] else 1
        if copies[path].count(before) != expected_count:
            raise SystemExit(f"{name}: expected {expected_count} mutation targets; update the operator")
        copies[path] = copies[path].replace(before, after)
        results.append(evaluate(name, copies, java, cp, report) |
                       {"expected": "PASS" if name == CONTROL[0] else "FAIL"})
    summary = {
        "base": run(["git", "rev-parse", "HEAD"], ROOT, 10).stdout.strip(),
        "sources_sha256": {str(path): hashlib.sha256(content.encode()).hexdigest()
                           for path, content in sources.items()},
        "results": results,
        "killed": sum(r["status"] == "FAIL" for r in results if r["name"] in {m[0] for m in MUTATIONS}),
        "mutants": len(MUTATIONS),
    }
    (report / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    if any(result["status"] != result["expected"] for result in results):
        raise SystemExit(f"Mutation check failed; inspect {report}")
    print(f"Killed {summary['killed']}/{summary['mutants']}; diagnostic control survived. Reports: {report}")


if __name__ == "__main__":
    main()
