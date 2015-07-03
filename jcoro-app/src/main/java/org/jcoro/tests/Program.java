package org.jcoro.tests;

import org.jcoro.Coro;
import org.jcoro.ICoroRunnable;
import org.jcoro.Instrument;
import org.jcoro.RestorePoint;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;

/**
 * @author elwood
 */
public class Program {
    public static void main(String[] args) {
        Coro outerCoro = Coro.initSuspended(() -> {
            Coro _outerCoro = Coro.get();

            Coro innerCoro = Coro.initSuspended((@Instrument(@RestorePoint("yield")) ICoroRunnable) () -> {
                Coro _innerCoro = Coro.get();
                int i = 5;
                System.out.println("i = " + i);
                _innerCoro.yield();
                i = 10;
                System.out.println("i = " + i);
            });
            System.out.println("Outer coro started");
            innerCoro.start();
            System.out.println("Yielding from outer coro..");

            _outerCoro.yield();

            System.out.println("Resuming inner coro..");
            innerCoro.resume();
            System.out.println("Returning from outer coro");
        });
        outerCoro.start();
        System.out.println("Resuming outer coro");
        outerCoro.resume();
        System.out.println("All finished");
    }

    private static Integer readFileSynca(AsynchronousFileChannel channel,
                                         ByteBuffer buffer,
                                         long pos) throws Throwable {
        Coro coro = Coro.get();
        final Integer[] res = new Integer[1];
        final Throwable[] exc = new Throwable[1];
        coro.yield(() -> channel.read(buffer, pos, null, new CompletionHandler<Integer, Object>() {
            @Override
            public void completed(Integer result, Object attachment) {
                res[0] = result;
                coro.resume();
            }

            @Override
            public void failed(Throwable e, Object attachment) {
                exc[0] = e;
                coro.resume();
            }
        }));
        if (res[0] != null) return res[0];
        throw exc[0];
    }
}
