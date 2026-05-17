package ui.audio;

import javafx.scene.media.AudioClip;
import java.util.prefs.Preferences;

/**
 * Gestor centralizado de efectos de sonido del juego.
 *
 * <p>
 * Esta clase controla la reproducción de efectos de sonidos, como:
 * </p>
 * <ul>
 * <li>Sonido de click de botones.</li>
 * <li>Sonido de hover al pasar el ratón.</li>
 * </ul>
 *
 * <p>
 * Funciona como un gestor estático, lo que permite que cualquier parte del
 * programa pueda reproducir efectos sin necesidad de crear instancias.
 * </p>
 *
 * <p>
 * También se encarga de:
 * </p>
 * <ul>
 * <li>Cargar los efectos al iniciar la clase.</li>
 * <li>Persistir el volumen de los efectos en las preferencias del usuario.</li>
 * <li>Aplicar el volumen actual a todos los efectos cargados.</li>
 * </ul>
 * 
 * @author Javier Coronilla Castellano.
 */
public class SoundManager {

    /**
     * Volumen global de los efectos de sonido.
     *
     * <p>
     * Se carga desde {@link Preferences} al iniciar la clase y se actualiza
     * cuando el usuario modifica el volumen desde el menú de opciones.
     * </p>
     */
    private static double volumenEfectos;

    /**
     * Clip de sonido para el efecto de click de botón.
     *
     * <p>
     * Se carga una sola vez en el bloque estático para evitar recargarlo
     * repetidamente, lo que mejora el rendimiento.
     * </p>
     */
    private static AudioClip clickButtonClip;

    /**
     * Clip de sonido para el efecto de hover.
     *
     * <p>
     * Este clip se carga en el segundo bloque estático.
     * </p>
     */
    private static AudioClip hoverClip;

    /**
     * Primer bloque estático encargado de:
     * <ul>
     * <li>Cargar el volumen guardado.</li>
     * <li>Cargar el sonido de click.</li>
     * <li>Aplicar el volumen al clip cargado.</li>
     * </ul>
     *
     * <p>
     * Este bloque se ejecuta automáticamente cuando la clase se carga por
     * primera vez en memoria.
     * </p>
     */
    static {

        // Cargamos las preferencias del usuario.
        Preferences prefs = Preferences.userNodeForPackage(SoundManager.class);

        // Si no existe un volumen guardado, se usa 0.7 como valor por defecto.
        volumenEfectos = prefs.getDouble("volumenEfectos", 0.7);

        // Cargamos el sonido de click.
        clickButtonClip = new AudioClip(SoundManager.class
                .getResource("/ui/audio/efectos/clickButton.wav").toExternalForm());

        // Aplicamos el volumen actual.
        clickButtonClip.setVolume(volumenEfectos);
    }

    /**
     * Segundo bloque estático encargado de:
     * <ul>
     * <li>Volver a cargar el volumen (por seguridad).</li>
     * <li>Cargar el sonido de hover.</li>
     * <li>Aplicar el volumen a ambos clips.</li>
     * </ul>
     */
    static {
        Preferences prefs = Preferences.userNodeForPackage(SoundManager.class);
        volumenEfectos = prefs.getDouble("volumenEfectos", 0.7);

        // Cargamos nuevamente el sonido de click (esto me solucionó algún que otro problema).
        clickButtonClip = new AudioClip(SoundManager.class
                .getResource("/ui/audio/efectos/clickButton.wav").toExternalForm());

        // Cargamos el sonido de hover.
        hoverClip = new AudioClip(SoundManager.class
                .getResource("/ui/audio/efectos/hover.wav").toExternalForm());

        // Aplicamos el volumen actual a ambos clips.
        clickButtonClip.setVolume(volumenEfectos);
        hoverClip.setVolume(volumenEfectos);
    }

    /**
     * Establece el volumen global de los efectos de sonido.
     *
     * <p>
     * Este método:
     * </p>
     * <ul>
     * <li>Actualiza el volumen en memoria.</li>
     * <li>Actualiza el volumen del clip de click.</li>
     * <li>Guarda el volumen en las preferencias del usuario.</li>
     * </ul>
     *
     * @param v Nuevo volumen (0.0 a 1.0).
     */
    public static void setVolumen(double v) {
        volumenEfectos = v;

        // actualizamos volumen del clip de click.
        clickButtonClip.setVolume(v);

        // Guardamos el volumen en las preferencias.
        Preferences prefs = Preferences.userNodeForPackage(SoundManager.class);
        prefs.putDouble("volumenEfectos", v);
    }

    /**
     * Devuelve el volumen actual de los efectos de sonido.
     *
     * @return Volumen entre 0.0 y 1.0.
     */
    public static double getVolumen() {
        return volumenEfectos;
    }

    /**
     * Reproduce el sonido de hover.
     *
     * <p>
     * A diferencia del sonido de click, este método crea un nuevo
     * {@link AudioClip} cada vez que se llama. Esto se hace porque los sonidos
     * de hover pueden solaparse si el usuario mueve el ratón rápidamente entre
     * botones.
     * </p>
     */
    public static void hover() {

        // Creamos un nuevo clip para permitir solapamiento.
        AudioClip hoverClip = new AudioClip(SoundManager.class
                .getResource("/ui/audio/efectos/hover.wav").toExternalForm());
        hoverClip.setVolume(volumenEfectos);
        hoverClip.play();
    }

    /**
     * Reproduce el sonido de click de botón.
     *
     * <p>
     * Este sonido se carga una sola vez en memoria y se reproduce
     * instantáneamente, lo que mejora el rendimiento.
     * </p>
     */
    public static void clickButton() {
        clickButtonClip.play();
    }
}
