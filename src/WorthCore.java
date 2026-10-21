import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.TreeSet;

/**
 * @author Giuseppe Muschetta
 */

public class WorthCore implements WorthInterface{

    /** server che gestisce il servizio */
    private final ServerCore server;

    /** insieme degli indirizzi assegnabili ad un progetto */
    private final TreeSet<String> availableAddresses;

    /** numero massimo di progetti creabili
     * (dipende dal range degli indirizzi multicast utilizzati per le chat) */
    private final int MAX_PROJECTS = 32*256; //8K progetti

    /** porta utilizzata dalle chat */
    private final int chatsPort = 13731;

    /** lista di utenti registrati */
    private final ArrayList<User> registeredUsers;

    /** lista dei progetti creati */
    private final ArrayList<Project> createdProjects;

    public WorthCore(ServerCore server) {
        this.server = server;
        //indirizzo di partenza per le chat multicast
        //ad ogni nuovo indirizzo assegnato aggiorniamo questa stringa
        //la quale rappresenterà l'ultimo indirizzo di multicast assegnato
        String multicastAddress = "239.255.224.0";
        availableAddresses = new TreeSet<>((o1, o2) -> {
            //tokenizza gli ottetti dell'indirizo
            String[] ip1 = o1.split("\\.");
            String ipFormatted1 = String.format("%3s.%3s.%3s.%3s", ip1[0],ip1[1],ip1[2],ip1[3]);
            String[] ip2 = o2.split("\\.");
            String ipFormatted2 = String.format("%3s.%3s.%3s.%3s",  ip2[0],ip2[1],ip2[2],ip2[3]);
            return ipFormatted1.compareTo(ipFormatted2);
        });
        String[] strAddress = multicastAddress.split("\\.");
        int[] address = new int[strAddress.length];
        for (int i = 0; i < address.length; i++)
            address[i] = Integer.parseInt(strAddress[i]);

        //aggiungo il primo
        availableAddresses.add(multicastAddress);
        //aggiungo tutti gli altri finche' non arrivo al massimo
        while (availableAddresses.size() != MAX_PROJECTS) {
            //incremento indirizzo a partire dall'ultimo indirizzo assegnato
            //e aggiungo all'insieme
            if (address[3] < 255) {
                address[3]++;
            } else if (address[2] < 255) {
                address[2]++;
                address[3] = 0;
            }
            StringBuilder chatAddress = new StringBuilder();
            for (int i = 0; i < address.length - 1; i++) {
                chatAddress.append(address[i]).append(".");
            }
            chatAddress.append(address[address.length - 1]);
            availableAddresses.add(chatAddress.toString());
        }
        registeredUsers = new ArrayList<>();
        createdProjects = new ArrayList<>();
    }


    /**
     *
     * @return la lista degli utenti registrati
     */
    public ArrayList<User> getRegisteredUsers() {
        return registeredUsers;
    }

    /**
     *
     * @return la lista dei progetti creati
     */
    public ArrayList<Project> getCreatedProjects() {
        return createdProjects;
    }

    /**
     * effettua il login dell'utente
     *
     * @param nickname nome utente che ha richiesto il login
     * @param password password fornita per accedere
     * @return messaggio contentente il responso per l'operazione richiesta
     */
    @Override
    public Message login(String nickname, String password) {
        Message message = new Message();
        User tmp = new User(nickname, password);
        int index = registeredUsers.indexOf(tmp);
        //utente non registrato
        if (index == -1) {
            message.setResponse(Response.NOT_REGISTERED);
        } else {
            try {   //check password
                if (!HashPassword.check(password, registeredUsers.get(index).getPassword())) {
                    message.setResponse(Response.WRONG_PASSWORD);
                } else if (registeredUsers.get(index).isOnline()) {
                    message.setResponse(Response.ALREADY_LOGGED);
                } else {
                    //sincronizzazione sulla lista perchè può essere modificata concorrentemente
                    //dai thread che eseguono i task ServerThread
                    synchronized (registeredUsers) {
                        registeredUsers.get(index).setOnline(true);
                    }
                    User user = registeredUsers.get(index);
                    message.setUser(user);
                    message.setResponse(Response.OK);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return message;
    }


    /**
     * effettua il logout dell'utente
     *
     * @param nickname nome utente che ha richiesto il logout
     * @return messaggio contentente il responso per l'operazione richiesta
     */
    @Override
    public Message logout(String nickname) {
        Message message = new Message();
        int index = registeredUsers.indexOf(new User(nickname, null));
        if (index != -1) {
            synchronized (registeredUsers) {
                registeredUsers.get(index).setOnline(false);
            }
            message.setResponse(Response.OK);
            //aggiorna le liste locali degli utenti registrati quando un utente effettua il logout
            server.updateClientUsers();
            return message;
        }
        message.setResponse(Response.UNKNOWN_ERROR);
        return message;
    }


    /**
     * costruisce la lista dei progetti di cui l'utente fa parte
     *
     * @param nickname nome utente che ha richiesto la lista dei progetti
     * @return messaggio da inviare al client contenente la lista dei progetti di cui fa parte
     */
    @Override
    public Message listProjects(String nickname) {
        Message message = new Message();
        //costruiamo la lista dei progetti dell'utente
        ArrayList<Project> userProjects = new ArrayList<>();
        for (Project project : createdProjects) {
            if (project.getMembers().contains(nickname))
                userProjects.add(project);
        }
        message.setResponse(Response.OK);
        message.setProjects(userProjects);
        return message;
    }


    /**
     * crea un nuovo progetto
     * e invia un messaggio nella chat di progetto indicando l'operazione eseguita
     *
     * @param nickname nome utente che ha richiesto la creazione del progetto
     * @param projectName nome progetto da creare
     * @return messaggio contentente il responso per l'operazione richiesta
     */
    @Override
    public Message createProject(String nickname, String projectName) {
        Message message = new Message();
        Project project = new Project(projectName, nickname);
        if (!bindChatAddress(project)) {
            message.setResponse(Response.UNABLE_CREATE_PROJECT);
            return message;
        }
        // controllo e modifica atomici
        synchronized (createdProjects) {
            if (createdProjects.contains(project)) {
                message.setResponse(Response.PROJECT_EXISTS);
            } else {
                //aggiorno la lista di tutti i progetti lato server
                createdProjects.add(project);
                server.saveProject(project);
                message.setResponse(Response.OK);
                server.updateClientChats();
                sendChatMsg(project, nickname + " ha creato il progetto " + projectName);
            }
        }
        return message;
    }


    /**
     * aggiunge un nuovo membro al progetto indicato
     * e invia un messaggio nella chat di progetto indicando l'operazione eseguita
     *
     * @param nickname nome utente che ha richiesto l'aggiunta di un nuovo membro
     * @param projectName nome progetto a cui aggiungere il nuovo membro
     * @param nickNewMember nome utente del membro da aggiungere
     * @return messaggio contentente il responso per l'operazione richiesta
     */
    @Override
    public Message addMember(String nickname, String projectName, String nickNewMember) {
        Message message = new Message();
        int projectIndex = createdProjects.indexOf(new Project(projectName, null));
        // controllo esistenza del progetto
        if (projectIndex == -1) {
            message.setResponse(Response.NONEXISTENT_PROJECT);
            return message;
        }
        Project project = createdProjects.get(projectIndex);
        // controllo appartenenza dell'utente al progetto
        if (!project.getMembers().contains(nickname)) {
            message.setResponse(Response.NONEXISTENT_PROJECT);
            return message;
        }
        // controllo esistenza negli utenti registrati del nuovo membro
        int newMemberIndex = registeredUsers.indexOf(new User(nickNewMember, null));
        if (newMemberIndex == -1) {
            message.setResponse(Response.NOT_REGISTERED);
            return message;
        }
        synchronized (createdProjects) {
            // controllo che il nuovo membro non sia già membro del progetto
            if (project.getMembers().contains(nickNewMember)) {
                message.setResponse(Response.MEMBER_EXISTS);
                return message;
            }
            // modifico nella lista createdProject (aggiungo il nuovo membro al progetto)
            project.getMembers().add(nickNewMember);
            server.saveProject(project);
        }
        message.setResponse(Response.OK);
        server.updateClientChats();
        sendChatMsg(project, nickname + " ha aggiunto un nuovo membro: " + nickNewMember);
        return message;
    }


    /**
     * recupera la lista dei membri del progetto
     *
     * @param nickname nome utente che ha richiesto la lista dei membri del progetto
     * @param projectName nome progetto del quale è stata richiesta la lista dei membri
     * @return messaggio da inviare al client contenente la lista dei membri del progetto
     */
    @Override
    public Message showMembers(String nickname, String projectName) {
        Message message = new Message();
        int projectIndex = createdProjects.indexOf(new Project(projectName, null));
        // controllo esistenza del progetto
        if (projectIndex == -1) {
            message.setResponse(Response.NONEXISTENT_PROJECT);
            return message;
        }
        Project project = createdProjects.get(projectIndex);
        // controllo appartenenza dell'utente al progetto
        if (!project.getMembers().contains(nickname)) {
            message.setResponse(Response.NONEXISTENT_PROJECT);
            return message;
        }
        message.setResponse(Response.OK);
        message.setMembers(project.getMembers());
        return message;
    }


    /**
     * recupera la lista di cards del progetto
     *
     * @param nickname nome utente che ha richiesto la lista di cards del progetto
     * @param projectName nome progetto del quale e' stata richiesta la lita di cards
     * @return messaggio da inviare al client contenente la lista delle cards del progetto
     */
    @Override
    public Message showCards(String nickname, String projectName) {
        Message message = new Message();
        int projectIndex = createdProjects.indexOf(new Project(projectName, null));
        // controllo esistenza progetto
        if (projectIndex == -1) {
            message.setResponse(Response.NONEXISTENT_PROJECT);
            return message;
        }
        Project project = createdProjects.get(projectIndex);
        // controllo appartenenza dell'utente al progetto
        if (!project.getMembers().contains(nickname)) {
            message.setResponse(Response.NONEXISTENT_PROJECT);
            return message;
        }

        ArrayList<Card> cards = new ArrayList<>(project.getCards());
        message.setResponse(Response.OK);
        message.setCards(cards);
        return message;
    }


    /**
     * recupera la card richiesta
     *
     * @param nickname nome utente che ha richiesto la card
     * @param projectName nome progetto a cui appartiene la card
     * @param cardName nome card richiesta
     * @return messaggio da inviare al client contenente la card richiesta
     */
    @Override
    public Message showCard(String nickname, String projectName, String cardName) {
        Message message = new Message();
        int projectIndex = createdProjects.indexOf(new Project(projectName, null));
        // controllo esistenza progetto
        if (projectIndex == -1) {
            message.setResponse(Response.NONEXISTENT_PROJECT);
            return message;
        }
        Project project = createdProjects.get(projectIndex);
        // controllo appartenenza dell'utente al progetto
        if (!project.getMembers().contains(nickname)) {
            message.setResponse(Response.NONEXISTENT_PROJECT);
            return message;
        }
        int cardIndex = project.getCards().indexOf(new Card(cardName, null));
        // controllo esistenza della carta nel progetto
        if (cardIndex == -1) {
            message.setResponse(Response.NONEXISTENT_CARD);
            return message;
        }
        // scrivo la carta nel messaggio
        message.setResponse(Response.OK);
        message.setCard(project.getCards().get(cardIndex));
        return message;
    }


    /**
     * aggiunge la card con i dettagli forniti al progetto
     * e invia un messaggio nella chat di progetto indicando l'operazione eseguita
     *
     * @param nickname nome utente che ha richiesto l'aggiunta della card
     * @param projectName nome progetto al quale bisogna aggiungere la card
     * @param cardName nome card da aggiungere
     * @param description descrizione card da aggiungere
     * @return messaggio contentente il responso per l'operazione richiesta
     */
    @Override
    public Message addCard(String nickname, String projectName, String cardName, String description) {
        Message message = new Message();
        int projectIndex = createdProjects.indexOf(new Project(projectName, null));
        // controllo esistenza progetto
        if (projectIndex == -1) {
            message.setResponse(Response.NONEXISTENT_PROJECT);
            return message;
        }
        Project project = createdProjects.get(projectIndex);
        // controllo appartenenza dell'utente al progetto
        if (!project.getMembers().contains(nickname)) {
            message.setResponse(Response.NONEXISTENT_PROJECT);
            return message;
        }
        synchronized (createdProjects) {
            // controllo se la carta esiste già
            Card card = new Card(cardName, description);
            if (project.getCards().contains(card)) {
                message.setResponse(Response.CARD_EXISTS);
                return message;
            }
            // la aggiungo al progetto (nella lista delle carte totali e nella lista to_do)
            project.getCards().add(card);
            project.getToDo().add(card);
            server.saveProject(project);
        }
        message.setResponse(Response.OK);
        sendChatMsg(project, nickname + " ha aggiunto la carta " + cardName);
        return message;
    }


    /**
     *  sposta la card, se consentito, da una lista di partenza a una di destinazione
     *  e invia un messaggio nella chat di progetto indicando l'operazione eseguita
     *
     * @param nickname nome utente che ha richiesto lo spostamento della card
     * @param projectName nome progetto di cui fa parte la card
     * @param cardName nome card da spostare
     * @param sourceList lista di partenza da cui spostare la card
     * @param destList lista di destinazione in cui spostare la card
     * @return messaggio contentente il responso per l'operazione richiesta
     */
    @Override
    public Message moveCard(String nickname, String projectName, String cardName, String sourceList, String destList) {
        Message message = new Message();
        int projectIndex = createdProjects.indexOf(new Project(projectName, null));
        // controllo esistenza progetto
        if (projectIndex == -1) {
            message.setResponse(Response.NONEXISTENT_PROJECT);
            return message;
        }
        Project project = createdProjects.get(projectIndex);
        // controllo appartenenza dell'utente al progetto
        if (!project.getMembers().contains(nickname)) {
            message.setResponse(Response.NONEXISTENT_PROJECT);
            return message;
        }
        // prendo il riferimento alla lista di partenza
        ArrayList<Card> sList;
        switch (sourceList) {
            case "todo" : sList = createdProjects.get(projectIndex).getToDo(); break;
            case "inprogress" : sList = createdProjects.get(projectIndex).getInProgress(); break;
            case "toberevised" : sList = createdProjects.get(projectIndex).getToBeRevised(); break;
            case "done" : sList = createdProjects.get(projectIndex).getDone(); break;
            default :
                message.setResponse(Response.NONEXISTENT_LIST);
                return message;
        }
        // prendo il riferimento alla lista di destinazione
        ArrayList<Card> dList;
        switch (destList) {
            case "todo" : dList = createdProjects.get(projectIndex).getToDo(); break;
            case "inprogress" : dList = createdProjects.get(projectIndex).getInProgress(); break;
            case "toberevised" : dList = createdProjects.get(projectIndex).getToBeRevised(); break;
            case "done" : dList = createdProjects.get(projectIndex).getDone(); break;
            default :
                message.setResponse(Response.NONEXISTENT_LIST);
                return message;
        }
        // controllo che lista di partenza e di destinazione non siano uguali
        if (sourceList.equals(destList)) {
            message.setResponse(Response.CARD_EXISTS);
            return message;
        }
        // controllo che siano rispettati i vincoli sullo spostamento
        switch (destList) {
            case "todo" :
                message.setResponse(Response.MOVE_CARD_FORBIDDEN);
                return message;

            case "inprogress" :
                if (sourceList.equals("done")) {
                    message.setResponse(Response.MOVE_CARD_FORBIDDEN);
                    return message;
                }
                break;
            case "toberevised" :
                if (!sourceList.equals("inprogress")) {
                    message.setResponse(Response.MOVE_CARD_FORBIDDEN);
                    return message;
                }
                break;
            case "done" :
                if (sourceList.equals("todo")){
                    message.setResponse(Response.MOVE_CARD_FORBIDDEN);
                    return message;
                }
                break;
            default :
        }
        synchronized (createdProjects) {
            // controllo che la carta da spostare sia effettivamente nella lista di partenza
            int cardIndex = sList.indexOf(new Card(cardName, null));
            if (cardIndex == -1) {
                message.setResponse(Response.NONEXISTENT_CARD);
                return message;
            }
            // sposto la carta da sourceList a destList, e aggiorno la sua history
            Card card = sList.remove(cardIndex);
            card.updateHistory(destList);
            dList.add(card);
            // aggiorno anche nella lista di tutte le carte create
            int cardIndex2 = project.getCards().indexOf(card);
            project.getCards().set(cardIndex2, card);
            server.saveProject(project);
        }
        // ritorno il messaggio per il client
        message.setResponse(Response.OK);
        sendChatMsg(project, nickname + " ha spostato la carta " + cardName +
                " dalla lista " + sourceList + " alla lista " + destList + ".");
        return message;
    }


    /**
     * cancella il progetto, controllando che tutte le card siano in done
     * e invia un messaggio nella chat di progetto indicando l'operazione eseguita
     *
     * @param nickname nome utente che ha richiesto la cancellazione del progetto
     * @param projectName nome progetto da cancellare
     * @return messaggio contentente il responso per l'operazione richiesta
     */
    @Override
    public Message cancelProject(String nickname, String projectName) {
        Message message = new Message();
        int projectIndex = createdProjects.indexOf(new Project(projectName, null));
        // controllo esistenza progetto
        if (projectIndex == -1) {
            message.setResponse(Response.NONEXISTENT_PROJECT);
            return message;
        }
        Project project = createdProjects.get(projectIndex);
        // controllo appartenenza dell'utente al progetto
        if (!project.getMembers().contains(nickname)) {
            message.setResponse(Response.NONEXISTENT_PROJECT);
            return message;
        }
        // controllo che tutte le carte siano nella lista DONE
        synchronized (createdProjects) {
            boolean ok = true;
            for (Card card : project.getCards()) {
                if (!card.getPosition().equals("DONE")) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                message.setResponse(Response.DELETE_FORBIDDEN);
                return message;
            }
            unBindChatAddress(project.getChatAddress());
            createdProjects.remove(project);
            server.deleteProject(project);
        }
        message.setResponse(Response.OK);
        server.updateClientChats();
        return message;
    }

    /**
     * associa al progetto un indirizzo multicast per la chat
     *
     * @param project nome progetto da associare all'indirizzo della chat
     * @return true se riesce ad associare l'indirizzo, altrimenti false
     */
    public synchronized boolean bindChatAddress(Project project) {
        //se l'insieme è vuoto non ci sono indirizzi disponibili
        if (availableAddresses.isEmpty())
            return false;

        try {
            project.setChatAddress(InetAddress.getByName(availableAddresses.pollFirst()));
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        return true;
    }

    /**
     * aggiunge di nuovo un indirizzo precedentemente associato ad un progetto all'insieme
     * degli indirizzi disponibili. Metodo utilizzato in seguito alla cancellazione di un progetto.
     * @param address indirizzo che adesso si può riutilizzare
     */
    //se devo cancellare un progetto e la sua relativa chat
    //devo rimettere il suo indirizzo nuovamente a disposizione aggiungendolo alla lista degli
    //indirizzi disopnibili
    public synchronized void unBindChatAddress(InetAddress address){
        availableAddresses.add(address.getHostName());
    }

    /**
     * metodo privato utilizzato per l'invio di un messaggio da parte del servizio
     * alla chat di progetto, in seguito ad un'operazione che ha modificato lo stato del progetto
     *
     * @param project nome progetto della chat in cui inviare il messaggio
     * @param message messaggio da inviare
     */
    private void sendChatMsg(Project project, String message) {
        String chatMsg = "Messaggio da WORTH: " + "\"" + message + "\"";
        byte[] buf = chatMsg.getBytes();
        DatagramPacket packet = new DatagramPacket(buf, buf.length, project.getChatAddress(), chatsPort);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}

