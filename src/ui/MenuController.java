package ui;

import firebase.FirebaseDatabaseService;
import i18n.IdiomaManager;
import java.util.HashMap;
import java.util.Map;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import ui.audio.ButtonSound;
import ui.audio.SoundManager;

public class MenuController {

    @FXML
    private ImageView btnIdiomaImage;
    @FXML
    private ImageView btnOfflineImage;
    @FXML
    private ImageView btnOnlineImage;
    @FXML
    private Button btnIdioma;
    @FXML
    private Button btnOnline;
    @FXML
    private Button btnOffline;
    @FXML
    private Button btnSalir;
    @FXML
    private Button btnOpciones;
    @FXML
    private ImageView logoImage;
    @FXML
    private StackPane rootMenuPrincipal;
    
    private final FirebaseDatabaseService dbService = new FirebaseDatabaseService();

    @FXML
    private void initialize() {

        Platform.runLater(() -> Animaciones.fadeInPro(rootMenuPrincipal));        
        
        // Cargar imagen
        logoImage.setImage(new Image(
                getClass().getResource("/ui/graphicResources/imagenes/tokaledaCardsGame.png").toExternalForm()
        ));

        btnIdiomaImage.setImage(IdiomaManager.cargarImagen("btnIdioma"));
        btnOfflineImage.setImage(IdiomaManager.cargarImagen("btnOffline"));
        btnOnlineImage.setImage(IdiomaManager.cargarImagen("btnOnline"));
        
        Animaciones.animarLogo(logoImage);
        Animaciones.animarBoton(btnOnline);
        Animaciones.animarBoton(btnOffline);
        Animaciones.animarBoton(btnOpciones);
        Animaciones.animarBoton(btnSalir);
        Animaciones.animarBoton(btnIdioma);
                
        // Hover sonoro (después o antes, da igual si usas addEventHandler)
        ButtonSound.activar(btnIdioma);
        ButtonSound.activar(btnOffline);
        ButtonSound.activar(btnOnline);
        ButtonSound.activar(btnOpciones);
        ButtonSound.activar(btnSalir);       

        btnIdioma.setOnAction(e -> cambiarIdioma()); 
        btnOpciones.setOnAction(e -> Animaciones.mostrarPopupSonido(rootMenuPrincipal));
        btnOnline.setOnAction(e -> abrirModoOnline());
        btnOffline.setOnAction(e -> abrirModoUnJugador());
        btnSalir.setOnAction(e -> {
            MainApp.desconectarUsuario();
            Platform.exit();
        });
    }

    private void abrirModoOnline() {
        MainApp.cambiarEscena("menuOnline.fxml", 800, 600);
    }

    private void abrirModoUnJugador() {
        MainApp.cambiarEscena("menuOffline.fxml", 1000, 700);
    }
    
    private void cambiarIdioma() {

        String langActual = IdiomaManager.getCodigoIdioma();
        String nuevo = langActual.equals("es") ? "en" : "es";

        // Guardar idioma en memoria
        IdiomaManager.setIdioma(nuevo);

        // Guardar idioma en Firebase si el usuario ya está logueado
        if (MainApp.usuarioActualUID != null && MainApp.usuarioActualToken != null) {
            try {
                Map<String, Object> cambios = new HashMap<>();
                cambios.put("idioma", nuevo);
                dbService.actualizarCamposUsuario(MainApp.usuarioActualUID, cambios, MainApp.usuarioActualToken);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        // Recargar escena
        MainApp.cambiarEscena("menuPrincipal.fxml", 800, 600);
    }        

}
