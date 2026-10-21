/**
 * @author Giuseppe Muschetta
 */

public class ServerMain {

    private final static int portTCP = 45678;
    private final static int portRegistry = 56789;

    public static void main(String[] args) {

        ServerCore server = new ServerCore(portTCP, portRegistry);
        server.begin();

    }

}

