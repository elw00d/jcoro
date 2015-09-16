package org.jcoro.tests;

import org.jcoro.Await;
import org.jcoro.Coro;
import org.jcoro.ICoroRunnable;
import org.jcoro.Async;
import org.junit.Test;

/**
 * todo : testify this
 *
 * @author elwood
 */
public class TryCatchTest {
    public static void main(String[] args) {
        new TryCatchTest().testCatch();
    }

    @Test
    public void testCatch() {
        final Coro coro = Coro.initSuspended(new ICoroRunnable() {
            @Override
            @Async(@Await("yield"))
            public void run() {
                Coro coro = Coro.get();
                try {
                    foo();
                    coro.yield(); // unreachable
                    System.out.println("unreachable");
                } catch (RuntimeException e) {
                    System.out.println("Catched exc");
                }
            }
        });
        coro.resume();
    }

    public void foo() {
        throw new RuntimeException();
    }
}
