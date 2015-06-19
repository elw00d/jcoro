package org.jcoro.tests.simpletest2;

import org.jcoro.Coro;
import org.jcoro.ICoroRunnable;
import org.jcoro.Instrument;

/**
 * @author elwood
 */
public class TestCoro {
    public static void main(String[] args) {
        Coro coro = Coro.initSuspended(new ICoroRunnable() {
            @Override
            @Instrument
            public void run() {
                int i = 5;
                System.out.println("coro: func begin, i = " + i);
                Coro c = Coro.get();
                c.yield(); // этот вызов априори считается @RestorePoint-вызовом
                System.out.println("coro: func end, i = " + i);
            }
        });
        System.out.println("Starting coro");
        coro.start();
        System.out.println("Coro yielded");
        coro.resume();
        System.out.println("Coro finished");
    }
}
