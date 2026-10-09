package dev.denza.apps.platform.shell

/**
 * [value] as exactly one word of a POSIX shell command line, whatever characters it holds.
 *
 * Everything between single quotes is literal to the shell; a single quote itself gets through
 * only by closing the quote, escaping one and opening again - `'\''`. That is the rule the
 * transport frames every command with (`LocalAdbClient.singleQuoted`) and the one the split's
 * resident helper reads a request line back by (`SplitTaskProxyMain.splitArguments`, which refuses
 * a double quote), so a word means the same thing on all three.
 *
 * Some of the per-file copies it replaces spelled the quote `'"'"'`. Both forms give the shell the
 * same word (`ShellQuoteTest` runs them through `/bin/sh`); this is the one the helper can read.
 */
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
