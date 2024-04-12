import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Predicate;

import java.util.Random;
import java.util.Comparator;
import java.util.Iterator;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.net.*;

public class SimpleSocket {
    DatagramSocket sock;

    PipedInputStream in;
    PipedOutputStream out;

    PipedOutputStream inWriter;
    PipedInputStream outReader;

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
    long rto;
    Random rng;

    String logFormat;

    PriorityBlockingQueue<STPPacket> SendWindow;
    PriorityBlockingQueue<STPPacket> ReceiveWindow;

    ArrayBlockingQueue<String> logBuffer;

    STPState state;

    public static void main(String args[]) throws IOException {
    }

    public SimpleSocket(int localPort, int window, boolean isReciever) throws SocketException {
        this.packetLoss = false; // TODO - Implement packet loss (disabled rn)
        this.state = STPState.CLOSED;
        this.window = window;
        this.localPort = localPort;
        this.isReceiver = isReciever;
        this.connected = false;
        this.remoteAddress = null;

        this.rto = 1000;

        this.rlp = 0;
        this.flp = 0;
        this.rng = new Random();

        this.SendWindow = new PriorityBlockingQueue<STPPacket>(window, new STPPacket.PriorityComparator());
        this.ReceiveWindow = new PriorityBlockingQueue<STPPacket>(window, new STPPacket.PriorityComparator());
        this.logBuffer = new ArrayBlockingQueue<String>(window);

        this.out = new PipedOutputStream();
        this.in = new PipedInputStream();

        try {
            this.inWriter = new PipedOutputStream(in);
            this.outReader = new PipedInputStream(out);
        } catch (IOException e) {
            System.out.println("Piping failed");
        }

        this.sock = new DatagramSocket(localPort);
    }

    protected void finalize() throws Exception {
        this.out.close();
        this.in.close();
    }

    public InputStream getInputStream() {
        return this.in;
    }

    public OutputStream getOutputStream() {
        return this.out;
    }

    public void setLogFormat(String formatString) {
        logFormat = formatString;
    }

    public void setTransmissionParams(float flp, float rlp, int rto) {
        this.flp = flp;
        this.rlp = rlp;
        this.rto = rto;
    }

    public void Connect(InetSocketAddress remoteHost) throws IOException {
        remoteAddress = remoteHost;
        this.startTime = System.currentTimeMillis();
        // sock.connect(remoteHost);

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
            startSeq(0, 0xFFFF - 1);
            STPPacket handshake = new STPPacket(STPFlag.SYN, getCurrSeq(0));
            addToSendBuffer(handshake);

            // manually invoke send
            processSendQueue();
            state = STPState.SYN_SENT;

            processIncomingPackets();

            processReceiveQueue();
        }

        state = STPState.EST;
        connected = true;

        /*
         * while connected {
         * 
         * incoming thread
         * 
         * outgoing thread
         * 
         * maintenence thread
         * 
         * thread handler
         * 
         */
        new connectionManager().start();
        new incomingThread().start();
        new outgoingThread().start();
    }

    // control signals of other threads + states
    protected class threadManager extends Thread {
        public void run() {
        }
    }

    // perform logging and sending operations for connection
    protected class connectionManager extends Thread {
        public void run() {
            while (state == STPState.EST) {
                try {
                    processReceiveQueue();
                    // retransmissionCheck();
                    processSendQueue();
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // Handle incoming data
    protected class incomingThread extends Thread {
        public void run() {
            while (true) {
                try {
                    processIncomingPackets();
                } catch (IOException ie) {
                    System.out.println("idk");
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // Handle outgoing data
    protected class outgoingThread extends Thread {
        public void run() {
            while (true) {
                try {
                    processOutgoingPackets();
                } catch (IOException ie) {
                    System.out.println("idk");
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // Handle IO between user and socket
    protected class IOThread extends Thread {
        public void run() {

        }
    }

    public void processOutgoingPackets() throws IOException {
        if (outReader.available() > 0) {
            int len = 0;
            byte[] pload = new byte[1000];
            len = outReader.read(pload, 0, 1000);
            STPPacket packet = new STPPacket(STPFlag.DATA, getCurrSeq(len), pload, len);
            addToSendBuffer(packet);
        }
    }

    private int getCurrSeq(int size) {
        int diff = (seq + size) - 0xFFFF;
        seq = (diff > 0) ? diff : seq + size;
        return seq;
    }

    private boolean acknowledge(int seqno) {
        // Remove element matching sequence
        boolean balls = SendWindow.removeIf(p -> p.seq == seqno);
        return balls;
    }

    private void addToSendBuffer(STPPacket p) {
        printwins();
        SendWindow.put(p);
    }

    private boolean addToReceiveBuffer(STPPacket p) {
        printwins();
        return ReceiveWindow.offer(p);
    }

    // non-blocking
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
                if (p.flg == STPFlag.ACK) {
                    this.ack = p.seq;
                }
                addToLogBuffer(p, "snd");
            }
        });

        // remove ack as they don't get retransmitted
        SendWindow.removeIf(p -> p.flg == STPFlag.ACK);

    }
    /*
     * WHERE I'M AT:
     * Kinda moved the thread logic to this class, but for some reason - even after
     * the ACK'ed DATA packets are dropped.
     * they get retransmitted multiple times and appear in the sendwindow again.
     * Considering moving the sendWindow
     * culling function directly to the receivewindow idk
     * 
     */

    public void retransmissionCheck() {
        long cTime = System.currentTimeMillis();
        SendWindow.forEach(p -> {
            p.updateSendFlag(cTime, rto);
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

    // blocking
    public void processIncomingPackets() throws IOException {
        byte[] buff = new byte[1004];
        DatagramPacket packet = new DatagramPacket(buff, buff.length);
        sock.receive(packet);
        int test = packet.getLength();
        String contents = new String(packet.getData(), StandardCharsets.UTF_8);

        STPPacket p = new STPPacket(packet.getData(), -1);

        if (addToReceiveBuffer(p)) {
            addToLogBuffer(p, "rcv");
        } else {
            addToLogBuffer(p, "drp");
        }
    }

    public void processReceiveQueue() {
        if (!ReceiveWindow.isEmpty()) {
            printwins();
        }
        STPPacket head = ReceiveWindow.poll();
        if (head == null) {
            return;
        }

        switch (head.flg) {
            case DATA:
                if (head.payload == null) {
                    break;
                }
                // Add data to output and create ack
                STPPacket ack = new STPPacket(STPFlag.ACK, head.seq);
                addToSendBuffer(ack);
                try {
                    inWriter.write(head.payload);
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
                break;
            case ACK:
                // Remove corresponding data segment from output queue
                if (acknowledge(head.seq)) {
                    // System.out.println(String.format("removed packet with SEQ=%d", head.seq));
                }
                break;
            case SYN:
                STPPacket synack = new STPPacket(STPFlag.ACK, head.seq);
                addToSendBuffer(synack);
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
        String datastr = new String();
        if (p.flg == STPFlag.DATA && p.size > 4) {
            String pload = new String(p.payload, StandardCharsets.UTF_8);
            datastr = new String("         DATA:" + pload);
        }
        String entry = String.format(logFormat, type, t, p.flg.name(), p.seq,
                p.size - 4, datastr);
        // System.out.print(entry);
        logBuffer.offer(entry);
    }

    private void printwins() {
        if (SendWindow.isEmpty() && ReceiveWindow.isEmpty()) {
            return;
        }
        System.out.print("SendWindow:   [ ");
        for (STPPacket p : SendWindow) {
            System.out.print("<" + p.toString() + "> ");
        }
        System.out.print("] \nReceiveWindow:[ ");
        for (STPPacket p : ReceiveWindow) {
            System.out.print("<" + p.toString() + "> ");
        }
        System.out.print("]\n");

    }

    public Status Send(byte[] data, int length) {
        if (!(length > 0)) {
            return Status.INV_PACKT;
        }
        STPPacket packet = new STPPacket(STPFlag.DATA, getCurrSeq(length), data, length);
        addToSendBuffer(packet);
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
    public int getPacketSize(int seq) {
        int size = seq - ack;
        if (seq == ack) {
            return 4;
        }
        if (seq < ack) {
            size = (seq + 0xFFFF) - ack;
        }
        return (size > 1000) ? 1004 : size + 4;
    }

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
            this.seq = (Byte.toUnsignedInt(in[2]) << 8) | Byte.toUnsignedInt(in[3]);
            int flagint = (Byte.toUnsignedInt(in[0]) << 8) | Byte.toUnsignedInt(in[1]);
            switch (flagint) {
                case 0:
                    // use last ACK value to determine size
                    this.flg = STPFlag.DATA;
                    if (len == -1) {
                        this.size = getPacketSize(seq);
                        len = this.size;
                    }
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
                default:
                    System.out.println("balls");
                    break;
            }

            if (len > 4) {
                this.payload = new byte[len - 4];
                for (int i = 4; i < len; i++) {
                    this.payload[i - 4] = in[i];
                }
            }
        }

        // print packet for debugging
        @Override
        public String toString() {
            return flg.name() + " sflag=" + sendFlag + " seq=" + seq + " size=" + size;
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

        public void updateSendFlag(long currTime, long rto) {
            if (!sendFlag && ((currTime - timeStamp) > rto)) {
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
