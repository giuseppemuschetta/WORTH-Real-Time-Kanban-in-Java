import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.*;
import java.net.Socket;
import java.text.SimpleDateFormat;

/**
 * @author Giuseppe Muschetta
 */
public class ServerThread implements Runnable {

    /** socket utilizzato per la comunicazione con il client */
    private final Socket clientSocket;
    /** nome utente dell'user gestito dal client */
    private String clientUser;
    /** istanza del servizio contenente tutte le funzionalità */
    private final WorthCore service;
    /** flag che indica la fine della comunicazione con il client */
    private boolean done;


    public ServerThread(Socket clientSocket, WorthCore service) {
        this.clientSocket = clientSocket;
        this.service = service;
        done = false;
    }

    /**
     * verifica la corrispondenza della richiesta letta con quelle accettate dal servizio
     * e invoca le funzionalità di quest'ultimo per risolverla.
     * Dopo aver ricevuto il responso lo invia al client tramite il metodo sendToClient.
     * Quando la richiesta è un'operazione di logout viene impostato il flag done a true e viene
     * chiuso il socket di comunicazione con il client
     */
    @Override
    public void run() {
        while (!done) {
            try {
                Message reqMsg = receiveFromClient();
                clientUser = reqMsg.getNickname();
                //a questo punto dall'oggetto "ricostruito" leggiamo la richiesta del client
                Message ansMsg;
                switch (reqMsg.getRequest()) {
                    case LOGIN:
                        ansMsg = service.login(reqMsg.getNickname(), reqMsg.getPassword());
                        break;

                    case LOGOUT:
                        ansMsg = service.logout(reqMsg.getNickname());
                        break;

                    case LIST_ALL_PROJECTS:
                        ansMsg = service.listProjects(reqMsg.getNickname());
                        break;

                    case CREATE_PROJECT:
                        ansMsg = service.createProject(reqMsg.getNickname(), reqMsg.getProjectName());
                        break;

                    case ADD_MEMBER:
                        ansMsg = service.addMember(reqMsg.getNickname(), reqMsg.getProjectName(),
                                reqMsg.getNewMember());
                        break;

                    case SHOW_ALL_MEMBERS:
                        ansMsg = service.showMembers(reqMsg.getNickname(), reqMsg.getProjectName());
                        break;

                    case SHOW_ALL_CARDS:
                        ansMsg = service.showCards(reqMsg.getNickname(), reqMsg.getProjectName());
                        break;

                    case SHOW_CARD:
                        ansMsg = service.showCard(reqMsg.getNickname(), reqMsg.getProjectName(),
                                reqMsg.getCardName());
                        break;

                    case ADD_CARD:
                        ansMsg = service.addCard(reqMsg.getNickname(), reqMsg.getProjectName(),
                                reqMsg.getCardName(), reqMsg.getDescription());
                        break;

                    case MOVE_CARD:
                        ansMsg = service.moveCard(reqMsg.getNickname(), reqMsg.getProjectName(),
                                reqMsg.getCardName(), reqMsg.getSourceList(),
                                reqMsg.getDestList());
                        break;

                    case CANCEL_PROJECT:
                        ansMsg = service.cancelProject(reqMsg.getNickname(), reqMsg.getProjectName());
                        break;

                    default:
                        throw new IllegalArgumentException("Bad request: " + reqMsg.getRequest());
                }

                sendToClient(ansMsg);
                if (reqMsg.getRequest() == Request.LOGOUT) {
                    done = true;
                    clientSocket.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * legge dallo stream associato al socket i byte inviati dal client e li deserializza in un oggetto di tipo
     * Message contenente i campi della richiesta
     * @return messaggio contenente la richiesta del client
     * @throws IOException -
     */
    private Message receiveFromClient() throws IOException {
        Message message = null;
        try {
            DataInputStream inStream = new DataInputStream(
                    new BufferedInputStream(clientSocket.getInputStream()));

            //leggo dal socket la dimensione dell'oggetto
            int dim = inStream.readInt();
            byte[] arrayDiByte = new byte[dim];
            //leggere dal socket l'oggetto in .json
            inStream.readFully(arrayDiByte);
            //FINISCO DI LEGGERE

            //INIZIA LA DESERIALIZZAZIONE
            //ora ho l'oggetto dentro l'array di byte che possiamo deserializzare con Jackson
            ObjectMapper mapper = new ObjectMapper();
            //rende visibile all'ObjectMapper gli attributi privati della classe dell'oggetto da deserializzare
            mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
            // configura la formattazione della data
            mapper.setDateFormat(new SimpleDateFormat("dd-MM-yy"));
            message = mapper.reader()
                    .forType(new TypeReference<Message>() {
                    })
                    .readValue(arrayDiByte);
        } catch (EOFException e) {
            service.logout(clientUser);
            clientSocket.close();
            done = true;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return message;
    }

    /**
     * serializza il responso dell'operazione richiesta dal client e scrive i byte sullo stream associato al socket
     * @param message messaggio contenente il responso dell'operazione richiesta
     * @throws IOException -
     */
    private void sendToClient(Message message) throws IOException {
        DataOutputStream outStream = new DataOutputStream(
                new BufferedOutputStream(clientSocket.getOutputStream()));
        ObjectMapper tiSerializzo = new ObjectMapper();
        tiSerializzo.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        tiSerializzo.setDateFormat(new SimpleDateFormat("dd-MMM-yy"));
        tiSerializzo.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        byte[] arrayDiBait = tiSerializzo.writeValueAsString(message).getBytes();
        outStream.writeInt(arrayDiBait.length);
        outStream.write(arrayDiBait);
        outStream.flush();
    }
}
