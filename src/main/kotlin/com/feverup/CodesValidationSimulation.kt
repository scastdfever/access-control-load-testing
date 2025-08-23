package com.feverup

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.gatling.javaapi.core.CoreDsl.*
import io.gatling.javaapi.core.ScenarioBuilder
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import io.gatling.javaapi.http.HttpProtocolBuilder
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.math.min

enum class EnvironmentName(val value: String) {
    LOCAL("local"),
    STAGING("staging");

    companion object {
        fun fromString(value: String): EnvironmentName {
            return entries.find { it.value.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown environment: $value")
        }
    }
}

enum class EnvironmentData(
    val environment: EnvironmentName,
    val partnerId: Int,
    val mainplanId: Int,
    val sessionId: Int
) {
    LOCAL(EnvironmentName.LOCAL, 198, 105544, 232948),
    STAGING(EnvironmentName.STAGING, 62, 278979, 12552516);

    companion object {
        fun fromEnvironment(environment: EnvironmentName): EnvironmentData {
            return entries.find { it.environment == environment }
                ?: throw IllegalArgumentException("Unknown environment data for: $environment")
        }
    }
}

enum class ServiceName(val value: String) {
    ACCESS_CONTROL("access-control"),
    FEVER2("fever2");

    companion object {
        fun fromString(value: String): ServiceName {
            return entries.find { it.value.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown service: $value")
        }
    }
}

object Constants {
    const val X_LOAD_TEST: String = "X-Load-Test"
    const val JSON_MIME_TYPE: String = "application/json"
    const val AGENT_HEADER: String = "Gatling/3.14.3 (Kotlin)"
}

object Config {
    val fever2Token: String = "Bearer ${getEnvironmentVariable("FEVER2_TOKEN")}"
    val b2bToken: String = "B2BToken ${getEnvironmentVariable("B2B_TOKEN")}"
    val environment: EnvironmentName = EnvironmentName.fromString(getEnvironmentVariable("LT_AC_ENVIRONMENT"))
    val environmentData: EnvironmentData = EnvironmentData.fromEnvironment(environment)
    val service: ServiceName = ServiceName.fromString(getEnvironmentVariable("LT_AC_SERVICE"))

    val baseUrl: String =
        when (environment to service) {
            EnvironmentName.LOCAL to ServiceName.ACCESS_CONTROL -> "http://localhost:8020"
            EnvironmentName.LOCAL to ServiceName.FEVER2 -> "http://localhost:8002"
            EnvironmentName.STAGING to ServiceName.ACCESS_CONTROL -> "https://access-control-api.staging.feverup.com"
            EnvironmentName.STAGING to ServiceName.FEVER2 -> "https://staging.feverup.com"
            else -> throw IllegalArgumentException("Invalid environment-service combination: $environment-$service")
        }

    val endpoint: String =
        when (service) {
            ServiceName.ACCESS_CONTROL -> "/api/1.1/partners/{partnerId}/codes/validate"
            ServiceName.FEVER2 -> "/b2b/2.0/partners/{partnerId}/codes/validate/"
        }.replace("{partnerId}", environmentData.partnerId.toString())

    val vus: Int =
        when (environment) {
            EnvironmentName.LOCAL -> 10
            EnvironmentName.STAGING -> 6
        }
}

fun getEnvironmentVariable(variable: String): String {
    return System.getenv(variable)
        ?: throw IllegalArgumentException("Environment variable '$variable' not found.")
}

class CodesValidationSimulation : Simulation() {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)
    private val httpProtocol: HttpProtocolBuilder =
        http.baseUrl(Config.baseUrl)
            .acceptHeader(Constants.JSON_MIME_TYPE)
            .contentTypeHeader(Constants.JSON_MIME_TYPE)
            .authorizationHeader(Config.b2bToken)
            .header(Constants.X_LOAD_TEST, "true")
            .userAgentHeader(Constants.AGENT_HEADER)

    private lateinit var allCodesData: List<Map<String, String>>

    private val validateCodeScenario: ScenarioBuilder =
        scenario("Validate Partitioned Codes Per User")
            .exec { session ->
                val userId = session.userId().toInt()
                val chunkSize = allCodesData.size / Config.vus
                val startIndex = (userId - 1) * chunkSize

                val endIndex =
                    if (userId == Config.vus) {
                        allCodesData.size
                    } else {
                        startIndex + chunkSize
                    }

                val userChunk =
                    if (startIndex >= allCodesData.size) {
                        emptyList()
                    } else {
                        allCodesData.subList(startIndex, min(endIndex, allCodesData.size))
                    }

                session.set("myCodeChunk", userChunk)
            }
            .foreach("#{myCodeChunk}", "codeMap")
            .on(
                exec(
                    http("POST_validate_code_#{codeMap.code}")
                        .post(Config.endpoint)
                        .body(
                            StringBody(
                                """{"code":"#{codeMap.code}","main_plan_ids":[${Config.environmentData.mainplanId}]}"""
                            )
                        )
                        .check(
                            status().`is`(200)
                        )
                )
            )

    override fun before() {
        logger.info("Gatling simulation is about to start.")

        val preparer = CodesPreparer()
        val orders = 5
        val ticketsPerOrder = 10
        val codes = preparer.prepareCodes(Config.environmentData.sessionId, orders, ticketsPerOrder)

        allCodesData = codes.map { mapOf("code" to it) }

        logger.info("Prepared {} codes for validation.", codes.size)
    }

    override fun after() {
        logger.info("Gatling simulation has finished.")
    }

    init {
        logger.info(
            "Properties loaded: Environment: {}, Service: {}, Base URL: {}, Virtual Users: {}, Endpoint: {}, ",
            Config.environment.value, Config.service.value, Config.baseUrl, Config.vus, Config.endpoint
        )

        setUp(
            validateCodeScenario.injectOpen(
                atOnceUsers(Config.vus)
            )
        )
            .protocols(httpProtocol)
            .disablePauses()
            .assertions(
                global()
                    .responseTime()
                    .max()
                    .lt(1000),
                global()
                    .failedRequests()
                    .count()
                    .`is`(0L)
            )
    }
}

class CodesPreparer {
    private val gson: Gson = Gson()
    private val debug: Boolean = true
    private val httpClient: OkHttpClient = if (debug) clientWithLogging() else OkHttpClient()
    private val host: String = when (Config.environment) {
        EnvironmentName.LOCAL -> "http://localhost:8002"
        EnvironmentName.STAGING -> "https://staging.feverup.com"
    }
    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    private fun clientWithLogging(): OkHttpClient {
        return OkHttpClient()
            .newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                logger.info("Request: {}", request.url)
                val response = chain.proceed(request)
                logger.info("Response code: {}", response.code)

                response
            }
            .build()
    }

    fun prepareCodes(sessionId: Int, orders: Int, tickets: Int): List<String> {
        val codes = mutableListOf<String>()

        (1..orders).forEach { order ->
            val cartId = createCart(sessionId, tickets)
            prepareBook(cartId)
            val ticketId = bookCart(cartId)
            codes.addAll(getCodesFromTicket(ticketId))
            logger.info("Order {} created with ticket ID {} and {} codes.", order, ticketId, tickets)
        }

        return codes
    }

    fun createCart(sessionId: Int, ticketNumber: Int): UUID {
        val requestBody = JsonObject().apply {
            add("sessions", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("session_id", sessionId)
                    addProperty("ticket_number", ticketNumber)
                })
            })
        }.toString()

        httpClient.newCall(
            Request.Builder()
                .url("$host/api/4.2/cart/")
                .header("Authorization", Config.fever2Token)
                .post(requestBody.toRequestBodyWithMediaType())
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Unexpected status code $response")
            }

            val responseBody = response.body?.string() ?: throw Exception("Empty response body")
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)

            return UUID.fromString(jsonResponse.get("cart_id").asString)
        }
    }

    fun prepareBook(cartId: UUID): UUID {
        val requestBody = JsonObject().apply {
            addProperty("cart_id", cartId.toString())
        }.toString()

        httpClient.newCall(
            Request.Builder()
                .url("$host/api/4.2/book/prepare/")
                .header("Authorization", Config.fever2Token)
                .post(requestBody.toRequestBodyWithMediaType())
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Unexpected status code $response")
            }

            // No need to parse the body since the cart id is the same
            return cartId
        }
    }

    fun bookCart(cartId: UUID): Int {
        httpClient.newCall(
            Request.Builder()
                .url("$host/api/4.2/cart/$cartId/book/free/")
                .header("Authorization", Config.fever2Token)
                .post("".toRequestBodyWithMediaType())
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Unexpected status code $response")
            }

            val responseBody = response.body?.string() ?: throw Exception("Empty response body")
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)

            return jsonResponse.get("ticket_id").asInt
        }
    }

    fun getCodesFromTicket(ticketId: Int): List<String> {
        httpClient.newCall(
            Request.Builder()
                .url("$host/api/4.1/tickets/$ticketId/")
                .header("Authorization", Config.fever2Token)
                .get()
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Unexpected status code $response")
            }

            val responseBody = response.body?.string() ?: throw Exception("Empty response body")
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
            val codesJsonArray = jsonResponse.getAsJsonArray("codes")

            return codesJsonArray.map { it.asJsonObject.get("code").asString }
        }
    }

    companion object {
        fun String.toRequestBodyWithMediaType(): RequestBody {
            return toRequestBody("application/json".toMediaTypeOrNull())
        }
    }
}
