import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.net.InetAddress;

public class Receiver {
    int localPort;
    int remotePort;
    InetSocketAddress remoteAddr;

    int winSize;

    private String outFile;
    private String logFile;

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

        client.run();

        System.out.println();
    }

    public Receiver(int localPort, int remotePort, String textFile, int winSize) throws Exception {
        this.localPort = localPort;
        this.remotePort = remotePort;
        this.logFile = "receiver_log.txt";

        InetAddress localhost = InetAddress.getLoopbackAddress();
        this.remoteAddr = new InetSocketAddress(localhost, remotePort);

        this.outFile = textFile;
        this.winSize = winSize;

        this.sock = new SimpleSocket(localPort, winSize, true);

        this.fos = new FileOutputStream(outFile);
    }

    public void run() {
        initialiseLog();

        for (int i = 0; (!sock.connected) && i < 5; i++) {
            try {
                sock.Connect(remoteAddr);
            } catch (Exception e) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                }
            }
        }
        if (!sock.connected) {
            return;
        }

        // set up threads
        try {
            start();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    // Thread runner
    public void start() throws IOException {
        new sendThread().start();
        new receiveThread().start();
        new timerThread().start();
        new logThread().start();
        new maintenanceThread().start();
    }

    // Threads
    public class sendThread extends Thread {
        public sendThread() {
            this.setName("Receive Thread");
        }

        public void run() {
            while (sock.state == STPState.EST) {
                sock.processSendQueue();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

    }

    public class receiveThread extends Thread {
        public receiveThread() {
            this.setName("Receive Thread");
        }

        public void run() {
            while (sock.state == STPState.EST) {
                try {
                    sock.processIncomingPackets();
                    Thread.sleep(100);
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public class timerThread extends Thread {
        public timerThread() {
            this.setName("Timer Thread");
        }

        public void run() {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public class logThread extends Thread {
        public logThread() {
            this.setName("Logging Thread");
        }

        public void run() {
            while (sock.state == STPState.EST) {
                try {
                    writeToLog();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public class maintenanceThread extends Thread {
        public void run() {
            while (sock.state == STPState.EST) {
                try {
                    sock.processReceiveQueue();
                    sock.retransmissionCheck();
                    sock.processSendQueue();
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

    }

    private void initialiseLog() {

        try (FileOutputStream fos = new FileOutputStream(logFile)) {
            ZonedDateTime now = ZonedDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

            String currtime = now.format(formatter);

            String header = """
                              Receiver Log File \n
                    Session date and time: %s\n
                    operation    delta     flag     seq      size
                    ------------------------------------------------
                        """;
            String data = String.format(header, currtime);
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);

            fos.write(bytes);

            sock.setLogFormat("%s          %-8.4f  %-4s      %-6d    %-4d %s\n");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void writeToLog() throws InterruptedException {
        String entry = sock.logBuffer.take();
        byte[] bytes = entry.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
            fos.write(bytes);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}
