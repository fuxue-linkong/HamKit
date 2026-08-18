package com.example.hamkit.data.satellite

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SatelliteDataSourceTest {

    private val urls = TleSourceUrls(
        satnogs = "https://tle.test/satnogs.3le",
        amateur = "https://tle.test/amateur.3le",
        active = "https://tle.test/active.3le",
        iss = "https://tle.test/iss.3le",
    )

    @Test
    fun `active failure rejects partial satnogs and amateur results`() = runBlocking {
        val dataSource = dataSource(
            responses = mapOf(
                urls.satnogs to HttpResponse(200, ISS_TLE),
                urls.amateur to HttpResponse(200, ISS_TLE),
                urls.active to HttpResponse(503, "temporarily unavailable"),
                urls.iss to HttpResponse(200, ISS_TLE),
            )
        )

        val error = try {
            dataSource.fetchAmateurTLEs()
            null
        } catch (e: IOException) {
            e
        }

        requireNotNull(error)
        assertTrue(error.message.orEmpty().contains("全量 active 卫星源下载失败"))
    }

    @Test
    fun `undersized active catalog rejects partial results`() = runBlocking {
        val dataSource = dataSource(
            responses = mapOf(
                urls.satnogs to HttpResponse(200, ISS_TLE),
                urls.amateur to HttpResponse(200, ISS_TLE),
                urls.active to HttpResponse(200, active3le(count = 1)),
                urls.iss to HttpResponse(200, ISS_TLE),
            )
        )

        val error = try {
            dataSource.fetchAmateurTLEs()
            null
        } catch (e: IOException) {
            e
        }

        requireNotNull(error)
        assertTrue(error.message.orEmpty().contains("全量 active 卫星源数据异常"))
    }

    @Test
    fun `complete active catalog is retained in merged result`() = runBlocking {
        val dataSource = dataSource(
            responses = mapOf(
                urls.satnogs to HttpResponse(200, ISS_TLE),
                urls.amateur to HttpResponse(200, ISS_TLE),
                urls.active to HttpResponse(
                    200,
                    active3le(count = SatelliteDataSource.MIN_ACTIVE_TLE_COUNT)
                ),
                urls.iss to HttpResponse(200, ISS_TLE),
            )
        )

        val result = dataSource.fetchAmateurTLEs()

        assertTrue(result.size >= SatelliteDataSource.MIN_ACTIVE_TLE_COUNT)
        assertTrue(result.any { it.tle.catnum == 10_000 && it.source == "ACTIVE" })
    }

    private fun dataSource(responses: Map<String, HttpResponse>): SatelliteDataSource {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val response = responses[chain.request().url.toString()]
                    ?: error("Unexpected request: ${chain.request().url}")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(response.statusCode)
                    .message(if (response.statusCode in 200..299) "OK" else "Error")
                    .body(response.body.toResponseBody())
                    .build()
            })
            .build()

        return SatelliteDataSource(
            sourceUrls = urls,
            client = client,
            activeClient = client,
            fetchAmsatStatus = { emptyMap() },
        )
    }

    private fun active3le(count: Int): String = buildString {
        repeat(count) { index ->
            val catalogNumber = 10_000 + index
            appendLine("ACTIVE-$catalogNumber")
            appendLine("1 ${String.format("%05d", catalogNumber)}U 26001A   26229.27403125  .00000123  00000+0  10000-3 0  9991")
            appendLine("2 ${String.format("%05d", catalogNumber)}  51.0000  73.0000 0010000 108.0000 302.0000 15.50000000 10000")
        }
    }

    private data class HttpResponse(
        val statusCode: Int,
        val body: String,
    )

    private companion object {
        const val ISS_TLE = """
            ISS (ZARYA)
            1 25544U 98067A   26227.79314638  .00005207  00000+0  10100-3 0  9998
            2 25544  51.6333   5.0907 0007602  50.2947 309.8710 15.49453975580997
        """
    }
}
