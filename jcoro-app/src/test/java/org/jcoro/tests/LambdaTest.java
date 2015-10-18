package org.jcoro.tests;

import org.junit.Assert;
import org.jcoro.Async;
import org.jcoro.Await;
import org.jcoro.Coro;
import org.jcoro.ICoroRunnable;
import org.junit.Test;

/**
 * @author elwood
 */
public class LambdaTest {
    public static void main(String[] args) {
        new LambdaTest().testRootLamda();
    }

    /**
     * Test checks that type annotations are used only from corresponding invokedynamic instruction,
     * and they dont affect all previous invokedynamics.
     */
    @Test
    public void testNotInstrument() {
        ICoroRunnable pleaseDontInstrumentIt = () -> {
            System.out.println("Just a little bit code");
        };
        Object nullObj = (@Async({@Await(value = "yield")}) ICoroRunnable) null;

        // If method is instrumented, direct call will fail with IllegalStateException, and test will fail too
        pleaseDontInstrumentIt.run();
    }

    @Test
    public void testRootLamda() {
        // We should annotate root ICoroRunnable lambda using this and only syntax
        // If we will use another interface (IMyCoroRunnable, for example), lambda will not be instrument as _root_ method
        // There are big difference between root lambda and non-root lambda because lambda is compiled to static method,
        // but called as instance method. So, to be compatible with usual instance methods, root lambda should put
        // one extra reference object (null) on coro stack before pausing. If another (not ICoroRunnable) interface will be used,
        // extra object will not be placed, and resume() will fail.
        int[] state = new int[1];
        state[0] = 0;
        Coro coro = Coro.initSuspended((@Async({@Await(value = "yield")}) ICoroRunnable) () -> {
            System.out.println("Started");
            state[0] = 1;
            Coro.get().yield();
            state[0] = 2;
            System.out.println("Continued");
        });
        coro.start();
        Assert.assertEquals(1, state[0]);
        System.out.println("Paused");
        coro.resume();
        Assert.assertEquals(2, state[0]);
        System.out.println("Finished");
    }

    private interface IMyCoroRunnable extends ICoroRunnable {
    }

    @Test(expected = AssertionError.class)
    public void testInvalidRootLambda() {
        // This syntax is incorrect for root lambda ! See `testRootLamda` test
        IMyCoroRunnable runnable = (@Async({@Await(value = "yield")}) IMyCoroRunnable) () -> {
            Coro.get().yield();
        };
        Coro coro = Coro.initSuspended(runnable);
        coro.start();
        coro.resume(); // Will fail with AssertionError
    }

    @Test
    public void testNonRootLambda() {
        int[] state = new int[1];
        state[0] = 0;
        Coro coro = Coro.initSuspended((@Async({@Await(value = "run", patchable = false)}) ICoroRunnable) () -> {
            System.out.println("Started");

            Runnable someFunc = (@Async({@Await("yield")}) Runnable) () -> {
                state[0] = 1;
                System.out.println("SomeFunc begin");
                Coro.get().yield();
                state[0] = 2;
                System.out.println("SomeFunc end");
            };

            someFunc.run();
            System.out.println("Continued");
        });
        coro.start();
        Assert.assertEquals(1, state[0]);
        System.out.println("Paused");
        coro.resume();
        Assert.assertEquals(2, state[0]);
        System.out.println("Finished");
    }
}
