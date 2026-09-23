package dev.denza.apps.design.luminofor

import java.io.File

/**
 * Reads `tools/design-canvas/luminofor/spec.json` for the Luminofor contract tests.
 *
 * The JVM test classpath has no JSON library and `org.json` there is Android's stub, so this is the
 * smallest parser that reads the spec: objects, arrays, strings, numbers, booleans and null. The
 * file is found the way the other board tests find theirs, by walking up from the working
 * directory until `tools/design-canvas` appears.
 */
object SpecJson {

    val spec: Map<String, Any?> by lazy { parse(read("luminofor/spec.json")) as Map<String, Any?> }

    fun read(name: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "tools/design-canvas/$name")
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        error("tools/design-canvas/$name not found above ${System.getProperty("user.dir")}")
    }

    /** `at("cluster", "grid", "axis")` -> the value there. */
    fun at(vararg path: String): Any? {
        var v: Any? = spec
        for (key in path) {
            v = when (v) {
                is Map<*, *> -> v[key]
                is List<*> -> v[key.toInt()]
                else -> error("${path.joinToString(".")}: not a container at $key")
            }
        }
        return v
    }

    fun num(vararg path: String): Double = (at(*path) as? Number)?.toDouble() ?: error("${path.joinToString(".")} is not a number")

    fun str(vararg path: String): String = at(*path) as? String ?: error("${path.joinToString(".")} is not a string")

    fun list(vararg path: String): List<Any?> = at(*path) as? List<Any?> ?: error("${path.joinToString(".")} is not a list")

    fun parse(text: String): Any? = Parser(text).run { value().also { skip(); require(i == s.length) { "trailing text at $i" } } }

    private class Parser(val s: String) {
        var i = 0

        fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skip()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at $i")
            }
        }

        fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            skip()
            if (s[i] == '}') { i++; return out }
            while (true) {
                skip()
                val k = string()
                skip(); require(s[i] == ':'); i++
                out[k] = value()
                skip()
                if (s[i] == ',') { i++; continue }
                require(s[i] == '}') { "expected } at $i" }
                i++
                return out
            }
        }

        fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            skip()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out += value()
                skip()
                if (s[i] == ',') { i++; continue }
                require(s[i] == ']') { "expected ] at $i" }
                i++
                return out
            }
        }

        fun string(): String {
            require(s[i] == '"')
            i++
            val b = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (s[i]) {
                        'n' -> b.append('\n')
                        't' -> b.append('\t')
                        'u' -> { b.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> b.append(s[i])
                    }
                } else b.append(s[i])
                i++
            }
            i++
            return b.toString()
        }

        fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }

        fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "expected $word at $i" }
            i += word.length
            return v
        }
    }
}
