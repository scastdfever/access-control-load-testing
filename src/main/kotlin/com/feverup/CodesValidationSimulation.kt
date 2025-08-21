package com.feverup

import io.gatling.javaapi.core.CoreDsl.*
import io.gatling.javaapi.core.ScenarioBuilder
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import io.gatling.javaapi.http.HttpProtocolBuilder
import io.netty.handler.codec.http.HttpHeaderNames
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.jvm.java
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

object Headers {
    const val X_LOAD_TEST: String = "X-Load-Test"
}

object Config {
    private const val PARTNER_ID: Int = 198
    private val props = Properties()
    const val JSON_MIME_TYPE: String = "application/json"
    const val AGENT_HEADER: String = "Gatling/3.9.5 (Kotlin)"

    init {
        val configFile: String =
            System.getProperty("properties.file")
                ?: throw IllegalArgumentException("Missing system property: properties.file")
        val inputStream =
            Config::class.java.classLoader.getResourceAsStream(configFile)
                ?: throw RuntimeException("Could not find config file: $configFile")
        props.load(inputStream)
        println("✅ Loaded config from: $configFile")
    }

    val baseUrl: String = getProperty(props, "host.url")
    val vus: Int = getProperty(props, "test.vus").toInt()
    val endpoint: String = getProperty(props, "test.endpoint").replace("{partnerId}", PARTNER_ID.toString())
    val token: String = "Token ${getEnvironmentVariable("USER_TOKEN")}"
    val environment: EnvironmentName = EnvironmentName.fromString(getEnvironmentVariable("LT_AC_ENVIRONMENT"))
}

fun getProperty(properties: Properties, property: String): String {
    return when {
        properties.containsKey(property) -> properties.getProperty(property)
        else -> throw IllegalArgumentException("Property '$property' not found in properties.")
    }
}

fun getEnvironmentVariable(variable: String): String {
    return System.getenv(variable)
        ?: throw IllegalArgumentException("Environment variable '$variable' not found.")
}

class CodesValidationSimulation : Simulation() {
    // 1. Configuration: Define constants and system properties for flexibility.
    private val vus = Config.vus

    // 2. HTTP Protocol Setup: Configure common HTTP settings for all requests.
    private val httpProtocol: HttpProtocolBuilder =
        http
            .baseUrl(Config.baseUrl)
            .headers(
                mapOf(
                    HttpHeaderNames.ACCEPT to Config.JSON_MIME_TYPE,
                    HttpHeaderNames.CONTENT_TYPE to Config.JSON_MIME_TYPE,
                    HttpHeaderNames.AUTHORIZATION to Config.token,
                    Headers.X_LOAD_TEST to "true"
                )
            )
            .userAgentHeader(Config.AGENT_HEADER)

    // 3. Data Preparation: Fetch all codes from the database once during initialization.
    private val allCodesData = prepareCodesData()

    // 4. Scenario Definition: Describes the actions a virtual user will perform.
    private val validateCodeScenario: ScenarioBuilder =
        scenario("Validate Partitioned Codes Per User")
            // This exec block runs for each user and calculates their unique chunk of data.
            .exec { session ->
                // Gatling's userId is 1-based.
                val userId = session.userId().toInt()
                val chunkSize = allCodesData.size / vus
                val startIndex = (userId - 1) * chunkSize

                // The last user takes the remaining chunk to handle cases where the data isn't
                // perfectly divisible.
                val endIndex =
                    if (userId == vus) {
                        allCodesData.size
                    } else {
                        startIndex + chunkSize
                    }

                // Get the sublist for this specific user. Handle cases where there are more users than
                // data.
                val userChunk =
                    if (startIndex >= allCodesData.size) {
                        emptyList()
                    } else {
                        allCodesData.subList(startIndex, min(endIndex, allCodesData.size))
                    }

                // Set this user's unique chunk into a session attribute.
                session.set("myCodeChunk", userChunk)
            }
            // Use foreach to loop over the user's unique chunk of codes.
            .foreach("#{myCodeChunk}", "codeMap")
            .on(
                exec(
                    http("POST_validate_code_#{codeMap.code}") // Use the code in the request name
                        // for uniqueness
                        .post(Config.endpoint)
                        .body(
                            StringBody(
                                // Use Gatling's Expression Language to build the body from the
                                // "codeMap" attribute.
                                """
                        {
                          "code": "#{codeMap.code}",
                          "main_plan_ids": [105544],
                          "connectivity_mode": "offline"
                        }
                        """
                                    .trimIndent()
                            )
                        )
                        .check(
                            status().`is`(200) // Check for a successful HTTP 200 OK response.
                        )
                )
            )

    // 5. Lifecycle Hooks: Optional hooks for logging or other actions.
    override fun before() {
        println("Gatling simulation is about to start.")
    }

    override fun after() {
        println("Gatling simulation has finished.")
    }

    // 6. Injection Profile & Assertions: Define the load and set success criteria.
    init {
        println(
            "Properties loaded: " +
                "Environment: ${Config.environment.value}, " +
                "Base URL: ${Config.baseUrl}, " +
                "Virtual Users: $vus, " +
                "Endpoint: ${Config.endpoint}, " +
                "Token: ${Config.token.take(4)}... (truncated for security)"
        )

        setUp(
            validateCodeScenario.injectOpen(
                atOnceUsers(vus) // Inject all virtual users at the same time.
            )
        )
            .protocols(httpProtocol)
            .assertions(
                global()
                    .responseTime()
                    .max()
                    .lt(1000), // Assert that the max response time is less than 1000 ms.
                global()
                    .failedRequests()
                    .count()
                    .`is`(0L) // Assert that there are zero failed requests.
            )
    }

    // --- Helper Functions ---

    private data class Code(val code: String)

    private data class ProcessResult(val exitCode: Int, val output: String)

    /** Prepares the database and fetches codes, returning them as a List of Maps. */
    private fun prepareCodesData(limit: Int? = null): List<Map<String, Any>> {
        if (Config.environment == EnvironmentName.STAGING) {
            return emptyList() // Skip preparation in staging environment.
        }

        println("Preparing database and fetching codes for the test...")

        // Step 1: Reset validation state in the database.
        val prepareSuccess = prepareCodesForValidation()
        if (!prepareSuccess) {
            throw IllegalStateException("Failed to prepare codes for validation. Aborting simulation.")
        }

        // Step 2: Fetch codes from the database.
        val codes = fetchCodesFromDatabase(limit)
        if (codes.isEmpty()) {
            throw IllegalStateException("No codes fetched from the database. Aborting simulation.")
        }
        println("${codes.size} codes fetched for the test.")

        // Step 3: Convert the list of codes to Gatling's feeder format (a List of Maps).
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
            // It's important to consume the error stream as well to prevent the process from hanging.
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
