package org.jcoro.nio;

import org.jcoro.Await;
import org.jcoro.Coro;
import org.jcoro.Async;

import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;

/**
 * @author bedefaced
 */
public class ServerSocketChannel {
    @Async(@Await("yield"))
    public static AsynchronousSocketChannel accept(AsynchronousServerSocketChannel channel) {
        Coro coro = Coro.get();
        final AsynchronousSocketChannel[] res = new AsynchronousSocketChannel[1];
        final Throwable[] exc = new Throwable[1];
        coro.yield(() -> channel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Object>() {
            @Override
            public void completed(AsynchronousSocketChannel result, Object attachment) {
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
