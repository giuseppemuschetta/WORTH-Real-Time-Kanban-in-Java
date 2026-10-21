/**
 * @author Giuseppe Muschetta
 */

public class ClientMain {

    private final static int portTCP = 45678;
    private final static int portRegistry = 56789;

    public static void main(String[] args) {

        ClientCore core = new ClientCore(portTCP,portRegistry);
        core.begin();

    }

}

