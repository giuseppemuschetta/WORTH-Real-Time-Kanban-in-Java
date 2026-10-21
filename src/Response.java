/**
 * @author Giuseppe Muschetta
 */
public enum Response {      //methods:
    OK,
    USER_EXISTS,            //register
    NOT_REGISTERED,         //login, add membro
    WRONG_PASSWORD,         //login
    ALREADY_LOGGED,         //login
    PROJECT_EXISTS,         //create_projects
    MEMBER_EXISTS,          //create_projects (utente già membro)
    NONEXISTENT_PROJECT,    //create_projects (progetto non esistente in quelli dell'user)
    NONEXISTENT_CARD,       //show_card, move_card
    NONEXISTENT_LIST,       //move_card
    CARD_EXISTS,            //add_card, move_card
    MOVE_CARD_FORBIDDEN,    //move_card
    UNKNOWN_ERROR,          //logout
    DELETE_FORBIDDEN,       //delete_project
    UNABLE_CREATE_PROJECT   //create_project (indirizzi multicast esauriti)
}
