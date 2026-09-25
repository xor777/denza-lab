package dev.denza.apps.feature.cloud

/** Ordered, bounded operations with readback. The shell/clock are the only device boundaries. */
internal class CloudLinkOperations(
    private val core: CloudLinkCore,
    private val read: () -> CloudCarState,
    private val shell: (String) -> String,
    private val network: () -> Boolean,
    private val now: () -> Long,
    private val pause: (Long) -> Unit,
    private val record: (String) -> Unit,
) {
    fun run(steps: List<CloudStep>, lossOnly: Boolean = false) {
        for (step in steps) {
            record("begin $step")
            when (step) {
                CloudStep.UseWifiProfile -> {
                    val before = read()
                    if (before.stockApnBusy || before.connected == true) {
                        record("skip UseWifiProfile: stock APN busy or TCP connected")
                        continue
                    }
                    check(network()) { "Интернет пропал перед подключением" }
                    profile(CloudLinkProtocol.WIFI_PROFILE) { it.wifiProfile }
                }
                is CloudStep.RestoreProfile -> {
                    check(!read().stockApnTransitioning) { "Ожидается переключение сотовой сети" }
                    profile(step.profile) { it.onStockProfile }
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
                    check(before.profile == CloudLinkProtocol.WIFI_PROFILE && !before.stockApnBusy) {
                        "Состояние сети изменилось перед отключением"
                    }
                    notify(CloudLinkProtocol.GONE)
                    core.goneSent()
                }
                CloudStep.WaitDisconnected -> {
                    var car = read()
                    repeat(3) {
                        if (car.connected == false || car.cellular) return@repeat
                        pause(1_000)
                        car = read()
                    }
                    check(car.connected == false || car.cellular) { "Ожидается отключение TCP" }
                }
            }
            record("done $step")
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
