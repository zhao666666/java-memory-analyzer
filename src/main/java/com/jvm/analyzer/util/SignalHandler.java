package com.jvm.analyzer.util;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Signal Handler - Safe signal handling for graceful shutdown
 *
 * Provides safe signal handling to ensure the analyzer
 * can be safely detached without affecting the target process.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class SignalHandler {

    private static final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);
    private static final List<ShutdownCallback> callbacks = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * Shutdown callback interface
     */
    public interface ShutdownCallback {
        void onShutdown();
    }

    /**
     * Initialize signal handlers
     */
    public static synchronized void initialize() {
        if (initialized.getAndSet(true)) {
            return;
        }

        // Register JVM shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(SignalHandler::handleShutdown, "Shutdown-Hook"));

        // Handle common signals on Unix-like systems
        registerSignalHandlers();

        System.out.println("[SignalHandler] Initialized");
    }

    /**
     * Register signal handlers for Unix signals
     */
    private static void registerSignalHandlers() {
        try {
            // Use reflection to avoid dependency on sun.misc.Signal
            Class<?> signalClass = Class.forName("sun.misc.Signal");
            Class<?> signalHandlerClass = Class.forName("sun.misc.SignalHandler");

            // Handle SIGINT (Ctrl+C)
            Object sigintHandler = new ProxySignalHandler("INT");
            signalClass.getMethod("handle", signalClass, signalHandlerClass)
                .invoke(null, signalClass.getConstructor(String.class).newInstance("INT"), sigintHandler);

            // Handle SIGTERM
            Object sigtermHandler = new ProxySignalHandler("TERM");
            signalClass.getMethod("handle", signalClass, signalHandlerClass)
                .invoke(null, signalClass.getConstructor(String.class).newInstance("TERM"), sigtermHandler);

            // Handle HUP
            Object hupHandler = new ProxySignalHandler("HUP");
            signalClass.getMethod("handle", signalClass, signalHandlerClass)
                .invoke(null, signalClass.getConstructor(String.class).newInstance("HUP"), hupHandler);

            System.out.println("[SignalHandler] OS signal handlers registered");

        } catch (Exception e) {
            // Signal API not available (may happen on some JVMs)
            System.out.println("[SignalHandler] OS signal handlers not available, using shutdown hook only");
        }
    }

    /**
     * Proxy signal handler
     */
    private static class ProxySignalHandler implements java.lang.reflect.InvocationHandler {
        private final String signalName;

        ProxySignalHandler(String signalName) {
            this.signalName = signalName;
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            if ("handle".equals(method.getName())) {
                System.out.println("[SignalHandler] Received signal: " + signalName);
                handleShutdown();
            }
            return null;
        }
    }

    /**
     * Handle shutdown
     */
    private static void handleShutdown() {
        if (!shutdownInProgress.compareAndSet(false, true)) {
            return; // Already shutting down
        }

        System.out.println("[SignalHandler] Shutdown initiated");

        // Execute callbacks in reverse order
        ListIterator<ShutdownCallback> iterator = callbacks.listIterator(callbacks.size());
        while (iterator.hasPrevious()) {
            try {
                iterator.previous().onShutdown();
            } catch (Exception e) {
                System.err.println("[SignalHandler] Callback error: " + e.getMessage());
            }
        }

        System.out.println("[SignalHandler] Shutdown complete");
    }

    /**
     * Register shutdown callback
     *
     * @param callback Callback to execute on shutdown
     */
    public static void registerCallback(ShutdownCallback callback) {
        callbacks.add(callback);
    }

    /**
     * Unregister shutdown callback
     *
     * @param callback Callback to remove
     */
    public static void unregisterCallback(ShutdownCallback callback) {
        callbacks.remove(callback);
    }

    /**
     * Check if shutdown is in progress
     */
    public static boolean isShuttingDown() {
        return shutdownInProgress.get();
    }

    /**
     * Initiate graceful shutdown
     */
    public static void initiateShutdown() {
        handleShutdown();
    }
}
