package com.feverup

import io.gatling.javaapi.core.CoreDsl.*
import io.gatling.javaapi.core.ScenarioBuilder
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import io.gatling.javaapi.http.HttpProtocolBuilder
import java.io.IOException
import java.util.concurrent.TimeUnit
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
    const val PARTNER_ID: Int = 198
    const val JSON_MIME_TYPE: String = "application/json"
    const val AGENT_HEADER: String = "Gatling/3.9.5 (Kotlin)"

    val token: String = "Token ${getEnvironmentVariable("USER_TOKEN")}"
    val environment: EnvironmentName = EnvironmentName.fromString(getEnvironmentVariable("LT_AC_ENVIRONMENT"))
    val service: ServiceName = ServiceName.fromString(getEnvironmentVariable("LT_AC_SERVICE"))

    val baseUrl: String =
        when (environment to service) {
            EnvironmentName.LOCAL to ServiceName.ACCESS_CONTROL -> "http://localhost:8020"
            EnvironmentName.LOCAL to ServiceName.FEVER2 -> "http://localhost:8002"
            EnvironmentName.STAGING to ServiceName.ACCESS_CONTROL -> "https://access-control.staging.feverup.com"
            EnvironmentName.STAGING to ServiceName.FEVER2 -> "https://fever2.staging.feverup.com"
            else -> throw IllegalArgumentException("Invalid environment-service combination: $environment-$service")
        }

    val endpoint: String =
        when (service) {
            ServiceName.ACCESS_CONTROL -> "/api/1.1/partners/{partnerId}/codes/validate"
            ServiceName.FEVER2 -> "/b2b/2.0/partners/{partnerId}/codes/validate/"
        }.replace("{partnerId}", PARTNER_ID.toString())

    val vus: Int =
        when (environment) {
            EnvironmentName.LOCAL -> 10
            EnvironmentName.STAGING -> 20
        }
}

fun getEnvironmentVariable(variable: String): String {
    return System.getenv(variable)
        ?: throw IllegalArgumentException("Environment variable '$variable' not found.")
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

    private val allCodesData = prepareCodesData()

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
                                """{\"code\":\"#{codeMap.code}\",\"main_plan_ids\":[105544],\"connectivity_mode\":\"offline\"}"""
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
                "Virtual Users: $Config.vus, " +
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

    private data class Code(val code: String)

    private data class ProcessResult(val exitCode: Int, val output: String)

    private fun prepareCodesData(limit: Int? = null): List<Map<String, Any>> {
        if (Config.environment == EnvironmentName.STAGING) {
            return emptyList()
        }

        println("Preparing database and fetching codes for the test...")

        val prepareSuccess = prepareCodesForValidation()
        if (!prepareSuccess) {
            throw IllegalStateException("Failed to prepare codes for validation. Aborting simulation.")
        }

        val codes = fetchCodesFromDatabase(limit)
        if (codes.isEmpty()) {
            throw IllegalStateException("No codes fetched from the database. Aborting simulation.")
        }
        println("${codes.size} codes fetched for the test.")

        return codes.map { mapOf("code" to it.code) }
    }

    private fun fetchCodesFromDatabase(limit: Int? = null): List<Code> {
        val limitMessage = limit?.toString() ?: "all"
        println("Fetching $limitMessage codes from the database...")
        val limitClause = limit?.let { "LIMIT $it" } ?: ""
        val command =
            arrayOf(
                "docker",
                "exec",
                "fever2-postgres",
                "psql",
                "-U",
                "fever_user",
                "-d",
                "fever",
                "-c",
                "COPY (SELECT code FROM core_plancodes WHERE main_plan_id = 105544 $limitClause) TO STDOUT;"
            )
        val result = executeCommand(command)
        if (result.exitCode != 0) {
            System.err.println("Failed to fetch codes. Exit code: ${result.exitCode}")
            return emptyList()
        }
        return result.output.lines().mapNotNull { it.trim().takeIf { it.isNotEmpty() } }.map(::Code)
    }

    private fun prepareCodesForValidation(): Boolean {
        println("Resetting code validation state in the database...")
        val command =
            arrayOf(
                "docker",
                "exec",
                "fever2-postgres",
                "psql",
                "-U",
                "fever_user",
                "-d",
                "fever",
                "-c",
                "UPDATE core_plancodes SET extra = 'is_validated => \"False\"' WHERE main_plan_id = 105544;"
            )
        val result = executeCommand(command)
        return result.exitCode == 0
    }

    private fun executeCommand(command: Array<String>): ProcessResult {
        return try {
            val process = ProcessBuilder(*command).start()
            val output = process.inputStream.bufferedReader().readText()
            process.errorStream.bufferedReader().readText()
            val exited = process.waitFor(10, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                throw InterruptedException("Command timed out")
            }
            ProcessResult(process.exitValue(), output)
        } catch (e: IOException) {
            System.err.println("Error executing command: ${e.message}")
            ProcessResult(-1, "")
        } catch (e: InterruptedException) {
            System.err.println("Command execution interrupted: ${e.message}")
            Thread.currentThread().interrupt()
            ProcessResult(-1, "")
        }
    }
}
