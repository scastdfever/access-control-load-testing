package com.feverup

import io.gatling.javaapi.core.CoreDsl.*
import io.gatling.javaapi.core.ScenarioBuilder
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import io.gatling.javaapi.http.HttpProtocolBuilder
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

enum class EnvironmentData(val environment: EnvironmentName, val partnerId: Int, val mainplanId: Int) {
    LOCAL(EnvironmentName.LOCAL, 198, 105544),
    STAGING(EnvironmentName.STAGING, 62, 278979);

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

object Headers {
    const val X_LOAD_TEST: String = "X-Load-Test"
}

object Config {
    const val JSON_MIME_TYPE: String = "application/json"
    const val AGENT_HEADER: String = "Gatling/3.9.5 (Kotlin)"

    val token: String = "Token ${getEnvironmentVariable("USER_TOKEN")}"
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
            EnvironmentName.STAGING -> 1
        }
}

fun getEnvironmentVariable(variable: String): String {
    return System.getenv(variable)
        ?: throw IllegalArgumentException("Environment variable '$variable' not found.")
}

object Utils {
    fun readFileResource(fileName: String): String {
        return Utils::class.java.classLoader.getResource(fileName)
            ?.readText()
            ?: throw IllegalArgumentException("File resource '$fileName' not found.")
    }

    fun getCodesDataFromCsv(ticketId: Int): List<Map<String, Any>> {
        val ticketIdColumn = 3
        val csvContent = readFileResource("plancodes.csv")
        val map =
            csvContent.lines()
                .drop(1) // Skip header
                .filter { it.isNotBlank() }
                .map { line -> line.split(",") }
                .filter { cells -> cells[ticketIdColumn] == ticketId.toString() } // Filter by ticketId
                .map { cells -> mapOf("code" to cells[0].trim()) }

        return map
    }
}

class CodesValidationSimulation : Simulation() {
    private val httpProtocol: HttpProtocolBuilder =
        http
            .baseUrl(Config.baseUrl)
            .acceptHeader(Config.JSON_MIME_TYPE)
            .contentTypeHeader(Config.JSON_MIME_TYPE)
            .authorizationHeader(Config.token)
            .header(Headers.X_LOAD_TEST, "true")
            .userAgentHeader(Config.AGENT_HEADER)

    private val allCodesData = Utils.getCodesDataFromCsv(14162721)

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
        println("Gatling simulation is about to start.")
    }

    override fun after() {
        println("Gatling simulation has finished.")
    }

    init {
        println(
            "Properties loaded: " +
                "Environment: ${Config.environment.value}, " +
                "Service: ${Config.service.value}, " +
                "Base URL: ${Config.baseUrl}, " +
                "Virtual Users: ${Config.vus}, " +
                "Endpoint: ${Config.endpoint}, " +
                "Token: ${Config.token.take(4)}... (truncated for security)"
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
