public class Sender {
    private
    float flp;
    float rlp;



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

        //construct socket instance
        if (args.length != 6) {
            System.out.println("Usage: Sender <sender port> <receiver port> <text file> <window size> <retransmission timer>");
            return;
        }


        


        System.out.println("Hello, World!");
    }
    
    public class sendThread extends Thread {

    }

    public class retransmissionTimer extends Thread {
        System.sleep(rto);
    }
}
