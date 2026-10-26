package com.yandex.mapkit.offline_cache;

/** Compile-only signature from pinned Navigator classes11.dex. Never package this interface. */
public interface RegionsAtPointListener {
    void onRegions(java.util.List<Integer> ids);
    void onError(com.yandex.runtime.Error error);
}
