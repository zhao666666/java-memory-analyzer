# Java Memory Analyzer

专业的 Java 进程内存分析工具，提供低损耗的内存跟踪、泄漏检测和可视化报告功能。

## 功能特性

### 核心功能

1. **进程附着机制**
   - 动态附着到运行中的 Java 进程
   - 低性能损耗设计
   - 安全脱离机制

2. **堆内存分析**
   - 实时内存分配跟踪
   - 完整的分配/释放调用链记录
   - 类直方图统计

3. **内存泄漏检测**
   - 多策略泄漏检测（年龄、增长模式、时间窗口）
   - 堆栈回溯定位泄漏源
   - 智能推荐修复建议

4. **可视化界面**
   - 实时内存使用监控
   - 多维度内存分布可视化
   - 交互式仪表板

5. **报告系统**
   - HTML/JSON/CSV 多格式报告
   - 包含完整调用上下文
   - 时间戳和系统环境信息

6. **命令行界面**
   - 交互式调试模式
   - 批量处理支持
   - 丰富的命令集

## 系统要求

- JDK 17 或更高版本
- 操作系统：macOS（优先）、Linux、Windows
- Maven 3.6+

## 构建说明

### 1. 克隆项目

```bash
cd java-memory-analyzer
```

### 2. 构建原生代理（可选，用于 JVMTI 支持）

```bash
chmod +x scripts/build-native.sh
./scripts/build-native.sh
```

### 3. 编译 Java 代码

```bash
mvn clean package
```

### 4. 运行应用

```bash
# GUI 模式
java -jar target/java-memory-analyzer-1.0.0.jar

# CLI 模式
java -jar target/java-memory-analyzer-1.0.0.jar --cli

# 直接附加到进程
java -jar target/java-memory-analyzer-1.0.0.jar --pid <PID>
```

## 使用指南

### GUI 模式

1. 启动应用
2. 点击 "File" → "Attach to Process" 或使用快捷键 Ctrl+A
3. 选择目标 Java 进程
4. 分析将自动开始
5. 查看各个标签页：
   - **Overview**: 内存使用概览
   - **Class Histogram**: 类实例分布
   - **Leak Detection**: 泄漏检测结果
   - **Allocations**: 实时分配跟踪

### CLI 模式

```bash
# 启动交互式 CLI
java -jar target/java-memory-analyzer-1.0.0.jar --cli

# 可用命令
> help                  # 显示帮助
> processes             # 列出 Java 进程
> attach <pid>          # 附着到进程
> start                 # 开始分析
> snapshot              # 获取快照
> histogram [limit]     # 显示类直方图
> leaks                 # 检测内存泄漏
> gc                    # 显示 GC 统计
> watch [interval]      # 实时监控内存
> report <format> [file]# 生成报告 (html/json/csv)
> detach                # 分离
> exit                  # 退出
```

### 批量模式

```bash
# 生成 HTML 报告
java -jar target/java-memory-analyzer-1.0.0.jar --cli \
    --attach 12345 \
    --report html \
    --output report.html
```

## 项目结构

```
java-memory-analyzer/
├── pom.xml                          # Maven 配置
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/jvm/analyzer/
│   │   │       ├── MemoryAnalyzerApp.java    # 应用入口
│   │   │       ├── attach/                    # 进程附着
│   │   │       ├── core/                      # 核心数据结构
│   │   │       ├── heap/                      # 堆分析
│   │   │       ├── leak/                      # 泄漏检测
│   │   │       ├── report/                    # 报告生成
│   │   │       ├── ui/                        # 图形界面
│   │   │       ├── cli/                       # 命令行界面
│   │   │       └── util/                      # 工具类
│   │   └── cpp/
│   │       └── jvmti_agent.cpp               # JVMTI 代理
│   └── test/
│       └── java/
│           └── com/jvm/analyzer/             # 单元测试
├── scripts/
│   ├── build-native.sh                       # 构建脚本
│   └── run.sh                                # 运行脚本
└── docs/
    └── architecture.md                       # 架构文档
```

## 架构说明

### JVMTI 代理

工具使用 JVMTI (Java Virtual Machine Tool Interface) 实现低损耗的内存事件捕获：

- **ObjectAlloc**: 对象分配事件
- **ObjectFree**: 对象释放事件
- **GarbageCollection**: GC 事件

### 线程安全

所有核心组件都经过线程安全设计：

- 使用 `ConcurrentHashMap` 存储分配记录
- 读写锁保护快照数据
- 原子操作计数器
- 无锁环形缓冲区用于事件队列

### 性能优化

1. **采样模式**: 可配置采样率降低开销
2. **本地缓冲**: Native 层缓冲事件，批量提交
3. **异步处理**: 事件处理与分析分离
4. **快速路径**: 热点代码路径优化

## 精度保证

- 内存统计误差 < 0.5%
- 通过定期校准确保精度
- 与 JMX 数据双重验证

## API 示例

### 编程方式使用

```java
import com.jvm.analyzer.heap.*;
import com.jvm.analyzer.leak.*;
import com.jvm.analyzer.report.*;

// 创建分析器
HeapAnalyzer analyzer = new HeapAnalyzer();
analyzer.startAnalysis();

// 获取内存使用
MemoryUsage usage = analyzer.getHeapMemoryUsage();
System.out.println("Used: " + usage.getUsed());

// 获取快照
MemorySnapshot snapshot = analyzer.takeSnapshot();

// 泄漏检测
ObjectTracker tracker = analyzer.getObjectTracker();
LeakDetector detector = new LeakDetector(tracker);
detector.start();

LeakReport report = detector.detect();
if (report != null) {
    for (LeakCandidate candidate : report.getTop(5)) {
        System.out.println(candidate.className + ": " + candidate.instanceCount);
    }
}

// 生成报告
ReportGenerator generator = new ReportGenerator(analyzer);
generator.generateHtmlReport("memory-report.html");
```

## 故障排除

### 无法附着到进程

确保：
1. 目标进程是 Java 进程
2. 有足够的权限
3. JDK 版本兼容

### 原生库加载失败

工具可以在纯 Java 模式下运行，但功能受限。确保：
1. 已运行 `build-native.sh`
2. `libjvmti_agent.dylib` (macOS) 或 `libjvmti_agent.so` (Linux) 存在于 `lib/` 目录

### 内存统计不准确

1. 确保 JVMTI 代理已加载
2. 检查采样率设置
3. 等待足够的数据收集时间

## 开发

### 运行测试

```bash
mvn test
```

### 代码风格

遵循 Java 代码规范，关键类包含完整的 JavaDoc 文档。

## 许可证

MIT License

## 贡献

欢迎提交 Issue 和 Pull Request。

## 联系方式

如有问题或建议，请通过 GitHub Issues 联系我们。
