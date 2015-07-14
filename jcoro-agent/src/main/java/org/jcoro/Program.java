package org.jcoro;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * @author elwood
 */
public class Program {
    private static String sourceDirPath;
    private static String destDirPath;

    /**
     * Должно быть 4 аргумента:
     * --source src --dest dst
     */
    public static void main(String[] args) {
        System.out.println("!!! program started !!!");

        if (args.length != 4 || !args[0].equals("--source") || !args[2].equals("--dest")) {
            System.out.println("Usage: program --source <src> --dest <dst>");
            return;
        }

        sourceDirPath = args[1];
        destDirPath = args[3];

        prepareEnv();
        instrumentClasses();
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

    private static void instrumentClasses() {
        File sourceDir = new File(sourceDirPath);
        List<File> allClassFiles = new ArrayList<>();
        collectClassFilesRecursively(allClassFiles, sourceDir);

        // Initialize classloader
        try {
            InstrumentProgram.classLoader = new URLClassLoader(
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
            InstrumentProgram instrumentProgram = new InstrumentProgram();
            final TransformResult transformResult = instrumentProgram.transform(bytes);
            //if (transformResult.wasModified()) {
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
            //}
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
