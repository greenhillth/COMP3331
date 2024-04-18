import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Sender {
    int localPort;
    int remotePort;
    InetSocketAddress remoteAddr;

    private float flp;
    private float rlp;
    private int rto;

    private String textFile;
    private String logFile;
    private SimpleSocket sock;

    private Random rng;

    ByteArrayInputStream fileData;

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

        Thread.sleep(500);
        client.run();

    }

    public Sender(int localPort, int remotePort, String textFile, int winSize, int retransmissionTimer, float flp,
            float rlp) throws SocketException {
        this.flp = flp;
        this.rlp = rlp;
        this.rto = retransmissionTimer;
        this.textFile = textFile;
        this.logFile = "sender_log.txt";

        InetAddress localhost = InetAddress.getLoopbackAddress();
        this.remoteAddr = new InetSocketAddress(localhost, remotePort);

        this.sock = new SimpleSocket(localPort, winSize, false);

    }

    public void run() throws Exception {
        initialiseLog();

        for (int i = 0; (!sock.connected()) && i < 5; i++) {
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
        if (!sock.connected()) {
            throw new SocketException("STP Unable to connect");
        }

        // Set Sender transmission parameters
        sock.setTransmissionParams(flp, rlp, rto);
        new logThread().start();

        OutputStream out = sock.getOutputStream();

        // Load file data into filedata buffer
        try (FileInputStream fis = new FileInputStream(textFile)) {

            byte[] buffer = new byte[1000];
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.close();

        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public class logThread extends Thread {

        public logThread() {
            this.setName("Logging Thread");
        }

        public void run() {
            while (sock.connected() && !sock.logBuffer.isEmpty()) {
                try {
                    writeToLog();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                Thread.sleep(500);
                while (!sock.logBuffer.isEmpty()) {
                    writeToLog();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        private void writeToLog() throws InterruptedException {
            String entry = sock.logBuffer.take();
            byte[] bytes = entry.getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                fos.write(bytes);
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }

        }

    }

    private void initialiseLog() {

        try (FileOutputStream fos = new FileOutputStream(logFile)) {
            ZonedDateTime now = ZonedDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

            String currtime = now.format(formatter);

            String header = """
                              Sender Log File \n
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
}
