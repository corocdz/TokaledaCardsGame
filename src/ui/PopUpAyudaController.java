package ui;

import javafx.animation.*;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import ui.audio.ButtonSound;

/**
 * Controlador encargado de gestionar el popUp de ayuda mostrado en la interfaz.
 *
 * <p>
 * Este popUp se utiliza para mostrar información útil al usuario sobre cómo
 * jugar, modos disponibles y funcionamiento general del juego.
 * </p>
 *
 * <h2>El controlador se encarga de:</h2>
 * <ul>
 * <li>Mostrar el mensaje de ayuda recibido.</li>
 * <li>Aplicar animaciones de entrada y salida.</li>
 * <li>Gestionar el cierre del popUp.</li>
 * <li>Activar efectos de sonido y hover en el botón.</li>
 * </ul>
 */
public class PopUpAyudaController {

    // -------------------------------
    // ELEMENTOS GRÁFICOS
    // -------------------------------
    /**
     * Capa raíz.
     */
    @FXML
    private StackPane rootAyuda;

    /**
     * Contenido donde saldrá el mensaje.
     */
    @FXML
    private VBox box;

    /**
     * Etiqueta de título.
     */
    @FXML
    private Label lblTitulo;

    /**
     * Etiqueta de mensaje.
     */
    @FXML
    private Label lblMensaje;

    /**
     * Botón de cerrar popUp.
     */
    @FXML
    private Button btnCerrar;

    /**
     * Inicializa el popUp tras cargarse el FXML.
     *
     * <p>
     * Configura:
     * </p>
     * <ul>
     * <li>La animación de entrada (fade + zoom).</li>
     * <li>La animación de hover del botón de cierre.</li>
     * <li>El sonido del botón mediante {@link ButtonSound}.</li>
     * <li>La acción de cierre del popUp.</li>
     * </ul>
     *
     * <p>
     * El popUp aparece suavemente sobre la pantalla, manteniendo la coherencia
     * visual con el resto de popUps del proyecto.
     * </p>
     */
    @FXML
    public void initialize() {

        // Estado inicial para animación
        rootAyuda.setOpacity(0);
        box.setScaleX(0.7);
        box.setScaleY(0.7);

        // Fade-in
        FadeTransition fade = new FadeTransition(Duration.millis(300), rootAyuda);
        fade.setFromValue(0);
        fade.setToValue(1);

        // Zoom-in
        ScaleTransition scale = new ScaleTransition(Duration.millis(300), box);
        scale.setFromX(0.7);
        scale.setFromY(0.7);
        scale.setToX(1);
        scale.setToY(1);

        new ParallelTransition(fade, scale).play();

        // Animación y sonido del botón
        Animaciones.animarBoton(btnCerrar);
        ButtonSound.activarClick(btnCerrar);

        btnCerrar.setOnAction(e -> cerrar());
    }

    /**
     * Establece el mensaje que se mostrará en el popUp de ayuda.
     *
     * <p>
     * Este método permite reutilizar el mismo popUp para mostrar distintos
     * textos informativos según la pantalla o acción que lo invoque.
     * </p>
     *
     * @param mensaje Texto que se mostrará en la etiqueta principal.
     */
    public void setMensaje(String mensaje) {
        lblMensaje.setText(mensaje);
    }

    /**
     * Cierra el popUp aplicando una animación de desvanecimiento.
     *
     * <p>
     * Una vez finaliza la animación, el popUp se elimina del StackPane padre,
     * evitando que permanezca en memoria o interfiera con otros elementos
     * interactivos de la interfaz.
     * </p>
     */
    private void cerrar() {
        
        // Animación de salida.
        FadeTransition fadeOut = new FadeTransition(Duration.millis(250), rootAyuda);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);

        fadeOut.setOnFinished(ev
                -> ((StackPane) rootAyuda.getParent()).getChildren().remove(rootAyuda)
        );

        fadeOut.play();
    }
}
