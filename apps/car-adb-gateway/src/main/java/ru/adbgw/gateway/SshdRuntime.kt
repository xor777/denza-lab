package ru.adbgw.gateway

import org.apache.sshd.common.util.io.PathUtils
import java.nio.file.Path

internal object SshdRuntime {
    // Android registers its own stripped Bouncy Castle under the name "BC". MINA finds that
    // provider by name and asks it for EC parameters, which it no longer has, so the first key
    // load dies in ECCurves' static initializer. Without the BC registrar MINA uses the platform
    // default (Conscrypt) for EC and keeps the EdDSA registrar for Ed25519.
    const val BOUNCY_CASTLE_ENABLED_PROPERTY = "org.apache.sshd.security.provider.BC.enabled"

    fun initialize(appHome: Path) {
        System.setProperty(BOUNCY_CASTLE_ENABLED_PROPERTY, "false")
        PathUtils.setUserHomeFolderResolver { appHome }
    }
}
