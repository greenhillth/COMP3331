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

    public static void main(String args[]) throws SocketException {

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

    public class STPPacket {
        STPFlag flg;
        int seq;
        byte[] payload;
        int size;

        public STPPacket(STPFlag flag, int seqno, byte[] data, int len) {
            this.flg = flag;
            this.seq = seqno;
            this.size = len + 4;
            this.payload = new byte[len];
            for (int i = 0; i < len; i++) {
                this.payload[i] = data[i];
            }
            return;
        }

        public STPPacket(STPFlag flag, int seqno) {
            this.flg = flag;
            this.seq = seqno;
            this.size = 4;
        }

        public STPPacket(byte[] in, int len) {
            this.size = len;
            int flagint = ((in[0] << 8) | in[1]);
            switch (flagint) {
                case 0:
                    this.flg = STPFlag.DATA;
                    break;
                case 1:
                    this.flg = STPFlag.ACK;
                    break;
                case 2:
                    this.flg = STPFlag.SYN;
                    break;
                case 3:
                    this.flg = STPFlag.FIN;
                    break;
            }
            this.seq = ((in[2] << 8) | in[3]);
            if (len > 4) {
                this.payload = new byte[len - 4];
                for (int i = 4; i < len; i++) {
                    this.payload[i - 4] = in[i];
                }
            }
        }

        public byte[] bytes() {
            byte sl = (byte) (seq & 0xFF);
            byte sh = (byte) ((seq >> 8) & 0xFF);
            byte[] header = { flg.hb, flg.lb, sh, sl };
            if (size == 4) {
                return header;
            }
            byte[] byteArr = new byte[size];
            ByteBuffer packet = ByteBuffer.wrap(byteArr);
            packet.put(header);
            packet.put(payload);
            return packet.array();
        }

        public void printPacket() {
            byte[] byteArray = this.bytes();

            System.out.println("Hexadecimal representation of packet:");
            for (byte b : byteArray) {
                String hex = String.format("%02X", b & 0xFF);
                System.out.print(hex + " ");
            }
            System.out.println();
        }

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
