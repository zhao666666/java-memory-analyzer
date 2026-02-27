#!/bin/bash

# Test script for Java Memory Analyzer CLI

echo "=== Java Memory Analyzer CLI Test ==="
echo

# Start a test Java process in background
echo "Starting test Java process..."
cd /Users/zhaojie/develop/workspaceVscode/dump/java-memory-analyzer

# Compile test app if needed
if [ ! -f test-app/MemoryTestApp.class ]; then
    echo "Compiling test application..."
    javac -d test-app test-app/MemoryTestApp.java
fi

# Start test app in background
java -Xmx64m -Xms16m -cp test-app MemoryTestApp &
TEST_PID=$!
echo "Test process started with PID: $TEST_PID"
sleep 2

# Function to cleanup
cleanup() {
    echo
    echo "Cleaning up..."
    kill $TEST_PID 2>/dev/null || true
    echo "Test process killed."
}

# Set trap for cleanup
trap cleanup EXIT

# Run CLI commands
echo
echo "=== Running CLI Commands ==="
echo

# Create command file
cat > /tmp/cli_commands.txt << EOF
attach $TEST_PID
status
start
debug add-data
histogram 10
snapshot
gc
status
exit
EOF

# Run CLI with command file
java -jar target/java-memory-analyzer-1.0.0.jar --cli < /tmp/cli_commands.txt

echo
echo "=== Test Complete ==="
