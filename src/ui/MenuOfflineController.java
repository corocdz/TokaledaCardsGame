/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package ui;

import firebase.FirebaseDatabaseService;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import i18n.IdiomaManager;
import java.util.ResourceBundle;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import ui.audio.ButtonSound;
import ui.audio.SoundManager;

/**
 *
 * @author coro
 */
public class MenuOfflineController {

    @FXML
    private Button btnIdioma;
    @FXML
    private Button btnVolver;
    @FXML
    private Button btnSalir;
    @FXML
    private Button btnOpciones;
    @FXML
    private Button btnPescaito;
    @FXML
    private Button btnYusa;
    @FXML
    private ImageView logoImage;
    @FXML
    private ImageView btnIdiomaImage;
    @FXML
    private ImageView btnPescaitoImage;
    @FXML
    private ImageView btnYusaImage;
    @FXML
    private ImageView btnVolverImage;
    @FXML
    private StackPane rootMenuOffline;
    @FXML
    private Label lblJugadoresIA;
    @FXML
    private Label lblTextoYusa;
    @FXML
    private Label lblTextoPescaito;

    @FXML
    private ComboBox<Integer> comboJugadoresIA;

    private final ResourceBundle bundle = IdiomaManager.getBundle();

    private final FirebaseDatabaseService dbService = new FirebaseDatabaseService();

    @FXML
    private void initialize() {

        Platform.runLater(() -> Animaciones.fadeInPro(rootMenuOffline));

        // Cargar imagen        
        logoImage.setImage(new Image(
                getClass().getResource("/ui/graphicResources/imagenes/tokaledaCardsGame.png").toExternalForm()
        ));
        btnPescaitoImage.setImage(new Image(
                getClass().getResource("/ui/graphicResources/imagenes/btnModoPescaito.png").toExternalForm()
        ));
        btnYusaImage.setImage(new Image(
                getClass().getResource("/ui/graphicResources/imagenes/btnModoYusa.png").toExternalForm()
        ));

        btnIdiomaImage.setImage(IdiomaManager.cargarImagen("btnIdioma"));
        btnVolverImage.setImage(IdiomaManager.cargarImagen("btnVolver"));

        comboJugadoresIA.getItems().addAll(1, 2, 3);
        comboJugadoresIA.getSelectionModel().selectFirst(); // por defecto 1

        btnIdioma.setOnAction(e -> cambiarIdioma());

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

        ButtonSound.activar(btnVolver);
        ButtonSound.activar(btnOpciones);
        ButtonSound.activar(btnSalir);
        ButtonSound.activar(btnIdioma);
        ButtonSound.activar(btnPescaito);
        ButtonSound.activar(btnYusa);

        // ⭐ EFECTO DE BOTÓN APRETADO AL SELECCIONAR MODO (solo host)
        comboJugadoresIA.setOnAction(e -> {
            Animaciones.animarPress(comboJugadoresIA);
            SoundManager.clickButton();
        });

        btnVolver.setOnAction(e -> MainApp.cambiarEscena("menuPrincipal.fxml", 800, 600));
        btnOpciones.setOnAction(e -> Animaciones.mostrarPopupSonido(rootMenuOffline));
        btnSalir.setOnAction(e -> {
            MainApp.desconectarUsuario();
            Platform.exit();
        });

        btnPescaito.setOnAction(e -> {
            int numIAs = comboJugadoresIA.getValue() != null
                    ? comboJugadoresIA.getValue() : 1;
            try {
                FXMLLoader loader = new FXMLLoader(
                        getClass().getResource("/ui/partidaOffline.fxml"));
                PartidaOfflinePescaitoController ctrl
                        = new PartidaOfflinePescaitoController();
                loader.setController(ctrl);
                Parent root = loader.load();
                ctrl.iniciarOffline(numIAs);

                Stage stage = (Stage) btnPescaito.getScene().getWindow();
                stage.setScene(new Scene(root, 1280, 720));
                stage.show();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        btnYusa.setOnAction(e -> {
            int numIAs = comboJugadoresIA.getValue() != null ? comboJugadoresIA.getValue() : 1;
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/ui/partidaOffline.fxml"));
                PartidaOfflineYusaController ctrl = new PartidaOfflineYusaController();
                loader.setController(ctrl);
                Parent root = loader.load();
                ctrl.iniciarOffline(numIAs);
                Stage stage = (Stage) btnYusa.getScene().getWindow();
                stage.setScene(new Scene(root, 1280, 720));
                stage.show();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

    }

    // =========================
    // UTILIDAD: MOSTRAR ERROR
    // =========================
    private void mostrarError(String mensaje) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(null);
        alert.setContentText(mensaje);
        alert.showAndWait();
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
        MainApp.cambiarEscena("menuOffline.fxml", 800, 600);
    }

}
