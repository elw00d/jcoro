package org.jcoro.nio;

import org.jcoro.Coro;
import org.jcoro.Instrument;
import org.jcoro.RestorePoint;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.TimeUnit;

/**
 * @author bedefaced
 */
public class SocketChannel {
    @Instrument(@RestorePoint("yield"))
    public static void connect(AsynchronousSocketChannel channel, SocketAddress remote) {
        Coro coro = Coro.get();
        final Throwable[] exc = new Throwable[1];
        coro.yield(() -> channel.connect(remote, null, new CompletionHandler<Void, Object>() {
            @Override
            public void completed(Void result, Object attachment) {
                coro.resume();
            }

            @Override
            public void failed(Throwable e, Object attachment) {
                exc[0] = e;
                coro.resume();
            }
        }));
        if (exc[0] != null) throw new RuntimeException(exc[0]);
    }

    @Instrument(@RestorePoint("yield"))
    public static Integer read(AsynchronousSocketChannel channel, ByteBuffer buffer) {
        Coro coro = Coro.get();
        final Integer[] res = new Integer[1];
        final Throwable[] exc = new Throwable[1];
        coro.yield(() -> channel.read(buffer, null, new CompletionHandler<Integer, Object>() {
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
    public static Integer read(AsynchronousSocketChannel channel, ByteBuffer buffer, long timeout, TimeUnit unit) {
        Coro coro = Coro.get();
        final Integer[] res = new Integer[1];
        final Throwable[] exc = new Throwable[1];
        coro.yield(() -> channel.read(buffer, timeout, unit, null, new CompletionHandler<Integer, Object>() {
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
    public static Long read(AsynchronousSocketChannel channel, ByteBuffer[] dsts, int offset,
                            int length, long timeout, TimeUnit unit) {
        Coro coro = Coro.get();
        final Long[] res = new Long[1];
        final Throwable[] exc = new Throwable[1];
        coro.yield(() -> channel.read(dsts, offset, length, timeout, unit, null, new CompletionHandler<Long, Object>() {
            @Override
            public void completed(Long result, Object attachment) {
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
    public static Integer write(AsynchronousSocketChannel channel, ByteBuffer buffer) {
        Coro coro = Coro.get();
        final Integer[] res = new Integer[1];
        final Throwable[] exc = new Throwable[1];
        coro.yield(() -> channel.write(buffer, null, new CompletionHandler<Integer, Object>() {
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
    public static Integer write(AsynchronousSocketChannel channel, ByteBuffer buffer, long timeout, TimeUnit unit) {
        Coro coro = Coro.get();
        final Integer[] res = new Integer[1];
        final Throwable[] exc = new Throwable[1];
        coro.yield(() -> channel.write(buffer, timeout, unit, null, new CompletionHandler<Integer, Object>() {
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
    public static Long write(AsynchronousSocketChannel channel, ByteBuffer[] dsts, int offset,
                            int length, long timeout, TimeUnit unit) {
        Coro coro = Coro.get();
        final Long[] res = new Long[1];
        final Throwable[] exc = new Throwable[1];
        coro.yield(() -> channel.write(dsts, offset, length, timeout, unit, null, new CompletionHandler<Long, Object>() {
            @Override
            public void completed(Long result, Object attachment) {
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
