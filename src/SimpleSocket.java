import java.io.*;
import java.util.*;
import java.net.*;

public class SimpleSocket {
    DatagramSocket sock;

    int window;
    int localPort;
    InetSocketAddress remoteHost;

    boolean isReceiver;
    boolean connected;
    DatagramPacket[] SendWindow;

    public SimpleSocket(int window, int localPort, int remotePort, boolean isReciever) throws SocketException {
        this.window = window;
        this.localPort = localPort;
        this.remoteHost = new InetSocketAddress(remotePort);
        this.isReceiver = isReciever;
        this.connected = false;
    }

    public Status Connect() {
        if (isReceiver) {
            try {
                this.sock = new DatagramSocket(localPort);
                sock.bind(remoteHost);
            } catch (SocketException e) {
                return Status.CONNECTION_FAILURE;
            }
            return Status.SUCCESS;
        }
        for (int attempts = 0; !connected && attempts < 5; attempts++) {
            try {
                this.sock = new DatagramSocket(localPort);
                sock.connect(remoteHost);

                connected = true;
                return Status.SUCCESS;
            } catch (Exception e) {
                try {
                    Thread.sleep(1000); // Retry every 1000ms
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                }
            }
        }
        return Status.CONNECTION_FAILURE;
    }

    public Status Send(byte[] data) {

        return Status.SUCCESS;
    }

    private Status SendPacket() {

        return Status.SUCCESS;

    }

}
