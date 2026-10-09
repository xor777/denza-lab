package dev.denza.apps

import java.io.File

/**
 * The app's own manifest, for the contract tests that read it rather than a copy of it.
 *
 * Read as text on purpose: a declaration is what the platform reads, so the text is the contract,
 * and no JVM test can ask a package manager what it made of it. That is the line between these
 * tests and the ones that read Kotlin sources, which hold code to strings where its behaviour is
 * the contract and are replaced by behavioural tests as the code allows.
 */
internal fun appManifest(): String = File("src/main/AndroidManifest.xml").readText()

/**
 * One component's declaration in a manifest: its opening tag to its closing tag, or the one tag
 * when it closes itself.
 *
 * [kind] is the element - `activity`, `service`, `receiver` - and [name] its `android:name` as the
 * manifest writes it, `.RuntimeRecoveryReceiver`. A component that is not declared fails the test
 * here, rather than handing back nothing for a `contains` to be false on. Five manifest contracts
 * each carried a copy of this, two of them reading the opening tag alone.
 */
internal fun String.component(kind: String, name: String): String {
    val opening = checkNotNull(
        Regex("<$kind\\s+[^>]*android:name=\"${Regex.escape(name)}\"[^>]*>").find(this),
    ) { "$kind $name is not declared in the manifest" }
    if (opening.value.trimEnd().endsWith("/>")) return opening.value
    val closing = "</$kind>"
    val closeAt = indexOf(closing, opening.range.last + 1)
    check(closeAt >= 0) { "$kind $name has no closing tag" }
    return substring(opening.range.first, closeAt + closing.length)
}
