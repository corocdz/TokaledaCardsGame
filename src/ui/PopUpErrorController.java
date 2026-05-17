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
 * Controlador encargado de gestionar el popUp de error mostrado en la interfaz.
 *
 * <p>
 * Este popUp se utiliza para informar al usuario de errores o situaciones
 * inesperadas durante la ejecución.
 * </p>
 *
 * <h2>El controlador se encarga de:</h2>
 * <ul>
 * <li>Mostrar el mensaje recibido desde el controlador que invoca el
 * popUp.</li>
 * <li>Aplicar animaciones de entrada y salida para una experiencia visual
 * suave.</li>
 * <li>Gestionar el cierre del popUp mediante el botón correspondiente.</li>
 * <li>Activar efectos de sonido y animaciones de hover en el botón.</li>
 * </ul>
 *
 * <p>
 * El popUp se inserta dinámicamente dentro de un {@link StackPane} padre, y se
 * elimina de él al cerrarse.
 * </p>
 * 
 * @author Javier Coronilla Castellano.
 */
public class PopUpErrorController {

    // -------------------------------
    // ELEMENTOS GRÁFICOS
    // -------------------------------
    /**
     * Contenedor raíz del popUp. Se usa para aplicar el fade de entrada y salida.
     */
    @FXML
    private StackPane rootError;
    
    /**
     * Caja central del popUp que contiene el mensaje y el botón
     */
    @FXML
    private VBox box; 
    
    /**
     * Mensaje de error.
     */
    @FXML
    private Label lblMensaje;
    
    /**
     * Botón para cerrar el popUp.
     */
    @FXML
    private Button btnCerrar;

    /**
     * Inicializa el popUp tras cargarse el FXML.
     *
     * <h2>Configura: </h2>
     * <ul>
     * <li>La animación de entrada.</li>
     * <li>La animación de hover del botón.</li>
     * <li>El sonido del botón.</li>
     * <li>La acción de cierre del popUp.</li>
     * </ul>
     */
    @FXML
    public void initialize() {

        // Estado inicial para  la animación de entrada.
        rootError.setOpacity(0);
        box.setScaleX(0.7);
        box.setScaleY(0.7);
        
        // Fade de entrada.
        FadeTransition fade = new FadeTransition(Duration.millis(300), rootError);
        fade.setFromValue(0);
        fade.setToValue(1);

        // Zoom de entrada.
        ScaleTransition scale = new ScaleTransition(Duration.millis(300), box);
        scale.setFromX(0.7);
        scale.setFromY(0.7);
        scale.setToX(1);
        scale.setToY(1);

        // Ejecutar ambas animaciones en paralelo.
        new ParallelTransition(fade, scale).play();

        // Animación y Efecto de sonido.
        Animaciones.animarBoton(btnCerrar);
        ButtonSound.activarClick(btnCerrar);

        // Cerrar popUp
        btnCerrar.setOnAction(e -> cerrar());

    }

    /**
     * Establece el mensaje que se mostrará en el popUp.
     *
     * @param mensaje Texto descriptivo del error.
     */
    public void setMensaje(String mensaje) {
        lblMensaje.setText(mensaje);
    }

    /**
     * Cierra el popUp aplicando una animación de desvanecimiento.
     *
     * <p>
     * Una vez finaliza la animación, el popUp se elimina del StackPane padre,
     * evitando que quede en memoria o interfiera con otros elementos.
     * </p>
     */
    private void cerrar() {
        
        // Fade de salida 
        FadeTransition fadeOut = new FadeTransition(Duration.millis(250), rootError);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);
        
        // Cuando termine, eliminar el popUp del contenedor padre.
        fadeOut.setOnFinished(ev -> ((StackPane) rootError.getParent()).getChildren().remove(rootError));
        
        fadeOut.play();
    }

}
