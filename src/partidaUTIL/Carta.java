package partidaUTIL;

/**
 * Clase que representa una carta individual de Tokaleda Cards Game. Dicha carta
 * está compuesta por un número, un palo y una ruta hacia su imagen
 * correspondiente.
 *
 * Esta clase es inmutable en cuanto a su contenido lógico: una vez creada, el
 * palo, número y ruta de la imagen no cambian.
 *
 * Se utiliza tanto en los modos Online como en los modos Offline para construir
 * la baraja, repartir las cartas y mostrar las imágenes en la interfaz con las
 * que se juegan a los juegos.
 *
 * @author Javier Coronilla Castellano
 */
public class Carta {

    /**
     * Enum de los palos disponibles en el juego. Cada palo representa una
     * temática en las cartas.
     */
    public enum Palo {
        CORONAS, CORAZONES, BALANZAS, DIANAS
    }

    /**
     * Palo al que pertenece la carta.
     */
    private Palo palo;

    /**
     * Número de la carta (1-12).
     */
    private int numero;

    /**
     * Ruta dentro del proyecto a la imagen PNG que representa a esa carta.
     */
    private String rutaImagen;

    // public static final String RUTA_DORSO = "/ui/graphicResources/cartas/parteTrasera.png";
    /**
     * Crea una nueva carta con un palo, número y ruta a su imagen.
     *
     * @param palo Palo de la carta.
     * @param numero Número de la carta.
     * @param rutaImagen Ruta a imagen de la carta en el proyecto.
     */
    public Carta(Palo palo, int numero, String rutaImagen) {
        this.palo = palo;
        this.numero = numero;
        this.rutaImagen = rutaImagen;
    }

    /**
     * Obtiene el palo de la carta.
     *
     * @return Palo de la carta.
     */
    public Palo getPalo() {
        return palo;
    }

    /**
     * Obtiene el numero de la carta.
     *
     * @return Numero de la carta.
     */
    public int getNumero() {
        return numero;
    }

    /**
     * Obtiene la ruta de la imagen asociada a la carta.
     *
     * @return Ruta de la imagen de la carta.
     */
    public String getRutaImagen() {
        return rutaImagen;
    }

    /**
     * Devuelve una representación textual de la carta. Usado para depuración y
     * logs.
     *
     * @return Cadena con el formato "PALO NUMERO".
     */
    @Override
    public String toString() {
        return palo + " " + numero;
    }
}
