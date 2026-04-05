package com.traintracker

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.UUID
import java.util.concurrent.TimeUnit

interface ConfirmTktApi {
    @POST("api/v1/availability/fetchAvailability")
    suspend fun fetchRaw(
        @Query("trainNo") trainNo: String,
        @Query("travelClass") travelClass: String,
        @Query("quota") quota: String,
        @Query("sourceStationCode") sourceStationCode: String,
        @Query("destinationStationCode") destinationStationCode: String,
        @Query("dateOfJourney") dateOfJourney: String,
        @Query("enableTG") enableTG: Boolean = true,
        @Query("tGPlan") tGPlan: String = "CTG-A36",
        @Query("showTGPrediction") showTGPrediction: Boolean = false,
        @Query("tgColor") tgColor: String = "DEFAULT",
        @Query("showPredictionGlobal") showPredictionGlobal: Boolean = true,
        @Query("showNewMealOptions") showNewMealOptions: Boolean = true,
        @Query("showNewAlternates") showNewAlternates: Boolean = false,
        @Query("showNewAltText") showNewAltText: Boolean = true
    ): ResponseBody

    @GET("api/v1/trains/getTrainInfo")
    suspend fun getTrainInfo(
        @Query("trainNo") trainNo: String
    ): ResponseBody
}

interface ErailApi {
    @GET("data.aspx")
    suspend fun getScheduleData(
        @Query("Action") action: String = "GetTrainTimeTable",
        @Query("Password") password: String = "2012",
        @Query("Data1") trainNo: String
    ): ResponseBody
}

object ApiClient {
    private const val BASE_URL  = "https://cttrainsapi.confirmtkt.com/"
    private const val ERAIL_URL = "https://erail.in/"
    private val DEVICE_ID: String = UUID.randomUUID().toString()

    val gson = GsonBuilder().setLenient().create()

    private val confirmTktClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Apikey", "ct-web!2\$")
                .addHeader("Clientid", "ct-web")
                .addHeader("Deviceid", DEVICE_ID)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("Origin", "https://www.confirmtkt.com")
                .addHeader("Referer", "https://www.confirmtkt.com/")
                .addHeader("Sec-Fetch-Dest", "empty")
                .addHeader("Sec-Fetch-Mode", "cors")
                .addHeader("Sec-Fetch-Site", "same-site")
                .addHeader("User-Agent",
                    "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .build()
            chain.proceed(request)
        }.build()

    private val erailClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
                .addHeader("Accept", "*/*")
                .build()
            chain.proceed(req)
        }.build()

    val api: ConfirmTktApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(confirmTktClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(ConfirmTktApi::class.java)

    val erailApi: ErailApi = Retrofit.Builder()
        .baseUrl(ERAIL_URL)
        .client(erailClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(ErailApi::class.java)

    suspend fun getDepartureTime(trainNo: String, fromStation: String): Pair<String?, String> {
        val debug = StringBuilder()

        // Method 1 — ConfirmTkt getTrainInfo
        try {
            val raw = api.getTrainInfo(trainNo).string()
            debug.appendLine("CT raw(150): ${raw.take(150)}")
            val time = parseConfirmTktSchedule(raw, fromStation)
            if (!time.isNullOrEmpty()) return Pair(time, debug.toString())
            debug.appendLine("CT: $fromStation not found")
        } catch (e: Exception) {
            debug.appendLine("CT error: ${e.message?.take(100)}")
        }

        // Method 2 — Erail data.aspx
        try {
            val raw = erailApi.getScheduleData(trainNo = trainNo).string()
            debug.appendLine("Erail raw(150): ${raw.take(150)}")
            val time = parseErailSchedule(raw, fromStation)
            if (!time.isNullOrEmpty()) return Pair(time, debug.toString())
            debug.appendLine("Erail: $fromStation not found")
        } catch (e: Exception) {
            debug.appendLine("Erail error: ${e.message?.take(100)}")
        }

        return Pair(null, debug.toString())
    }

    private fun parseConfirmTktSchedule(raw: String, fromStation: String): String? {
        return try {
            val el = gson.fromJson(raw, com.google.gson.JsonElement::class.java)
            val arr = when {
                el.isJsonArray -> el.asJsonArray
                el.isJsonObject -> {
                    val obj = el.asJsonObject
                    obj.getAsJsonArray("data")
                        ?: obj.getAsJsonArray("stationList")
                        ?: obj.getAsJsonArray("stations")
                        ?: obj.getAsJsonArray("body")
                }
                else -> null
            } ?: return null

            for (item in arr) {
                if (!item.isJsonObject) continue
                val obj = item.asJsonObject
                val code = obj.get("stationCode")?.asString
                    ?: obj.get("stnCode")?.asString
                    ?: obj.get("code")?.asString
                    ?: continue
                if (code.equals(fromStation, ignoreCase = true)) {
                    return obj.get("departureTime")?.asString
                        ?: obj.get("depTime")?.asString
                        ?: obj.get("departure")?.asString
                }
            }
            null
        } catch (e: Exception) { null }
    }

    private fun parseErailSchedule(raw: String, fromStation: String): String? {
        // Erail pipe-separated: index1=StationCode, index4=DepTime
        val lines = raw.split("~")
        for (line in lines) {
            val parts = line.split("|")
            if (parts.size < 5) continue
            val code = parts.getOrNull(1)?.trim() ?: continue
            if (code.equals(fromStation, ignoreCase = true)) {
                val dep = parts.getOrNull(4)?.trim()
                if (!dep.isNullOrEmpty() && dep != "--" && dep.contains(":")) {
                    return dep
                }
            }
        }
        return null
    }
}
