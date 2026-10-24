#!/usr/bin/env python3
"""Production live coordinator + pure protocol, fake Android clock/files/Binder boundary. No adb."""
import argparse,json,pathlib,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[3]
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',type=pathlib.Path,required=True);a=p.parse_args()
    a.out.mkdir(parents=True,exist_ok=True);classes=a.out/'classes';classes.mkdir(exist_ok=True)
    sources=list(pathlib.Path(__file__).parent.rglob('*.java'))
    sources += [x for x in (ROOT/'libraries/adas-link/src/main/java').rglob('*.java') if x.name!='SomeIpPublisher.java']
    module=ROOT/'experiments/yandex-hud-module/src/main/java/dev/denza/hud/probe'
    sources += [module/'LiveMapBridge.java',module/'AdasFeed.java',module/'CallbackQueue.java']
    jdk=pathlib.Path('/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin')
    subprocess.run([str(jdk/'javac'),'--release','8','-encoding','UTF-8','-d',str(classes),*map(str,sources)],check=True)
    subprocess.run([str(jdk/'java'),'-cp',str(classes),'dev.denza.hud.probe.LiveRuntimeTest',str(a.out/'fixtures')],check=True)
if __name__=='__main__':main()
