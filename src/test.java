import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

public class test {
    public static void main(String[] args) {
        try {
            // Create piped input and output streams
            PipedInputStream pipedInputStream = new PipedInputStream();
            PipedOutputStream pipedOutputStream = new PipedOutputStream();

            // Connect the input stream to the output stream
            pipedInputStream.connect(pipedOutputStream);

            // Write data to the output stream
            String message = "Hello, world!";
            pipedOutputStream.write(message.getBytes());

            // Read data from the input stream
            int byteRead;
            while ((byteRead = pipedInputStream.read()) != -1) {
                System.out.print((char) byteRead);
            }

            // Close the streams
            pipedOutputStream.close();
            pipedInputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
