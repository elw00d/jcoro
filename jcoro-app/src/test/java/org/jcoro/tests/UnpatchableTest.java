package org.jcoro.tests;

import org.jcoro.Coro;
import org.jcoro.ICoroRunnable;
import org.jcoro.Instrument;
import org.jcoro.RestorePoint;
import org.junit.Ignore;
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

    @Ignore
    @Test
    public void test() {
        Coro coro = Coro.initSuspended(new ICoroRunnable() {
            @Instrument(@RestorePoint(value = "unpatchableMethod", patchable = false))
            public void run() {
                unpatchableMethod(10);
            }

            public void unpatchableMethod(int i) {
                System.out.println(String.format("unpatchableMethod(%d) start", i));
                patchableMethod(i);
                System.out.println(String.format("unpatchableMethod(%d) end", i));
            }

            @Instrument(@RestorePoint("yield"))
            public void patchableMethod(int i) {
                System.out.println("Before yield, i = " + i);
                Coro.get().yield();
                System.out.println("After yield, i = " + i);
            }
        });
        coro.start();
        System.out.println("Paused. Resuming..");
        coro.resume();
    }
}
