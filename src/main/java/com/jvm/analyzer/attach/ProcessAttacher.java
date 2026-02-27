package com.jvm.analyzer.attach;

import com.sun.tools.attach.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import java.nio.file.*;

/**
 * Process Attacher - Attaches analyzer agent to running Java processes
 *
 * Provides low-overhead process attachment using Java Attach API.
 * Supports both dynamic attachment and static agent loading.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 *  附着到目标 JVM 进程
 */
public class ProcessAttacher {

    private static final String AGENT_JAR = "java-memory-analyzer-agent.jar";
    private static final String AGENT_CLASS = "com.jvm.analyzer.attach.AgentLoader";

    private final Map<String, String> agentOptions = new ConcurrentHashMap<>();

    /**
     * Process information holder
     */
    public static class ProcessInfo {
        public final String pid;
        public final String displayName;
        public final String mainClass;
        public final String arguments;
        public final long startTime;

        public ProcessInfo(String pid, String displayName, String mainClass,
                          String arguments, long startTime) {
            this.pid = pid;
            this.displayName = displayName;
            this.mainClass = mainClass;
            this.arguments = arguments;
            this.startTime = startTime;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s - %s", pid, displayName, mainClass);
        }
    }

    /**
     * Attachment result
     */
    public static class AttachmentResult {
        public final boolean success;
        public final String message;
        public final String pid;

        public AttachmentResult(boolean success, String message, String pid) {
            this.success = success;
            this.message = message;
            this.pid = pid;
        }

        public static AttachmentResult success(String pid, String message) {
            return new AttachmentResult(true, message, pid);
        }

        public static AttachmentResult failure(String pid, String message) {
            return new AttachmentResult(false, message, pid);
        }
    }

    /**
     * List all running Java processes
     *
     * @return List of process information
     */
    public static List<ProcessInfo> 
    listJavaProcesses() {
        List<ProcessInfo> processes = new ArrayList<>();

        try {
            List<VirtualMachineDescriptor> descriptors = VirtualMachine.list();
            for (VirtualMachineDescriptor descriptor : descriptors) {
                String displayName = descriptor.displayName();
                String pid = descriptor.id();

                // Try to get more detailed information
                String mainClass = displayName;
                String arguments = "";
                long startTime = 0;

                try {
                    VirtualMachine vm = VirtualMachine.attach(pid);
                    try {
                        Properties props = vm.getSystemProperties();
                        if (props != null) {
                            mainClass = props.getProperty("sun.java.command", displayName);
                            startTime = Long.parseLong(
                                props.getProperty("java.vm.starttime", "0"));
                        }

                        // Get runtime arguments
                        List<String> args = vm.getAgentProperties().entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .toList();
                        arguments = String.join(" ", args);

                    } finally {
                        vm.detach();
                    }
                } catch (Exception e) {
                    // Some processes may not be attachable
                    mainClass = displayName;
                }

                processes.add(new ProcessInfo(pid, displayName, mainClass, arguments, startTime));
            }
        } catch (SecurityException e) {
            System.err.println("Security exception while listing processes: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Error listing Java processes: " + e.getMessage());
            e.printStackTrace();
        }

        return processes;
    }

    /**
     * Attach to a specific process by PID
     *
     * @param pid Process ID
     * @return Attachment result
     */
    public AttachmentResult attach(int pid) {
        return attach(String.valueOf(pid));
    }

    /**
     * Attach to a specific process by PID string
     *
     * @param pid Process ID
     * @return Attachment result
     */
    public AttachmentResult attach(String pid) {
        VirtualMachine vm = null;
        try {
            // Attach to the target VM
            vm = VirtualMachine.attach(pid);

            // Get agent properties to check if already loaded
            Properties agentProps = vm.getAgentProperties();
            String loadedAgents = agentProps.getProperty("DLList");

            // Load the agent
            String agentPath = getAgentJarPath();
            String options = buildAgentOptions();

            vm.loadAgent(agentPath, options);

            return AttachmentResult.success(pid, "Agent loaded successfully");

        } catch (AttachNotSupportedException e) {
            return AttachmentResult.failure(pid,
                "Attach not supported: " + e.getMessage());
        } catch (IOException e) {
            return AttachmentResult.failure(pid,
                "IO error: " + e.getMessage());
        } catch (AgentLoadException e) {
            return AttachmentResult.failure(pid,
                "Agent load error: " + e.getMessage());
        } catch (AgentInitializationException e) {
            return AttachmentResult.failure(pid,
                "Agent initialization error: " + e.getMessage());
        } finally {
            if (vm != null) {
                try {
                    vm.detach();
                } catch (IOException e) {
                    // Ignore detach errors
                }
            }
        }
    }

    /**
     * Load agent at VM startup (static attachment)
     *
     * @param pid Process ID
     * @param options Agent options
     * @return Attachment result
     */
    public AttachmentResult loadAgentAtStartup(String pid, String options) {
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(pid);
            String agentPath = getAgentJarPath();
            vm.loadAgent(agentPath, options);
            return AttachmentResult.success(pid, "Agent loaded at startup");
        } catch (Exception e) {
            return AttachmentResult.failure(pid, e.getMessage());
        } finally {
            if (vm != null) {
                try {
                    vm.detach();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Build agent options string
     */
    private String buildAgentOptions() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : agentOptions.entrySet()) {
            if (!first) sb.append(",");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
            first = false;
        }
        return sb.toString();
    }

    /**
     * Get agent JAR path
     */
    private String getAgentJarPath() {
        // First, try to get the current JAR path (this should always work)
        try {
            String jarPath = ProcessAttacher.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath();
            jarPath = java.net.URLDecoder.decode(jarPath, "UTF-8");

            // Handle file:// URL prefix
            if (jarPath.startsWith("file:")) {
                jarPath = jarPath.substring(5);
            }

            File jarFile = new File(jarPath);
            if (jarFile.exists()) {
                return jarFile.getAbsolutePath();
            }
        } catch (Exception e) {
            System.err.println("[ProcessAttacher] Warning: Could not get JAR path: " + e.getMessage());
        }

        // Try other locations as fallback
        String[] possiblePaths = {
            // Current directory
            System.getProperty("user.dir") + File.separator + "java-memory-analyzer.jar",
            // Lib directory
            System.getProperty("user.dir") + File.separator + "lib" + File.separator + AGENT_JAR,
            // Target directory (development)
            System.getProperty("user.dir") + File.separator + "target" + File.separator +
                "java-memory-analyzer-1.0.0.jar"
        };

        for (String path : possiblePaths) {
            if (path != null && new File(path).exists()) {
                return path;
            }
        }

        // Last resort - return current JAR path even if it doesn't exist
        return System.getProperty("user.dir") + File.separator + "java-memory-analyzer.jar";
    }

    /**
     * Find JAR in classpath
     */
    private String findJarInClasspath() {
        String classpath = System.getProperty("java.class.path");
        String[] paths = classpath.split(File.pathSeparator);

        for (String path : paths) {
            if (path.endsWith(".jar") && path.contains("java-memory-analyzer")) {
                return path;
            }
        }
        return null;
    }

    /**
     * Set agent option
     */
    public void setOption(String key, String value) {
        agentOptions.put(key, value);
    }

    /**
     * Set sampling interval
     */
    public void setSamplingInterval(int interval) {
        agentOptions.put("sampling", String.valueOf(interval));
    }

    /**
     * Enable/disable sampling
     */
    public void setSamplingEnabled(boolean enabled) {
        if (enabled) {
            agentOptions.remove("nosampling");
        } else {
            agentOptions.put("nosampling", "true");
        }
    }

    /**
     * Check if a process is attachable
     */
    public static boolean isAttachable(String pid) {
        try {
            VirtualMachine vm = VirtualMachine.attach(pid);
            vm.detach();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get JVM information for a process
     */
    public static JvmInfo getJvmInfo(String pid) {
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(pid);
            Properties props = vm.getSystemProperties();

            JvmInfo info = new JvmInfo();
            info.pid = pid;
            info.javaVersion = props.getProperty("java.version", "unknown");
            info.javaVendor = props.getProperty("java.vendor", "unknown");
            info.vmName = props.getProperty("java.vm.name", "unknown");
            info.vmVersion = props.getProperty("java.vm.version", "unknown");
            info.startTime = Long.parseLong(
                props.getProperty("java.vm.starttime", "0"));
            info.uptime = System.currentTimeMillis() - info.startTime;

            // Memory info
            info.heapMax = Long.parseLong(
                props.getProperty("java.vm.maxHeapSize", "0"));
            info.heapCommitted = Runtime.getRuntime().totalMemory();
            info.heapUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            return info;

        } catch (Exception e) {
            return null;
        } finally {
            if (vm != null) {
                try {
                    vm.detach();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * JVM Information holder
     */
    public static class JvmInfo {
        public String pid;
        public String javaVersion;
        public String javaVendor;
        public String vmName;
        public String vmVersion;
        public long startTime;
        public long uptime;
        public long heapMax;
        public long heapCommitted;
        public long heapUsed;

        @Override
        public String toString() {
            return String.format("JVM[pid=%s, version=%s, uptime=%ds, heap=%dMB]",
                pid, javaVersion, uptime / 1000, heapUsed / 1024 / 1024);
        }
    }
}
