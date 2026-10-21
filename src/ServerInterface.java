import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * @author Giuseppe Muschetta
 */
public interface ServerInterface extends Remote {

    /**
     * registra l'utente al servizio con nickname e password forniti
     *
     * @param nickname nome utente da registrare
     * @param password password da associare all'utente
     * @return responso per l'operazione richiesta
     * @throws RemoteException -
     */
    Response register(String nickname, String password) throws RemoteException;

    /**
     * registra il client per le callbacks
     *
     * @param clientStub stub/proxy corrispondente al riferimento remoto dell'oggetto client
     *                   utilizzato dal server per le callbacks
     * @throws RemoteException -
     */
    void registerForCallback(ClientInterface clientStub) throws RemoteException;

    /**
     * deregistra il client per le callbacks
     *
     * @param clientStub stub/proxy corrispondente al riferimento remoto dell'oggetto client
     *                   utilizzato dal server per le callbacks
     * @throws RemoteException -
     */
    void unregisterForCallback(ClientInterface clientStub) throws RemoteException;





}

