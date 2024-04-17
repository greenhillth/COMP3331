import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Predicate;
import java.util.logging.Logger;
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
    int rto;
    Random rng;

    String logFormat;

    LinkedBlockingQueue<STPPacket> SendBuffer;
    LinkedBlockingQueue<STPPacket> ReceiveBuffer;

    ArrayBlockingQueue<STPPacket> SlidingWindow;

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

        this.rlp = 0;
        this.flp = 0;
        this.rng = new Random();

        this.SendBuffer = new LinkedBlockingQueue<STPPacket>(window);// , new STPPacket.PriorityComparator());
        this.ReceiveBuffer = new LinkedBlockingQueue<STPPacket>(window);// , new STPPacket.PriorityComparator());
        this.SlidingWindow = new ArrayBlockingQueue<STPPacket>(window);
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
        new WindowThread().start();
        new retransmissionThread().start();
    }

    // control signals of other threads + states
    protected class threadManager extends Thread {
        public void run() {
        }
    }

    // perform logging and sending operations for connection
    protected class connectionManager extends Thread {
        public connectionManager() {
            this.setName("Send/Receive Thread");
        }

        public void run() {
            while (state != STPState.CLOSED) {
                try {
                    processReceiveQueue();
                    processSendQueue();

                    // retransmissionCheck();
                    if (!isReceiver) {
                        Thread.sleep(300);

                    }
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // perform logging and sending operations for connection
    protected class retransmissionThread extends Thread {
        public retransmissionThread() {
            this.setName("Retransmission Thread");
        }

        public void run() {
            while (state != STPState.CLOSED) {
                long sleepms = rto;
                try {
                    STPPacket p = SlidingWindow.peek();
                    if (p != null && p.outgoing) {
                        // store sequence no
                        int headseq = p.seq;
                        // set sleep time
                        sleepms = System.currentTimeMillis() - p.timeStamp + rto;
                        // sleep until RTO reached
                        Thread.sleep(sleepms);
                        if (SlidingWindow.peek() == p) {
                            p.timeStamp = System.currentTimeMillis();
                            SlidingWindow.offer(p);
                            sendPacket(p);

                        }
                    } else {
                        Thread.sleep(rto);
                    }

                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // Handle incoming data
    protected class incomingThread extends Thread {
        public incomingThread() {
            this.setName("Incoming Thread");
        }

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
        public outgoingThread() {
            this.setName("Outgoing Thread");
        }

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

    // Process sliding window functions
    protected class WindowThread extends Thread {
        public WindowThread() {
            this.setName("Sliding Window Processing");
        }

        public void run() {
            while (true) {
                try {
                    processSlidingWindow();
                } catch (Exception e) {
                    Thread.currentThread().interrupt();

                }
            }
        }
    }

    // Pack data from socket inputstream to send
    public void processOutgoingPackets() throws IOException {

        byte[] pload = new byte[512];
        int len;

        try {
            len = outReader.read(pload);
            if (len > 0) {
                STPPacket packet = new STPPacket(STPFlag.DATA, getCurrSeq(len), pload, len);
                addToSendBuffer(packet);
            }

        } catch (IOException ie) {
            try {
                // Thread.sleep(100);
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
        }

    }

    private int getCurrSeq(int size) {
        int diff = (seq + size) - 0xFFFF;
        seq = (diff > 0) ? diff : seq + size;
        return seq;
    }

    private boolean acknowledge(int seqno) {
        // Remove element matching sequence
        boolean balls = SlidingWindow.removeIf(p -> p.seq == seqno);

        /*
         * 
         * STPPacket head = SlidingWindow.peek();
         * boolean balls = false;
         * if ((head != null) && (head.seq == seqno)) {
         * SlidingWindow.poll();
         * balls = true;
         * }
         */

        if (balls) {
            System.out.println("Removed packet with seq " + seqno + " from sliding window");
        } else {
            System.out.println("Could not remove packet with seq " + seqno + " (Packet not found)");

        }
        return balls;
    }

    private void addToSendBuffer(STPPacket p) {
        // printwins();
        try {
            SendBuffer.put(p);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        // System.out.println("Added packet " + p.toString() + " to send buffer");
    }

    private String getSlidingWin() {
        String window = new String(" Current window state: [");
        for (STPPacket p : SlidingWindow) {
            window = window + " <" + p.toString() + "> ";
        }
        return window + "]";
    }

    private boolean addToReceiveBuffer(STPPacket p) {
        // printwins();
        return ReceiveBuffer.offer(p);
    }

    // blocking
    public void processSendQueue() {
        if (SendBuffer.isEmpty()) {
            return;
        }

        STPPacket p = SendBuffer.poll();

        // add to sliding window to await confirmation
        if (p.flg == STPFlag.DATA) {
            try {
                SlidingWindow.put(p);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (isReceiver) {
                SlidingWindow.offer(p);

            }
        }
        sendPacket(p);

    }

    private void sendPacket(STPPacket p) {
        DatagramPacket d = new DatagramPacket(p.bytes(), p.size, remoteAddress);
        if (!packetLoss(p)) {
            try {
                sock.send(d);
                System.out.println("Sending packet " + p.toString());
                // Thread.sleep(100);
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
            addToLogBuffer(p, "snd", "successful send");
        } else {
            addToLogBuffer(p, "snd", "simulated packet loss");
        }
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

        STPPacket p = new STPPacket(packet.getData(), packet.getLength());

        addToReceiveBuffer(p);

    }

    // process packets in sliding window
    public void processSlidingWindow() {

        SlidingWindow.forEach(p -> {
            if (!p.outgoing && p.flg == STPFlag.DATA) {
                if (inSequence(p)) {
                    writeToOutput(p);
                    p.cullFlag = true;
                }
            }
        });

        int b4 = SlidingWindow.size();
        // clean up sliding window
        SlidingWindow.removeIf(p -> (p.cullFlag));
        int after = SlidingWindow.size();
        if (b4 > after && isReceiver) {
            System.out.printf("Removed %d elements from SlidingWindow\n", b4 - after);
        }
    }

    private boolean retransmissionCheck(STPPacket p) {
        if (p.outgoing && ((p.timeStamp + rto) < System.currentTimeMillis())) {
            return true;
        }
        return false;
    }

    private void writeToOutput(STPPacket p) {
        try {
            inWriter.write(p.payload);
            p.cullFlag = true;
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean inSequence(STPPacket p) {
        int expected = this.ack + p.size - 4;
        expected = (expected > 0xFFFF) ? expected - 0xFFFF : expected;

        boolean result = (p.seq == expected);
        if (result) {
            this.ack = p.seq;
        }
        return result;
    }

    public void processReceiveQueue() {
        if (!ReceiveBuffer.isEmpty()) {
            // printwins();
        }

        STPPacket p = ReceiveBuffer.poll();
        if (p == null) {
            return;
        }

        switch (p.flg) {
            case DATA:
                if (p.payload == null) {
                    break;
                }

                // Attempt to write to output stream
                if (inSequence(p)) {
                    writeToOutput(p);
                    STPPacket ack = new STPPacket(STPFlag.ACK, p.seq);
                    sendPacket(ack);
                } else if (SlidingWindow.remainingCapacity() > 1) {
                    int cap = SlidingWindow.remainingCapacity();
                    if (!SlidingWindow.contains(p)) {
                        SlidingWindow.add(p);
                    } else {
                        addToLogBuffer(p, "inf", "Duplicate data recieved, not added");
                    }
                    addToLogBuffer(p, "rcv", "added to window, " + cap + " spots left, " + getSlidingWin());
                    STPPacket ack = new STPPacket(STPFlag.ACK, p.seq);
                    sendPacket(ack);
                } else {
                    addToLogBuffer(p, "drp", "Window full, dropped, " + getSlidingWin());
                }

                break;
            case ACK:
                // Remove corresponding data segment from sliding window
                addToLogBuffer(p, "rcv");
                if (acknowledge(p.seq)) {
                    System.out.println(String.format("removed packet with SEQ=%d", p.seq));
                }
                break;
            case SYN:
                addToLogBuffer(p, "rcv");
                // set ISN
                this.ack = p.seq;
                STPPacket synack = new STPPacket(STPFlag.ACK, p.seq);
                addToSendBuffer(synack);
                break;
            case FIN:
                addToLogBuffer(p, "rcv");
                state = STPState.FIN_WAIT;
                break;
            default:
                break;
        }

        return;

    }

    // send diag info to log buffer

    public void addToLogBuffer(STPPacket p, String type, String option) {
        double t = (p.timeStamp - startTime) * 1e-3;
        String entry = String.format(logFormat, type, t, p.flg.name(), p.seq,
                p.size - 4, option);
        logBuffer.offer(entry);
    }

    public void addToLogBuffer(STPPacket p, String type) {
        addToLogBuffer(p, type, "");
    }

    private void printwins() {
        if (SendBuffer.isEmpty() && ReceiveBuffer.isEmpty()) {
            return;
        }
        System.out.print("SendWindow:   [ ");
        for (STPPacket p : SendBuffer) {
            System.out.print("<" + p.toString() + "> ");
        }
        System.out.print("] \nReceiveWindow:[ ");
        for (STPPacket p : ReceiveBuffer) {
            System.out.print("<" + p.toString() + "> ");
        }
        System.out.print("]\n");

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
        boolean outgoing;
        boolean cullFlag;

        // Parameterised Constructors
        public STPPacket(STPFlag flag, int seqno, byte[] data, int payloadSize) {
            this.timeStamp = System.currentTimeMillis();
            this.outgoing = true;
            this.cullFlag = false;
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

        public STPPacket(byte[] in, int len) {
            this.timeStamp = System.currentTimeMillis();
            this.outgoing = false;
            this.cullFlag = false;
            this.size = len;
            this.seq = (Byte.toUnsignedInt(in[2]) << 8) | Byte.toUnsignedInt(in[3]);
            int flagint = (Byte.toUnsignedInt(in[0]) << 8) | Byte.toUnsignedInt(in[1]);
            switch (flagint) {
                case 0:
                    // use last ACK value to determine size
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
            return flg.name() + " cFlag=" + cullFlag + " seq=" + seq + " size=" + size;
        }

        // Overloaded equality for comparisons
        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof STPPacket)) {
                return false;
            }
            STPPacket other = (STPPacket) obj;
            return this.seq == other.seq && this.flg == other.flg;
        }

        // Overloaded Constructor
        public STPPacket(STPFlag flag, int seqno) {
            this(flag, seqno, null, 0);
        }

        // Get bytes of initialised STPPacket
        public byte[] bytes() {
            timeStamp = System.currentTimeMillis();
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
