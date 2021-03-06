package eu.kanade.tachiyomi.extension.zh.baozimanhua

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class Baozimanhua : ParsedHttpSource() {

    override val name = "Baozimanhua"

    override val baseUrl = "https://cn.baozimh.com"

    override val lang = "zh"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun chapterListSelector(): String = "div.pure-g[id^=chapter] > div"

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = document.select(chapterListSelector()).map { element ->
            chapterFromElement(element)
        }
        // chapters are listed oldest to newest in the source
        return chapters.reversed()
    }

    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            url = element.select("a").attr("href").trim()
            name = element.text()
        }
    }

    override fun popularMangaSelector(): String = "div.pure-g div a.comics-card__poster"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            url = element.attr("href")!!.trim()
            title = element.attr("title")!!.trim()
            thumbnail_url = element.select("> amp-img").attr("src")!!.trim()
        }
    }

    override fun popularMangaNextPageSelector() = throw java.lang.UnsupportedOperationException("Not used.")

    override fun popularMangaRequest(page: Int): Request = GET("https://www.baozimh.com/classify", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector()).map { element ->
            popularMangaFromElement(element)
        }
        return MangasPage(mangas, mangas.size == 36)
    }

    override fun latestUpdatesSelector(): String = "div.pure-g div a.comics-card__poster"

    override fun latestUpdatesFromElement(element: Element): SManga {
        return popularMangaFromElement(element)
    }

    override fun latestUpdatesNextPageSelector() = throw java.lang.UnsupportedOperationException("Not used.")

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/list/new", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector()).map { element ->
            popularMangaFromElement(element)
        }
        return MangasPage(mangas, mangas.size == 12)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.select("h1.comics-detail__title").text().trim()
            thumbnail_url = document.select("div.pure-g div > amp-img").attr("src").trim()
            author = document.select("h2.comics-detail__author").text().trim()
            description = document.select("p.comics-detail__desc").text().trim()
            status = when (document.selectFirst("div.tag-list > span.tag").text().trim()) {
                "?????????" -> SManga.ONGOING
                "?????????" -> SManga.COMPLETED
                "?????????" -> SManga.ONGOING
                "?????????" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = document.select("section.comic-contain > amp-img").mapIndexed() { index, element ->
            Page(index, imageUrl = element.attr("src").trim())
        }
        return pages
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException("Not used.")

    override fun searchMangaSelector() = throw java.lang.UnsupportedOperationException("Not used.")

    override fun searchMangaFromElement(element: Element) = throw java.lang.UnsupportedOperationException("Not used.")

    override fun searchMangaNextPageSelector() = throw java.lang.UnsupportedOperationException("Not used.")

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return if (query.startsWith(ID_SEARCH_PREFIX)) {
            val id = query.removePrefix(ID_SEARCH_PREFIX)
            client.newCall(searchMangaByIdRequest(id))
                .asObservableSuccess()
                .map { response -> searchMangaByIdParse(response, id) }
        } else {
            super.fetchSearchManga(page, query, filters)
        }
    }

    private fun searchMangaByIdRequest(id: String) = GET("$baseUrl/comic/$id", headers)

    private fun searchMangaByIdParse(response: Response, id: String): MangasPage {
        val sManga = mangaDetailsParse(response)
        sManga.url = "/comic/$id"
        return MangasPage(listOf(sManga), false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // impossible to search a manga and use the filters
        return if (query.isNotEmpty()) {
            GET("$baseUrl/search?q=$query", headers)
        } else {
            lateinit var tag: String
            lateinit var region: String
            lateinit var status: String
            lateinit var start: String
            filters.forEach { filter ->
                when (filter) {
                    is TagFilter -> {
                        tag = filter.toUriPart()
                    }
                    is RegionFilter -> {
                        region = filter.toUriPart()
                    }
                    is StatusFilter -> {
                        status = filter.toUriPart()
                    }
                    is StartFilter -> {
                        start = filter.toUriPart()
                    }
                }
            }
            GET("$baseUrl/classify?type=$tag&region=$region&state=$status&filter=$start&page=$page")
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        // Normal search
        return if (response.request.url.encodedPath.startsWith("search?")) {
            val mangas = document.select(popularMangaSelector()).map { element ->
                popularMangaFromElement(element)
            }
            MangasPage(mangas, false)
            // Filter search
        } else {
            val mangas = document.select(popularMangaSelector()).map { element ->
                popularMangaFromElement(element)
            }
            MangasPage(mangas, mangas.size == 36)
        }
    }

    override fun getFilterList() = FilterList(
        Filter.Header("?????????????????????????????????"),
        TagFilter(),
        RegionFilter(),
        StatusFilter(),
        StartFilter()
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class TagFilter : UriPartFilter(
        "??????",
        arrayOf(
            Pair("??????", "all"),
            Pair("??????", "dushi"),
            Pair("??????", "mouxian"),
            Pair("??????", "rexie"),
            Pair("??????", "lianai"),
            Pair("??????", "danmei"),
            Pair("??????", "wuxia"),
            Pair("??????", "gedou"),
            Pair("??????", "kehuan"),
            Pair("??????", "mohuan"),
            Pair("??????", "tuili"),
            Pair("??????", "xuanhuan"),
            Pair("??????", "richang"),
            Pair("??????", "shenghuo"),
            Pair("??????", "gaoxiao"),
            Pair("??????", "xiaoyuan"),
            Pair("??????", "qihuan"),
            Pair("??????", "mengxi"),
            Pair("??????", "chuanyue"),
            Pair("??????", "hougong"),
            Pair("??????", "zhanzheng"),
            Pair("??????", "lishi"),
            Pair("??????", "juqing"),
            Pair("??????", "tongren"),
            Pair("??????", "jingji"),
            Pair("??????", "lizhi"),
            Pair("??????", "zhiyu"),
            Pair("??????", "jijia"),
            Pair("??????", "chunai"),
            Pair("??????", "meishi"),
            Pair("??????", "egao"),
            Pair("??????", "nuexin"),
            Pair("??????", "dongzuo"),
            Pair("??????", "liangxian"),
            Pair("??????", "weimei"),
            Pair("??????", "fuchou"),
            Pair("??????", "naodong"),
            Pair("??????", "gongdou"),
            Pair("??????", "yundong"),
            Pair("??????", "lingyi"),
            Pair("??????", "gufeng"),
            Pair("??????", "quanmou"),
            Pair("??????", "jiecao"),
            Pair("??????", "mingxing"),
            Pair("??????", "anhei"),
            Pair("??????", "shehui"),
            Pair("????????????", "yinlewudao"),
            Pair("??????", "dongfang"),
            Pair("AA", "aa"),
            Pair("??????", "xuanyi"),
            Pair("?????????", "qingxiaoshuo"),
            Pair("??????", "bazong"),
            Pair("??????", "luoli"),
            Pair("??????", "zhandou"),
            Pair("??????", "liangsong"),
            Pair("??????", "yuri"),
            Pair("?????????", "danuzhu"),
            Pair("??????", "huanxiang"),
            Pair("??????", "shaonu"),
            Pair("??????", "shaonian"),
            Pair("??????", "xingzhuanhuan"),
            Pair("??????", "zhongsheng"),
            Pair("??????", "hanman"),
            Pair("??????", "qita")
        )
    )

    private class RegionFilter : UriPartFilter(
        "??????",
        arrayOf(
            Pair("??????", "all"),
            Pair("??????", "cn"),
            Pair("??????", "jp"),
            Pair("??????", "kr"),
            Pair("??????", "en")
        )
    )

    private class StatusFilter : UriPartFilter(
        "??????",
        arrayOf(
            Pair("??????", "all"),
            Pair("?????????", "serial"),
            Pair("?????????", "pub")
        )
    )

    private class StartFilter : UriPartFilter(
        "????????????",
        arrayOf(
            Pair("??????", "*"),
            Pair("ABCD", "ABCD"),
            Pair("EFGH", "EFGH"),
            Pair("IJKL", "IJKL"),
            Pair("MNOP", "MNOP"),
            Pair("QRST", "QRST"),
            Pair("UVW", "UVW"),
            Pair("XYZ", "XYZ"),
            Pair("0-9", "0-9")
        )
    )

    companion object {
        const val ID_SEARCH_PREFIX = "id:"
    }
}
