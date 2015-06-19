package org.jcoro;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;

/**
 * @author elwood
 */
public class MethodAnalyzer extends MethodVisitor {
    private final MethodVisitor nextMv; // todo: попробовать позвать след визитор в конце visitEnd()
    private final MethodNode mn;
    private final String owner;
    //private final String name;
    //private final String desc;
    private final MethodId methodId;

    private final byte[] classFile;

    // "out parameters"
    private final Map<MethodId, Integer> restorePointsCounts;
    private final Map<MethodId, Frame[]> framesMap;
    private final Map<MethodId, AbstractInsnNode[]> insnsMap;

    private int restorePoints = 0;

    public MethodAnalyzer(int api, MethodVisitor mv, int access, String owner, String name, String desc, String signature,
                          String[] exceptions, Map<MethodId, Integer> restorePointsCounts,
                          Map<MethodId, Frame[]> framesMap,
                          Map<MethodId, AbstractInsnNode[]> insnsMap,
                          byte[] classFile) {
        super(api, new MethodNode(Opcodes.ASM5, access, name, desc, signature, exceptions));
        //
        this.nextMv = mv;
        this.mn = (MethodNode) super.mv;
        this.owner = owner;
        //this.name = name;
        //this.desc = desc;
        this.classFile = classFile;
        //
        this.methodId = new MethodId(owner, name, desc);
        //
        this.restorePointsCounts = restorePointsCounts;
        this.framesMap = framesMap;
        this.insnsMap = insnsMap;
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        MethodId callingMethodId = new MethodId(owner, name, desc);
        if (InstrumentProgram.isRestorePoint(callingMethodId)) {
            restorePoints++;
        }

        super.visitMethodInsn(opcode, owner, name, desc, itf);
    }

    @Override
    public void visitEnd() {
        if (restorePoints > 0)
            restorePointsCounts.put(methodId, restorePoints);
        //
        class ByteClassLoader extends URLClassLoader {
            public ByteClassLoader(URL[] urls, ClassLoader parent) {
                super(urls, parent);
            }

            @Override
            protected Class<?> findClass(final String name) throws ClassNotFoundException {
                // todo : fix hardcode
                if ("org.jcoro.tests.simpletest2.TestCoro$1".equals(name)) {
                    return defineClass(name, classFile, 0, classFile.length);
                }
                return super.findClass(name);
            }

        }
        ByteClassLoader classLoader = new ByteClassLoader(new URL[0], Thread.currentThread().getContextClassLoader());
        //

        Analyzer analyzer = new Analyzer(new SimpleVerifier() {
            @Override
            protected Class getClass(Type t) {
                try {
                    return super.getClass(t);
                } catch (RuntimeException e) {
                    try {
                        return classLoader.findClass(t.getInternalName().replaceAll("/", "."));
                    } catch (ClassNotFoundException e1) {
                        throw new RuntimeException(e1);
                    }
                }
            }
        });
        AbstractInsnNode[] insns = mn.instructions.toArray();
        insnsMap.put(methodId, insns);
        try {
            Frame[] frames = analyzer.analyze(owner, mn);
            framesMap.put(methodId, frames);
        } catch (AnalyzerException e) {
            throw new RuntimeException("Cannot analyze method", e);
        }
        //
        super.visitEnd();
    }
}
