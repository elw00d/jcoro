package org.jcoro.analyzer;

import org.jcoro.MethodId;
import org.objectweb.asm.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * @author elwood
 */
public class Analyzer {
    /**
     * Возвращает { className -> { methodNameToInstrument -> list [ callsToInstrument ] } }
     * Таким образом, чтобы проверить, нужно ли инструментировать метод класса, надо позвать
     *  map.get(className).containsKey(methodName)
     * А чтобы получить список вызовов внутри метода, которые могут привести к вызову yield(), нужно выполнить
     *  map.get(className).get(methodName)
     * Если внутри метода нет ни одного вызова, который бы мог привести к вызову yield(), то этот метод
     * инструментировать не нужно, и его не будет во вложенной мапе (таким образом, во вложенной мапе не должно
     * быть ни одного entry с пустым списком в качестве значения).
     */
    public Map<String, Map<String, List<MethodId>>> analyzeJars(List<String> jarPaths) throws IOException {
        // Первый проход алгоритма
        for (String jarPath : jarPaths) {
            JarFile jarFile = new JarFile(new File(jarPath));
            Enumeration<JarEntry> jarEntries = jarFile.entries();
            while (jarEntries.hasMoreElements()) {
                JarEntry jarEntry = jarEntries.nextElement();
                if (jarEntry.getName().contains("org/jcoro/tests/simpletest/") && jarEntry.getName().contains(".class")) {
                    byte[] bytes = new byte[(int) jarEntry.getSize()];
                    try (InputStream inputStream = jarFile.getInputStream(jarEntry)) {
                        int totalReaded = 0;
                        while (totalReaded < bytes.length) {
                            int readed = inputStream.read(bytes);
                            if (readed == -1) throw new RuntimeException("Can't read jar entry");
                            totalReaded += readed;
                        }
                    }
                    analyzeFirst(bytes);
                }
            }
        }

        // Второй проход
//        for (String jarPath : jarPaths) {
//            JarFile jarFile = new JarFile(new File(jarPath));
//            Enumeration<JarEntry> jarEntries = jarFile.entries();
//            while (jarEntries.hasMoreElements()) {
//                JarEntry jarEntry = jarEntries.nextElement();
//                if (jarEntry.getName().contains("Test")) {
//                    byte[] bytes = new byte[(int) jarEntry.getSize()];
//                    try (InputStream inputStream = jarFile.getInputStream(jarEntry)) {
//                        int totalReaded = 0;
//                        while (totalReaded < bytes.length) {
//                            int readed = inputStream.read(bytes);
//                            if (readed == -1) throw new RuntimeException("Can't read jar entry");
//                            totalReaded += readed;
//                        }
//                    }
//                    analyzeClass(bytes);
//                }
//            }
//        }
        return null;
    }

    private Map<String, String> superNames = new HashMap<>();
    private Map<String, Set<String>> implementingInterfaces = new HashMap<>();
    private Map<String, List<MethodId>> declaredMethods = new HashMap<>();

    private void analyzeFirst(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM5) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                super.visit(version, access, name, signature, superName, interfaces);
            }
        };
        reader.accept(classVisitor, 0);
        System.out.println(classVisitor.hashCode());
    }

    // Первый проход алгоритма - для сбора информации об иерархиях наследования и реализации интерфейсов
    // для каждого метода надо уметь определять
    // 1. Список методов, наследующих (переопределяющих) или реализующих (если интерфейс) этот метод
    //    Т.е. список всех методов вниз по иерархии
    // 2. Список методов, которые переопределяются этим методом
    //    Т.е. список методов вверх по иерархии

    // Алгоритм:
    // 1. Проходим по классам, методам и вызовам внутри методов. Собираем структуры данных, показывающие:
    //    - список всех методов, реализующих ICoroRunnable.run()
    //    - список всех методов, которые внутри содержат вызов yield()
    //    - для каждого метода - список методов, которые могут быть вызваны при выполнении
    // 2. Для каждого из методов, содержащих внутри вызов yield(), маркируем все методы вверх по стеку вызова,
    //    до тех пор, пока не встретится метод, реализующий ICoroRunnable.run(). Каждый из отмеченных методов
    //    становится методом, который необходимо инструментировать (methodNameToInstrument). А вызов(ы), через
    //    которые был найден путь к вызывающему методу, становятся точками восстановления (callsToInstrument).
    // 3. Находим все методы, которые необходимо инструментировать, и собираем их в результирующую мапу.

    private Set<MethodId> coroRunnables = new HashSet<>();
    private Map<MethodId, Set<MethodId>> calls = new HashMap<>();
    private Set<MethodId> yieldingMethods = new HashSet<>();
    private Map<MethodId, Set<MethodId>> interfaceImplementations = new HashMap<>();
    private Map<MethodId, Set<MethodId>> overrides = new HashMap<>();

    private void analyzeClass(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM5) {
            private String className;
            private boolean implementsICoroRunnable = false;

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                // Сохраняем имя класса в field, а также определяем, реализует ли этот класс ICoroRunnable
                className = name;
                for (String i : interfaces) {
                    if ("org/jcoro/ICoroRunnable".equals(i)) {
                        implementsICoroRunnable = true;
                        break;
                    }
                }

                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodId methodId = new MethodId(className, name, desc);

                // Если обнаружили метод, реализующий ICoroRunnable.run()
                if (implementsICoroRunnable && "run".equals(name) && "()V".equals(desc)) {
                    coroRunnables.add(methodId);
                }

                return new MethodVisitor(Opcodes.ASM5, super.visitMethod(access, name, desc, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        // Если нашли вызов Coro.yield(), добавляем вызывающий метод во множество yieldingMethods
                        if ("org/jcoro/Coro".equals(owner) && "yield".equals(name) && "()V".equals(desc)) {
                            yieldingMethods.add(methodId);
                        } else {
                            // Иначе просто добавляем во множество вызываемых методов
                            MethodId callingMethodId = new MethodId(owner, name, desc);
                            Set<MethodId> callsList = calls.get(methodId);
                            if (callsList == null) {
                                callsList = new HashSet<>();
                                calls.put(methodId, callsList);
                            }
                            callsList.add(callingMethodId);
                        }

                        super.visitMethodInsn(opcode, owner, name, desc, itf);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
                        // Метод, который будет вызван при вызове лямбды
                        Handle callingMethodHandle = (Handle) bsmArgs[1];
                        String callingMethodClassName = callingMethodHandle.getOwner(); // Имя класса, содержащего метод
                        String callingMethodName = callingMethodHandle.getName(); // Имя метода
                        String callingMethodSignature = callingMethodHandle.getDesc(); // Сигнатура метода

                        // Если лямбда реализует ICoroRunnable.run(), добавляем метод, её реализующий, в coroRunnables
                        if ("run".equals(name) && "()Lorg/jcoro/ICoroRunnable;".equals(desc)) {
                            coroRunnables.add(new MethodId(callingMethodClassName, callingMethodName, callingMethodSignature));
                        }

                        // Добавляем лямбду в calls, т.к. она может быть вызвана, если создана в рамках этого метода
                        MethodId callingMethodId = new MethodId(callingMethodClassName, callingMethodName, callingMethodSignature);
                        Set<MethodId> callsList = calls.get(methodId);
                        if (callsList == null) {
                            callsList = new HashSet<>();
                            calls.put(methodId, callsList);
                        }
                        callsList.add(callingMethodId);

                        super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
                    }
                };
            }
        };
        reader.accept(classVisitor, 0);
        System.out.println(classVisitor.hashCode());
    }
}
