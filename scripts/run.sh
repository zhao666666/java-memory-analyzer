#!/bin/bash
# Run script for Java Memory Analyzer

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LIB_DIR="$PROJECT_DIR/lib"

# Add native library path
export JAVA_LIBRARY_PATH="$LIB_DIR:$JAVA_LIBRARY_PATH"

# Find the jar file
JAR_FILE="$PROJECT_DIR/target/java-memory-analyzer-1.0.0.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "JAR file not found: $JAR_FILE"
    echo "Please build the project first: mvn package"
    exit 1
fi

echo "Starting Java Memory Analyzer..."
echo ""

java -Djava.library.path="$LIB_DIR" \
     --add-exports java.base/sun.tools.attach=ALL-UNNAMED \
     -jar "$JAR_FILE" "$@"
