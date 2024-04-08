import java.io.*;
import java.util.*;
import java.net.*;


enum Status {
    SUCCESS,
    TIMEOUT

}

public class SimpleSocket extends DatagramSocket {
    DatagramSocket sock;


    int window;
    int remotePort;
    int localPort;

    DatagramPacket[] SendWindow;
    int


    
    public void SimpleSocket() throws SocketException {


    }


    public SimpleSocket(int window, int serverPort, int clientPort) throws SocketException {
        this.window = window;
        this.remotePort = serverPort;
        this.localPort = clientPort;

    }

    public Status Connect() { 
        
        return Status.SUCCESS;
    
    }

    public Status Send(byte[] data) {

        return Status.SUCCESS;
    }

    private Status SendPacket() {
        

        return Status.SUCCESS;

    }





    

}
