package dev.denza.adasstatus.probe;

import java.util.Map;

/** Last observations are retained; silence never becomes a negative availability. */
final class StatusModel {
    boolean connected, halKnown, halAvailable, demo, demoDisconnected;
    Integer dnp, icc, acc, noaMode;
    Long selectedPath, selectionCode, alarmReason, alarmStage;
    long pilotAt = -1, modeAt = -1, selectedAt = -1;
    String transport = "not bound";
    boolean pilotForConnection;
    StatusModel copy() {
        StatusModel out = new StatusModel();
        out.connected=connected;out.halKnown=halKnown;out.halAvailable=halAvailable;out.demo=demo;out.demoDisconnected=demoDisconnected;
        out.dnp=dnp;out.icc=icc;out.acc=acc;out.noaMode=noaMode;out.selectedPath=selectedPath;out.selectionCode=selectionCode;
        out.alarmReason=alarmReason;out.alarmStage=alarmStage;out.pilotAt=pilotAt;out.modeAt=modeAt;out.selectedAt=selectedAt;
        out.transport=transport;out.pilotForConnection=pilotForConnection;return out;
    }

    void control(String event, String detail) {
        transport = event + " " + detail;
        if (event.equals("binder_connected")) { connected = true; pilotForConnection = false; }
        if (event.equals("binder_disconnected") || event.equals("null_binding") || event.equals("bind_failed") || event.equals("client_setup_error") || event.equals("closed")) { connected = false; pilotForConnection = false; }
        if (event.equals("hal_available")) { halKnown = true; halAvailable = detail.equals("true"); if (!halAvailable) pilotForConnection = false; }
    }
    void apply(Map<String, Object> event, long now, boolean fixture) {
        demo = fixture;
        if ("pilotStatus".equals(event.get("event"))) {
            dnp = ((Number) event.get("dnp")).intValue(); icc = ((Number) event.get("icc")).intValue();
            acc = ((Number) event.get("acc")).intValue(); pilotAt = now; pilotForConnection = true;
        } else if ("noaMode".equals(event.get("event"))) {
            noaMode = ((Number) event.get("noaMode")).intValue(); modeAt = now;
        } else if ("pathSelected".equals(event.get("event"))) {
            selectedPath = ((Number) event.get("pathIDResp")).longValue();
            selectionCode = ((Number) event.get("statusCode")).longValue(); selectedAt = now;
        } else if ("alarm".equals(event.get("event"))) {
            alarmReason = ((Number) event.get("pilotAlarmReason")).longValue();
            alarmStage = ((Number) event.get("alarmStage")).longValue();
        }
    }
    String freshness(long now) {
        if (pilotAt < 0 || (demo ? demoDisconnected : !connected || !pilotForConnection || halKnown && !halAvailable)) return "UNKNOWN";
        return now-pilotAt <= 5000 ? "RECENT OBSERVATION" : "UNKNOWN (heartbeat contract unknown)";
    }
    String render(long now, long dropped) {
        return compact(now) + "\nDNP, последний: " + ru(dnp) + " (DNP " + dnp + ")"
                + "\nACC, последний: " + ru(acc) + " (" + acc + ")"
                + "\nICC, последний: " + ru(icc) + " (" + icc + ")"
                + "\nID последнего ACK: " + selectedPath + " / код " + selectionCode
                + "\nСигнал/предупреждение: " + (alarmReason == null ? "неизвестно" : alarmReason + " / стадия " + alarmStage)
                + "\nBinder: " + connected + " · HAL: " + (halKnown ? halAvailable : "UNKNOWN")
                + "\nПереполнение очереди: " + dropped
                + "\n" + transport;
    }
    String compact(long now) {
        boolean fresh = freshness(now).startsWith("RECENT");
        return (demo ? "Эмулятор\n" : "")
                + "NOA: " + (fresh ? ru(dnp) : "нет данных")
                + "\nICC: " + (fresh ? ru(icc) : "нет данных")
                + "\nРежим: " + (noaMode == null ? "нет данных" : noaMode == 1 ? "трассовый" : noaMode == 2 ? "городской" : "нет данных")
                + "\nМаршрут: " + (selectionCode == null ? "нет ответа" : selectionCode == 200 ? "подтверждён" : "код " + selectionCode)
                + "\nОтвет: " + (pilotAt < 0 ? "не получен" : Math.max(0,(now-pilotAt)/1000) + " с назад");
    }
    private static String ru(Integer value) {
        if (value == null) return "неизвестно";
        if (value == 0) return "неактивен";
        if (value == 1) return "активен";
        if (value == 2) return "доступен";
        return "неизвестно";
    }
}
