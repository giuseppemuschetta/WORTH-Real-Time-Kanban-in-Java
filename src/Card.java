import java.io.Serializable;

/**
 * @author Giuseppe Muschetta
 */
public class Card implements Serializable {

    private static final long serialVersionUID = 8443194847660515023L;

    /** nome card */
    private String name;

    /** descrizione card */
    private String description;

    /** storico spostamenti card */
    private String history;

    /** lista corrente card */
    private String position;


    public Card(String name, String description){
        this.name = name;
        this.description = description;
        history = "TODO";
        position = "TODO";
    }

    public Card(){}

    /**
     * @return nome card
     */
    public String getName() {
        return name;
    }

    /**
     * @return storico spostamenti card
     */
    public String getHistory() {
        return history;
    }

    /**
     * aggiorna lo storico degli spostamenti della card concatenando
     * alla stringa delle liste in cui è stata la nuova lista in cui si trova
     *
     * @param newList nuova lista in cui è stata inserita la card
     */
    public void updateHistory(String newList){
        this.history += " -> " + newList.toUpperCase();
        this.position = newList.toUpperCase();
    }

    /**
     * recupera la lista in cui si trova la card in quel momento prendendo
     * l'ultima stringa concatenata alla history
     *
     * @return stringa che indica il nome della lista nella quale si trova la card in quel momento
     */
    public String getPosition(){
        return position;
    }

    /**
     *
     * @return stringa rappresentante la card
     */
    @Override
    public String toString() {
        return "    Name:        " + name + "\n" +
                "    Status:      " + position + "\n" +
                "    Description: " + description;
    }

    /**
     *
     * @param obj oggetto da confrontare con this
     * @return true se i due oggetti sono uguali, false altrimenti
     */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Card))
            return false;
        return this.name.equals(((Card) obj).getName());
    }


}

