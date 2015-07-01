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

    private boolean isYielding = false;

    public static Coro initSuspended(ICoroRunnable runnable) {
        return new Coro(runnable);
    }

    private boolean alreadyYielded = false;

    public boolean isAlreadyYielded() {
        return alreadyYielded;
    }

    public void yield() {
        if (!alreadyYielded) {
            isYielding = true;
            get().refsStack.push(this); // Аргументы и this если есть
            alreadyYielded = true;
        }
    }

    public void start() {
        resume();
    }

    public void resume() {
        if (null != activeCoro.get()) {
            throw new AssertionError("This shouldn't happen");
        }
        activeCoro.set(this);
        try {
            // todo : restore stack, call top func
            runnable.run();
        } finally {
            isYielding = false;
            activeCoro.remove();
        }
    }

    private Stack<Integer> statesStack = new Stack<>();

    private Stack<Object> refsStack = new Stack<>();
    private Stack<Integer> intsStack = new Stack<>();
    private Stack<Double> doublesStack = new Stack<>();
    private Stack<Float> floatsStack = new Stack<>();
    private Stack<Long> longsStack = new Stack<>();

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
