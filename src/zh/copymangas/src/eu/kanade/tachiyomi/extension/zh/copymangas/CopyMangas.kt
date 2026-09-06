package eu.kanade.tachiyomi.extension.zh.copymangas

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.luhuiguo.chinese.ChineseUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

@Source
abstract class CopyMangas :
    KeiSource(),
    ConfigurableSource {
    private val preferences by getPreferencesLazy()

    private var convertToSc = preferences.getBoolean(SC_TITLE_PREF, false)
    private var useHotmanga = preferences.getBoolean(USE_HOTMANGA_REF, false) // false = 拷贝，true = 热辣

    private var apiUrl = getDomain(DOMAIN_PREF, DEFAULT_API_DOMAIN)
    private var webUrl = getDomain(WEB_DOMAIN_PREF, DEFAULT_WEB_DOMAIN)

    private var hotmangaApiUrl = getDomain(HOTMANGA_DOMAIN_PREF, DEFAULT_HOTMANGA_API_DOMAIN)
    private var hotmangaWebUrl = getDomain(HOTMANGA_WEB_DOMAIN_PREF, DEFAULT_HOTMANGA_WEB_DOMAIN)

    private fun getDomain(pref: String, default: String): String {
        val domain = preferences.getString(pref, default)
        return if (domain.isNullOrBlank()) {
            "https://$default"
        } else {
            "https://$domain"
        }
    }

    private fun getUrl(type: String): String = when (type) {
        "api" -> if (useHotmanga) hotmangaApiUrl else apiUrl
        "web" -> if (useHotmanga) hotmangaWebUrl else webUrl
        else -> throw IllegalArgumentException("Unknown URL type: $type")
    }

    override val baseUrl: String
        get() = getUrl("web")

    private val chapterRatelimitRegex = Regex("""/chapter2?/""")
    private val imageQualityRegex = Regex("""(c|h)(800|1200|1500)x\.""")

    private val trustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        }
    }
    private val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, arrayOf(trustManager), SecureRandom())
    }

    private fun responseInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.toString().contains(getUrl("api"))) return chain.proceed(request)

        val response = chain.proceed(request)
        if (!response.isSuccessful) return response
        if (response.header("Content-Type") != "application/json") {
            response.close()
            throw Exception("返回数据错误，不是json")
        }

        val result = response.peekBody(Long.MAX_VALUE).string().parseAs<ResultMessageDto>()
        if (result.code != 200) {
            response.close()
            throw Exception("返回数据错误:${result.message}")
        }
        return response
    }

    override fun OkHttpClient.Builder.configureClient() = sslSocketFactory(sslContext.socketFactory, trustManager)
        .rateLimit(preferences.getString(CHAPTER_API_RATE_PREF, "15")!!.toInt(), 61.seconds) { it.toString().contains(chapterRatelimitRegex) }
        .apply {
            interceptors().apply {
                val uncaughtExceptionInterceptor = first { it.javaClass.simpleName == "UncaughtExceptionInterceptor" }
                remove(uncaughtExceptionInterceptor)
                add(0, uncaughtExceptionInterceptor)
                add(1, ::responseInterceptor)
            }
            addInterceptor(CommentsInterceptor)
        }

    private fun Headers.Builder.setUserAgent(userAgent: String) = set("User-Agent", userAgent)
    private fun Headers.Builder.setWebp(useWebp: Boolean) = set(
        "webp",
        if (useWebp) {
            "1"
        } else {
            "0"
        },
    )

    private fun Headers.Builder.setRegion(useOverseasCdn: Boolean) = set(
        "region",
        if (useOverseasCdn) {
            "0"
        } else {
            "1"
        },
    )

    private fun Headers.Builder.setToken(token: String = "") = set(
        "authorization",
        if (token.isNotBlank()) {
            "Token $token"
        } else {
            "Token"
        },
    )

    override fun Headers.Builder.configureHeaders() = setUserAgent(
        preferences.getString(BROWSER_USER_AGENT_PREF, DEFAULT_BROWSER_USER_AGENT)!!,
    )

    private var apiHeaders = Headers.Builder()
        .setUserAgent(preferences.getString(BROWSER_USER_AGENT_PREF, DEFAULT_BROWSER_USER_AGENT)!!)
        .setWebp(preferences.getBoolean(WEBP_PREF, true))
        .setRegion(preferences.getBoolean(OVERSEAS_CDN_PREF, false))
        .setToken("")
        .add("version", "2025.08.08")
        .add("platform", "1")
        .build()

    private val webHeaders: Headers
        get() = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()

    private suspend fun fetchToken(username: String, password: String): Map<String, String> {
        val results =
            mutableMapOf<String, String>("success" to "false", "message" to "", "token" to "")
        if (username.isBlank() || password.isBlank()) {
            results["message"] = "用户名或密码为空"
            return results
        }
        try {
            val salt = (1000..9999).random().toString()
            val passwordEncoded =
                Base64.encodeToString("$password-$salt".toByteArray(), Base64.DEFAULT).trim()
            val formBody = FormBody.Builder()
                .addEncoded("username", username)
                .addEncoded("password", passwordEncoded)
                .addEncoded("salt", salt)
                .build()
            val headers = apiHeaders.newBuilder().setToken().build()
            val response = client.post("${getUrl("api")}/api/v3/login", headers, formBody, ensureSuccess = false)
            if (response.code != 200) {
                results["message"] =
                    response.parseAs<ResultMessageDto>().message
            } else {
                results["token"] =
                    response.parseAs<ResultDto<TokenDto>>().results.token
                results["success"] = "true"
            }
        } catch (e: Exception) {
            Log.e("CopyMangas", "failed to fetch token", e)
        }
        return results
    }

    private suspend fun verifyToken(token: String): Boolean {
        if (token.isBlank()) {
            return false
        }
        try {
            val headers = apiHeaders.newBuilder()
                .setToken(token)
                .build()
            return client.get("${getUrl("api")}/api/v3/member/info", headers, ensureSuccess = false).use {
                it.code == 200
            }
        } catch (e: Exception) {
            Log.e("CopyMangas", "failed to verify token", e)
        }
        return false
    }

    init {
        MangaDto.convertToSc = preferences.getBoolean(SC_TITLE_PREF, false)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val offset = PAGE_SIZE * (page - 1)
        return getMangaList(
            "${getUrl("api")}/api/v3/comics?limit=$PAGE_SIZE&offset=$offset&free_type=1&ordering=-popular&theme=&top=",
        )
    }

    private suspend fun getMangaList(url: String): MangasPage {
        val page = client.get(url, apiHeaders).parseAs<ResultDto<ListDto<MangaDto>>>().results
        val hasNextPage = page.offset + page.limit < page.total
        return MangasPage(page.list.map { it.toSManga() }, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val offset = PAGE_SIZE * (page - 1)
        return getMangaList(
            "${getUrl("api")}/api/v3/comics?limit=$PAGE_SIZE&offset=$offset&free_type=1&ordering=-datetime_updated&theme=&top=",
        )
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val headersBuilder = apiHeaders.newBuilder()
        val offset = PAGE_SIZE * (page - 1)
        val builder = getUrl("api").toHttpUrl().newBuilder()
            .addQueryParameter("limit", "$PAGE_SIZE")
            .addQueryParameter("offset", "$offset")

        if (query.isNotBlank()) {
            builder.addPathSegments("api/v3/search/comic")
                .addQueryParameter("q", query)
            filters.firstInstanceOrNull<SearchFilter>()?.addQuery(builder)
            // builder.addQueryParameter("q_type", "")
            headersBuilder.setToken(preferences.getString(if (useHotmanga) HOTMANGA_TOKEN_PREF else TOKEN_PREF, "")!!)
        } else {
            val ranking = filters.firstInstanceOrNull<RankingGroup>()
            if (ranking != null && (ranking.state[0] as TypeFilter).state != 0) {
                val rankType = (ranking.state[0] as TypeFilter).state
                if (rankType != 1) {
                    // 排行榜
                    builder.addPathSegments("api/v3/ranks")
                        .addQueryParameter("type", "1")
                    ranking.state.filterIsInstance<CopyMangaFilter>().forEach {
                        it.addQuery(builder)
                    }
                } else {
                    // 最新上架
                    builder.addPathSegments("api/v3/update/newest")
                }
            } else {
                // 分类/发现
                builder.addPathSegments("api/v3/comics")
                filters.filterIsInstance<CopyMangaFilter>().forEach {
                    if (it !is SearchFilter) {
                        it.addQuery(builder)
                    }
                }
            }
        }

        val response = client.get(builder.build(), headersBuilder.build())
        val page = if (response.request.url.pathSegments.last().startsWith("comic")) {
            response.parseAs<ResultDto<ListDto<MangaDto>>>().results
        } else {
            response.parseAs<ResultDto<ListDto<RanksListWrapperDto>>>().results
        }

        val mangas = page.list.map {
            if (it is RanksListWrapperDto) it.comic.toSManga() else (it as MangaDto).toSManga()
        }
        val hasNextPage = page.offset + page.limit < page.total

        return MangasPage(mangas, hasNextPage)
    }

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    private suspend fun getMangaDetails(mangaSlug: String): MangaWrapperDto = client.get(
        "${getUrl("api")}/api/v3/comic2/$mangaSlug?platform=1&_update=true",
        apiHeaders,
    ).parseAs<ResultDto<MangaWrapperDto>>().results

    suspend fun getMangaDetails(manga: SManga): SManga = getMangaDetails(manga.url.removePrefix(MangaDto.URL_PREFIX)).toSMangaDetails()

    suspend fun getChapterList(manga: SManga): List<SChapter> {
        val mangaSlug = manga.url.removePrefix(MangaDto.URL_PREFIX)
        val mangaDetails = getMangaDetails(mangaSlug)
        return fetchChapterList(mangaSlug, mangaDetails.groups)
    }

    private suspend fun fetchChapterList(manga: String, groups: ChapterGroups?): List<SChapter> {
        val result = ArrayList<SChapter>()
        for (group in groups.orEmpty().values) {
            result += fetchChapterGroup(manga, group.path_word, group.name)
        }
        return result
    }

    private suspend fun fetchChapterGroup(manga: String, key: String, name: String): List<SChapter> {
        val result = ArrayList<SChapter>(0)
        var offset = 0
        var hasNextPage = true
        val groupName = when {
            key.equals("default") -> ""
            convertToSc -> ChineseUtils.toSimplified(name)
            else -> name
        }
        while (hasNextPage) {
            val chapters = client.get(
                "${getUrl("api")}/api/v3/comic/$manga/group/$key/chapters?limit=$CHAPTER_PAGE_SIZE&offset=$offset&_update=true",
                apiHeaders,
            ).parseAs<ResultDto<ListDto<ChapterDto>>>().results
            result.ensureCapacity(chapters.total)
            chapters.list.mapTo(result) { it.toSChapter(groupName) }
            offset += CHAPTER_PAGE_SIZE
            hasNextPage = offset < chapters.total
        }
        return result.asReversed()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) {
            return SMangaUpdate(manga, chapters)
        }

        val mangaSlug = manga.url.removePrefix(MangaDto.URL_PREFIX)
        val mangaDetails = getMangaDetails(mangaSlug)
        val updatedManga = if (fetchDetails) mangaDetails.toSMangaDetails() else manga
        val chapterList = if (fetchChapters) fetchChapterList(mangaSlug, mangaDetails.groups) else chapters

        return SMangaUpdate(updatedManga, chapterList)
    }

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url.replace("/chapter2/", "/chapter/")

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = if (useHotmanga) {
            "$hotmangaApiUrl/api/v3${chapter.url.replace("/chapter2/", "/chapter/")}"
        } else {
            "$apiUrl/api/v3${chapter.url}"
        }
        val chapterId = url.toHttpUrl().pathSegments.last()
        val result = client.get("$url?platform=1&_update=true", apiHeaders)
            .parseAs<ResultDto<ChapterPageListWrapperDto>>().results
        val images = result.chapter.contents
        val orders = result.chapter.words
        val pageList = if (orders.isNullOrEmpty()) {
            images.mapIndexedTo(ArrayList(images.size + 1)) { i, it -> Page(i, imageUrl = it.url) }
        } else {
            images.withIndex().sortedBy { orders[it.index] }.map { it.value }.mapIndexedTo(ArrayList(images.size + 1)) { i, it ->
                Page(i, imageUrl = it.url)
            }
        }
        if (preferences.getBoolean(COMMENTS_PREF, false)) {
            pageList.add(
                Page(
                    pageList.size,
                    url = COMMENTS_FLAG,
                    imageUrl = chapterCommentsUrl(chapterId),
                ),
            )
        }
        return pageList
    }

    private var imageQuality = preferences.getString(QUALITY_PREF, QUALITY[0])
    override fun imageRequest(page: Page): Request {
        var imageUrl = page.imageUrl!!
        if (page.url == COMMENTS_FLAG) {
            return GET(imageUrl, apiHeaders).newBuilder().tag(String::class, COMMENTS_FLAG).build()
        }
        imageUrl = imageQualityRegex.replace(imageUrl, "c${imageQuality}x.")
        return GET(imageUrl, webHeaders)
    }

    private fun chapterCommentsUrl(chapterId: String) = "$apiUrl/api/v3/roasts?chapter_id=$chapterId&limit=30&offset=0&_update=true"

    private inline fun showToast(
        context: Context,
        text: String,
        duration: Int = Toast.LENGTH_SHORT,
    ) {
        if (Looper.getMainLooper() == Looper.myLooper()) {
            Toast.makeText(context, text, duration).show()
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, text, duration).show()
            }
        }
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client.get(
        "${getUrl("api")}/api/v3/theme/comic/count?limit=500&offset=0&free_type=1",
        apiHeaders,
    ).parseAs()

    override fun getFilterList(data: JsonElement?): FilterList {
        val genreFilter = data
            ?.parseAs<ResultDto<ListDto<KeywordDto>>>()
            ?.results
            ?.list
            ?.filter { it.count != null && it.count != 0 }
            ?.sortedBy { it.name }
            ?.let { genres ->
                val params = ArrayList<Param>(genres.size + 1).apply { add(Param("全部", "")) }
                GenreFilter(genres.mapTo(params) { it.toParam() }.toTypedArray())
            }
            ?: Filter.Header("点击“重置”尝试刷新题材分类")

        return FilterList(
            buildList<Filter<*>> {
                add(SearchFilter())
                add(Filter.Separator())
                add(RankingGroup())
                add(Filter.Separator())
                add(Filter.Header("分类（搜索文本时无效）"))
                add(genreFilter)
                add(TopFilter())
                add(SortFilter())
            },
        )
    }

    var fetchTokenState =
        0 // -1 = failed , 0 = not yet, 1 = fetching, 2 = succeed, 3 = Token is valid no need to refresh

    private val domainRegex = Regex("""(?<=://|^)([a-zA-Z0-9-]+\.[a-zA-Z0-9.-]+)""")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = if (useHotmanga) HOTMANGA_DOMAIN_PREF else DOMAIN_PREF
            title = "API域名"
            summary = "API请求使用的域名，拷贝默认为$DEFAULT_API_DOMAIN，热辣默认为$DEFAULT_HOTMANGA_API_DOMAIN"
            setDefaultValue(DEFAULT_API_DOMAIN)
            setOnPreferenceChangeListener { _, newValue ->
                var ref = DOMAIN_PREF
                var defaultDomain = DEFAULT_API_DOMAIN
                if (useHotmanga) {
                    ref = HOTMANGA_DOMAIN_PREF
                    defaultDomain = DEFAULT_HOTMANGA_API_DOMAIN
                }

                var domain = domainRegex.find(newValue as String)?.value
                if (domain.isNullOrBlank()) {
                    domain = defaultDomain
                }
                preferences.edit().putString(ref, domain).commit()
                if (useHotmanga) {
                    hotmangaApiUrl = "https://$domain"
                } else {
                    apiUrl = "https://$domain"
                }
                true
            }
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = if (useHotmanga) HOTMANGA_WEB_DOMAIN_PREF else WEB_DOMAIN_PREF
            title = "网页版域名"
            summary = "webview中使用的域名，拷贝默认为$DEFAULT_WEB_DOMAIN，热辣默认为$DEFAULT_HOTMANGA_WEB_DOMAIN"
            setDefaultValue(DEFAULT_WEB_DOMAIN)
            setOnPreferenceChangeListener { _, newValue ->
                var ref = WEB_DOMAIN_PREF
                var defaultDomain = DEFAULT_WEB_DOMAIN
                if (useHotmanga) {
                    ref = HOTMANGA_WEB_DOMAIN_PREF
                    defaultDomain = DEFAULT_HOTMANGA_WEB_DOMAIN
                }

                var domain = domainRegex.find(newValue as String)?.value
                if (domain.isNullOrBlank()) {
                    domain = defaultDomain
                }
                preferences.edit().putString(ref, domain).commit()
                if (useHotmanga) {
                    hotmangaWebUrl = "https://$domain"
                } else {
                    webUrl = "https://$domain"
                }
                true
            }
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = USE_HOTMANGA_REF
            title = "切换为热辣漫画"
            summary = "关闭时适配拷贝漫画，开启时适配热辣漫画\n切换后，请重启应用再修改其他设置"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                useHotmanga = newValue as Boolean
                preferences.edit().putBoolean(USE_HOTMANGA_REF, useHotmanga).apply()
                true
            }
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = OVERSEAS_CDN_PREF
            title = "图片使用“港台及海外线路”"
            summary = "关闭时使用“大陆用户线路”，已阅读章节需要清空缓存才能生效"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                val useOverseasCdn = newValue as Boolean
                preferences.edit().putBoolean(OVERSEAS_CDN_PREF, useOverseasCdn).apply()
                apiHeaders = apiHeaders.newBuilder().setRegion(useOverseasCdn).build()
                true
            }
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = QUALITY_PREF
            title = "图片分辨率（像素）"
            summary = "阅读过的部分需要清空缓存才能生效\n当前值：%s"
            entries = QUALITY
            entryValues = QUALITY
            setDefaultValue(QUALITY[0])
            setOnPreferenceChangeListener { _, newValue ->
                imageQuality = newValue as String
                preferences.edit().putString(QUALITY_PREF, imageQuality).apply()
                true
            }
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = WEBP_PREF
            title = "使用 WebP 图片格式"
            summary = "默认开启，可以节省网站流量"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                val useWebp = newValue as Boolean
                preferences.edit().putBoolean(WEBP_PREF, useWebp).apply()
                apiHeaders = apiHeaders.newBuilder().setWebp(useWebp).build()
                true
            }
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = CHAPTER_API_RATE_PREF
            title = "章节图片请求频率限制"
            summary =
                "此值影响向章节图片api时发起连接请求的数量。需要重启软件以生效。\n当前值：每分钟 %s 个请求"
            entries = RATE_ARRAY
            entryValues = RATE_ARRAY
            setDefaultValue("15")
            setOnPreferenceChangeListener { _, newValue ->
                val rateLimit = newValue as String
                preferences.edit().putString(CHAPTER_API_RATE_PREF, rateLimit).apply()
                true
            }
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = SC_TITLE_PREF
            title = "将作品标题及简介转换为简体中文"
            summary = "修改后，已添加漫画需要迁移才能更新信息"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                convertToSc = newValue as Boolean
                preferences.edit().putBoolean(SC_TITLE_PREF, convertToSc).apply()
                MangaDto.convertToSc = convertToSc
                true
            }
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = COMMENTS_PREF
            title = "章末吐槽页"
            summary = "修改后，已加载的章节需要清除章节缓存才能生效。"
            setDefaultValue(false)
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = if (useHotmanga) HOTMANGA_USERNAME_PREF else USERNAME_PREF
            title = "用户名"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                fetchTokenState = 0
                val usernameRef = if (useHotmanga) HOTMANGA_USERNAME_PREF else USERNAME_PREF
                preferences.edit().putString(usernameRef, newValue as String).commit()
                true
            }
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = if (useHotmanga) HOTMANGA_PASSWORD_PREF else PASSWORD_PREF
            title = "密码"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                fetchTokenState = 0
                val passwordRef = if (useHotmanga) HOTMANGA_PASSWORD_PREF else PASSWORD_PREF
                preferences.edit().putString(passwordRef, newValue as String).commit()
                true
            }
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = if (useHotmanga) HOTMANGA_TOKEN_PREF else TOKEN_PREF
            title = "用户登录Token"
            summary =
                "输入登录Token即可以搜索阅读仅登录用户可见的漫画；可点击下方的“更新Token”来自动获取/更新"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val token = newValue as String
                fetchTokenState = 0
                val tokenRef = if (useHotmanga) HOTMANGA_TOKEN_PREF else TOKEN_PREF
                preferences.edit().putString(tokenRef, token).apply()
                true
            }
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = "update_token"
            title = "更新Token"
            summary = "填写用户名及密码设置后，点击此选项尝试登录以更新Token"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                val usernameRef = if (useHotmanga) HOTMANGA_USERNAME_PREF else USERNAME_PREF
                val passwordRef = if (useHotmanga) HOTMANGA_PASSWORD_PREF else PASSWORD_PREF
                val tokenRef = if (useHotmanga) HOTMANGA_TOKEN_PREF else TOKEN_PREF

                if (fetchTokenState == 1) {
                    Toast.makeText(screen.context, "正在尝试登录，请勿反复点击", Toast.LENGTH_SHORT)
                        .show()
                    return@setOnPreferenceChangeListener false
                } else if (fetchTokenState == 2) {
                    Toast.makeText(
                        screen.context,
                        "Token已经成功更新，返回重进刷新",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnPreferenceChangeListener false
                } else if (fetchTokenState == 3) {
                    Toast.makeText(screen.context, "Token仍有效，不需要更新", Toast.LENGTH_SHORT)
                        .show()
                    return@setOnPreferenceChangeListener false
                } else if (fetchTokenState == -1) {
                    Toast.makeText(
                        screen.context,
                        "Token更新失败，请再次尝试或检查用户名/密码是否有误",
                        Toast.LENGTH_SHORT,
                    ).show()
                    // return@setOnPreferenceChangeListener false
                }
                val username = preferences.getString(usernameRef, "")!!
                val password = preferences.getString(passwordRef, "")!!
                if (username.isBlank() || password.isBlank()) {
                    Toast.makeText(
                        screen.context,
                        "请在扩展设置界面输入用户名和密码",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnPreferenceChangeListener false
                }
                Toast.makeText(screen.context, "开始尝试登录以更新Token", Toast.LENGTH_SHORT).show()
                fetchTokenState = 1
                thread {
                    runBlocking {
                        try {
                            if (!verifyToken(preferences.getString(tokenRef, "")!!)) {
                                val results = fetchToken(username, password)
                                if (results["success"] != "false") {
                                    preferences.edit().putString(tokenRef, results["token"]!!).apply()
                                    showToast(screen.context, "Token已经成功更新，返回重进刷新")
                                } else {
                                    showToast(screen.context, "Token获取失败，${results["message"]}")
                                    fetchTokenState = -1
                                }
                                fetchTokenState = 2
                            } else {
                                showToast(screen.context, "Token仍有效，不需要更新")
                                fetchTokenState = 3
                            }
                        } catch (e: Throwable) {
                            fetchTokenState = 0
                            Log.e("CopyMangas", "failed to fetch token", e)
                        }
                    }
                }
                false
            }
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = BROWSER_USER_AGENT_PREF
            title = "浏览器User Agent"
            summary = "高级设置，不建议修改\n重启生效"
            setDefaultValue(DEFAULT_BROWSER_USER_AGENT)
            setOnPreferenceChangeListener { _, newValue ->
                val userAgent = newValue as String
                preferences.edit().putString(BROWSER_USER_AGENT_PREF, userAgent).apply()
                apiHeaders = apiHeaders.newBuilder().setUserAgent(userAgent).build()
                true
            }
        }.let(screen::addPreference)
    }

    companion object {
        private const val DOMAIN_PREF = "api_domainZ"
        private const val WEB_DOMAIN_PREF = "web_domainZ"
        private const val HOTMANGA_DOMAIN_PREF = "hotmanga_domainZ"
        private const val HOTMANGA_WEB_DOMAIN_PREF = "hotmanga_web_domainZ"
        private const val OVERSEAS_CDN_PREF = "changeCDNZ"
        private const val QUALITY_PREF = "imageQualityZ"
        private const val SC_TITLE_PREF = "showSCTitleZ"
        private const val WEBP_PREF = "useWebpZ"
        private const val COMMENTS_PREF = "comments"

        private const val CHAPTER_API_RATE_PREF = "chapterApiRateZ"

        private const val USERNAME_PREF = "usernameZ"
        private const val HOTMANGA_USERNAME_PREF = "hotmangaUsernameZ"
        private const val PASSWORD_PREF = "passwordZ"
        private const val HOTMANGA_PASSWORD_PREF = "hotmangaPasswordZ"
        private const val TOKEN_PREF = "tokenZ"
        private const val HOTMANGA_TOKEN_PREF = "hotmangaTokenZ"
        private const val USE_HOTMANGA_REF = "useHotmangaZ"

//        private const val VERSION_PREF = "versionZ"
        private const val BROWSER_USER_AGENT_PREF = "browserUserAgent"

        private const val DEFAULT_API_DOMAIN = "api.copy4000.com"
        private const val DEFAULT_WEB_DOMAIN = "www.copy4000.com"
        private const val DEFAULT_HOTMANGA_API_DOMAIN = "mapi.hotmangasf.com"
        private const val DEFAULT_HOTMANGA_WEB_DOMAIN = "www.manga2026.xyz"

        private val QUALITY = arrayOf("800", "1200", "1500")
        private val RATE_ARRAY = (5..60 step 5).map { i -> i.toString() }.toTypedArray()

//        private const val DEFAULT_VERSION = "2.3.0"
        private const val DEFAULT_BROWSER_USER_AGENT =
            "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/103.0.5060.53 Mobile Safari/537.36"

        private const val PAGE_SIZE = 20
        private const val CHAPTER_PAGE_SIZE = 100

        const val COMMENTS_FLAG = "COMMENTS"
    }
}
