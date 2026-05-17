package ui;

import i18n.IdiomaManager;
import javafx.animation.*;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import ui.audio.ButtonSound;

/**
 * Controlador del popUp para unirse a una sala online.
 *
 * <p>
 * Este popUp permite al usuario introducir un código de sala y una contraseña
 * para acceder a una sala existente.
 * </p>
 *
 * <h2>El controlador gestiona:</h2>
 * <ul>
 * <li>La carga de imágenes traducidas según el idioma actual.</li>
 * <li>Las animaciones de entrada y salida del popUp.</li>
 * <li>La validación básica de los campos.</li>
 * <li>La captura del código y contraseña introducidos.</li>
 * <li>El cierre del popUp devolviendo los datos al menú online.</li>
 * </ul>
 *
 * <p>
 * El popUp funciona de forma modal, es deciir, el menú online queda bloqueado
 * hasta que el usuario pulse “Unirse” o “Cancelar”.
 * </p>
 * 
 * 
 * @author Javier Coronilla Castellano.
 */
public class PopUpUnirseController {

    // -------------------------------
    // ELEMENTOS GRÁFICOS
    // -------------------------------
    /**
     * Capa raíz del popUp para unirse a la sala.
     */
    @FXML
    private StackPane rootPopup;

    /**
     * Caja central que contiene todos los elementos del popUp.
     */
    @FXML
    private VBox popupBox;

    /**
     * Imagen superior del popUp.
     */
    @FXML
    private ImageView popUpCrearSalaImagen;

    /**
     * Imagen del botón para unirse a la sala.
     */
    @FXML
    private ImageView btnUnirseImage;

    /**
     * Imagen del botón para cancelar el intento de unirse a una sala.
     */
    @FXML
    private ImageView btnCancelarImage;

    /**
     * Botón para unirse a la sala.
     */
    @FXML
    private Button btnUnirse;

    /**
     * Botón para cancelar el intento de unirse a la sala.
     */
    @FXML
    private Button btnCancelar;

    /**
     * TextField para introducir el codigo de la sala.
     */
    @FXML
    private TextField txtCodigo;

    /**
     * PasswordField para introducir la contraseña de la sala.
     */
    @FXML
    private PasswordField txtPassword;

    // -------------------------------
    // ELEMENTOS GENERALES
    // -------------------------------
    /**
     * Referencia al Stage del popUp para poder cerrarlo desde el controlador.
     */
    private Stage popupStage;

    /**
     * Codigo introducido por el usuario.
     */
    private String codigo = null;

    /**
     * Contraseña introducida por el usuario.
     */
    private String password = null;

    /**
     * Inicializa el popUp tras cargarse el FXML.
     *
     * <p>
     * Este método:
     * </p>
     * <ul>
     * <li>Carga las imágenes según el idioma actual.</li>
     * <li>Aplica la animación de entrada.</li>
     * <li>Activa animaciones y sonidos en los botones.</li>
     * <li>Configura validación para impedir espacios en blanco.</li>
     * </ul>
     */
    @FXML
    private void initialize() {

        // Código documentado en otras numerosas clases.
        popUpCrearSalaImagen.setImage(IdiomaManager.cargarImagen("popUpCrearSalaImagen"));
        btnUnirseImage.setImage(IdiomaManager.cargarImagen("btnUnirse"));
        btnCancelarImage.setImage(IdiomaManager.cargarImagen("btnCancelar"));

        animarEntrada();

        Animaciones.animarBoton(btnUnirse);
        Animaciones.animarBoton(btnCancelar);

        ButtonSound.activar(btnCancelar);
        ButtonSound.activar(btnUnirse);

        // Validación: Impedimos que el usuario introduzca espacios en blanco en los campos de código y contraseña
        txtCodigo.textProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue.contains(" ")) {
                txtCodigo.setText(newValue.replace(" ", ""));
            }
        });

        txtPassword.textProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue.contains(" ")) {
                txtPassword.setText(newValue.replace(" ", ""));
            }
        });

    }

    /**
     * Aplica una animación suave de entrada al popUp.
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

        // Ejecutamos ambas animaciones en paralelo.
        new ParallelTransition(st, ft).play();
    }

    /**
     * Aplica una animación de salida y ejecuta una acción al finalizar.
     *
     * <p>
     * El popUp se reduce y desvanece en 200 ms. Una vez termina la animación,
     * se ejecuta la acción indicada (normalmente cerrar el Stage).
     * </p>
     *
     * @param after Acción a ejecutar tras finalizar la animación.
     */
    private void animarSalida(Runnable after) {

        // Reducimos tamaño.
        ScaleTransition st = new ScaleTransition(Duration.millis(200), popupBox);
        st.setToX(0.7);
        st.setToY(0.7);

        // Reducimos opacidad.
        FadeTransition ft = new FadeTransition(Duration.millis(200), popupBox);
        ft.setToValue(0);

        // Ejecutamos ambas animaciones juntas.
        ParallelTransition pt = new ParallelTransition(st, ft);

        // Cuando termine, ejecutar la acción recibida.
        pt.setOnFinished(e -> after.run());
        pt.play();
    }

    /**
     * Acción del botón "Unirse".
     *
     * <p>
     * Guarda el código y la contraseña introducidos por el usuario y cierra el
     * popUp con animación de salida.
     * </p>
     */
    @FXML
    private void onUnirse() {

        // Guardamos valores introducidos
        codigo = txtCodigo.getText().trim();
        password = txtPassword.getText().trim();

        // Animación de salida y cerramos popUp.
        animarSalida(() -> popupStage.close());
    }

    /**
     * Acción del botón "Cancelar".
     *
     * <p>
     * No devuelve ningún dato (ambos valores quedan en null) y cierra el popUp
     * con animación de salida.
     * </p>
     */
    @FXML
    private void onCancelar() {

        codigo = null;
        password = null;

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
     * Devuelve el código introducido por el usuario.
     *
     * @return Código de sala o {@code null} si se canceló.
     */
    public String getCodigo() {
        return codigo;
    }

    /**
     * Devuelve la contraseña introducida por el usuario.
     *
     * @return Contraseña o {@code null} si se canceló.
     */
    public String getPassword() {
        return password;
    }
}
