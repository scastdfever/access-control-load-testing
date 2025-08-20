#!/bin/bash

# Determine environment file based on environment selection
LT_AC_ENVIRONMENT=$1
LT_AC_SERVICE=$2

select_env() {
    local choice
    echo "Select environment:"
    echo "1) Local"
    echo "2) Staging"
    read -rp "Enter your choice (1-2): " choice

    case $choice in
    1) LT_AC_ENVIRONMENT="local" ;;
    2) LT_AC_ENVIRONMENT="staging" ;;
    *) echo "Invalid choice. Defaulting to local."; LT_AC_ENVIRONMENT="local" ;;
    esac

    echo "Selected environment: $LT_AC_ENVIRONMENT"
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
    *) echo "Invalid choice. Defaulting to fever2."; LT_AC_SERVICE="fever2" ;;
    esac

    echo "Selected service: $LT_AC_SERVICE"
}

load_env_file() {
    local env_file=".env.$LT_AC_ENVIRONMENT"
    if [ ! -f "$env_file" ]; then
        echo "❌ Environment file $env_file not found. Exiting."
        exit 1
    fi

    export "$(grep -v '^#' "$env_file" | xargs)"
    echo "✅ Loaded environment variables from $env_file"
}

main() {
    local LT_AC_ENVIRONMENT=${LT_AC_ENVIRONMENT:-local}
    local LT_AC_SERVICE=${LT_AC_SERVICE:-fever2}

    echo "Environment: $LT_AC_ENVIRONMENT | Service: $LT_AC_SERVICE"

    mvn clean gatling:test \
        -Dgatling.simulationClass=com.feverup.CodesValidationSimulation \
        -Dproperties.file="access-control-load-testing.$LT_AC_SERVICE.$LT_AC_ENVIRONMENT.properties"
}

# Main script execution
if [ -z "$LT_AC_ENVIRONMENT" ]; then
    select_env
fi

if [ -z "$LT_AC_SERVICE" ]; then
    select_service
fi

load_env_file

export LT_AC_ENVIRONMENT
export LT_AC_SERVICE

main
