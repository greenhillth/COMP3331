import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.Random;
import java.io.*;
import java.nio.*;
import java.net.*;

public class SimpleSocket {
    DatagramSocket sock;

    private final Lock lock = new ReentrantLock();

    PipedInputStream in;
    PipedOutputStream out;
    PipedOutputStream inWriter;
    PipedInputStream outReader;

    int window;
    int localPort;
    InetSocketAddress remoteAddress;

    int seq;
    int ack;

    long startTime;
    long mslStart;

    boolean isReceiver;
    int dupAck;
    int prevAck;

    float rlp;
    float flp;
    int rto;
    Random rng;

    LinkedBlockingQueue<STPPacket> SendBuffer;
    LinkedBlockingQueue<STPPacket> ReceiveBuffer;

    ArrayBlockingQueue<STPPacket> SlidingWindow;

    String logFormat;
    LinkedBlockingQueue<String> logBuffer;

    int[] stats;
    int rollover;

    STPState state;
    boolean[] shutdown = { false, false, false, false };

    public SimpleSocket(int localPort, int window, boolean isReciever) throws SocketException {
        this.state = STPState.CLOSED;
        this.window = window;
        this.localPort = localPort;
        this.isReceiver = isReciever;
        this.dupAck = 0;
        this.remoteAddress = null;
        this.stats = new int[7];

        this.rlp = 0;
        this.flp = 0;
        this.rng = new Random();

        this.SendBuffer = new LinkedBlockingQueue<STPPacket>();
        this.ReceiveBuffer = new LinkedBlockingQueue<STPPacket>();

        this.SlidingWindow = new ArrayBlockingQueue<STPPacket>((int) window / 1000);
        this.logBuffer = new LinkedBlockingQueue<String>();

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

    public boolean connected() {
        return (this.state == STPState.EST);
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

        // Receiver logic
        if (isReceiver) {
            // await handshake
            state = STPState.LISTEN;
        }

        // Sender logic
        else {
            try (FileOutputStream fos = new FileOutputStream("balls.txt", false)) {
                fos.close();
            } catch (Exception e) {

            }
            startSeq(0, 0xFFFF - 1);
            STPPacket handshake = new STPPacket(STPFlag.SYN, getCurrSeq(0));
            addToSendBuffer(handshake);

            // manually invoke send
            processSendQueue();
            state = STPState.SYN_SENT;
        }

        state = STPState.EST;

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
        if (!isReceiver) {
            new retransmissionThread().start();
        }
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
                    processSendQueue();
                    if (state == STPState.TIME_WAIT) {
                        checkMSL();
                    }
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }

            }

            // signal shutdown if reader finished
            try {
                while (in.available() > 0) {
                }
                for (int i = 0; i < 4; i++) {
                    shutdown[i] = true;
                }

            } catch (IOException ie) {

                for (int i = 0; i < 4; i++) {
                    shutdown[i] = true;
                }
            }

            try {

                in.close();
                out.close();
                sock.close();
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }

        }
    }

    private void checkMSL() {
        if (System.currentTimeMillis() > mslStart + 2000) {
            state = STPState.CLOSED;
        }
    }

    // perform logging and sending operations for connection
    protected class retransmissionThread extends Thread {
        public retransmissionThread() {
            this.setName("Retransmission Thread");
        }

        public void run() {
            try {
                while (state != STPState.CLOSED) {
                    // check state of oldest packet in window
                    STPPacket p = SlidingWindow.peek();
                    if (p == null) {
                        continue;
                    }
                    // get time difference between original send and now
                    long invokeTime = System.currentTimeMillis();
                    boolean resendFlag = ((rto + p.timeStamp) < invokeTime);
                    // while cached packet still at head
                    while (p == SlidingWindow.peek()) {
                        // check if due for resend (either >3 duplicates received or rto)
                        if (resendFlag) {
                            resendFlag = false;
                            dupAck = 0;
                            invokeTime = System.currentTimeMillis();
                            sendPacket(p);
                            // update retransmit stat
                            stats[3]++;
                        }
                        // update resend flag
                        resendFlag = ((dupAck >= 3) || invokeTime + rto < System.currentTimeMillis());
                    }

                }
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Handle incoming data
    protected class incomingThread extends Thread {
        public incomingThread() {
            this.setName("Incoming Thread");
        }

        public void run() {
            while (!shutdown[2]) {
                try {
                    processIncomingPackets();
                } catch (IOException ie) {
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
            boolean EOF = false;
            while (!EOF) {
                try {
                    EOF = processOutgoingPackets();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            // add FIN once all input parsed
            STPPacket packet = new STPPacket(STPFlag.FIN, getCurrSeq(4));
            addToSendBuffer(packet);
        }
    }

    // Process sliding window functions
    protected class WindowThread extends Thread {
        public WindowThread() {
            this.setName("Sliding Window Processing");
        }

        public void run() {
            while (!shutdown[3]) {
                try {
                    processSlidingWindow();
                } catch (Exception e) {
                    Thread.currentThread().interrupt();

                }
            }
        }
    }

    // Pack data from socket inputstream to send
    public boolean processOutgoingPackets() throws InterruptedException {

        byte[] pload = new byte[1000];
        int len;

        try {
            len = outReader.readNBytes(pload, 0, 1000);
            if (len > 0) {
                STPPacket packet = new STPPacket(STPFlag.DATA, getCurrSeq(len), pload, len);
                addToSendBuffer(packet);
                return false;
            } else {
                return true;
            }

        } catch (IOException ie) {
            return true;
        }

    }

    private int getCurrSeq(int size) {
        int diff = (seq + size) - 0xFFFF;
        this.seq = (diff > 0) ? diff : seq + size;
        return seq;
    }

    private boolean acknowledge(int seqno, boolean cumulative) {

        boolean removed = false;
        if (cumulative) {
            STPPacket c = SlidingWindow.peek();
            while (c != null && c.seq == prevAck) {
                SlidingWindow.poll();
                if (prevAck == seqno) {
                    break;
                }
                prevAck = (c.size + prevAck) & 0xFFFF;
                removed = true;

                c = SlidingWindow.peek();
            }
            return removed;
        } else {

            // Remove element matching sequence
            removed = SlidingWindow.removeIf(p -> p.seq == seqno);

            if (state == STPState.FIN_WAIT && SlidingWindow.isEmpty()) {
                state = STPState.CLOSED;
                // add ending stats to log buffer
                addStatsToLogBuffer();
            } else if (state == STPState.SYN_SENT) {
                state = STPState.EST;
            }

            /*
             * 
             * STPPacket head = SlidingWindow.peek();
             * boolean removed = false;
             * if ((head != null) && (head.seq == seqno)) {
             * SlidingWindow.poll();
             * removed = true;
             * }
             */

            if (removed) {
                if (SlidingWindow.isEmpty()) {
                    dupAck = 0;
                }
                stats[4]++;
                System.out.println("Removed packet with seq " + seqno + " from sliding window");
            } else {
                dupAck++;
                stats[4]++;
                System.out.println("Could not remove packet with seq " + seqno + " (Packet not found)");

            }
            return removed;
        }
    }

    private void addStatsToLogBuffer() {
        String formstr = new String("""
                \n----------FINAL STATS-----------
                Original Data Sent:       %d
                Original Data acked:      %d
                Original segments sent:   %d
                Retransmitted segments:   %d
                Dup acks received:        %d
                Data segments dropped:    %d
                Ack segments dropped:     %d
                        """);
        stats[1] = rollover * 0xFFFF - stats[1];
        String endStr = String.format(formstr, stats[0], stats[1], stats[2], stats[3], stats[4], stats[5], stats[6]);
        addToLogBuffer(endStr);
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

    // private boolean addToReceiveBuffer(STPPacket p) {
    // // printwins();
    // return ReceiveBuffer.offer(p);
    // }

    // blocking
    public void processSendQueue() {
        if (SendBuffer.isEmpty()) {
            return;
        }

        STPPacket p = SendBuffer.peek();

        // If data, add to sliding window
        if (p.flg == STPFlag.DATA) {
            if (SlidingWindow.remainingCapacity() == 0) {
                // unless full
                return;
            } else {
                p.timeStamp = System.currentTimeMillis();
                SlidingWindow.add(p);
            }
        }
        // Send fin upon all DATA ack'd
        else if (p.flg == STPFlag.FIN) {
            if (state == STPState.EST) {
                state = STPState.CLOSING;
            }
            if (!SlidingWindow.isEmpty()) {
                return;
            }
            SlidingWindow.add(p);
        }
        // add initial SYN to slidingwindow
        else if (p.flg == STPFlag.SYN) {
            SlidingWindow.add(p);
        }

        // send the packet
        sendPacket(p);

        // remove from head
        SendBuffer.poll();

        // update original data stat
        if (state == STPState.EST) {
            stats[0] += p.size - 4;
            stats[2]++;
        }
    }

    private void sendPacket(STPPacket p) {
        DatagramPacket d = new DatagramPacket(p.bytes(), p.size, remoteAddress);
        if (p.flg == STPFlag.FIN) {
            state = STPState.FIN_WAIT;
        }
        if (!packetLoss(p)) {
            try {
                sock.send(d);
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
            addToLogBuffer(p, "snd");
        } else {
            addToLogBuffer(p, "drp");
            if (p.flg == STPFlag.DATA) {
                stats[5]++;
            }
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

        // simulate packet loss

        if (packetLoss(p)) {

            stats[6]++;

            return;
        }

        switch (p.flg) {
            case DATA:
                if (p.payload == null) {
                    break;
                }

                // Attempt to write to output stream
                if (inSequence(p)) {
                    STPPacket ack = new STPPacket(STPFlag.ACK, p.seq);
                    sendPacket(ack);
                    writeToOutput(p);
                    // handle lost ACK by checking recently written packets
                } else if (ReceiveBuffer.contains(p) || SlidingWindow.contains(p)) {
                    STPPacket ack = new STPPacket(STPFlag.ACK, p.seq);
                    sendPacket(ack);
                } else if (SlidingWindow.remainingCapacity() != 0) {
                    if (!SlidingWindow.contains(p)) {
                        SlidingWindow.add(p);
                    }
                    STPPacket ack = new STPPacket(STPFlag.ACK, p.seq);
                    sendPacket(ack);
                } else {
                    addToLogBuffer(p, "drp");
                }

                break;
            case ACK:
                // Remove corresponding data segment from sliding window
                addToLogBuffer(p, "rcv");
                if (acknowledge(p.seq, false)) {
                    System.out.println(String.format("removed packet with SEQ=%d", p.seq));
                    if (-500 < (stats[1] - p.seq) && (stats[1] - p.seq) < 500) {
                        rollover++;
                    }
                }
                break;
            case SYN:
                addToLogBuffer(p, "rcv");
                // set ISN
                this.ack = p.seq;
                STPPacket synack = new STPPacket(STPFlag.ACK, p.seq);
                addToSendBuffer(synack);
                this.state = STPState.EST;
                break;
            case FIN:
                addToLogBuffer(p, "rcv");
                STPPacket ack = new STPPacket(STPFlag.ACK, p.seq);
                sendPacket(ack);
                if (state == STPState.EST) {
                    state = STPState.TIME_WAIT;
                    mslStart = System.currentTimeMillis();
                }
                break;
            default:
                break;
        }

        return;

    }

    // process packets in sliding window
    public void processSlidingWindow() {

        lock.lock();
        try {
            SlidingWindow.forEach(p -> {
                if (!p.outgoing && p.flg == STPFlag.DATA) {
                    if (inSequence(p)) {
                        writeToOutput(p);
                        p.cullFlag = true;
                    }
                }
            });

            // clean up sliding window
            SlidingWindow.removeIf(p -> (p.cullFlag));

        } finally {
            lock.unlock();
        }

    }

    private void writeToOutput(STPPacket p) {
        // Mutex to ensure threads don't concurrently write to output
        lock.lock();
        if (ReceiveBuffer.size() > window / 1000) {
            ReceiveBuffer.poll();
        }
        try {
            // log written packet
            if (!ReceiveBuffer.contains(p)) {
                ReceiveBuffer.offer(p);
                inWriter.write(p.payload, 0, p.size - 4);
                inWriter.flush();
                p.cullFlag = true;
                this.ack = p.seq;
            }
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    private boolean inSequence(STPPacket p) {
        int expected = this.ack + p.size - 4;
        expected = (expected > 0xFFFF) ? expected - 0xFFFF : expected;

        boolean result = (p.seq == expected);

        return result;
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

    public void addToLogBuffer(String manual) {
        logBuffer.offer(manual);
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
        // save ISN
        stats[1] = seq;
        return seq;
    }

    // STP PACKET
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
