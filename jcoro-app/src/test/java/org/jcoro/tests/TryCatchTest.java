package org.jcoro.tests;

import org.jcoro.Coro;
import org.jcoro.ICoroRunnable;
import org.jcoro.Instrument;
import org.jcoro.RestorePoint;
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
            @Instrument(@RestorePoint("yield"))
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
