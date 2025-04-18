package eu.kanade.tachiyomi.extension.zh.rumanhua

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

val json: Json by injectLazy()

inline fun <reified T> Response.parseAs(): T {
    return json.decodeFromString(body.string())
}

private val authorRegex = Regex("""(?<=作者：)(\S+( · )?)*""")

fun formatAuthor(string: String): String {
    val input = string.replace("&", "·").replace("\u00a0", " ")
    return authorRegex.find(input)!!.value.replace(" · ", ",")
}
