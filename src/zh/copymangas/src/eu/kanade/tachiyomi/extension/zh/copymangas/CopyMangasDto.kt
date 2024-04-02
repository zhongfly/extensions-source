package eu.kanade.tachiyomi.extension.zh.copymangas

import com.luhuiguo.chinese.ChineseUtils
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale

@Serializable
class MangaDto(
    val name: String,
    val path_word: String,
    val author: List<KeywordDto>,
    val cover: String,
    val region: ValueDto? = null,
    val status: ValueDto? = null,
    val theme: List<KeywordDto>? = null,
    val brief: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = URL_PREFIX + path_word
        title = if (convertToSc) { ChineseUtils.toSimplified(name) } else { name }
        author = this@MangaDto.author.joinToString { it.name }
        thumbnail_url = cover.removeSuffix(".328x422.jpg")
    }

    fun toSMangaDetails(groups: ChapterGroups) = toSManga().apply {
        description = (if (convertToSc) { ChineseUtils.toSimplified(brief) } else { brief })
        genre = buildList(theme!!.size + 1) {
            add(region!!.display)
            theme.mapTo(this) { it.name }
        }.joinToString { ChineseUtils.toSimplified(it) }
        status = when (this@MangaDto.status!!.value) {
            0 -> SManga.ONGOING
            1 -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    companion object {
        internal var convertToSc = false

        const val URL_PREFIX = "/comic/"
    }
}

@Serializable
class ChapterDto(
    val uuid: String,
    val name: String,
    val comic_path_word: String,
    val datetime_created: String,
) {
    fun toSChapter(group: String) = SChapter.create().apply {
        url = "/comic/$comic_path_word/chapter2/$uuid"
        name = if (group.isEmpty()) { this@ChapterDto.name } else { group + '：' + this@ChapterDto.name }
        date_upload = dateFormat.parse(datetime_created)?.time ?: 0
    }

    companion object {
        val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH) }
    }
}

@Serializable
class KeywordDto(val name: String, val path_word: String) {
    fun toParam() = Param(ChineseUtils.toSimplified(name), path_word)
}

@Serializable
class ValueDto(val value: Int, val display: String)

@Serializable
class MangaWrapperDto(val comic: MangaDto, val groups: ChapterGroups? = null) {
    fun toSManga() = comic.toSManga()
    fun toSMangaDetails() = comic.toSMangaDetails(groups!!)
}

typealias ChapterGroups = LinkedHashMap<String, KeywordDto>

@Serializable
class ChapterPageListDto(val contents: List<UrlDto>, val words: List<Int>)

@Serializable
class UrlDto(val url: String)

@Serializable
class ChapterPageListWrapperDto(val chapter: ChapterPageListDto, val show_app: Boolean)

@Serializable
class ListDto<T>(
    val total: Int,
    val limit: Int,
    val offset: Int,
    val list: List<T>,
)

@Serializable
class ResultDto<T>(val results: T)

@Serializable
class ResultMessageDto(val code: Int, val message: String)

@Serializable
class TokenDto(val token: String)

@Serializable
class VersionDto(
    val android: VersionInfo,
) {
    @Serializable
    class VersionInfo(
        val update: Boolean,
        val version: String? = null,
    )
}
