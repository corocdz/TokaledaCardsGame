package ui;

import firebase.FirebaseAuthService;
import firebase.FirebaseDatabaseService;
import java.util.HashMap;
import java.util.Map;
import javafx.fxml.FXML;
import i18n.IdiomaManager;
import java.util.ResourceBundle;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import ui.MainApp;
import ui.audio.ButtonSound;
import ui.audio.SoundManager;

public class LoginController {

    @FXML
    private TextField txtEmail;
    @FXML
    private PasswordField txtPassword;
    @FXML
    private Button btnLogin;
    @FXML
    private Button btnRegistro;
    @FXML
    private Button btnLoginJ1;
    @FXML
    private Button btnLoginJ2;
    @FXML
    private Button btnLoginJ3;
    @FXML
    private Button btnLoginJ4;
    @FXML
    private Button botonOpciones;
    @FXML
    private ImageView botonOpcionesImage;
    @FXML
    private ImageView logoImage;
    @FXML
    private ImageView btnRegistroImage;
    @FXML
    private ImageView btnIniciarSesionImage;
    @FXML
    private ImageView btnIdiomaImage;
    @FXML
    private Button btnIdioma;
    @FXML
    private Label tituloBienvenida;
    @FXML
    private Label textoRegistro;
    @FXML
    private StackPane rootLogin;

    private final ResourceBundle bundle = IdiomaManager.getBundle();

    private final FirebaseAuthService authService = new FirebaseAuthService();
    private final FirebaseDatabaseService dbService = new FirebaseDatabaseService();

    @FXML
    private void initialize() {

        Platform.runLater(() -> Animaciones.fadeInPro(rootLogin));

        // Cargar imagen del logo
        logoImage.setImage(new Image(
                getClass().getResource("/ui/graphicResources/imagenes/tokaledaCardsGame.png").toExternalForm()
        ));

        btnIdiomaImage.setImage(IdiomaManager.cargarImagen("btnIdioma"));
        btnRegistroImage.setImage(IdiomaManager.cargarImagen("btnRegistro"));
        btnIniciarSesionImage.setImage(IdiomaManager.cargarImagen("btnIniciarSesion"));

        Animaciones.animarLogo(logoImage);
        Animaciones.animarLabelGeneral(tituloBienvenida);
        Animaciones.animarLabelSecundario(textoRegistro);
        Animaciones.animarBoton(btnLogin);
        Animaciones.animarBoton(btnRegistro);
        Animaciones.animarBoton(botonOpciones);
        Animaciones.animarBoton(btnIdioma);

        // Hover sonoro (después o antes, da igual si usas addEventHandler)
        ButtonSound.activar(btnIdioma);
        ButtonSound.activar(btnRegistro);
        ButtonSound.activar(btnLogin);
        ButtonSound.activar(botonOpciones);

        btnLogin.setOnAction(e -> login());
        btnRegistro.setOnAction(e -> MainApp.cambiarEscena("registro.fxml", 600, 400));
        btnIdioma.setOnAction(e -> cambiarIdioma());
        botonOpciones.setOnAction(e -> Animaciones.mostrarPopupSonido(rootLogin));

        btnLoginJ1.setOnAction(e -> loginRapido("jugador1@test.com", "123456"));
        btnLoginJ2.setOnAction(e -> loginRapido("jugador2@test.com", "123456"));
        btnLoginJ3.setOnAction(e -> loginRapido("jugador3@test.com", "123456"));
        btnLoginJ4.setOnAction(e -> loginRapido("jugador4@test.com", "Coro11."));
    }

    private void login() {
        String email = txtEmail.getText().trim();
        String password = txtPassword.getText().trim();

        // Validación local
        if (email.isEmpty() || password.isEmpty()) {
            Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.campos"));
            return;
        }

        try {
            String result = authService.login(email, password);

            switch (result) {
                case "OK":
                    break;

                case "CREDENCIALES_INCORRECTAS":
                    Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.credenciales"));
                    return;

                case "USER_DISABLED":
                    Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.cuentaDesactivada"));
                    return;

                case "INVALID_EMAIL":
                    Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.emailNoValido"));
                    return;

                case "NETWORK_ERROR":
                    Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.desconexion"));
                    return;

                default:
                    Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.desconocido"));
                    return;
            }

            // -------------------------
            // LOGIN CORRECTO
            // -------------------------
            String uid = authService.getLocalId();
            String token = authService.getIdToken();
            MainApp.usuarioActualUID = uid;
            MainApp.usuarioActualToken = token;

            // 1. Leer nodo completo del usuario
            String nodoJson = dbService.leerNodo("usuarios/" + uid, token);

            boolean yaConectado = false;

            if (nodoJson != null && !nodoJson.equals("null")) {
                Map<String, Object> datosExistentes = new com.google.gson.Gson().fromJson(nodoJson, Map.class);
                if (datosExistentes != null) {

                    // Cargar idioma del usuario
                    Object idioma = datosExistentes.get("idioma");
                    if (idioma != null) {
                        IdiomaManager.setIdioma(idioma.toString());
                    }

                    Object conectado = datosExistentes.get("conectado");
                    if (conectado != null && Boolean.TRUE.equals(conectado)) {
                        yaConectado = true;
                    }
                }
            }

            // 2. Si ya está conectado, bloquear login
            if (yaConectado) {
                Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.activo"));
                MainApp.usuarioActualUID = null;
                MainApp.usuarioActualToken = null;
                return;
            }

            // 3. Preparar cambios: marcar como conectado y actualizar última conexión
            Map<String, Object> cambios = new HashMap<>();
            cambios.put("conectado", true);
            cambios.put("ultimaConexion", System.currentTimeMillis());

            // Si el nodo no existía, también metemos datos básicos
            if (nodoJson == null || nodoJson.equals("null")) {
                cambios.put("email", email);
                cambios.put("nombre", "Usuario");
                cambios.put("avatar", "default.png");
                cambios.put("idioma", IdiomaManager.getCodigoIdioma());
            }

            // 4. Fusionar y guardar
            dbService.actualizarCamposUsuario(uid, cambios, token);

            // 5. Entrar al menú
            MainApp.cambiarEscena("menuPrincipal.fxml", 800, 600);
            System.out.println("UID en login: " + uid);

        } catch (Exception ex) {
            Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.inicio") + ex.getMessage());
        }
    }

    private void loginRapido(String email, String password) {
        try {
            String result = authService.login(email, password);

            switch (result) {
                case "OK":
                    break;

                case "CREDENCIALES_INCORRECTAS":
                    Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.credenciales"));
                    return;
                    
                case "USER_DISABLED":
                    Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.cuentaDesactivada"));
                    return;

                case "INVALID_EMAIL":
                    Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.emailNoValido"));
                    return;

                case "NETWORK_ERROR":
                    Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.desconexion"));
                    return;

                default:
                    Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.desconocido"));
                    return;
            }

            // 2. Obtener UID y token igual que en login normal
            String uid = authService.getLocalId();
            String token = authService.getIdToken();
            MainApp.usuarioActualUID = uid;
            MainApp.usuarioActualToken = token;

            // 3. Leer nodo del usuario para comprobar si ya está conectado
            String nodoJson = dbService.leerNodo("usuarios/" + uid, token);

            boolean yaConectado = false;

            if (nodoJson != null && !nodoJson.equals("null")) {
                Map<String, Object> datosExistentes = new com.google.gson.Gson().fromJson(nodoJson, Map.class);
                if (datosExistentes != null) {
                    Object conectado = datosExistentes.get("conectado");
                    if (conectado != null && Boolean.TRUE.equals(conectado)) {
                        yaConectado = true;
                    }
                }
            }

            // 4. Evitar doble login
            if (yaConectado) {
                Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.activo"));
                MainApp.usuarioActualUID = null;
                MainApp.usuarioActualToken = null;
                return;
            }

            // 5. Preparar cambios en el nodo del usuario
            Map<String, Object> cambios = new HashMap<>();
            cambios.put("conectado", true);
            cambios.put("ultimaConexion", System.currentTimeMillis());

            // SIEMPRE actualizar idioma al iniciar sesión
            cambios.put("idioma", IdiomaManager.getCodigoIdioma());

            // Si el nodo no existía, crear datos básicos
            if (nodoJson == null || nodoJson.equals("null")) {
                cambios.put("email", email);
                cambios.put("nombre", "Usuario");
                cambios.put("avatar", "default.png");
                cambios.put("idioma", IdiomaManager.getCodigoIdioma());
            }

            // 6. Guardar cambios en Firebase
            dbService.actualizarCamposUsuario(uid, cambios, token);

            // 7. Entrar al menú principal
            MainApp.cambiarEscena("menuPrincipal.fxml", 800, 600);
            System.out.println("UID en login rápido: " + uid);

        } catch (Exception ex) {
            Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.rapido") + ex.getMessage());
        }
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
        MainApp.cambiarEscena("login.fxml", 800, 600);
    }

}
