package dev.denza.apps.core

import org.junit.rules.ExternalResource

/**
 * Runs [body] as a process started with [language] stored would: [AppText] and [AppDecimal] answer in
 * it, then go back to Russian. The car's `Locale.getDefault()` is not touched.
 *
 * It reaches what asks [AppText] when it runs. A surface that kept a word or a figure from an earlier
 * call keeps it here too, as it could not on a car, where a language change restarts the process.
 */
internal fun <T> inLanguage(language: AppLanguage, body: () -> T): T {
    AppText.install(StringsXmlSource(language), language)
    try {
        return body()
    } finally {
        AppText.install(StringsXmlSource(), AppLanguage.RUSSIAN)
    }
}

internal fun <T> inEnglish(body: () -> T): T = inLanguage(AppLanguage.ENGLISH, body)

/**
 * Runs each test of a class in English: a contract test's English subclass holds one of these and
 * nothing else, so that it asks its parent's questions of `values-en/`.
 */
class EnglishWords : ExternalResource() {
    override fun before() = AppText.install(StringsXmlSource(AppLanguage.ENGLISH), AppLanguage.ENGLISH)

    override fun after() = AppText.install(StringsXmlSource(), AppLanguage.RUSSIAN)
}

/**
 * What a test expects in the language it runs in. A Russian run compares against the Russian literal
 * it always did, and a contract test keeps its words side by side where it reads them.
 */
internal fun say(russian: String, english: String): String =
    if (AppText.language == AppLanguage.ENGLISH) english else russian

/** A figure written as a Russian literal, «19,8», with the mark the running language writes. */
internal fun figure(russian: String): String = say(russian, russian.replace(',', '.'))
