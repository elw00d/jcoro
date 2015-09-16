package org.jcoro;

import org.objectweb.asm.*;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author elwood
 */
public class Program {
    private static URLClassLoader classLoader;

    private static String sourceDirPath;
    private static String destDirPath;

    /**
     * Должно быть 4 аргумента:
     * --source src --dest dst
     */
    public static void main(String[] args) {
        System.out.println("Instrumenting program started");

        if (args.length != 4 || !args[0].equals("--source") || !args[2].equals("--dest")) {
            System.out.println("Usage: program --source <src> --dest <dst>");
            return;
        }

        sourceDirPath = args[1];
        destDirPath = args[3];

        prepareEnv();
        new Program().instrumentClasses();
    }

    /**
     * Validates sourceDir and destDir.
     * Checks if sourceDir exists; creates destDir if doesn't exist.
     */
    private static void prepareEnv() {
        File sourceDir = new File(sourceDirPath);
        if (!sourceDir.exists()) {
            System.out.println("Source directory not found");
            System.exit(-1);
        }
        File destDir = new File(destDirPath);
        if (!destDir.exists()) {
            if (!destDir.mkdirs()) {
                System.out.println("Cannot create destination directory");
                System.exit(-1);
            }
        }
    }

    private void instrumentClasses() {
        File sourceDir = new File(sourceDirPath);
        List<File> allClassFiles = new ArrayList<>();
        collectClassFilesRecursively(allClassFiles, sourceDir);

        // Initialize classloader
        try {
            classLoader = new URLClassLoader(
                    new URL[]{sourceDir.toURI().toURL()},
                    Thread.currentThread().getContextClassLoader()
            );
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }

        for (File classFile : allClassFiles) {
            final byte[] bytes;
            try {
                bytes = Files.readAllBytes(classFile.toPath());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            final TransformResult transformResult = transform(bytes);

            final String[] parts = transformResult.getClassName().split("/");
            final String onlyName = parts[parts.length - 1];

            // Create all directories for package
            File dir = new File(destDirPath);
            for (int i = 0; i < parts.length - 1; i++) {
                String subdirName = parts[i];
                File subdir = new File(dir, subdirName);
                if (!subdir.exists()) {
                    if (!subdir.mkdir()) throw new RuntimeException("Cannot create directory: " + subdir.getPath());
                }
                dir = subdir;
            }

            // Create class file
            File transformedClassFile = new File(dir, onlyName + ".class");
            if (transformedClassFile.exists()) {
                if (!transformedClassFile.delete()) throw new RuntimeException("Cannot delete file: " + transformedClassFile.getPath());
            }
            try {
                if (!transformedClassFile.createNewFile())
                    throw new RuntimeException("Cannot create new file: " + transformedClassFile.getPath());
            } catch (IOException e) {
                throw new RuntimeException("Cannot create new file: " + transformedClassFile.getPath(), e);
            }

            try {
                Files.write(transformedClassFile.toPath(), transformResult.getData(), StandardOpenOption.WRITE);
            } catch (IOException e) {
                throw new RuntimeException("Cannot write to file: " + transformedClassFile.getPath(), e);
            }
        }

    }

    private String className;
    private boolean wasModified; // Были ли на самом деле изменения в классе

    public TransformResult transform(byte[] bytes) {
        className = null;
        wasModified = false;

        Map<MethodId, MethodAnalyzeResult> analyzeResults = new HashMap<>();

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
                return new MethodAnalyzer(Opcodes.ASM5, access, className, name, desc,
                        signature, exceptions, analyzeResults, classLoader);
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
                MethodId methodId = new MethodId(className, name, desc);

                // Если метод не нужно инструментировать, ничего и не делаем
                boolean needInstrument = analyzeResults.containsKey(methodId);
                if (!needInstrument)
                    return super.visitMethod(access, name, desc, signature, exceptions);

                MethodAnalyzeResult analyzeResult = analyzeResults.get(methodId);

                // Если внутри метода нет ни одной точки восстановления - также можем не инструментировать его
                if (analyzeResult.getRestorePointCallsCount() == 0)
                    return super.visitMethod(access, name, desc, signature, exceptions);

                wasModified = true;

                return new MethodAdapter(Opcodes.ASM5, super.visitMethod(access, name, desc, signature, exceptions),
                        analyzeResult,
                        (access & Opcodes.ACC_STATIC) == Opcodes.ACC_STATIC,
                        Type.getType(desc).getReturnType());
            }
        };
        try {
            reader.accept(adapter, 0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }

        if (wasModified) {
            byte[] transformed = writer.toByteArray();
            return new TransformResult(true, className, transformed);
        } else {
            return new TransformResult(false, className, bytes);
        }
    }

    private static void collectClassFilesRecursively(List<File> allClassFiles,
                                                     File directory) {
        final File[] allFiles = directory.listFiles();
        if (null == allFiles) return; // Skip incorrect dir
        for (File file : allFiles) {
            if (file.isFile()) {
                if (file.getName().endsWith(".class")) {
                    allClassFiles.add(file);
                }
            } else if (file.isDirectory()) {
                collectClassFilesRecursively(allClassFiles, file);
            }
        }

    }
}
