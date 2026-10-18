package dev.denza.apps.feature.diagnostics;
import dev.denza.apps.platform.shell.ShellProxyBootstrap;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Promoted from the 2026-10-18 shell-UID trace, with a process lock added.
 * Allowlisted ECU 0x720 or 0x762, only 19 01 FF or 19 02 FF. No sessions, clears or unlocks.
 * The app checks profile170, P and zero speed before each launch. A bounded
 * process never retries; its listener is removed on completion and binder death.
 */
public final class VehicleDtcProxyMain {
  static final int RX = 0x9900010A, TX = 0xAA00010A;
  static Object device, listener;
  static RandomAccessFile lockFile;
  static FileLock lock;
  static Class<?> listenerClass;
  static final AtomicBoolean done = new AtomicBoolean();
  static final AtomicInteger frames = new AtomicInteger();
  static volatile boolean sent;
  static Handler main;
  static String prop() throws Exception {
    return (String)Class.forName("android.os.SystemProperties")
      .getMethod("get",String.class).invoke(null,"sys.diagmgrsrv.task_bk");
  }
  static void out(String s) { System.out.println(s); System.out.flush(); }
  static void finish(String reason) {
    if (!done.compareAndSet(false,true)) return;
    out("END " + reason + " frames=" + frames.get());
    try {
      if (device != null && listener != null) {
        device.getClass().getMethod("unregisterListener",listenerClass).invoke(device,listener);
        out("UNREGISTERED");
      }
    } catch(Throwable e) { out("UNREGISTER_ERROR " + e); }
    try { if (lock != null) lock.release(); if (lockFile != null) lockFile.close(); } catch(Exception ignored) {}
    System.exit(0);
  }
  static void received(byte[] b) {
    if (b == null) { finish("NULL_FRAME"); return; }
    int n = frames.incrementAndGet();
    if (n > 128 || b.length > 1024) { finish("BOUND_EXCEEDED"); return; }
    StringBuilder s = new StringBuilder();
    for(byte v:b) s.append(String.format("%02X", v & 255));
    out("FRAME phase="+(sent?"response":"passive")+" raw="+s);
    if (!sent) { finish("PREEXISTING_DIAGNOSTIC_TRAFFIC_NO_REQUEST"); }
  }
  public static void main(String[] args) {
    if (args.length != 2 || !(args[0].equals("count") || args[0].equals("list"))
        || !(args[1].equals("body") || args[1].equals("lbms")))
      throw new IllegalArgumentException("Only count/list and body/lbms");
    final byte address = (byte)(args[1].equals("body") ? 0x20 : 0x62);
    final byte sub = (byte)(args[0].equals("count") ? 1 : 2);
    new Thread(() -> {
      try { Thread.sleep(18000); } catch(Exception ignored) {}
      out("HARD_TIMEOUT"); System.exit(3);
    },"dtc-watchdog").start();
    out("START uid="+Process.myUid()+" mode="+args[0]);
    try {
      if (Process.myUid() != 2000) { finish("WRONG_UID"); return; }
      lockFile = new RandomAccessFile("/data/local/tmp/denza-dtc-read.lock", "rw");
      lock = lockFile.getChannel().tryLock();
      if (lock == null) { finish("BUSY_NO_REQUEST"); return; }
      ShellProxyBootstrap.exemptHiddenApis();
      if (!"r@-1_0&w@-1_0".equals(prop())) { finish("MANAGER_STATE_NOT_BASELINE"); return; }
      ShellProxyBootstrap.prepareMainLooper();
      Context context = ShellProxyBootstrap.systemContext();
      main = new Handler(Looper.getMainLooper());
      Class<?> dc = Class.forName("android.hardware.bydauto.mqtt.BYDAutoMqttDevice");
      listenerClass = Class.forName("android.hardware.IBYDAutoListener");
      Class<?> ec = Class.forName("android.hardware.IBYDAutoEvent");
      Class<?> vc = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
      device = dc.getMethod("getInstance",Context.class).invoke(null,context);
      listener = Proxy.newProxyInstance(VehicleDtcProxyMain.class.getClassLoader(),new Class<?>[]{listenerClass},(p,m,a)->{
        try {
          if (m.getName().equals("onDataEventChanged") && ((Integer)a[0]) == RX)
            received((byte[])vc.getField("bufferDataValue").get(a[1]));
          else if (m.getName().equals("onDataChanged") && ((Integer)ec.getMethod("getEventType").invoke(a[0])) == RX)
            received((byte[])ec.getMethod("getBufferData").invoke(a[0]));
          else if (m.getName().equals("onError")) { finish("CALLBACK_ERROR "+a[0]+" "+a[1]); }
          else if (m.getName().equals("hashCode")) return System.identityHashCode(p);
          else if (m.getName().equals("equals")) return p == a[0];
          else if (m.getName().equals("toString")) return "VehicleDtcProxyMainListener";
        } catch(Throwable e) { finish("CALLBACK_EXCEPTION "+e); }
        return null;
      });
      dc.getMethod("registerListener",listenerClass,int[].class).invoke(device,listener,new int[]{RX});
      out("REGISTERED passive_ms=2000");
      main.postDelayed(()->{
        try {
          if (done.get() || frames.get()!=0 || !"r@-1_0&w@-1_0".equals(prop())) {
            finish("PREFLIGHT_CHANGED_NO_REQUEST"); return;
          }
          Object value = vc.getConstructor().newInstance();
          byte[] payload = new byte[]{0x0A,0x07,address,0x03,0x19,sub,(byte)0xFF};
          vc.getField("bufferDataValue").set(value,payload);
          sent=true;
          out("REQUEST 0A07"+String.format("%02X",address)+"0319"+(sub==1?"01":"02")+"FF");
          Object rc = dc.getMethod("set",int[].class,vc).invoke(device,new int[]{TX},value);
          out("TRANSPORT_RESULT "+rc);
          if (!Integer.valueOf(0).equals(rc)) finish("TRANSPORT_REJECTED");
        } catch(Throwable e) { out("SEND_EXCEPTION "+e); if(e.getCause()!=null) out("CAUSE "+e.getCause()); finish("NO_RETRY"); }
      },2000);
      main.postDelayed(()->finish("WINDOW_ENDED"),12000);
      Looper.loop();
    } catch(Throwable e) { out("FAIL "+e); if(e.getCause()!=null) out("CAUSE "+e.getCause()); finish("NO_RETRY"); }
  }
}
