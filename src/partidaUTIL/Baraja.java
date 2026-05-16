package partidaUTIL;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Clase que representa una baraja completa de las cartas utilizadas en Tokaleda
 * Cards Game. Es utilizada en los distintos modos de juego (Pescaito, Yusa)
 * tanto en modo Online como en modo Offline.
 *
 * La baraja se genera automáticamente al crear la instancia de esta clase,
 * incluyendo todas las combinaciones de palos, números e imágenes asociadas a
 * cada una de ellas.
 *
 * Cada carta contiene:
 * <ul>
 * <li> Un palo (CORONAS, CORAZONES, BALANZAS, DIANAS)</li>
 * <li> Un número del 1 al 12</li>
 * <li> La ruta a la imagen PNG asociada a dicha carta en el proyecto</li>
 * </ul>
 *
 * La baraja permite:
 * <ul>
 * <li> Barajar aleatoriamente las cartas</li>
 * <li> Robar cartas de la parte superior</li>
 * <li> Consultar cuántas cartas quedan en la baraja</li>
 * <li> Obtener la lista completa de cartas restantes</li>
 * </ul>
 *
 * @author Javier Coronilla Castellano
 */
public class Baraja {

    /**
     * Lista interna que contiene todas las cartas de la baraja. Se inicializa
     * en el constructor mediante {@link #generarBaraja()}.
     */
    private List<Carta> cartas = new ArrayList<>();

    /**
     * Crea una baraja nueva y genera automáticamente todas las cartas.
     * IMPORTANTE: La baraja NO se baraja por defecto; para ello debe llamarse a
     * {@link #barajar()}.
     */
    public Baraja() {
        generarBaraja();
    }

    /**
     * Genera las 48 cartas de la baraja para jugar (4 palos x 12 números = 48
     * cartas). Cada carta incluye la ruta a su imagen correspondiente dentro
     * del proyecto.
     *
     * Las rutas siguen el formato:
     * <pre>
     *      /ui/graphicResources/cartas/{palo}_{numero}.png
     * </pre>
     *
     * Este método solo se ejecuta cuando se quiere construir la baraja.
     */
    private void generarBaraja() {
        for (Carta.Palo palo : Carta.Palo.values()) {
            for (int i = 1; i <= 12; i++) {

                String ruta = "/ui/graphicResources/cartas/"
                        + palo.name().toLowerCase() + "_" + i + ".png";

                cartas.add(new Carta(palo, i, ruta));
            }
        }
    }

    /**
     * Mezcla aleatoriamente todas las cartas de la baraja. Es usado antes de
     * repartir las cartas para repartirlas barajadas.
     */
    public void barajar() {
        Collections.shuffle(cartas);
    }

    /**
     * Roba la carta superior de la baraja.
     *
     * @return La primera carta disponible, o {@code null} si la baraja está
     * vacía.
     */
    public Carta robar() {
        if (cartas.isEmpty()) {
            return null;
        }
        return cartas.remove(0);
    }

    /**
     * Devuelve cuántas cartas quedan en la baraja.
     *
     * @return Número de cartas restantes en la baraja.
     */
    public int size() {
        return cartas.size();
    }

    /**
     * Devuelve la lista completa de cartas restantes en la baraja. La lista
     * devuelta es la lista INTERNA.
     *
     * @return Lista de cartas aún no robadas (dentro de la baraja).
     */
    public List<Carta> getCartasRestantes() {
        return cartas;
    }
}
