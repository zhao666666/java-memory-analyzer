#!/bin/bash

# Test script for bytecode instrumentation

echo "=== Testing Bytecode Instrumentation ==="
echo

cd /Users/zhaojie/develop/workspaceVscode/dump/java-memory-analyzer

# Create a simple test class
cat > /tmp/TestAllocation.java << 'EOF'
import java.util.*;

public class TestAllocation {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Test Allocation Program ===");
        System.out.println("Creating objects...");

        List<Object> objects = new ArrayList<>();

        // Create some objects
        for (int i = 0; i < 100; i++) {
            objects.add(new String("Test string " + i));
            objects.add(new HashMap<>());
            objects.add(new ArrayList<>());
        }

        System.out.println("Created " + objects.size() + " objects");
        System.out.println("Waiting for analysis...");

        // Keep running for 30 seconds
        Thread.sleep(30000);

        System.out.println("Done");
    }
}
EOF

# Compile test class
javac -d /tmp /tmp/TestAllocation.java

echo "Starting test application with javaagent..."

# Run with javaagent
java -javaagent:target/java-memory-analyzer-1.0.0.jar \
     -Xmx64m -Xms16m \
     -cp /tmp TestAllocation &

TEST_PID=$!
echo "Test process started with PID: $TEST_PID"

# Wait for application to start
sleep 3

echo
echo "Attaching CLI to test process..."
echo
echo "processes
attach $TEST_PID
status
histogram 10
exit" | java -jar target/java-memory-analyzer-1.0.0.jar --cli 2>&1 | head -50

# Cleanup
echo
echo "Cleaning up..."
kill $TEST_PID 2>/dev/null || true

echo "Test complete"
