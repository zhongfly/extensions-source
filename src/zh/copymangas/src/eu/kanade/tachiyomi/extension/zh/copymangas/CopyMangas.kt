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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.serializer
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import rx.Observable
import rx.Single
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class CopyMangas : HttpSource(), ConfigurableSource {
    override val name = "拷贝漫画"
    override val lang = "zh"
    override val supportsLatest = true

    val json: Json = Injekt.get()
    private val preferences by getPreferencesLazy()

    private var convertToSc = preferences.getBoolean(SC_TITLE_PREF, false)
    private var useHotmanga = preferences.getBoolean(USE_HOTMANGA_REF, false) // false = 拷贝，true = 热辣

    private var apiUrl = getDomain(DOMAIN_PREF, DEFAULT_API_DOMAIN)
    private var webUrl = getDomain(WEB_DOMAIN_PREF, DEFAULT_WEB_DOMAIN)
    override val baseUrl = "https://" + DEFAULT_WEB_DOMAIN

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

    private fun getUrl(type: String): String {
        return when (type) {
            "api" -> if (useHotmanga) hotmangaApiUrl else apiUrl
            "web" -> if (useHotmanga) hotmangaWebUrl else webUrl
            else -> throw IllegalArgumentException("Unknown URL type: $type")
        }
    }

    private val chapterRatelimitRegex = Regex("""/chapter2?/""")
    private val imageQualityRegex = Regex("""(c|h)(800|1200|1500)x\.""")

    private val trustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return emptyArray()
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        }
    }
    private val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, arrayOf(trustManager), SecureRandom())
    }

    private val chapterInterceptor = RateLimitInterceptor(
        null,
        preferences.getString(CHAPTER_API_RATE_PREF, "15")!!.toInt(),
        61,
        TimeUnit.SECONDS,
    )

    private fun responseIntercepter(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.toString().contains(getUrl("api"))) return chain.proceed(request)

        val response = chain.proceed(request)
        if (!response.isSuccessful) return response
        if (response.header("Content-Type") != "application/json") {
            throw Exception("返回数据错误，不是json")
        } else {
            val result = response.peekBody(Long.MAX_VALUE).parseAs<ResultMessageDto>()
            if (result.code != 200) {
                throw Exception("返回数据错误:${result.message}")
            }
            return response
        }
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .addInterceptor { chain ->
            val url = chain.request().url.toString()
            when {
                url.contains(chapterRatelimitRegex) -> chapterInterceptor.intercept(chain)
                else -> chain.proceed(chain.request())
            }
        }
        .addInterceptor(CommentsInterceptor)
        .addInterceptor(::responseIntercepter)
        .build()

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

    private var apiHeaders = Headers.Builder()
        .setUserAgent(preferences.getString(BROWSER_USER_AGENT_PREF, DEFAULT_BROWSER_USER_AGENT)!!)
        .setWebp(preferences.getBoolean(WEBP_PREF, true))
        .setRegion(preferences.getBoolean(OVERSEAS_CDN_PREF, false))
        .setToken("")
        .add("version", "2025.08.08")
        .add("platform", "1")
        .build()

    private fun fetchToken(username: String, password: String): Map<String, String> {
        val results =
            mutableMapOf<String, String>("success" to "false", "message" to "", "token" to "")
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            results["message"] = "用户名或密码为空"
            return results
        }
        try {
            val salt = (1000..9999).random().toString()
            val passwordEncoded =
                Base64.encodeToString("$password-$salt".toByteArray(), Base64.DEFAULT).trim()
            val formBody: RequestBody = FormBody.Builder()
                .addEncoded("username", username)
                .addEncoded("password", passwordEncoded)
                .addEncoded("salt", salt)
                .build()
            val headers = apiHeaders.newBuilder().setToken().build()
            val response = client.newCall(POST("${getUrl("api")}/api/v3/login", headers, formBody)).execute()
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

    private fun verifyToken(token: String): Boolean {
        if (token.isNullOrBlank()) {
            return false
        }
        var result = false
        try {
            val headers = apiHeaders.newBuilder()
                .setToken(token)
                .build()
            val response = client.newCall(GET("${getUrl("api")}/api/v3/member/info", headers)).execute()
            result = (response.code == 200)
        } catch (e: Exception) {
            Log.e("CopyMangas", "failed to verify token", e)
        }
        return result
    }

    init {
        MangaDto.convertToSc = preferences.getBoolean(SC_TITLE_PREF, false)
    }

    override fun popularMangaRequest(page: Int): Request {
        val offset = PAGE_SIZE * (page - 1)
        return GET(
            "${getUrl("api")}/api/v3/comics?limit=$PAGE_SIZE&offset=$offset&free_type=1&ordering=-popular&theme=&top=",
            apiHeaders,
        )
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val page = response.parseAs<ResultDto<ListDto<MangaDto>>>().results
        val hasNextPage = page.offset + page.limit < page.total
        return MangasPage(page.list.map { it.toSManga() }, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val offset = PAGE_SIZE * (page - 1)
        return GET(
            "${getUrl("api")}/api/v3/comics?limit=$PAGE_SIZE&offset=$offset&free_type=1&ordering=-datetime_updated&theme=&top=",
            apiHeaders,
        )
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val headersBuilder = apiHeaders.newBuilder()
        val offset = PAGE_SIZE * (page - 1)
        val builder = getUrl("api").toHttpUrl().newBuilder()
            .addQueryParameter("limit", "$PAGE_SIZE")
            .addQueryParameter("offset", "$offset")
        if (query.isNotBlank()) {
            builder.addPathSegments("api/v3/search/comic")
                .addQueryParameter("q", query)
            filters.filterIsInstance<SearchFilter>().firstOrNull()?.addQuery(builder)
            // builder.addQueryParameter("q_type", "")
            headersBuilder.setToken(preferences.getString(if (useHotmanga) HOTMANGA_TOKEN_PREF else TOKEN_PREF, "")!!)
        } else {
            builder.addPathSegments("api/v3/comics")
            filters.filterIsInstance<CopyMangaFilter>().forEach {
                if (it !is SearchFilter) {
                    it.addQuery(builder)
                }
            }
        }
        return Request.Builder().url(builder.build()).headers(headersBuilder.build()).build()
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val page = response.parseAs<ResultDto<ListDto<MangaDto>>>().results
        val hasNextPage = page.offset + page.limit < page.total
        return MangasPage(page.list.map { it.toSManga() }, hasNextPage)
    }

    override fun getMangaUrl(manga: SManga) = getUrl("web") + manga.url

    override fun mangaDetailsRequest(manga: SManga) =
        GET("${getUrl("api")}/api/v3/comic2/${manga.url.removePrefix(MangaDto.URL_PREFIX)}?platform=1&_update=true", apiHeaders)

    override fun mangaDetailsParse(response: Response): SManga =
        response.parseAs<ResultDto<MangaWrapperDto>>().results.toSMangaDetails()

    private fun ArrayList<SChapter>.fetchChapterGroup(manga: String, key: String, name: String) {
        val result = ArrayList<SChapter>(0)
        var offset = 0
        var hasNextPage = true
        val groupName = when {
            key.equals("default") -> ""
            convertToSc -> ChineseUtils.toSimplified(name)
            else -> name
        }
        while (hasNextPage) {
            val response = client.newCall(
                GET(
                    "${getUrl("api")}/api/v3/comic/$manga/group/$key/chapters?limit=$CHAPTER_PAGE_SIZE&offset=$offset&_update=true",
                    apiHeaders,
                ),
            ).execute()
            val chapters = response.parseAs<ResultDto<ListDto<ChapterDto>>>().results
            result.ensureCapacity(chapters.total)
            chapters.list.mapTo(result) { it.toSChapter(groupName) }
            offset += CHAPTER_PAGE_SIZE
            hasNextPage = offset < chapters.total
        }
        addAll(result.asReversed())
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        Single.create<List<SChapter>> {
            val result = ArrayList<SChapter>()
            val response = client.newCall(mangaDetailsRequest(manga)).execute()
            val groups = response.parseAs<ResultDto<MangaWrapperDto>>().results.groups!!.values
            val mangaSlug = manga.url.removePrefix(MangaDto.URL_PREFIX)
            for (group in groups) {
                result.fetchChapterGroup(mangaSlug, group.path_word, group.name)
            }
            it.onSuccess(result)
        }.toObservable()

    override fun chapterListRequest(manga: SManga) =
        throw UnsupportedOperationException("Not used.")

    override fun chapterListParse(response: Response) =
        throw UnsupportedOperationException("Not used.")

    override fun getChapterUrl(chapter: SChapter) = getUrl("web") + chapter.url.replace("/chapter2/", "/chapter/")

    // 新版 API 中间是 /chapter2/ 并且返回值需要排序
    override fun pageListRequest(chapter: SChapter): Request {
        val url = if (useHotmanga) {
            "$hotmangaApiUrl/api/v3${chapter.url.replace("/chapter2/", "/chapter/")}"
        } else {
            "$apiUrl/api/v3${chapter.url}"
        }
        return GET("$url?platform=1&_update=true", apiHeaders)
    }

    override fun pageListParse(response: Response): List<Page> {
        val result = response.parseAs<ResultDto<ChapterPageListWrapperDto>>().results
        val images = result.chapter.contents
        val orders = result.chapter.words
        val pageList = if (orders.isNullOrEmpty()) {
            images.mapIndexedTo(ArrayList(images.size + 1)) { i, it -> Page(i, imageUrl = it.url) }
        } else {
            images.withIndex().sortedBy { orders[it.index] }.map { it.value }.mapIndexedTo(ArrayList(images.size + 1)) { i, it ->
                Page(i, imageUrl = it.url)
            }
        }
        return pageList
    }

    override fun imageUrlParse(response: Response) =
        throw UnsupportedOperationException("Not used.")

    private var imageQuality = preferences.getString(QUALITY_PREF, QUALITY[0])
    override fun imageRequest(page: Page): Request {
        var imageUrl = page.imageUrl!!
        imageUrl = imageQualityRegex.replace(imageUrl, "c${imageQuality}x.")
        return GET(imageUrl, headers)
    }

    inline fun <reified T> ResponseBody.parseAs(): T = json.decodeFromStream(serializer(), this.byteStream())

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

    private var genres: Array<Param> = emptyArray()
    private var isFetchingGenres = false

    override fun getFilterList(): FilterList {
        val genreFilter = if (genres.isEmpty()) {
            fetchGenres()
            Filter.Header("点击“重置”尝试刷新题材分类")
        } else {
            GenreFilter(genres)
        }
        return FilterList(
            SearchFilter(),
            Filter.Separator(),
            Filter.Header("分类（搜索文本时无效）"),
            genreFilter,
            TopFilter(),
            SortFilter(),
        )
    }

    private fun fetchGenres() {
        if (genres.isNotEmpty() || isFetchingGenres) {
            return
        }
        isFetchingGenres = true
        thread {
            try {
                val response = client.newCall(
                    GET(
                        "${getUrl("api")}/api/v3/theme/comic/count?limit=500&offset=0&free_type=1",
                        apiHeaders,
                    ),
                ).execute()
                val list = response.parseAs<ListDto<KeywordDto>>().list.sortedBy { it.name }
                val result = ArrayList<Param>(list.size + 1).apply { add(Param("全部", "")) }
                genres = list.mapTo(result) { it.toParam() }.toTypedArray()
            } catch (e: Exception) {
                Log.e("CopyManga", "failed to fetch genres", e)
            } finally {
                isFetchingGenres = false
            }
        }
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
                apiUrl = "https://$domain"
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
                webUrl = "https://$domain"
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
                if (username.isNullOrBlank() || password.isNullOrBlank()) {
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

        private const val DEFAULT_API_DOMAIN = "api.2025copy.com"
        private const val DEFAULT_WEB_DOMAIN = "www.2025copy.com"
        private const val DEFAULT_HOTMANGA_API_DOMAIN = "mapi.hotmangasd.com"
        private const val DEFAULT_HOTMANGA_WEB_DOMAIN = "www.relamanhua.org"

        private val QUALITY = arrayOf("800", "1200", "1500")
        private val RATE_ARRAY = (5..60 step 5).map { i -> i.toString() }.toTypedArray()

//        private const val DEFAULT_VERSION = "2.3.0"
        private const val DEFAULT_BROWSER_USER_AGENT =
            "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/103.0.5060.53 Mobile Safari/537.36"

        private const val PAGE_SIZE = 20
        private const val CHAPTER_PAGE_SIZE = 100
    }
}
