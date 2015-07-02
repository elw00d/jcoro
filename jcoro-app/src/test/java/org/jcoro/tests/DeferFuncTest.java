package org.jcoro.tests;

import org.jcoro.Coro;
import org.jcoro.ICoroRunnable;
import org.jcoro.Instrument;
import org.jcoro.RestorePoint;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author elwood
 */
public class DeferFuncTest {
    public static void main(String[] args) {
        new DeferFuncTest().testDeferFunc();
    }

    @Test
    public void testDeferFunc() {
        int[] array = new int[2];
        array[0] = 0;
        array[1] = 0;
        Coro coro = Coro.initSuspended(new ICoroRunnable() {
            @Override
            @Instrument({@RestorePoint(value = "foo")})
            public void run() {
                int i = 5;
                double f = 10;
                System.out.println("coro: func begin, i = " + i);
                //
                final String argStr = foo(i, f, "argStr");
                //
                System.out.println("coro: func end, i: " + i + ", str: " + argStr);
            }

            @Instrument(@RestorePoint("yield"))
            private String foo(int x, double y, String m) {
                Assert.assertTrue(x == 5);
                Assert.assertTrue(y == 10);
                Assert.assertTrue(m.equals("argStr"));
                //
                Coro c = Coro.get();
                c.yield(() -> {
                    System.out.println("Deferred func !");
                    c.resume();
                    Assert.assertTrue(array[0] == 1);
                });
                array[0] = 1;
                //
                Assert.assertTrue(x == 5);
                Assert.assertTrue(y == 10);
                Assert.assertTrue(m.equals("argStr"));
                return "returnedStr";
            }
        });
        System.out.println("Starting coro");
        coro.start();
        System.out.println("Coro yielded");
//        Assert.assertTrue(array[0] == 0);
//        coro.resume();
        System.out.println("Coro finished");
    }
}
