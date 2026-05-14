package ui;

import firebase.FirebaseAuthService;
import firebase.FirebaseDatabaseService;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import ui.MainApp;
import i18n.IdiomaManager;
import java.util.ResourceBundle;
import java.util.HashMap;
import java.util.Map;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import ui.audio.ButtonSound;
import ui.audio.SoundManager;

public class RegistroController {

    @FXML
    private TextField txtNombre;
    @FXML
    private TextField txtEmail;
    @FXML
    private PasswordField txtPassword;
    @FXML
    private PasswordField txtConfirmar;
    @FXML
    private ImageView btnRegistroImage;
    @FXML
    private Button btnRegistro;
    @FXML
    private ImageView btnVolverImage;
    @FXML
    private Button botonOpciones;
    @FXML
    private ImageView botonOpcionesImage;
    @FXML
    private Button btnVolver;
    @FXML
    private ImageView logoImage;
    @FXML
    private ImageView btnIdiomaImage;
    @FXML
    private Button btnIdioma;
    @FXML
    private Label tituloRegistro;
    @FXML
    private Label textoVolver;
    @FXML
    private StackPane rootRegistro;

    private final ResourceBundle bundle = IdiomaManager.getBundle();

    private final FirebaseAuthService authService = new FirebaseAuthService();
    private final FirebaseDatabaseService dbService = new FirebaseDatabaseService();

    @FXML
    private void initialize() {

        Platform.runLater(() -> Animaciones.fadeInPro(rootRegistro));

        // Cargar imagen
        logoImage.setImage(new Image(
                getClass().getResource("/ui/graphicResources/imagenes/tokaledaCardsGame.png").toExternalForm()
        ));

        // Limitador nombre (10)
        txtNombre.setTextFormatter(new TextFormatter<>(c
                -> c.getControlNewText().length() <= 10 ? c : null
        ));

        // Limitador email (50)
        txtEmail.setTextFormatter(new TextFormatter<>(c
                -> c.getControlNewText().length() <= 50 ? c : null
        ));

        // Limitador password (32)
        txtPassword.setTextFormatter(new TextFormatter<>(c
                -> c.getControlNewText().length() <= 32 ? c : null
        ));

        txtConfirmar.setTextFormatter(new TextFormatter<>(c
                -> c.getControlNewText().length() <= 32 ? c : null
        ));

        btnIdiomaImage.setImage(IdiomaManager.cargarImagen("btnIdioma"));
        btnRegistroImage.setImage(IdiomaManager.cargarImagen("btnRegistro"));
        btnVolverImage.setImage(IdiomaManager.cargarImagen("btnVolver"));

        Animaciones.animarLogo(logoImage);
        Animaciones.animarBoton(btnVolver);
        Animaciones.animarBoton(btnRegistro);
        Animaciones.animarLabelGeneral(tituloRegistro);
        Animaciones.animarLabelSecundario(textoVolver);

        // Hover sonoro (después o antes, da igual si usas addEventHandler)
        ButtonSound.activar(btnIdioma);
        ButtonSound.activar(btnRegistro);
        ButtonSound.activar(btnVolver);
        ButtonSound.activar(botonOpciones);

        btnIdioma.setOnAction(e -> cambiarIdioma());
        btnRegistro.setOnAction(e -> registrar());
        btnVolver.setOnAction(e -> MainApp.cambiarEscena("login.fxml", 600, 400));
        botonOpciones.setOnAction(e -> Animaciones.mostrarPopupSonido(rootRegistro));
    }

    private void registrar() {
        String nombre = txtNombre.getText().trim();
        String email = txtEmail.getText().trim();
        String password = txtPassword.getText().trim();
        String confirmar = txtConfirmar.getText().trim();

        // Campos vacíos
        if (nombre.isEmpty() || email.isEmpty() || password.isEmpty() || confirmar.isEmpty()) {
            Animaciones.mostrarError(rootRegistro, bundle.getString("registroController.error.campos"));
            return;
        }

        // Email válido
        if (!emailValido(email)) {
            Animaciones.mostrarError(rootRegistro, bundle.getString("registro.errorFormatoEmail"));
            return;
        }

        // Contraseña fuerte
        if (!passwordValida(password)) {
            Animaciones.mostrarError(rootRegistro, bundle.getString("registro.errorFormatoPassword"));
            return;
        }

        // Coincidencia de contraseñas
        if (!password.equals(confirmar)) {
            Animaciones.mostrarError(rootRegistro, bundle.getString("registroController.error.password"));
            return;
        }

        try {
            String result = authService.register(email, password);

            switch (result) {
                case "OK":
                    String uid = authService.getLocalId();
                    String token = authService.getIdToken();

                    Map<String, Object> datos = new HashMap<>();
                    datos.put("email", email);
                    datos.put("nombre", nombre);
                    datos.put("avatar", "default.png");
                    datos.put("conectado", true);
                    datos.put("ultimaConexion", System.currentTimeMillis());
                    datos.put("idioma", IdiomaManager.getCodigoIdioma());

                    dbService.guardarUsuario(uid, datos, token);

                    MainApp.usuarioActualUID = uid;
                    MainApp.usuarioActualToken = token;

                    MainApp.cambiarEscena("menuPrincipal.fxml", 800, 600);
                    return;

                case "EMAIL_EXISTS":
                    Animaciones.mostrarError(rootRegistro, bundle.getString("registro.errorEmailRegistrado"));
                    return;

                case "INVALID_EMAIL":
                    Animaciones.mostrarError(rootRegistro, bundle.getString("registro.errorFormatoEmail"));
                    return;

                case "WEAK_PASSWORD":
                    Animaciones.mostrarError(rootRegistro, bundle.getString("registro.errorFormatoPassword"));
                    return;

                case "MISSING_PASSWORD":
                    Animaciones.mostrarError(rootRegistro, bundle.getString("registro.errorSinPassword"));
                    return;

                case "NETWORK_ERROR":
                    Animaciones.mostrarError(rootRegistro, bundle.getString("registro.errorConexion"));
                    return;

                default:
                    Animaciones.mostrarError(rootRegistro, bundle.getString("registro.errorDesconocido"));
                    return;
            }

        } catch (Exception ex) {
            Animaciones.mostrarError(rootRegistro, bundle.getString("registroController.error.excepcion") + ex.getMessage());
        }
    }

    private boolean emailValido(String email) {
        return email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    private boolean passwordValida(String password) {
        return password.matches("^(?=.*[A-Z])(?=(?:.*\\d){2,})(?=.*[^A-Za-z0-9]).{6,}$");
    }

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
        MainApp.cambiarEscena("registro.fxml", 800, 600);
    }

}
