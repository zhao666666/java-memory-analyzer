# Java Memory Analyzer - 架构文档

## 系统架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        用户接口层                                │
├─────────────────────────────┬───────────────────────────────────┤
│      GUI (Swing)            │         CLI (命令行)               │
│   - MemoryDashboard         │   - MemoryAnalyzerCli             │
│   - 实时监控面板             │   - 交互式命令                     │
│   - 可视化图表               │   - 批量处理                       │
└──────────────┬──────────────┴──────────────┬────────────────────┘
               │                             │
               ▼                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                        Java 应用层                               │
├──────────────────┬──────────────────┬───────────────────────────┤
│   进程附着模块    │    分析引擎      │      报告系统             │
│  ProcessAttacher │  HeapAnalyzer    │   ReportGenerator        │
│  AgentLoader     │  ObjectTracker   │   - HTML Builder          │
│                  │  LeakDetector    │   - JSON Builder          │
│                  │  TimeWindow      │   - CSV Builder           │
└──────────────────┴──────────────────┴───────────────────────────┘
               │                             │
               ▼                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                        JNI 桥接层                                │
│                NativeMemoryTracker                              │
└─────────────────────────────┬───────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     JVMTI Agent (C++)                            │
├──────────────────┬──────────────────┬───────────────────────────┤
│  事件捕获         │    对象跟踪       │      事件队列             │
│  - ObjectAlloc   │  AllocationTracker│  EventQueue (lock-free)  │
│  - ObjectFree    │  Stack Trace     │  异步处理                 │
│  - GC Events     │  Hash Table      │                          │
└──────────────────┴──────────────────┴───────────────────────────┘
```

## 核心模块设计

### 1. 进程附着模块 (attach)

**组件:**
- `ProcessAttacher`: 使用 Java Attach API 附着到目标 JVM
- `AgentLoader`: Java Agent 入口点，支持动态和静态加载

**流程:**
```
1. 列举 Java 进程 → VirtualMachine.list()
2. 选择目标进程 → VirtualMachine.attach(pid)
3. 加载 Agent → vm.loadAgent(agentPath, options)
4. Agent 初始化 → Agent_OnAttach() / Agent_OnLoad()
5. 注册 JVMTI 回调
```

### 2. JVMTI Agent (C++)

**关键数据结构:**

```cpp
// 事件队列 - 无锁环形缓冲区
class EventQueue {
    AllocationEvent buffer[EVENT_QUEUE_SIZE];
    std::atomic<size_t> head, tail, count;
};

// 对象跟踪器 - 线程安全的哈希表
class AllocationTracker {
    HashEntry* buckets[ALLOCATION_HASH_SIZE];
    std::mutex mutex;
    std::atomic<uint64_t> total_allocated, current_usage;
};
```

**事件回调:**
- `CallbackObjectAlloc`: 对象分配时触发
- `CallbackObjectFree`: 对象释放时触发
- `CallbackGarbageCollectionStart/Finish`: GC 事件

### 3. 堆分析模块 (heap)

**组件:**
- `HeapAnalyzer`: 主分析器，协调各组件
- `ObjectTracker`: 对象生命周期跟踪
- `AllocationRecorder`: 分配记录器

**数据流:**
```
JVMTI Event → NativeMemoryTracker → AllocationRecord
                                          ↓
                                    ObjectTracker
                                          ↓
                                    MemorySnapshot
```

### 4. 泄漏检测模块 (leak)

**检测策略:**

1. **基于年龄的检测**
   - 识别存活时间超过阈值的对象
   - 分组统计同类对象

2. **基于增长模式的检测**
   - 监控类实例数量增长
   - 识别异常增长模式

3. **基于时间窗口的检测**
   - 滑动窗口分析 (默认 10 个快照)
   - 线性回归计算增长斜率
   - 一致性增长判定

**算法:**
```java
// 时间窗口分析
for each snapshot in window:
    record class instance counts

calculate slope using linear regression:
    slope = (n*Σxy - Σx*Σy) / (n*Σx² - (Σx)²)

if growth_count >= threshold AND slope > 0:
    flag as potential leak
```

### 5. 可视化模块 (ui)

**组件:**
- `MemoryDashboard`: 主窗口
- `MemoryOverviewPanel`: 内存概览
- `ClassHistogramPanel`: 类直方图
- `LeakDetectionPanel`: 泄漏检测
- `AllocationTrackerPanel`: 分配跟踪

**刷新机制:**
- 使用 `ScheduledExecutorService` 定时刷新 (1 秒间隔)
- SwingUtilities.invokeLater 确保线程安全
- 数据更新节流 (避免频繁 UI 更新)

### 6. 报告系统 (report)

**报告生成流程:**
```
HeapAnalyzer → 收集数据
     ↓
ReportGenerator
     ↓
┌────┼────┐
↓    ↓    ↓
HTML JSON CSV
```

**HTML 报告包含:**
- 系统信息表格
- 内存使用概览 (含进度条)
- 类直方图 (Top 50)
- 分配站点统计
- GC 统计
- 泄漏检测结果

## 线程安全设计

### 并发控制策略

| 组件 | 策略 | 实现 |
|------|------|------|
| AllocationTracker | 分段锁 | Hash 桶 + Mutex |
| ObjectTracker | 读写锁 | ReentrantReadWriteLock |
| MemorySnapshot | 不可变对象 | Copy-on-Write |
| EventQueue | 无锁 | CAS 操作 |
| Counter | 原子变量 | LongAdder/AtomicLong |

### 线程模型

```
┌─────────────────────────────────────────────────────────┐
│                     Application Threads                  │
│  (GUI Event Dispatch, CLI, User Code)                    │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│                     Scheduler Thread                     │
│  (定时刷新，10Hz)                                         │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│                   Event Processor Thread                 │
│  (JVMTI 事件异步处理)                                       │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│                   Cleanup Thread                         │
│  (定期清理过期数据)                                        │
└─────────────────────────────────────────────────────────┘
```

## 性能优化

### 1. 采样策略

```cpp
// 可配置采样率
if (sampling_enabled && (alloc_counter % sampling_interval) != 0) {
    return; // 跳过此次分配
}
```

### 2. 本地缓冲

- Native 层维护事件队列
- 批量提交到 Java 层
- 减少 JNI 调用次数

### 3. 快速路径

```cpp
// 热点代码优化
static inline jlong get_current_timestamp() {
    // 使用 chrono 直接计算，避免系统调用
}
```

### 4. 数据结构优化

- 使用 `LongAdder` 替代 `AtomicLong` (高并发场景)
- 使用 `ConcurrentHashMap` 替代 `Hashtable`
- 使用环形缓冲区避免锁竞争

## 精度控制

### 误差来源分析

1. **采样误差**: 采样导致部分分配未被记录
   - 缓解：降低采样率或使用全量模式

2. **时间窗口误差**: 快照间的数据变化
   - 缓解：增加快照频率

3. **JVMTI 限制**: 部分事件可能丢失
   - 缓解：与 JMX 数据交叉验证

### 精度保证措施

- 定期与 `Runtime.totalMemory()` 对比校准
- 双重验证机制
- 误差监控和告警

## 安全脱离机制

### 脱离流程

```
1. 停止泄漏检测 → LeakDetector.stop()
2. 停止堆分析 → HeapAnalyzer.stopAnalysis()
3. 执行预脱离回调 → preDetachCallbacks
4. 清空引用 → null references
5. 执行后脱离回调 → postDetachCallbacks
6. 标记已脱离 → detached = true
```

### 信号处理

- 注册 JVM Shutdown Hook
- 捕获 SIGINT/SIGTERM/HUP 信号
- 确保资源正确释放

## 扩展点

### 自定义分配器钩子

```java
public interface AllocationHook {
    void onAllocation(AllocationRecord record);
    void onFree(long objectId);
}
```

### 自定义泄漏检测器

```java
public interface LeakStrategy {
    List<LeakCandidate> detect(ObjectTracker tracker);
}
```

### 自定义报告生成器

```java
public interface ReportBuilder {
    boolean build(HeapAnalyzer analyzer, String outputPath);
}
```

## 未来改进

1. **图形界面增强**
   - 内存使用趋势图
   - 火焰图展示热点分配
   - 快照对比视图

2. **分析能力提升**
   - 引用链分析
   - 支配树计算
   - 堆转储解析

3. **性能优化**
   - 增量式快照
   - 压缩存储
   - 分布式分析

4. **集成能力**
   - REST API
   - Prometheus 指标导出
   - IDE 插件
