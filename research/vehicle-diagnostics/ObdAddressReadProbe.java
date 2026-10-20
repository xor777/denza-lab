package dev.denza.diagnostics.probe;
import dev.denza.apps.platform.shell.ShellProxyBootstrap;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Research only, approved by the owner on 2026-10-20: which modules answer the qualified
 * device-1033 envelope beyond 0x720 and 0x762. Run 1 (no argument): fixed addresses, OBD
 * 01 00 and 03, UDS 19 01 FF and 19 02 FF; 03 and 19 02 only where 01 00 or 19 01 answered.
 * Run 2 ("detail"): the responders run 1 found, read further - single OBD PIDs, 07, 0A,
 * UDS 19 with masks 08/04, identification DIDs F197/F18A/F195. Run 3 ("ident"): F187 and
 * F191 from 7E2, 7E3, 7E5, 7E6 and 7E7. Run 4 ("ident2"): F194 from 7E0 and those five.
 * allowed() is the whitelist.
 * No sessions, clears, unlocks, writes, routines or 7DF.
 * One request at a time; a step ends on its last frame, a non-success result or 4 s.
 */
public final class ObdAddressReadProbe {
  static final int RX = 0x9900010A, TX = 0xAA00010A;
  static final int[] OBD = {0x7E0,0x7E1,0x7E2,0x7E3,0x7E4,0x7E5,0x7E6,0x7E7};
  static final int[] BODY = {0x747,0x726,0x722};
  static final byte[] PIDS = {0x01,0x00}, MODE3 = {0x03};
  static final byte[] COUNT = {0x19,0x01,(byte)0xFF}, LIST = {0x19,0x02,(byte)0xFF};
  static Object device, listener;
  static Class<?> listenerClass, valueClass, deviceClass;
  static RandomAccessFile lockFile;
  static FileLock lock;
  static Handler main;
  static final AtomicBoolean done = new AtomicBoolean();
  static final Deque<Object[]> plan = new ArrayDeque<>();
  static final Set<String> seen = new HashSet<>();
  static int frames, stepId = -1, stepReq, stepFrames, stepTotal;
  static byte[] stepPayload;
  static boolean stepPositive, stepOpen;
  static Runnable stepTimeout;

  static void out(String s) { System.out.println(s); System.out.flush(); }
  static String hex(byte[] b, int from, int to) {
    StringBuilder s = new StringBuilder();
    for (int i = from; i < Math.min(to, b.length); i++) s.append(String.format("%02X", b[i] & 255));
    return s.toString();
  }
  static String prop() throws Exception {
    return (String)Class.forName("android.os.SystemProperties")
      .getMethod("get",String.class).invoke(null,"sys.diagmgrsrv.task_bk");
  }
  static void finish(String reason) {
    if (!done.compareAndSet(false,true)) return;
    out("END " + reason + " frames=" + frames);
    try {
      if (device != null && listener != null) {
        deviceClass.getMethod("unregisterListener",listenerClass).invoke(device,listener);
        out("UNREGISTERED");
      }
    } catch(Throwable e) { out("UNREGISTER_ERROR " + e); }
    try { if (lock != null) lock.release(); if (lockFile != null) lockFile.close(); } catch(Exception ignored) {}
    System.exit(0);
  }
  /** Read-only services only: OBD 01 (one PID), 03, 07, 0A; UDS 19 01/02 with mask FF/08/04;
   *  22 F197/F18A/F195/F187 (part number)/F191 (hardware number)/F194 (supplier software number). */
  static boolean allowed(byte[] p) {
    int sid = p[0] & 255;
    if (sid == 0x01) return p.length == 2;
    if (sid == 0x03 || sid == 0x07 || sid == 0x0A) return p.length == 1;
    if (sid == 0x19) return p.length == 3 && (p[1] == 1 || p[1] == 2)
        && ((p[2] & 255) == 0xFF || p[2] == 0x08 || p[2] == 0x04);
    if (sid == 0x22) return p.length == 3 && (p[1] & 255) == 0xF1
        && ((p[2] & 255) == 0x97 || (p[2] & 255) == 0x8A || (p[2] & 255) == 0x95
            || (p[2] & 255) == 0x87 || (p[2] & 255) == 0x91 || (p[2] & 255) == 0x94);
    return false;
  }
  static byte[] b(int... v) { byte[] r = new byte[v.length]; for (int i = 0; i < v.length; i++) r[i] = (byte)v[i]; return r; }
  static boolean detail, ident, ident2;
  /** Run 4 ("ident2"): F194, which BYD's own OTA reads beside F195; 7E0 is the control. */
  static void ident2Plan() {
    for (int id : new int[]{0x7E0,0x7E2,0x7E3,0x7E5,0x7E6,0x7E7}) plan.addLast(new Object[]{id, b(0x22,0xF1,0x94)});
  }
  /** Run 3 ("ident"): part and hardware numbers of the unnamed powertrain modules. */
  static void identPlan() {
    for (int id : new int[]{0x7E2,0x7E3,0x7E5,0x7E6,0x7E7}) for (int did : new int[]{0x87,0x91})
      plan.addLast(new Object[]{id, b(0x22,0xF1,did)});
  }
  /** Run 2: the modules run 1 found, read further. Fixed; no follow-ups. */
  static void detailPlan() {
    int[][] pids = {{0x7E0, 0x01,0x03,0x04,0x05,0x06,0x07,0x0B,0x0C,0x0D,0x0E,0x0F,0x11,0x13,0x15,0x1C,0x1F,0x20,0x40,0x60},
                    {0x7E2, 0x01,0x05,0x0C,0x0D,0x20,0x40}, {0x7E5, 0x01,0x04,0x05,0x0C,0x0D,0x20,0x40}};
    for (int[] row : pids) {
      for (int i = 1; i < row.length; i++) plan.addLast(new Object[]{row[0], b(0x01,row[i])});
      plan.addLast(new Object[]{row[0], b(0x07)});
      plan.addLast(new Object[]{row[0], b(0x0A)});
    }
    for (int id : new int[]{0x7E0,0x7E2}) for (int sub : new int[]{1,2}) for (int mask : new int[]{0x08,0x04})
      plan.addLast(new Object[]{id, b(0x19,sub,mask)});
    for (int id : new int[]{0x7E0,0x7E2,0x7E5,0x7E3,0x7E6,0x7E7}) for (int did : new int[]{0x97,0x8A,0x95})
      plan.addLast(new Object[]{id, b(0x22,0xF1,did)});
    for (int id : new int[]{0x720,0x722,0x726,0x747,0x762}) plan.addLast(new Object[]{id, b(0x22,0xF1,0x97)});
  }

  static void received(byte[] b) {
    if (b == null) { finish("NULL_FRAME"); return; }
    if (++frames > 400 || b.length > 1024) { finish("BOUND_EXCEEDED"); return; }
    String raw = hex(b,0,b.length);
    if (!seen.add(raw)) return; // the listener delivers each packet twice
    if (stepId < 0) { out("FRAME passive raw=" + raw); finish("PREEXISTING_DIAGNOSTIC_TRAFFIC"); return; }
    if (!stepOpen) { out("FRAME late step=" + stepId + " raw=" + hex(b,0,Math.min(b.length,80))); return; }
    int id = b.length >= 3 ? ((b[1] & 255) << 8) | (b[2] & 255) : -1;
    if (b.length < 7 || (b[0] & 255) != 0x0A || id != stepReq + 8) {
      out("FRAME other step=" + stepId + " raw=" + hex(b,0,Math.min(b.length,80)));
      return;
    }
    int len = b[3] & 255, total = b[4] & 255, index = b[5] & 255;
    out("FRAME step=" + stepId + " id=" + Integer.toHexString(id) + " total=" + total + " index=" + index
        + " raw=" + hex(b,0,Math.min(b.length, (index == 1 ? 7 : 6) + len)));
    stepFrames++;
    if (index == 1) {
      stepTotal = total;
      int result = b[6] & 255;
      if (result == 1 && len >= 1) {
        int sid = b[7] & 255;
        stepPositive = sid == ((stepPayload[0] & 255) + 0x40);
        // A positive count with zero records needs no list read.
        if (stepPositive && Arrays.equals(stepPayload,COUNT) && len >= 6 && (b[11] & 255) == 0 && (b[12] & 255) == 0)
          stepPositive = false;
      }
      if (result != 1) { endStep("RESULT_" + result); return; }
    }
    if (stepTotal > 0 && index >= stepTotal) endStep("COMPLETE");
  }

  static void endStep(String why) {
    if (!stepOpen) return; // one end per step: a late frame never schedules a second request
    stepOpen = false;
    if (stepTimeout != null) main.removeCallbacks(stepTimeout);
    out("STEP_END step=" + stepId + " req=" + Integer.toHexString(stepReq) + " why=" + why
        + " frames=" + stepFrames + " positive=" + stepPositive);
    if (!detail && stepPositive && Arrays.equals(stepPayload,PIDS)) plan.addLast(new Object[]{stepReq, MODE3});
    if (!detail && stepPositive && Arrays.equals(stepPayload,COUNT)) plan.addLast(new Object[]{stepReq, LIST});
    main.postDelayed(ObdAddressReadProbe::next, 300);
  }

  static void next() {
    if (done.get()) return;
    Object[] step = plan.pollFirst();
    if (step == null) { finish("PLAN_DONE"); return; }
    try {
      if (!"r@-1_0&w@-1_0".equals(prop())) { finish("MANAGER_STATE_CHANGED_NO_REQUEST"); return; }
      stepId++;
      stepReq = (Integer)step[0];
      stepPayload = (byte[])step[1];
      stepFrames = 0; stepTotal = 0; stepPositive = false;
      if (!allowed(stepPayload)) { finish("PAYLOAD_NOT_ALLOWED"); return; }
      byte[] p = new byte[4 + stepPayload.length];
      p[0] = 0x0A; p[1] = (byte)(stepReq >> 8); p[2] = (byte)stepReq; p[3] = (byte)stepPayload.length;
      System.arraycopy(stepPayload,0,p,4,stepPayload.length);
      Object value = valueClass.getConstructor().newInstance();
      valueClass.getField("bufferDataValue").set(value,p);
      stepOpen = true;
      out("REQUEST step=" + stepId + " " + hex(p,0,p.length));
      Object rc = deviceClass.getMethod("set",int[].class,valueClass).invoke(device,new int[]{TX},value);
      out("TRANSPORT_RESULT step=" + stepId + " " + rc);
      if (!Integer.valueOf(0).equals(rc)) { endStep("TRANSPORT_" + rc); return; }
      stepTimeout = () -> endStep("TIMEOUT_4S");
      main.postDelayed(stepTimeout, 4000);
    } catch(Throwable e) { out("SEND_EXCEPTION " + e); if (e.getCause()!=null) out("CAUSE " + e.getCause()); finish("NO_RETRY"); }
  }

  public static void main(String[] args) {
    detail = args.length == 1 && args[0].equals("detail");
    ident = args.length == 1 && args[0].equals("ident");
    ident2 = args.length == 1 && args[0].equals("ident2");
    if (args.length != 0 && !detail && !ident && !ident2) throw new IllegalArgumentException("No arguments, detail, ident or ident2: every plan is fixed");
    if (ident2) { detail = true; ident2Plan(); }
    else if (ident) { detail = true; identPlan(); }
    else if (detail) detailPlan();
    else {
      for (int id : OBD) { plan.addLast(new Object[]{id, PIDS}); plan.addLast(new Object[]{id, COUNT}); }
      for (int id : BODY) plan.addLast(new Object[]{id, COUNT});
    }
    new Thread(() -> {
      try { Thread.sleep(detail ? 330000 : 150000); } catch(Exception ignored) {}
      out("HARD_TIMEOUT"); System.exit(3);
    },"obd-watchdog").start();
    out("START uid=" + Process.myUid() + " steps=" + plan.size());
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
      deviceClass = Class.forName("android.hardware.bydauto.mqtt.BYDAutoMqttDevice");
      listenerClass = Class.forName("android.hardware.IBYDAutoListener");
      Class<?> ec = Class.forName("android.hardware.IBYDAutoEvent");
      valueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
      device = deviceClass.getMethod("getInstance",Context.class).invoke(null,context);
      listener = Proxy.newProxyInstance(ObdAddressReadProbe.class.getClassLoader(),new Class<?>[]{listenerClass},(p,m,a)->{
        try {
          if (m.getName().equals("onDataEventChanged") && ((Integer)a[0]) == RX)
            received((byte[])valueClass.getField("bufferDataValue").get(a[1]));
          else if (m.getName().equals("onDataChanged") && ((Integer)ec.getMethod("getEventType").invoke(a[0])) == RX)
            received((byte[])ec.getMethod("getBufferData").invoke(a[0]));
          else if (m.getName().equals("onError")) { finish("CALLBACK_ERROR "+a[0]+" "+a[1]); }
          else if (m.getName().equals("hashCode")) return System.identityHashCode(p);
          else if (m.getName().equals("equals")) return p == a[0];
          else if (m.getName().equals("toString")) return "ObdAddressReadProbeListener";
        } catch(Throwable e) { finish("CALLBACK_EXCEPTION "+e); }
        return null;
      });
      deviceClass.getMethod("registerListener",listenerClass,int[].class).invoke(device,listener,new int[]{RX});
      out("REGISTERED passive_ms=2000");
      main.postDelayed(ObdAddressReadProbe::next, 2000);
      Looper.loop();
    } catch(Throwable e) { out("FAIL "+e); if(e.getCause()!=null) out("CAUSE "+e.getCause()); finish("NO_RETRY"); }
  }
}
