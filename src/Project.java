import java.io.Serializable;
import java.net.InetAddress;
import java.util.ArrayList;

/**
 * @author Giuseppe Muschetta
 */
public class Project implements Serializable {

    private static final long serialVersionUID = -1225614063308839028L;

    /** nome progetto */
    private String name;

    /** lista di cards nello stato di TODO */
    private ArrayList<Card> toDo;

    /** lista di cards nello stato di INPROGRESS */
    private ArrayList<Card> inProgress;

    /** lista di cards nello stato di TOBEREVISED */
    private ArrayList<Card> toBeRevised;

    /** lista di cards nello stato di DONE */
    private ArrayList<Card> done;

    /** lista di tutte le cards del progetto */
    private ArrayList<Card> cards;

    /** lista di tutti i membri del progetto */
    private ArrayList<String> members;

    /** indirizzo multicast della chat di progetto */
    private InetAddress chatAddress;

    public Project(){}

    public Project(String name) {
        this.name = name;
        this.toDo = new ArrayList<>();
        this.inProgress = new ArrayList<>();
        this.toBeRevised = new ArrayList<>();
        this.done = new ArrayList<>();
        this.cards = new ArrayList<>();
        this.members = new ArrayList<>();
    }

    /**
     *
     * @param name nome progetto
     * @param nickFirstMember utente che crea il progetto
     */
    public Project(String name, String nickFirstMember) {
        this.name = name;
        this.toDo = new ArrayList<>();
        this.inProgress = new ArrayList<>();
        this.toBeRevised = new ArrayList<>();
        this.done = new ArrayList<>();
        this.cards = new ArrayList<>();
        this.members = new ArrayList<>();
        this.members.add(nickFirstMember);
    }



    /**
     * @return nome progetto
     */
    public String getName() {
        return this.name;
    }

    /**
     * @return lista di membri del progetto
     */
    public ArrayList<String> getMembers() {
        return members;
    }

    /**
     * @return lista di cards del progetto
     */
    public ArrayList<Card> getCards() {
        return cards;
    }

    /**
     * @return lista _TODO
     */
    public ArrayList<Card> getToDo() {
        return toDo;
    }

    /**
     * @return lista INPROGRESS
     */
    public ArrayList<Card> getInProgress() {
        return inProgress;
    }

    /**
     * @return lista TOBEREVISED
     */
    public ArrayList<Card> getToBeRevised() {
        return toBeRevised;
    }

    /**
     * @return lista DONE
     */
    public ArrayList<Card> getDone() {
        return done;
    }

    /**
     * @return indirizzo della chat di progetto
     */
    public InetAddress getChatAddress() {
        return chatAddress;
    }

    /**
     * @param chatAddress indirizzo della chat di progetto
     */
    public void setChatAddress(InetAddress chatAddress) {
        this.chatAddress = chatAddress;
    }

    /**
     * effettua il parsing del nome della lista ritornando la lista effettiva
     * @param list nome della lista da parsare
     * @return lista richiesta
     */
    public ArrayList<Card> parseList(String list) {
        ArrayList<Card> parsedList;
        switch (list.toUpperCase()) {
            case "TODO" : parsedList = this.getToDo(); break;
            case "INPROGRESS" : parsedList = this.getInProgress(); break;
            case "TOBEREVISED" : parsedList = this.getToBeRevised(); break;
            case "DONE" : parsedList = this.getDone(); break;
            default : parsedList = null;
        }
        return parsedList;
    }

    /**
     * @return stringa che rappresenta il progetto (tramite il nome)
     */
    @Override
    public String toString() {
        return this.name;
    }

    /**
     * @param obj oggetto da confrontare con this
     * @return true se i due oggetti sono uguali, false altrimenti
     */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Project))
            return false;
        return this.name.equals(((Project) obj).getName());
    }
}
