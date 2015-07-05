package org.jcoro;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
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
//        System.out.println("Starting async IO operation...");
        coro.yield(() -> {
//            System.out.println("Starting Accept()...");
            listener.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
                @Override
                public void completed(AsynchronousSocketChannel result, Void attachment) {
                    try {
                        res[0] = result;
                        coro.resume();
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void failed(Throwable e, Void attachment) {
                    exc[0] = e;
                    coro.resume();
                }
            });
        });
//        System.out.println("Returning res[0]...");
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

//                    AsynchronousChannelGroup channelGroup = AsynchronousChannelGroup.withThreadPool(
//                            executorService
//                    );
//                    AsynchronousServerSocketChannel listener = channelGroup.provider()
//                            .openAsynchronousServerSocketChannel(channelGroup);
//                    listener.bind(new InetSocketAddress(5000));

                    final AsynchronousServerSocketChannel listener =
                            AsynchronousServerSocketChannel.open().bind(new InetSocketAddress(5000));

                    while (true) {
                        AsynchronousSocketChannel channel = accept(listener);
                        System.out.println("Accepted " + channel.toString());
                        if (openChannels.incrementAndGet() == 2) {
                            System.out.println("Accepted 2 !");
                        }
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
//                        handle(channel);
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
        coro.yield(() -> {
            channel.read(buffer, 5, TimeUnit.SECONDS, null, new CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer result, Void attachment) {
                    try {
                        res[0] = result;
                        coro.resume();
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void failed(Throwable e, Void attachment) {
                    try {
                        exc[0] = e;
                        coro.resume();
                    } catch(Throwable ex) {
                        ex.printStackTrace();
                    }
                }
            });
        });
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
                    try {
                        res[0] = result;
                        coro.resume();
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
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
        System.out.println("Starting handling " + channel);
        ByteBuffer buffer = ByteBuffer.allocate(10 * 1024);

        read(channel, buffer);
//        for (int i = 0; i < 10;i++){
//            int x = i * 3;
//        }
//        System.out.println("Readed from " + channel);
        //System.out.print("d");
//        System.out.println(String.format("Readed %d bytes", read));
        write(channel, outBuffer);
//        System.out.println("Written to " + channel);

        try {
            System.out.println("Closing " + channel);
            int nOpen = openChannels.decrementAndGet();
            System.out.println("Open channels: " + nOpen);
            channel.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

//        ByteBuffer outBuffer = ByteBuffer.wrap("200 OK".getBytes(Charset.forName("utf-8")));
//        channel.write(outBuffer, 5, TimeUnit.SECONDS, null, new CompletionHandler<Integer, Void>() {
//            @Override
//            public void completed(Integer result, Void attachment) {
//                    try {
//                        channel.close();
//                    } catch (IOException e) {
//                        e.printStackTrace();
//                    }
//            }
//
//            @Override
//            public void failed(Throwable e, Void attachment) {
//            }
//        });
    }
}
