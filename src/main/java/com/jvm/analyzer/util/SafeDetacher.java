package com.jvm.analyzer.util;

import com.jvm.analyzer.attach.*;
import com.jvm.analyzer.heap.*;
import com.jvm.analyzer.leak.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Safe Detacher - Ensures safe detachment from target process
 *
 * Provides mechanisms to safely detach from the target JVM
 * without causing issues or leaving resources allocated.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class SafeDetacher {

    private final AtomicBoolean detached = new AtomicBoolean(true);
    private final AtomicBoolean detaching = new AtomicBoolean(false);
    private final List<DetachCallback> preDetachCallbacks = new CopyOnWriteArrayList<>();
    private final List<DetachCallback> postDetachCallbacks = new CopyOnWriteArrayList<>();

    private volatile ProcessAttacher attacher;
    private volatile HeapAnalyzer heapAnalyzer;
    private volatile LeakDetector leakDetector;
    private volatile int targetPid = -1;

    /**
     * Detach callback interface
     */
    public interface DetachCallback {
        void onDetach();
    }

    /**
     * Set components to manage
     */
    public void setComponents(ProcessAttacher attacher, HeapAnalyzer heapAnalyzer,
                             LeakDetector leakDetector, int targetPid) {
        this.attacher = attacher;
        this.heapAnalyzer = heapAnalyzer;
        this.leakDetector = leakDetector;
        this.targetPid = targetPid;
        this.detached.set(false);
    }

    /**
     * Register pre-detach callback
     */
    public void registerPreDetachCallback(DetachCallback callback) {
        preDetachCallbacks.add(callback);
    }

    /**
     * Register post-detach callback
     */
    public void registerPostDetachCallback(DetachCallback callback) {
        postDetachCallbacks.add(callback);
    }

    /**
     * Perform safe detachment
     *
     * @return true if successful
     */
    public synchronized boolean detach() {
        if (detached.get()) {
            return true; // Already detached
        }

        if (!detaching.compareAndSet(false, true)) {
            return false; // Detach already in progress
        }

        System.out.println("[SafeDetacher] Starting safe detachment from PID " + targetPid);

        boolean success = true;

        try {
            // Step 1: Stop leak detection
            if (leakDetector != null) {
                System.out.println("[SafeDetacher] Stopping leak detector...");
                try {
                    leakDetector.stop();
                } catch (Exception e) {
                    System.err.println("[SafeDetacher] Error stopping leak detector: " + e.getMessage());
                }
            }

            // Step 2: Stop heap analysis
            if (heapAnalyzer != null) {
                System.out.println("[SafeDetacher] Stopping heap analyzer...");
                try {
                    heapAnalyzer.stopAnalysis();
                } catch (Exception e) {
                    System.err.println("[SafeDetacher] Error stopping heap analyzer: " + e.getMessage());
                }
            }

            // Step 3: Execute pre-detach callbacks
            System.out.println("[SafeDetacher] Executing pre-detach callbacks...");
            for (DetachCallback callback : preDetachCallbacks) {
                try {
                    callback.onDetach();
                } catch (Exception e) {
                    System.err.println("[SafeDetacher] Pre-detach callback error: " + e.getMessage());
                }
            }

            // Step 4: Clear references
            heapAnalyzer = null;
            leakDetector = null;
            attacher = null;

            // Step 5: Execute post-detach callbacks
            System.out.println("[SafeDetacher] Executing post-detach callbacks...");
            for (DetachCallback callback : postDetachCallbacks) {
                try {
                    callback.onDetach();
                } catch (Exception e) {
                    System.err.println("[SafeDetacher] Post-detach callback error: " + e.getMessage());
                }
            }

            System.out.println("[SafeDetacher] Successfully detached from process");

        } catch (Exception e) {
            System.err.println("[SafeDetacher] Error during detachment: " + e.getMessage());
            success = false;
        } finally {
            detached.set(true);
            detaching.set(false);
            targetPid = -1;
        }

        return success;
    }

    /**
     * Check if detached
     */
    public boolean isDetached() {
        return detached.get();
    }

    /**
     * Check if detaching in progress
     */
    public boolean isDetaching() {
        return detaching.get();
    }

    /**
     * Get target PID
     */
    public int getTargetPid() {
        return targetPid;
    }

    /**
     * Mark as attached
     */
    public void markAttached() {
        detached.set(false);
    }

    /**
     * Reset state for re-attachment
     */
    public void reset() {
        detached.set(true);
        detaching.set(false);
        targetPid = -1;
        heapAnalyzer = null;
        leakDetector = null;
        attacher = null;
    }
}
