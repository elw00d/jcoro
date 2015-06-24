package org.jcoro;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;

/**
 * @author elwood
 */
public class InstrumentProgram {
    public static void main(String[] args) throws IOException {
        InstrumentProgram analyzer= new InstrumentProgram();
        ArrayList<String> jarPaths = new ArrayList<>();
        jarPaths.add("jcoro-app/build/libs/jcoro-app-1.0.jar");
        analyzer.analyzeJars(jarPaths);
    }

    public void analyzeJars(List<String> jarPaths) throws IOException {
        // Первый проход алгоритма
        for (String jarPath : jarPaths) {
            JarFile jarFile = new JarFile(new File(jarPath));
            Enumeration<JarEntry> jarEntries = jarFile.entries();
            while (jarEntries.hasMoreElements()) {
                JarEntry jarEntry = jarEntries.nextElement();
                if (jarEntry.getName().contains("org/jcoro/tests/simpletest2/") && jarEntry.getName().contains(".class")) {
                    byte[] bytes = new byte[(int) jarEntry.getSize()];
                    try (InputStream inputStream = jarFile.getInputStream(jarEntry)) {
                        int totalReaded = 0;
                        while (totalReaded < bytes.length) {
                            int readed = inputStream.read(bytes);
                            if (readed == -1) throw new RuntimeException("Can't read jar entry");
                            totalReaded += readed;
                        }
                    }
                    transform(bytes);
                }
            }
        }
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
        if (methodId.className.equals("org/jcoro/tests/simpletest2/TestCoro$1")
                && methodId.methodName.equals("foo")) {
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
    static boolean isRestorePoint(MethodId methodId) {
        // todo: реализовать настоящее поведение вместо заглушки
        if (methodId.className.equals("org/jcoro/Coro") && methodId.methodName.equals("yield"))
            return true;
        if (methodId.className.equals("org/jcoro/tests/simpletest2/TestCoro$1")
                && methodId.methodName.equals("foo"))
            return true;
        return false;
    }

    private String className;
    private boolean wasModified; // Были ли на самом деле изменения в классе

    private void transform(byte[] bytes) {
        className = null;
        wasModified = false;

        Map<MethodId, Integer> restorePointsCounts = new HashMap<>();
        Map<MethodId, Frame[]> framesMap = new HashMap<>();
        Map<MethodId, AbstractInsnNode[]> insnsMap = new HashMap<>();

        // Сначала посчитаем для каждого метода кол-во точек восстановления внутри него
        // Это необходимо для генерации кода switch в начале метода
        ClassReader countingReader = new ClassReader(bytes);
        countingReader.accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                // Сохраняем имя класса в field, а также определяем, реализует ли этот класс ICoroRunnable
                className = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor methodVisitor = super.visitMethod(access, name, desc, signature, exceptions);

                // todo : научиться распознавать классы, отнаследованные от ICoroRunnable
                if (!className.equals("org/jcoro/tests/simpletest2/TestCoro$1")) {
                    return methodVisitor;
                }

                return new MethodAnalyzer(Opcodes.ASM5, methodVisitor, access,
                        className, name, desc, signature, exceptions,
                        restorePointsCounts, framesMap, insnsMap, bytes);
            }
        }, 0);

        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS /*| ClassWriter.COMPUTE_FRAMES*/) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                try {
                    return super.getCommonSuperClass(type1, type2);
                } catch (RuntimeException e) {
                    // todo : убедиться в том, что всегда возвращать Object здесь - безопасно
                    return "java/lang/Object";
                }
            }
        };
        ClassVisitor adapter = new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                // todo : научиться распознавать классы, отнаследованные от ICoroRunnable
                if (!className.equals("org/jcoro/tests/simpletest2/TestCoro$1"))
                    return super.visitMethod(access, name, desc, signature, exceptions);

                MethodId methodId = new MethodId(className, name, desc);

                // Если метод не нужно инструментировать, ничего и не делаем
                if (!needInstrument(methodId)) return super.visitMethod(access, name, desc, signature, exceptions);

                // Если внутри метода нет ни одной точки восстановления - также можем не инструментировать его
                if (!restorePointsCounts.containsKey(methodId)) return super.visitMethod(access, name, desc, signature, exceptions);

                wasModified = true;

                // Если метод - первый, кого зовёт движок сопрограмм, то его нужно интерпретировать как статический
                // чтобы он не записывал this в стек при сохранении фрейма, т.к. подкладывать this под вызов уже будет некому -
                // мы вызываем run() напрямую
                boolean methodImplementsICoroRunnable = "run".equals(name); // todo :

                return new MethodAdapter(Opcodes.ASM5, super.visitMethod(access, name, desc, signature, exceptions),
                        restorePointsCounts.get(methodId), insnsMap.get(methodId), framesMap.get(methodId),
                        (access & Opcodes.ACC_STATIC) == Opcodes.ACC_STATIC, methodImplementsICoroRunnable);
            }
        };
        try {
            reader.accept(adapter, 0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
        byte[] transformed = writer.toByteArray();

        if (wasModified) {
            try {
                Files.write(
                        //Paths.get(className.replaceAll("/", ".") + ".class"),
                        Paths.get("TestCoro$1.class"),
                        transformed);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
