# Access Control Load Testing

A comprehensive load testing solution for Fever's access control and Fever2 services using Gatling and Kotlin. This
project provides performance testing capabilities to validate system behavior under various load conditions.

## 🎯 Project Overview

This project is designed to perform load testing on Fever's services, specifically:

- **Access Control Service**: Tests code validation endpoints with configurable load parameters
- **Fever2 Service**: Tests Fever2-specific endpoints and functionality

The load testing framework uses Gatling, a powerful open-source load testing tool, combined with Kotlin for test
scenario development.

## 🏗️ Architecture

- **Framework**: Gatling 3.14.3 with Kotlin 2.2.0
- **Build Tool**: Maven with Gatling Maven Plugin
- **Language**: Kotlin (100%)

## 📁 Project Structure

```
access-control-load-testing/
├── pom.xml                                    # Maven configuration
├── scripts/
│   └── run_load_tests.bash                    # Load test execution script
├── src/
│   └── main/
│       ├── kotlin/
│       │   └── com/feverup/
│       │       └── CodesValidationSimulation.kt  # Main load test simulation
│       └── resources/
│           ├── *.properties                  # Environment-specific configurations
│           ├── gatling.conf                  # Gatling framework configuration
│           └── logback-test.xml              # Logging configuration
└── target/                                   # Build output directory
```

## 🚀 Getting Started

### Prerequisites

- Java 8 or higher
- Maven 3.6+
- Bash shell (for running the script)

### Installation

1. Clone the repository:

```bash
git clone <repository-url>
cd access-control-load-testing
```

2. Build the project:

```bash
mvn clean compile
```

## ⚙️ Configuration

### Environment Variables

The following environment variables must be set:

- `LT_AC_ENVIRONMENT`: Target environment (`local` or `staging`)
- `USER_TOKEN`: Authentication token for API access (or written to a .env.<env> file, being <env> the environment name
  like `local` or `staging`)

### Configuration Files

The project uses environment-specific property files located in `src/main/resources/`:

- `access-control-load-testing.access-control.local.properties` - Local access control service
- `access-control-load-testing.access-control.staging.properties` - Staging access control service
- `access-control-load-testing.fever2.local.properties` - Local Fever2 service
- `access-control-load-testing.fever2.staging.properties` - Staging Fever2 service

Each properties file contains:

- `host.url`: Base URL for the service
- `test.vus`: Number of virtual users for load testing
- `test.endpoint`: API endpoint to test

## 🧪 Running Load Tests

### Using the Bash Script (Recommended)

The easiest way to run load tests is using the provided bash script:

```bash
# Run with interactive environment/service selection
./scripts/run_load_tests.bash

# Run with specific environment and service
./scripts/run_load_tests.bash local access-control
./scripts/run_load_tests.bash staging fever2
```

### Using Maven Directly

```bash
# Run access control tests on local environment
mvn clean gatling:test \
    -Dgatling.simulationClass=com.feverup.CodesValidationSimulation \
    -Dproperties.file="access-control-load-testing.access-control.local.properties"

# Run Fever2 tests on staging environment
mvn clean gatling:test \
    -Dgatling.simulationClass=com.feverup.CodesValidationSimulation \
    -Dproperties.file="access-control-load-testing.fever2.staging.properties"
```

## 📊 Test Scenarios

### CodesValidationSimulation

The main simulation class that performs:

1. **Data Partitioning**: Automatically distributes test data across virtual users
2. **Code Validation**: Tests the selected code validation endpoint
3. **Load Distribution**: Configurable virtual user count with intelligent data chunking
4. **Performance Metrics**: Comprehensive response time and throughput analysis

### Key Features

- **Smart Data Distribution**: Each virtual user processes a unique subset of test data
- **Configurable Load**: Adjustable virtual user count via properties files
- **Environment Flexibility**: Support for local and staging environments
- **Service Agnostic**: Can test both access-control and Fever2 services
- **Authentication**: Built-in token-based authentication support

## 📈 Understanding Results

After running load tests, Gatling generates detailed reports in the `target/gatling/` directory:

- **HTML Reports**: Interactive charts and statistics
- **Performance Metrics**: Response times, throughput, and error rates
- **User Experience**: Virtual user behavior and session data
- **System Health**: Resource utilization and bottleneck identification

## 🔧 Customization

### Modifying Test Parameters

Edit the appropriate properties file to adjust:

- Virtual user count
- Target endpoints
- Service URLs
- Test data sources

## 🛠️ Development

### Building

```bash
# Clean build
mvn clean compile

# Run tests
mvn test

# Package
mvn package
```

## 🐛 Troubleshooting

### Common Issues

1. **Missing Environment Variables**: Ensure `LT_AC_ENVIRONMENT` and `USER_TOKEN` are set
2. **Configuration File Not Found**: Verify the properties file path in the `-Dproperties.file` parameter
3. **Authentication Errors**: Check that `USER_TOKEN` is valid and has proper permissions
4. **Port Conflicts**: Ensure target services are running on configured ports

### Debug Mode

Enable detailed logging by modifying `logback-test.xml` or running with debug flags:

```bash
mvn clean gatling:test -Dgatling.logLevel=DEBUG
```

## 📚 Additional Resources

- [Gatling Documentation](https://gatling.io/docs/)
- [Kotlin Documentation](https://kotlinlang.org/docs/)
- [Maven Documentation](https://maven.apache.org/guides/)

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

## 📄 License

This project is proprietary to Fever. All rights reserved.
Made by Samuel Castrillo Domínguez.

## 🆘 Support

For questions or issues related to this load testing framework, please contact the development team or create an issue
in the project repository.
