import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.*;

public class Receiver {
    int localPort;
    int remotePort;
    InetSocketAddress remoteAddr;

    int winSize;

    private String outFile;
    private FileOutputStream fos;
    private SimpleSocket sock;

    public static void main(String[] args) throws Exception {
        // get args for
        // Arg struct:
        /*
         * 0 - Receiver port (int)
         * 1 - receiver port (int)
         * 2 - name of sample text file to send (string)
         * 3 - max window size (int)
         */

        // construct socket instance
        if (args.length != 4) {
            System.out.println(
                    "Usage: Receiver <Receiver port> <Sender Port> <Output Text> <Window Size>");
            return;
        }

        Receiver client = new Receiver(Integer.parseInt(args[0]), Integer.parseInt(args[1]),
                args[2], Integer.parseInt(args[3]));

        System.out.println("Receiver created");

        client.connect();

        System.out.println();
    }

    public Receiver(int localPort, int remotePort, String textFile, int winSize) throws Exception {
        this.localPort = localPort;
        this.remotePort = remotePort;

        InetAddress localhost = InetAddress.getLoopbackAddress();
        this.remoteAddr = new InetSocketAddress(localhost, remotePort);

        this.outFile = textFile;
        this.winSize = winSize;

        this.sock = new SimpleSocket(localPort, winSize, true);

        this.fos = new FileOutputStream(outFile);
    }

    public void connect() throws IOException {
        sock.Connect(remoteAddr);
    }

    // Threads

    public class sendThread extends Thread {
        public void run() {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

    }

    public class writerThread extends Thread {
        public void run() {
            try {
                Thread.sleep(1000);
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
