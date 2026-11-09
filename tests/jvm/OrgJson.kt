// Substituto mínimo do org.json do Android, só para testar na JVM.
package org.json

class JSONObject() {
    val map = LinkedHashMap<String, Any?>()
    constructor(text: String) : this() { val p = P(text); val o = p.value() as JSONObject; map.putAll(o.map) }
    fun put(k: String, v: Any?): JSONObject { map[k] = v; return this }
    fun optString(k: String, d: String = ""): String = map[k]?.let { if (it is String) it else it.toString() } ?: d
    fun optBoolean(k: String, d: Boolean = false): Boolean = (map[k] as? Boolean) ?: d
    fun optInt(k: String, d: Int = 0): Int = (map[k] as? Number)?.toInt() ?: d
    fun optLong(k: String, d: Long = 0L): Long = (map[k] as? Number)?.toLong() ?: d
    fun optDouble(k: String, d: Double = Double.NaN): Double = (map[k] as? Number)?.toDouble() ?: d
    fun keys(): Iterator<String> = map.keys.toList().iterator()
    fun has(k: String) = map.containsKey(k)
    fun isNull(k: String) = map.containsKey(k) && map[k] == null
    fun optJSONObject(k: String): JSONObject? = map[k] as? JSONObject
    fun optJSONArray(k: String): JSONArray? = map[k] as? JSONArray
    override fun toString(): String = map.entries.joinToString(",", "{", "}") { q(it.key) + ":" + enc(it.value) }
    companion object {
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        fun enc(v: Any?): String = when (v) { null -> "null"; is String -> q(v); is JSONArray -> v.toString(); else -> v.toString() }
    }
}
class JSONArray() {
    val list = ArrayList<Any?>()
    constructor(text: String) : this() { list.addAll((P(text).value() as JSONArray).list) }
    fun length() = list.size
    fun optJSONObject(i: Int): JSONObject? = list.getOrNull(i) as? JSONObject
    fun optString(i: Int): String = list.getOrNull(i)?.toString() ?: ""
    fun put(v: Any?): JSONArray { list.add(v); return this }
    override fun toString() = list.joinToString(",", "[", "]") { JSONObject.enc(it) }
}
private class P(val s: String) {
    var i = 0
    fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    fun value(): Any? { ws(); return when (s[i]) {
        '{' -> { i++; val o = JSONObject(); ws(); if (s[i] == '}') { i++; return o }
                 while (true) { ws(); val k = str(); ws(); i++; o.put(k, value()); ws(); if (s[i] == ',') { i++ } else { i++; return o } } ; o }
        '[' -> { i++; val l = JSONArray(); ws(); if (s[i] == ']') { i++; return l }
                 while (true) { l.list.add(value()); ws(); if (s[i] == ',') i++ else { i++; return l } }; l }
        '"' -> str()
        't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
        else -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; val t = s.substring(st, i); if (t.contains('.') || t.contains('e')) t.toDouble() else t.toLong() }
    } }
    fun str(): String { i++; val b = StringBuilder(); while (s[i] != '"') { if (s[i] == '\\') { i++; when (s[i]) { 'n' -> b.append('\n'); 't' -> b.append('\t'); 'u' -> { b.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }; else -> b.append(s[i]) } } else b.append(s[i]); i++ }; i++; return b.toString() }
}
