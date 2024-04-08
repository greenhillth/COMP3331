import java.io.*;
import java.util.*;
import java.net.*;

public class SimpleSocket {
    DatagramSocket sock;

    int window;
    int remotePort;
    int localPort;

    DatagramPacket[] SendWindow;

    public SimpleSocket(int window, int localPort, int remotePort) throws SocketException {
        this.window = window;
        this.localPort = localPort;
        this.remotePort = remotePort;
    }

    public Status Connect() {

        return Status.SUCCESS;

    }

    public Status Send(byte[] data) {

        return Status.SUCCESS;
    }

    private Status SendPacket() {

        return Status.SUCCESS;

    }

}
