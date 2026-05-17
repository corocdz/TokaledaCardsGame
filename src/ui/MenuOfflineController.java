/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package ui;

import firebase.FirebaseDatabaseService;
import java.util.HashMap;
import java.util.Map;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import i18n.IdiomaManager;
import java.util.ResourceBundle;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import ui.audio.ButtonSound;
import ui.audio.SoundManager;

/**
 * Controlador encargado de gestionar el menú del modo offline.
 *
 * <p>
 * Desde esta pantalla el usuario puede seleccionar el número de jugadores IA,
 * elegir entre los modos Pescaito o Yusa, cambiar el idioma, acceder a opciones
 * o volver al menú principal.
 * </p>
 *
 * <h2>Responsabilidades principales:</h2>
 * <ul>
 * <li>Configurar la interfaz del menú offline.</li>
 * <li>Gestionar la selección del número de jugadores IA.</li>
 * <li>Permitir jugar a los modos de juego disponibles en la app de manera
 * Offline sin tener que utilizar Firebase ni gestión de salas online.</li>
 * </ul>
 *
 * <h2>Importancia dentro del proyecto:</h2>
 * <p>
 * Este menú es el punto de entrada al modo un jugador. Su correcto
 * funcionamiento garantiza una experiencia fluida y coherente de los modos de
 * juego disponibles en la aplicación en un entorno Offline para todos.
 * </p>
 *
 * @author Javier Coronilla Castellano.
 */
public class MenuOfflineController {

    // -------------------------------
    // ELEMENTOS GRÁFICOS
    // -------------------------------
    /**
     * Botón para el idioma.
     */
    @FXML
    private Button btnIdioma;

    /**
     * Botón para volver al menú principal.
     */
    @FXML
    private Button btnVolver;

    /**
     * Botón para cerrar la apliación.
     */
    @FXML
    private Button btnSalir;

    /**
     * Botón para el popUp de sonido.
     */
    @FXML
    private Button btnOpciones;

    /**
     * Botón para el modo de juego Pescaito.
     */
    @FXML
    private Button btnPescaito;

    /**
     * Botón para el modo de Yusa.
     */
    @FXML
    private Button btnYusa;

    /**
     * Imagen para el logo.
     */
    @FXML
    private ImageView logoImage;

    /**
     * Imagen para el botón de idioma.
     */
    @FXML
    private ImageView btnIdiomaImage;

    /**
     * Imagen para el botón del modo Pescaito.
     */
    @FXML
    private ImageView btnPescaitoImage;

    /**
     * Imagen para el botón del modo Yusa.
     */
    @FXML
    private ImageView btnYusaImage;

    /**
     * Imagen para el botón del volver al menú principal.
     */
    @FXML
    private ImageView btnVolverImage;

    /**
     * Capa raíz.
     */
    @FXML
    private StackPane rootMenuOffline;

    /**
     * Texto jugadoresIA.
     */
    @FXML
    private Label lblJugadoresIA;

    /**
     * Texto informativo de la Yusa.
     */
    @FXML
    private Label lblTextoYusa;

    /**
     * Texto informativo del Pescaito.
     */
    @FXML
    private Label lblTextoPescaito;

    /**
     * ComboBox para añadir jugadoresIA a la partida.
     */
    @FXML
    private ComboBox<Integer> comboJugadoresIA;

    // -------------------------------
    // ELEMENTOS GENERALES
    // -------------------------------
    /**
     * Bundle de idioma cargado dinámicamente según la elección del usuario.
     */
    private final ResourceBundle bundle = IdiomaManager.getBundle();

    /**
     * Servicio encargado de leer y actualizar datos del usuario en Firebase.
     */
    private final FirebaseDatabaseService dbService = new FirebaseDatabaseService();

    /**
     * Método de inicialización ejecutado automáticamente por JavaFX al cargar
     * el FXML.
     *
     * <p>
     * Configura animaciones, imágenes, sonidos, limitadores de texto y
     * listeners de botones. También aplica un efecto de entrada suave a la
     * pantalla.
     * </p>
     */
    @FXML
    private void initialize() {

        // Animación de entrada de la pantalla.
        Platform.runLater(() -> Animaciones.fadeIn(rootMenuOffline));

        // Cargamos imagen para el logo de la aplicación.
        logoImage.setImage(new Image(
                getClass().getResource("/ui/graphicResources/imagenes/tokaledaCardsGame.png").toExternalForm()
        ));

        // Cargamos imagen para el botón de pescaito. Como es el mismo en español que en inglés, lo cargamos así directamente.
        btnPescaitoImage.setImage(new Image(
                getClass().getResource("/ui/graphicResources/imagenes/btnModoPescaito.png").toExternalForm()
        ));
        // Cargamos imagen para el botón de yusa. Como es el mismo en español que en inglés, lo cargamos así directamente.
        btnYusaImage.setImage(new Image(
                getClass().getResource("/ui/graphicResources/imagenes/btnModoYusa.png").toExternalForm()
        ));

        // Cargamos imágenes de los botones de idioma y volver en función del idioma actual de la aplicación.
        btnIdiomaImage.setImage(IdiomaManager.cargarImagen("btnIdioma"));
        btnVolverImage.setImage(IdiomaManager.cargarImagen("btnVolver"));

        // Rellenamos el comboBox con número de jugadores IA permitidos para la partida. (Mínimo 1, máximo 3)
        comboJugadoresIA.getItems().addAll(1, 2, 3);
        comboJugadoresIA.getSelectionModel().selectFirst(); // Valor por defecto del comboBox es 1.               

        // Animaciones de los elementos de la pantalla.
        Animaciones.animarLogo(logoImage);
        Animaciones.animarLabelGeneral(lblJugadoresIA);
        Animaciones.animarLabelGeneral(lblTextoPescaito);
        Animaciones.animarLabelGeneral(lblTextoYusa);

        Animaciones.animarBoton(btnVolver);
        Animaciones.animarBoton(btnOpciones);
        Animaciones.animarBoton(btnSalir);
        Animaciones.animarBoton(btnIdioma);
        Animaciones.animarBoton(btnPescaito);
        Animaciones.animarBoton(btnYusa);

        // Activar sonidos para los botones.
        ButtonSound.activar(btnVolver);
        ButtonSound.activar(btnOpciones);
        ButtonSound.activar(btnSalir);
        ButtonSound.activar(btnIdioma);
        ButtonSound.activar(btnPescaito);
        ButtonSound.activar(btnYusa);

        // Animación de sonido y visual para el comboBox.
        comboJugadoresIA.setOnAction(e -> {
            Animaciones.animarPress(comboJugadoresIA);
            SoundManager.clickButton();
        });

        // Listeners para los botones
        btnIdioma.setOnAction(e -> cambiarIdioma()); // Cambiar idioma.
        btnVolver.setOnAction(e -> MainApp.cambiarEscena("menuPrincipal.fxml", 1200, 1000)); // Volver al menú principal.
        btnOpciones.setOnAction(e -> Animaciones.mostrarPopupSonido(rootMenuOffline)); // Mostrar popUp Sonido.
        btnSalir.setOnAction(e -> { // Salir de la aplicación.
            MainApp.desconectarUsuario(); // Desconectar usuario de la Firebase antes de cerrar aplicación.
            Platform.exit();
        });

        /**
         * --------------------- MODO PESCAITO OFFLINE ---------------------
         *
         * Al pulsar el botón se inicia una partida offline del modo pescaito
         * generando manualmente el controlador
         * ({@link PartidaOfflinePescaitoController}) y cargando la escena de la
         * partida ({@link partidaOffline.fxml}). El número de jugadores IA son
         * pasados como parámetro al método encargado de iniciar la partida.
         *
         */
        btnPescaito.setOnAction(e -> {

            // Obtenemos el número de IAs seleccionadas en el comboBox. Usamos 1 como valor seguro en caso de null o algo.
            int numIAs = comboJugadoresIA.getValue() != null ? comboJugadoresIA.getValue() : 1;
            try {
                // Cargamos el fxml de la partida Offline. partidaOffline.fxml es compartido por ambos modos de juego (Yusa y Pescaito).
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/ui/partidaOffline.fxml"));

                // Creamos controlador del modo de juego Pescaito Offline para pasarle el número de jugadores IA como parámetros.
                PartidaOfflinePescaitoController controller = new PartidaOfflinePescaitoController();

                // Asociamos el controlador creado al fxml.
                loader.setController(controller);
                Parent root = loader.load();

                // Inicializamos la partida Offline con el número de jugadores IA seleccionados.
                controller.iniciarOffline(numIAs);

                // Obtenemos la ventana actual para reemplazar la escena.
                Stage stage = (Stage) btnPescaito.getScene().getWindow();

                stage.setScene(new Scene(root));
                stage.setMaximized(true);
                stage.show();

            } catch (Exception ex) { // Capturamos posibles excepciones.
                ex.printStackTrace();
            }
        });

        /**
         * --------------------- MODO YUSA OFFLINE ---------------------
         *
         * Al pulsar el botón se inicia una partida offline del modo Yusa
         * generando manualmente el controlador
         * ({@link PartidaOfflineYusaController}) y cargando la escena de la
         * partida ({@link partidaOffline.fxml}). El número de jugadores IA son
         * pasados como parámetro al método encargado de iniciar la partida.
         *
         */
        btnYusa.setOnAction(e -> { // Exactamente la misma lógica explicada en el modo pescaito offline justo arriba.
            int numIAs = comboJugadoresIA.getValue() != null ? comboJugadoresIA.getValue() : 1;
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/ui/partidaOffline.fxml"));
                PartidaOfflineYusaController controller = new PartidaOfflineYusaController();
                loader.setController(controller);
                Parent root = loader.load();
                controller.iniciarOffline(numIAs);
                Stage stage = (Stage) btnYusa.getScene().getWindow();
                stage.setScene(new Scene(root));
                stage.setMaximized(true);
                stage.show();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

    }

    /**
     * Cambia el idioma de la interfaz entre español e inglés y recarga la
     * escena.
     * <p>
     * Si el usuario está logueado, también actualiza su idioma en Firebase para
     * mantener coherencia entre sesiones.
     * </p>
     */
    private void cambiarIdioma() {

        String langActual = IdiomaManager.getCodigoIdioma();
        String nuevo = langActual.equals("es") ? "en" : "es";

        IdiomaManager.setIdioma(nuevo);

        if (MainApp.usuarioActualUID != null && MainApp.usuarioActualToken != null) {
            try {
                Map<String, Object> cambios = new HashMap<>();
                cambios.put("idioma", nuevo);
                dbService.actualizarCamposUsuario(MainApp.usuarioActualUID, cambios, MainApp.usuarioActualToken);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        MainApp.cambiarEscena("menuOffline.fxml", 1200, 1000);
    }

}
