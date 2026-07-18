package ru.adbgw.gateway

import android.content.Context
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import java.io.File
import java.security.KeyPair

class SshKeyStore(context: Context) {
    private val appFiles = context.applicationContext.filesDir

    val tunnelKeyPair: KeyPair by lazy { loadRsa(File(appFiles, "relay_device_key")) }
    val innerHostKeyPair: KeyPair by lazy { loadRsa(File(appFiles, "inner_ssh_host_key")) }

    val tunnelPublicKey: String get() = PublicKeyEntry.toString(tunnelKeyPair.public)
    val innerHostPublicKey: String get() = PublicKeyEntry.toString(innerHostKeyPair.public)

    private fun loadRsa(file: File): KeyPair = SimpleGeneratorHostKeyProvider(file.toPath()).apply {
        algorithm = "RSA"
        keySize = 2048
        setStrictFilePermissions(false)
    }.loadKeys(null).first()
}
