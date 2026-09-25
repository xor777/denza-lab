package dev.denza.apps.feature.cloud

import android.content.Context
import android.os.Environment
import dev.denza.apps.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** Serializes installation identity and publication with cloud configuration writes. */
internal object CloudCustomInstallation {
    private val random = SecureRandom()
    private val publication = CloudMarkerPublication(CloudLinkSettings)
    private const val INSTALL_ID = "custom_install_id"
    private const val GENERATION = "custom_config_generation"
    private const val STAMP = "custom_config_stamp"

    data class Snapshot(val marker: CloudInstallMarker, val file: File)

    fun generation(context: Context): Long = context.getSharedPreferences("cloud_link", Context.MODE_PRIVATE)
        .getLong(GENERATION, 0)

    private data class Config(val marker: CloudInstallMarker, val stamp: String)

    private fun desired(app: Context): Boolean = BuildConfig.CLOUD_NATIVE_PILOT &&
        CloudLinkSettings.isEnabled(app) && !CloudLinkSettings.pendingDisable(app) &&
        CloudLinkSettings.mode(app) == CloudSimMode.CUSTOM &&
        CloudLinkSettings.customIdentity(app)?.valid() == true

    private fun stamp(app: Context): String {
        val identity = CloudLinkSettings.customIdentity(app)
        val input = listOf(CloudLinkSettings.mode(app)?.name ?: "none", desired(app).toString(),
            identity?.iccid ?: "", identity?.imsi ?: "").joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun publish(context: Context): Snapshot {
        val app = context.applicationContext
        // getExternalFilesDir may itself touch FUSE; it also stays outside the UI settings lock.
        val directory = app.getExternalFilesDir(null) ?: error("Недоступны файлы приложения")
        check(Environment.getExternalStorageState(directory) == Environment.MEDIA_MOUNTED) {
            "Недоступны файлы приложения"
        }
        val file = File(directory, "cloud/install.json")
        val config = publication.publish(snapshot = {
            val prefs = app.getSharedPreferences("cloud_link", Context.MODE_PRIVATE)
            val stamp = stamp(app)
            val savedId = prefs.getString(INSTALL_ID, null)
            check(savedId == null || savedId.matches(Regex("[0-9a-f]{32}"))) { "Не удалось прочитать настройки облака" }
            val id = savedId ?: ByteArray(16).also(random::nextBytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val previous = prefs.getLong(GENERATION, 0)
            check(previous >= 0) { "Не удалось прочитать настройки облака" }
            val changed = savedId == null || previous == 0L || prefs.getString(STAMP, null) != stamp
            val generation = if (changed) Math.addExact(previous, 1L) else previous
            // RAM equality after commit(false) is not durable proof. An unchanged editor
            // still retries SharedPreferences' outstanding disk generation when necessary.
            check(prefs.edit().putString(INSTALL_ID, id).putLong(GENERATION, generation)
                .putString(STAMP, stamp).commit()) { "Не удалось сохранить настройки облака" }
            Config(CloudInstallMarker(id, generation, desired(app)), stamp)
        }, write = { it.marker.publish(file.toPath()) }, current = {
            it.stamp == stamp(app) && it.marker.generation == generation(app)
        })
        return Snapshot(config.marker, file)
    }
}
