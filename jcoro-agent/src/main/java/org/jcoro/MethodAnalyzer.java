package org.jcoro;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.tree.analysis.Frame;

import java.lang.annotation.Annotation;
import java.util.*;

import static java.util.stream.Collectors.toList;

/**
 * Получает для переданного метода количество точек восстановления и два параллельных массива:
 * массив состояний фрейма и массив инструкций (каждой инструкции соответствует состояние фрейма).
 *
 * Собранные данные записываются в мапы только в случае, если метод помечен аннотацией @Async.
 *
 * @author elwood
 */
public class MethodAnalyzer extends MethodVisitor {
    private final MethodNode mn;
    private final String owner;
    private final MethodId methodId;

    private final ClassLoader classLoader;

    private List<Await> declaredRestorePoints;
    private boolean rootLambda;

    // "out parameters"
    private final Map<MethodId, MethodAnalyzeResult> resultMap;

    private int restorePointCalls = 0;
    private Set<MethodId> restorePoints; // Set of found restore points
    private Set<MethodId> unpatchableRestorePoints; // Set of restore points marked with unpatchable=true flag

    public MethodAnalyzer(int api, int access, String owner, String name, String desc, String signature,
                          String[] exceptions,
                          Map<MethodId, MethodAnalyzeResult> resultMap,
                          ClassLoader classLoader,
                          AsyncLambdaInfo asyncLambdaInfo) { // This parameter is passed when method is lambda
        super(api, new MethodNode(Opcodes.ASM5, access, name, desc, signature, exceptions));
        //
        this.mn = (MethodNode) super.mv;
        this.owner = owner;
        this.classLoader = classLoader;
        //
        this.methodId = new MethodId(owner, name, desc);
        // output
        this.resultMap = resultMap;

        if (asyncLambdaInfo != null) {
            this.declaredRestorePoints = asyncLambdaInfo.getDeclaredRestorePoints();

            // If lambda is ICoroRunnable, we consider it as Root Lambda (lambda, which will be called first)
            // If you want to use some ICoroRunnable lambda in non-root context, you can inherit interface
            // (INonRootCoroRunnable extends ICoroRunnable) and use it instead of ICoroRunnable.
            this.rootLambda = asyncLambdaInfo.getDesc().endsWith(")Lorg/jcoro/ICoroRunnable;");
        }
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        if ("Lorg/jcoro/Async;".equals(desc)) {
            return new AnnotationNode(Opcodes.ASM5, desc) {
                @Override
                public void visitEnd() {
                    assert "value".equals(this.values.get(0));
                    List<AnnotationNode> restorePoints = (List<AnnotationNode>) this.values.get(1);
                    declaredRestorePoints = Helpers.parseAwaitAnnotations(restorePoints);
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

    private Optional<Await> findInRestorePoints(MethodId callingMethodId) {
        if (declaredRestorePoints == null) return Optional.empty();

        return declaredRestorePoints.stream().filter(restorePoint -> {
            boolean ownerEquals = restorePoint.owner().equals("")
                    || restorePoint.owner().equals(callingMethodId.className);
            boolean nameEquals = restorePoint.value().equals(callingMethodId.methodName);
            boolean descEquals = "".equals(restorePoint.desc())
                    || restorePoint.desc().equals(callingMethodId.signature);
            return ownerEquals && nameEquals && descEquals;
        }).findFirst();
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        MethodId callingMethodId = new MethodId(owner, name, desc);
        final Optional<Await> restorePointOptional = findInRestorePoints(callingMethodId);
        if (restorePointOptional.isPresent()) {
            if (null == restorePoints) restorePoints = new HashSet<>();
            restorePoints.add(callingMethodId);
            //
            if (!restorePointOptional.get().patchable()) {
                if (null == unpatchableRestorePoints) unpatchableRestorePoints = new HashSet<>();
                unpatchableRestorePoints.add(callingMethodId);
            }
            //
            restorePointCalls++;
        }
        //
        super.visitMethodInsn(opcode, owner, name, desc, itf);
    }

    private static class LocalVarInfo {
        String name;
        String desc;
        String signature;
        Label start;
        Label end;
        int index;

        public LocalVarInfo(String name, String desc, String signature, Label start, Label end, int index) {
            this.name = name;
            this.desc = desc;
            this.signature = signature;
            this.start = start;
            this.end = end;
            this.index = index;
        }
    }

    private List<LocalVarInfo> localVars = new ArrayList<>();

    @Override
    public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
        localVars.add(new LocalVarInfo(name, desc, signature, start, end, index));
        super.visitLocalVariable(name, desc, signature, start, end, index);
    }

    @Override
    public void visitEnd() {
        // Нужно ли записывать собранные данные в выходные мапы
        // Если на методе нет аннотации @Async - то не пишем
        // В противном случае пишем, даже если точек восстановления не найдено
        if (declaredRestorePoints == null) {
            super.visitEnd();
            return;
        }

        Analyzer analyzer = new Analyzer(new SimpleVerifier() {
            @Override
            protected Class getClass(Type t) {
                try {
                    return super.getClass(t);
                } catch (RuntimeException e) {
                    try {
                        // Используем класслоадер, который умеет загружать исходный класс из массива байт
                        // Это нужно для работы верификатора
                        return classLoader.loadClass(t.getInternalName().replaceAll("/", "."));
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

            // Корректируем инфу о типах значений локальных переменных с учётом таблиц переменных,
            // которые записываются в class-файл. Без этого ASM иногда не может определить настоящий тип
            // переменной (после инструкции aconst_null, например) и выдаёт в этом месте "Lnull;".
            // Нас это не устраивает, мы не можем обрабатывать переменных, типа которых мы не знаем.
            localVars.forEach(localVarInfo -> {
                LabelNode startLabel = (LabelNode) localVarInfo.start.info; // inclusive
                LabelNode endLabel = (LabelNode) localVarInfo.end.info; // exclusive
                boolean meetStart = false;
                boolean meetEnd = false;
                for (int i = 0; i < insns.length; i++) {
                    if (insns[i] == startLabel) {
                        meetStart = true;
                    }
                    if (insns[i] == endLabel) {
                        meetEnd = true;
                    }
                    if (meetStart && !meetEnd) {
                        Value oldLocal = frames[i].getLocal(localVarInfo.index);
                        BasicValue newLocal = new BasicValue(Type.getType(localVarInfo.desc));
                        if (!((BasicValue) oldLocal).getType().getDescriptor().equals(localVarInfo.desc)) {
                            frames[i].setLocal(localVarInfo.index, newLocal);
                        }
                    }
                }
            });

            // Теперь окончательно проверяем, что фреймов с переменными типа "Lnull;" нигде не осталось
            // В стеке, к сожалению, "Lnull;" возможны (после инструкций типа ACONST_NULL)
            for (int i = 0; i < frames.length; i++) {
                final Frame frame = frames[i];
                if (frame != null) { // frame может быть null в конце методов, если крайние инструкции - что-то вроде labels
                    for (int j = 0; j < frame.getLocals(); j++) {
                        final BasicValue value = (BasicValue) frame.getLocal(j);
                        if (value.getType() != null && "Lnull;".equals(value.getType().getDescriptor()))
                            throw new AssertionError("This shouldn't happen. Bug in ASM ?");
                    }
                }
            }
        } catch (AnalyzerException e) {
            throw new RuntimeException("Cannot analyze method", e);
        }
        //
        resultMap.put(methodId, new MethodAnalyzeResult(
                restorePointCalls, restorePoints, unpatchableRestorePoints, frames, insns, rootLambda)
        );
        //
        super.visitEnd();
    }
}
