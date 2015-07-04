package org.jcoro;

import java.util.Stack;

/**
 * @author elwood
 */
public class Coro implements AutoCloseable {
    private static ThreadLocal<Coro> activeCoro = new ThreadLocal<>();

    private final ICoroRunnable runnable;

    private Coro(ICoroRunnable runnable) {
        this.runnable = runnable;
    }

    /**
     * Returns active coro instance or null if there are no coro created yet.
     * Called basically from injected bytecode.
     */
    public static Coro get() {
        return activeCoro.get();
    }

    private volatile boolean isYielding = false;

    public static Coro initSuspended(ICoroRunnable runnable) {
        return new Coro(runnable);
    }

    private volatile Runnable deferFunc;

    private volatile boolean suspendedAfterYield = false;

    public void yield() {
        if (isYielding)
            throw new IllegalStateException("Yielding is already started");
        // suspendedAfterYield равен true, если мы восстанавливаем стек и в процессе восстановления стека
        // добрались до самого глубокого вызова yield() - на котором собственно и запаузились
        // И если мы находимся внутри yield() и suspendedAfterYield = true, то нам ничего делать не нужно
        // Только сбросить обратно этот флаг с тем, чтобы сопрограмму снова можно было запаузить
        if (suspendedAfterYield) {
            suspendedAfterYield = false;
            return;
        }
//        if (!suspendedAfterYield) {
            isYielding = true;
            get().refsStack.push(this); // Аргументы и this если есть
//            suspendedAfterYield = true;
//        }
    }

    public void yield(Runnable deferFunc) {
        this.deferFunc = deferFunc;
        yield();
    }

    private static ThreadLocal<Integer> resumeCallsInStack = new ThreadLocal<>();

    public void start() {
        resume();
    }

    public void resume() {
        Integer integer = resumeCallsInStack.get();
        resumeCallsInStack.set((integer == null ? 0 : integer) +1);
        if (integer != null && integer > 1) {
            System.out.println("Error!!1");
        }
        if (null != activeCoro.get()) {
            throw new AssertionError("This shouldn't happen");
        }
        activeCoro.set(this);
        try {
            // Call coro func
            runnable.run();
        } finally {
            if (isYielding) {
                isYielding = false;
                suspendedAfterYield = true;
            }
            activeCoro.remove();
        }
        // Call defer func
        try {
            if (deferFunc != null) {
                deferFunc.run();
            }
        } finally {
            deferFunc = null;
        }
        resumeCallsInStack.set(resumeCallsInStack.get()-1);
    }

    private volatile Stack<Integer> statesStack = new Stack<>();

    private volatile Stack<Object> refsStack = new Stack<>();
    private volatile Stack<Integer> intsStack = new Stack<>();
    private volatile Stack<Double> doublesStack = new Stack<>();
    private volatile Stack<Float> floatsStack = new Stack<>();
    private volatile Stack<Long> longsStack = new Stack<>();

    public static void pushState(int state) {
        get().statesStack.push(state);
    }

    public static void pushRef(Object ref) {
        get().refsStack.push(ref);
    }

    public static void pushInt(int i) {
        get().intsStack.push(i);
    }

    public static void pushDouble(double d) {
        get().doublesStack.push(d);
    }

    public static void pushFloat(float f) {
        get().floatsStack.push(f);
    }

    public static void pushLong(long l) {
        get().longsStack.push(l);
    }

    public static Integer popState() {
        Stack<Integer> statesStack = get().statesStack;
        if (statesStack.empty()) return null;
        return statesStack.pop();
    }

    public static Object popRef() {
        return get().refsStack.pop();
    }

    public static int popInt() {
        return get().intsStack.pop();
    }

    public static double popDouble() {
        return get().doublesStack.pop();
    }

    public static float popFloat() {
        return get().floatsStack.pop();
    }

    public static long popLong() {
        return get().longsStack.pop();
    }

    public static boolean isYielding() {
        return get() != null && get().isYielding;
    }

    public void close() throws Exception {

    }
}
