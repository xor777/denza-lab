package dev.denza.apps.feature.simulcast;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Stable mapping between the DiShare receiver contract and its visible screen card. */
public final class ScreenTarget {
    public static final List<ScreenTarget> SUPPORTED = Collections.unmodifiableList(Arrays.asList(
            new ScreenTarget("screen_hud", "ar_hud_screen"),
            new ScreenTarget("screen_fse", "fse_screen"),
            new ScreenTarget("screen_rse_l", "left_rse_screen"),
            new ScreenTarget("screen_rse_r", "right_rse_screen"),
            new ScreenTarget("screen_overhead", "overhead_screen"),
            // Some single-rear-screen configurations expose the built-in rear
            // display as the DiShare TV receiver while keeping the same rear card.
            new ScreenTarget("screen_tv", "overhead_screen")
    ));

    public final String receiverId;
    public final String viewResourceName;

    public ScreenTarget(String receiverId, String viewResourceName) {
        this.receiverId = receiverId;
        this.viewResourceName = viewResourceName;
    }
}
