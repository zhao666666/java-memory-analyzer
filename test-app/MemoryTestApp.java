import java.util.*;
import java.util.concurrent.*;

/**
 * 测试应用 - 演示 JVMTI Agent 的效果
 */
public class MemoryTestApp {

    private static List<byte[]> memoryHog = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        System.out.println("=== 内存测试应用 ===");
        System.out.println("按任意键开始分配内存...");
        System.in.read();

        // 分配一些内存
        for (int i = 0; i < 100; i++) {
            memoryHog.add(new byte[1024 * 100]); // 100KB
            Thread.sleep(10);
        }

        System.out.println("已分配 10MB 内存");
        System.out.println("按任意键继续分配...");
        System.in.read();

        // 再分配一些
        for (int i = 0; i < 50; i++) {
            memoryHog.add(new byte[1024 * 200]); // 200KB
            Thread.sleep(10);
        }

        System.out.println("已分配额外 10MB 内存");
        System.out.println("程序运行中，请使用 CLI 附着查看 histogram...");
        System.out.println("按 Ctrl+C 退出");

        // 保持运行
        while (true) {
            Thread.sleep(1000);
        }
    }
}
