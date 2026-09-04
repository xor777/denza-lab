#!/usr/bin/env python3
"""Compile the real renderer with a minimal Android host shim and run its behavioural tests.

No Android/vehicle timing or firmware behavior is simulated. The shim only supplies API-shaped
objects, controlled callbacks and Binder transaction recording. Production APKs never include it.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = Path("apps/denza-apps/src/main/java/dev/denza/apps/feature/mirrors")
RENDERER = PACKAGE / "AvcCameraRenderer.java"
GATE = PACKAGE / "AvcInitializationGate.java"
TIMING = PACKAGE / "AvcStartupTiming.java"
TEST = Path("tools/tests/mirrors_startup/RendererContractTest.java")

# API scaffolding only. Assertions live in RendererContractTest and execute the actual renderer.
SHIMS = {
    "content/Context": "public abstract class Context { public static final int BIND_AUTO_CREATE=1; public abstract boolean bindService(Intent i,ServiceConnection c,int f); public abstract void unbindService(ServiceConnection c); }",
    "content/Intent": "public class Intent { public Intent(String a){} public Intent setPackage(String p){return this;} }",
    "content/ComponentName": "public class ComponentName {}",
    "content/ServiceConnection": "public interface ServiceConnection { void onServiceConnected(ComponentName n,android.os.IBinder b); void onServiceDisconnected(ComponentName n); default void onBindingDied(ComponentName n){} default void onNullBinding(ComponentName n){} }",
    "os/IBinder": "public interface IBinder { boolean transact(int c,Parcel d,Parcel r,int f) throws RemoteException; }",
    "os/RemoteException": "public class RemoteException extends Exception { public RemoteException(String m){super(m);} }",
    "os/Parcel": "public class Parcel { private final java.util.List<Object> values=new java.util.ArrayList<>(); private int offset; public static Parcel obtain(){return new Parcel();} public void recycle(){} public void readException(){} public void writeInterfaceToken(String s){writeString(s);} public void writeString(String s){values.add(s);} public String readString(){return (String)values.get(offset++);} public void writeInt(int v){values.add(v);} public int readInt(){return (Integer)values.get(offset++);} }",
    "os/SystemClock": "public class SystemClock { public static long now=100; public static int reads; public static long elapsedRealtime(){reads++;return now;} }",
    "graphics/SurfaceTexture": "public class SurfaceTexture { public void setDefaultBufferSize(int w,int h){} }",
    "graphics/ColorMatrix": "public class ColorMatrix { public ColorMatrix(){} public ColorMatrix(float[] a){} public void setSaturation(float f){} public void postConcat(ColorMatrix m){} }",
    "graphics/ColorMatrixColorFilter": "public class ColorMatrixColorFilter { public ColorMatrixColorFilter(ColorMatrix m){} }",
    "graphics/Paint": "public class Paint { public void setColorFilter(ColorMatrixColorFilter f){} }",
    "graphics/RenderEffect": "public class RenderEffect { public static RenderEffect createColorFilterEffect(ColorMatrixColorFilter f){return new RenderEffect();} }",
    "view/View": "public class View { public static final int LAYER_TYPE_NONE=0,LAYER_TYPE_HARDWARE=2; }",
    "view/Surface": "public class Surface { private boolean valid=true; public Surface(android.graphics.SurfaceTexture t){} public boolean isValid(){return valid;} public void release(){valid=false;} public void writeToParcel(android.os.Parcel p,int flags){} }",
    "view/TextureView": "public class TextureView extends View { public boolean available=true; public android.graphics.SurfaceTexture texture=new android.graphics.SurfaceTexture(); public TextureView(android.content.Context c){} public void setSurfaceTextureListener(SurfaceTextureListener l){} public boolean isAvailable(){return available;} public android.graphics.SurfaceTexture getSurfaceTexture(){return texture;} public int getWidth(){return 720;} public int getHeight(){return 450;} public void setRenderEffect(android.graphics.RenderEffect e){} public void setLayerType(int t,android.graphics.Paint p){} public interface SurfaceTextureListener { void onSurfaceTextureAvailable(android.graphics.SurfaceTexture t,int w,int h); void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture t,int w,int h); boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture t); void onSurfaceTextureUpdated(android.graphics.SurfaceTexture t); } }",
}


def evaluate(name, sources, classpath, java_home, report):
    with tempfile.TemporaryDirectory(prefix="denza-mirror-startup-") as temporary:
        directory = Path(temporary)
        files = []
        for path, content in sources.items():
            target = directory / path.name
            target.write_text(content)
            files.append(str(target))
        for path, body in SHIMS.items():
            target = directory / "android" / (path + ".java")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("package android." + path.rsplit("/", 1)[0].replace("/", ".") + ";\n" + body)
            files.append(str(target))
        classes = directory / "classes"
        try:
            compile_result = subprocess.run([str(java_home / "bin/javac"), "-cp", classpath,
                "-d", str(classes), *files], text=True, capture_output=True, timeout=30)
            output = compile_result.stdout + compile_result.stderr
            if compile_result.returncode:
                status = "INVALID"
            else:
                result = subprocess.run([str(java_home / "bin/java"), "-cp",
                    str(classes) + os.pathsep + classpath, "org.junit.runner.JUnitCore",
                    "dev.denza.apps.feature.mirrors.RendererContractTest"],
                    text=True, capture_output=True, timeout=20)
                output += result.stdout + result.stderr
                status = ("PASS" if result.returncode == 0 and "OK (" in result.stdout else
                          "FAIL" if result.returncode == 1 and "FAILURES!!!" in result.stdout else "INVALID")
        except subprocess.TimeoutExpired as error:
            status, output = "INVALID", str(error)
        (report / f"{name}.txt").write_text(output)
        print(f"{name}: {status}", flush=True)
        return status


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--red-ref", help="Run current tests against this committed renderer first")
    parser.add_argument("--expect-red", action="store_true", help="Accept a behavioural failure of the current pre-fix renderer")
    args = parser.parse_args()
    report = ROOT / "build/reports/mirrors-startup"
    report.mkdir(parents=True, exist_ok=True)
    cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"
    jars = []
    for group, artifact, version in (("junit", "junit", "4.13.2"), ("org.hamcrest", "hamcrest-core", "1.3")):
        matches = list((cache / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
        if len(matches) != 1:
            raise SystemExit(f"Resolve Gradle test dependencies first: {artifact}:{version}")
        jars.append(str(matches[0]))
    classpath = os.pathsep.join(jars)
    java_home = Path(os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"))
    paths = [RENDERER, GATE, TEST] + ([TIMING] if (ROOT / TIMING).exists() else [])
    sources = {path: (ROOT / path).read_text() for path in paths}
    results = []
    if args.red_ref:
        red = dict(sources)
        for path in (RENDERER, GATE):
            red[path] = subprocess.check_output(["git", "show", f"{args.red_ref}:{path}"], cwd=ROOT, text=True)
        results.append({"name": "red-base", "actual": evaluate("red-base", red, classpath, java_home, report), "expected": "FAIL"})
    expected = "FAIL" if args.expect_red else "PASS"
    results.append({"name": "current", "actual": evaluate("current", sources, classpath, java_home, report), "expected": expected})
    summary = {"results": results, "source_sha256": {str(path): hashlib.sha256(value.encode()).hexdigest() for path, value in sources.items()}}
    (report / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    if any(result["actual"] != result["expected"] for result in results):
        raise SystemExit(f"Check failed: {report}")


if __name__ == "__main__":
    main()
