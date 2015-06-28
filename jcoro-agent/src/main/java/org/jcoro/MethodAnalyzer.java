package org.jcoro;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.*;

import java.lang.annotation.Annotation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

import static java.util.stream.Collectors.toList;

/**
 * Получает для переданного метода количество точек восстановления и два параллельных массива:
 * массив состояний фрейма и массив инструкций (каждой инструкции соответствует состояние фрейма).
 *
 * Собранные данные записываются в мапы только в случае, если метод помечен аннотацией @Instrument.
 *
 * @author elwood
 */
public class MethodAnalyzer extends MethodVisitor {
    private final MethodVisitor nextMv; // todo: попробовать позвать след визитор в конце visitEnd()
    private final MethodNode mn;
    private final String owner;
    private final MethodId methodId;

    private final byte[] classFile;

    private List<RestorePoint> declaredRestorePoints;

    // "out parameters"
    private final Map<MethodId, MethodAnalyzeResult> resultMap;

    private int restorePointCalls = 0;
    private Set<MethodId> restorePoints; // Set of found restore points

    public MethodAnalyzer(int api, MethodVisitor mv, int access, String owner, String name, String desc, String signature,
                          String[] exceptions,
                          Map<MethodId, MethodAnalyzeResult> resultMap,
                          byte[] classFile) {
        super(api, new MethodNode(Opcodes.ASM5, access, name, desc, signature, exceptions));
        //
        this.nextMv = mv;
        this.mn = (MethodNode) super.mv;
        this.owner = owner;
        this.classFile = classFile;
        //
        this.methodId = new MethodId(owner, name, desc);
        // output
        this.resultMap = resultMap;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        if ("Lorg/jcoro/Instrument;".equals(desc)) {
            return new AnnotationNode(Opcodes.ASM5, desc) {
                @Override
                public void visitEnd() {
                    assert "value".equals(this.values.get(0));
                    List<AnnotationNode> restorePoints = (List<AnnotationNode>) this.values.get(1);
                    declaredRestorePoints = restorePoints.stream().map(annotationNode -> {
                        assert "value".equals(annotationNode.values.get(0));
                        String value = (String) annotationNode.values.get(1);
                        if (annotationNode.values.size() > 2) {
                            assert "desc".equals(annotationNode.values.get(2));
                        }
                        String desc = annotationNode.values.size() > 2
                                ? (String) annotationNode.values.get(3)
                                : "";
                        return new RestorePoint() {
                            @Override
                            public String value() {
                                return value;
                            }

                            @Override
                            public String desc() {
                                return desc;
                            }

                            @Override
                            public Class<? extends Annotation> annotationType() {
                                return RestorePoint.class;
                            }
                        };
                    }).collect(toList());
                    super.visitEnd();
                }
            };
        } else {
            return super.visitAnnotation(desc, visible);
        }
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String desc, boolean visible) {
        return super.visitTypeAnnotation(typeRef, typePath, desc, visible);
    }

    private boolean isRestorePoint(MethodId callingMethodId) {
        if (declaredRestorePoints == null) return false;

        return declaredRestorePoints.stream().anyMatch(restorePoint -> {
            boolean ownerEquals = true; // todo : позволять уточнять имя класса/интерфейса
            boolean nameEquals = restorePoint.value().equals(callingMethodId.methodName);
            boolean descEquals = "".equals(restorePoint.desc())
                    || restorePoint.desc().equals(callingMethodId.signature);
            return ownerEquals && nameEquals && descEquals;
        });
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        MethodId callingMethodId = new MethodId(owner, name, desc);
        if (isRestorePoint(callingMethodId)) {
            if (null == restorePoints) {
                restorePoints = new HashSet<>();
            }
            restorePoints.add(callingMethodId);

            restorePointCalls++;
        }
        //
        super.visitMethodInsn(opcode, owner, name, desc, itf);
    }

    @Override
    public void visitEnd() {
        // Нужно ли записывать собранные данные в выходные мапы
        // Если на методе нет аннотации @Instrument - то не пишем
        // В противном случае пишем, даже если точек восстановления не найдено
        if (declaredRestorePoints == null) {
            super.visitEnd();
            return;
        }

        // Делаем класслоадер, который умеет загружать исходный класс из массива байт
        // Это нужно для работы верификатора
//        class ByteClassLoader extends URLClassLoader {
//            public ByteClassLoader(URL[] urls, ClassLoader parent) {
//                super(urls, parent);
//            }
//
//            @Override
//            protected Class<?> findClass(final String name) throws ClassNotFoundException {
//                // Этот класслоадер умеет грузить только анализируемый класс
//                if (name.equals(methodId.className.replaceAll("/", "."))) {
//                    return defineClass(name, classFile, 0, classFile.length);
//                }
//                return super.findClass(name);
//            }
//
//        }
//        ByteClassLoader classLoader = new ByteClassLoader(new URL[0], Thread.currentThread().getContextClassLoader());
//        //

        Analyzer analyzer = new Analyzer(new SimpleVerifier() {
            @Override
            protected Class getClass(Type t) {
                try {
                    return super.getClass(t);
                } catch (RuntimeException e) {
                    try {
                        return InstrumentProgram.classLoader.loadClass(t.getInternalName().replaceAll("/", "."));
                    } catch (ClassNotFoundException e1) {
                        throw new RuntimeException(e1);
                    }
                }
            }
        });
        AbstractInsnNode[] insns = mn.instructions.toArray();
        Frame[] frames;
        try {
            frames = analyzer.analyze(owner, mn);
        } catch (AnalyzerException e) {
            throw new RuntimeException("Cannot analyze method", e);
        }
        //
        resultMap.put(methodId, new MethodAnalyzeResult(
                restorePointCalls, restorePoints, frames, insns)
        );
        //
        super.visitEnd();
    }
}
