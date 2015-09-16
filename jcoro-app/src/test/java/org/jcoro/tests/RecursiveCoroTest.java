package org.jcoro.tests;

import junit.framework.Assert;
import org.jcoro.Async;
import org.jcoro.Await;
import org.jcoro.Coro;
import org.jcoro.ICoroRunnable;
import org.junit.Test;

/**
 * @author elwood
 */
public class RecursiveCoroTest {
    public static void main(String[] args) {
        new RecursiveCoroTest().test();
    }

    @Test
    public void test() {
        final int[] state = new int[1];
        final Coro coro = Coro.initSuspended(new ICoroRunnable() {
            private int i = 0;

            @Async({@Await("yield"), @Await("run")})
            public void run() {
                final int _i = i;
                if (i != 1) {
                    i = 1;
                    run();
                }
                final Coro _coro = Coro.get();
                _coro.yield();
                System.out.println(String.format("run(%d) ends", _i));
                state[0] = _i;
            }
        });
        coro.start();
        coro.resume();
        Assert.assertEquals(state[0], 1);
        coro.resume();
        Assert.assertEquals(state[0], 0);
        System.out.println("Finished");
    }
}
