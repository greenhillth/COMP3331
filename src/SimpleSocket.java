import java.io.*;
import java.nio.*;
import java.util.*;
import java.net.*;

public class SimpleSocket {
    DatagramSocket sock;

    int window;
    int localPort;
    InetSocketAddress remoteAddress;

    boolean isReceiver;
    boolean connected;
    DatagramPacket[] SendWindow;

    STPState state;

    public static void main(String args[]) {

    }

    public SimpleSocket(int localPort, int window, boolean isReciever) throws SocketException {
        this.state = STPState.CLOSED;
        this.window = window;
        this.localPort = localPort;
        this.isReceiver = isReciever;
        this.connected = false;
        this.remoteAddress = null;

        this.sock = new DatagramSocket(localPort);
    }

    public Status Connect(InetSocketAddress remoteHost) {
        if (isReceiver) {
            // await handshake
            state = STPState.LISTEN;
            while (!connected) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                }
            }
            remoteAddress = remoteHost;
            return Status.SUCCESS;
        }

        // init handshake if sender
        initiateHandshake(remoteHost, 5);

        // for (int attempts = 0; !connected && attempts < 5; attempts++) {
        // initiateHandshake(remoteHost);

        // try {
        // Thread.sleep(1000); // Retry every 1000ms
        // } catch (InterruptedException ie) {
        // ie.printStackTrace();
        // }
        // }
        return Status.CONNECTION_FAILURE;
    }

    public Status Send(byte[] data) {

        return Status.SUCCESS;
    }

    private Status SendPacket() {

        return Status.SUCCESS;

    }

    private Status initiateHandshake(InetSocketAddress addr, int attempts) {
        for (int i = 0; i < attempts; i++) {
            DatagramPacket pkt = new DatagramPacket(null, i);
        }

        return Status.SUCCESS;
    }

    // STP Packet Constructors
    /*
     * Packet format:
     * +------+-------+------+
     * | type | seqno | data |
     * +------+-------+------+
     * | 2 | 2 | MSS |
     * +------+-------+------+
     *
     * 
     */

    private class STPPacket {
        char flg;
        char seq;
        byte[] bytes;
        int size;

        public STPPacket(STPFlag flag, int seqno, byte[] data, int len) {
            this.flg = flag.val;
            this.seq = (char) seqno;
            this.size = len + 4;

            ByteBuffer packet = ByteBuffer.allocate(len + 4);

            return;
        }

        public STPPacket(STPFlag flag, int seqno) {
            this.flg = flag.val;
            this.seq = (char) seqno;
            this.size = 4;
            // Flag high byte //Flag low byte
            byte[] barray = { (byte) (flg & 0xFF), (byte) (flg >> 8 & 0xFF),
                    (byte) (seq & 0xFF), (byte) (seq >> 8 & 0xFF) };
            // Seq high byte //Seq low byte
            this.bytes = barray;
        }

        // public static void main(String args[]) {

        // }
    }
}

enum STPState {
    CLOSED,
    SYN_SENT,
    LISTEN,
    EST,
    CLOSING,
    FIN_WAIT,
    TIME_WAIT;

}

enum STPFlag {
    DATA(0x00),
    ACK(0x01),
    SYN(0x02),
    FIN(0x03);

    public char val;
    public byte hb;
    public byte lb;

    private STPFlag(int flag) {
        this.val = (char) flag;
        this.lb = (byte) (val & 0xFF);
        this.hb = (byte) (val >> 8 & 0xFF);
    }
}
