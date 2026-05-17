package ui;

import firebase.FirebaseDatabaseService;
import i18n.IdiomaManager;
import java.util.HashMap;
import java.util.Map;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import ui.audio.ButtonSound;

/**
 * Controlador encargado de gestionar el menú principal de la aplicación.
 *
 * <p>
 * Esta pantalla actúa como punto central de navegación tras iniciar sesión.
 * Desde aquí el usuario puede acceder al modo offline entrando al Menu Offline
 * ({@link MenuOfflineController}), al modo online desde el Modo Online ({@link
 * MenuOnlineController}), a las opciones de sonido, cambiar el idioma o cerrar
 * la aplicación.
 * </p>
 *
 * <h2>Responsabilidades principales:</h2>
 * <ul>
 * <li>Cargar y animar los elementos visuales del menú.</li>
 * <li>Gestionar la navegación hacia los distintos modos de juego.</li>
 * <li>Permitir el cambio de idioma desde el menú principal.</li>
 * <li>Desconectar al usuario de Firebase al salir de la aplicación.</li>
 * </ul>
 *
 * <h2>Importancia dentro del proyecto:</h2>
 * <p>
 * El menú principal es el punto de entrada a todas las funcionalidades del
 * juego. Permite al usuario escoger la modalidad deseada para su partida y
 * modificar y almacenar el idioma preferido. Su correcto funcionamiento
 * garantiza una navegación fluida y coherente.
 * </p>
 *
 * @author Javier Coronilla Castellano.
 */
public class MenuController {

    // -------------------------------
    // ELEMENTOS GRÁFICOS
    // -------------------------------
    /**
     * Imagen para el botón de idioma.
     */
    @FXML
    private ImageView btnIdiomaImage;

    /**
     * Imagen para el botón de ayuda.
     */
    @FXML
    private ImageView btnAyudaImage;

    /**
     * Imagen para el botón de menú offline.
     */
    @FXML
    private ImageView btnOfflineImage;

    /**
     * Imagen para el botón de menú online.
     */
    @FXML
    private ImageView btnOnlineImage;

    /**
     * Botón para el idioma.
     */
    @FXML
    private Button btnIdioma;

    /**
     * Botón para la ayuda.
     */
    @FXML
    private Button btnAyuda;

    /**
     * Botón para el menú online.
     */
    @FXML
    private Button btnOnline;

    /**
     * Botón para el menú offline.
     */
    @FXML
    private Button btnOffline;

    /**
     * Botón para cerrar la aplicación.
     */
    @FXML
    private Button btnSalir;

    /**
     * Botón para el popUp de sonido.
     */
    @FXML
    private Button btnOpciones;

    /**
     * Imagen para el logo.
     */
    @FXML
    private ImageView logoImage;

    /**
     * Capa raíz.
     */
    @FXML
    private StackPane rootMenuPrincipal;

    /**
     * Servicio encargado de leer y actualizar datos del usuario en Firebase.
     */
    private final FirebaseDatabaseService dbService = new FirebaseDatabaseService();

    /**
     * Método de inicialización automática ejecutado por JavaFX al cargar el
     * FXML.
     * <p>
     * Configura animaciones, imágenes, efectos sonoros y listeners de botones.
     * También aplica un efecto de entrada suave a la pantalla.
     * </p>
     */
    @FXML
    private void initialize() {

        // Animación de entrada de la pantalla.
        // Ponemos la animación en la cola para que se ejecute cuando esté la UI lista.
        Platform.runLater(() -> Animaciones.fadeIn(rootMenuPrincipal));

        // Cargamos el logo principal de la aplicación.
        logoImage.setImage(new Image(
                getClass().getResource("/ui/graphicResources/imagenes/tokaledaCardsGame.png").toExternalForm()
        ));

        // Cargamos imágenes de los botones según el idioma actual.
        btnIdiomaImage.setImage(IdiomaManager.cargarImagen("btnIdioma"));
        btnOfflineImage.setImage(IdiomaManager.cargarImagen("btnOffline"));
        btnOnlineImage.setImage(IdiomaManager.cargarImagen("btnOnline"));

        // Animaciones para los elementos del menú.
        Animaciones.animarLogo(logoImage);
        Animaciones.animarBoton(btnOnline);
        Animaciones.animarBoton(btnOffline);
        Animaciones.animarBoton(btnOpciones);
        Animaciones.animarBoton(btnSalir);
        Animaciones.animarBoton(btnIdioma);
        Animaciones.animarBoton(btnAyuda);

        // Activar sonidos.
        ButtonSound.activar(btnIdioma);
        ButtonSound.activar(btnOffline);
        ButtonSound.activar(btnOnline);
        ButtonSound.activar(btnOpciones);
        ButtonSound.activar(btnSalir);
        ButtonSound.activar(btnAyuda);

        // Listeners para los botones.
        btnIdioma.setOnAction(e -> cambiarIdioma()); // Cambiamos el idioma.
        btnOpciones.setOnAction(e -> Animaciones.mostrarPopupSonido(rootMenuPrincipal)); // Mostramos el popUp de sonido.
        btnOnline.setOnAction(e -> abrirModoOnline()); // Cambiamos a la pantalla del menú online.
        btnOffline.setOnAction(e -> abrirModoUnJugador()); // Cambiamos a la pantalla del menú offline.
        btnSalir.setOnAction(e -> { // Salir de la aplicación.
            MainApp.desconectarUsuario(); // Desconectamos al usuario de Firebase antes de salir de la aplicación.
            Platform.exit();
        });
        btnAyuda.setOnAction(e -> {
            Animaciones.mostrarAyuda(rootMenuPrincipal,IdiomaManager.getBundle().getString("ayuda.texto"));
        });

    }

    /**
     * Abre el menú del modo online.
     *
     * <p>
     * Cambia la escena a {@code menuOnline.fxml}, donde el usuario podrá crear
     * o unirse a salas online.
     * </p>
     */
    private void abrirModoOnline() {
        MainApp.cambiarEscena("menuOnline.fxml", 1200, 1000);
    }

    /**
     * Abre el menú del modo offline.
     *
     * <p>
     * Cambia la escena a {@code menuOffline.fxml}, donde el usuario podrá jugar
     * contra la IA.
     * </p>
     */
    private void abrirModoUnJugador() {
        MainApp.cambiarEscena("menuOffline.fxml", 1200, 1000);
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

        // Obtenemos el código del idioma actual desde el gestor de idiomas.
        String actLenguage = IdiomaManager.getCodigoIdioma();

        // Alternamos el idioma. Si está en español pasa a inglés y viceversa.
        String nuevo = actLenguage.equals("es") ? "en" : "es";

        // Guardamos idioma en memoria. 
        IdiomaManager.setIdioma(nuevo);

        // Guardamos idioma en Firebase si el usuario ya está logueado
        if (MainApp.usuarioActualUID != null && MainApp.usuarioActualToken != null) {
            try {
                Map<String, Object> cambios = new HashMap<>();
                cambios.put("idioma", nuevo);
                dbService.actualizarCamposUsuario(MainApp.usuarioActualUID, cambios, MainApp.usuarioActualToken);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        // Recargamos la escena con el nuevo idioma aplicado.
        MainApp.cambiarEscena("menuPrincipal.fxml", 1200, 1000);
    }

}
