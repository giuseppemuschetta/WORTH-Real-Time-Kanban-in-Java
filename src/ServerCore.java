import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RemoteObject;
import java.rmi.server.UnicastRemoteObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author Giuseppe Muschetta
 */
public class ServerCore extends RemoteObject implements ServerInterface {

    /** threadpool usato per gestire le richieste dei client connessi */
    private final ExecutorService pool;
    /** istanza del servizio worth contenente tutte le funzionalità */
    private final WorthCore service;
    /** lista degli stub dei clients registrati per le callbacks */
    private final ArrayList<ClientInterface> callbackClients;
    /** porta server socket */
    private final int portTCP;
    /** porta servizio di registry */
    private final int portRegistry;

    /** mapper usato per la serializzazione/deserializzazione */
    private final ObjectMapper mapper;
    /** stringa corrispondente al path dove salvare i dati */
    private final String saveFolder;
    /** nome file che conserva i dati degli utenti registrati al servizio */
    private final String usersFilename;
    /** nome file che conserva i dati dei membri di un progetto */
    private final String membersFilename;

    public ServerCore(int portTCP, int portRegistry){
        pool = Executors.newCachedThreadPool();
        service = new WorthCore(this);
        callbackClients = new ArrayList<>();
        this.portTCP = portTCP;
        this.portRegistry = portRegistry;
        mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setDateFormat(new SimpleDateFormat("dd-MMM-yy"));
        saveFolder = "res";
        usersFilename = "users.json";
        membersFilename = "members.json";
    }

    /**
     * metodo che avvia il server.
     * Il server accetta le richieste di connessione in entrata e delega la gestione del
     * client ad un threadpool che esegue il task ServerThread che si occupa dell'intera
     * comunicazione con il client.
     */
    public void begin(){
        //loadingResources() carica un file generale per tutti glli utenti registrati
        //ogni progetto è rappresentato da una directory /res/backup/<nome_progetto>
        //contenente un file .json per i membri appartenenti al progetto e un altro file .json
        //per ogni card o arttività creata nel progetto
        loadingResources();
        try{
            exportingRMIobject();
            ServerSocket ss = new ServerSocket(portTCP);
            System.out.println("Server in attesa di connessioni sulla porta "+portTCP);
            while(true){
                Socket clientSocket = ss.accept();
                pool.submit(new ServerThread(clientSocket, service));
            }
        }catch(IOException e){
            e.printStackTrace();
        }
    }

    /**
     * registra l'utente al servizio con nickname e password forniti
     *
     * @param nickname nome utente da registrare
     * @param password password da associare all'utente
     * @return responso per l'operazione richiesta
     * @throws RemoteException -
     */
    @Override
    public Response register(String nickname, String password) throws RemoteException {
        User user = null;
        try {
            //creo il nuovo utente da registrare facendo l'hash della password fornita
            user = new User(nickname, HashPassword.getSaltedHash(password));
        } catch (Exception e) {
            e.printStackTrace();
        }
        //sincronizzo sulla lista di utenti registrati perchè in un dato momento ci possono essere
        //diversi thread ServerThread che per soddisfare le richieste del client possono invocare metodi
        //della classe WorthCore i quali modificano lo stato di questa lista
        synchronized (service.getRegisteredUsers()) {
            //l'utente esiste già
            if (service.getRegisteredUsers().contains(user)) {
                return Response.USER_EXISTS;
            }
            //aggiungo l'utente alla lista degli utenti registrati
            service.getRegisteredUsers().add(user);
            saveUsers();
        }
        //arrivati qui c'è stato un cambiamento di stato degli utenti registrati al servizio,
        //faccio la update per innescare le callbacks ai clients registrati
        updateClientUsers();
        return Response.OK;
    }

    /**
     * registra il client per le callbacks
     *
     * @param clientStub stub/proxy corrispondente al riferimento remoto dell'oggetto client
     *                   utilizzato dal server per le callbacks
     * @throws RemoteException -
     */
    @Override
    public synchronized void registerForCallback(ClientInterface clientStub) throws RemoteException {
        if (!callbackClients.contains(clientStub)) {
            callbackClients.add(clientStub);
            //faccio le callback perchè questo metodo viene invocato da remoto
            //dal client subito dopo la procedura di login
            //in questo modo tutti gli utenti riceveranno l'aggiornamento che un utente è online
            //l'utente che ha fatto il login riceverà la lista di tutti gli utenti registrati
            //e la lista delle chat dei progetti di cui è membro
            updateClientUsers();
            updateClientChats();
        }
    }

    /**
     * annulla la registrazione del client per le callbacks
     *
     * @param clientStub stub/proxy corrispondente al riferimento remoto dell'oggetto client
     *                   utilizzato dal server per le callbacks
     * @throws RemoteException -
     */
    @Override
    public synchronized void unregisterForCallback(ClientInterface clientStub) throws RemoteException {
        callbackClients.remove(clientStub);
        //l'update vero e proprio della callback lo faccio dentro alla classe Worthcore nel metodo logout
        //perchè se un client si chiude con ctrl-c anzichè effettuare correttamente il logout
        //l'update agli altri client non perviene.
    }


    /** scrive il file degli utenti registrati */
    public void saveUsers() {
        try {
            File userFile = new File(saveFolder + File.separator + usersFilename);
            //mapper.writeValue(userFile, service.getRegisteredUsers());
            writeFile(userFile.toString(), service.getRegisteredUsers());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    /**
     * utility usata dalla classe WorthCore
     * scrive i file di un progetto nella directory relativa
     */
    public void saveProject(Project project) {
        File projectDir = new File(saveFolder + File.separator + project.getName());
        File membersFile = new File(projectDir + File.separator + membersFilename);
        try {
            if (!projectDir.exists()) {
                projectDir.mkdir();
                membersFile.createNewFile();
                //mapper.writeValue(membersFile, project.getMembers());
                writeFile(membersFile.toString(), project.getMembers());
            } else {
                //se la directory del progetto esiste gia' aggiorno membri e card
                //mapper.writeValue(membersFile, project.getMembers());
                writeFile(membersFile.toString(), project.getMembers());
                for (Card card : project.getCards()) {
                    File cardFile = new File(projectDir + File.separator + card.getName() + ".json");
                    //mapper.writeValue(cardFile, card);
                    writeFile(cardFile.toString(), card);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * utility usata dalla classe WorthCore
     * cancella i dati di un progetto
     */
    public void deleteProject(Project project){
        String projectPath = saveFolder + File.separator + project.getName();
        deleteDirectory(projectPath);
    }

    /**
     * effettua il caricamento dei dati degli utenti e dei progetti
     * salvati nella directory usata per la persistenza dei dati
     */
    void loadingResources() {
        //questo metodo utilizza i metodi ausiliari writeFile e readFile autoesplicativi
        File backupDir = new File(saveFolder);
        File userFile = new File(backupDir + File.separator + usersFilename);
        if (!backupDir.exists()) {
            backupDir.mkdir();
        }
        try {
            //se il file degli utenti registrati non esiste lo creo
            if (!userFile.exists()) {
                userFile.createNewFile();
                //mapper.writeValue(userFile, service.getRegisteredUsers());
                writeFile(userFile.toString(), service.getRegisteredUsers());
            } else { //altrimenti leggo da quello esistente
                //User[] users = mapper.readValue(userFile, User[].class);
                User[] users = mapper.reader()
                        .forType(new TypeReference<User[]>() {
                        })
                        .readValue(readFile(userFile.toString()).getBytes(StandardCharsets.UTF_8));
                for (User user : users) {
                    user.setOnline(false);
                    service.getRegisteredUsers().add(user);
                }
            }
            //leggo i progetti
            String[] files = backupDir.list();
            assert files != null;
            //con un ciclo for mi ricostruisco lo stato persistente del server
            for (String filename : files) {
                File projectDirectory = new File(saveFolder + File.separator + filename);
                if (projectDirectory.isDirectory()) {

                    Project project = new Project(projectDirectory.getName());
                    // leggo i membri del progetto oppure creo il file se non esiste
                    String projectPath = saveFolder + File.separator + projectDirectory.getName();
                    File membersFile = new File(projectPath + File.separator + membersFilename);
                    //String[] members = mapper.readValue(membersFile, String[].class);
                    String[] members = mapper.reader()
                            .forType(new TypeReference<String[]>() {
                            })
                            .readValue(readFile(membersFile.toString()).getBytes(StandardCharsets.UTF_8));

                    for (String member : members) {
                        project.getMembers().add(member);
                    }
                    // leggo le card del progetto
                    String[] cardfiles = projectDirectory.list();
                    assert cardfiles != null;
                    for (String cardfile : cardfiles) {
                        if (!cardfile.equals(membersFilename)) {
                            File cardFile = new File(projectPath + File.separator + cardfile);
                            //Card card = mapper.readValue(cardFile, Card.class);
                            Card card = mapper.reader()
                                    .forType(new TypeReference<Card>() {
                                    })
                                    .readValue(readFile(cardFile.toString()).getBytes(StandardCharsets.UTF_8));

                            //prendo da ogni card l'ultima lista in cui si trovava
                            project.parseList(card.getPosition()).add(card);
                            project.getCards().add(card);
                        }
                    }
                    //all'avvio del server carico i progetti e assegno nuovi indirizzi di chat ad ognuno
                    //ogni progetto ha la sua chat multicast con il suo proprio indirizzo IP di chat
                    //ad ogni avvio del server riassegno gli indirizzi ai progetti (e quindi alla sua chat)
                    service.bindChatAddress(project);
                    service.getCreatedProjects().add(project);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** esportazione oggetto RMI */
    private void exportingRMIobject() throws RemoteException {
        //esporto l'oggetto this per l'invocazione dei metodi remoti da parte del client
        ServerInterface stub = (ServerInterface) UnicastRemoteObject.exportObject(this, 0);
        Registry registry = LocateRegistry.createRegistry(portRegistry);
        registry.rebind("SERVER-WORTH", stub);
        System.out.println("Server: servizio di registry pronto sulla porta " + portRegistry);
    }

    /**
     *  Aggiorna le liste locali di chat degli users/clients registrati per le callbacks,
     *  in seguito ad un'operazione che ha cambiato lo stato dei progetti dell'utente.
     *  Gli eventi che causano un cambiamento di stato nei progetti dell'utente sono:
     *  - l'utente viene aggiunto ad un progetto
     *  - l'utente viene cancellato un progetto in seguito all'operazione cancel_project.
     *
     *  Il metodo utilizza la lista degli stub e su ognuno invoca i metodi dell'interfaccia
     *  remota ClientInterface che lo notificano dei cambiamenti.
     *
     *  Se il client si disconnette senza preavviso, la callback sul relativo stub solleverà
     *  un'eccezione che viene risolta nel blocco catch eliminando il riferimento dello stub dalla
     *  lista dei client registrati per le callback
     */
    public void updateClientChats() {
        int i = 0;
        while (i < callbackClients.size()) {
            try {
                callbackClients.get(i).notifyChatsEvent(service.getCreatedProjects());
            } catch (Exception e){
                if(callbackClients.remove(callbackClients.get(i)))
                    continue;
            }
            i++;
        }
    }


    /**
     * aggiorna le liste locali di utenti registati degli users/clients registrati per le
     * callbacks, in seguito ad un'operazione che ha cambiato lo stato degli utenti registrati al servizio.
     * Gli eventi che causano un cambiamento di stato negli utenti registrati sono: registrazione,
     * login e logout di un utente.
     *
     * Il metodo utilizza la lista degli stub e su ognuno invoca i metodi dell'interfaccia
     * remota ClientInterface che lo nificano dei cambiamenti
     *
     * Se il client si disconnette senza preavviso, la callback sul relativo stub solleverà
     * un'eccezione che viene risolta nel blocco catch eliminando il riferimento dello stub dalla
     * lista dei client registrati per le callback
     */
    public void updateClientUsers() {
        int i = 0;
        while (i < callbackClients.size()) {
            try {
                callbackClients.get(i).notifyUserEvent(service.getRegisteredUsers());
            } catch (Exception e){
                if(callbackClients.remove(callbackClients.get(i)))
                    continue;
            }
            i++;
        }
    }

    /**
     * cancella ricorsivamente una directory
     *
     * @param filename stringa corrispondente alla directory da cancellare
     */
    private void deleteDirectory(String filename) {
        File file = new File(filename);
        if (file.isDirectory()) {
            String[] files = file.list();
            assert files != null;
            for (String newFilename : files) {
                deleteDirectory(filename + File.separator + newFilename);
            }
        }
        file.delete();
    }

    /**
     * scrive un oggetto in un file nel path indicato, convertendolo prima in json
     *
     * @param pathName path in cui creare il file
     * @param objToWrite oggetto da convertire in json e scrivere
     * @throws IOException errore nelle operazioni di scrittura nel canale
     */
    private void writeFile(String pathName, Object objToWrite) throws IOException {
        Path path = Paths.get(pathName);
        FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        byte[] arrayDiByte = mapper.writeValueAsString(objToWrite).getBytes();
        ByteBuffer byteBuffer = ByteBuffer.wrap(arrayDiByte);
        while (byteBuffer.hasRemaining())
            fileChannel.write(byteBuffer);
    }

    /**
     * legge il file nel path indicato
     *
     * @param filename path in cui si trova il file da leggere
     * @return stringa letta dal file
     * @throws IOException errore nelle operazioni di lettura dal canale
     */
    private String readFile(String filename) throws IOException {
        Path path = Paths.get(filename);
        FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.READ);
        ByteBuffer byteBuffer = ByteBuffer.allocate(8192);
        StringBuilder stringBuilder = new StringBuilder();
        while (fileChannel.read(byteBuffer) != -1) {
            byteBuffer.flip();
            while (byteBuffer.hasRemaining()) {
                stringBuilder.append(StandardCharsets.UTF_8.decode(byteBuffer).toString());
            }
            byteBuffer.clear();
        }
        return stringBuilder.toString();
    }

}

