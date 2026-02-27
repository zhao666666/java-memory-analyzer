/**
 * JVMTI Agent - Java Memory Analyzer
 *
 * A low-overhead memory analysis agent using JVMTI.
 * Tracks object allocations, deallocations, and provides stack traces.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */

#include <jvmti.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <pthread.h>
#include <unistd.h>
#include <signal.h>

#include <unordered_map>
#include <vector>
#include <string>
#include <mutex>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <queue>
#include <thread>
#include <fstream>

// ============================================================================
// Configuration
// ============================================================================

#define MAX_STACK_DEPTH 128
#define EVENT_QUEUE_SIZE 65536
#define ALLOCATION_HASH_SIZE 1000003
#define ENABLE_SAMPLING 1
#define SAMPLING_INTERVAL 10  // Sample every Nth allocation

// ============================================================================
// Data Structures
// ============================================================================

/**
 * Allocation information for each tracked object
 */
struct AllocationInfo {
    jlong size;
    jlong timestamp;
    jclass klass;
    jobject thread;
    jvmtiFrameInfo* frames;
    jint frame_count;
    uint64_t thread_id;
    uint32_t hash;

    AllocationInfo() : size(0), timestamp(0), klass(nullptr),
                       thread(nullptr), frames(nullptr), frame_count(0),
                       thread_id(0), hash(0) {}
};

/**
 * Event types for the event queue
 */
enum EventType {
    EVENT_ALLOC = 1,
    EVENT_FREE = 2,
    EVENT_GC_START = 3,
    EVENT_GC_FINISH = 4,
    EVENT_MONITOR = 5
};

/**
 * Event structure for lock-free queue
 */
struct AllocationEvent {
    EventType type;
    jlong tag;
    jlong size;
    jlong timestamp;
    jclass klass;
    jthread thread;
    jvmtiFrameInfo* frames;
    jint frame_count;
    uint64_t thread_id;

    AllocationEvent() : type(EVENT_ALLOC), tag(0), size(0), timestamp(0),
                        klass(nullptr), thread(nullptr), frames(nullptr),
                        frame_count(0), thread_id(0) {}
};

/**
 * Lock-free ring buffer for event queue
 * 无锁环形缓冲区（Ring Buffer）
 */
class EventQueue {
private:
    AllocationEvent buffer[EVENT_QUEUE_SIZE];
    std::atomic<size_t> head{0};
    std::atomic<size_t> tail{0};
    std::atomic<size_t> count{0};

public:
    bool push(const AllocationEvent& event) {
        size_t current_tail = tail.load(std::memory_order_relaxed);
        size_t next_tail = (current_tail + 1) % EVENT_QUEUE_SIZE;

        if (next_tail == head.load(std::memory_order_acquire)) {
            return false; // Queue full
        }

        buffer[current_tail] = event;
        tail.store(next_tail, std::memory_order_release);
        count.fetch_add(1, std::memory_order_relaxed);
        return true;
    }

    bool pop(AllocationEvent& event) {
        size_t current_head = head.load(std::memory_order_relaxed);

        if (current_head == tail.load(std::memory_order_acquire)) {
            return false; // Queue empty
        }

        event = buffer[current_head];
        head.store((current_head + 1) % EVENT_QUEUE_SIZE, std::memory_order_release);
        count.fetch_sub(1, std::memory_order_relaxed);
        return true;
    }

    size_t size() const {
        return count.load(std::memory_order_relaxed);
    }

    bool empty() const {
        return head.load(std::memory_order_relaxed) ==
               tail.load(std::memory_order_relaxed);
    }
};

/**
 * Thread-safe allocation tracker
 */
class AllocationTracker {
private:
    struct HashEntry {
        jlong tag;
        AllocationInfo info;
        HashEntry* next;

        HashEntry() : tag(0), next(nullptr) {}
    };

    HashEntry* buckets[ALLOCATION_HASH_SIZE];
    std::mutex mutex;
    std::atomic<uint64_t> total_allocated{0};
    std::atomic<uint64_t> total_freed{0};
    std::atomic<uint64_t> current_usage{0};
    std::atomic<uint64_t> alloc_count{0};
    std::atomic<uint64_t> free_count{0};

    uint32_t hash_tag(jlong tag) {
        return (uint32_t)(tag ^ (tag >> 32)) % ALLOCATION_HASH_SIZE;
    }

public:
    AllocationTracker() {
        memset(buckets, 0, sizeof(buckets));
    }

    void track(jlong tag, const AllocationInfo& info) {
        std::lock_guard<std::mutex> lock(mutex);

        uint32_t h = hash_tag(tag);
        HashEntry* entry = new HashEntry();
        entry->tag = tag;
        entry->info = info;
        entry->next = buckets[h];
        buckets[h] = entry;

        total_allocated.fetch_add(info.size, std::memory_order_relaxed);
        current_usage.fetch_add(info.size, std::memory_order_relaxed);
        alloc_count.fetch_add(1, std::memory_order_relaxed);
    }

    bool untrack(jlong tag, AllocationInfo& info) {
        std::lock_guard<std::mutex> lock(mutex);

        uint32_t h = hash_tag(tag);
        HashEntry* prev = nullptr;
        HashEntry* curr = buckets[h];

        while (curr) {
            if (curr->tag == tag) {
                if (prev) {
                    prev->next = curr->next;
                } else {
                    buckets[h] = curr->next;
                }

                info = curr->info;
                total_freed.fetch_add(info.size, std::memory_order_relaxed);
                current_usage.fetch_sub(info.size, std::memory_order_relaxed);
                free_count.fetch_add(1, std::memory_order_relaxed);

                delete curr;
                return true;
            }
            prev = curr;
            curr = curr->next;
        }
        return false;
    }

    AllocationInfo* find(jlong tag) {
        std::lock_guard<std::mutex> lock(mutex);

        uint32_t h = hash_tag(tag);
        HashEntry* curr = buckets[h];

        while (curr) {
            if (curr->tag == tag) {
                return &curr->info;
            }
            curr = curr->next;
        }
        return nullptr;
    }

    uint64_t get_total_allocated() const { return total_allocated.load(); }
    uint64_t get_total_freed() const { return total_freed.load(); }
    uint64_t get_current_usage() const { return current_usage.load(); }
    uint64_t get_alloc_count() const { return alloc_count.load(); }
    uint64_t get_free_count() const { return free_count.load(); }

    void get_snapshot(std::vector<std::pair<jlong, AllocationInfo>>& snapshot) {
        std::lock_guard<std::mutex> lock(mutex);

        for (int i = 0; i < ALLOCATION_HASH_SIZE; i++) {
            HashEntry* curr = buckets[i];
            while (curr) {
                snapshot.push_back({curr->tag, curr->info});
                curr = curr->next;
            }
        }
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mutex);

        for (int i = 0; i < ALLOCATION_HASH_SIZE; i++) {
            HashEntry* curr = buckets[i];
            while (curr) {
                HashEntry* next = curr->next;
                delete curr;
                curr = next;
            }
            buckets[i] = nullptr;
        }
    }
};

// ============================================================================
// Global State
// ============================================================================

static jvmtiEnv* g_jvmti = nullptr;
static JNIEnv* g_jni_env = nullptr;
static JavaVM* g_java_vm = nullptr;
static AllocationTracker g_tracker;
static EventQueue g_event_queue;

// Java class and method references for JNI callback
static jclass g_heap_analyzer_class = nullptr;
static jmethodID g_on_object_alloc_method = nullptr;

static std::atomic<bool> g_agent_active{true};
static std::atomic<bool> g_sampling_enabled{true};
static std::atomic<int> g_sampling_interval{10};
static std::atomic<uint64_t> g_alloc_counter{0};

static pthread_mutex_t g_print_mutex = PTHREAD_MUTEX_INITIALIZER;
static std::thread g_event_processor_thread;

// Callback function pointer type
typedef void (*EventCallback)(const AllocationEvent&);
static EventCallback g_event_callback = nullptr;

// ============================================================================
// Utility Functions
// ============================================================================

static inline jlong get_current_timestamp() {
    auto now = std::chrono::system_clock::now();
    auto duration = now.time_since_epoch();
    return std::chrono::duration_cast<std::chrono::milliseconds>(duration).count();
}

static inline uint64_t get_current_thread_id() {
    uint64_t thread_id = 0;
    pthread_mutex_lock(&g_print_mutex);
    thread_id = (uint64_t)(uintptr_t)pthread_self();
    pthread_mutex_unlock(&g_print_mutex);
    return thread_id;
}

static void safe_print(const char* msg) {
    pthread_mutex_lock(&g_print_mutex);
    fprintf(stderr, "[JVM TI] %s\n", msg);
    pthread_mutex_unlock(&g_print_mutex);
}

static void safe_print(const char* format, int value) {
    pthread_mutex_lock(&g_print_mutex);
    fprintf(stderr, "[JVM TI] ");
    fprintf(stderr, format, value);
    fprintf(stderr, "\n");
    pthread_mutex_unlock(&g_print_mutex);
}

// ============================================================================
// Stack Trace Capture
// ============================================================================

static jvmtiFrameInfo* capture_stack_trace(jvmtiEnv* jvmti, jint* frame_count,
                                           jint max_depth = MAX_STACK_DEPTH) {
    jvmtiFrameInfo* frames = (jvmtiFrameInfo*)malloc(sizeof(jvmtiFrameInfo) * max_depth);
    if (!frames) return nullptr;

    // GetStackTrace signature: GetStackTrace(jthread, jint startDepth, jint maxCount, jvmtiFrameInfo*, jint*)
    jvmtiError err = jvmti->GetStackTrace(NULL, 0, max_depth, frames, frame_count);

    if (JVMTI_ERROR_NONE != err) {
        free(frames);
        return nullptr;
    }

    if (*frame_count <= 0) {
        free(frames);
        return nullptr;
    }

    return frames;
}

static void free_stack_trace(jvmtiFrameInfo* frames) {
    if (frames) {
        free(frames);
    }
}

/**
 * Build stack trace string from jvmtiFrameInfo
 * Format: "class.method(file:line);class.method(file:line);..."
 */
static char* build_stack_trace_string(jvmtiEnv* jvmti, JNIEnv* jni,
                                       jvmtiFrameInfo* frames, jint frame_count) {
    if (!frames || frame_count <= 0) {
        return nullptr;
    }

    std::string result;
    for (int i = 0; i < frame_count && i < 20; i++) {  // Limit to 20 frames
        jvmtiFrameInfo* frame = &frames[i];

        // Get method name
        char* method_name = nullptr;
        char* method_sig = nullptr;
        char* method_generic = nullptr;
        if (jvmti->GetMethodName(frame->method, &method_name, &method_sig, &method_generic) != JVMTI_ERROR_NONE || !method_name) {
            method_name = (char*)"unknown";
        }

        // Get class name
        char* class_name = nullptr;
        jclass klass = nullptr;
        if (jvmti->GetMethodDeclaringClass(frame->method, &klass) == JVMTI_ERROR_NONE && klass) {
            jvmti->GetClassSignature(klass, &class_name, nullptr);
        }
        if (!class_name) {
            class_name = (char*)"unknown";
        }

        // Get line number - use GetLineNumberTable
        jint line_number = 0;
        jint table_count = 0;
        jvmtiLineNumberEntry* table = nullptr;
        if (jvmti->GetLineNumberTable(frame->method, &table_count, &table) == JVMTI_ERROR_NONE && table) {
            // Find the line number for the current location
            for (int j = 0; j < table_count; j++) {
                if (table[j].start_location <= frame->location) {
                    line_number = table[j].line_number;
                } else {
                    break;
                }
            }
            jvmti->Deallocate((unsigned char*)table);
        }

        // Get source file name
        char* source_file = nullptr;
        if (klass) {
            jvmti->GetSourceFileName(klass, &source_file);
        }
        if (!source_file) {
            source_file = (char*)"unknown";
        }

        // Build string: "class.method(file:line);"
        if (i > 0) result += ";";
        result += class_name;
        result += ".";
        result += method_name;
        result += "(";
        result += source_file;
        result += ":";
        result += std::to_string(line_number);
        result += ")";

        // Free JVMTI allocated memory
        if (method_name && strcmp(method_name, "unknown") != 0) {
            jvmti->Deallocate((unsigned char*)method_name);
        }
        if (method_sig) {
            jvmti->Deallocate((unsigned char*)method_sig);
        }
        if (method_generic) {
            jvmti->Deallocate((unsigned char*)method_generic);
        }
        if (class_name && strcmp(class_name, "unknown") != 0) {
            jvmti->Deallocate((unsigned char*)class_name);
        }
        if (source_file && strcmp(source_file, "unknown") != 0) {
            jvmti->Deallocate((unsigned char*)source_file);
        }
        if (klass) {
            jni->DeleteLocalRef(klass);
        }
    }

    // Copy to C string
    char* result_str = (char*)malloc(result.length() + 1);
    strcpy(result_str, result.c_str());
    return result_str;
}

// ============================================================================
// JVMTI Event Callbacks
// ============================================================================

/**
 * Object Allocation Event Handler
 */
// 这是 C++ 代码，运行在 JVM 内部
// 每当有对象分配，JVM 会自动调用这个回调函数
void JNICALL CallbackObjectAlloc(jvmtiEnv* jvmti_env, JNIEnv* jni_env,
                                  jthread thread, jobject object,
                                  jclass object_klass, jlong size) {
    if (!g_agent_active.load(std::memory_order_relaxed)) {
        return;
    }

    // Sampling check
    if (g_sampling_enabled.load(std::memory_order_relaxed)) {
        uint64_t counter = g_alloc_counter.fetch_add(1, std::memory_order_relaxed);
        if (counter % g_sampling_interval.load(std::memory_order_relaxed) != 0) {
            return;
        }
    }

    // Get object tag
    // Note: GetObjectTag/SetObjectTag removed in newer JDK, use object address as tag
    jlong tag = (jlong)(uintptr_t)object;

    // Capture stack trace
    jint frame_count = 0;
    jvmtiFrameInfo* frames = capture_stack_trace(jvmti_env, &frame_count);

    // Create allocation info
    AllocationInfo info;
    info.size = size;
    info.timestamp = get_current_timestamp();
    info.klass = object_klass;
    info.thread = thread;
    info.frames = frames;
    info.frame_count = frame_count;
    info.thread_id = get_current_thread_id();
    info.hash = (uint32_t)(tag ^ (tag >> 32));

    // Track allocation
    g_tracker.track(tag, info);

    // Create event
    AllocationEvent event;
    event.type = EVENT_ALLOC;
    event.tag = tag;
    event.size = size;
    event.timestamp = info.timestamp;
    event.klass = (jclass)jni_env->NewGlobalRef(object_klass);
    event.thread = (jthread)jni_env->NewGlobalRef(thread);
    event.frame_count = frame_count;
    event.thread_id = info.thread_id;

    // Copy frames
    if (frames && frame_count > 0) {
        event.frames = (jvmtiFrameInfo*)malloc(sizeof(jvmtiFrameInfo) * frame_count);
        memcpy(event.frames, frames, sizeof(jvmtiFrameInfo) * frame_count);
    }

    // Push to event queue
    g_event_queue.push(event);

    // Call callback if registered
    if (g_event_callback) {
        g_event_callback(event);
    }

    // ===== Call Java layer via JNI =====
    // Notify Java layer about this allocation
    if (g_java_vm && g_heap_analyzer_class && g_on_object_alloc_method) {
        JNIEnv* env = nullptr;
        bool attached = false;

        // Get JNIEnv for current thread
        if (g_java_vm->GetEnv((void**)&env, JNI_VERSION_1_8) != JNI_OK) {
            // Not attached, attach current thread
            if (g_java_vm->AttachCurrentThread((void**)&env, nullptr) == JNI_OK) {
                attached = true;
            }
        }

        if (env) {
            // Get class name
            char* class_sig = nullptr;
            jvmti_env->GetClassSignature(object_klass, &class_sig, nullptr);
            std::string class_name = class_sig ? class_sig : "unknown";
            // Remove L and ; from signature (e.g., "Ljava/lang/String;" -> "java/lang/String")
            if (class_name[0] == 'L') {
                class_name = class_name.substr(1, class_name.length() - 2);
            }

            // Build stack trace string
            char* stack_trace = build_stack_trace_string(jvmti_env, env, frames, frame_count);

            // Get thread info
            std::string thread_name = "unknown";
            jlong thread_id = get_current_thread_id();

            // Call Java method
            jstring classNameStr = env->NewStringUTF(class_name.c_str());
            jstring threadNameStr = env->NewStringUTF(thread_name.c_str());
            jstring stackTraceStr = stack_trace ? env->NewStringUTF(stack_trace) : nullptr;

            env->CallStaticVoidMethod(
                g_heap_analyzer_class,
                g_on_object_alloc_method,
                tag,
                classNameStr,
                size,
                thread_id,
                threadNameStr,
                stackTraceStr
            );

            // Clean up local refs
            env->DeleteLocalRef(classNameStr);
            env->DeleteLocalRef(threadNameStr);
            if (stackTraceStr) env->DeleteLocalRef(stackTraceStr);
            if (class_sig) jvmti_env->Deallocate((unsigned char*)class_sig);
            if (stack_trace) free(stack_trace);

            // Detach if we attached
            if (attached) {
                g_java_vm->DetachCurrentThread();
            }
        }
    }
}

/**
 * Garbage Collection Start Event Handler
 */
void JNICALL CallbackGarbageCollectionStart(jvmtiEnv* jvmti_env) {
    if (!g_agent_active.load(std::memory_order_relaxed)) {
        return;
    }

    AllocationEvent event;
    event.type = EVENT_GC_START;
    event.timestamp = get_current_timestamp();
    g_event_queue.push(event);
}

/**
 * Garbage Collection Finish Event Handler
 */
void JNICALL CallbackGarbageCollectionFinish(jvmtiEnv* jvmti_env) {
    if (!g_agent_active.load(std::memory_order_relaxed)) {
        return;
    }

    AllocationEvent event;
    event.type = EVENT_GC_FINISH;
    event.timestamp = get_current_timestamp();
    g_event_queue.push(event);
}

/**
 * Object Free Event Handler (VMObjectFree)
 * Note: This requires -XX:+UnlockDiagnosticVMOptions -XX:+TrackObjectFree
 */
void JNICALL CallbackObjectFree(jvmtiEnv* jvmti_env, jlong tag) {
    if (!g_agent_active.load(std::memory_order_relaxed)) {
        return;
    }

    AllocationInfo info;
    if (g_tracker.untrack(tag, info)) {
        AllocationEvent event;
        event.type = EVENT_FREE;
        event.tag = tag;
        event.size = info.size;
        event.timestamp = get_current_timestamp();
        event.thread_id = get_current_thread_id();
        g_event_queue.push(event);
    }
}

/**
 * VM Death Event Handler
 */
void JNICALL CallbackVMDeath(jvmtiEnv* jvmti_env, JNIEnv* jni_env) {
    g_agent_active.store(false, std::memory_order_release);
    safe_print("VM Death - Agent shutting down");
}

// ============================================================================
// Event Processor Thread
// ============================================================================

static void event_processor_loop() {
    while (g_agent_active.load(std::memory_order_acquire)) {
        AllocationEvent event;

        if (g_event_queue.pop(event)) {
            // Process event
            switch (event.type) {
                case EVENT_ALLOC:
                    // Allocation events are already tracked
                    break;
                case EVENT_FREE:
                    // Free events are already processed
                    break;
                case EVENT_GC_START:
                    safe_print("GC Start detected");
                    break;
                case EVENT_GC_FINISH:
                    safe_print("GC Finish detected");
                    break;
                default:
                    break;
            }

            // Cleanup global refs
            if (event.klass && g_jni_env) {
                g_jni_env->DeleteGlobalRef(event.klass);
            }
            if (event.thread && g_jni_env) {
                g_jni_env->DeleteGlobalRef(event.thread);
            }

            // Free frames
            if (event.frames) {
                free(event.frames);
            }
        } else {
            // No events, sleep briefly
            std::this_thread::sleep_for(std::chrono::microseconds(100));
        }
    }
}

// ============================================================================
// Agent Commands (Communication with Java layer)
// ============================================================================

static void process_agent_command(const char* command) {
    if (strncmp(command, "sampling:", 9) == 0) {
        int interval = atoi(command + 9);
        if (interval > 0) {
            g_sampling_interval.store(interval, std::memory_order_release);
            safe_print("Sampling interval set to %d", interval);
        }
    } else if (strcmp(command, "snapshot") == 0) {
        // Trigger snapshot
        safe_print("Snapshot command received");
    } else if (strcmp(command, "stop") == 0) {
        g_agent_active.store(false, std::memory_order_release);
        safe_print("Stop command received");
    }
}

/**
 * Execute agent command - called from Java via JVMTI
 */
static void ExecuteCommand(jvmtiEnv* jvmti_env, const char* command) {
    process_agent_command(command);
}

// ============================================================================
// Agent Initialization
// ============================================================================

/**
 * Enable required JVMTI capabilities
 */
static jvmtiError enable_capabilities(jvmtiEnv* jvmti) {
    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof(caps));

    // Core capabilities for memory analysis
    // Note: can_generate_allocation_samples and can_generate_vm_object_alloc_events
    // are not available in all JDK versions. We use the generic events capability.
    caps.can_generate_all_class_hook_events = 1;
    caps.can_generate_object_free_events = 1;
    caps.can_generate_garbage_collection_events = 1;
    caps.can_tag_objects = 1;
    caps.can_get_owned_monitor_info = 1;
    caps.can_get_current_contended_monitor = 1;
    caps.can_get_source_file_name = 1;
    caps.can_get_line_numbers = 1;

    return jvmti->AddCapabilities(&caps);
}

/**
 * Set up JVMTI event callbacks
 */
static void setup_callbacks(jvmtiEnv* jvmti) {
    jvmtiEventCallbacks callbacks;
    memset(&callbacks, 0, sizeof(callbacks));

    callbacks.VMObjectAlloc = CallbackObjectAlloc;
    callbacks.ObjectFree = CallbackObjectFree;
    callbacks.GarbageCollectionStart = CallbackGarbageCollectionStart;
    callbacks.GarbageCollectionFinish = CallbackGarbageCollectionFinish;
    callbacks.VMDeath = CallbackVMDeath;

    jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
}

/**
 * Enable events
 */
static void enable_events(jvmtiEnv* jvmti) {
    jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_OBJECT_ALLOC, nullptr);
    jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_OBJECT_FREE, nullptr);
    jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_GARBAGE_COLLECTION_START, nullptr);
    jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_GARBAGE_COLLECTION_FINISH, nullptr);
    jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_DEATH, nullptr);
}

/**
 * Native method registration
 */
static void register_native_methods(jvmtiEnv* jvmti, JNIEnv* env) {
    // Register native methods for Java communication
    // This allows Java layer to send commands to the agent
}

// ============================================================================
// Agent Entry Points
// ============================================================================

/**
 * Agent_OnAttach - Called when agent is dynamically attached to a running VM
 */
JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options, void* reserved) {
    fprintf(stderr, "[JVM TI] Agent_OnAttach called, options: %s\n", options ? options : "none");

    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_8) != JNI_OK) {
        fprintf(stderr, "[JVM TI] Failed to get JNIEnv\n");
        return JNI_ERR;
    }
    g_jni_env = env;  // Save global JNI env
    g_java_vm = vm;   // Save Java VM pointer

    if (vm->GetEnv((void**)&g_jvmti, JNI_VERSION_1_8) != JNI_OK) {
        fprintf(stderr, "[JVM TI] Failed to get JVMTI env\n");
        return JNI_ERR;
    }

    // Find HeapAnalyzer class and onObjectAlloc method for JNI callback
    jclass ha_class = env->FindClass("com/jvm/analyzer/heap/HeapAnalyzer");
    if (ha_class) {
        g_heap_analyzer_class = (jclass)env->NewGlobalRef(ha_class);
        g_on_object_alloc_method = env->GetStaticMethodID(
            g_heap_analyzer_class,
            "onObjectAlloc",
            "(JLjava/lang/String;JJLjava/lang/String;Ljava/lang/String;)V"
        );
        if (g_on_object_alloc_method) {
            fprintf(stderr, "[JVM TI] Found onObjectAlloc method for callback\n");
        } else {
            fprintf(stderr, "[JVM TI] Warning: onObjectAlloc method not found\n");
        }
        env->DeleteLocalRef(ha_class);
    } else {
        fprintf(stderr, "[JVM TI] Warning: HeapAnalyzer class not found\n");
    }

    // Parse options
    if (options) {
        char* opt = strtok(options, ",");
        while (opt) {
            if (strncmp(opt, "sampling=", 9) == 0) {
                int interval = atoi(opt + 9);
                if (interval > 0) {
                    g_sampling_interval.store(interval, std::memory_order_release);
                }
            } else if (strcmp(opt, "nosampling") == 0) {
                g_sampling_enabled.store(false, std::memory_order_release);
            }
            opt = strtok(nullptr, ",");
        }
    }

    // Enable capabilities
    jvmtiError err = enable_capabilities(g_jvmti);
    if (err != JVMTI_ERROR_NONE) {
        fprintf(stderr, "[JVM TI] Failed to enable capabilities: %d\n", err);
        return JNI_ERR;
    }

    // Setup callbacks
    setup_callbacks(g_jvmti);

    // Enable events
    enable_events(g_jvmti);

    // Start event processor thread
    g_event_processor_thread = std::thread(event_processor_loop);

    fprintf(stderr, "[JVM TI] Agent successfully attached\n");
    return JNI_OK;
}

/**
 * Agent_OnLoad - Called when agent is loaded at VM startup via -agentpath
 */
JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options, void* reserved) {
    fprintf(stderr, "[JVM TI] Agent_OnLoad called, options: %s\n", options ? options : "none");

    // Get JNIEnv - in some VMs this might fail at very early stage
    // We need to handle this gracefully
    g_java_vm = vm;

    // Try to get JVMTI interface directly from JavaVM
    // This is the recommended way for JVMTI agents
    jint jvmtiResult = vm->GetEnv((void**)&g_jvmti, JVMTI_VERSION_1_0);
    if (jvmtiResult != JNI_OK) {
        fprintf(stderr, "[JVM TI] GetEnv for JVMTI returned %d, trying JNI_VERSION_1_8...\n", jvmtiResult);
        // Try with JNI_VERSION_1_8 as fallback
        if (vm->GetEnv((void**)&g_jvmti, JNI_VERSION_1_8) != JNI_OK) {
            fprintf(stderr, "[JVM TI] Failed to get JVMTI env\n");
            return JNI_ERR;
        }
    }

    fprintf(stderr, "[JVM TI] JVMTI interface obtained\n");

    // Try to get JNIEnv (might not be available in all VMs at this stage)
    if (vm->GetEnv((void**)&g_jni_env, JNI_VERSION_1_8) == JNI_OK) {
        fprintf(stderr, "[JVM TI] JNIEnv available\n");

        // Try to find HeapAnalyzer class (may not be available at this early stage)
        jclass ha_class = g_jni_env->FindClass("com/jvm/analyzer/heap/HeapAnalyzer");
        if (ha_class) {
            g_heap_analyzer_class = (jclass)g_jni_env->NewGlobalRef(ha_class);
            g_on_object_alloc_method = g_jni_env->GetStaticMethodID(
                g_heap_analyzer_class,
                "onObjectAlloc",
                "(JLjava/lang/String;JJLjava/lang/String;Ljava/lang/String;)V"
            );
            if (g_on_object_alloc_method) {
                fprintf(stderr, "[JVM TI] Found onObjectAlloc method for callback\n");
            }
            g_jni_env->DeleteLocalRef(ha_class);
        } else {
            fprintf(stderr, "[JVM TI] HeapAnalyzer class not found (will try later)\n");
        }
    } else {
        fprintf(stderr, "[JVM TI] JNIEnv not available at load time, will use JNI callbacks\n");
    }

    // Parse options
    if (options) {
        char* opt = strtok(options, ",");
        while (opt) {
            if (strncmp(opt, "sampling=", 9) == 0) {
                int interval = atoi(opt + 9);
                if (interval > 0) {
                    g_sampling_interval.store(interval, std::memory_order_release);
                }
            } else if (strcmp(opt, "nosampling") == 0) {
                g_sampling_enabled.store(false, std::memory_order_release);
            }
            opt = strtok(nullptr, ",");
        }
    }

    // Enable capabilities
    jvmtiError err = enable_capabilities(g_jvmti);
    if (err != JVMTI_ERROR_NONE) {
        fprintf(stderr, "[JVM TI] Failed to enable capabilities: %d\n", err);
        return JNI_ERR;
    }
    fprintf(stderr, "[JVM TI] Capabilities enabled\n");

    // Setup callbacks
    setup_callbacks(g_jvmti);

    // Enable events
    enable_events(g_jvmti);
    fprintf(stderr, "[JVM TI] Events enabled\n");

    // Start event processor thread
    g_event_processor_thread = std::thread(event_processor_loop);

    fprintf(stderr, "[JVM TI] Agent successfully loaded\n");
    return JNI_OK;
}

/**
 * Agent_OnUnload - Called when agent is unloaded
 */
JNIEXPORT void JNICALL Agent_OnUnload(JavaVM* vm) {
    fprintf(stderr, "[JVM TI] Agent_OnUnload called\n");

    g_agent_active.store(false, std::memory_order_release);

    if (g_event_processor_thread.joinable()) {
        g_event_processor_thread.join();
    }

    // Cleanup
    g_tracker.clear();

    if (g_jvmti) {
        g_jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_VM_OBJECT_ALLOC, nullptr);
        g_jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_OBJECT_FREE, nullptr);
        g_jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_GARBAGE_COLLECTION_START, nullptr);
        g_jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_GARBAGE_COLLECTION_FINISH, nullptr);
    }

    // Clean up global refs
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_8) == JNI_OK && env) {
        if (g_heap_analyzer_class) {
            env->DeleteGlobalRef(g_heap_analyzer_class);
            g_heap_analyzer_class = nullptr;
        }
    }

    g_jvmti = nullptr;
    g_jni_env = nullptr;
    g_java_vm = nullptr;
    g_on_object_alloc_method = nullptr;

    fprintf(stderr, "[JVM TI] Agent unloaded\n");
}

// ============================================================================
// Exported Functions for Java JNI Calls
// ============================================================================

extern "C" {

/**
 * Get current memory usage statistics
 */
JNIEXPORT void JNICALL Java_com_jvm_analyzer_core_NativeMemoryTracker_getMemoryStats
    (JNIEnv* env, jclass clazz, jlongArray stats) {

    jlong* stats_arr = env->GetLongArrayElements(stats, nullptr);
    if (stats_arr && env->GetArrayLength(stats) >= 5) {
        stats_arr[0] = (jlong)g_tracker.get_total_allocated();
        stats_arr[1] = (jlong)g_tracker.get_total_freed();
        stats_arr[2] = (jlong)g_tracker.get_current_usage();
        stats_arr[3] = (jlong)g_tracker.get_alloc_count();
        stats_arr[4] = (jlong)g_tracker.get_free_count();
    }
    env->ReleaseLongArrayElements(stats, stats_arr, 0);
}

/**
 * Send command to agent
 */
JNIEXPORT void JNICALL Java_com_jvm_analyzer_core_NativeMemoryTracker_sendCommand
    (JNIEnv* env, jclass clazz, jstring command) {

    const char* cmd = env->GetStringUTFChars(command, nullptr);
    if (cmd) {
        process_agent_command(cmd);
        env->ReleaseStringUTFChars(command, cmd);
    }
}

/**
 * Check if agent is active
 */
JNIEXPORT jboolean JNICALL Java_com_jvm_analyzer_core_NativeMemoryTracker_isAgentActive
    (JNIEnv* env, jclass clazz) {
    return g_agent_active.load(std::memory_order_relaxed) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Get event queue size
 */
JNIEXPORT jint JNICALL Java_com_jvm_analyzer_core_NativeMemoryTracker_getEventQueueSize
    (JNIEnv* env, jclass clazz) {
    return (jint)g_event_queue.size();
}

/**
 * Set sampling interval
 */
JNIEXPORT void JNICALL Java_com_jvm_analyzer_core_NativeMemoryTracker_setSamplingInterval
    (JNIEnv* env, jclass clazz, jint interval) {
    if (interval > 0) {
        g_sampling_interval.store(interval, std::memory_order_release);
        g_sampling_enabled.store(true, std::memory_order_release);
    } else {
        g_sampling_enabled.store(false, std::memory_order_release);
    }
}

} // extern "C"
