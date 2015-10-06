package org.jcoro;

import org.jcoro.nio.SocketChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.jcoro.nio.ServerSocketChannel.accept;
import static org.jcoro.nio.SocketChannel.read;
import static org.jcoro.nio.SocketChannel.write;

/**
 * @author elwood
 */
public class SyncaServer {
    public static void main(String[] args) throws IOException, InterruptedException {
        Coro coro = Coro.initSuspended(new ICoroRunnable() {
            @Override
            @Async({@Await("accept")})
            public void run() {
                try {
                    ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

                    final AsynchronousServerSocketChannel listener =
                            AsynchronousServerSocketChannel.open().bind(new InetSocketAddress(8080));

                    while (true) {
                        AsynchronousSocketChannel channel = accept(listener);
                        executorService.submit(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Coro handleCoro = Coro.initSuspended(new ICoroRunnable() {
                                        @Override
                                        @Async({@Await("handle")})
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

    @Async({@Await("read"), @Await("write")})
    public static void handle(AsynchronousSocketChannel channel) {
        ByteBuffer buffer = ByteBuffer.allocate(10 * 1024);

        read(channel, buffer);
        ByteBuffer outBuffer = ByteBuffer.wrap(("HTTP/1.1 200 OK\n" +
                "Server: jcoro SyncaServer\n" +
                "Content-Language: ru\n" +
                "Content-Type: text/html; charset=utf-8\n" +
                "Content-Length: 0\n" +
                "Connection: close").getBytes(Charset.forName("utf-8")));
        write(channel, outBuffer);

        try {
            channel.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
