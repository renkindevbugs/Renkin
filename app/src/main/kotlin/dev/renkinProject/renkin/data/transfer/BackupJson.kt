package dev.renkinProject.renkin.data.transfer

import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException

internal fun parseBackupJson(text: String): JSONObject {
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in text) {
        if (quoted) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> quoted = false
            }
        } else {
            when (char) {
                '"' -> quoted = true
                '{', '[' -> {
                    depth++
                    if (depth > 64) throw IOException("Backup JSON is nested too deeply")
                }
                '}', ']' -> {
                    depth--
                    if (depth < 0) throw IOException("Unbalanced backup JSON")
                }
                '\'', '/' -> throw IOException("Non-standard backup JSON")
            }
        }
    }
    if (quoted || depth != 0) throw IOException("Incomplete backup JSON")
    val tokens = JSONTokener(text)
    val result = JSONObject(tokens)
    if (tokens.nextClean() != '\u0000') throw IOException("Trailing backup JSON content")
    return result
}
