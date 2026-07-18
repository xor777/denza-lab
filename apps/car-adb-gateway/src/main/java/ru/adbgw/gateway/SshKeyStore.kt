package ru.adbgw.gateway

import android.content.Context
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyPair

class SshKeyStore(context: Context) {
    private val appFiles = context.applicationContext.filesDir

    private val tunnelFile = File(appFiles, "relay_device_key")
    private val controlFile = File(appFiles, "relay_control_key")
    private val innerHostFile = File(appFiles, "inner_ssh_host_key")
    private var cachedTunnelKeyPair: KeyPair? = null
    private var cachedControlKeyPair: KeyPair? = null
    private var cachedInnerHostKeyPair: KeyPair? = null

    val tunnelKeyPair: KeyPair get() = synchronized(this) {
        cachedTunnelKeyPair ?: loadRsa(tunnelFile).also { cachedTunnelKeyPair = it }
    }
    val controlKeyPair: KeyPair get() = synchronized(this) {
        cachedControlKeyPair ?: loadRsa(controlFile).also { cachedControlKeyPair = it }
    }
    val innerHostKeyPair: KeyPair get() = synchronized(this) {
        cachedInnerHostKeyPair ?: loadRsa(innerHostFile).also { cachedInnerHostKeyPair = it }
    }

    val tunnelPublicKey: String get() = PublicKeyEntry.toString(tunnelKeyPair.public)
    val controlPublicKey: String get() = PublicKeyEntry.toString(controlKeyPair.public)
    val innerHostPublicKey: String get() = PublicKeyEntry.toString(innerHostKeyPair.public)

    @Synchronized
    fun preserveLegacyControlIdentity(hasRegistration: Boolean) {
        if (!hasRegistration || controlFile.exists() || !tunnelFile.exists()) return
        Files.copy(tunnelFile.toPath(), controlFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        cachedControlKeyPair = cachedTunnelKeyPair
    }

    @Synchronized
    fun rotateForReenrollment() {
        val files = listOf(tunnelFile, controlFile, innerHostFile)
        val staged = files.map { File(it.parentFile, "${it.name}.next") }
        staged.forEach { it.delete() }
        staged.forEach(::loadRsa)
        staged.zip(files).forEach { (source, target) ->
            runCatching {
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        cachedTunnelKeyPair = null
        cachedControlKeyPair = null
        cachedInnerHostKeyPair = null
    }

    private fun loadRsa(file: File): KeyPair = SimpleGeneratorHostKeyProvider(file.toPath()).apply {
        algorithm = "RSA"
        keySize = 2048
        setStrictFilePermissions(false)
    }.loadKeys(null).first()
}
