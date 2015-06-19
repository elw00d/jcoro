package org.jcoro;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static java.util.stream.Collectors.toList;

/**
 * @author elwood
 */
public class Test {
    public class InnerClass implements ICoroRunnable {
        @Override
        public void run() {
            m();
        }
    }

    public static void bar(int i, Function<Integer, Boolean> predicate, double x) {
        System.out.println("bar");
    }

    public static void foo() {
        Function<Integer, Boolean> funcInstance = integer -> {
            return true;
        };
        bar(14, funcInstance, 4.);
    }

    public static void m() {
        ICoroRunnable coroRunnable = () -> {
            System.out.println("");
            p();
        };
    }

    public static void p() {
        Coro.get().yield();
    }
}
