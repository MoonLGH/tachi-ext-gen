package eu.kanade.tachiyomi.extension.ru.comx

import android.webkit.CookieManager
import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ComX : ParsedHttpSource() {

    private val json: Json by injectLazy()

    override val name = "Com-x"

    override val baseUrl = "https://com-x.life"

    override val lang = "ru"

    override val supportsLatest = true
    private val cookieManager by lazy { CookieManager.getInstance() }
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor(RateLimitInterceptor(3))
        .addNetworkInterceptor { chain ->
            val originalRequest = chain.request()
            val response = chain.proceed(originalRequest)
            if (originalRequest.url.toString().contains(baseUrl) and (response.code == 404))
                throw Exception("HTTP error ${response.code}. ???????????????? Antibot, ???????????????????? ???????????? ?????????? ?? WebView")
            response
        }
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) =
                cookies.filter { it.matches(url) }.forEach {
                    cookieManager.setCookie(url.toString(), it.toString())
                }

            override fun loadForRequest(url: HttpUrl) =
                cookieManager.getCookie(url.toString())?.split("; ")
                    ?.mapNotNull { Cookie.parse(url, it) } ?: emptyList()
        })
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:77.0) Gecko/20100101 Firefox/78.0")
        .add("Referer", baseUrl + "/comix-read/")

    override fun popularMangaSelector() = "div.short"

    override fun latestUpdatesSelector() = "ul#content-load li.latest"

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/comix-read/page/$page/", headers)

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select(popularMangaSelector()).map { element ->
            popularMangaFromElement(element)
        }
        return MangasPage(mangas, document.select(".pagination__pages span").first().text().toInt() <= document.select(".pagination__pages a:last-child").first().text().toInt())
    }

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.thumbnail_url = baseUrl + element.select("img").first().attr("src")
        element.select(".readed__title a").first().let {
            manga.setUrlWithoutDomain(it.attr("href"))
            manga.title = it.text().split(" / ").first()
        }
        return manga
    }
    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select(latestUpdatesSelector()).map { element ->
            latestUpdatesFromElement(element)
        }
        return MangasPage(mangas, false)
    }

    override fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.thumbnail_url = baseUrl + element.select("img").first().attr("src")
        element.select("a.latest__title").first().let {
            manga.setUrlWithoutDomain(it.attr("href"))
            manga.title = it.text().split(" / ").first()
        }
        return manga
    }

    override fun popularMangaNextPageSelector(): Nothing? = null

    override fun latestUpdatesNextPageSelector(): Nothing? = null

    override fun searchMangaNextPageSelector(): Nothing? = null

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        /** val url = "$baseUrl/index.php?do=xsearch&searchCat=comix-read&page=$page".toHttpUrlOrNull()!!.newBuilder()
         (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
         when (filter) {
         is TypeList -> filter.state.forEach { type ->
         if (type.state) {
         url.addQueryParameter("field[type][${type.id}]", 1.toString())
         }
         }
         is PubList -> filter.state.forEach { publisher ->
         if (publisher.state) {
         url.addQueryParameter("subCat[]", publisher.id)
         }
         }
         is GenreList -> filter.state.forEach { genre ->
         if (genre.state) {
         url.addQueryParameter("field[genre][${genre.id}]", 1.toString())
         }
         }
         }
         }**/
        if (query.isNotEmpty()) {
            return POST(
                "$baseUrl/index.php?do=search&search_start=$page",
                body = FormBody.Builder()
                    .add("do", "search")
                    .add("subaction", "search")
                    .add("story", query)
                    .build(),
                headers = headers
            )
        }
        return GET("$baseUrl/comix-read/", headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div.page__grid").first()

        val manga = SManga.create()
        manga.title = infoElement.select(".page__title-original").text().split(" | ").first()
        manga.author = infoElement.select(".page__list li:contains(????????????????)").text()
        manga.genre = infoElement.select(".page__tags a").joinToString { it.text() }
        manga.status = parseStatus(infoElement.select(".page__list li:contains(????????????)").text())

        manga.description = infoElement.select(".page__text ").text()

        val src = infoElement.select(".img-wide img").attr("src")
        if (src.contains("://")) {
            manga.thumbnail_url = src
        } else {
            manga.thumbnail_url = baseUrl + src
        }
        return manga
    }

    private fun parseStatus(element: String): Int = when {
        element.contains("????????????????????????") ||
            element.contains(" ???? ") ||
            element.contains("??????????????") -> SManga.ONGOING
        element.contains("????????????") ||
            element.contains("??????????????") ||
            element.contains("?????? ??????") ||
            element.contains("?????????????????????? ??????????") -> SManga.COMPLETED

        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = throw NotImplementedError("Unused")

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val dataStr = document
            .toString()
            .substringAfter("window.__DATA__ = ")
            .substringBefore("</script>")
            .substringBeforeLast(";")

        val data = json.decodeFromString<JsonObject>(dataStr)
        val chaptersList = data["chapters"]?.jsonArray
        val chapters: List<SChapter>? = chaptersList?.map {
            val chapter = SChapter.create()
            chapter.name = it.jsonObject["title"]!!.jsonPrimitive.content
            chapter.date_upload = parseDate(it.jsonObject["date"]!!.jsonPrimitive.content)
            chapter.setUrlWithoutDomain("/readcomix/" + data["news_id"] + "/" + it.jsonObject["id"]!!.jsonPrimitive.content + ".html")
            chapter
        }
        return chapters ?: emptyList()
    }

    private val simpleDateFormat by lazy { SimpleDateFormat("dd.MM.yyyy", Locale.US) }
    private fun parseDate(date: String?): Long {
        date ?: return 0L
        return try {
            simpleDateFormat.parse(date)!!.time
        } catch (_: Exception) {
            Date().time
        }
    }

    override fun chapterFromElement(element: Element): SChapter =
        throw NotImplementedError("Unused")

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body!!.string()
        val baseImgUrl = "https://img.com-x.life/comix/"

        val beginTag = "\"images\":["
        val beginIndex = html.indexOf(beginTag)
        val endIndex = html.indexOf("]", beginIndex)

        val urls: List<String> = html.substring(beginIndex + beginTag.length, endIndex)
            .split(',').map {
                val img = it.replace("\\", "").replace("\"", "")
                baseImgUrl + img
            }

        val pages = mutableListOf<Page>()
        for (i in urls.indices) {
            pages.add(Page(i, "", urls[i]))
        }

        return pages
    }

    override fun pageListParse(document: Document): List<Page> {
        throw Exception("Not used")
    }

    override fun imageUrlParse(document: Document) = ""

    override fun imageRequest(page: Page): Request {
        return GET(page.imageUrl!!, headers)
    }
    /**
     private class CheckFilter(name: String, val id: String) : Filter.CheckBox(name)

     private class TypeList(types: List<CheckFilter>) : Filter.Group<CheckFilter>("?????? ??????????????", types)
     private class PubList(publishers: List<CheckFilter>) : Filter.Group<CheckFilter>("??????????????", publishers)
     private class GenreList(genres: List<CheckFilter>) : Filter.Group<CheckFilter>("??????????", genres)

     override fun getFilterList() = FilterList(
     TypeList(getTypeList()),
     PubList(getPubList()),
     GenreList(getGenreList()),
     )

     private fun getTypeList() = listOf(
     CheckFilter("??????????????", "1"),
     CheckFilter("?????? ??????", "2"),
     CheckFilter("?????????????????????? ??????????", "3"),
     CheckFilter("??????????????", "4"),
     )

     private fun getPubList() = listOf(
     CheckFilter("Marvel", "2"),
     CheckFilter("DC Comics", "14"),
     CheckFilter("Dark Horse", "7"),
     CheckFilter("IDW Publishing", "6"),
     CheckFilter("Image", "4"),
     CheckFilter("Vertigo", "8"),
     CheckFilter("Dynamite Entertainment", "10"),
     CheckFilter("Wildstorm", "5"),
     CheckFilter("Avatar Press", "11"),
     CheckFilter("Boom! Studios", "12"),
     CheckFilter("Top Cow", "9"),
     CheckFilter("Oni Press", "13"),
     CheckFilter("Valiant", "15"),
     CheckFilter("Icon Comics", "16"),
     CheckFilter("Manga", "3"),
     CheckFilter("Manhua", "45"),
     CheckFilter("Manhwa", "44"),
     CheckFilter("???????????? ??????????????", "18")
     )

     private fun getGenreList() = listOf(
     CheckFilter("Sci-Fi", "2"),
     CheckFilter("????????????????????", "3"),
     CheckFilter("??????????????????????", "4"),
     CheckFilter("????????????", "5"),
     CheckFilter("???????????? ??????????????????", "6"),
     CheckFilter("??????????????", "7"),
     CheckFilter("??????????????", "8"),
     CheckFilter("??????????????", "9"),
     CheckFilter("????????????????", "10"),
     CheckFilter("??????????", "11"),
     CheckFilter("??????????", "12"),
     CheckFilter("????????", "13"),
     CheckFilter("????????????", "14"),
     CheckFilter("????????????????????????", "15"),
     CheckFilter("??????????????????", "16"),
     CheckFilter("??????????????", "17"),
     CheckFilter("????????????????????", "18"),
     CheckFilter("????????????", "19"),
     CheckFilter("????????????????", "20"),
     CheckFilter("??????????????????", "21"),
     CheckFilter("??????????????", "22"),
     CheckFilter("?????????????? ????????????????????", "23"),
     CheckFilter("????????????????????????????", "24"),
     CheckFilter("????????", "25"),
     CheckFilter("????????????", "26"),
     CheckFilter("??????????????", "27"),
     CheckFilter("????????????????????????????", "28"),
     CheckFilter("????????????????????????????????", "29"),
     CheckFilter("??????????????????????????????????", "30"),
     CheckFilter("??????????????????????", "31"),
     CheckFilter("?????????????????????? ???? ??????????????", "32"),
     CheckFilter("????????????????????????????????????", "33"),
     CheckFilter("????????????", "34"),
     CheckFilter("????????????", "35"),
     CheckFilter("????????????????????", "36"),
     CheckFilter("????????????????????????", "37"),
     CheckFilter("??????????", "38"),
     CheckFilter("??????????????", "39"),
     CheckFilter("??????????????", "40"),
     CheckFilter("??????????", "41"),
     CheckFilter("??????????????????????????", "42"),
     CheckFilter("????????????????????", "43"),
     CheckFilter("??????????????", "44"),
     CheckFilter("??????????", "45"),
     CheckFilter("????????", "46"),
     CheckFilter("??????????????", "47"),
     CheckFilter("????????????", "66"),
     CheckFilter("??????????", "67"),
     CheckFilter("??????????", "68"),
     CheckFilter("??????????-????", "69"),
     CheckFilter("????????????????", "70"),
     CheckFilter("??????????????", "73"),
     CheckFilter("??????????", "74"),
     CheckFilter("????????", "76"),
     CheckFilter("??????", "77"),

     )**/
}
