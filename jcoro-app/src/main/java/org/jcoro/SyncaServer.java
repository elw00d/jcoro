package org.jcoro;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author elwood
 */
public class SyncaServer {
    @Instrument({@RestorePoint("yield")})
    public static AsynchronousSocketChannel accept(AsynchronousServerSocketChannel listener) throws Throwable {
        Coro coro = Coro.get();
        final AsynchronousSocketChannel[] res = new AsynchronousSocketChannel[1];
        final Throwable[] exc = new Throwable[1];
        System.out.println("Starting async IO operation...");
        coro.yield(() -> {
            System.out.println("Starting Accept()...");
            listener.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
                @Override
                public void completed(AsynchronousSocketChannel result, Void attachment) {
                    res[0] = result;
                    System.out.println("Accept completed");
                    coro.resume();
                }

                @Override
                public void failed(Throwable e, Void attachment) {
                    exc[0] = e;
                    coro.resume();
                }
            });
        });
        System.out.println("Returning res[0]...");
        if (res[0] != null) return res[0];
        throw exc[0];
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Coro coro = Coro.initSuspended(new ICoroRunnable() {
            @Override
            @Instrument({@RestorePoint("accept")})
            public void run() {
                try {
                    final AsynchronousServerSocketChannel listener =
                            AsynchronousServerSocketChannel.open().bind(new InetSocketAddress(5000));

                    //ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

                    while (true) {
                        AsynchronousSocketChannel channel = accept(listener);

                        handle(channel);
                    }
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            }
        });
        coro.start();
        System.out.println("After start");
        Thread.sleep(60000);
    }

    public static void handle(AsynchronousSocketChannel channel) {
        ByteBuffer buffer = ByteBuffer.allocate(10 * 1024);

        channel.read(buffer, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer result, Void attachment) {
                ByteBuffer outBuffer = ByteBuffer.wrap("200 OK".getBytes(Charset.forName("utf-8")));
                channel.write(outBuffer, null, new CompletionHandler<Integer, Void>() {
                    @Override
                    public void completed(Integer result, Void attachment) {
                        System.out.println("Response sent");
                        try {
                            channel.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void failed(Throwable exc, Void attachment) {
                    }
                });
            }

            @Override
            public void failed(Throwable exc, Void attachment) {

            }
        });
    }
}
