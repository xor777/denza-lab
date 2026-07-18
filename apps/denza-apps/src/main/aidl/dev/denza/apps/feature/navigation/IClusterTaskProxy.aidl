package dev.denza.apps.feature.navigation;

import android.view.Surface;

interface IClusterTaskProxy {
    int createVirtualDisplay(String token, String name, int width, int height, int densityDpi, in Surface surface);
    void releaseVirtualDisplay(String token);
    int findAllowedTask(String token, String packageName);
    boolean moveTask(String token, int taskId, int displayId);
    boolean setTaskBounds(String token, int taskId, int left, int top, int right, int bottom);
    boolean focusTask(String token, int taskId);
    int taskDisplayId(String token, int taskId);
}
