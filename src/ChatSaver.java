import java.io.IOException;
import java.net.*;

/**
 * @author Giuseppe Muschetta
 */
public class ChatSaver implements Runnable{

    /** chat di cui salvare i messaggi */
    private final Chat chat;

    /** user a cui interessano i messaggi */
    private final User user;

    /** socket per collegarsi al gruppo multicast */
    MulticastSocket multicastSocket;

    private final int DIM_BUFFER = 8192;

    public ChatSaver(Chat chat, User user) {
        this.chat = chat;
        this.user = user;
    }

    @Override
    public void run() {
        SocketAddress socketAddress = new InetSocketAddress(chat.getAddress(), chat.getPort());
        try {
            multicastSocket = new MulticastSocket(chat.getPort());
            multicastSocket.joinGroup(socketAddress, multicastSocket.getNetworkInterface());
            multicastSocket.setSoTimeout(2000);
            while (user.isOnline() && !Thread.currentThread().isInterrupted()) {
                try {
                    byte[] buffer = new byte[DIM_BUFFER];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    multicastSocket.receive(packet);
                    String received = new String(packet.getData());
                    chat.getMessages().add(received.trim());
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (multicastSocket != null) {
                    multicastSocket.leaveGroup(socketAddress, multicastSocket.getNetworkInterface());
                    multicastSocket.close();
                }
            } catch (IOException e) { e.printStackTrace(); }
        }
    }
}

