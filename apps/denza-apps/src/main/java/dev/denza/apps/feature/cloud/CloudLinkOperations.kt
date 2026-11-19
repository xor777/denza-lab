package dev.denza.apps.feature.cloud

/**
 * Ordered, bounded operations with readback. The shell/clock are the only device boundaries.
 *
 * [memory] and [remember] are the durable [CloudProfileMemory]: what the adapter owes is committed
 * before its profile write, and let go only after a read confirms the car back on it.
 */
internal class CloudLinkOperations(
    private val core: CloudLinkCore,
    private val read: () -> CloudCarState,
    private val shell: (String) -> String,
    private val network: () -> Boolean,
    private val now: () -> Long,
    private val pause: (Long) -> Unit,
    private val record: (String) -> Unit,
    private val memory: () -> CloudProfileMemory,
    private val remember: (CloudProfileMemory) -> Unit,
) {
    fun run(steps: List<CloudStep>, lossOnly: Boolean = false) {
        for (step in steps) {
            record("begin $step remembered=${memory().label}")
            when (step) {
                CloudStep.UseWifiProfile -> {
                    val before = read()
                    if (before.stockApnBusy || before.connected == true) {
                        record("skip UseWifiProfile: stock APN busy or TCP connected")
                        continue
                    }
                    check(network()) { "Интернет пропал перед подключением" }
                    // Commit what the car was on, and that the write is ours, before the write.
                    val owed = memory().beforeWrite(before)
                    check(owed is CloudProfileMemory.Owed) { "Профиль не прочитан перед записью" }
                    if (owed != memory()) remember(owed)
                    profile(CloudLinkProtocol.WIFI_PROFILE) { it.wifiProfile }
                }
                is CloudStep.RestoreProfile -> {
                    if (lossOnly && network()) {
                        record("skip RestoreProfile: network returned")
                        continue
                    }
                    profile(step.profile) { it.on(step.profile) }
                    // Read back on it: nothing is owed any more.
                    remember(CloudProfileMemory.Clear)
                }
                CloudStep.AnnounceReady -> {
                    val before = read()
                    if (before.stockApnBusy || before.connected == true) {
                        record("skip AnnounceReady: stock APN busy or TCP connected")
                        continue
                    }
                    check(network()) { "Интернет пропал перед подключением" }
                    check(before.wifiProfile) { "Профиль изменился перед подключением" }
                    check(before.connected != null) { "Нет ответа от облачного сервиса" }
                    if (!before.stockApnBusy && before.connected != true) {
                        notify(CloudLinkProtocol.READY)
                        val after = read()
                        check(after.wifiProfile) { "Профиль изменился после подключения" }
                        core.readySent(now())
                    }
                }
                CloudStep.AnnounceGone -> {
                    val before = read()
                    if (lossOnly && network()) {
                        record("skip AnnounceGone: network returned")
                        continue
                    }
                    check(before.profile == CloudLinkProtocol.WIFI_PROFILE && !before.cellular) {
                        "Состояние сети изменилось перед отключением"
                    }
                    notify(CloudLinkProtocol.GONE)
                    core.goneSent()
                }
                CloudStep.WaitDisconnected -> {
                    if (lossOnly && network()) {
                        record("skip WaitDisconnected: network returned")
                        continue
                    }
                    var car = read()
                    repeat(3) {
                        if (car.connected == false || car.cellular) return@repeat
                        pause(1_000)
                        car = read()
                    }
                    check(car.connected == false || car.cellular) { "Ожидается отключение TCP" }
                }
            }
            record("done $step remembered=${memory().label}")
        }
    }

    private fun profile(profile: String, accepted: (CloudCarState) -> Boolean) {
        check(profile in setOf(CloudLinkProtocol.WIFI_PROFILE, CloudLinkProtocol.STOCK_PROFILE))
        check(CloudLinkProtocol.profileAccepted(shell(CloudLinkProtocol.profileCommand(profile)))) {
            "Команда смены профиля отклонена"
        }
        pause(3_000)
        check(accepted(read())) { "Профиль не подтвердился после записи" }
    }

    private fun notify(value: Int) {
        check(CloudLinkProtocol.notifyAccepted(shell(CloudLinkProtocol.notifyCommand(value)))) {
            "Команда облачному сервису отклонена ($value)"
        }
    }
}
