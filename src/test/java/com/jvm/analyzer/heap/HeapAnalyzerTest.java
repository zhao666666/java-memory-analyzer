package com.jvm.analyzer.heap;

import com.jvm.analyzer.core.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Unit tests for HeapAnalyzer
 */
public class HeapAnalyzerTest {

    private HeapAnalyzer heapAnalyzer;

    @BeforeEach
    public void setUp() {
        heapAnalyzer = new HeapAnalyzer();
    }

    @AfterEach
    public void tearDown() {
        if (heapAnalyzer != null) {
            heapAnalyzer.stopAnalysis();
            heapAnalyzer.clear();
        }
    }

    @Test
    public void testStartStopAnalysis() {
        // Start analysis
        heapAnalyzer.startAnalysis();
        assertTrue(heapAnalyzer.isAnalyzing(), "Analysis should be running");

        // Stop analysis
        heapAnalyzer.stopAnalysis();
        assertFalse(heapAnalyzer.isAnalyzing(), "Analysis should be stopped");
    }

    @Test
    public void testTakeSnapshot() {
        heapAnalyzer.startAnalysis();

        MemorySnapshot snapshot = heapAnalyzer.takeSnapshot();

        assertNotNull(snapshot, "Snapshot should not be null");
        assertTrue(snapshot.getSnapshotId() > 0, "Snapshot ID should be positive");
        assertTrue(snapshot.getTimestamp() > 0, "Timestamp should be positive");
        assertTrue(snapshot.getTotalHeapUsed() > 0, "Heap used should be positive");
    }

    @Test
    public void testRecordAllocation() {
        heapAnalyzer.startAnalysis();

        // Create test allocation record
        AllocationRecord record = new AllocationRecord.Builder()
            .setObjectId(12345L)
            .setClassName("java.lang.String")
            .setSize(1024L)
            .captureStackTrace(2)
            .build();

        heapAnalyzer.recordAllocation(record);

        // Verify tracking
        ObjectTracker tracker = heapAnalyzer.getObjectTracker();
        assertTrue(tracker.getTrackedCount() > 0, "Should track objects");
    }

    @Test
    public void testGetRecentAllocations() {
        heapAnalyzer.startAnalysis();

        // Add some test allocations
        for (int i = 0; i < 10; i++) {
            AllocationRecord record = new AllocationRecord.Builder()
                .setObjectId(1000L + i)
                .setClassName("test.TestClass")
                .setSize(100L + i)
                .build();
            heapAnalyzer.recordAllocation(record);
        }

        List<AllocationRecord> recent = heapAnalyzer.getRecentAllocations(5);
        assertNotNull(recent);
        assertTrue(recent.size() <= 5, "Should return at most 5 records");
    }

    @Test
    public void testGetSnapshots() {
        heapAnalyzer.startAnalysis();

        // Take multiple snapshots
        heapAnalyzer.takeSnapshot();

        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        heapAnalyzer.takeSnapshot();

        List<MemorySnapshot> snapshots = heapAnalyzer.getSnapshots();
        assertTrue(snapshots.size() >= 2, "Should have at least 2 snapshots");
    }

    @Test
    public void testGetHeapMemoryUsage() {
        MemoryUsage usage = heapAnalyzer.getHeapMemoryUsage();

        assertNotNull(usage);
        assertTrue(usage.getUsed() > 0, "Used memory should be positive");
        assertTrue(usage.getCommitted() > 0, "Committed memory should be positive");
        assertTrue(usage.getMax() > 0, "Max memory should be positive");
    }

    @Test
    public void testClear() {
        heapAnalyzer.startAnalysis();

        // Add some data
        heapAnalyzer.takeSnapshot();

        AllocationRecord record = new AllocationRecord.Builder()
            .setObjectId(99999L)
            .setClassName("test.TestClass")
            .setSize(1000L)
            .build();
        heapAnalyzer.recordAllocation(record);

        // Clear
        heapAnalyzer.clear();

        // Verify cleared
        ObjectTracker tracker = heapAnalyzer.getObjectTracker();
        assertEquals(0, tracker.getTrackedCount(), "Tracked count should be 0 after clear");
    }

    @Test
    public void testSnapshotComparison() {
        heapAnalyzer.startAnalysis();

        // Take first snapshot
        MemorySnapshot snapshot1 = heapAnalyzer.takeSnapshot();

        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Take second snapshot
        MemorySnapshot snapshot2 = heapAnalyzer.takeSnapshot();

        // Compare
        MemorySnapshot.SnapshotDiff diff = snapshot1.compare(snapshot2);

        assertNotNull(diff);
        assertEquals(snapshot1.getSnapshotId(), diff.baseSnapshotId);
        assertEquals(snapshot2.getSnapshotId(), diff.currentSnapshotId);
        assertTrue(diff.timeDelta >= 0, "Time delta should be non-negative");
    }

    @Test
    public void testConcurrentSnapshotTaking() throws Exception {
        heapAnalyzer.startAnalysis();

        int threadCount = 5;
        int snapshotsPerThread = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ConcurrentLinkedQueue<MemorySnapshot> allSnapshots = new ConcurrentLinkedQueue<>();

        // Start multiple threads taking snapshots concurrently
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    for (int j = 0; j < snapshotsPerThread; j++) {
                        MemorySnapshot snapshot = heapAnalyzer.takeSnapshot();
                        allSnapshots.add(snapshot);
                        Thread.sleep(1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        // Wait for all threads to complete
        latch.await(10, TimeUnit.SECONDS);

        // Verify all snapshots were taken
        assertEquals(threadCount * snapshotsPerThread, allSnapshots.size(),
            "All snapshots should be taken");

        // Verify snapshot IDs are unique
        Set<Long> ids = new HashSet<>();
        for (MemorySnapshot snapshot : allSnapshots) {
            ids.add(snapshot.getSnapshotId());
        }
        assertEquals(threadCount * snapshotsPerThread, ids.size(),
            "All snapshot IDs should be unique");
    }
}
