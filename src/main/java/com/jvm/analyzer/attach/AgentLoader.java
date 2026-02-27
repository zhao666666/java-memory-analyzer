package com.jvm.analyzer.attach;

import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.jar.*;

/**
 * Agent Loader - Java agent entry point for dynamic attachment
 *
 * This class serves as the Java-side entry point when the agent
 * is loaded into a target JVM.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class AgentLoader {

    private static Instrumentation instrumentation;
    private static boolean agentInitialized = false;
    private static final Map<String, String> options = new HashMap<>();

    /**
     * Agent premain - called when agent is loaded at VM startup
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        init(agentArgs, inst);
    }

    /**
     * Agent main - called when agent is dynamically attached
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        init(agentArgs, inst);
    }

    /**
     * Initialize the agent
     */
    private static synchronized void init(String agentArgs, Instrumentation inst) {
        if (agentInitialized) {
            System.err.println("[AgentLoader] Agent already initialized");
            return;
        }

        instrumentation = inst;

        // Parse agent arguments
        if (agentArgs != null && !agentArgs.isEmpty()) {
            parseOptions(agentArgs);
        }

        // Set up native library path
        String nativePath = System.getProperty("java.library.path");
        String agentLibPath = System.getProperty("user.dir") + "/lib";
        if (!nativePath.contains(agentLibPath)) {
            System.setProperty("java.library.path",
                nativePath + ":" + agentLibPath);
        }

        // Try to load native library
        try {
            System.loadLibrary("jvmti_agent");
            System.err.println("[AgentLoader] Native library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            System.err.println("[AgentLoader] Warning: Could not load native library: " + e.getMessage());
            System.err.println("[AgentLoader] Running in Java-only mode");
        }

        // Register transformer for class instrumentation (if needed)
        inst.addTransformer(new MemoryAnalysisTransformer(), true);

        agentInitialized = true;
        System.err.println("[AgentLoader] Agent initialized with options: " + options);
    }

    /**
     * Parse agent options
     */
    private static void parseOptions(String args) {
        String[] parts = args.split(",");
        for (String part : parts) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                options.put(kv[0].trim(), kv[1].trim());
            } else if (!part.trim().isEmpty()) {
                options.put(part.trim(), "true");
            }
        }
    }

    /**
     * Get instrumentation instance
     */
    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    /**
     * Check if agent is initialized
     */
    public static boolean isInitialized() {
        return agentInitialized;
    }

    /**
     * Get agent option
     */
    public static String getOption(String key) {
        return options.get(key);
    }

    /**
     * Get all options
     */
    public static Map<String, String> getOptions() {
        return Collections.unmodifiableMap(options);
    }

    /**
     * Memory Analysis Transformer - instruments classes for memory tracking
     */
    static class MemoryAnalysisTransformer implements ClassFileTransformer {

        @Override
        public byte[] transform(ClassLoader loader, String className,
                               Class<?> classBeingRedefined,
                               ProtectionDomain protectionDomain,
                               byte[] classfileBuffer) throws IllegalClassFormatException {
            // Skip internal classes and analyzer classes
            if (className == null ||
                className.startsWith("java/") ||
                className.startsWith("javax/") ||
                className.startsWith("sun/") ||
                className.startsWith("com/jvm/analyzer/")) {
                return null;
            }

            // For now, we don't transform classes
            // The JVMTI agent handles allocation tracking at the VM level
            // This transformer is here for future bytecode instrumentation needs

            return null; // No transformation
        }
    }
}
