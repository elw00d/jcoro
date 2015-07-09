package org.jcoro.nio;

import org.jcoro.Coro;
import org.jcoro.Instrument;
import org.jcoro.RestorePoint;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousByteChannel;
import java.nio.channels.CompletionHandler;

/**
 * @author elwood
 */
public class ByteChannel {
    @Instrument(@RestorePoint("yield"))
    public static Integer read(AsynchronousByteChannel channel, ByteBuffer dst) {
        final Coro coro = Coro.get();
        final Integer[] res = new Integer[1];
        final Throwable[] exc = new Throwable[1];
        coro.yield(() -> channel.read(dst, null, new CompletionHandler<Integer, Object>() {
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
        if (exc[0] != null) throw new RuntimeException(exc[0]);
        return res[0];
    }

    @Instrument(@RestorePoint("yield"))
    public static Integer write(AsynchronousByteChannel channel, ByteBuffer src) {
        final Coro coro = Coro.get();
        final Integer[] res = new Integer[1];
        final Throwable[] exc =  new Throwable[1];
        coro.yield(() -> channel.write(src, null, new CompletionHandler<Integer, Object>() {
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
        if (exc[0] != null) throw new RuntimeException(exc[0]);
        return res[0];
    }
}
