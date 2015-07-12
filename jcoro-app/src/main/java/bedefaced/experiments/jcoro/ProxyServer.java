package bedefaced.experiments.jcoro;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;

import org.jcoro.Coro;
import org.jcoro.ICoroRunnable;
import org.jcoro.Instrument;
import org.jcoro.RestorePoint;
import org.jcoro.nio.ServerSocketChannel;
import org.jcoro.nio.SocketChannel;

public class ProxyServer implements Runnable {

    private InetAddress hostAddress;
    private int portlocal;
    private InetAddress remoteAddress;
    private int portremote;
    private AsynchronousServerSocketChannel serverChannel;

    public ProxyServer(InetAddress hostAddress, int portlocal,
                       String remotehost, int portremote) throws UnknownHostException {
        this.hostAddress = hostAddress;
        this.portlocal = portlocal;

        this.portremote = portremote;
        this.remoteAddress = InetAddress.getByName(remotehost);
    }

    public static void main(String[] args) throws NumberFormatException,
            UnknownHostException {
        if (args.length != 3) {
            System.err.println("usage: <port> <host> <port>");
            return;
        }
        new Thread(new ProxyServer(null, Integer.valueOf(args[0]), args[1],
                Integer.valueOf(args[2]))).start();
        try {
            Thread.sleep(999999999);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        try {
            serverChannel = AsynchronousServerSocketChannel.open();

            InetSocketAddress isa = new InetSocketAddress(this.hostAddress,
                    this.portlocal);
            serverChannel.bind(isa);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        ByteBuffer readClientBuffer = ByteBuffer.allocate(32);
        ByteBuffer readRemoteBuffer = ByteBuffer.allocate(32);

        Coro acceptClientCoro = Coro.initSuspended(new ICoroRunnable() {
            @Override
            @Instrument({@RestorePoint("accept"), @RestorePoint("connect")})
            public void run() {
                while (true) {
                    AsynchronousSocketChannel client = ServerSocketChannel
                            .accept(serverChannel);

                    AsynchronousSocketChannel remote = null;
                    SocketChannel.connect(remote, new InetSocketAddress(
                            remoteAddress, portremote));

                    Coro readClientCoro = Coro
                            .initSuspended(new ICoroRunnable() {

                                @Override
                                @Instrument({@RestorePoint("read"), @RestorePoint("write")})
                                public void run() {
                                    while (true) {
                                        SocketChannel.read(client,
                                                readClientBuffer);
                                        readClientBuffer.flip();
                                        SocketChannel.write(remote,
                                                readClientBuffer);
                                    }
                                }

                            });

                    Coro readRemoteCoro = Coro
                            .initSuspended(new ICoroRunnable() {

                                @Override
                                @Instrument({@RestorePoint("read"), @RestorePoint("write")})
                                public void run() {
                                    while (true) {
                                        SocketChannel.read(remote,
                                                readRemoteBuffer);
                                        readRemoteBuffer.flip();
                                        SocketChannel.write(client,
                                                readRemoteBuffer);
                                    }
                                }

                            });

                    readClientCoro.start();
                    readRemoteCoro.start();

                }
            }
        });

        acceptClientCoro.start();
    }

}
