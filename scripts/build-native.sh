#!/bin/bash
# Build script for Java Memory Analyzer
# Builds both Java code and native JVMTI agent

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
CPP_DIR="$PROJECT_DIR/src/main/cpp"
LIB_DIR="$PROJECT_DIR/lib"

echo "========================================"
echo "Java Memory Analyzer - Build Script"
echo "========================================"
echo ""

# Create lib directory
mkdir -p "$LIB_DIR"

# Detect OS
OS="$(uname -s)"
echo "Detected OS: $OS"

# Build native agent
echo ""
echo "Building native JVMTI agent..."

case "$OS" in
    Darwin)
        # macOS
        echo "Building for macOS..."

        # Check for clang
        if ! command -v clang &> /dev/null; then
            echo "Warning: clang not found. Skipping native agent build."
            echo "Install Xcode Command Line Tools: xcode-select --install"
        else
            # Get Java home
            JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || echo /Library/Java/Home)"

            if [ ! -d "$JAVA_HOME" ]; then
                echo "Warning: JAVA_HOME not found. Skipping native agent build."
            else
                clang++ -std=c++17 -O2 -arch x86_64 -arch arm64 \
                    -I"$JAVA_HOME/include" \
                    -I"$JAVA_HOME/include/darwin" \
                    -shared -undefined dynamic_lookup \
                    -o "$LIB_DIR/libjvmti_agent.dylib" \
                    "$CPP_DIR/jvmti_agent.cpp"

                if [ -f "$LIB_DIR/libjvmti_agent.dylib" ]; then
                    echo "Native agent built successfully: $LIB_DIR/libjvmti_agent.dylib"
                else
                    echo "Failed to build native agent"
                fi
            fi
        fi
        ;;

    Linux)
        # Linux
        echo "Building for Linux..."

        # Get Java home
        if [ -z "$JAVA_HOME" ]; then
            JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which java)")")")"
        fi

        if [ ! -d "$JAVA_HOME" ]; then
            echo "Warning: JAVA_HOME not found. Skipping native agent build."
        else
            g++ -std=c++17 -O2 -fPIC \
                -I"$JAVA_HOME/include" \
                -I"$JAVA_HOME/include/linux" \
                -shared \
                -o "$LIB_DIR/libjvmti_agent.so" \
                "$CPP_DIR/jvmti_agent.cpp" \
                -lpthread

            if [ -f "$LIB_DIR/libjvmti_agent.so" ]; then
                echo "Native agent built successfully: $LIB_DIR/libjvmti_agent.so"
            else
                echo "Failed to build native agent"
            fi
        fi
        ;;

    *)
        echo "Unsupported OS: $OS"
        echo "Native agent will not be built."
        ;;
esac

echo ""
echo "========================================"
echo "Build complete!"
echo "========================================"
echo ""
echo "To run the application:"
echo "  cd $PROJECT_DIR"
echo "  mvn package"
echo "  java -jar target/java-memory-analyzer-1.0.0.jar"
echo ""
