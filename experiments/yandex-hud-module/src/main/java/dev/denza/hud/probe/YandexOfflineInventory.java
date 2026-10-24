package dev.denza.hud.probe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Read-only SDK inventory. An installed usable region does not prove coverage of the current route. */
final class YandexOfflineInventory {
    static final long POLL_INTERVAL_MS = 2000, TTL_MS = 5000;
    static final int MAX_REGIONS = 8192;
    static final class Snapshot {
        final long observedAt, generation;
        final boolean valid;
        final String fingerprint, reasonCode, reasonRussian, catalogDigest;
        final List<Map<String,Object>> regions;
        final int catalogCount, usableCount;
        Snapshot(long at, long generation, boolean valid, String fingerprint, String code, String russian,
                 String digest, List<Map<String,Object>> regions, int count, int usable) {
            observedAt = at; this.generation = generation; this.valid = valid; this.fingerprint = fingerprint;
            reasonCode = code; reasonRussian = russian; catalogDigest = digest;
            this.regions = Collections.unmodifiableList(new ArrayList<>(regions)); catalogCount = count; usableCount = usable;
        }
        boolean ready(long now) { return valid && usableCount > 0 && now >= observedAt && now - observedAt < TTL_MS; }
        Map<String,Object> status() {
            Map<String,Object> m = new LinkedHashMap<>(); m.put("schema","denza-yandex-offline-inventory-v1");
            m.put("observedAt",observedAt); m.put("generation",generation); m.put("valid",valid); m.put("fingerprint",fingerprint);
            m.put("reasonCode",reasonCode); m.put("reasonRussian",reasonRussian); m.put("catalogDigest",catalogDigest);
            m.put("catalogCount",catalogCount); m.put("usableCount",usableCount); m.put("regions",regions); m.put("ttlMs",TTL_MS);
            m.put("coverageMeaning","at least one usable installed region; route coverage not established");
            return Collections.unmodifiableMap(m);
        }
    }
    interface Access { Object manager(); Object call(Object object, String name, Object... args); }
    private final Thread owner = Thread.currentThread();
    private Snapshot last;
    Snapshot snapshot() { owner(); return last; }
    boolean ready(long now) { owner(); return last != null && last.ready(now); }
    Snapshot poll(Reflect api, long now) {
        if (api == null) throw new IllegalArgumentException("reflection bridge required");
        return poll(new Access() {
            @Override public Object manager() { return api.call(api.call(api.type("com.yandex.mapkit.MapKitFactory"),"getInstance"),"getOfflineCacheManager"); }
            @Override public Object call(Object o,String name,Object... args) { return api.call(o,name,args); }
        }, now);
    }
    Snapshot poll(Access access, long now) {
        owner(); if (access == null) throw new IllegalArgumentException("read access required");
        if (now < 0 || (last != null && now < last.observedAt)) return finish(now,false,"clock_invalid",Collections.emptyList(),"",0,0);
        if (last != null && now - last.observedAt < POLL_INTERVAL_MS) return last;
        List<Map<String,Object>> nonAvailable = new ArrayList<>();
        TreeMap<Integer,Map<String,Object>> canonical = new TreeMap<>(); int count = 0, usable = 0; boolean outdated = false;
        String blocking = null;
        try {
            Object manager = access.manager(); if (manager == null) throw new Invalid("manager_missing");
            Object raw = access.call(manager,"regions"); if (!(raw instanceof List)) throw new Invalid("catalog_invalid");
            List<?> catalog = (List<?>)raw; count = catalog.size();
            if (count < 0) throw new Invalid("catalog_invalid");
            if (count > MAX_REGIONS) throw new Invalid("catalog_truncated");
            for (int index = 0; index < count; index++) {
                Object region = catalog.get(index); if (region == null) throw new Invalid("region_invalid");
                long idLong = integer(access.call(region,"getId"),"region_invalid");
                if (idLong < 0 || idLong > Integer.MAX_VALUE) throw new Invalid("region_invalid"); int id = (int)idLong;
                if (canonical.containsKey(id)) throw new Invalid("duplicate_region");
                Object stateObject = access.call(manager,"getState",id);
                if (!(stateObject instanceof Enum<?>)) throw new Invalid("state_invalid");
                String state = ((Enum<?>)stateObject).name();
                if (!Arrays.asList("AVAILABLE","COMPLETED","OUTDATED","DOWNLOADING","PAUSED","NEED_UPDATE","UNSUPPORTED").contains(state)) throw new Invalid("state_unknown");
                Object releaseObject = access.call(region,"getReleaseTime"); long release = integer(releaseObject,"release_invalid");
                if (release < 0) throw new Invalid("release_invalid");
                Object downloadedObject = access.call(manager,"getDownloadedReleaseTime",id);
                Long downloaded = downloadedObject == null ? null : integer(downloadedObject,"release_invalid");
                if (downloaded != null && downloaded < 0) throw new Invalid("release_invalid");
                Object progressObject = access.call(manager,"getProgress",id);
                if (!(progressObject instanceof Float) && !(progressObject instanceof Double)) throw new Invalid("progress_invalid");
                double progress = ((Number)progressObject).doubleValue();
                if (!Double.isFinite(progress) || progress < 0 || progress > 1) throw new Invalid("progress_invalid");
                boolean completeState = state.equals("COMPLETED") || state.equals("OUTDATED");
                boolean downloadedPresent = downloaded != null && downloaded > 0;
                boolean ready = completeState && downloadedPresent && progress == 1;
                String regionReason = ready ? state.equals("OUTDATED") ? "usable_outdated" : "usable" : "not_installed";
                if (completeState && !downloadedPresent) regionReason = "downloaded_release_missing";
                else if (completeState && progress != 1) regionReason = "installed_progress_incomplete";
                else if (!ready && downloadedPresent) regionReason = state.equals("NEED_UPDATE") ? "installed_legacy_update_required" :
                        state.equals("UNSUPPORTED") ? "installed_unsupported" : "installed_state_not_usable";
                // Preserve each unusable region's reason. Admission asks only whether any region is usable.
                if (!ready && (completeState || downloadedPresent) && blocking == null) blocking = regionReason;
                if (ready) { usable++; outdated |= state.equals("OUTDATED"); }
                // Package layout is needed only by the regional attribute reader. An unsupported
                // layout query must not strengthen the separate installed-map prerequisite.
                Boolean legacyPath=null;
                if(ready)try { Object legacy=access.call(manager,"isLegacyPath",id);if(legacy instanceof Boolean)legacyPath=(Boolean)legacy; }
                catch(RuntimeException unsupportedLayout) { /* Reader reports layout unavailable. */ }
                Map<String,Object> row = new LinkedHashMap<>(); row.put("id",id); row.put("state",state);
                row.put("downloadedReleaseTime",downloaded); row.put("currentReleaseTime",release); row.put("progress",progress);
                row.put("usable",ready); row.put("reasonCode",regionReason); row.put("isLegacyPath",legacyPath);
                canonical.put(id,Collections.unmodifiableMap(row));
            }
            if (catalog.size() != count) throw new Invalid("catalog_changed");
            StringBuilder stable = new StringBuilder();
            for (Map<String,Object> row : canonical.values()) {
                stable.append(row.get("id")).append('|').append(row.get("state")).append('|').append(row.get("downloadedReleaseTime"))
                        .append('|').append(row.get("currentReleaseTime")).append('|').append(row.get("progress"))
                        .append('|').append(row.get("isLegacyPath")).append('\n');
                if (!row.get("state").equals("AVAILABLE") || row.get("downloadedReleaseTime") != null && ((Long)row.get("downloadedReleaseTime")) > 0)
                    nonAvailable.add(row);
            }
            String digest = hash(stable.toString());
            if (usable == 0) return finish(now,false,blocking == null ? "no_usable_regions" : blocking,nonAvailable,digest,count,0);
            return finish(now,true,outdated ? "ready_outdated" : "ready",nonAvailable,digest,count,usable);
        } catch (Invalid e) {
            return finish(now,false,e.code,Collections.emptyList(),"",count,0);
        } catch (RuntimeException e) {
            String message = e.getMessage(); String code = message != null && message.startsWith("Missing method") ? "api_unsupported" : "sdk_read_error";
            return finish(now,false,code,Collections.emptyList(),"",count,0);
        }
    }
    Map<String,Object> status() {
        owner();
        return last == null ? new Snapshot(0,0,false,hash("not_polled"),"not_polled",russian("not_polled"),"",Collections.emptyList(),0,0).status() : last.status();
    }
    private Snapshot finish(long now,boolean valid,String code,List<Map<String,Object>> regions,String digest,int count,int usable) {
        String fingerprint = hash(valid + "|" + code + "|" + digest + "|" + count + "|" + usable);
        long generation = last == null ? 1 : last.generation + (last.fingerprint.equals(fingerprint) ? 0 : 1);
        return last = new Snapshot(now,generation,valid,fingerprint,code,russian(code),digest,regions,count,usable);
    }
    private void owner() { if (Thread.currentThread() != owner) throw new IllegalStateException("offline inventory owner thread required"); }
    private static long integer(Object o,String code) { if (!(o instanceof Integer) && !(o instanceof Long)) throw new Invalid(code); return ((Number)o).longValue(); }
    private static final class Invalid extends RuntimeException { final String code; Invalid(String code) { this.code = code; } }
    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder s = new StringBuilder(64);
            for(byte b:bytes){s.append(Character.forDigit((b>>>4)&15,16));s.append(Character.forDigit(b&15,16));}return s.toString();
        } catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static String russian(String code) {
        switch(code) {
            case "ready": return "Офлайн-карты доступны; покрытие маршрута не проверено";
            case "ready_outdated": return "Офлайн-карта устарела, но доступна; покрытие маршрута не проверено";
            case "no_usable_regions": return "Нет пригодных загруженных офлайн-карт Яндекса";
            case "installed_legacy_update_required": return "Установленная офлайн-карта требует обновления формата";
            case "installed_unsupported": return "Установленная офлайн-карта не поддерживается";
            case "installed_state_not_usable": return "Состояние установленной офлайн-карты пока не позволяет её использовать";
            case "downloaded_release_missing": return "Нет подтверждённого выпуска загруженной офлайн-карты";
            case "installed_progress_incomplete": return "Установленная офлайн-карта загружена не полностью";
            case "catalog_truncated": return "Каталог офлайн-карт превышает допустимый размер";
            case "state_unknown": case "state_invalid": return "Неизвестное состояние офлайн-карт Яндекса";
            case "api_unsupported": return "Эта версия Яндекса не предоставляет ожидаемый интерфейс офлайн-карт";
            case "clock_invalid": return "Не подтверждена свежесть списка офлайн-карт";
            case "not_polled": return "Список офлайн-карт ещё не проверен";
            default: return "Не удалось достоверно прочитать список офлайн-карт Яндекса";
        }
    }
}
