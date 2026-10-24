package minecartographer.textures

/**
 * Just enough JSON for Minecraft's block model files: objects become maps, arrays lists,
 * numbers doubles. Lenient about what Mojang's files contain (comments are not expected).
 */
object Json {
    fun parse(text: String): Any? = Parser(text).run {
        val value = value()
        skipSpace()
        if (pos != text.length) fail("Unexpected trailing text")
        value
    }

    private class Parser(val text: String) {
        var pos = 0

        fun fail(message: String): Nothing = throw IllegalArgumentException("$message at offset $pos")

        fun skipSpace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipSpace()
            if (pos >= text.length) fail("Unexpected end")
            return when (val c = text[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else fail("Unexpected '$c'")
            }
        }

        fun obj(): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipSpace()
            if (text.getOrNull(pos) == '}') { pos++; return map }
            while (true) {
                skipSpace()
                val key = string()
                skipSpace()
                if (text.getOrNull(pos) != ':') fail("Expected ':'")
                pos++
                map[key] = value()
                skipSpace()
                when (text.getOrNull(pos)) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        fun array(): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipSpace()
            if (text.getOrNull(pos) == ']') { pos++; return list }
            while (true) {
                list += value()
                skipSpace()
                when (text.getOrNull(pos)) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        fun string(): String {
            if (text.getOrNull(pos) != '"') fail("Expected a string")
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("Unterminated string")
                val c = text[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = text.getOrNull(pos++) ?: fail("Unterminated escape")
                        when (e) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> {
                                sb.append(text.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun number(): Double {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
            return text.substring(start, pos).toDoubleOrNull() ?: fail("Bad number")
        }

        fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) fail("Expected $word")
            pos += word.length
            return value
        }
    }
}
