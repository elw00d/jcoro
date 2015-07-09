package org.jcoro.nio;

import org.jcoro.Coro;
import org.jcoro.Instrument;
import org.jcoro.RestorePoint;

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.FileLock;

/**
 * @author elwood
 */
public class FileChannel {
    @Instrument(@RestorePoint("yield"))
    public static FileLock lock(AsynchronousFileChannel channel) {
        final Coro coro = Coro.get();
        final FileLock[] res = new FileLock[1];
        final Throwable[] exc = new Throwable[1];
        coro.yield(() -> channel.lock(null, new CompletionHandler<FileLock, Object>() {
            @Override
            public void completed(FileLock result, Object attachment) {
                res[0] = result;
                coro.resume();
            }

            @Override
            public void failed(Throwable e, Object attachment) {
                exc[0] = e;
                coro.resume();
            }
        }));
        if (null != exc[0]) throw new RuntimeException(exc[0]);
        return res[0];
    }

    @Instrument(@RestorePoint("yield"))
    public static FileLock lock(AsynchronousFileChannel channel,
                                long position,
                                long size,
                                boolean shared) {
        final Coro coro = Coro.get();
        final FileLock[] res = new FileLock[1];
        final Throwable[] exc = new Throwable[1];
        coro.yield(() -> channel.lock(position, size, shared, null, new CompletionHandler<FileLock, Object>() {
            @Override
            public void completed(FileLock result, Object attachment) {
                res[0] = result;
                coro.resume();
            }

            @Override
            public void failed(Throwable e, Object attachment) {
                exc[0] = e;
                coro.resume();
            }
        }));
        if (null != exc[0]) throw new RuntimeException(exc[0]);
        return res[0];
    }

    @Instrument(@RestorePoint("yield"))
    public static Integer read(AsynchronousFileChannel channel,
                               ByteBuffer dst,
                               long position) {
        final Coro coro = Coro.get();
        final Integer[] res = new Integer[1];
        final Throwable[] exc = new Throwable[1];
        coro.yield(() -> channel.read(dst, position, null, new CompletionHandler<Integer, Object>() {
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
        if (null != exc[0]) throw new RuntimeException(exc[0]);
        return res[0];
    }

    @Instrument(@RestorePoint("yield"))
    public static Integer write(AsynchronousFileChannel channel,
                                ByteBuffer src,
                                long position) {
        final Coro coro = Coro.get();
        final Integer[] res = new Integer[1];
        final Throwable[] exc = new Throwable[1];
        coro.yield(() -> channel.write(src, position, null, new CompletionHandler<Integer, Object>() {
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
        if (null != exc[0]) throw new RuntimeException(exc[0]);
        return res[0];
    }
}
