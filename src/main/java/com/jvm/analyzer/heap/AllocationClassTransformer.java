package com.jvm.analyzer.heap;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;

/**
 * Allocation Class Transformer - Instruments classes for object allocation tracking
 *
 * Uses ASM to modify bytecode and insert callbacks in object constructors.
 * This allows capturing object allocations without relying on JVMTI events.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class AllocationClassTransformer implements ClassFileTransformer {

    // Classes to exclude from instrumentation
    private static final Set<String> EXCLUDED_CLASSES = new HashSet<>();

    static {
        // Exclude internal classes
        EXCLUDED_CLASSES.add("java/lang/Object");
        EXCLUDED_CLASSES.add("java/lang/String");
        EXCLUDED_CLASSES.add("java/lang/Class");
        EXCLUDED_CLASSES.add("java/lang/Thread");
        EXCLUDED_CLASSES.add("java/lang/System");
        EXCLUDED_CLASSES.add("java/lang/Runnable");
        EXCLUDED_CLASSES.add("java/lang/Cloneable");
        EXCLUDED_CLASSES.add("java/lang/Comparable");
        EXCLUDED_CLASSES.add("java/lang/Throwable");
        EXCLUDED_CLASSES.add("java/lang/Exception");
        EXCLUDED_CLASSES.add("java/lang/RuntimeException");
        EXCLUDED_CLASSES.add("java/lang/Error");
        EXCLUDED_CLASSES.add("java/lang/StringBuilder");
        EXCLUDED_CLASSES.add("java/lang/StringFactory");

        // Exclude IntelliJ IDEA debugger classes to prevent conflicts
        EXCLUDED_CLASSES.add("com/intellij/rt/debugger/agent/");
        EXCLUDED_CLASSES.add("com/jvm/analyzer/heap/AllocationClassTransformer");
        EXCLUDED_CLASSES.add("com/jvm/analyzer/heap/InstrumentedAllocationRecorder");

        // Exclude array classes
        EXCLUDED_CLASSES.add("[");
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                           Class<?> classBeingRedefined,
                           ProtectionDomain protectionDomain,
                           byte[] classfileBuffer) {
        // Skip excluded classes
        if (className == null || isExcluded(className)) {
            return null;
        }

        // Skip arrays
        if (className.startsWith("[")) {
            return null;
        }

        // Skip core Java classes that are loaded very early
        // These classes are needed for basic JVM operation
        if (className.startsWith("java/") || className.startsWith("javax/") ||
            className.startsWith("sun/") || className.startsWith("jdk/")) {
            return null;
        }

        try {
            // Transform the class
            return transformClass(classfileBuffer);
        } catch (Exception e) {
            // Log error but don't fail the transformation
            System.err.println("[AllocationClassTransformer] Error transforming " + className + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Check if class should be excluded
     */
    private boolean isExcluded(String className) {
        for (String excluded : EXCLUDED_CLASSES) {
            if (className.startsWith(excluded)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Transform class bytecode
     */
    private byte[] transformClass(byte[] classfileBuffer) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
        ClassNode cn = new ClassNode();

        // First pass: analyze class structure
        cr.accept(cn, 0);

        // Check if class has any constructors to instrument
        boolean hasConstructor = false;
        for (MethodNode method : cn.methods) {
            if ("<init>".equals(method.name)) {
                hasConstructor = true;
                break;
            }
        }

        if (!hasConstructor) {
            // No constructors to instrument, return original
            return classfileBuffer;
        }

        // Second pass: transform constructors
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            private String className;
            private String superName;

            @Override
            public void visit(int version, int access, String name, String signature,
                             String superName, String[] interfaces) {
                this.className = name;
                this.superName = superName;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                            String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                // Only instrument constructors (not static initializers)
                if ("<init>".equals(name)) {
                    return new AllocationTrackingMethodVisitor(mv, className, access, descriptor);
                }

                return mv;
            }
        }, 0);

        return cw.toByteArray();
    }

    /**
     * Method visitor that instruments constructors
     */
    private class AllocationTrackingMethodVisitor extends MethodVisitor {

        private final String className;
        private final int access;
        private final String descriptor;

        public AllocationTrackingMethodVisitor(MethodVisitor mv, String className,
                                                int access, String descriptor) {
            super(Opcodes.ASM9, mv);
            this.className = className;
            this.access = access;
            this.descriptor = descriptor;
        }

        @Override
        public void visitInsn(int opcode) {
            // Check for constructor return (AFTER the INVOKESPECIAL to super/this)
            if (opcode == Opcodes.RETURN) {
                // Insert allocation tracking call before return
                insertAllocationCallback();
            }
            super.visitInsn(opcode);
        }

        /**
         * Insert callback to track allocation
         * This is called at the end of constructor, when 'this' is fully initialized
         */
        private void insertAllocationCallback() {
            // Stack manipulation to call InstrumentedAllocationRecorder.recordAllocation()

            // Push 'this' reference
            mv.visitVarInsn(Opcodes.ALOAD, 0);

            // Call the static method
            // Signature: recordAllocation(Object):void
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "com/jvm/analyzer/heap/InstrumentedAllocationRecorder",
                "recordAllocation",
                "(Ljava/lang/Object;)V",
                false
            );
        }
    }
}
