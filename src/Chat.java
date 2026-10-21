import java.io.Serializable;
import java.net.InetAddress;
import java.util.ArrayList;

/**
 * @author Giuseppe Muschetta
 */
public class Chat implements Serializable {

    private static final long serialVersionUID = 5673658892440952089L;

    /** indirizzo multicast chat */
    private InetAddress address;

    /** porta servizio multicast */
    private final int port = 13731;

    /** progetto di cui fa parte la chat */
    private String project;

    /** lista dei messaggi inviati sulla chat */
    private ArrayList<String> messages;

    public Chat(){}

    public Chat(InetAddress address, String project) {
        this.address = address;
        this.project = project;
        messages = new ArrayList<>();
    }

    public Chat(String project){
        this.project = project;
        messages = new ArrayList<>();
    }

    /**
     *
     * @return indirizzo multicast chat
     */
    public InetAddress getAddress() {
        return address;
    }

    /**
     *
     * @return porta servizio multicast chat
     */
    public int getPort() {
        return port;
    }

    /**
     *
     * @return progetto di cui fa parte la chat
     */
    public String getProject() {
        return project;
    }

    /**
     *
     * @return lista di messaggi della chat
     */
    public ArrayList<String> getMessages() {
        return messages;
    }


    /**
     * stampa i messaggi della chat resettando la lista dei messaggi
     * per far si che l'utente veda solamente i messaggi non ancora letti
     *
     * @return stringa rappresentante la chat con tutti i suoi messaggi
     */
    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        for (String message : messages)
            str.append("< ").append(message).append("\n");
        str.append("< " + "Non ci sono altri messaggi");
        messages = new ArrayList<>();
        return str.toString();
    }


    /**
     *
     * @param obj oggetto da confrontare con this
     * @return true se i due oggetti sono uguali, false altrimenti
     */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Chat))
            return false;
        return project.equals(((Chat) obj).project);
    }

}

