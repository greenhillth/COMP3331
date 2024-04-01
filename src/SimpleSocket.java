import java.net.DatagramSocket;
import java.net.SocketException;


enum Status {
    SUCCESS,
    TIMEOUT

}

public class SimpleSocket extends DatagramSocket {
    DatagramSocket sock;


    int window;
    int remotePort;
    int localPort;



    public SimpleSocket() throws SocketException {


    }


    public SimpleSocket(int window, int serverPort, int clientPort) throws SocketException {
        this.window = window;
        this.remotePort = serverPort;
        this.localPort = clientPort;

    }

    public Status Connect() { 
        
        return Status.SUCCESS;
    
    }

    private Status SendPacket() {
        

        return Status.SUCCESS;

    }
    

}
