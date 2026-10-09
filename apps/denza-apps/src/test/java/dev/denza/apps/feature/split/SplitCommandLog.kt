package dev.denza.apps.feature.split

import java.io.File

/**
 * Everything the fake car of every split test was sent, written to one file - off unless asked.
 *
 * It is how a refactor of the recipes proves that no command changed: record the split tests
 * before and after, and the two files are equal.
 *
 * ```
 * ./gradlew :denza-apps:testDebugUnitTest --tests 'dev.denza.apps.feature.split.*' --rerun \
 *     -PsplitCommandLog=/path/to/before.log
 * # change the code, run again with -PsplitCommandLog=/path/to/after.log, then:
 * diff /path/to/before.log /path/to/after.log
 * ```
 *
 * `--rerun` is needed because an up-to-date test task writes nothing. Each [FakeShell] is named
 * by the test that built it and its place among that test's cars (`Class.test#2`), and records
 * every command in order plus every gate flip that came through the in-process binder. The file is
 * sorted by that name and written when the test JVM ends. The scenarios drive the actor
 * deterministically, so two runs of the same code write the same file.
 */
internal object SplitCommandLog {
    private val target: File? = System.getProperty("denza.splitCommandLog")?.let(::File)
    private val lock = Any()
    private val cars = sortedMapOf<String, MutableList<String>>()
    private val carsPerTest = mutableMapOf<String, Int>()

    init {
        target?.let { file ->
            Runtime.getRuntime().addShutdownHook(Thread { write(file) })
        }
    }

    /** The name a new fake car records under, or `null` when nothing is recorded. */
    fun carName(): String? {
        target ?: return null
        val frame = Thread.currentThread().stackTrace.lastOrNull { frame ->
            frame.className.startsWith("dev.denza.apps.") && frame.className.endsWith("Test")
        }
        val test = frame?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" } ?: "?"
        return synchronized(lock) {
            val number = (carsPerTest[test] ?: 0) + 1
            carsPerTest[test] = number
            "$test#$number".also { name -> cars[name] = mutableListOf() }
        }
    }

    fun record(car: String?, line: String) {
        car ?: return
        synchronized(lock) { cars.getValue(car) += line }
    }

    private fun write(file: File) {
        file.parentFile?.mkdirs()
        synchronized(lock) {
            file.bufferedWriter().use { writer ->
                cars.forEach { (car, lines) ->
                    writer.write("## $car\n")
                    lines.forEach { line -> writer.write("$line\n") }
                }
            }
        }
    }
}
