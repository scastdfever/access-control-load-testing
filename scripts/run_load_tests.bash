#!/bin/bash

# Determine environment file based on environment selection
LT_AC_ENVIRONMENT=$1
LT_AC_SERVICE=$2

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_status() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

select_env() {
    local choice
    echo "Select environment:"
    echo "1) Local"
    echo "2) Staging"
    read -rp "Enter your choice (1-2): " choice

    case $choice in
    1) LT_AC_ENVIRONMENT="local" ;;
    2) LT_AC_ENVIRONMENT="staging" ;;
    *)
        echo -e "${YELLOW}Invalid choice. Defaulting to local.${NC}"
        LT_AC_ENVIRONMENT="local"
        ;;
    esac

    echo -e "${GREEN}Selected environment: $LT_AC_ENVIRONMENT${NC}"
}

select_service() {
    local choice
    echo "Select service:"
    echo "1) fever2"
    echo "2) access-control"
    read -rp "Enter your choice (1-2): " choice

    case $choice in
    1) LT_AC_SERVICE="fever2" ;;
    2) LT_AC_SERVICE="access-control" ;;
    *)
        echo -e "${YELLOW}Invalid choice. Defaulting to fever2.${NC}"
        LT_AC_SERVICE="fever2"
        ;;
    esac

    echo -e "${GREEN}Selected service: $LT_AC_SERVICE${NC}"
}

load_env_file() {
    local env_file=".env.$LT_AC_ENVIRONMENT"
    if [ ! -f "$env_file" ]; then
        print_error "Environment file $env_file not found. Exiting."
        exit 1
    fi

    export "$(grep -v '^#' "$env_file" | xargs)"
    print_status "Loaded environment variables from $env_file"
}

show_usage() {
    echo "Usage: $0 [environment] [service]"
    echo ""
    echo "Arguments:"
    echo "  environment    local or staging (default: local)"
    echo "  service       fever2 or access-control (default: fever2)"
    echo ""
    echo "Examples:"
    echo "  $0 local fever2                    # Run local fever2 tests"
    echo "  $0 local access-control            # Run local access-control tests"
    echo "  $0 staging fever2                  # Run staging fever2 tests"
    echo "  $0                                # Run with defaults (local + fever2)"
    echo ""
    echo "Interactive mode:"
    echo "  $0                                # Will prompt for environment and service"
}

main() {
    local LT_AC_ENVIRONMENT=${LT_AC_ENVIRONMENT:-local}
    local LT_AC_SERVICE=${LT_AC_SERVICE:-fever2}

    print_info "Load Testing Configuration:"
    echo "  Environment: $LT_AC_ENVIRONMENT"
    echo "  Service: $LT_AC_SERVICE"
    echo ""

    # Load environment variables
    load_env_file

    # Export environment variables for the simulation
    export LT_AC_ENVIRONMENT
    export LT_AC_SERVICE

    print_status "Starting Gatling simulation..."
    mvn clean gatling:test -q -B \
        -Dgatling.simulationClass=com.feverup.CodesValidationSimulation
}

# Show help if requested
if [[ "$1" == "--help" || "$1" == "-h" ]]; then
    show_usage
    exit 0
fi

# Main script execution
if [ -z "$LT_AC_ENVIRONMENT" ]; then
    select_env
fi

if [ -z "$LT_AC_SERVICE" ]; then
    select_service
fi

main
