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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author elwood
 */
public class AsyncServer {
    static AtomicInteger openChannels = new AtomicInteger();

    public static void main(String[] args) throws IOException, InterruptedException {
        final AsynchronousServerSocketChannel listener =
                AsynchronousServerSocketChannel.open().bind(new InetSocketAddress(5000));

        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        listener.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
            @Override
            public void completed(AsynchronousSocketChannel channel, Void attachment) {
                listener.accept(null, this);
//                openChannels.incrementAndGet();

                executorService.submit(new Runnable() {
                    @Override
                    public void run() {
                        handle(channel);
                    }
                });
                //handle(channel);
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                //
            }
        });

        Thread.sleep(60000);
    }

    private static void handle(AsynchronousSocketChannel channel) {
        ByteBuffer buffer = ByteBuffer.allocate(10 * 1024);

        channel.read(buffer, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer result, Void attachment) {
                ByteBuffer outBuffer = ByteBuffer.wrap("200 OK".getBytes(Charset.forName("utf-8")));
                channel.write(outBuffer, null, new CompletionHandler<Integer, Void>() {
                    @Override
                    public void completed(Integer result, Void attachment) {
                        //System.out.println("Response sent");
                        try {
                            channel.close();
//                            int nOpen = openChannels.decrementAndGet();
//                            System.out.println("Open channels: " + nOpen);
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
