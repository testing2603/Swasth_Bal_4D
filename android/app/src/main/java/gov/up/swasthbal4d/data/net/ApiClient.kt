package gov.up.swasthbal4d.data.net

import gov.up.swasthbal4d.BuildConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalSerializationApi::class)
val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

class ApiClient(private val tokenProvider: () -> String?) {

    val baseUrl: String = BuildConfig.API_BASE_URL

    private fun client(timeoutSec: Long) = OkHttpClient.Builder()
        .connectTimeout(timeoutSec, TimeUnit.SECONDS)
        .readTimeout(timeoutSec * 3, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
            tokenProvider()?.let { req.header("Authorization", "Bearer $it") }
            chain.proceed(req.build())
        }
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    private fun retrofit(timeoutSec: Long) = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client(timeoutSec))
        .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ApiService::class.java)

    /** Normal calls. Generous: a sleeping free server can take ~60 s to wake up. */
    val api: ApiService = retrofit(30)

    /** Short-timeout calls used during a visit: if the network is slow we fall back to a
     *  PROVISIONAL result instead of making the worker wait (design 7.2). */
    val quick: ApiService = retrofit(6)

    /** Absolute URL for media paths returned by the server ("/api/v1/facility/media/..."). */
    fun absolute(path: String): String {
        val root = baseUrl.substringBefore("/api/v1/")
        return if (path.startsWith("http")) path else root + path
    }
}

/** Turns HTTP / network errors into a short Hindi-friendly message for the UI. */
fun Throwable.userMessage(): String = when (this) {
    is HttpException -> {
        val raw = response()?.errorBody()?.string()
        val detail = runCatching {
            val el = AppJson.parseToJsonElement(raw ?: "").jsonObject["detail"]
            el?.let { d -> runCatching { d.jsonPrimitive.content }.getOrElse { d.jsonObject["message"]?.jsonPrimitive?.content } }
        }.getOrNull()
        detail ?: "सर्वर त्रुटि (${code()})"
    }
    is java.io.IOException -> "नेटवर्क नहीं है - ऑफ़लाइन काम जारी है"
    else -> message ?: "कुछ गलत हुआ"
}
