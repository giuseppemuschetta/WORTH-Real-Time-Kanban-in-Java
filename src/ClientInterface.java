import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.ArrayList;

/**
 * @author Giuseppe Muschetta
 */
public interface ClientInterface extends Remote {

    /**
     * Notifica il client in seguito ad un cambiamento di stato degli utenti registrati.
     * Il server invoca il metodo sullo stub del client, ricevuto nel momento della registrazione alle
     * callbacks, passando come parametro la lista aggiornata degli utenti registrati
     *
     * @param registeredUsers lista degli utenti registrati al servizio aggiornata
     * @throws RemoteException -
     */
    void notifyUserEvent(ArrayList<User> registeredUsers) throws RemoteException;

    /**
     * Notifica il client in seguito ad un cambiamento di stato dei progetti.
     * Il server invoca il metodo sullo stub del client, ricevuto nel momento della registrazione alle
     * callbacks, passando come parametro la lista aggiornata dei progetti
     *
     * @param createdProjects lista dei progetti creati nel servizio aggiornata
     * @throws RemoteException -
     */
    void notifyChatsEvent(ArrayList<Project> createdProjects) throws RemoteException;

}

