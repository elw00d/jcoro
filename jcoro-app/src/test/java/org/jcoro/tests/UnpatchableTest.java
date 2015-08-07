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
public class UnpatchableTest {
    public static void main(String[] args) {
        new UnpatchableTest().test();
    }

    @Test
    public void test() {
        Coro coro = Coro.initSuspended(new ICoroRunnable() {
            @Instrument(@RestorePoint(value = "unpatchableMethod", patchable = false))
            public void run() {
                try {
                    unpatchableMethod(10);
                } catch (IllegalArgumentException e) {
                    System.out.println("Successfully catched exception: " + e.getMessage());
                }
            }

            public void unpatchableMethod(int i) {
                System.out.println(String.format("unpatchableMethod(%d) start", i));
                patchableMethod(i);
                System.out.println(String.format("unpatchableMethod(%d) end", i));
            }

            @Instrument(@RestorePoint(value = "unpatchableMethod2", patchable = false))
            public void patchableMethod(int i) {
                System.out.println("patchableMethod(" + i + ")");
                unpatchableMethod2(5, "string");
                System.out.println("patchableMethod(" + i + ") end");
                throw new IllegalArgumentException("SomeException");
            }

            public void unpatchableMethod2(int a, String b) {
                System.out.println(String.format("unpatchableMethod2(%d, %s)", a, b));
                patchableMethod2(a, b);
                System.out.println(String.format("unpatchableMethod2(%d, %s) end", a, b));
            }

            @Instrument(@RestorePoint("yield"))
            public void patchableMethod2(int a, String b) {
                System.out.println(String.format("patchableMethod(%d, %s): before yield", a, b));
                Coro.get().yield();
                System.out.println(String.format("patchableMethod(%d, %s): after yield", a, b));
            }
        });
        coro.start();
        System.out.println("Paused. Resuming..");
        coro.resume();
    }
}
