package com.xxxx.emby_tv.mcp

/**
 * Minimal JSON reader/writer: Map<String, Any?>, List<Any?>, String, Double,
 * Boolean, null. Kept dependency-free so the MCP server adds no libraries.
 */
object Json {
    class ParseError(message: String) : Exception(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val value = p.value()
        p.skipWs()
        if (p.pos != text.length) throw ParseError("trailing data at ${p.pos}")
        return value
    }

    fun write(value: Any?): String {
        val sb = StringBuilder()
        writeTo(sb, value)
        return sb.toString()
    }

    private class Parser(val src: String) {
        var pos = 0

        fun skipWs() {
            while (pos < src.length && src[pos].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) pos++
        }

        fun value(): Any? {
            if (pos >= src.length) throw ParseError("unexpected end")
            return when (val c = src[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> if (c == '-' || c.isDigit()) num() else throw ParseError("unexpected '$c' at $pos")
            }
        }

        fun expect(word: String) {
            if (!src.startsWith(word, pos)) throw ParseError("expected $word")
            pos += word.length
        }

        fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (pos < src.length && src[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                val key = str()
                skipWs()
                if (pos >= src.length || src[pos] != ':') throw ParseError("expected :")
                pos++
                skipWs()
                out[key] = value()
                skipWs()
                if (pos >= src.length) throw ParseError("unexpected end in object")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> throw ParseError("expected , or }")
                }
            }
        }

        fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++
            skipWs()
            if (pos < src.length && src[pos] == ']') { pos++; return out }
            while (true) {
                skipWs()
                out.add(value())
                skipWs()
                if (pos >= src.length) throw ParseError("unexpected end in array")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> throw ParseError("expected , or ]")
                }
            }
        }

        fun str(): String {
            if (pos >= src.length || src[pos] != '"') throw ParseError("expected string at $pos")
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= src.length) throw ParseError("unterminated string")
                val c = src[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= src.length) throw ParseError("unterminated escape")
                        when (val e = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw ParseError("bad \\u escape")
                                sb.append(src.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw ParseError("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Double {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] in "-+.eE")) pos++
            val raw = src.substring(start, pos)
            return raw.toDoubleOrNull() ?: throw ParseError("bad number '$raw'")
        }
    }

    private fun writeTo(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> quote(sb, value)
            is Boolean -> sb.append(value)
            is Double -> {
                if (value == Math.rint(value) && !value.isInfinite() && Math.abs(value) < 1e15) sb.append(value.toLong())
                else sb.append(value)
            }
            is Float -> writeTo(sb, value.toDouble())
            is Number -> sb.append(value)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    writeTo(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, item)
                }
                sb.append(']')
            }
            else -> quote(sb, value.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
