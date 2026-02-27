package com.jvm.analyzer;

import com.jvm.analyzer.ui.MemoryDashboard;
import com.jvm.analyzer.cli.MemoryAnalyzerCli;

import java.util.ArrayList;
import java.util.List;

/**
 * Java Memory Analyzer - Main Application Entry Point
 *
 * A professional memory analysis tool for Java processes.
 * Provides low-overhead memory tracking, leak detection, and visualization.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class MemoryAnalyzerApp {

    private static final String VERSION = "1.0.0";
    private static final String APP_NAME = "Java Memory Analyzer";

    public static void main(String[] args) {
        // Fix for macOS input method error
        System.setProperty("apple.awt.disableInputMethods", "true");

        if (args.length == 0) {
            launchGUI();
        } else if ("--cli".equals(args[0]) || "-c".equals(args[0])) {
            launchCLI(args);
        } else if ("--help".equals(args[0]) || "-h".equals(args[0])) {
            printHelp();
        } else if ("--version".equals(args[0]) || "-v".equals(args[0])) {
            printVersion();
        } else {
            // Default: launch GUI with optional target PID
            launchGUI(args);
        }
    }

    /**
     * Launch the graphical user interface
     */
    public static void launchGUI() {
        launchGUI(new String[0]);
    }

    /**
     * Launch the graphical user interface with arguments
     */
    public static void launchGUI(String[] args) {
        try {
            // Set system look and feel
            try {
                javax.swing.UIManager.setLookAndFeel(
                    javax.swing.UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // Ignore and use default L&F
            }

            java.awt.EventQueue.invokeLater(() -> {
                MemoryDashboard dashboard = new MemoryDashboard();

                // Parse arguments for target PID
                for (int i = 0; i < args.length; i++) {
                    if ("--pid".equals(args[i]) || "-p".equals(args[i])) {
                        if (i + 1 < args.length) {
                            try {
                                int pid = Integer.parseInt(args[i + 1]);
                                dashboard.attachToProcess(pid);
                            } catch (NumberFormatException e) {
                                dashboard.showError("Invalid PID: " + args[i + 1]);
                            }
                        }
                        break;
                    }
                }

                dashboard.setVisible(true);
            });
        } catch (Exception e) {
            System.err.println("Failed to launch GUI: " + e.getMessage());
            e.printStackTrace();
            launchCLI(args);
        }
    }

    /**
     * Launch the command line interface
     */
    public static void launchCLI(String[] args) {
        // Remove --cli/-c from args if present
        List<String> argList = new ArrayList<>();
        for (String arg : args) {
            if (!"--cli".equals(arg) && !"-c".equals(arg)) {
                argList.add(arg);
            }
        }
        MemoryAnalyzerCli cli = new MemoryAnalyzerCli();
        cli.run(argList.toArray(new String[0]));
    }

    /**
     * Print application help
     */
    public static void printHelp() {
        System.out.println(APP_NAME + " v" + VERSION);
        System.out.println();
        System.out.println("用法：java -jar java-memory-analyzer.jar [选项]");
        System.out.println();
        System.out.println("选项:");
        System.out.println("  --gui, -g          启动图形界面 (默认)");
        System.out.println("  --cli, -c          启动命令行界面");
        System.out.println("  --pid <id>, -p     目标进程 ID (图形模式)");
        System.out.println("  --attach <pid>     附着到运行中的进程 (命令行模式)");
        System.out.println("  --report <format>  生成报告 (html|json|csv)");
        System.out.println("  --output <file>    报告输出文件");
        System.out.println("  --help, -h         显示此帮助信息");
        System.out.println("  --version, -v      显示版本信息");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  java -jar java-memory-analyzer.jar");
        System.out.println("  java -jar java-memory-analyzer.jar --cli --attach 12345");
        System.out.println("  java -jar java-memory-analyzer.jar --pid 12345");
        System.out.println("  java -jar java-memory-analyzer.jar --cli --report html --output report.html");
    }

    /**
     * Print version information
     */
    public static void printVersion() {
        System.out.println(APP_NAME + " v" + VERSION);
        System.out.println("构建：" + getBuildInfo());
        System.out.println("JVM: " + System.getProperty("java.version"));
        System.out.println("操作系统：" + System.getProperty("os.name") + " " + System.getProperty("os.version"));
    }

    private static String getBuildInfo() {
        try {
            var props = new java.util.Properties();
            try (var is = MemoryAnalyzerApp.class.getResourceAsStream("/build.properties")) {
                if (is != null) {
                    props.load(is);
                    return props.getProperty("build.version", VERSION) +
                           " (" + props.getProperty("build.date", "unknown") + ")";
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return VERSION;
    }
}
