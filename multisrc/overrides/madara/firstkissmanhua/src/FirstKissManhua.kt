package eu.kanade.tachiyomi.extension.en.firstkissmanhua

import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Page
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class FirstKissManhua : Madara(
    "1st Kiss Manhua",
    "https://1stkissmanhua.com",
    "en",
    SimpleDateFormat("d MMM yyyy", Locale.US)
) {
    private val rateLimitInterceptor = RateLimitInterceptor(1, 2, TimeUnit.SECONDS)

    override val client = network.cloudflareClient.newBuilder()
        .addNetworkInterceptor(rateLimitInterceptor)
        .build()

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headersBuilder().add("Referer", "https://1stkissmanga.com").build())
}
