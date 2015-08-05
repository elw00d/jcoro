package org.jcoro;

import java.util.Stack;

/**
 * @author elwood
 */
public class Coro implements AutoCloseable {
    private static ThreadLocal<Stack<Coro>> activeCoroStack = new ThreadLocal<>();

    private final ICoroRunnable runnable;

    private Coro(ICoroRunnable runnable) {
        this.runnable = runnable;
    }

    public static boolean exists() {
        return getSafe() != null;
    }

    /**
     * Returns top active coro instance or throws IllegalStateException
     * if there are no coro created yet. Should be called by user code if
     * need to retrieve current coro;
     */
    public static Coro get() {
        final Stack<Coro> coroStack = activeCoroStack.get();
        if (null == coroStack || coroStack.empty())
            throw new IllegalStateException("No active coro exists");
        return coroStack.peek();
    }

    /**
     * Returns top active coro instance or null if there are no coro created yet.
     * Called from generated code when instrumented method starts.
     */
    public static Coro getSafe() {
        final Stack<Coro> coroStack = activeCoroStack.get();
        if (null == coroStack || coroStack.empty())
            return null;
        return coroStack.peek();
    }

    /**
     * Returns top active coro instance without checking of its existence.
     * Can be used only if caller is sure about coro exists.
     */
    private static Coro getUnsafe() {
        return activeCoroStack.get().peek();
    }

    private static void pushCoro(Coro coro) {
        Stack<Coro> coroStack = activeCoroStack.get();
        if (coroStack == null) {
            coroStack = new Stack<>();
            activeCoroStack.set(coroStack);
        }
        coroStack.push(coro);
    }

    private boolean isYielding = false;

    public static Coro initSuspended(ICoroRunnable runnable) {
        return new Coro(runnable);
    }

    private Runnable deferFunc;

    private boolean suspendedAfterYield = false;

    /**
     * Приостанавливает выполнение сопрограммы, сохраняя состояние и возвращая дефолтные значения
     * вверх по всему стеку вызовов до корневого ICoroRunnable.run() - метода.
     */
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
        isYielding = true;
        get().refsStack.push(this); // Аргументы и this если есть
    }

    public void setDeferFunc(Runnable deferFunc) {
        this.deferFunc = deferFunc;
    }

    /**
     * Приостанавливает выполнение сопрограммы, сохраняя состояние и возвращая дефолтные значения
     * вверх по всему стеку вызовов до корневого ICoroRunnable.run() - метода. Дополнительно задаёт
     * deferFunc, который будет выполнен после сохранения состояния всех методов. В качестве deferFunc
     * удобно передавать лямбду, которая шедулит очередную асинхронную операцию с коллбеком.
     */
    public void yield(Runnable deferFunc) {
        // Так как мы не инструментируем этот метод (он - библиотечный), а вызов этого метода
        // является точкой восстановления (как и yield() без параметров), то
        // мы должны быть аккуратными в месте вызова этого метода, и не менять состояние this,
        // если мы оказались в этом методе при восстановлении стека (при вызове yield(null)).
        // Иначе при восстановлении стека можно неожиданно обнулить deferFunc,
        // а выше по стеку тоже был вызов resume() - например если одна асинхронная операция
        // выполняется в том же потоке, в котором была запланирована
        if (!suspendedAfterYield) {
            this.deferFunc = deferFunc;
        }
        yield();
    }

    public void start() {
        resume();
    }

    public void resume() {
        pushCoro(this);
        try {
            // Call coro func
            if (suspendedAfterYield) {
                Object rootInstance = popRef();
                if (rootInstance != runnable) throw new AssertionError("This shouldn't happen");
            }
            runnable.run();
        } finally {
            if (isYielding) {
                isYielding = false;
                suspendedAfterYield = true;
            }
            activeCoroStack.get().pop();
        }
        // Call defer func
        if (deferFunc != null) {
            // Обнуляем deferFunc перед вызовом, т.к. внутри deferFunc может быть любой код,
            // в том числе и приводящий к рекурсивному вызову resume() - например если
            // запланированная в deferFunc асинхронная операция выполняется мгновенно в вызывающем потоке
            // В этом случае при вызове coro,resume() из callback'a вложенной асинхронной операции
            // resume() увидит deferFunc, уже выполняющийся, и выполнит его ещё раз. Скорее всего,
            // это приведёт к тому, что крайний вызов будет лишним, а resume() выполнится ещё один раз,
            // когда уже не будет сохранённого state, и сопрограмма начнёт выполняться сначала.
            // В общем, произойдёт полное разрушение потока выполнения
            Runnable deferFuncCopy = deferFunc;
            deferFunc = null;
            deferFuncCopy.run();
        }
    }

    private Stack<Integer> statesStack = new Stack<>();

    private Stack<Object> refsStack = new Stack<>();
    private Stack<Integer> intsStack = new Stack<>();
    private Stack<Double> doublesStack = new Stack<>();
    private Stack<Float> floatsStack = new Stack<>();
    private Stack<Long> longsStack = new Stack<>();

    public static void pushState(int state) {
        getUnsafe().statesStack.push(state);
    }

    public static void pushRef(Object ref) {
        getUnsafe().refsStack.push(ref);
    }

    public static void pushInt(int i) {
        getUnsafe().intsStack.push(i);
    }

    public static void pushDouble(double d) {
        getUnsafe().doublesStack.push(d);
    }

    public static void pushFloat(float f) {
        getUnsafe().floatsStack.push(f);
    }

    public static void pushLong(long l) {
        getUnsafe().longsStack.push(l);
    }

    public static Integer popState() {
        Stack<Integer> statesStack = getUnsafe().statesStack;
        if (statesStack.empty()) return null;
        return statesStack.pop();
    }

    public static Object popRef() {
        return getUnsafe().refsStack.pop();
    }

    public static int popInt() {
        return getUnsafe().intsStack.pop();
    }

    public static double popDouble() {
        return getUnsafe().doublesStack.pop();
    }

    public static float popFloat() {
        return getUnsafe().floatsStack.pop();
    }

    public static long popLong() {
        return getUnsafe().longsStack.pop();
    }

    public static boolean isYielding() {
        final Coro coro = getSafe();
        return coro != null && coro.isYielding;
    }

    // Stuff for support of storing args of methods before calling
    // (if dealing with unpatchable methods)
    // Здесь довольно много не очень хорошо поименованных методов, это следствие того, что
    // объектики при вызове unpatchable метода нужно неоднократно перекладывать между стеками

    /**
     * Флаг, устанавливается в true при вызове unpatchable метода при восстановлении контекста выполнения
     * (чтобы первый вызванный unpatchable-кодом инструментированный метод скорректировал refsStack, убрав
     * лишний this со стека (если он не статический), после этого сбросив флаг обратно в false). Если же первый
     * инструментированный метод - статический, то флаг просто должен быть сброшен в false.
     */
    private boolean unpatchableCall;

    public static boolean isUnpatchableCall() {
        return getUnsafe().unpatchableCall;
    }

    public static void setUnpatchableCall(boolean unpatchableCall) {
        getUnsafe().unpatchableCall = unpatchableCall;
    }

    private static class UnpatchableMethodArgsStore {
        public Stack<Object> refsStack = new Stack<>();
        public Stack<Integer> intsStack = new Stack<>();
        public Stack<Long> longsStack = new Stack<>();
        public Stack<Float> floatsStack = new Stack<>();
        public Stack<Double> doublesStack = new Stack<>();
    }

    private UnpatchableMethodArgsStore unpatchableStore;

    private UnpatchableMethodArgsStore getUnpatchableStore() {
        if (null == unpatchableStore)
            unpatchableStore = new UnpatchableMethodArgsStore();
        return unpatchableStore;
    }

    public static void pushRefToUnpatchable(Object ref) {
        getUnsafe().getUnpatchableStore().refsStack.push(ref);
    }

    public static void pushIntToUnpatchable(int i) {
        getUnsafe().getUnpatchableStore().intsStack.push(i);
    }

    public static void pushLongToUnpatchable(long l) {
        getUnsafe().getUnpatchableStore().longsStack.push(l);
    }

    public static void pushFloatToUnpatchable(float f) {
        getUnsafe().getUnpatchableStore().floatsStack.push(f);
    }

    public static void pushDoubleToUnpatchable(double d) {
        getUnsafe().getUnpatchableStore().doublesStack.push(d);
    }

    public static Object popRefFromUnpatchable() {
        return getUnsafe().getUnpatchableStore().refsStack.pop();
    }

    public static int popIntFromUnpatchable() {
        return getUnsafe().getUnpatchableStore().intsStack.pop();
    }

    public static long popLongFromUnpatchable() {
        return getUnsafe().getUnpatchableStore().longsStack.pop();
    }

    public static float popFloatFromUnpatchable() {
        return getUnsafe().getUnpatchableStore().floatsStack.pop();
    }

    public static double popDoubleFromUnpatchable() {
        return getUnsafe().getUnpatchableStore().doublesStack.pop();
    }

    public static Object peekRefFromUnpatchable(int skip) {
        final Stack<Object> stack = getUnsafe().getUnpatchableStore().refsStack;
        return stack.get(stack.size() - 1 - skip);
    }

    public static int peekIntFromUnpatchable(int skip) {
        final Stack<Integer> stack = getUnsafe().getUnpatchableStore().intsStack;
        return stack.get(stack.size() - 1 - skip);
    }

    public static long peekLongFromUnpatchable(int skip) {
        final Stack<Long> stack = getUnsafe().getUnpatchableStore().longsStack;
        return stack.get(stack.size() - 1 - skip);
    }

    public static float peekFloatFromUnpatchable(int skip) {
        final Stack<Float> stack = getUnsafe().getUnpatchableStore().floatsStack;
        return stack.get(stack.size() - 1 - skip);
    }

    public static double peekDoubleFromUnpatchable(int skip) {
        final Stack<Double> stack = getUnsafe().getUnpatchableStore().doublesStack;
        return stack.get(stack.size() - 1 - skip);
    }

    public void close() throws Exception {
    }
}
