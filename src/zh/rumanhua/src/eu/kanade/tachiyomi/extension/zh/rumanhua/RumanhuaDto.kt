package eu.kanade.tachiyomi.extension.zh.rumanhua

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable

@Serializable
class ResponseDto<T>(
    val code: String = "200",
    val msg: String = "",
    val data: T,
)

@Serializable
class SearchMangaDto(
    private val id: String,
    private val name: String,
    private val imgurl: String,
) {
    fun toSManga() = SManga.create().apply {
        url = id
        title = name
        thumbnail_url = imgurl
        initialized = true
    }
}

@Serializable
class ChapterDto(
    val chapterid: String,
    val chaptername: String,
)
