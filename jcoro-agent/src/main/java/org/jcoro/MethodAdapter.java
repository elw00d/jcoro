package org.jcoro;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * @author elwood
 */
public class MethodAdapter extends MethodVisitor {
    private final MethodAnalyzeResult analyzeResult;

    private final boolean isStatic;
    private final Type returnType;

    private int insnIndex = 0; // Currently monitoring index of original instruction
    private Label[] restoreLabels;
    private int restorePointsProcessed = 0;

    public MethodAdapter(int api, MethodVisitor mv, MethodAnalyzeResult methodAnalyzeResult,
                         boolean isStatic, Type returnType) {
        super(api, mv);
        //
        this.analyzeResult = methodAnalyzeResult;
        this.isStatic = isStatic;
        this.returnType = returnType;
    }

    private Frame currentFrame() {
        return analyzeResult.getFrames()[insnIndex];
    }

    private Frame nextFrame() {
        return analyzeResult.getFrames()[insnIndex + 1];
    }

    private static class TryCatchBlock {
        Label start;
        Label end;
        Label handler;
        String type;

        TryCatchBlock(Label start, Label end, Label handler, String type) {
            this.start = start;
            this.end = end;
            this.handler = handler;
            this.type = type;
        }
    }

    // Все участки [label_1; label_2) должны быть исключены из всех блоков try-catch в методе.
    //
    // Дело в том, что при инструментировании методов участки [restorePointStart; noActiveCoro) и [afterCall; noSaveContext)
    // не должны быть внутри try-catch блоков. А собственно вызов (участок [noActiveCoro;afterCall) ) - должен быть в рамках try-catch
    // (если таковой имеется). То есть мы делим исходный try-catch на три - до нового байткода, собственно вызов, и после нового байткода.
    //
    // Чтобы хранить информацию о том, какие участки нужно исключить из try-catch блоков, используем эту структуру.
    private static class TryCatchExcludeBlock {
        Label label_1;
        Label label_2;
    }
    private List<TryCatchBlock> tryCatchBlocks = new ArrayList<>();
    private List<TryCatchExcludeBlock> tryCatchExcludeBlocks = new ArrayList<>();

    @Override
    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        tryCatchBlocks.add(new TryCatchBlock(start, end, handler, type));
    }

    // Возвращает true и заполняет result блоками, на которые был разбит исходный блок,
    // Если блок не надо разбивать, возвращает false
    private boolean splitIfNeed(TryCatchBlock block, List<TryCatchBlock> result) {
        int start = block.start.getOffset();
        int end = block.end.getOffset();
        for (TryCatchExcludeBlock splitCandidate : tryCatchExcludeBlocks) {
            int label_1 = splitCandidate.label_1.getOffset();
            int label_2 = splitCandidate.label_2.getOffset();

            if (label_1 >= start && label_1 <= end && label_2 >= start && label_2 <= end) {
                // Интервал [label_1; label_2) находится внутри интервала [start; end)
                // Вырезаем его - остаётся 2 интервала [start; label1) и [label2; end)
                if (label_1 > start)
                    result.add(new TryCatchBlock(block.start, splitCandidate.label_1, block.handler, block.type));
                if (end > label_2)
                    result.add(new TryCatchBlock(splitCandidate.label_2, block.end, block.handler, block.type));
                return true;
            } else if (label_1 >= start && label_1 < end && label_2 >= end) {
                // Интервал [label_1; label_2) касается интервала [start; end) справа
                // Вырезаем его - остаётся один интервал [start; label1)
                if (label_1 > start)
                    result.add(new TryCatchBlock(block.start, splitCandidate.label_1, block.handler, block.type));
                return true;
            } else if (label_1 < start && label_2 > start && label_2 <= end) {
                // Интервал [label_1; label_2) касается интервала [start; end) слева
                // Вырезаем его - остаётся один интервал [label2; end)
                if (end > label_2)
                    result.add(new TryCatchBlock(splitCandidate.label_2, block.end, block.handler, block.type));
                return true;
            } else if (label_1 < start && label_2 >= end) {
                // Интервал [label_1; label_2) не должен охватывать целиком [start; end),
                // т.к. в генерируемом коде мы не добавляем своих try-catch блоков
                throw new AssertionError("This shouldn't happen");
            } else {
                // Do nothing
            }
        }
        return false;
    }

    /**
     * Выполняет разбиение всех блоков try-catch до тех пор, пока разбивать станет нечего.
     */
    private List<TryCatchBlock> splitTryCatchBlocks() {
        List<TryCatchBlock> blocksBefore = tryCatchBlocks;
        List<TryCatchBlock> blocksAfter;
        boolean anyChange;
        do {
            anyChange = false;
            blocksAfter = new ArrayList<>();
            for (TryCatchBlock block : blocksBefore) {
                final List<TryCatchBlock> splittedBlocks = new ArrayList<>();
                if (splitIfNeed(block, splittedBlocks)) {
                    blocksAfter.addAll(splittedBlocks);
                    anyChange = true;
                } else {
                    blocksAfter.add(block);
                }
            }
            blocksBefore = blocksAfter;
        } while (anyChange);
        return blocksAfter;
    }

    @Override
    public void visitEnd() {
        final List<TryCatchBlock> splittedTryCatchBlocks = splitTryCatchBlocks();
        for (TryCatchBlock tryCatchBlock : splittedTryCatchBlocks) {
            mv.visitTryCatchBlock(tryCatchBlock.start, tryCatchBlock.end, tryCatchBlock.handler, tryCatchBlock.type);
        }
        super.visitEnd();
    }

    @Override
    public void visitCode() {
        // if (Coro.get() == null) goto noActiveCoroLabel;
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "getSafe", "()Lorg/jcoro/Coro;", false);
        Label noActiveCoroLabel = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, noActiveCoroLabel);

        // popState()
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popState", "()Ljava/lang/Integer;", false);
        mv.visitInsn(Opcodes.DUP);
        Label noActiveStateLabel = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, noActiveStateLabel);

        // Now, when state != null, we have to pop extra ref (saved "this") if current method is not static
        // and if this method has been called from unpatchable context
        if (!isStatic) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "isUnpatchableCall", "()Z", false);
            Label noUnpatchableFlag = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, noUnpatchableFlag);

            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popRef", "()Ljava/lang/Object;", false);
            mv.visitInsn(Opcodes.POP);

            mv.visitLabel(noUnpatchableFlag);
            putFrame(currentFrame(), "Ljava/lang/Integer;");
        }
        // Reset Coro.unpatchableCall flag to false always (when restoring state)
        mv.visitLdcInsn(false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "setUnpatchableCall", "(Z)V", false);

        // switch (state)
        final int nRestorePoints = analyzeResult.getRestorePointCallsCount();
        restoreLabels = new Label[nRestorePoints];
        for (int i = 0; i < nRestorePoints; i++) restoreLabels[i] = new Label();
        if (nRestorePoints == 1) {
            // If state != null and there is only one restore point in this method, jump to label directly
            mv.visitInsn(Opcodes.POP);
            mv.visitJumpInsn(Opcodes.GOTO, restoreLabels[0]);
        } else {
            assert nRestorePoints > 1;
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "Ljava/lang/Integer;", "intValue", "()I", false);
            mv.visitTableSwitchInsn(0, nRestorePoints - 1, noActiveCoroLabel, restoreLabels);
        }

        // noActiveStateLabel:
        mv.visitLabel(noActiveStateLabel);
        putFrame(currentFrame(), "Ljava/lang/Integer;");
        mv.visitInsn(Opcodes.POP); // Remove extra (duplicated) `state`

        // noActiveCoroLabel:
        mv.visitLabel(noActiveCoroLabel);
        putFrame(currentFrame());
        super.visitCode();
    }

    private Object convertFrameOperandToInsn(Value value) {
        final BasicValue local = (BasicValue) value;
        if (local.isReference()) {
            return local.getType().getDescriptor();
        } else if (local == BasicValue.RETURNADDRESS_VALUE) {
            // Эта штука возможна только в старый версиях джавы - когда в рамках метода можно было
            // делать подпрограммы (см инструкции jsr и ret) - после выхода джавы 1.6 это уже не актуально
            throw new UnsupportedOperationException("Frames with old opcodes (subroutines) are not supported");
        } else if (local == BasicValue.UNINITIALIZED_VALUE) {
            return Opcodes.TOP;
        } else {
            final int sort = local.getType().getSort();
            switch (sort) {
                case Type.VOID:
                case Type.OBJECT:
                case Type.ARRAY:
                    // Should be already processed in if (isReference()) case
                    throw new AssertionError("This shouldn't happen");
                case Type.INT:
                case Type.SHORT:
                case Type.BYTE:
                case Type.BOOLEAN:
                case Type.CHAR:
                    return Opcodes.INTEGER;
                case Type.LONG:
                    return Opcodes.LONG;
                case Type.DOUBLE:
                    return Opcodes.DOUBLE;
                case Type.FLOAT:
                    return Opcodes.FLOAT;
                default:
                    throw new AssertionError("This shouldn't happen");
            }
        }
    }

    private void putFrame(Frame frame) {
        putFrame(frame, null);
    }

    private void putFrame(Frame frame, String additionalStackOperand) {
        Object[] locals = new Object[frame.getLocals()];
        for (int i = 0; i < frame.getLocals(); i++) {
            locals[i] = convertFrameOperandToInsn(frame.getLocal(i));
        }
        Object[] stacks = new Object[frame.getStackSize() + (additionalStackOperand != null ? 1 : 0)];
        for (int i = 0; i < frame.getStackSize(); i++) {
            stacks[i] = convertFrameOperandToInsn(frame.getStack(i));
        }
        if (additionalStackOperand != null) {
            stacks[stacks.length - 1] = additionalStackOperand;
        }
        Object[] fixedLocals = fixLocals(locals);
        callVisitFrame(Opcodes.F_FULL, fixedLocals.length, fixedLocals, stacks.length, stacks);
    }

    private void visitCurrentFrameWithoutStack() {
        // Если метод статический - все локальные переменные еще равны TOP
        // Если метод нестатический - первая переменная - this, остальное - TOP
        final Frame frame = currentFrame();
        Object[] locals = new Object[frame.getLocals()];
        int i = 0;
        if (!isStatic) {
            locals[0] = convertFrameOperandToInsn(frame.getLocal(0));
            i++;
        }
        for (int j = i; j < locals.length; j++) {
            locals[j] = Opcodes.TOP;
        }
        Object[] fixedLocals = fixLocals(locals);
        callVisitFrame(Opcodes.F_FULL, fixedLocals.length, fixLocals(locals), 0, new Object[0]);
    }

    private void callVisitFrame(int type, int nLocal, Object[] local, int nStack,
                                Object[] stack) {
        mv.visitFrame(type, nLocal, local, nStack, stack);
        noInsnsSinceLastFrame = true;
    }

    private Object[] fixLocals(Object[] locals) {
        List<Object> fixed = new ArrayList<>();
        for (int i = 0; i < locals.length; i++) {
            fixed.add(locals[i]);
            if (locals[i] == Opcodes.DOUBLE || locals[i] == Opcodes.LONG) {
                i++; // skip next
            }
        }
        return fixed.toArray(new Object[fixed.size()]);
    }

    /**
     * Generates ldc 0 (null) instruction for specified type.
     */
    private void visitLdcDefaultValueForType(Type type) {
        final int sort = type.getSort();
        switch (sort) {
            case Type.VOID:
                break;
            case Type.OBJECT:
            case Type.ARRAY:
                mv.visitInsn(Opcodes.ACONST_NULL);
                break;
            case Type.INT:
            case Type.SHORT:
            case Type.BYTE:
            case Type.BOOLEAN:
            case Type.CHAR:
                mv.visitLdcInsn(0);
                break;
            case Type.LONG:
                mv.visitLdcInsn(0L);
                break;
            case Type.DOUBLE:
                mv.visitLdcInsn(0.d);
                break;
            case Type.FLOAT:
                mv.visitLdcInsn(0.f);
                break;
            default:
                throw new AssertionError("This shouldn't happen");
        }
    }

    private void restoreLocals() {
        Frame frame = currentFrame();
        for (int i = frame.getLocals() - 1; i >= 0; i--) {
            BasicValue local = (BasicValue) frame.getLocal(i);
            if (local == BasicValue.UNINITIALIZED_VALUE) {
                // do nothing
            } else if (local == BasicValue.RETURNADDRESS_VALUE) {
                // do nothing
            } else if (local.isReference()) {
                final String typeDescriptor = local.getType().getDescriptor();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popRef", "()Ljava/lang/Object;", false);
                mv.visitTypeInsn(Opcodes.CHECKCAST, typeDescriptor);
                mv.visitVarInsn(Opcodes.ASTORE, i);
            } else {
                final int sort = local.getType().getSort();
                switch (sort) {
                    case Type.VOID:
                    case Type.OBJECT:
                    case Type.ARRAY:
                        // Should be already processed in if (isReference()) case
                        throw new AssertionError("This shouldn't happen");
                    case Type.INT:
                    case Type.SHORT:
                    case Type.BYTE:
                    case Type.BOOLEAN:
                    case Type.CHAR:
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popInt", "()I", false);
                        mv.visitVarInsn(Opcodes.ISTORE, i);
                        break;
                    case Type.LONG:
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popLong", "()J", false);
                        mv.visitVarInsn(Opcodes.LSTORE, i);
                        break;
                    case Type.DOUBLE:
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popDouble", "()D", false);
                        mv.visitVarInsn(Opcodes.DSTORE, i);
                        break;
                    case Type.FLOAT:
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popFloat", "()F", false);
                        mv.visitVarInsn(Opcodes.FSTORE, i);
                        break;
                    default:
                        throw new AssertionError("This shouldn't happen");
                }
            }
        }
    }

    private void restoreStackBottom(Type callingMethodType, boolean callingMethodIsStatic) {
        final Type[] argumentTypes = callingMethodType.getArgumentTypes();
        int nArgs = argumentTypes.length;
        int skipStackVars = nArgs + ((!callingMethodIsStatic) ? 1 : 0);
        //
        Frame frame = currentFrame();
        for (int i = frame.getStackSize() - 1; i >= skipStackVars; i--) {
            BasicValue local = (BasicValue) frame.getStack(i);
            if (local == BasicValue.UNINITIALIZED_VALUE) {
                // do nothing
            } else if (local == BasicValue.RETURNADDRESS_VALUE) {
                // do nothing
            } else if (local.isReference()) {
                final String typeDescriptor = local.getType().getDescriptor();
                if (!"Lnull;".equals(typeDescriptor)) {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popRef", "()Ljava/lang/Object;", false);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, typeDescriptor);
                } else
                    mv.visitInsn(Opcodes.ACONST_NULL);
            } else {
                final int sort = local.getType().getSort();
                switch (sort) {
                    case Type.VOID:
                    case Type.OBJECT:
                    case Type.ARRAY:
                        // Should be already processed in if (isReference()) case
                        throw new AssertionError("This shouldn't happen");
                    case Type.INT:
                    case Type.SHORT:
                    case Type.BYTE:
                    case Type.BOOLEAN:
                    case Type.CHAR:
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popInt", "()I", false);
                        break;
                    case Type.LONG:
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popLong", "()J", false);
                        break;
                    case Type.DOUBLE:
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popDouble", "()D", false);
                        break;
                    case Type.FLOAT:
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popFloat", "()F", false);
                        break;
                    default:
                        throw new AssertionError("This shouldn't happen");
                }
            }
        }
    }

    private void restoreInstance(Type callingMethodType) {
        Frame frame = currentFrame();
        //
        final Type[] argumentTypes = callingMethodType.getArgumentTypes();
        int nArgs = argumentTypes.length;
        //
        BasicValue value = (BasicValue) frame.getStack(frame.getStackSize() - 1 - nArgs);
        if (!value.isReference()) throw new AssertionError("This shouldn't happen");

        final String typeDescriptor = value.getType().getDescriptor();
        if ("Lnull;".equals(typeDescriptor))
            throw new AssertionError("This shouldn't happen");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popRef", "()Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, typeDescriptor);
    }

    private void saveStackBottom(Type callingMethodReturnType) {
        Frame frame = nextFrame();
        // Кроме возвращаемого значения вызванного метода - ведь он вернул нам null или 0 в случае
        // после осуществления прерывания
        boolean skipFirstStackItem = (callingMethodReturnType.getSort() != Type.VOID);
        //
        for (int i = skipFirstStackItem ? 1 : 0; i < frame.getStackSize(); i++) {
            BasicValue local = (BasicValue) frame.getStack(i);
            if (local == BasicValue.UNINITIALIZED_VALUE) {
                // do nothing
            } else if (local == BasicValue.RETURNADDRESS_VALUE) {
                // do nothing
            } else if (local.isReference()) {
                // Если здесь - null, то можно ничего не сохранять, а при восстановлении симметрично сделать ACONST_NULL
                final String typeDescriptor = local.getType().getDescriptor();
                if (!"Lnull;".equals(typeDescriptor))
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushRef", "(Ljava/lang/Object;)V", false);
            } else {
                final int sort = local.getType().getSort();
                switch (sort) {
                    case Type.VOID:
                    case Type.OBJECT:
                    case Type.ARRAY:
                        // Should be already processed in if (isReference()) case
                        throw new AssertionError("This shouldn't happen");
                    case Type.INT:
                    case Type.SHORT:
                    case Type.BYTE:
                    case Type.BOOLEAN:
                    case Type.CHAR:
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushInt", "(I)V", false);
                        break;
                    case Type.LONG:
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushLong", "(J)V", false);
                        break;
                    case Type.DOUBLE:
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushDouble", "(D)V", false);
                        break;
                    case Type.FLOAT:
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushFloat", "(F)V", false);
                        break;
                    default:
                        throw new AssertionError("This shouldn't happen");
                }
            }
        }
    }

    private void saveLocals() {
        Frame frame = nextFrame();
        for (int i = 0; i <frame.getLocals(); i++) {
            BasicValue local = (BasicValue) frame.getLocal(i);
            if (local == BasicValue.UNINITIALIZED_VALUE) {
                // do nothing
            } else if (local == BasicValue.RETURNADDRESS_VALUE) {
                // do nothing
            } else if (local.isReference()) {
                mv.visitVarInsn(Opcodes.ALOAD, i);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushRef", "(Ljava/lang/Object;)V", false);
            } else {
                final int sort = local.getType().getSort();
                switch (sort) {
                    case Type.VOID:
                    case Type.OBJECT:
                    case Type.ARRAY:
                        // Should be already processed in if (isReference()) case
                        throw new AssertionError("This shouldn't happen");
                    case Type.INT:
                    case Type.SHORT:
                    case Type.BYTE:
                    case Type.BOOLEAN:
                    case Type.CHAR:
                        mv.visitVarInsn(Opcodes.ILOAD, i);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushInt", "(I)V", false);
                        break;
                    case Type.LONG:
                        mv.visitVarInsn(Opcodes.LLOAD, i);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushLong", "(J)V", false);
                        break;
                    case Type.DOUBLE:
                        mv.visitVarInsn(Opcodes.DLOAD, i);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushDouble", "(D)V", false);
                        break;
                    case Type.FLOAT:
                        mv.visitVarInsn(Opcodes.FLOAD, i);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushFloat", "(F)V", false);
                        break;
                    default:
                        throw new AssertionError("This shouldn't happen");
                }
            }
        }
    }

    private void saveThis() {
        if (!isStatic) {
            assert nextFrame().getLocals() >= 1; // At least one local ("this") should be present
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushRef", "(Ljava/lang/Object;)V", false);
        } else if (analyzeResult.isRootLambda()) {
            // Put extra NULL object to keep stack balanced when resuming
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushRef", "(Ljava/lang/Object;)V", false);
        }
    }

    private void returnDefault() {
        visitLdcDefaultValueForType(returnType); // Push default value for return type
        //
        final int type = returnType.getSort();
        switch (type) {
            case Type.VOID:
                mv.visitInsn(Opcodes.RETURN);
                break;
            case Type.OBJECT:
            case Type.ARRAY:
                mv.visitInsn(Opcodes.ARETURN);
                break;
            // https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.9.2
            case Type.INT:
            case Type.SHORT:
            case Type.BYTE:
            case Type.BOOLEAN:
            case Type.CHAR:
                mv.visitInsn(Opcodes.IRETURN);
                break;
            case Type.LONG:
                mv.visitInsn(Opcodes.LRETURN);
                break;
            case Type.DOUBLE:
                mv.visitInsn(Opcodes.DRETURN);
                break;
            case Type.FLOAT:
                mv.visitInsn(Opcodes.FRETURN);
                break;
            default:
                throw new AssertionError("This shouldn't happen");
        }
    }

    private void visitMethodInsnPatchable(int opcode, String owner, String name, String desc, boolean itf) {
        // Первый блок, который должен быть исключён из всех try-catch блоков метода
        // Блок обрамляет код восстановление контекста выполнения (последовательность pop-вызовов)
        TryCatchExcludeBlock tryCatchSplitInfo_1 = new TryCatchExcludeBlock();
        // Второй блок - обрамляет код сохранения контекста выполнения (последовательность push-вызовов)
        TryCatchExcludeBlock tryCatchSplitInfo_2 = new TryCatchExcludeBlock();

        Label noActiveCoroLabel = new Label();
        tryCatchSplitInfo_1.label_2 = noActiveCoroLabel;
        mv.visitJumpInsn(Opcodes.GOTO, noActiveCoroLabel);

        // label_i:
        mv.visitLabel(restoreLabels[restorePointsProcessed]);
        visitCurrentFrameWithoutStack();
        tryCatchSplitInfo_1.label_1 = restoreLabels[restorePointsProcessed];

        // Restore execution context
        {
            boolean callingMethodIsStatic = (opcode == Opcodes.INVOKESTATIC);
            final Type callingMethodType = Type.getType(desc);

            restoreLocals();

            // Восстанавливаем дно стека (стек операндов, за исключением аргументов подготавливаемого вызова)
            restoreStackBottom(callingMethodType, callingMethodIsStatic);

            // Восстанавливаем instance для вызова, если метод - экземплярный
            if (!callingMethodIsStatic) restoreInstance(callingMethodType);

            // Передаём дефолтные значения для аргументов вызова
            final Type[] argumentTypes = callingMethodType.getArgumentTypes();
            for (Type argumentType : argumentTypes) {
                visitLdcDefaultValueForType(argumentType);
            }
        }

        // Сюда приходим сразу, если нет необходимости восстанавливать стек
        mv.visitLabel(noActiveCoroLabel);
        putFrame(currentFrame());

        // Original call
        super.visitMethodInsn(opcode, owner, name, desc, itf);

        Label afterCallLabel = new Label();
        mv.visitLabel(afterCallLabel);
        tryCatchSplitInfo_2.label_1 = afterCallLabel;

        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "isYielding", "()Z", false);
        Label noSaveContextLabel = new Label();
        tryCatchSplitInfo_2.label_2 = noSaveContextLabel;
        mv.visitJumpInsn(Opcodes.IFEQ, noSaveContextLabel);

        // Save execution context
        {
            // Save stack first
            saveStackBottom(Type.getReturnType(desc));

            // Second save locals
            saveLocals();

            // Finally, save "this" if method is instance method
            saveThis();

            // Save the state
            mv.visitLdcInsn(restorePointsProcessed);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushState", "(I)V", false);

            // And return 0 or null
            returnDefault();
        }

        mv.visitLabel(noSaveContextLabel);
        putFrame(nextFrame());

        tryCatchExcludeBlocks.add(tryCatchSplitInfo_1);
        tryCatchExcludeBlocks.add(tryCatchSplitInfo_2);
        restorePointsProcessed++;
    }

    /**
     * Restores args of calling type (for unpatchable call they were saved too).
     */
    private void restoreArgs(Type callingMethodType) {
        final Type[] argumentTypes = callingMethodType.getArgumentTypes();
        for (Type argumentType : argumentTypes) {
            final int sort = argumentType.getSort();
            switch (sort) {
                case Type.VOID:
                case Type.OBJECT:
                case Type.ARRAY:
                    final String typeDescriptor = argumentType.getDescriptor();
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popRef", "()Ljava/lang/Object;", false);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, typeDescriptor);
                    break;
                case Type.INT:
                case Type.SHORT:
                case Type.BYTE:
                case Type.BOOLEAN:
                case Type.CHAR:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popInt", "()I", false);
                    break;
                case Type.LONG:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popLong", "()J", false);
                    break;
                case Type.DOUBLE:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popDouble", "()D", false);
                    break;
                case Type.FLOAT:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popFloat", "()F", false);
                    break;
                default:
                    throw new AssertionError("This shouldn't happen");
            }
        }
    }

    /**
     * Each argument for calling method should be copied into temporary unpatchable storage.
     * Unpatchable storage is represented by 5 stacks: refs, ints, longs, floats and doubles.
     * After copying we should restore original args set in the frame stack (to call the method).
     * To do this, we can use peekSomethingFromUnpatchable(int offset) method, and we need to know,
     * where concrete argument is placed inside one of unpatchable stack. This function returns
     * array of offsets that can be used to retrieve args back.
     *
     * offsets[0] corresponds to first argument
     * last offsets item corresponds to instance of object to call (if method is not static)
     *
     * For example to retrieve the first arg, call peekRefFromUnpatchable(offsets[0]).
     */
    private int[] calculateUnpatchableOffsets(Type[] argumentTypes, boolean callingMethodIsStatic) {
        int[] offsets = new int[argumentTypes.length + (callingMethodIsStatic ? 0 : 1)];
        StackDepthInfo stackDepth = calculateStackDepth(argumentTypes, callingMethodIsStatic);

        int _refsStackDepth = stackDepth.refsStackDepth;
        int _intsStackDepth = stackDepth.intsStackDepth;
        int _longsStackDepth = stackDepth.longsStackDepth;
        int _floatsStackDepth = stackDepth.floatsStackDepth;
        int _doublesStackDepth = stackDepth.doublesStackDepth;

        for (int i = argumentTypes.length - 1; i >= 0; i--) {
            Type argumentType = argumentTypes[i];
            final int sort = argumentType.getSort();
            switch (sort) {
                case Type.VOID:
                case Type.OBJECT:
                case Type.ARRAY:
                    offsets[i] = --_refsStackDepth;
                    break;
                case Type.INT:
                case Type.SHORT:
                case Type.BYTE:
                case Type.BOOLEAN:
                case Type.CHAR:
                    offsets[i] = --_intsStackDepth;
                    break;
                case Type.LONG:
                    offsets[i] = --_longsStackDepth;
                    break;
                case Type.FLOAT:
                    offsets[i] = --_floatsStackDepth;
                    break;
                case Type.DOUBLE:
                    offsets[i] = --_doublesStackDepth;
                    break;
                default:
                    throw new AssertionError("This shouldn't happen");
            }
        }
        return offsets;
    }

    /**
     * Saves the args in Coro's main storage, by copying them from unpatchable storage.
     */
    private void saveArgs(Type[] argumentTypes, int[] offsets, boolean callingMethodIsStatic) {
        // Перекладываем аргументы из unpatchable storage в обычный storage
        for (int i = 0; i < argumentTypes.length; i++) {
            Type argumentType = argumentTypes[i];
            final int sort = argumentType.getSort();
            //
            mv.visitLdcInsn(offsets[i]);
            //
            switch (sort) {
                case Type.VOID:
                case Type.OBJECT:
                case Type.ARRAY:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "peekRefFromUnpatchable", "(I)Ljava/lang/Object;", false);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushRef", "(Ljava/lang/Object;)V", false);
                    break;
                case Type.INT:
                case Type.SHORT:
                case Type.BYTE:
                case Type.BOOLEAN:
                case Type.CHAR:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "peekIntFromUnpatchable", "(I)I", false);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushInt", "(I)V", false);
                    break;
                case Type.LONG:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "peekLongFromUnpatchable", "(I)J", false);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushLong", "(J)V", false);
                    break;
                case Type.FLOAT:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "peekFloatFromUnpatchable", "(I)F", false);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushFloat", "(F)V", false);
                    break;
                case Type.DOUBLE:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "peekDoubleFromUnpatchable", "(I)D", false);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushDouble", "(D)V", false);
                    break;
                default:
                    throw new AssertionError("This shouldn't happen");
            }
        }

        if (!callingMethodIsStatic) {
            mv.visitLdcInsn(0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "peekRefFromUnpatchable", "(I)Ljava/lang/Object;", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushRef", "(Ljava/lang/Object;)V", false);
        }
    }

    private static class StackDepthInfo {
        int refsStackDepth;
        int intsStackDepth;
        int longsStackDepth;
        int floatsStackDepth;
        int doublesStackDepth;
    }

    private static StackDepthInfo calculateStackDepth(Type[] argumentTypes, boolean callingMethodIsStatic) {
        StackDepthInfo info = new StackDepthInfo();

        for (Type argumentType : argumentTypes) {
            final int sort = argumentType.getSort();
            switch (sort) {
                case Type.VOID:
                case Type.OBJECT:
                case Type.ARRAY:
                    info.refsStackDepth++;
                    break;
                case Type.INT:
                case Type.SHORT:
                case Type.BYTE:
                case Type.BOOLEAN:
                case Type.CHAR:
                    info.intsStackDepth++;
                    break;
                case Type.LONG:
                    info.longsStackDepth++;
                    break;
                case Type.FLOAT:
                    info.floatsStackDepth++;
                    break;
                case Type.DOUBLE:
                    info.doublesStackDepth++;
                    break;
                default:
                    throw new AssertionError("This shouldn't happen");
            }
        }
        if (!callingMethodIsStatic) {
            info.refsStackDepth++;
        }

        return info;
    }

    /**
     * Removes all items saved to unpatchables storage before method call.
     */
    private void cleanUnpatchablesFrame(Type[] argumentTypes, boolean callingMethodIsStatic) {
        StackDepthInfo stackDepth = calculateStackDepth(argumentTypes, callingMethodIsStatic);

        mv.visitLdcInsn(stackDepth.refsStackDepth);
        mv.visitLdcInsn(stackDepth.intsStackDepth);
        mv.visitLdcInsn(stackDepth.longsStackDepth);
        mv.visitLdcInsn(stackDepth.floatsStackDepth);
        mv.visitLdcInsn(stackDepth.doublesStackDepth);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "cleanupUnpatchableFrame", "(IIIII)V", false);
    }

    private void saveArgsInTempStorage(Type[] argumentTypes, boolean callingMethodIsStatic,
                                       int[] offsets, String owner) {
        // Save args and instance to temporary storage
        for (int i = argumentTypes.length - 1; i >= 0; i--) {
            Type argumentType = argumentTypes[i];
            final int sort = argumentType.getSort();
            switch (sort) {
                case Type.VOID:
                case Type.OBJECT:
                case Type.ARRAY:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushRefToUnpatchable", "(Ljava/lang/Object;)V", false);
                    break;
                case Type.INT:
                case Type.SHORT:
                case Type.BYTE:
                case Type.BOOLEAN:
                case Type.CHAR:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushIntToUnpatchable", "(I)V", false);
                    break;
                case Type.LONG:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushLongToUnpatchable", "(J)V", false);
                    break;
                case Type.FLOAT:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushFloatToUnpatchable", "(F)V", false);
                    break;
                case Type.DOUBLE:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushDoubleToUnpatchable", "(D)V", false);
                    break;
                default:
                    throw new AssertionError("This shouldn't happen");
            }
        }
        if (!callingMethodIsStatic) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushRefToUnpatchable", "(Ljava/lang/Object;)V", false);
        }

        // Copy instance and args back to the frame stack
        if (!callingMethodIsStatic) {
            mv.visitLdcInsn(0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "peekRefFromUnpatchable", "(I)Ljava/lang/Object;", false);
            mv.visitTypeInsn(Opcodes.CHECKCAST, owner);
        }
        for (int i = 0; i < argumentTypes.length; i++) {
            Type argumentType = argumentTypes[i];
            final int sort = argumentType.getSort();
            //
            mv.visitLdcInsn(offsets[i]);
            //
            switch (sort) {
                case Type.VOID:
                case Type.OBJECT:
                case Type.ARRAY:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "peekRefFromUnpatchable", "(I)Ljava/lang/Object;", false);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, argumentType.getDescriptor());
                    break;
                case Type.INT:
                case Type.SHORT:
                case Type.BYTE:
                case Type.BOOLEAN:
                case Type.CHAR:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "peekIntFromUnpatchable", "(I)I", false);
                    break;
                case Type.LONG:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "peekLongFromUnpatchable", "(I)J", false);
                    break;
                case Type.FLOAT:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "peekFloatFromUnpatchable", "(I)F", false);
                    break;
                case Type.DOUBLE:
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "peekDoubleFromUnpatchable", "(I)D", false);
                    break;
                default:
                    throw new AssertionError("This shouldn't happen");
            }
        }
    }

    private void visitMethodInsnUnpatchable(int opcode, String owner, String name, String desc, boolean itf) {
        TryCatchExcludeBlock excludeBlock = new TryCatchExcludeBlock();

        Label noRestoringLabel = new Label();
        mv.visitJumpInsn(Opcodes.GOTO, noRestoringLabel);

        // label_i:
        mv.visitLabel(restoreLabels[restorePointsProcessed]);

        excludeBlock.label_1 = restoreLabels[restorePointsProcessed];

        visitCurrentFrameWithoutStack();

        // Restore execution context
        {
            boolean callingMethodIsStatic = (opcode == Opcodes.INVOKESTATIC);
            final Type callingMethodType = Type.getType(desc);

            restoreLocals();

            // Восстанавливаем дно стека
            restoreStackBottom(callingMethodType, callingMethodIsStatic);

            // Восстанавливаем instance для вызова, если метод - экземплярный
            if (!callingMethodIsStatic) restoreInstance(callingMethodType);

            // Восстанавливаем аргументы вызова (для вызова unpatchable кода они были сохранены)
            restoreArgs(callingMethodType);

            // Устанавливаем флаг того, что мы вызываем unpatchable метод
            mv.visitLdcInsn(1);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "setUnpatchableCall", "(Z)V", false);
        }

        // Сюда приходим сразу, если нет необходимости восстанавливать стек
        mv.visitLabel(noRestoringLabel);
        putFrame(currentFrame());

        boolean callingMethodIsStatic = (opcode == Opcodes.INVOKESTATIC);
        final Type callingMethodType = Type.getType(desc);
        final Type[] argumentTypes = callingMethodType.getArgumentTypes();

        // Offsets for each arg in temporary stacks
        int[] offsets = calculateUnpatchableOffsets(argumentTypes, callingMethodIsStatic);

        // Push instance and args to temporary storage before call
        saveArgsInTempStorage(argumentTypes, callingMethodIsStatic, offsets, owner);

        Label beforeCallLabel = new Label();
        mv.visitLabel(beforeCallLabel);

        // Original call
        super.visitMethodInsn(opcode, owner, name, desc, itf);

        Label afterCallLabel = new Label();
        mv.visitLabel(afterCallLabel);

        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "isYielding", "()Z", false);
        Label noSaveContextLabel = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, noSaveContextLabel);

        // Save execution context
        {
            // Save args of calling unpatchable method
            saveArgs(argumentTypes, offsets, callingMethodIsStatic);

            // Clean the used space in unpatchables storage
            cleanUnpatchablesFrame(argumentTypes, callingMethodIsStatic);

            // Save stack
            saveStackBottom(Type.getReturnType(desc));

            // Save locals
            saveLocals();

            // Save "this" if method is instance method
            saveThis();

            // Save the state
            mv.visitLdcInsn(restorePointsProcessed);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushState", "(I)V", false);

            // And return 0 or null
            returnDefault();
        }
        mv.visitLabel(noSaveContextLabel);
        putFrame(nextFrame());

        cleanUnpatchablesFrame(argumentTypes, callingMethodIsStatic);

        Label noExceptionLabel = new Label();
        mv.visitJumpInsn(Opcodes.GOTO, noExceptionLabel);

        Label exceptionLabel = new Label();
        mv.visitLabel(exceptionLabel);
        putFrame(nextFrame(), "Ljava/lang/Throwable;");

        cleanUnpatchablesFrame(argumentTypes, callingMethodIsStatic);

        Label endNoExceptionsBlockLabel = new Label();
        mv.visitLabel(endNoExceptionsBlockLabel);
        mv.visitInsn(Opcodes.ATHROW);

        mv.visitLabel(noExceptionLabel);
        putFrame(nextFrame());

        excludeBlock.label_2 = endNoExceptionsBlockLabel;

        // All exceptions from original call are rethrowed after cleaning unpatchables frame
        mv.visitTryCatchBlock(beforeCallLabel, afterCallLabel, exceptionLabel, "Ljava/lang/Throwable;");

        restorePointsProcessed++;
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        assert analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.METHOD_INSN;

        MethodId callingMethodId = new MethodId(owner, name, desc);
        // Do nothing if this call is not restore point call
        if (!analyzeResult.getRestorePoints().contains(callingMethodId)) {
            mv.visitMethodInsn(opcode, owner, name, desc, itf);
            insnIndex++;
            return;
        }

        if (analyzeResult.getUnpatchableRestorePoints() != null &&
                analyzeResult.getUnpatchableRestorePoints().contains(callingMethodId)) {
            visitMethodInsnUnpatchable(opcode, owner, name, desc, itf);
        } else {
            visitMethodInsnPatchable(opcode, owner, name, desc, itf);
        }

        insnIndex++;
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        super.visitMaxs(maxStack, maxLocals);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        assert analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.FIELD_INSN;
        super.visitFieldInsn(opcode, owner, name, desc);
        insnIndex++;
        noInsnsSinceLastFrame = false;
    }

    @Override
    public void visitInsn(int opcode) {
        assert analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.INSN;
        super.visitInsn(opcode);
        insnIndex++;
        noInsnsSinceLastFrame = false;
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        assert analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.INT_INSN;
        super.visitIntInsn(opcode, operand);
        insnIndex++;
        noInsnsSinceLastFrame = false;
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        assert analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.VAR_INSN;
        super.visitVarInsn(opcode, var);
        insnIndex++;
        noInsnsSinceLastFrame = false;
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        assert analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.TYPE_INSN;
        super.visitTypeInsn(opcode, type);
        insnIndex++;
        noInsnsSinceLastFrame = false;
    }

    /**
     * Shouldn't called by ASM because it is deprecated;
     * shouldn't called by our code, because we can avoid calling it :)
     */
    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
        assert analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.INVOKE_DYNAMIC_INSN;
        super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
        insnIndex++;
        noInsnsSinceLastFrame = false;
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        assert analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.JUMP_INSN;
        super.visitJumpInsn(opcode, label);
        insnIndex++;
        noInsnsSinceLastFrame = false;
    }

    @Override
    public void visitLdcInsn(Object cst) {
        assert analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.LDC_INSN;
        super.visitLdcInsn(cst);
        insnIndex++;
        noInsnsSinceLastFrame = false;
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        assert analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.IINC_INSN;
        super.visitIincInsn(var, increment);
        insnIndex++;
        noInsnsSinceLastFrame = false;
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        assert analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.TABLESWITCH_INSN;
        super.visitTableSwitchInsn(min, max, dflt, labels);
        insnIndex++;
        noInsnsSinceLastFrame = false;
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        assert analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.LOOKUPSWITCH_INSN;
        super.visitLookupSwitchInsn(dflt, keys, labels);
        insnIndex++;
        noInsnsSinceLastFrame = false;
    }

    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {
        assert analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.MULTIANEWARRAY_INSN;
        super.visitMultiANewArrayInsn(desc, dims);
        insnIndex++;
        noInsnsSinceLastFrame = false;
    }

    private boolean noInsnsSinceLastFrame = true;

    @Override
    public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
        assert analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.FRAME;

        // Если эту проверку убрать, возможны случаи, когда подряд будут идти 2 фрейма, а между ними
        // ни одной значащей инструкции. В этой ситуации ASM ругнётся IllegalStateException, т.к. ожидает
        // в таких случаях только фрейм с типом SAME
        if (!noInsnsSinceLastFrame) {
            this.putFrame(currentFrame());
        }

        insnIndex++;
    }

    @Override
    public void visitLabel(Label label) {
        assert analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.LABEL;
        super.visitLabel(label);
        insnIndex++;
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        assert analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.LINE;
        super.visitLineNumber(line, start);
        insnIndex++;
    }
}
