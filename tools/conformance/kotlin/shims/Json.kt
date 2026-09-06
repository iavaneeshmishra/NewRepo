// Tiny org.json-compatible subset (read-only) so ProtocolVectorsTest can run
// under plain kotlinc. NOT used by the Android build (which pulls real org.json).
@file:Suppress("PackageDirectoryMismatch", "unused")

package org.json

class JSONArray internal constructor(private val items: List<Any?>) {
    fun length() = items.size
    fun getString(i: Int): String = items[i] as String
    fun getInt(i: Int): Int = (items[i] as Number).toInt()
    fun getJSONObject(i: Int) = JSONObject(items[i] as Map<String, Any?>)
}

class JSONObject internal constructor(private val map: Map<String, Any?>) {
    constructor(text: String) : this(Parser(text).parseValue() as Map<String, Any?>)
    fun getString(k: String): String = map[k] as? String ?: throw NoSuchElementException(k)
    fun getInt(k: String): Int = (map[k] as? Number ?: throw NoSuchElementException(k)).toInt()
    fun getJSONObject(k: String) = JSONObject(map[k] as? Map<String, Any?> ?: throw NoSuchElementException(k))
    fun getJSONArray(k: String) = JSONArray(map[k] as? List<Any?> ?: throw NoSuchElementException(k))
    fun has(k: String) = map.containsKey(k)
}

private class Parser(private val s: String) {
    private var i = 0
    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    fun parseValue(): Any? {
        ws()
        return when (s[i]) {
            '{' -> parseObject(); '[' -> parseArray(); '"' -> parseString()
            't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
            else -> parseNumber()
        }
    }
    private fun parseObject(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(); i++
        ws(); if (s[i] == '}') { i++; return m }
        while (true) {
            ws(); val k = parseString(); ws(); require(s[i++] == ':'); m[k] = parseValue(); ws()
            when (s[i++]) { ',' -> continue; '}' -> return m; else -> error("bad object at $i") }
        }
    }
    private fun parseArray(): List<Any?> {
        val l = ArrayList<Any?>(); i++
        ws(); if (s[i] == ']') { i++; return l }
        while (true) {
            l.add(parseValue()); ws()
            when (s[i++]) { ',' -> continue; ']' -> return l; else -> error("bad array at $i") }
        }
    }
    private fun parseString(): String {
        require(s[i++] == '"'); val sb = StringBuilder()
        while (true) {
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> when (val e = s[i++]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                    'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
                else -> sb.append(c)
            }
        }
    }
    private fun parseNumber(): Number {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        val t = s.substring(start, i)
        return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
    }
}
