#!/usr/bin/env python3
"""Build emulator-only instrumentation APKs; no installation or car access."""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',type=Path,required=True)
    args=parser.parse_args(); args.out=args.out.resolve(); args.out.mkdir(parents=True,exist_ok=False)
    sdk=Path(os.environ.get('ANDROID_HOME','/opt/homebrew/share/android-commandlinetools'))
    java=Path(os.environ.get('JAVA_HOME','/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
    env=dict(os.environ,JAVA_HOME=str(java)); bt=sdk/'build-tools/36.0.0'; android=sdk/'platforms/android-35/android.jar'
    source=ROOT/'experiments/hud-speed-module/test-device'
    def run(command): subprocess.run([str(x) for x in command],env=env,check=True)
    stubs=args.out/'stubs'; stubs.mkdir()
    run([java/'bin/javac','--release','8','-cp',android,'-d',stubs,*sorted((source/'stubs').rglob('*.java'))])
    for kind,target,entry,key,password in [
            ('source','air.StrelkaHUDFREE','SourceCheck',ROOT/'captures/hud-speed-signing/adapter.p12','denza-local-experiment'),
            ('host','dev.denza.apps','HostCheck',Path.home()/'.android/debug.keystore','android')]:
        out=args.out/kind; out.mkdir(); classes=out/'classes'; classes.mkdir(); dex=out/'dex'; dex.mkdir()
        manifest=out/'AndroidManifest.xml'
        manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.denza.hud.speed.check.{kind}">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/>
<application android:label="HUD Speed emulator check"/>
<instrumentation android:name="dev.denza.hud.speed.check.{entry}" android:targetPackage="{target}"/>
</manifest>''')
        run([java/'bin/javac','--release','8','-encoding','UTF-8','-cp',str(android)+os.pathsep+str(stubs),'-d',classes,*sorted((source/kind).rglob('*.java'))])
        run([java/'bin/jar','cf',out/'test.jar','-C',classes,'.'])
        run([bt/'d8','--min-api','26','--lib',android,'--classpath',stubs,'--output',dex,out/'test.jar'])
        run([bt/'aapt2','link','-I',android,'--manifest',manifest,'-o',out/'unsigned.apk'])
        with zipfile.ZipFile(out/'unsigned.apk','a') as archive: archive.write(dex/'classes.dex','classes.dex')
        run([bt/'zipalign','-f','4',out/'unsigned.apk',out/'aligned.apk'])
        run([bt/'apksigner','sign','--ks',key,'--ks-pass','pass:'+password,'--out',out/'emulator-only-check.apk',out/'aligned.apk'])


if __name__=='__main__': main()
