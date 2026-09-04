package dev.denza.apps.feature.mirrors;

/** One startup's monotonic timestamps, diagnostic only; never authorizes camera transitions. */
final class AvcStartupTiming {
    final long startedAtMs;
    long boundAtMs = -1;
    long surfaceAtMs = -1;
    long readyAtMs = -1;
    long getNameMs;
    long bufferTypeMs;
    long initDisplayMs;
    long viewpointMs;

    AvcStartupTiming(long startedAtMs) {
        this.startedAtMs = startedAtMs;
    }

    String firstFrameDetails(long nowMs) {
        return "renderer_start_ms=" + startedAtMs
                + " first_update_ms=" + nowMs
                + " renderer_to_frame_ms=" + (nowMs - startedAtMs)
                + " bind_ready_ms=" + (boundAtMs - startedAtMs)
                + " surface_ready_ms=" + (surfaceAtMs - startedAtMs)
                + " get_name_ms=" + getNameMs
                + " buffer_type_ms=" + bufferTypeMs
                + " init_display_ms=" + initDisplayMs
                + " viewpoint_ms=" + viewpointMs
                + " ready_to_frame_ms=" + (nowMs - readyAtMs);
    }
}
