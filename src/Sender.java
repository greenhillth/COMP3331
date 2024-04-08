import java.io.IOException;
import java.net.SocketException;

public class Sender {
    private float flp;
    private float rlp;
    private int rto;

    private String textFile;
    private SimpleSocket sock;

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

        try {
            client.start();
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }

        System.out.println("Hello, World!");
    }

    public Sender(int localPort, int remotePort, String textFile, int winSize, int retransmissionTimer, float flp,
            float rlp) throws SocketException {
        this.flp = flp;
        this.rlp = rlp;
        this.textFile = textFile;
        this.sock = new SimpleSocket(winSize, localPort, remotePort);

    }

    // Thread runner
    public void start() throws IOException {
        sock.Connect();

        new sendThread().start();
        new logThread().start();
        new maintenanceThread().start();
        new timerThread().start();
    }

    // Threads
    public class sendThread extends Thread {
        public void run() {
            try {
                Thread.sleep(rto);
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
