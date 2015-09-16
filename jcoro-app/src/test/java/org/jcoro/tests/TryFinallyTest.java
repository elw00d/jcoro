package org.jcoro.tests;

import org.jcoro.Async;
import org.jcoro.Await;
import org.jcoro.Coro;
import org.jcoro.ICoroRunnable;
import org.junit.Test;

/**
 * todo : testify this
 *
 * @author elwood
 */
public class TryFinallyTest {
    public static void main(String[] args) {
        new TryFinallyTest().test2();
    }

    @Test
    public void test() {
        final Coro coro = Coro.initSuspended(new ICoroRunnable() {
            @Override
            @Async(@Await("yield"))
            public void run() {
                try {
                    System.out.println("Before yield");
                    Coro coro = Coro.get();
                    coro.yield();
                    System.out.println("After yield");
                } finally {
                    System.out.println("Finally");
                }
            }
        });
        coro.resume();
        coro.resume();
    }

    @Test
    public void test2() {
        final Coro coro = Coro.initSuspended(new ICoroRunnable() {
            @Override
            @Async(@Await("yield"))
            public void run() {
                try {
                    try {
                        System.out.println("Try");
                    } finally {
                        System.out.println("Before yield");
                        Coro coro = Coro.get();
                        coro.yield();
                        System.out.println("After yield");
                    }
                } finally {
                    System.out.println("Outer finally");
                }
            }
        });
        coro.resume();
        coro.resume();
    }
}
