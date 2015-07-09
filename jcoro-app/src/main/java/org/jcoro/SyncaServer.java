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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author elwood
 */
public class SyncaServer {
    static AtomicInteger openChannels = new AtomicInteger();

    @Instrument({@RestorePoint("yield")})
    public static AsynchronousSocketChannel accept(AsynchronousServerSocketChannel listener) {
        Coro coro = Coro.get();
        final AsynchronousSocketChannel[] res = new AsynchronousSocketChannel[1];
        final Throwable[] exc = new Throwable[1];
        coro.yield(() -> {
            listener.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
                @Override
                public void completed(AsynchronousSocketChannel result, Void attachment) {
                    res[0] = result;
                    coro.resume();
                }

                @Override
                public void failed(Throwable e, Void attachment) {
                    exc[0] = e;
                    coro.resume();
                }
            });
        });
        if (exc[0] != null)
            throw new RuntimeException(exc[0]);
        return res[0];
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Coro coro = Coro.initSuspended(new ICoroRunnable() {
            @Override
            @Instrument({@RestorePoint("accept")})
            public void run() {
                try {
                    ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

                    final AsynchronousServerSocketChannel listener =
                            AsynchronousServerSocketChannel.open().bind(new InetSocketAddress(5000));

                    while (true) {
                        AsynchronousSocketChannel channel = accept(listener);
                        executorService.submit(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Coro handleCoro = Coro.initSuspended(new ICoroRunnable() {
                                        @Override
                                        @Instrument({@RestorePoint("handle")})
                                        public void run() {
                                            try {
                                                handle(channel);
                                            } catch (Throwable e) {
                                                e.printStackTrace();
                                            }
                                        }
                                    });
                                    handleCoro.start();
                                } catch (Throwable e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        coro.start();
        System.out.println("After start");
        Thread.sleep(60000);
    }

    @Instrument(@RestorePoint("yield"))
    public static Integer read(AsynchronousSocketChannel channel,
                               ByteBuffer buffer) {
        Coro coro = Coro.get();
        final Integer[] res = new Integer[1];
        final Throwable[] exc = new Throwable[1];
        coro.setDeferFunc(() -> {
            channel.read(buffer, 5, TimeUnit.SECONDS, null, new CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer result, Void attachment) {
                    res[0] = result;
                    coro.resume();
                }

                @Override
                public void failed(Throwable e, Void attachment) {
                    exc[0] = e;
                    coro.resume();
                }
            });
        });
        coro.yield();
        if (exc[0] != null) throw new RuntimeException(exc[0]);
        return res[0];
    }

    @Instrument(@RestorePoint("yield"))
    private static Integer write(AsynchronousSocketChannel channel,
                                 ByteBuffer buffer) {
        Coro coro = Coro.get();
        final Integer[] res = new Integer[1];
        final Throwable[] exc = new Throwable[1];
        coro.setDeferFunc(() -> {
            channel.write(buffer, 5, TimeUnit.SECONDS, null, new CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer result, Void attachment) {
                    res[0] = result;
                    coro.resume();
                }

                @Override
                public void failed(Throwable e, Void attachment) {
                    exc[0] = e;
                    coro.resume();
                }
            });
        });
        coro.yield();
        if (exc[0] != null) throw new RuntimeException(exc[0]);
        return res[0];
    }

    static ByteBuffer outBuffer = ByteBuffer.wrap("200 OK".getBytes(Charset.forName("utf-8")));

    @Instrument({@RestorePoint("read"), @RestorePoint("write")})
    public static void handle(AsynchronousSocketChannel channel) {
//        System.out.println("Starting handling " + channel);
        ByteBuffer buffer = ByteBuffer.allocate(10 * 1024);

        Integer read = read(channel, buffer);
//        System.out.println(String.format("Readed %d bytes", read));
        write(channel, outBuffer);
//        System.out.println("Written to " + channel);

        try {
//            System.out.println("Closing " + channel);
            channel.close();
            int nOpen = openChannels.decrementAndGet();
//            System.out.println("Open channels: " + nOpen);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
