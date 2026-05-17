package partidaUTIL;

/**
 * Clase que representa una carta individual de Tokaleda Cards Game.
 *<p>
 * Dicha carta está compuesta por un número, un palo y una ruta hacia su imagen
 * correspondiente.
 *</p>
 * 
 * <p>
 * Esta clase es inmutable en cuanto a su contenido lógico: una vez creada, el
 * palo, número y ruta de la imagen no cambian.
 *</p>
 * 
 * <p>
 * Se utiliza tanto en los modos Online como en los modos Offline para construir
 * la baraja, repartir las cartas y mostrar las imágenes en la interfaz con las
 * que se juegan a los juegos.
 *</p>
 * @author Javier Coronilla Castellano.
 */
public class Carta {

    /**
     * Enumeración de los palos disponibles en el juego.
     *
     * <p>
     * Cada palo representa una temática visual distinta dentro del diseño
     * artístico del juego.
     * </p>
     */
    public enum Palo {
        CORONAS, CORAZONES, BALANZAS, DIANAS
    }

    /**
     * Palo al que pertenece la carta.
     * <p>
     * Este valor es inmutable y se establece únicamente en el constructor.
     * </p>
     */
    private Palo palo;

    /**
     * Número de la carta (entre 1 y 12).
     * <p>
     * Este valor también es inmutable.
     * </p>
     */
    private int numero;

    /**
     * Ruta dentro del proyecto a la imagen PNG que representa esta carta.
     * <p>
     * Debe corresponder a un recurso existente para que la interfaz pueda
     * mostrar la carta correctamente.
     * </p>
     */
    private String rutaImagen;

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
     * Devuelve una representación textual de la carta.
     *
     * <p>
     * Este método es útil para depuración, logs y para mostrar cartas en
     * formato texto cuando no se utiliza la interfaz gráfica.
     * </p>
     *
     * @return Cadena con el formato "PALO NUMERO".
     */
    @Override
    public String toString() {
        return palo + " " + numero;
    }
}
