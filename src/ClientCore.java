import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RemoteObject;
import java.rmi.server.UnicastRemoteObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;

/**
 * @author Giuseppe Muschetta
 */
public class ClientCore extends RemoteObject implements ClientInterface {

    private static final long serialVersionUID = 4975715027275449432L;

    /** porta servizio di registry */
    private final int registryPort;

    /** porta per la connessione tcp con il server */
    private final int serverPort;

    /** utente gestito dal client */
    private User user;

    /** lista di riferimenti dei thread che eseguono il task ChatSaver */
    private final ArrayList<Thread> chatSavers;

    /** socket client */
    private Socket socket;

    /** stub client registrato per le callbacks */
    private ClientInterface stub;

    /** definisce i metodi remoti del server */
    private ServerInterface server;

    public ClientCore(int portTCP, int portRegistry){
        serverPort = portTCP;
        registryPort = portRegistry;
        chatSavers = new ArrayList<>();
        socket = new Socket();
    }

    public User getUser() {
        return user;
    }

    /**
     * notifica il client in seguito ad un cambiamento di stato degli utenti registrati.
     * Il server invoca il metodo sullo stub del client, ricevuto nel momento della registrazione alle
     * callbacks, passando come parametro la lista aggiornata degli utenti registrati
     *
     * @param registeredUsers lista degli utenti registrati al servizio aggiornata
     * @throws RemoteException -
     */
    @Override
    public void notifyUserEvent(ArrayList<User> registeredUsers) throws RemoteException {
        synchronized (user.getUsers()) {
            //setto la lista locale dell'user con la lista aggiornata tramite callback
            user.setUsersList(registeredUsers);
        }
    }

    /**
     * notifica il client in seguito ad un cambiamento di stato dei progetti.
     * Il server invoca il metodo sullo stub del client, ricevuto nel momento della registrazione alle
     * callbacks, passando come parametro la lista aggiornata dei progetti
     *
     * @param createdProjects lista dei progetti creati nel servizio aggiornata
     * @throws RemoteException -
     */
    @Override
    public void notifyChatsEvent(ArrayList<Project> createdProjects) throws RemoteException {
        //costruisco la lista di chat che interessano l'user
        ArrayList<Chat> userChats = new ArrayList<>();
        for (Project project : createdProjects) {
            if (project.getMembers().contains(user.getNickname()))
                userChats.add(new Chat(project.getChatAddress(), project.getName()));
        }
        synchronized (user.getChats()) {
            //setto la lista locale delle chat dell'user con la lista aggiornata tramite callback
            user.setChats(userChats);
        }
    }


    public void begin() {
        Registry registry;
        //gestore dell'interazione con l'utente
        ClientMenu menu = new ClientMenu(this);
        try {
            //recupero il riferimento dell'oggetto remoto del server
            registry = LocateRegistry.getRegistry(registryPort);
            server = (ServerInterface) registry.lookup("SERVER-WORTH");
            menu.startInteraction();
            //arrivo qui in seguitop ad una "exit"
            //quindi faccio il logout dell'utente se non è gia stato fatto
            if (user != null && user.isOnline())
                logout(user.getNickname());

        } catch (IOException e) {
            try { //quando il server viene chiuso prima del client viene lanciata una IOException
                //se quest'ultimo era loggato, prima di terminare
                //chiudo il socket e interrompo il thread dell'oggetto esportato
                //e quelli dei saver delle chat dell'utente
                if(user != null && user.isOnline()) {
                    UnicastRemoteObject.unexportObject(this, false);
                    socket.close();
                    interruptAllSavers();
                    user = null;
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            System.err.println("< Siamo spiacenti, il servizio WORTH non è disponibile. Riprovare più tardi");
        } catch (NotBoundException e) {
            System.err.println("Errore nella connessione al servizio di registry");
            e.printStackTrace();
        }
    }

    /**
     * richiede la registrazione dell'utente al servizio,
     * utilizzando RMI sullo stub del server recuperato dal registry
     *
     * @param nickname nome utente da registrare
     * @param password password da associare all'utente da registrare
     * @return stringa contenente il responso dell'operazione richiesta
     * @throws RemoteException -
     */
    public String remoteRegister(String nickname, String password) throws RemoteException {
        //invocazione del metodo remoto del server
        Response response = server.register(nickname, password);
        if (response == Response.OK)
            return "ok";
        else
            return "Impossibile registrarsi: l'utente " + nickname + " esiste già";
    }

    /**
     * richiede il login dell'utente
     *
     * @param nickname nome utente che ha richiesto il login
     * @param password password fornita per accedere
     * @return stringa contentente il responso per l'operazione richiesta
     */
    public String login(String nickname, String password) throws IOException {
        if (socket.isClosed())
            socket = new Socket(InetAddress.getLocalHost(), serverPort);
        if (!socket.isConnected())
            socket.connect(new InetSocketAddress(InetAddress.getLocalHost(), serverPort));

        Message message = new Message(Request.LOGIN);
        message.setNickname(nickname);
        message.setPassword(password);
        sendToServer(message);

        Message receivedMsg = receiveFromServer();
        switch (receivedMsg.getResponse()) {
            case OK :
                user = receivedMsg.getUser();
                //se l'user != null l'operazione di login è andata buon fine
                //e il server ci ha mandato il riferimento all'user
                if (user != null) {
                    user.setClient(this);
                    //esportazione stub client da passare al server per le callbacks
                    stub = (ClientInterface) UnicastRemoteObject.exportObject(this, 0);
                    server.registerForCallback(stub);
                    return "ok";
                }
                break;
            case NOT_REGISTERED :
                return "L'utente non è registrato";
            case WRONG_PASSWORD :
                return "Password errata";
            case ALREADY_LOGGED :
                return "Utente già collegato";
            default :
                return "Errore: errore nella comunicazione con il server";
        }
        return "Errore sconosciuto: errore nella fase di login";
    }


    /**
     * richiede il logout dell'utente
     *
     * @param nickname nome utente che ha richiesto il logout
     * @return stringa contentente il responso per l'operazione richiesta
     */
    public String logout(String nickname) throws IOException {
        if (!user.getNickname().equals(nickname))
            return "Nickname errato";

        if (user.isOnline()) {
            Message message = new Message(Request.LOGOUT);
            message.setNickname(nickname);
            sendToServer(message);

            Message receivedMsg = receiveFromServer();
            switch (receivedMsg.getResponse()) {
                case OK :
                    server.unregisterForCallback(stub);
                    UnicastRemoteObject.unexportObject(this, false);
                    interruptAllSavers();
                    user = null;
                    socket.close();
                    return "ok";

                case UNKNOWN_ERROR :
                    return "Errore nella fase di logout";

                default : return "Errore: errore nella comunicazione con il server";
            }
        }
        return "Errore sconosciuto: l'utente e' già disconnesso";
    }

    /**
     * recupera la lista locale degli utenti registrati all'interno dell'user
     * e la stampa utilizzando il metodo printFormattedUsers() di ClientMenu
     *
     * @return stringa contenente il responso per l'operazione richiesta
     */
    public String listUsers() {
        ArrayList<User> users = user.getUsers();
        if (!users.isEmpty()) {
            String msg = users.size() == 1 ?
                    "Attualmente c'è "+ users.size() +" utente registrato a WORTH":
                    "Attualmente ci sono "+ users.size() +" utenti registrati a WORTH";
            ClientMenu.printFormattedUsers(users, msg);
            return "ok";
        }
        return "Errore sconosciuto: non ci sono utenti registrati";
    }

    /**
     * recupera la lista locale degli utenti online con il metodo getOnlineUser() di user
     * e la stampa utilizzando il metodo printFormattedUsers() di ClientMenu
     *
     * @return stringa contenente il responso per l'operazione richiesta
     */
    public String listOnlineUsers() {
        ArrayList<User> onlineUsers = user.getOnlineUsers();
        if (!onlineUsers.isEmpty()) {
            String msg = onlineUsers.size() == 1 ?
                    "In questo momento c'è "+ onlineUsers.size() +" utente online" :
                    "In questo momento ci sono "+ onlineUsers.size() +" utenti online";
            ClientMenu.printFormattedUsers(onlineUsers, msg);
            return "ok";
        }
        //almeno l'utente che utilizza questo metodo deve essere online
        return "Errore sconosciuto: non ci sono utenti online";
    }

    /**
     * richiede la lista dei progetti di cui l'utente fa parte e la stampa usando
     * printFormattedProjects() di ClientMenu
     *
     * @return stringa contenente il responso per l'operazione richiesta
     */
    public String listProjects() throws IOException {
        Message message = new Message(Request.LIST_ALL_PROJECTS);
        message.setNickname(user.getNickname());
        sendToServer(message);

        Message receivedMsg = receiveFromServer();
        switch (receivedMsg.getResponse()) {
            case OK :
                if(!receivedMsg.getProjects().isEmpty()) {
                    String msg = receivedMsg.getProjects().size() == 1 ?
                            "Fai parte di "+receivedMsg.getProjects().size()+" progetto" :
                            "Fai parte di "+receivedMsg.getProjects().size()+" progetti" ;
                    ClientMenu.printFormattedProjects(receivedMsg.getProjects(), msg);
                    return "ok";
                }
                return "Non fai parte di nessun progetto";
            case UNKNOWN_ERROR : return "Errore sconosciuto nel server";
            default : return "Errore: errore nella comunicazione con il server";
        }
    }

    /**
     * richiede la creazione di un nuovo progetto
     *
     * @param projectName nome progetto da creare
     * @return stringa contentente il responso per l'operazione richiesta
     */
    public String createProject(String projectName) throws IOException {

        Message message = new Message(Request.CREATE_PROJECT);
        message.setNickname(user.getNickname());
        message.setProjectName(projectName);
        sendToServer(message);

        Message receivedMsg = receiveFromServer();
        switch (receivedMsg.getResponse()) {
            case OK :
                return "ok";

            case UNABLE_CREATE_PROJECT :
                return "Errore del server";

            case PROJECT_EXISTS :
                return "Esiste già un progetto con questo nome";

            default : return "Errore: errore nella comunicazione con il server";
        }
    }

    /**
     * richiede l'aggiunta di un nuovo membro al progetto indicato
     *
     * @param projectName nome progetto a cui aggiungere il nuovo membro
     * @param nickNewMember nome utente del membro da aggiungere
     * @return stringa contentente il responso per l'operazione richiesta
     */
    public String addMember(String projectName, String nickNewMember) throws IOException {

        Message message = new Message(Request.ADD_MEMBER);
        message.setProjectName(projectName);
        message.setNickname(user.getNickname());
        message.setNewMember(nickNewMember);
        sendToServer(message);

        Message receivedMsg = receiveFromServer();
        switch (receivedMsg.getResponse()) {
            case OK : return "ok";
            case NOT_REGISTERED : return "L'utente " + nickNewMember + " non esiste";
            case MEMBER_EXISTS : return "L'utente " + nickNewMember + " è già membro del progetto";
            case NONEXISTENT_PROJECT : return "Non sei membro di un progetto di nome " + projectName;
            default : return "Errore: errore nella comunicazione con il server";
        }

    }

    /**
     * richiede la lista dei membri del progetto e la stampa usando
     * printFormattedUsers() di ClientMenu
     *
     * @param projectName nome progetto del quale è stata richiesta la lista dei membri
     * @return stringa da inviare al client contenente la lista dei membri del progetto
     */
    public String showMembers(String projectName) throws IOException {

        Message message = new Message(Request.SHOW_ALL_MEMBERS);
        message.setProjectName(projectName);
        message.setNickname(user.getNickname());
        sendToServer(message);

        Message receivedMsg = receiveFromServer();
        switch (receivedMsg.getResponse()) {
            case OK :
                if (!receivedMsg.getMembers().isEmpty()) {
                    ArrayList<User> members = new ArrayList<>();
                    for (String memberName : receivedMsg.getMembers()) {
                        int memberIndex = user.getUsers().indexOf(new User(memberName, null));
                        members.add(user.getUsers().get(memberIndex));
                    }
                    String msg = receivedMsg.getMembers().size() == 1 ?
                            "Il progetto "+projectName+" è composto da " + receivedMsg.getMembers().size() + " membro" :
                            "Il progetto "+projectName+" è composto da " + receivedMsg.getMembers().size() + " membri" ;
                    ClientMenu.printFormattedUsers(members, msg);
                    return "ok";
                }
                return "Nel progetto non e' presente nessun membro";
            case NONEXISTENT_PROJECT :
                return "Non sei membro di un progetto di nome " + projectName;
            default :
                return "Errore: errore nella comunicazione con il server";
        }

    }

    /**
     * richiede la lista di cards del progetto e la stampa usando
     * printFormattedCards() di ClientMenu
     *
     * @param projectName nome progetto del quale e' stata richiesta la lista di cards
     * @return stringa da inviare al client contenente la lista delle cards del progetto
     */
    public String showCards(String projectName) throws IOException {

        Message message = new Message(Request.SHOW_ALL_CARDS);
        message.setProjectName(projectName);
        message.setNickname(user.getNickname());
        sendToServer(message);

        Message receivedMsg = receiveFromServer();
        switch (receivedMsg.getResponse()) {
            case OK :
                if (!receivedMsg.getCards().isEmpty()) {
                    String msg = "Il progetto "+projectName+" è composto da " + receivedMsg.getCards().size() +" card";
                    ClientMenu.printFormattedCards(receivedMsg.getCards(), msg);
                    return "ok";
                }
                return "Nel progetto non è presente nessuna card";
            case NONEXISTENT_PROJECT :
                return "Non sei membro di un progetto di nome " + projectName;
            default :
                return "Errore: errore nella comunicazione con il server";
        }

    }

    /**
     * richiede la card e la stampa usando
     * printCard() di ClientMenu
     *
     * @param projectName nome progetto a cui appartiene la card
     * @param cardName nome card richiesta
     * @return stringa da inviare al client contenente la card richiesta
     */
    public String showCard(String projectName, String cardName) throws IOException {

        Message message = new Message(Request.SHOW_CARD);
        message.setProjectName(projectName);
        message.setNickname(user.getNickname());
        message.setCardName(cardName);
        sendToServer(message);

        Message receivedMsg = receiveFromServer();
        switch (receivedMsg.getResponse()) {
            case OK :
                ClientMenu.printCard(receivedMsg.getCard());
                return "ok";

            case NONEXISTENT_PROJECT :
                return "Non sei membro di un progetto di nome " + projectName;
            case NONEXISTENT_CARD :
                return "Non esiste nessuna carta di nome " + cardName + " nel progetto";
            default : return"Errore: errore nella comunicazione con il server";
        }

    }

    /**
     * richiede l'aggiunta della card con i dettagli forniti al progetto
     *
     * @param projectName nome progetto al quale bisogna aggiungere la card
     * @param cardName nome card da aggiungere
     * @param description descrizione card da aggiungere
     * @return stringa contentente il responso per l'operazione richiesta
     */
    public String addCard(String projectName, String cardName, String description) throws IOException {

        Message message = new Message(Request.ADD_CARD);
        message.setProjectName(projectName);
        message.setCardName(cardName);
        message.setDescription(description);
        message.setNickname(user.getNickname());
        sendToServer(message);

        Message receivedMsg = receiveFromServer();
        switch (receivedMsg.getResponse()) {
            case OK : return "ok";
            case NONEXISTENT_PROJECT : return "Non sei membro di un progetto di nome " + projectName;
            case CARD_EXISTS : return "La card " + cardName + " esiste già";
            default : return "Errore: errore nella comunicazione con il server";
        }

    }

    /**
     *  richiede lo spostamento della card, se consentito, da una lista di partenza a una di destinazione
     *
     * @param projectName nome progetto di cui fa parte la card
     * @param cardName nome card da spostare
     * @param sourceList lista di partenza da cui spostare la card
     * @param destList lista di destinazione in cui spostare la card
     * @return stringa contentente il responso per l'operazione richiesta
     */
    public String moveCard(String projectName, String cardName, String sourceList, String destList) throws IOException {

        Message message = new Message(Request.MOVE_CARD);
        message.setProjectName(projectName);
        message.setCardName(cardName);
        message.setSourceList(sourceList);
        message.setDestList(destList);
        message.setNickname(user.getNickname());
        sendToServer(message);

        Message receivedMsg = receiveFromServer();
        switch (receivedMsg.getResponse()) {
            case OK : return "ok";
            case NONEXISTENT_PROJECT :
                return "Non sei membro di un progetto di nome " + projectName;
            case NONEXISTENT_LIST :
                return "Una delle liste non esiste";
            case NONEXISTENT_CARD :
                return "La card " + cardName + " non è presente nella lista di partenza";
            case MOVE_CARD_FORBIDDEN :
                return "Vietato spostare la card da " + sourceList + " a " + destList;
            case CARD_EXISTS :
                return "La card " + cardName + " è già nella lista di destinazione";
            default : return "Errore: errore nella comunicazione con il server";
        }
    }

    /**
     * richiede la card e ne stampa lo storico degli spostamenti utilizzando
     * il metodo printCardHistory() di ClientMenu
     * @param projectName nome progetto di cui fa parte la card
     * @param cardName nome card
     * @return stringa contenente il responso per l'operazione richiesta
     * @throws IOException -
     */
    public String getCardHistory(String projectName, String cardName) throws IOException {

        //chiedo al server la card e da questa mi prenderò la history
        Message message = new Message(Request.SHOW_CARD);
        message.setProjectName(projectName);
        message.setNickname(user.getNickname());
        message.setCardName(cardName);
        sendToServer(message);

        Message receivedMsg = receiveFromServer();
        switch (receivedMsg.getResponse()) {
            case OK :
                ClientMenu.printCardHistory(receivedMsg.getCard());
                return "ok";
            case NONEXISTENT_PROJECT :
                return "Non sei membro di un progetto di nome " + projectName;
            case NONEXISTENT_CARD :
                return "Non esiste nessuna carta di nome " + cardName + " nel progetto";
            default :
                return "Errore: errore nella comunicazione con il server";
        }
    }

    /**
     * invia un messaggio sulla chat di progetto
     * @param projectName nome progetto relativo alla chat su cui inviare il messaggio
     * @param message messaggio da inviare
     * @return stringa contenente il responso per l'operazione richiesta
     */
    public String sendChatMsg(String projectName, String message) {
        if (!user.getChats().contains(new Chat(projectName))) {
            return "Non sei membro di un progetto di nome " + projectName;
        }
        user.sendChatMsg(projectName, message);
        return "ok";
    }

    /**
     * riceve i messaggi della chat di progetto non ancora letti
     * a partire dall'ultima esecuzione dello stesso metodo
     *
     * @param projectName nome progetto relativo alla chat da leggere
     * @return stringa contenente il responso per l'operazione richiesta
     */
    public String readChat(String projectName) {
        if (!user.getChats().contains(new Chat(projectName))) {
            return "Non sei membro di un progetto di nome " + projectName;
        }
        user.readChat(projectName);
        return "ok";
    }

    /**
     * richiede la cancellazione del progetto
     *
     * @param projectName nome progetto da cancellare
     * @return stringa contentente il responso per l'operazione richiesta
     */
    public String cancelProject(String projectName) throws IOException {

        Message message = new Message(Request.CANCEL_PROJECT);
        message.setProjectName(projectName);
        message.setNickname(user.getNickname());
        sendToServer(message);

        Message receivedMsg = receiveFromServer();
        switch (receivedMsg.getResponse()) {
            case OK :
                return "ok";
            case NONEXISTENT_PROJECT :
                return "Non sei membro di un progetto di nome " + projectName;
            case DELETE_FORBIDDEN :
                return "Impossibile cancellare il progetto: le carte non sono tutte nella lista DONE";
            default :
                return "Errore: errore nella comunicazione con il server";
        }
    }

    /**
     * invia al server un messaggio per una richiesta, facendo una gathering write sul
     * socket channel su cui è stata stabilita la connessione. Scrive due buffer, il primo
     * per indicare la dimensione del messaggio inviato, il secondo per il messaggio vero e proprio
     *
     * @param message messaggio da inviare
     * @throws IOException errore durante la scrittura sul canale
     */
    private void sendToServer(Message message) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setDateFormat(new SimpleDateFormat("dd-MMM-yy"));
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        byte[] byteArray = mapper.writeValueAsString(message).getBytes();
        DataOutputStream outStream = new DataOutputStream(
                new BufferedOutputStream(socket.getOutputStream()));
        outStream.writeInt(byteArray.length);
        outStream.write(byteArray);
        outStream.flush();
    }

    /**
     * riceve dal server un messaggio, facendo due read sul socket channel su
     * cui è stata stabilita la connessione. Legge due buffer, il primo
     * conterrà la dimensione del messaggio inviato, utile per allocare il secondo
     * buffer della dimensione esatta per contenere il messaggio vero e proprio
     *
     * @return responso in seguito ad una richiesta al server
     * @throws IOException errore nella fase di lettura sul canale
     */
    private Message receiveFromServer() throws IOException {
        DataInputStream inStream = new DataInputStream(
                new BufferedInputStream(socket.getInputStream()));
        //leggere dal socket la dimensione dell'oggetto
        int dim = inStream.readInt(); //abbiamo letto 4 posizioni da 1 byte ciascuna
        byte[] arrayDiByte = new byte[dim];
        //leggere dal socket l'oggetto in .json
        inStream.readFully(arrayDiByte); //adesso dovremmo avere tutto l'oggetto nell'array di byte
        //INIZIA LA DESERIALIZZAZIONE
        //ora ho l'oggetto dentro l'array di byte che possiamo deserializzare con Jackson
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setDateFormat(new SimpleDateFormat("dd-MM-yy"));
        Message message = mapper.reader()
                .forType(new TypeReference<Message>() {})
                .readValue(arrayDiByte);
        return message;
    }

    /**
     * avvia un thread che esegue il task ChatSaver e lo aggiunge alla lista dei savers
     * Utilizzato nel momento in cui l'user viene aggiunto a un nuovo progetto e quindi vuole
     * iniziare a memorizzare i messaggi inviati su quella chat
     *
     * @param chat chat di cui salvare i messaggi
     */
    public void startSaver(Chat chat) {
        Thread snifferThread = new Thread(new ChatSaver(chat, user));
        snifferThread.setName(chat.getProject());
        chatSavers.add(snifferThread);
        snifferThread.start();
    }

    /**
     * interrompe un thread che esegue il task ChatSaver e lo rimuove alla lista dei savers
     * Utilizzato nel momento in cui viene cancellato un progetto dell'user, quindi non vorrà
     * più ricevere e memorizzare i messaggi inviati su quella chat
     * @param chat chat di cui interrompere il thread saver
     */
    public void interruptSaver(Chat chat) {
        for (Thread thread : chatSavers) {
            if (thread.getName().equals(chat.getProject())) {
                thread.interrupt();
                chatSavers.remove(thread);
                break;
            }
        }
    }

    /**
     * interrompe tutti i thread saver, cioè smette di ricevere tutti i messaggi dalle chat di progetto
     * di cui fa parte l'utente. Utilizzato nel momento quando si deve chiudere il client, cioè in seguito
     * ad un'operazione di logout oppure dopo la disconnessione dal server
     */
    private void interruptAllSavers() {
        for (Thread thread : chatSavers) {
            thread.interrupt();
        }
    }
}
