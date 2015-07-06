package org.jcoro.tests;

import junit.framework.Assert;
import org.jcoro.Coro;
import org.jcoro.ICoroRunnable;
import org.jcoro.Instrument;
import org.jcoro.RestorePoint;
import org.junit.Test;

/**
 * Test for one coro creates another coro inside, and both yield and resume in correct order.
 *
 * @author elwood
 */
public class InnerCoroTest {
    public static void main(String[] args) {
        new InnerCoroTest().test();
    }

    private void assertStepIs(int step, int[] state){
        Assert.assertEquals(step, state[0]);
        state[0]++;
    }

    @Test
    public void test() {
        final int[] state = new int[1];
        state[0] = 0;
        Coro outerCoro = Coro.initSuspended(new ICoroRunnable() {
            @Override
            @Instrument(@RestorePoint("yield"))
            public void run() {
                Coro _outerCoro = Coro.get();

                Coro innerCoro = Coro.initSuspended(new ICoroRunnable() {
                    @Override
                    @Instrument(@RestorePoint("yield"))
                    public void run() {
                        assertStepIs(2, state);
                        Coro _innerCoro = Coro.get();
                        int i = 5;
                        System.out.println("i = " + i);

                        _innerCoro.yield();

                        assertStepIs(6, state);
                        i = 10;
                        System.out.println("i = " + i);
                    }
                });
                assertStepIs(1, state);
                System.out.println("Outer coro started");

                innerCoro.start();

                assertStepIs(3, state);
                System.out.println("Yielding from outer coro..");

                _outerCoro.yield();

                assertStepIs(5, state);
                System.out.println("Resuming inner coro..");

                innerCoro.resume();

                assertStepIs(7, state);
                System.out.println("Returning from outer coro");
            }
        });
        assertStepIs(0, state);
        outerCoro.start();

        assertStepIs(4, state);
        System.out.println("Resuming outer coro");

        outerCoro.resume();

        assertStepIs(8, state);
        System.out.println("All finished");
    }
}
