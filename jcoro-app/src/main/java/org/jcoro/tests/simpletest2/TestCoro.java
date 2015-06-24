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
                double f = 10;
                System.out.println("coro: func begin, i = " + i);
                //
                final String argStr = foo(i, f, "argStr");
                //
                System.out.println("coro: func end, i: " + i + ", str: " + argStr);
            }

            private String foo(int x, double y, String m) {
                assert x == 5;
//                assert y == 10;
//                assert m.equals("argStr");
                //
                Coro c = Coro.get();
                c.yield(); // ���� ����� ������� ��������� @RestorePoint-�������
                //
//                assert x == 5;
//                assert y == 10;
//                assert m.equals("argStr");
                return "returnedStr";
            }
        });
        System.out.println("Starting coro");
        coro.start();
        System.out.println("Coro yielded");
        coro.resume();
        System.out.println("Coro finished");
    }
}
