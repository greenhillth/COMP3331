import java.io.IOException;
import java.net.*;
import java.util.*;

public class Sender {
    int localPort;
    int remotePort;
    InetSocketAddress remoteAddr;

    private float flp;
    private float rlp;
    private int rto;

    private String textFile;
    private SimpleSocket sock;

    private Random rng;

    public static void main(String[] args) throws Exception {
        // get args for
        // Arg struct:
        /*
         * 0 - sender port (int)
         * 1 - receiver port (int)
         * 2 - name of sample text file to send (string)
         * 3 - max window size (int)
         * 4 - rto or retransmission timer, in ms (int)
         * 5 - flp or forward loss probability (float)
         * 6 - rlp or reverse loss probability (float)
         */

        // construct socket instance
        if (args.length != 7) {
            System.out.println(
                    "Usage: Sender <sender port> <receiver port> <text file> <window size> <retransmission timer> <flp> <rlp>");
            return;
        }

        Sender client = new Sender(Integer.parseInt(args[0]), Integer.parseInt(args[1]),
                args[2], Integer.parseInt(args[3]), Integer.parseInt(args[4]),
                Float.parseFloat(args[5]), Float.parseFloat(args[6]));

        System.out.println("Sender created");

        client.connect();

        System.out.println();

    }

    public Sender(int localPort, int remotePort, String textFile, int winSize, int retransmissionTimer, float flp,
            float rlp) throws SocketException {
        this.flp = flp;
        this.rlp = rlp;
        this.textFile = textFile;

        InetAddress localhost = InetAddress.getLoopbackAddress();
        this.remoteAddr = new InetSocketAddress(localhost, remotePort);

        this.sock = new SimpleSocket(localPort, winSize, false);

    }

    public void connect() throws IOException {
        sock.Connect(remoteAddr);
    }

    private Status send() throws InterruptedException {
        if (!forwardLoss()) {

        }

        return Status.SUCCESS;
    }

    private boolean forwardLoss() {
        return rng.nextFloat() < flp;
    }

    private boolean reverseLoss() {
        return rng.nextFloat() < rlp;
    }

    // Thread runner
    public void start() throws IOException {
        new sendThread().start();
        new logThread().start();
        new maintenanceThread().start();
        new timerThread().start();
    }

    // Threads
    public class sendThread extends Thread {
        public void run() {
            try {
                send();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

    }

    public class timerThread extends Thread {
        public void run() {
            try {
                Thread.sleep(rto);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public class logThread extends Thread {
        public void run() {
            try {

            } catch (Exception e) {

            }
        }

    }

    public class maintenanceThread extends Thread {
        public void run() {
            try {

            } catch (Exception e) {

            }
        }

    }
}
