package dev.denza.tools;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed OEM 0x720 DTC read only. No address or arbitrary payload arguments. */
public final class DtcReadProbe {
  static final int RX = 0x9900010A, TX = 0xAA00010A;
  static Object device, listener;
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
    if (args.length != 1 || !(args[0].equals("count") || args[0].equals("list")))
      throw new IllegalArgumentException("Only count or list; fixed ECU 0x720");
    final byte sub = (byte)(args[0].equals("count") ? 1 : 2);
    new Thread(() -> {
      try { Thread.sleep(18000); } catch(Exception ignored) {}
      out("HARD_TIMEOUT"); System.exit(3);
    },"dtc-watchdog").start();
    out("START uid="+Process.myUid()+" mode="+args[0]);
    try {
      Class<?> vm = Class.forName("dalvik.system.VMRuntime");
      vm.getMethod("setHiddenApiExemptions",String[].class).invoke(
        vm.getMethod("getRuntime").invoke(null),(Object)new String[]{"L"});
      if (!"r@-1_0&w@-1_0".equals(prop())) { finish("MANAGER_STATE_NOT_BASELINE"); return; }
      Looper.prepareMainLooper();
      Class<?> at = Class.forName("android.app.ActivityThread");
      Context context = (Context)at.getMethod("getSystemContext").invoke(at.getMethod("systemMain").invoke(null));
      main = new Handler(Looper.getMainLooper());
      Class<?> dc = Class.forName("android.hardware.bydauto.mqtt.BYDAutoMqttDevice");
      listenerClass = Class.forName("android.hardware.IBYDAutoListener");
      Class<?> ec = Class.forName("android.hardware.IBYDAutoEvent");
      Class<?> vc = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
      device = dc.getMethod("getInstance",Context.class).invoke(null,context);
      listener = Proxy.newProxyInstance(DtcReadProbe.class.getClassLoader(),new Class<?>[]{listenerClass},(p,m,a)->{
        try {
          if (m.getName().equals("onDataEventChanged") && ((Integer)a[0]) == RX)
            received((byte[])vc.getField("bufferDataValue").get(a[1]));
          else if (m.getName().equals("onDataChanged") && ((Integer)ec.getMethod("getEventType").invoke(a[0])) == RX)
            received((byte[])ec.getMethod("getBufferData").invoke(a[0]));
          else if (m.getName().equals("onError")) { finish("CALLBACK_ERROR "+a[0]+" "+a[1]); }
          else if (m.getName().equals("hashCode")) return System.identityHashCode(p);
          else if (m.getName().equals("equals")) return p == a[0];
          else if (m.getName().equals("toString")) return "DtcReadProbeListener";
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
          byte[] payload = new byte[]{0x0A,0x07,0x20,0x03,0x19,sub,(byte)0xFF};
          vc.getField("bufferDataValue").set(value,payload);
          sent=true;
          out("REQUEST 0A07200319"+(sub==1?"01":"02")+"FF");
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
