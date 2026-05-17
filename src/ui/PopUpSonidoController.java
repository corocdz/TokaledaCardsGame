package ui;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Slider;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import ui.audio.ButtonSound;
import ui.audio.MusicManager;
import ui.audio.SoundManager;

/**
 * Controlador del popUp de sonido.
 *
 * <p>
 * Este popUp permite al usuario ajustar el volumen de la música y de los
 * efectos de sonido del juego mediante dos {@link Slider}. Además, gestiona el
 * cierre del popUp y aplica pequeñas animaciones y sonidos al botón de cierre.
 * </p>
 *
 * <p>
 * Los cambios de volumen se aplican en tiempo real a través de
 * {@link MusicManager} y {@link SoundManager}.
 * </p>
 * 
 * @author Javier Coronilla Castellano.
 */
public class PopUpSonidoController {

    // -------------------------------
    // ELEMENTOS GRÁFICOS
    // -------------------------------
    /**
     * Slider para controlar el volumen de la música de fondo.
     */
    @FXML
    private Slider sliderMusica;

    /**
     * Slider para controlar el volumen de los efectos de sonido de la
     * aplicación.
     */
    @FXML
    private Slider sliderEfectos;

    /**
     * Botón para cerrar el popUp de sonido.
     */
    @FXML
    private Button btnCerrar;

    /**
     * Capa raíz del popUp de sonido.
     */
    @FXML
    private StackPane rootSonido;

    /**
     * Inicializa el popUp de sonido tras cargarse el FXML.
     *
     * <p>
     * Este método:
     * </p>
     * <ul>
     * <li>Sincroniza los sliders con el volumen actual de música y
     * efectos.</li>
     * <li>Registra listeners para actualizar el volumen en tiempo real.</li>
     * <li>Aplica animación y sonido al botón de cierre.</li>
     * <li>Configura la acción de cierre del popUp.</li>
     * </ul>
     */
    @FXML
    private void initialize() {

        // Establece valores iniciales de los sliders según el volumen actual.
        sliderMusica.setValue(MusicManager.getVolumen());
        sliderEfectos.setValue(SoundManager.getVolumen());
        
        // Cuando cambia el slider de música, actualizamos el volumen de MusicManager.
        sliderMusica.valueProperty().addListener((obs, oldV, newV) -> MusicManager.setVolumen(newV.doubleValue()));
        
        // Cuando cambia el slider de efectos, actualizamos el volumen de SoundManager.
        sliderEfectos.valueProperty().addListener((obs, oldV, newV) -> SoundManager.setVolumen(newV.doubleValue()));

        // Animación y efecto de sonido para botón cerrar.
        Animaciones.animarBoton(btnCerrar);
        ButtonSound.activarClick(btnCerrar);
        
        // Acción del botón: eliminar el popUp de su contenedor padre
        btnCerrar.setOnAction(e -> ((Pane) rootSonido.getParent()).getChildren().remove(rootSonido));
    }
}
