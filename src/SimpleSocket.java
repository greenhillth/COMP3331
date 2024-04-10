import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Predicate;

import java.util.Random;
import java.util.Comparator;
import java.util.Iterator;
import java.io.*;
import java.nio.*;
import java.net.*;

public class SimpleSocket {
    DatagramSocket sock;

    int window;
    int localPort;
    InetSocketAddress remoteAddress;

    int seq;
    int ack;

    boolean packetLoss;

    long startTime;

    boolean isReceiver;
    boolean connected;

    float rlp;
    float flp;
    int rto;
    Random rng;

    PriorityBlockingQueue<STPPacket> SendWindow;
    PriorityBlockingQueue<STPPacket> ReceiveWindow;

    ArrayBlockingQueue<String> logBuffer;

    STPState state;

    public static void main(String args[]) throws SocketException {

    }

    public SimpleSocket(int localPort, int window, boolean isReciever) throws SocketException {
        this.packetLoss = false; // TODO - Implement packet loss (disabled rn)
        this.state = STPState.CLOSED;
        this.window = window;
        this.localPort = localPort;
        this.isReceiver = isReciever;
        this.connected = false;
        this.remoteAddress = null;

        this.rlp = 0;
        this.flp = 0;
        this.rng = new Random();

        this.SendWindow = new PriorityBlockingQueue<STPPacket>(window, new STPPacket.PriorityComparator());
        this.ReceiveWindow = new PriorityBlockingQueue<STPPacket>(window, new STPPacket.PriorityComparator());
        this.logBuffer = new ArrayBlockingQueue<String>(window);

        this.sock = new DatagramSocket(localPort);
    }

    public void setTransmissionParams(float flp, float rlp, int rto) {
        this.flp = flp;
        this.rlp = rlp;
        this.rto = rto;
    }

    public void Connect(InetSocketAddress remoteHost) throws IOException {
        remoteAddress = remoteHost;
        this.startTime = System.currentTimeMillis();
        sock.connect(remoteHost);

        // Receiver logic
        if (isReceiver) {
            // await handshake
            state = STPState.LISTEN;
            processIncomingPackets();

            processReceiveQueue();

            processSendQueue();
        }

        // Sender logic
        else {
            startSeq(0, 0xFF - 1);
            STPPacket handshake = new STPPacket(STPFlag.SYN, getCurrSeq(4));
            addToSendBuffer(handshake);

            // manually invoke send
            processSendQueue();
            state = STPState.SYN_SENT;

            processIncomingPackets();

            processReceiveQueue();
        }

        state = STPState.EST;
        connected = true;
    }

    private int getCurrSeq(int size) {
        int diff = (seq + size) - 0xFF;
        seq = (diff > 0) ? diff : seq;
        return seq;
    }

    private boolean acknowledge(int seq) {
        // Remove element matching sequence
        return SendWindow.removeIf(p -> p.seq == seq);
    }

    private boolean addToSendBuffer(STPPacket p) {
        return SendWindow.offer(p);
    }

    private boolean addToReceiveBuffer(STPPacket p) {
        return ReceiveWindow.offer(p);
    }

    public void processSendQueue() {
        SendWindow.forEach(p -> {
            if (p.sendFlag) {
                DatagramPacket d = new DatagramPacket(p.bytes(), p.size, remoteAddress);
                if (!packetLoss(p)) {
                    try {
                        sock.send(d);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                addToLogBuffer(p, "snd");
            }
        });

    }

    private boolean packetLoss(STPPacket p) {
        if (isReceiver || (rlp == 0 && flp == 0)) {
            return false;
        }
        if (p.flg == STPFlag.ACK) {
            return rng.nextFloat() < rlp;
        } else {
            return rng.nextFloat() < flp;
        }

    }

    public void processIncomingPackets() throws IOException {
        byte[] buff = new byte[1004];
        DatagramPacket packet = new DatagramPacket(buff, buff.length);
        sock.receive(packet);

        STPPacket p = new STPPacket(packet.getData(), buff.length);

        if (addToReceiveBuffer(p)) {
            addToLogBuffer(p, "rcv");
        } else {
            addToLogBuffer(p, "drp");
        }
    }

    public void processReceiveQueue() {
        STPPacket head = ReceiveWindow.poll();
        if (head == null) {
            return;
        }

        switch (head.flg) {
            case DATA:
                // Add data to output and create ack
                STPPacket ack = new STPPacket(STPFlag.ACK, head.seq);
                while (!addToSendBuffer(ack))
                    ;
                break;
            case ACK:
                // Remove corresponding data segment from output queue
                acknowledge(head.seq);
                break;
            case SYN:
                STPPacket synack = new STPPacket(STPFlag.ACK, head.seq);
                while (!addToSendBuffer(synack))
                    ;
                break;
            case FIN:
                // Initiate close
                break;
            default:
                break;
        }
        // send diag info to log buffer

    }

    public void addToLogBuffer(STPPacket p, String type) {
        double t = (p.timeStamp - startTime) * 1e-3;
        String entry = String.format("%s   %.4f\t%s   %d   %d", type, t, p.flg.name(), p.seq, p.size - 4);
        System.out.println(entry);
        logBuffer.offer(entry);
    }

    public Status Send(byte[] data) {

        return Status.SUCCESS;
    }

    private int startSeq(int lower, int upper) {
        Random random = new Random();
        seq = random.nextInt(upper - lower + 1) + lower;
        return seq;
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
        int priority;

        long timeStamp;
        boolean sendFlag;

        // Parameterised Constructors
        public STPPacket(int priority, STPFlag flag, int seqno, byte[] data, int payloadSize) {
            this.priority = priority;
            this.timeStamp = 0;
            this.sendFlag = true;
            this.flg = flag;
            this.seq = seqno;
            this.size = payloadSize + 4;
            if (size > 4) {
                this.payload = new byte[payloadSize];
                for (int i = 0; i < payloadSize; i++) {
                    this.payload[i] = data[i];
                }
            }
        }

        public STPPacket(int priority, byte[] in, int len) {
            this.priority = priority;
            this.timeStamp = System.currentTimeMillis();
            this.sendFlag = true;
            this.size = len;
            int flagint = ((in[0] << 8) | in[1]);
            switch (flagint) {
                case 0:
                    this.flg = STPFlag.DATA;
                    break;
                case 1:
                    this.flg = STPFlag.ACK;
                    this.size = 4;
                    break;
                case 2:
                    this.flg = STPFlag.SYN;
                    this.size = 4;
                    break;
                case 3:
                    this.flg = STPFlag.FIN;
                    this.size = 4;
                    break;
            }

            this.seq = (Byte.toUnsignedInt(in[2]) << 8) | Byte.toUnsignedInt(in[3]);
            if (len > 4) {
                this.payload = new byte[len - 4];
                for (int i = 4; i < len; i++) {
                    this.payload[i - 4] = in[i];
                }
            }
        }

        // Overloaded Constructors
        public STPPacket(STPFlag flag, int seqno, byte[] data, int payloadSize) {
            this(0, flag, seqno, data, payloadSize);
        }

        public STPPacket(STPFlag flag, int seqno) {
            this(0, flag, seqno, null, 0);
        }

        public STPPacket(int priority, STPFlag flag, int seqno) {
            this(priority, flag, seqno, null, 0);
        }

        public STPPacket(byte[] in, int len) {
            this(0, in, len);
        }

        // Get bytes of initialised STPPacket
        public byte[] bytes() {
            timeStamp = System.currentTimeMillis();
            sendFlag = false;
            byte sl = (byte) (seq & 0xFF);
            byte sh = (byte) ((seq >>> 8) & 0xFF);
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

        // same as above but doesnt modify timeStamp
        public byte[] debuggingBytes() {
            byte sl = (byte) (seq & 0xFF);
            byte sh = (byte) ((seq >>> 8) & 0xFF);
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

        public void updateSendFlag(long currTime, int rto) {
            if (!sendFlag && (currTime - timeStamp > rto)) {
                sendFlag = true;
            }
        }

        public static class PriorityComparator implements Comparator<STPPacket> {
            @Override
            public int compare(STPPacket p1, STPPacket p2) {
                return Integer.compare(p1.priority, p2.priority);
            }
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
        this.hb = (byte) (val >>> 8 & 0xFF);
    }
}
