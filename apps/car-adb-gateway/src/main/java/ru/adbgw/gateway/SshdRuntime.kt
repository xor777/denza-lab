package ru.adbgw.gateway

import org.apache.sshd.common.util.io.PathUtils
import java.nio.file.Path

internal object SshdRuntime {
    fun initialize(appHome: Path) {
        PathUtils.setUserHomeFolderResolver { appHome }
    }
}
