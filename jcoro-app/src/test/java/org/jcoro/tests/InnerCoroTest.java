package org.jcoro.tests;

import org.jcoro.Coro;
import org.jcoro.ICoroRunnable;
import org.jcoro.Instrument;
import org.jcoro.RestorePoint;
import org.junit.Test;

/**
 * @author elwood
 */
public class InnerCoroTest {
    @Test
    public void test() {
        Coro outerCoro = Coro.initSuspended(new ICoroRunnable() {
            @Override
            @Instrument(@RestorePoint("yield"))
            public void run() {
                Coro _outerCoro = Coro.get();

                Coro innerCoro = Coro.initSuspended(new ICoroRunnable() {
                    @Override
                    @Instrument(@RestorePoint("yield"))
                    public void run() {
                        Coro _innerCoro = Coro.get();
                        int i = 5;
                        System.out.println("i = " + i);
                        _innerCoro.yield();
                        i = 10;
                        System.out.println("i = " + i);
                    }
                });
                System.out.println("Outer coro started");
                innerCoro.start();
                System.out.println("Yielding from outer coro..");

                _outerCoro.yield();

                System.out.println("Resuming inner coro..");
                innerCoro.resume();
                System.out.println("Returning from outer coro");
            }
        });
        outerCoro.start();
        System.out.println("Resuming outer coro");
        outerCoro.resume();
        System.out.println("All finished");
    }
}
