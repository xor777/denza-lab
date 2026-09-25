package dev.denza.apps.feature.cloud

import java.io.FileNotFoundException

/** Reuse the fixed report when a retained URI is lost; ambiguity never creates another file. */
internal class CloudReportTarget<T>(
    private val find: () -> List<T>,
    private val insert: () -> T,
    private val inspect: (T) -> Match,
    private val discardFresh: (T) -> Unit,
    private val write: (T) -> Unit,
    private val remember: (T) -> Unit,
) {
    enum class Match { EXACT, DIFFERENT, MISSING }

    fun publish(saved: T?) {
        if (saved == null) {
            use(select(allowInsert = true))
            return
        }
        try {
            when (inspect(saved)) {
                Match.EXACT -> use(saved)
                // A visible row with a different name/path is the owner's archive. Leave it
                // untouched and create/reuse the requested fixed name.
                Match.DIFFERENT -> use(select(allowInsert = true))
                // A missing row or inaccessible grant cannot prove the fixed file is absent.
                Match.MISSING -> use(select(allowInsert = false))
            }
        } catch (error: FileNotFoundException) {
            // A missing descriptor may also mean scoped-storage ownership changed. A lookup
            // can recover one visible report; zero is still ambiguous, so never insert here.
            use(select(allowInsert = false))
        } catch (error: SecurityException) {
            // A revoked grant does not prove deletion. Never insert on this path.
            use(select(allowInsert = false))
        }
    }

    private fun select(allowInsert: Boolean): T {
        val matches = find()
        return when (matches.size) {
            0 -> if (allowInsert) {
                val fresh = insert()
                val verified = try { inspect(fresh) == Match.EXACT } catch (error: Exception) {
                    runCatching { discardFresh(fresh) }
                    throw error
                }
                if (!verified) {
                    runCatching { discardFresh(fresh) }
                    error("Созданный отчёт получил другое имя")
                }
                fresh
            } else error("Нет доступа к прежнему отчёту")
            1 -> matches.single()
            else -> error("Несколько отчётов с одним именем")
        }
    }

    private fun use(target: T) {
        write(target)
        remember(target)
    }
}
