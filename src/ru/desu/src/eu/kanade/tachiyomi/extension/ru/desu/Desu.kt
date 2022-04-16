package eu.kanade.tachiyomi.extension.ru.desu

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.text.DecimalFormat

class Desu : HttpSource() {
    override val name = "Desu"

    override val baseUrl = "https://desu.me"

    override val lang = "ru"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    override fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", "Tachiyomi")
        add("Referer", baseUrl)
    }

    private fun mangaPageFromJSON(jsonStr: String, next: Boolean): MangasPage {
        val mangaList = json.parseToJsonElement(jsonStr).jsonArray
            .map {
                SManga.create().apply {
                    mangaFromJSON(it.jsonObject, false)
                }
            }

        return MangasPage(mangaList, next)
    }

    private fun SManga.mangaFromJSON(obj: JsonObject, chapter: Boolean) {
        val id = obj["id"]!!.jsonPrimitive.int

        url = "/$id"
        title = obj["name"]!!.jsonPrimitive.content
            .split(" / ")
            .first()
        thumbnail_url = obj["image"]!!.jsonObject["original"]!!.jsonPrimitive.content

        val ratingValue = obj["score"]!!.jsonPrimitive.floatOrNull ?: 0F
        val ratingStar = when {
            ratingValue > 9.5 -> "★★★★★"
            ratingValue > 8.5 -> "★★★★✬"
            ratingValue > 7.5 -> "★★★★☆"
            ratingValue > 6.5 -> "★★★✬☆"
            ratingValue > 5.5 -> "★★★☆☆"
            ratingValue > 4.5 -> "★★✬☆☆"
            ratingValue > 3.5 -> "★★☆☆☆"
            ratingValue > 2.5 -> "★✬☆☆☆"
            ratingValue > 1.5 -> "★☆☆☆☆"
            ratingValue > 0.5 -> "✬☆☆☆☆"
            else -> "☆☆☆☆☆"
        }

        val rawAgeStop = when (obj["adult"]!!.jsonPrimitive.int) {
            1 -> "18+"
            else -> "0+"
        }

        val rawTypeStr = when (obj["kind"]!!.jsonPrimitive.content) {
            "manga" -> "Манга"
            "manhwa" -> "Манхва"
            "manhua" -> "Маньхуа"
            "comics" -> "Комикс"
            "one_shot" -> "Ваншот"
            else -> "Манга"
        }

        var altName = ""

        if (obj["synonyms"]!!.jsonPrimitive.contentOrNull != null) {
            altName = "Альтернативные названия:\n" +
                obj["synonyms"]!!.jsonPrimitive.content
                    .replace("|", " / ") +
                "\n\n"
        }

        description = obj["russian"]!!.jsonPrimitive.content + "\n" +
            ratingStar + " " + ratingValue +
            " (голосов: " +
            obj["score_users"]!!.jsonPrimitive.int +
            ")\n" + altName +
            obj["description"]!!.jsonPrimitive.content

        genre = if (chapter) {
            obj["genres"]!!.jsonArray
                .map { it.jsonObject["russian"]!!.jsonPrimitive.content }
                .plusElement(rawTypeStr)
                .plusElement(rawAgeStop)
                .joinToString()
        } else {
            obj["genres"]!!.jsonPrimitive.content + ", " + rawTypeStr + ", " + rawAgeStop
        }

        status = when (obj["status"]!!.jsonPrimitive.content) {
            "ongoing" -> SManga.ONGOING
            "released" -> SManga.COMPLETED
            //  "copyright" -> SManga.LICENSED  Hides available chapters!
            else -> SManga.UNKNOWN
        }
    }

    override fun popularMangaRequest(page: Int) =
        GET("$baseUrl$API_URL/?limit=50&order=popular&page=$page")

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl$API_URL/?limit=50&order=updated&page=$page")

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var url = "$baseUrl$API_URL/?limit=20&page=$page"
        val types = mutableListOf<Type>()
        val genres = mutableListOf<Genre>()
        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is OrderBy -> url += "&order=" + arrayOf("popular", "updated", "name")[filter.state]
                is TypeList -> filter.state.forEach { type -> if (type.state) types.add(type) }
                is GenreList -> filter.state.forEach { genre -> if (genre.state) genres.add(genre) }
            }
        }

        if (types.isNotEmpty()) {
            url += "&kinds=" + types.joinToString(",") { it.id }
        }
        if (genres.isNotEmpty()) {
            url += "&genres=" + genres.joinToString(",") { it.id }
        }
        if (query.isNotEmpty()) {
            url += "&search=$query"
        }
        return GET(url)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val res = json.parseToJsonElement(response.body!!.string()).jsonObject
        val obj = res["response"]!!.jsonArray
        val nav = res["pageNavParams"]!!.jsonObject
        val count = nav["count"]!!.jsonPrimitive.int
        val limit = nav["limit"]!!.jsonPrimitive.int
        val page = nav["page"]!!.jsonPrimitive.int

        return mangaPageFromJSON(obj.toString(), count > page * limit)
    }

    private fun titleDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + API_URL + manga.url, headers)
    }

    // Workaround to allow "Open in browser" use the real URL.
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(titleDetailsRequest(manga))
            .asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response).apply { initialized = true }
            }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + "/manga" + manga.url, headers)
    }
    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val obj = json.parseToJsonElement(response.body!!.string())
            .jsonObject["response"]!!
            .jsonObject

        mangaFromJSON(obj, true)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val obj = json.parseToJsonElement(response.body!!.string())
            .jsonObject["response"]!!
            .jsonObject

        val cid = obj["id"]!!.jsonPrimitive.int

        return obj["chapters"]!!.jsonObject["list"]!!.jsonArray.map {
            val chapterObj = it.jsonObject
            val ch = chapterObj["ch"]!!.jsonPrimitive.float
            val fullNumStr = "${chapterObj["vol"]!!.jsonPrimitive.int}. Глава " + DecimalFormat("#,###.##").format(ch).replace(",", ".")
            val title = chapterObj["title"]!!.jsonPrimitive.contentOrNull ?: ""

            SChapter.create().apply {
                name = "$fullNumStr $title"
                url = "/$cid/chapter/${chapterObj["id"]!!.jsonPrimitive.int}"
                chapter_number = ch
                date_upload = chapterObj["date"]!!.jsonPrimitive.long * 1000L
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + API_URL + manga.url, headers)
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + API_URL + chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val obj = json.parseToJsonElement(response.body!!.string())
            .jsonObject["response"]!!
            .jsonObject

        return obj["pages"]!!.jsonObject["list"]!!.jsonArray
            .mapIndexed { i, jsonEl ->
                Page(i, "", jsonEl.jsonObject["img"]!!.jsonPrimitive.content)
            }
    }

    override fun imageUrlParse(response: Response) =
        throw UnsupportedOperationException("This method should not be called!")

    private fun searchMangaByIdRequest(id: String): Request {
        return GET("$baseUrl$API_URL/$id", headers)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_SLUG_SEARCH)) {
            val realQuery = query.removePrefix(PREFIX_SLUG_SEARCH)
            client.newCall(searchMangaByIdRequest(realQuery))
                .asObservableSuccess()
                .map { response ->
                    val details = mangaDetailsParse(response)
                    details.url = "/$realQuery"
                    MangasPage(listOf(details), false)
                }
        } else {
            client.newCall(searchMangaRequest(page, query, filters))
                .asObservableSuccess()
                .map { response ->
                    searchMangaParse(response)
                }
        }
    }

    private class OrderBy : Filter.Select<String>(
        "Сортировка",
        arrayOf("Популярность", "Дата", "Имя")
    )

    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Жанр", genres)

    private class TypeList(types: List<Type>) : Filter.Group<Type>("Тип", types)

    private class Type(name: String, val id: String) : Filter.CheckBox(name)

    private class Genre(name: String, val id: String) : Filter.CheckBox(name)

    override fun getFilterList() = FilterList(
        OrderBy(),
        TypeList(getTypeList()),
        GenreList(getGenreList())
    )

    private fun getTypeList() = listOf(
        Type("Манга", "manga"),
        Type("Манхва", "manhwa"),
        Type("Маньхуа", "manhua"),
        Type("Ваншот", "one_shot"),
        Type("Комикс", "comics")
    )

    private fun getGenreList() = listOf(
        Genre("Безумие", "Dementia"),
        Genre("Боевые искусства", "Martial Arts"),
        Genre("Вампиры", "Vampire"),
        Genre("Военное", "Military"),
        Genre("Гарем", "Harem"),
        Genre("Демоны", "Demons"),
        Genre("Детектив", "Mystery"),
        Genre("Детское", "Kids"),
        Genre("Дзёсей", "Josei"),
        Genre("Додзинси", "Doujinshi"),
        Genre("Драма", "Drama"),
        Genre("Игры", "Game"),
        Genre("Исторический", "Historical"),
        Genre("Комедия", "Comedy"),
        Genre("Космос", "Space"),
        Genre("Магия", "Magic"),
        Genre("Машины", "Cars"),
        Genre("Меха", "Mecha"),
        Genre("Музыка", "Music"),
        Genre("Пародия", "Parody"),
        Genre("Повседневность", "Slice of Life"),
        Genre("Полиция", "Police"),
        Genre("Приключения", "Adventure"),
        Genre("Психологическое", "Psychological"),
        Genre("Романтика", "Romance"),
        Genre("Самураи", "Samurai"),
        Genre("Сверхъестественное", "Supernatural"),
        Genre("Сёдзе", "Shoujo"),
        Genre("Сёдзе Ай", "Shoujo Ai"),
        Genre("Сейнен", "Seinen"),
        Genre("Сёнен", "Shounen"),
        Genre("Сёнен Ай", "Shounen Ai"),
        Genre("Смена пола", "Gender Bender"),
        Genre("Спорт", "Sports"),
        Genre("Супер сила", "Super Power"),
        Genre("Триллер", "Thriller"),
        Genre("Ужасы", "Horror"),
        Genre("Фантастика", "Sci-Fi"),
        Genre("Фэнтези", "Fantasy"),
        Genre("Хентай", "Hentai"),
        Genre("Школа", "School"),
        Genre("Экшен", "Action"),
        Genre("Этти", "Ecchi"),
        Genre("Юри", "Yuri"),
        Genre("Яой", "Yaoi")
    )

    companion object {
        const val PREFIX_SLUG_SEARCH = "slug:"

        private const val API_URL = "/manga/api"
    }
}