package eu.kanade.tachiyomi.extension.zh.dumanwu

import android.annotation.SuppressLint
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class Dumanwu : ParsedHttpSource() {
    override val lang = "zh"
    override val supportsLatest = true
    override val name = "读漫屋"
    override val baseUrl = "https://dumanwu1.com"
    override val client: OkHttpClient = network.client.newBuilder()
        .ignoreAllSSLErrors()
        .build()

    private fun OkHttpClient.Builder.ignoreAllSSLErrors(): OkHttpClient.Builder {
        val naiveTrustManager = @SuppressLint("CustomX509TrustManager")
        object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) = Unit
        }

        val insecureSocketFactory = SSLContext.getInstance("TLSv1.2").apply {
            val trustAllCerts = arrayOf<TrustManager>(naiveTrustManager)
            init(null, trustAllCerts, SecureRandom())
        }.socketFactory

        sslSocketFactory(insecureSocketFactory, naiveTrustManager)
        hostnameVerifier { _, _ -> true }
        return this
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/rank/5")
    override fun latestUpdatesSelector(): String = "ol.rank-list > li"
    override fun latestUpdatesNextPageSelector(): String? = null
    override fun latestUpdatesFromElement(element: Element): SManga = SManga.create().apply {
        title = element.selectFirst(".simple-info h2")!!.text()
        thumbnail_url = element.selectFirst("img.cartoon-poster")!!.attr("data-src")
        url = element.selectFirst("a[href]")!!.attr("href").removeSurrounding("/")
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/rank/1")
    override fun popularMangaSelector(): String = latestUpdatesSelector()
    override fun popularMangaNextPageSelector(): String? = null
    override fun popularMangaFromElement(element: Element): SManga = latestUpdatesFromElement(element)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val body = FormBody.Builder()
            .addEncoded("k", query)
            .build()
        return POST("$baseUrl/s", body = body)
    }
    override fun searchMangaParse(response: Response): MangasPage {
        val result = response.parseAs<ResponseDto<List<SearchMangaDto>?>>()
        if (result.code == "200") {
            val mangas = result.data!!
            return MangasPage(mangas.map { it.toSManga() }, false)
        } else {
            throw Exception(result.msg)
        }
    }
    override fun searchMangaSelector(): String = throw UnsupportedOperationException()
    override fun searchMangaNextPageSelector(): String? = null
    override fun searchMangaFromElement(element: Element): SManga = throw UnsupportedOperationException()

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/${manga.url}")
    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst(".banner-title")!!.text()
        thumbnail_url = document.selectFirst("img.banner-pic")!!.attr("data-src")
        author = formatAuthor(document.selectFirst(".author")!!.text())
        artist = author
        description = document.selectFirst(".introduction")!!.ownText().removeSurrounding("\"")
        status = SManga.UNKNOWN
    }

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapterElements = document.select(".chaplist-box li a")
        val chapters = mutableListOf<SChapter>()
        chapters.addAll(
            chapterElements.map {
                SChapter.create().apply {
                    url = it.attr("href").removeSuffix(".html")
                    name = it.text()
                }
            },
        )
        if (document.selectFirst(".chaplist-box > .chaplist-more") != null) {
            val mangaId = response.request.url.pathSegments.last()
            val body = FormBody.Builder()
                .addEncoded("id", mangaId)
                .build()
            val moreResponse = client.newCall(POST("$baseUrl/morechapter", body = body)).execute()
            val moreChapters = moreResponse.parseAs<ResponseDto<List<ChapterDto>>>().data
            chapters.addAll(
                moreChapters.map {
                    SChapter.create().apply {
                        url = "$mangaId/${it.chapterid}"
                        name = it.chaptername
                    }
                },
            )
        }
        return chapters
    }
    override fun chapterListSelector(): String = throw UnsupportedOperationException()
    override fun chapterFromElement(element: Element): SChapter = throw UnsupportedOperationException()

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/${chapter.url}.html")
    override fun pageListParse(document: Document): List<Page> {
        val encScript = document.selectFirst("script:containsData(eval)")!!.data()
        val id = document.selectFirst(".readerContainer[data-id]")!!.attr("data-id")
        val images = QuickJs.create().use {
            it.evaluate("var id=$id;$encScript;$decryptScript") as Array<*>
        }
        return images.mapIndexed { index, it ->
            Page(index, imageUrl = it.toString())
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    companion object {
        const val decryptScript = """
        function _0x1c72dc(_0x2ba890){
            var _0xf79727='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
            var _0xdd54cd,_0x4356aa,_0x3c6736,_0x444c73,_0x5799af,_0x582152,_0x420099,_0xe80067,_0x51d7ca=0,_0x367e8d=0,_0xc87c7='',_0x250acf=[];
            if(!_0x2ba890){
                return _0x2ba890;
            }
            _0x2ba890+='';
            do{
                _0x444c73=_0xf79727.indexOf(_0x2ba890.charAt(_0x51d7ca++));
                _0x5799af=_0xf79727.indexOf(_0x2ba890.charAt(_0x51d7ca++));
                _0x582152=_0xf79727.indexOf(_0x2ba890.charAt(_0x51d7ca++));
                _0x420099=_0xf79727.indexOf(_0x2ba890.charAt(_0x51d7ca++));
                _0xe80067=_0x444c73<<0x12|_0x5799af<<0xc|_0x582152<<0x6|_0x420099;
                _0xdd54cd=_0xe80067>>0x10&0xff;
                _0x4356aa=_0xe80067>>0x8&0xff;
                _0x3c6736=_0xe80067&0xff;
                if(_0x582152==64){
                    _0x250acf[_0x367e8d++]=String.fromCharCode(_0xdd54cd);
                }else if(_0x420099==64){
                    _0x250acf[_0x367e8d++]=String.fromCharCode(_0xdd54cd,_0x4356aa);
                }else{
                    _0x250acf[_0x367e8d++]=String.fromCharCode(_0xdd54cd,_0x4356aa,_0x3c6736);
                }
            }while(_0x51d7ca<_0x2ba890.length);
            _0xc87c7=_0x250acf.join('');
            return _0xc87c7;
        }
        var _0x2155fc=_0x1c72dc(__c0rst96)
        var key=['smkhy258', 'smkd95fv', 'md496952', 'cdcsdwq', 'vbfsa256', 'cawf151c', 'cd56cvda', '8kihnt9', 'dso15tlo', '5ko6plhy'][id]
        var keyLength=key.length;
        var _0x1e348e='';
        for(i=0;i<_0x2155fc.length;i++){
            k=i%keyLength;
            _0x1e348e+=String.fromCharCode(_0x2155fc.charCodeAt(i)^key.charCodeAt(k));
        }

        var imgs=JSON.parse(_0x1c72dc(_0x1e348e))
        imgs
        """
    }
}
