package org.jcoro;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.AnnotationNode;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toList;

/**
 * Searches type-annotated (with @Async annotation) lambdas.
 * Type annotations can be placed before invokedynamic insn and before previous instruction (bug in javac?).
 * So, we should track every neighbor instructions.
 *
 * Found async lambdas are put on resultMap.
 *
 * @author elwood
 */
public class LambdasSearchVisitor extends ClassVisitor {
    private final Map<MethodId, AsyncLambdaInfo> resultMap;

    public LambdasSearchVisitor(Map<MethodId, AsyncLambdaInfo> resultMap) {
        super(Opcodes.ASM5);
        this.resultMap = resultMap;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        return new MethodVisitor(Opcodes.ASM5) {
            private List<Await> lastRestorePoints = null;
            private String lastDesc = null;
            private Handle lastLambda = null;

            @Override
            public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath, String desc, boolean visible) {
                if (TypeReference.CAST == new TypeReference(typeRef).getSort()
                        && "Lorg/jcoro/Async;".equals(desc)) {
                    return new AnnotationNode(Opcodes.ASM5, desc) {
                        @Override
                        public void visitEnd() {
                            assert "value".equals(this.values.get(0));
                            List<AnnotationNode> restorePoints = (List<AnnotationNode>) this.values.get(1);
                            lastRestorePoints = Helpers.parseAwaitAnnotations(restorePoints);
                            checkIfRestorePointWasDefined();
                            super.visitEnd();
                        }
                    };
                }
                return super.visitInsnAnnotation(typeRef, typePath, desc, visible);
            }

            private void checkIfRestorePointWasDefined() {
                if (lastRestorePoints != null && lastDesc != null && lastLambda != null) {
                    resultMap.put(new MethodId(lastLambda.getOwner(), lastLambda.getName(), lastLambda.getDesc()),
                            new AsyncLambdaInfo(lastDesc, lastRestorePoints));
                    clearLastInvokeDynamicArgs();
                }
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
                lastDesc = desc;
                lastLambda = (Handle) bsmArgs[1];
                checkIfRestorePointWasDefined();
                super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
            }

            private void clearLastInvokeDynamicArgs() {
                lastDesc = null;
                lastLambda = null;
                lastRestorePoints = null;
            }

            @Override
            public void visitInsn(int opcode) {
                clearLastInvokeDynamicArgs();
                super.visitInsn(opcode);
            }

            @Override
            public void visitIntInsn(int opcode, int operand) {
                clearLastInvokeDynamicArgs();
                super.visitIntInsn(opcode, operand);
            }

            @Override
            public void visitVarInsn(int opcode, int var) {
                clearLastInvokeDynamicArgs();
                super.visitVarInsn(opcode, var);
            }

            @Override
            public void visitTypeInsn(int opcode, String type) {
                clearLastInvokeDynamicArgs();
                super.visitTypeInsn(opcode, type);
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                clearLastInvokeDynamicArgs();
                super.visitFieldInsn(opcode, owner, name, desc);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                clearLastInvokeDynamicArgs();
                super.visitMethodInsn(opcode, owner, name, desc, itf);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String desc) {
                clearLastInvokeDynamicArgs();
                super.visitMethodInsn(opcode, owner, name, desc);
            }

            @Override
            public void visitJumpInsn(int opcode, Label label) {
                clearLastInvokeDynamicArgs();
                super.visitJumpInsn(opcode, label);
            }

            @Override
            public void visitLdcInsn(Object cst) {
                clearLastInvokeDynamicArgs();
                super.visitLdcInsn(cst);
            }

            @Override
            public void visitIincInsn(int var, int increment) {
                clearLastInvokeDynamicArgs();
                super.visitIincInsn(var, increment);
            }

            @Override
            public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                clearLastInvokeDynamicArgs();
                super.visitTableSwitchInsn(min, max, dflt, labels);
            }

            @Override
            public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                clearLastInvokeDynamicArgs();
                super.visitLookupSwitchInsn(dflt, keys, labels);
            }

            @Override
            public void visitMultiANewArrayInsn(String desc, int dims) {
                clearLastInvokeDynamicArgs();
                super.visitMultiANewArrayInsn(desc, dims);
            }
        };
    }
}
