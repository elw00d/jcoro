package org.jcoro;

import org.objectweb.asm.*;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.ProtectionDomain;

import static org.objectweb.asm.Opcodes.*;

/**
 * @author elwood
 */
public class Agent {
    public static void premain(String args, Instrumentation inst) {
        Class[] allLoadedClasses = inst.getAllLoadedClasses();
        System.out.println("allLoadedClasses.size " + allLoadedClasses.length);
        for (Class loadedClass : allLoadedClasses) {
            if (loadedClass.getCanonicalName() != null &&
                    loadedClass.getCanonicalName().contains("jcoro")) {
                System.out.println("Class: " + loadedClass);
            }
        }

        inst.addTransformer(new MyClassFileTransformer());
        System.out.println("Premain executed ");
    }

    /**
     * Нужно ли инструментировать код метода methodId
     */
    private static boolean needInstrument(MethodId methodId) {
        // todo: реализовать настоящее поведение вместо заглушки
        if (methodId.className.equals("org/jcoro/tests/simpletest2/TestCoro$1")
                && methodId.methodName.equals("run")) {
            return true;
        }
        return false;
    }

    /**
     * Нужно ли рассматривать вызов метода methodId как вызов, внутри которого может произойти вызов yield(),
     * или такого не может произойти ? Если есть вероятность, что при вызове methodId произойдёт вызов yield(),
     * то мы должны рассматривать этот вызов как точку восстановления, и обрамлять вызов метода соответствующим
     * байткодом.
     */
    private static boolean isRestorePoint(MethodId methodId) {
        // todo: реализовать настоящее поведение вместо заглушки
        if (methodId.className.equals("org/jcoro/Coro") && methodId.methodName.equals("yield"))
            return true;
        return false;
    }

    private static class MyClassFileTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader,
                                String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) throws IllegalClassFormatException {
            // todo : научиться распознавать классы, отнаследованные от ICoroRunnable
            if (!className.equals("org/jcoro/tests/simpletest2/TestCoro"))
                return classfileBuffer;

            System.out.println("Transforming class " + className);
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            ClassVisitor adapter = new ClassVisitor(Opcodes.ASM5, writer) {
                @Override
                public void visitSource(String source, String debug) {
                    visitField(ACC_PUBLIC | ACC_STATIC, "genStr",
                            Type.getDescriptor(String.class), null, "Gen string!");
                    super.visitSource(source, debug);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    MethodId methodId = new MethodId(className, name, desc);
                    if (!needInstrument(methodId)) return super.visitMethod(access, name, desc, signature, exceptions);

                    return new MethodVisitor(Opcodes.ASM5, super.visitMethod(access, name, desc, signature, exceptions)) {
                        @Override
                        public void visitCode() {
                            // if (Coro.get() == null) goto noActiveCoroLabel;
                            visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "get", "()Lorg/jcoro/Coro;", false);
                            Label noActiveCoroLabel = new Label();
                            visitJumpInsn(Opcodes.IFEQ, noActiveCoroLabel);
                            // switch (state)

                            // todo :

                            // noActiveCoroLabel:
                            visitLabel(noActiveCoroLabel);
                            super.visitCode();
                        }
                    };
                }
            };
            reader.accept(adapter, 0);
            byte[] transformed = writer.toByteArray();

            //
            try {
                Files.write(
                        Paths.get(className.replaceAll("/", ".") + ".class"),
                        transformed);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            return transformed;
        }
    }
}
