package com.feverup

import io.gatling.javaapi.core.CoreDsl.*
import io.gatling.javaapi.core.ScenarioBuilder
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import io.gatling.javaapi.http.HttpProtocolBuilder
import java.io.IOException
import java.util.*
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

object Config {
  const val JSON_MIME_TYPE: String = "application/json"
  const val AUTHORIZATION_HEADER: String = "Authorization"
  const val AGENT_HEADER: String = "Gatling/3.9.5 (Kotlin)"
  private const val PARTNER_ID: Int = 198
  private val props = Properties()

  val environment: EnvironmentName =
      EnvironmentName.fromString(
          System.getenv("LT_AC_ENVIRONMENT")
              ?: error("Missing environment variable: LT_AC_ENVIRONMENT"))

  private val configFile: String =
      System.getProperty("properties.file") ?: error("Missing system property: properties.file")

  init {
    val inputStream =
        Config::class.java.classLoader.getResourceAsStream(configFile)
            ?: throw RuntimeException("Could not find config file: $configFile")
    props.load(inputStream)
    println("✅ Loaded config from: $configFile")
  }

  val baseUrl: String = props.getProperty("host.url") ?: error("Missing property: host.url")

  val vus: Int = props.getProperty("test.vus")?.toInt() ?: error("Missing property: test.vus")

  private val token: String =
      System.getenv("USER_TOKEN") ?: error("Missing environment variable: USER_TOKEN")

  private val endpoint: String =
      props.getProperty("test.endpoint") ?: error("Missing property: test.endpoint")

  val finalEndpoint: String = endpoint.replace("{partnerId}", PARTNER_ID.toString())
  val b2bToken: String = "Token $token"
}

class CodesValidationSimulation : Simulation() {

  // 1. Configuration: Define constants and system properties for flexibility.
  private val vus = Config.vus

  // 2. HTTP Protocol Setup: Configure common HTTP settings for all requests.
  private val httpProtocol: HttpProtocolBuilder =
      http
          .baseUrl(Config.baseUrl)
          .acceptHeader(Config.JSON_MIME_TYPE)
          .contentTypeHeader(Config.JSON_MIME_TYPE)
          .header(Config.AUTHORIZATION_HEADER, Config.b2bToken)
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
                      .post(Config.finalEndpoint)
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
                                  .trimIndent()))
                      .check(
                          status().`is`(200) // Check for a successful HTTP 200 OK response.
                          )))

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
            "Endpoint: ${Config.finalEndpoint}, " +
            "Token: ${Config.b2bToken.take(4)}... (truncated for security)")

    setUp(
            validateCodeScenario.injectOpen(
                atOnceUsers(vus) // Inject all virtual users at the same time.
                ))
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
            "COPY (SELECT code FROM core_plancodes WHERE main_plan_id = 105544 $limitClause) TO STDOUT;")
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
            "UPDATE core_plancodes SET extra = 'is_validated => \"False\"' WHERE main_plan_id = 105544;")
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
