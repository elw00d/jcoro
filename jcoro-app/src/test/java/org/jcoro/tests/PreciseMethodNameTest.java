package org.jcoro.tests;

import org.jcoro.Async;
import org.jcoro.Await;
import org.jcoro.Coro;
import org.jcoro.ICoroRunnable;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Stack;

/**
 * @author elwood
 */
public class PreciseMethodNameTest {
    public static void main(String[] args) {
        new PreciseMethodNameTest().test();
    }

    @Test
    public void test() {
        Coro coro = Coro.initSuspended(new ICoroRunnable() {
            @Async({@Await(value = "bar", desc = "", owner = "org/jcoro/tests/PreciseMethodNameTest$Moo")})
            public void run() {
                Foo foo = new Foo();
                foo.bar();
                Moo moo = new Moo();
                moo.bar();
            }
        });
        coro.start();

        // If foo.bar() will be instrumented, state after yielded moo.bar() will be 1
        // So we should check that state is equal to 0
        final Field statesStackField;
        final Stack statesStack;
        try {
            statesStackField = Coro.class.getDeclaredField("statesStack");
            statesStackField.setAccessible(true);
            statesStack = (Stack) statesStackField.get(coro);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        Assert.assertTrue((Integer) statesStack.peek() == 0);
        coro.resume();
    }

    private static class Foo {
        public void bar() {
            System.out.println("Foo::bar()");
        }
    }

    private static class Moo {
        @Async({@Await("yield")})
        public void bar() {
            System.out.println("Moo::bar() begin");
            Coro.get().yield();
            System.out.println("Moo::bar() end");
        }
    }
}
