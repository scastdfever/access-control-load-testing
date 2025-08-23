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
- **Configuration**: Environment variables and .env files (no properties files)
- **Dependencies**: Gson for JSON handling, OkHttp for HTTP client operations

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
│           ├── gatling.conf                  # Gatling framework configuration
│           └── logback-test.xml              # Logging configuration
├── env.local.example                         # Example local environment file
├── env.staging.example                       # Example staging environment file
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

2. Set up environment files:

```bash
# Copy example files and add your tokens
cp env.local.example .env.local
cp env.staging.example .env.staging

# Edit the files and add your actual tokens
# .env.local should contain:
#   FEVER2_TOKEN=your_local_fever2_token_here
#   B2B_TOKEN=your_local_b2b_token_here
# .env.staging should contain:
#   FEVER2_TOKEN=your_staging_fever2_token_here
#   B2B_TOKEN=your_staging_b2b_token_here
```

3. Build the project:

```bash
mvn clean compile
```

## ⚙️ Configuration

### Environment Variables

The following environment variables are used:

- `LT_AC_ENVIRONMENT`: Target environment (`local` or `staging`)
- `LT_AC_SERVICE`: Target service (`access-control` or `fever2`)
- `FEVER2_TOKEN`: Authentication token for Fever2 service API access
- `B2B_TOKEN`: Authentication token for B2B/Access Control service API access

### Environment Files

The project uses `.env` files for storing authentication tokens:

- `.env.local` - Contains `FEVER2_TOKEN` and `B2B_TOKEN` for local environment
- `.env.staging` - Contains `FEVER2_TOKEN` and `B2B_TOKEN` for staging environment

**Note**: If you set `FEVER2_TOKEN` or `B2B_TOKEN` as environment variables, they take precedence over the `.env` files.

### Service Configuration

The system automatically configures:

- **Base URLs**:
  - Local Access Control: `http://localhost:8020`
  - Local Fever2: `http://localhost:8002`
  - Staging Access Control: `https://access-control-api.staging.feverup.com`
  - Staging Fever2: `https://staging.feverup.com`

- **Endpoints**:
  - Access Control: `/api/1.1/partners/{partnerId}/codes/validate`
  - Fever2: `/b2b/2.0/partners/{partnerId}/codes/validate/`

- **Virtual Users**:
  - Local: 10 users
  - Staging: 6 users

- **Environment Data**:
  - **Local**: Partner ID: 198, Main Plan ID: 105544, Session ID: 232948
  - **Staging**: Partner ID: 62, Main Plan ID: 278979, Session ID: 12552516

## 🧪 Running Load Tests

### Using the Bash Script (Recommended)

The easiest way to run load tests is using the provided bash script:

```bash
# Run with interactive prompts for all parameters
./scripts/run_load_tests.bash

# Run with specific environment and service
./scripts/run_load_tests.bash local access-control
./scripts/run_load_tests.bash staging fever2

# Show help
./scripts/run_load_tests.bash --help
```

### Script Parameters

The script accepts two parameters in order:
1. **Environment**: `local` or `staging` (defaults to `local`)
2. **Service**: `access-control` or `fever2` (defaults to `fever2`)

### Using Maven Directly

```bash
# Set environment variables first
export LT_AC_ENVIRONMENT=local
export LT_AC_SERVICE=access-control
export FEVER2_TOKEN=your_fever2_token_here
export B2B_TOKEN=your_b2b_token_here

# Run the simulation
mvn clean gatling:test \
    -Dgatling.simulationClass=com.feverup.CodesValidationSimulation
```

## 📊 Test Scenarios

### CodesValidationSimulation

The main simulation class that performs:

1. **Dynamic Code Generation**: Creates test codes by interacting with the Fever2 API
2. **Data Partitioning**: Automatically distributes test data across virtual users
3. **Code Validation**: Tests the selected code validation endpoint
4. **Load Distribution**: Configurable virtual user count with intelligent data chunking
5. **Performance Metrics**: Comprehensive response time and throughput analysis

### Key Features

- **Smart Data Distribution**: Each virtual user processes a unique subset of test data
- **Configurable Load**: Adjustable virtual user count based on environment
- **Environment Flexibility**: Support for local and staging environments
- **Service Agnostic**: Can test both access-control and Fever2 services
- **Authentication**: Built-in token-based authentication support
- **Dynamic Code Preparation**: Automatically generates test codes from the Fever2 service

### Code Preparation Process

The simulation automatically:
1. Creates shopping carts with specified ticket quantities (5 orders × 10 tickets each)
2. Prepares and books the carts via Fever2 API endpoints
3. Extracts validation codes from the generated tickets
4. Distributes codes across virtual users for testing

### CodesPreparer Class

A dedicated class that handles the complete code generation workflow:

- **Cart Creation**: `/api/4.2/cart/` - Creates shopping carts with session and ticket data
- **Book Preparation**: `/api/4.2/book/prepare/` - Prepares carts for booking
- **Cart Booking**: `/api/4.2/cart/{cartId}/book/free/` - Books the prepared carts
- **Code Extraction**: `/api/4.1/tickets/{ticketId}/` - Retrieves validation codes from tickets

The system uses hardcoded session IDs for each environment to ensure consistent test data generation.

## 📈 Understanding Results

After running load tests, Gatling generates detailed reports in the `target/gatling/` directory:

- **HTML Reports**: Interactive charts and statistics
- **Performance Metrics**: Response times, throughput, and error rates
- **User Experience**: Virtual user behavior and session data
- **System Health**: Resource utilization and bottleneck identification

## 🔧 Customization

### Modifying Test Parameters

Edit the `CodesValidationSimulation.kt` file to adjust:

- Virtual user count per environment
- Target endpoints
- Service URLs
- Code preparation parameters (orders, tickets per order)
- Environment-specific data (partner IDs, main plan IDs, session IDs)

### Environment-Specific Configuration

The system automatically adapts to different environments:

- **Local**: Lower virtual user count (10) for development testing
- **Staging**: Higher virtual user count (6) for pre-production validation

### Code Generation Configuration

The `CodesPreparer` class uses the following hardcoded parameters:
- **Orders per session**: 5
- **Tickets per order**: 10
- **Total codes generated**: 50 codes per session

These values can be modified in the `before()` method of the simulation class.

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

1. **Missing Environment Variables**: Ensure all required environment variables are set
2. **Missing Tokens**: Check that `FEVER2_TOKEN` and `B2B_TOKEN` are valid and have proper permissions
3. **Service Unavailable**: Ensure target services are running on configured ports
4. **Missing .env Files**: Create `.env.local` and `.env.staging` files with your tokens

### Debug Mode

Enable detailed logging by modifying `logback-test.xml` or running with debug flags:

```bash
mvn clean gatling:test -Dgatling.logLevel=DEBUG
```

### Token Loading Issues

If you encounter token loading problems:

1. Check that `.env` files exist and contain valid `FEVER2_TOKEN` and `B2B_TOKEN` values
2. Verify file permissions on `.env` files
3. Try setting `FEVER2_TOKEN` and `B2B_TOKEN` directly as environment variables
4. Check the script output for specific error messages

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
