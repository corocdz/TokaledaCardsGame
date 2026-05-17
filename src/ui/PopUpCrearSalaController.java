package ui;

import i18n.IdiomaManager;
import javafx.animation.*;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import ui.audio.ButtonSound;

/**
 * Controlador del popUp encargado mostrar la ventana para crear una sala
 * online.
 *
 * <p>
 * Este popUp permite al usuario introducir una contraseña para la sala que
 * desea crear.
 * </p>
 *
 * <h2>El controlador gestiona:</h2>
 * <ul>
 * <li>La carga de imágenes traducidas según el idioma.</li>
 * <li>Las animaciones de entrada y salida del popUp.</li>
 * <li>La captura de la contraseña introducida por el usuario.</li>
 * <li>El cierre del popUp devolviendo el resultado al menú online.</li>
 * </ul>
 *
 * <p>
 * El popUp funciona de forma modal: el menú online queda bloqueado hasta que el
 * usuario pulse “Crear” o “Cancelar”.
 * </p>
 *
 *
 * @author Javier Coronilla Castellano.
 */
public class PopUpCrearSalaController {

    // -------------------------------
    // ELEMENTOS GRÁFICOS
    // -------------------------------
    /**
     * Capa raíz.
     */
    @FXML
    private StackPane rootPopup;

    /**
     * Caja central que contiene el contenido del popUp.
     */
    @FXML
    private VBox popupBox;

    /**
     * Imagen superior del popUp.
     */
    @FXML
    private ImageView popUpCrearSalaImagen;

    /**
     * Imagen de botón de crear.
     */
    @FXML
    private ImageView btnCrearImage;

    /**
     * Imagen de botón de cancelar.
     */
    @FXML
    private ImageView btnCancelarImage;

    /**
     * Botón de cancelar.
     */
    @FXML
    private Button btnCancelar;

    /**
     * Botón de crear.
     */
    @FXML
    private Button btnCrear;

    /**
     * PasswordField para la contraseña de la sala.
     */
    @FXML
    private PasswordField txtPassword;

    // -------------------------------
    // ELEMENTOS GENERALES
    // -------------------------------
    /**
     * Referencia al Stage del popUp para poder cerrarlo.
     */
    private Stage popupStage;

    /**
     * Contraseña introducida por el usuario. ({@code null} si cancela)
     */
    private String resultado = null;

    /**
     * Método de inicialización automática ejecutado por JavaFX al cargar el
     * FXML.
     * <p>
     * Configura animaciones, imágenes, efectos sonoros y listeners de botones.
     * </p>
     */
    @FXML
    private void initialize() {

        // Cargamos imágenes según el idioma.
        popUpCrearSalaImagen.setImage(IdiomaManager.cargarImagen("popUpCrearSalaImagen"));
        btnCrearImage.setImage(IdiomaManager.cargarImagen("btnCrear"));
        btnCancelarImage.setImage(IdiomaManager.cargarImagen("btnCancelar"));

        // Animaciones.
        animarEntrada();
        Animaciones.animarBoton(btnCrear);
        Animaciones.animarBoton(btnCancelar);

        // Sonidos de botones.
        ButtonSound.activar(btnCancelar);
        ButtonSound.activar(btnCrear);

        // Impedimos que se puedan escribir espacios en blanco en el campo de la contraseña para la sala.
        txtPassword.textProperty().addListener((obs, oldValue, newValue) -> {
            // Elimina espacios automáticamente
            if (newValue.contains(" ")) {
                txtPassword.setText(newValue.replace(" ", ""));
            }
        });

    }

    /**
     * Aplica una animación suave de aparición al popUp.
     */
    private void animarEntrada() {

        // Estado inicial reducido y transparente.
        popupBox.setScaleX(0.7);
        popupBox.setScaleY(0.7);
        popupBox.setOpacity(0);

        // Animación de escalado.
        ScaleTransition st = new ScaleTransition(Duration.millis(250), popupBox);
        st.setToX(1);
        st.setToY(1);

        // Animación de opacidad.
        FadeTransition ft = new FadeTransition(Duration.millis(250), popupBox);
        ft.setToValue(1);

        // Ejecutar ambas animaciones en paralelo.
        new ParallelTransition(st, ft).play();
    }

    /**
     * Aplica una animación de salida y ejecuta una acción al finalizar.
     *
     * <p>
     * Una vez termina la animación, se ejecuta la acción indicada.
     * </p>
     *
     * @param after Acción a ejecutar tras finalizar la animación.
     */
    private void animarSalida(Runnable after) {

        // Reeducir tamaño.
        ScaleTransition st = new ScaleTransition(Duration.millis(200), popupBox);
        st.setToX(0.7);
        st.setToY(0.7);

        // Reducir opacidad.
        FadeTransition ft = new FadeTransition(Duration.millis(200), popupBox);
        ft.setToValue(0);

        // Ejecutar ambas animaciones juntas.
        ParallelTransition pt = new ParallelTransition(st, ft);

        // Cuando termine la animación, ejecutar la acción recibida.
        pt.setOnFinished(e -> after.run());
        pt.play();
    }

    /**
     * Acción del botón "Crear".
     *
     * <p>
     * Guarda la contraseña introducida por el usuario y cierra el popUp con
     * animación de salida.
     * </p>
     */
    @FXML
    private void onCrear() {

        // Guardamos la contraseña escrita sin espacios
        resultado = txtPassword.getText().trim();

        // Animación de salida y cierre del popUp
        animarSalida(() -> popupStage.close());
    }

    /**
     * Acción del botón "Cancelar".
     *
     * <p>
     * No devuelve ninguna contraseña (resultado = null) y cierra el popUp con
     * animación de salida.
     * </p>
     */
    @FXML
    private void onCancelar() {

        // No se devuleve contraseña.
        resultado = null;

        // Animación de salida y cierre del popUp.
        animarSalida(() -> popupStage.close());
    }

    /**
     * Asigna el Stage del popUp para poder cerrarlo desde el controlador.
     *
     * @param stage Ventana del popUp.
     */
    public void setStage(Stage stage) {
        this.popupStage = stage;
    }

    /**
     * Devuelve la contraseña introducida por el usuario.
     *
     * <p>
     * Si el usuario canceló, devuelve {@code null}.
     * </p>
     *
     * @return Contraseña escrita o {@code null} si se canceló.
     */
    public String getResultado() {
        return resultado;
    }
}
