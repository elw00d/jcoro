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
    private final boolean rootCall;
    private final Type returnType;

    private int insnIndex = 0; // Currently monitoring index of original instruction
    private Label[] restoreLabels;
    private int restorePointsProcessed = 0;

    public MethodAdapter(int api, MethodVisitor mv, MethodAnalyzeResult methodAnalyzeResult,
                         boolean isStatic, boolean rootCall, Type returnType) {
        super(api, mv);
        //
        this.analyzeResult = methodAnalyzeResult;
        this.isStatic = isStatic;
        this.rootCall = rootCall;
        this.returnType = returnType;
    }

    private boolean generatingCode = false; // todo : remove this

    private class GenerateCode implements AutoCloseable {
        public GenerateCode() {
            if (generatingCode) throw new AssertionError("This shouldn't happen");
            generatingCode = true;
        }

        @Override
        public void close() throws Exception {
            if (!generatingCode) throw new AssertionError("This shouldn't happen");
            generatingCode = false;
        }
    }

    private Frame currentFrame() {
        return analyzeResult.getFrames()[insnIndex];
    }

    private Frame nextFrame() {
        return analyzeResult.getFrames()[insnIndex + 1];
    }

    @Override
    public void visitCode() {
        // if (Coro.get() == null) goto noActiveCoroLabel;
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "get", "()Lorg/jcoro/Coro;", false);
        Label noActiveCoroLabel = new Label();
        //mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"Lorg/jcoro/Coro;"});
        mv.visitJumpInsn(Opcodes.IFNULL, noActiveCoroLabel);

        // popState()
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popState", "()Ljava/lang/Integer;", false);
        mv.visitInsn(Opcodes.DUP);
        Label noActiveStateLabel = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, noActiveStateLabel);

        // switch (state)
        final int nRestorePoints = analyzeResult.getRestorePointCallsCount();
        restoreLabels = new Label[nRestorePoints];
        for (int i = 0; i < nRestorePoints; i++) restoreLabels[i] = new Label();
        if (nRestorePoints == 1) {
            // Если state != null и оно может быть только одно, то сразу прыгаем на метку
            mv.visitInsn(Opcodes.POP);
            mv.visitJumpInsn(Opcodes.GOTO, restoreLabels[0]);
        } else {
            assert nRestorePoints > 1;
            mv.visitTableSwitchInsn(0, nRestorePoints, noActiveCoroLabel, restoreLabels);
        }

        // noActiveStateLabel:
        mv.visitLabel(noActiveStateLabel);
        visitCurrentFrame("Ljava/lang/Integer;");
        mv.visitInsn(Opcodes.POP);

        // noActiveCoroLabel:
        mv.visitLabel(noActiveCoroLabel);
        visitCurrentFrame(null);
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
        } else if (local == BasicValue.INT_VALUE) {
            return Opcodes.INTEGER;
        } else if (local == BasicValue.LONG_VALUE) {
            return Opcodes.LONG;
        } else if (local == BasicValue.FLOAT_VALUE) {
            return Opcodes.FLOAT;
        } else if (local == BasicValue.DOUBLE_VALUE) {
            return Opcodes.DOUBLE;
        } else {
            throw new AssertionError("This shouldn't happen");
        }
    }

    private void visitCurrentFrame(String additionalStackOperand) {
        final Frame frame = currentFrame();
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
        mv.visitFrame(Opcodes.F_FULL, fixedLocals.length, fixedLocals, stacks.length, stacks);
    }

    private void visitNextFrame() {
        final Frame frame = nextFrame();
        Object[] locals = new Object[frame.getLocals()];
        for (int i = 0; i < frame.getLocals(); i++) {
            locals[i] = convertFrameOperandToInsn(frame.getLocal(i));
        }
        Object[] stacks = new Object[frame.getStackSize()];
        for (int i = 0; i < frame.getStackSize(); i++) {
            stacks[i] = convertFrameOperandToInsn(frame.getStack(i));
        }
        Object[] fixedLocals = fixLocals(locals);
        mv.visitFrame(Opcodes.F_FULL, fixedLocals.length, fixLocals(locals), stacks.length, stacks);
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
        mv.visitFrame(Opcodes.F_FULL, fixedLocals.length, fixLocals(locals), 0, new Object[0]);
    }

    private Object[] fixLocals(Object[] locals) {
        List<Object> fixed = new ArrayList<>();
        for (int i = 0; i < locals.length; i++) {
            fixed.add(locals[i]);
            if (locals[i] == Opcodes.DOUBLE) {
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

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        assert generatingCode || analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.METHOD_INSN;
        try {
            MethodId callingMethodId = new MethodId(owner, name, desc);
            // Do nothing if this call is not restore point call
            if (!analyzeResult.getRestorePoints().contains(callingMethodId)) {
                super.visitMethodInsn(opcode, owner, name, desc, itf);
                return;
            }

            Label noActiveCoroLabel = new Label();
            mv.visitJumpInsn(Opcodes.GOTO, noActiveCoroLabel);

            // label_i:
            mv.visitLabel(restoreLabels[restorePointsProcessed]);

            visitCurrentFrameWithoutStack();

            // pop the stack and locals
            {
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
//                        mv.visitFrame();
                    } else if (local == BasicValue.INT_VALUE) {
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popInt", "()I", false);
                        mv.visitVarInsn(Opcodes.ISTORE, i);
                    } else if (local == BasicValue.LONG_VALUE) {
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popLong", "()J", false);
                        mv.visitVarInsn(Opcodes.LSTORE, i);
                    } else if (local == BasicValue.FLOAT_VALUE) {
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popFloat", "()F", false);
                        mv.visitVarInsn(Opcodes.FSTORE, i);
                    } else {
                        assert local == BasicValue.DOUBLE_VALUE;
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popDouble", "()D", false);
                        mv.visitVarInsn(Opcodes.DSTORE, i);
                    }
                }
                // Восстанавливаем дно стека
                boolean callingMethodIsStatic = (opcode == Opcodes.INVOKESTATIC);
                final Type callingMethodType = Type.getType(desc);
                final Type[] argumentTypes = callingMethodType.getArgumentTypes();
                int nArgs = argumentTypes.length; //"foo".equals(name) ? 3 : 0; // todo :
                int skipStackVars = nArgs + ((!callingMethodIsStatic) ? 1 : 0);
                //
                for (int i = frame.getStackSize() - 1; i >= skipStackVars; i--) {
                    BasicValue local = (BasicValue) frame.getStack(i);
                    if (local == BasicValue.UNINITIALIZED_VALUE) {
                        // do nothing
                    } else if (local == BasicValue.RETURNADDRESS_VALUE) {
                        // do nothing
                    } else if (local.isReference()) {
                        final String typeDescriptor = local.getType().getDescriptor();
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popRef", "()Ljava/lang/Object;", false);
                        mv.visitTypeInsn(Opcodes.CHECKCAST, typeDescriptor);
                    } else if (local == BasicValue.INT_VALUE) {
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popInt", "()I", false);
                    } else if (local == BasicValue.LONG_VALUE) {
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popLong", "()J", false);
                    } else if (local == BasicValue.FLOAT_VALUE) {
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popFloat", "()F", false);
                    } else {
                        assert local == BasicValue.DOUBLE_VALUE;
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popDouble", "()D", false);
                    }
                }
                // Восстанавливаем instance для вызова, если метод - экземплярный
                if (!callingMethodIsStatic) {
                    BasicValue local = (BasicValue) frame.getStack(0);
                    if (local == BasicValue.UNINITIALIZED_VALUE) {
                        // do nothing
                    } else if (local == BasicValue.RETURNADDRESS_VALUE) {
                        // do nothing
                    } else if (local.isReference()) {
                        final String typeDescriptor = local.getType().getDescriptor();
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popRef", "()Ljava/lang/Object;", false);
                        mv.visitTypeInsn(Opcodes.CHECKCAST, typeDescriptor);
                    } else if (local == BasicValue.INT_VALUE) {
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popInt", "()I", false);
                    } else if (local == BasicValue.LONG_VALUE) {
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popLong", "()J", false);
                    } else if (local == BasicValue.FLOAT_VALUE) {
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popFloat", "()F", false);
                    } else {
                        assert local == BasicValue.DOUBLE_VALUE;
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "popDouble", "()D", false);
                    }
                }
                // Передаём дефолтные значения для аргументов вызова
                // todo : infer args
                for (int i = 0; i < argumentTypes.length; i++) {
                    visitLdcDefaultValueForType(argumentTypes[i]);
                }
//                if ("foo".equals(name)) {
//                    mv.visitLdcInsn(0);
//                    mv.visitLdcInsn(0.0D);
//                    mv.visitInsn(Opcodes.ACONST_NULL);
//                }
            }

            // Сюда приходим сразу, если нет необходимости восстанавливать стек
            mv.visitLabel(noActiveCoroLabel);
            visitCurrentFrame(null);
            super.visitMethodInsn(opcode, owner, name, desc, itf);

            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "isYielding", "()Z", false);
            Label noSaveContextLabel = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, noSaveContextLabel);

            // Save the stack
            {
                Frame frame = nextFrame();
                // Second, save stack
                // Кроме возвращаемого значения вызванного метода - ведь он вернул нам null или 0 в случае
                // после осуществления прерывания
                final Type callingMethodReturnType = Type.getReturnType(desc);
                boolean skipFirstStackItem = (callingMethodReturnType.getSort() != Type.VOID);
                //
                for (int i = skipFirstStackItem ? 1 : 0; i < frame.getStackSize(); i++) {
                    BasicValue local = (BasicValue) frame.getStack(i);
                    if (local == BasicValue.UNINITIALIZED_VALUE) {
                        // do nothing
                    } else if (local == BasicValue.RETURNADDRESS_VALUE) {
                        // do nothing
                    } else if (local.isReference()) {
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushRef", "(Ljava/lang/Object;)V", false);
                    } else if (local == BasicValue.INT_VALUE) {
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushInt", "(I)V", false);
                    } else if (local == BasicValue.LONG_VALUE) {
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushLong", "(J)V", false);
                    } else if (local == BasicValue.FLOAT_VALUE) {
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushFloat", "(F)V", false);
                    } else {
                        assert local == BasicValue.DOUBLE_VALUE;
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushDouble", "(D)V", false);
                    }
                }
                // Thirst, save locals
                for (int i = 0; i <frame.getLocals(); i++) {
                    BasicValue local = (BasicValue) frame.getLocal(i);
                    if (local == BasicValue.UNINITIALIZED_VALUE) {
                        // do nothing
                    } else if (local == BasicValue.RETURNADDRESS_VALUE) {
                        // do nothing
                    } else if (local.isReference()) {
                        mv.visitVarInsn(Opcodes.ALOAD, i);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushRef", "(Ljava/lang/Object;)V", false);
                    } else if (local == BasicValue.INT_VALUE) {
                        mv.visitVarInsn(Opcodes.ILOAD, i);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushInt", "(I)V", false);
                    } else if (local == BasicValue.LONG_VALUE) {
                        mv.visitVarInsn(Opcodes.LLOAD, i);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushLong", "(J)V", false);
                    } else if (local == BasicValue.FLOAT_VALUE) {
                        mv.visitVarInsn(Opcodes.FLOAD, i);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushFloat", "(F)V", false);
                    } else {
                        assert local == BasicValue.DOUBLE_VALUE;
                        mv.visitVarInsn(Opcodes.DLOAD, i);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushDouble", "(D)V", false);
                    }
                }
                // Save the state
                mv.visitLdcInsn(restorePointsProcessed);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushState", "(I)V", false);
                // Finally, save "this" if method is instance method and if method is not rootCall in coro-usage calls hierarchy
                // (if it isn't ICoroRunnable.run() method implementation)
                if (!isStatic && !rootCall) {
                    assert frame.getLocals() >= 1; // At least one local ("this") should be present
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jcoro/Coro", "pushRef", "(Ljava/lang/Object;)V", false);
                }
                // And return
                if (!rootCall) {
                    // Push default value for return type
                    visitLdcDefaultValueForType(returnType);
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
                } else {
                    mv.visitInsn(Opcodes.RETURN);
                }
            }
            mv.visitLabel(noSaveContextLabel);
            visitNextFrame();

            restorePointsProcessed++;
        } finally {
            if (!generatingCode) insnIndex++;
        }
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        super.visitMaxs(maxStack, maxLocals);
    }

    @Override
    public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
        assert generatingCode || analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.FRAME;
        //
        this.visitCurrentFrame(null);
        //super.visitFrame(type, nLocal, local, nStack, stack);

        //
        if (!generatingCode) insnIndex++;
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        assert generatingCode || analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.FIELD_INSN;
        super.visitFieldInsn(opcode, owner, name, desc);
        if (!generatingCode) insnIndex++;
    }

    @Override
    public void visitInsn(int opcode) {
        assert generatingCode || analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.INSN;
        super.visitInsn(opcode);
        if (!generatingCode) insnIndex++;
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        assert generatingCode || analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.INT_INSN;
        super.visitIntInsn(opcode, operand);
        if (!generatingCode) insnIndex++;
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        assert generatingCode || analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.VAR_INSN;
        super.visitVarInsn(opcode, var);
        if (!generatingCode) insnIndex++;
    }

    @Override
    public void visitTypeInsn(int opcode, String type) {
        assert generatingCode || analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.TYPE_INSN;
        super.visitTypeInsn(opcode, type);
        if (!generatingCode) insnIndex++;
    }

    /**
     * Shouldn't called by ASM because it is deprecated;
     * shouldn't called by our code, because we can avoid calling it :)
     */
    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc) {
        throw new UnsupportedOperationException();
//        assert generatingCode || insns[insnIndex].getType() == AbstractInsnNode.METHOD_INSN;
//        super.visitMethodInsn(opcode, owner, name, desc);
//        if (!generatingCode) insnIndex++;
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
        assert generatingCode || analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.INVOKE_DYNAMIC_INSN;
        super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
        if (!generatingCode) insnIndex++;
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        assert generatingCode || analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.JUMP_INSN;
        super.visitJumpInsn(opcode, label);
        if (!generatingCode) insnIndex++;
    }

    @Override
    public void visitLabel(Label label) {
        assert generatingCode || analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.LABEL;
        super.visitLabel(label);
        if (!generatingCode) insnIndex++;
    }

    @Override
    public void visitLdcInsn(Object cst) {
        assert generatingCode || analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.LDC_INSN;
        super.visitLdcInsn(cst);
        if (!generatingCode) insnIndex++;
    }

    @Override
    public void visitIincInsn(int var, int increment) {
        assert generatingCode || analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.IINC_INSN;
        super.visitIincInsn(var, increment);
        if (!generatingCode) insnIndex++;
    }

    @Override
    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
        assert generatingCode || analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.TABLESWITCH_INSN;
        super.visitTableSwitchInsn(min, max, dflt, labels);
        if (!generatingCode) insnIndex++;
    }

    @Override
    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
        assert generatingCode || analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.LOOKUPSWITCH_INSN;
        super.visitLookupSwitchInsn(dflt, keys, labels);
        if (!generatingCode) insnIndex++;
    }

    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {
        assert generatingCode || analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.MULTIANEWARRAY_INSN;
        super.visitMultiANewArrayInsn(desc, dims);
        if (!generatingCode) insnIndex++;
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        assert generatingCode || analyzeResult.getInsns()[insnIndex].getType() == AbstractInsnNode.LINE;
        super.visitLineNumber(line, start);
        if (!generatingCode) insnIndex++;
    }
}
