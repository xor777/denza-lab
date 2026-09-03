package dev.denza.apps.feature.speaker

/**
 * The players the car already speaks for, copied from the firmware that decides it.
 *
 * The stock `MediaController` follows media sessions and reports playback to the cluster and the
 * amplifier. It does that faithfully for the packages in its own two lists, and for anything else
 * it does the opposite: `MediaTaskManager` marks the session a black third-party app and then sends
 * `sendMusicState(2)` - paused - with a blank title and source 26. That is why a third-party player
 * can play for hours with the covers down. The amplifier is being told, continuously, that nothing
 * is playing.
 *
 * So this app reports for exactly the packages the car does not, and stays silent where the car is
 * already telling the truth. Reporting over the stock player would be two writers on one property.
 *
 * Both lists are hard-coded in the vendor package, which makes them a copy of somebody else's
 * constant: **re-read them after a firmware update.** A package that quietly joins the vendor list
 * would be reported twice, and one that quietly leaves it would stop raising the covers.
 */
object SpeakerCoverReporting {
    /** `MediaTaskManager.mWhiteListPackageNames` - sessions the car reports normally. */
    val carReported: Set<String> = linkedSetOf(
        "com.byd.mediacenter",
        "com.byd.videoplay",
        "com.byd.videoplay.youku",
        "com.byd.videoplay.youku.fse",
        "com.byd.videoplay.fse",
        "com.byd.videoplay.hd",
        "com.tencent.qqmusiccar",
        "com.netease.cloudmusic.iot",
        "com.ximalaya.ting.android",
        "com.ximalaya.ting.android.car.byd",
        "bubei.tingshu.hd",
    )

    /** `MediaTaskManager.mFilterMediaFocusPackageNames` - focus owners it deliberately skips. */
    val carHandled: Set<String> = linkedSetOf(
        "android",
        "com.android.server.telecom",
        "com.android.bluetooth",
    )

    /**
     * Whether the car is already reporting this package's playback, so the app must not.
     *
     * A null package is nobody: media focus with no owner we can name is not something to speak
     * for, and guessing would put a report on the bus with no player behind it.
     */
    fun carSpeaksFor(packageName: String?): Boolean =
        packageName == null || packageName in carReported || packageName in carHandled
}
