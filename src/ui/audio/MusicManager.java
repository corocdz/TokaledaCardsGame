package ui.audio;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import java.util.prefs.Preferences;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.util.Duration;

/**
 * Gestor centralizado de la música del juego.
 *
 * <p>
 * Esta clase se encarga de reproducir, cambiar, suavizar transiciones (fade-in
 * / fade-out) y persistir el volumen de la música en el juego.
 * </p>
 *
 * <p>
 * No se crean instancias, sino que todos los métodos y atributos son estáticos
 * para que cualquier parte del programa pueda acceder a la música global.
 * </p>
 *
 * <p>
 * También almacena el tipo de música que está sonando actualmente para evitar
 * reinicios innecesarios (por ejemplo, no reiniciar la música del menú si ya
 * está sonando).
 * </p>
 *
 *
 * @author Javier Coronilla Castellano.
 */
public class MusicManager {

    /**
     * Reproductor multimedia de JavaFX encargado de reproducir la música.
     */
    private static MediaPlayer player;

    /**
     * Volumen actual de la música.
     *
     * <p>
     * Se carga desde {@link Preferences} al iniciar la clase y se actualiza
     * cada vez que el usuario cambia el volumen.
     * </p>
     */
    private static double volumenMusica;

    /**
     * Identificador del tipo de música que está sonando actualmente.
     *
     * <p>
     * Se usa para evitar reiniciar música que ya está sonando. Ejemplos:
     * <ul>
     * <li>"menu"</li>
     * <li>"partida"</li>
     * </ul>
     * </p>
     */
    private static String musicaActual = "";

    /**
     * Carga el volumen guardado en las preferencias del usuario.
     *
     * <p>
     * Este bloque se ejecuta automáticamente cuando la clase se carga por
     * primera vez en memoria.
     * </p>
     */
    static {
        Preferences prefs = Preferences.userNodeForPackage(MusicManager.class);
        // Si no existe un volumen guardado, se usa 0.5 como valor por defecto.
        volumenMusica = prefs.getDouble("volumenMusica", 0.5);
    }

    /**
     * Reproduce una pista de música aplicando un efecto de transición suave.
     *
     * <p>
     * El proceso es:
     * </p>
     * <ol>
     * <li>Aplicar fade-out a la música actual (si existe).</li>
     * <li>Cargar la nueva pista.</li>
     * <li>Reproducirla desde volumen 0.</li>
     * <li>Aplicar fade-in hasta el volumen configurado.</li>
     * </ol>
     *
     * @param ruta Ruta del archivo de música dentro del proyecto.
     */
    public static void reproducirMusica(String ruta) {

        // Aplicamos fade-out antes de cambiar de música.
        fadeOut(() -> {
            try {
                // Cargamos la nueva pista desde los recursos del proyecto.
                Media media = new Media(MusicManager.class.getResource(ruta).toExternalForm());
                player = new MediaPlayer(media);

                // La música se reproduce en bucle infinito.
                player.setCycleCount(MediaPlayer.INDEFINITE);
                player.setVolume(0); // empezamos en 0 para el fade-in

                // Comenzamos reproducción
                player.play();

                // Aplicamos fade-in hasta volumen configurado
                fadeIn(player, volumenMusica, 1500);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 800); // fade-out de 800ms antes de cambiar
    }

    /**
     * Reproduce la música del menú principal.
     *
     * <p>
     * Si ya está sonando la música del menú, no se reinicia.
     * </p>
     */
    public static void playMenuMusic() {
        if (isPlaying("menu")) {
            return; // evitar reiniciar
        }
        musicaActual = "menu";
        reproducirMusica("/ui/audio/musica/musicaGeneral.wav");
    }

    /**
     * Reproduce la música de la partida.
     *
     * <p>
     * Si ya está sonando la música de partida, no se reinicia.
     * </p>
     */
    public static void playPartidaMusic() {
        if (isPlaying("partida")) {
            return; // evitar reiniciar
        }
        musicaActual = "partida";
        reproducirMusica("/ui/audio/musica/musicaPartida.wav");
    }

    /**
     * Establece el volumen de la música y lo guarda en las preferencias.
     *
     * @param v Nuevo volumen (0.0 a 1.0).
     */
    public static void setVolumen(double v) {
        volumenMusica = v;

        // Si hay reproductor activo, acutalizamos su volumen.
        if (player != null) {
            player.setVolume(v);
        }

        // Guardamos volumen en preferencias del usuario.
        Preferences prefs = Preferences.userNodeForPackage(MusicManager.class);
        prefs.putDouble("volumenMusica", v);
    }

    /**
     * Devuelve el volumen actual de la música.
     *
     * @return Volumen entre 0.0 y 1.0.
     */
    public static double getVolumen() {
        return volumenMusica;
    }

    /**
     * Aplica un efecto de fade-in al reproductor.
     *
     * @param player Reproductor al que aplicar el efecto.
     * @param targetVolume Volumen final deseado.
     * @param durationMs Duración del efecto en milisegundos.
     */
    private static void fadeIn(MediaPlayer player, double targetVolume, int durationMs) {

        // Volumen puesto a 0 para efecto de fade in.
        player.setVolume(0);

        // Creamos una animación que incrementa el volumen progresivamente hasta nuestro volumen almacenado en preferencias del usuario.
        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(player.volumeProperty(), 0)),
                new KeyFrame(Duration.millis(durationMs), new KeyValue(player.volumeProperty(), targetVolume))
        );
        timeline.play();
    }

    /**
     * Aplica un efecto de fade-out a la música actual.
     *
     * <p>
     * Cuando el fade-out termina, se ejecuta la acción indicada en
     * {@code afterFade}, normalmente para cargar la siguiente pista.
     * </p>
     *
     * @param afterFade Acción a ejecutar tras el fade-out.
     * @param durationMs Duración del efecto en milisegundos.
     */
    private static void fadeOut(Runnable afterFade, int durationMs) {

        // Si no hay música sonando, ejecutamos directamente la acción.
        if (player == null) {
            if (afterFade != null) {
                afterFade.run();
            }
            return;
        }

        // Animación que reduce el volumen hasta 0.
        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(player.volumeProperty(), player.getVolume())),
                new KeyFrame(Duration.millis(durationMs), new KeyValue(player.volumeProperty(), 0))
        );

        // Cuando termina el fade-out
        timeline.setOnFinished(e -> {
            player.stop(); // Detenemos música actal.
            if (afterFade != null) {
                afterFade.run(); // Ejecutamos la acción posterior.
            }
        });

        timeline.play();
    }

    /**
     * Indica si la música actual corresponde al tipo indicado.
     *
     * @param tipo Identificador del tipo de música ("menu", "partida").
     * @return {@code true} si está sonando ese tipo de música.
     */
    public static boolean isPlaying(String tipo) {
        return musicaActual.equals(tipo);
    }

}
