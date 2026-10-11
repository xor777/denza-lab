#!/usr/bin/env python3
# Owner-authorized, single-session car experiment. See experiments/alice-wheel-probe/README.md.
import subprocess,pathlib,time,json,shlex,threading,hashlib,argparse
ROOT=pathlib.Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser(description='Install and observe the bounded Alice wheel probe; starts DISARMED. Create OUTPUT/STOP to clean up, or wait ten minutes.')
parser.add_argument('output', type=pathlib.Path, help='New capture directory (must not already exist)')
O=parser.parse_args().output.resolve(); O.mkdir(parents=True, exist_ok=False)
ADB=['adb','-s','127.0.0.1:5555']; PKG='dev.denza.alicewheel.probe'; SERVICE=PKG+'/'+PKG+'.VoiceKeyService'
APK=ROOT/'experiments/alice-wheel-probe/build/outputs/apk/debug/alice-wheel-probe-debug.apk'
def shell(*args):
 r=subprocess.run(ADB+['shell',shlex.join(args)],capture_output=True,timeout=25,check=True)
 return r.stdout
def save(name,*args):
 out=shell(*args); (O/name).write_bytes(out); return out
def apphash(pkg,name):
 path=shell('pm','path',pkg).decode().splitlines()[0].removeprefix('package:')
 return save(name,'sha256sum',path).decode().split()[0]
def command(op,seconds=120):
 return shell('am','broadcast','-n',PKG+'/.ProbeReceiver','-a',PKG+'.COMMAND','--es','op',op,'--ei','seconds',str(seconds))
props=['log.tag.WindowManager','log.tag.MediaKeyHandler','log.tag.AliceWheelProbe']
old={p:shell('getprop',p).decode().strip() for p in props}
services=shell('settings','get','secure','enabled_accessibility_services').decode().strip()
(O/'baseline.json').write_text(json.dumps({'props':old,'services':services},indent=2))
if ('package:'+PKG) in shell('pm','list','packages',PKG).decode().splitlines(): raise RuntimeError('Probe already installed; refusing replacement')
for pkg in ['dev.denza.apps','ru.yandex.yandexnavi']: apphash(pkg,pkg+'-sha-before.txt')
save('crash-before.txt','logcat','-b','crash','-d','-v','threadtime')
save('audio-before.txt','dumpsys','audio')
save('accessibility-before.txt','dumpsys','accessibility')
installed=False; enabled=False; log=None; reader=None; samples=[]
def sample_click():
 start=time.monotonic()
 for t in [0.3,1.5,3.5]:
  time.sleep(max(0,start+t-time.monotonic()))
  save('audio-click-'+str(t)+'.txt','dumpsys','audio')
 r=subprocess.run(ADB+['exec-out','screencap','-p'],capture_output=True,timeout=20,check=True)
 (O/'after-click.png').write_bytes(r.stdout)
 save('accessibility-after-click.txt','dumpsys','accessibility')
 time.sleep(3)
 save('audio-settled.txt','dumpsys','audio')
def readlog():
 with (O/'trace.txt').open('w') as f:
  for line in log.stdout:
   f.write(line); f.flush()
   if 'AliceWheelProbe' in line and any(k in line for k in ['KEY ','CLICK ','ARM ','CONNECTED','DISARM']): print(line.strip(),flush=True)
   if 'CLICK accepted=true' in line:
    t=threading.Thread(target=sample_click); samples.append(t); t.start()
try:
 for p in props: shell('setprop',p,'D')
 log=subprocess.Popen(ADB+['logcat','-b','main','-b','system','-v','threadtime','-T','1','WindowManager:D','MediaKeyHandler:D','AliceWheelProbe:D','AudioRecord:I','*:'+ 'S'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
 reader=threading.Thread(target=readlog); reader.start()
 r=subprocess.run(ADB+['install','-r',str(APK)],capture_output=True,timeout=45,check=True)
 (O/'install.txt').write_bytes(r.stdout+r.stderr); installed=True
 actual=apphash(PKG,'probe-sha-before.txt'); expected=hashlib.sha256(APK.read_bytes()).hexdigest()
 if actual!=expected: raise RuntimeError('Installed probe hash mismatch')
 save('installed-package.txt','dumpsys','package',PKG)
 save('wake.txt','am','start','-n',PKG+'/.WakeActivity')
 time.sleep(2)
 save('accessibility-before-enable.txt','dumpsys','accessibility')
 values=[] if services in ['', 'null'] else services.split(':')
 values.append(SERVICE)
 shell('settings','put','secure','enabled_accessibility_services',':'.join(values)); enabled=True
 for i in range(12):
  time.sleep(0.5)
  status=command('status').decode()
  save('accessibility-wait-'+str(i)+'.txt','dumpsys','accessibility')
  save('enabled-wait-'+str(i)+'.txt','settings','get','secure','enabled_accessibility_services')
  if 'result=0' in status and 'remainingMs=' in status: break
 else: raise RuntimeError('Probe service did not connect: '+status)
 save('accessibility-connected.txt','dumpsys','accessibility')
 print('CONNECTED AND DISARMED. Ready for explicit ARM command.',flush=True)
 deadline=time.monotonic()+600
 while time.monotonic()<deadline and not (O/'STOP').exists(): time.sleep(0.25)
finally:
 if enabled:
  try: (O/'disarm.txt').write_bytes(command('disarm'))
  except Exception as e: print('disarm error',str(e),flush=True)
  current=shell('settings','get','secure','enabled_accessibility_services').decode().strip()
  remaining=[s for s in current.split(':') if s not in [SERVICE,PKG+'/.VoiceKeyService']]
  shell('settings','put','secure','enabled_accessibility_services',':'.join(remaining))
 for t in samples: t.join(timeout=25)
 if installed:
  apphash(PKG,'probe-sha-after.txt')
  r=subprocess.run(ADB+['uninstall',PKG],capture_output=True,timeout=30)
  (O/'uninstall.txt').write_bytes(r.stdout+r.stderr)
 for p,v in old.items(): shell('setprop',p,v)
 if log:
  log.terminate()
  try: log.wait(timeout=5)
  except subprocess.TimeoutExpired: log.kill(); log.wait()
 if reader: reader.join(timeout=5)
 save('crash-after.txt','logcat','-b','crash','-d','-v','threadtime')
 save('accessibility-after.txt','dumpsys','accessibility')
 after=shell('settings','get','secure','enabled_accessibility_services').decode().strip()
 (O/'restored.json').write_text(json.dumps({'props':{p:shell('getprop',p).decode().strip() for p in props},'services':after},indent=2))
 for pkg in ['dev.denza.apps','ru.yandex.yandexnavi']: apphash(pkg,pkg+'-sha-after.txt')
 print('CLEANUP COMPLETE',flush=True)
