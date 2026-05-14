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
import ui.audio.ButtonSound;

public class MenuOnlineController {

    @FXML
    private Button btnIdioma;
    @FXML
    private Button btnCrearSala;
    @FXML
    private Button btnUnirseSala;
    @FXML
    private Button btnVolver;
    @FXML
    private Button btnSalir;
    @FXML
    private Button btnOpciones;
    @FXML
    private ImageView logoImage;
    @FXML
    private ImageView btnCrearSalaImage;
    @FXML
    private ImageView btnIdiomaImage;
    @FXML
    private ImageView btnUnirseSalaImage;
    @FXML
    private ImageView btnVolverImage;
    @FXML
    private StackPane rootMenu;
    @FXML
    private Pane overlayOscuro;

    private final ResourceBundle bundle = IdiomaManager.getBundle();
    
    private final FirebaseDatabaseService dbService = new FirebaseDatabaseService();

    @FXML
    private void initialize() {

        Platform.runLater(() -> Animaciones.fadeInPro(rootMenu));        
        
        // Cargar imagen
        logoImage.setImage(new Image(
                getClass().getResource("/ui/graphicResources/imagenes/tokaledaCardsGame.png").toExternalForm()
        ));

        btnIdiomaImage.setImage(IdiomaManager.cargarImagen("btnIdioma"));
        btnCrearSalaImage.setImage(IdiomaManager.cargarImagen("btnCrearSala"));
        btnUnirseSalaImage.setImage(IdiomaManager.cargarImagen("btnUnirseSala"));
        btnVolverImage.setImage(IdiomaManager.cargarImagen("btnVolver"));

        btnIdioma.setOnAction(e -> cambiarIdioma());

        Animaciones.animarLogo(logoImage);

        Animaciones.animarBoton(btnCrearSala);
        Animaciones.animarBoton(btnUnirseSala);
        Animaciones.animarBoton(btnVolver);
        Animaciones.animarBoton(btnOpciones);
        Animaciones.animarBoton(btnSalir);
        Animaciones.animarBoton(btnIdioma);
        
        ButtonSound.activar(btnCrearSala);
        ButtonSound.activar(btnUnirseSala);
        ButtonSound.activar(btnVolver);
        ButtonSound.activar(btnOpciones);
        ButtonSound.activar(btnSalir);
        ButtonSound.activar(btnIdioma);

        btnCrearSala.setOnAction(e -> mostrarPopupCrearSala());
        btnUnirseSala.setOnAction(e -> mostrarPopupUnirseSala());
        btnVolver.setOnAction(e -> MainApp.cambiarEscena("menuPrincipal.fxml", 800, 600));
        btnOpciones.setOnAction(e -> Animaciones.mostrarPopupSonido(rootMenu));
        btnSalir.setOnAction(e -> {
            MainApp.desconectarUsuario();
            Platform.exit();
        });
    }

    // =========================
    // CREAR SALA
    // =========================
    private void mostrarPopupCrearSala() {
        try {

            overlayOscuro.setVisible(true);
            overlayOscuro.toFront();

            GaussianBlur blur = new GaussianBlur(20);
            rootMenu.setEffect(blur);

            // 1. Cargar el FXML del popup
            System.out.println(getClass().getResource("popUpCrearSala.fxml"));
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("popUpCrearSala.fxml"),
                    IdiomaManager.getBundle()
            );

            Parent root = loader.load();

            // 2. Crear el Stage del popup
            Stage popup = new Stage();
            popup.initModality(Modality.APPLICATION_MODAL); // bloquea la ventana principal
            popup.initStyle(StageStyle.TRANSPARENT);        // sin bordes, permite transparencia

            // 3. Crear la escena con transparencia
            Scene scene = new Scene(root);
            scene.setFill(Color.TRANSPARENT);

            popup.setScene(scene);

            // 4. Pasar el Stage al controlador del popup
            PopUpCrearSalaController controller = loader.getController();
            controller.setStage(popup);

            // 5. Mostrar el popup (espera hasta que se cierre)
            popup.showAndWait();

            overlayOscuro.setVisible(false);
            rootMenu.setEffect(null);

            // 6. Recuperar el resultado
            String password = controller.getResultado();

            if (password != null && !password.trim().isEmpty()) {
                crearSalaEnFirebase(password);
            }

        } catch (Exception e) {
            e.printStackTrace();
            mostrarError("Error al abrir el popup: " + e.getMessage());
        }
    }

    /**
     * Genera un código tipo ABC23 (3 letras + 2 números).
     */
    private String generarCodigoSala() {
        String letras = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder codigo = new StringBuilder();

        // 3 letras
        for (int i = 0; i < 3; i++) {
            int index = (int) (Math.random() * letras.length());
            codigo.append(letras.charAt(index));
        }

        // 2 números
        int numeros = (int) (Math.random() * 100);
        codigo.append(String.format("%02d", numeros));

        return codigo.toString();
    }

    /**
     * Genera un código que no exista en Firebase bajo salas/CODIGO.
     */
    private String generarCodigoUnico(FirebaseDatabaseService db, String token) throws IOException {
        String codigo;

        while (true) {
            codigo = generarCodigoSala();
            // leerNodo es genérico: le pasamos "salas/CODIGO"
            String nodo = db.leerNodo("salas/" + codigo, token);

            if (nodo == null || nodo.equals("null")) {
                break; // código libre
            }
        }

        return codigo;
    }

    /**
     * Crea la sala en Firebase: - salas/CODIGO - usuarios/UID/salaActual =
     * CODIGO - guarda el código en SalaContext para usarlo en la pantalla de
     * sala
     */
    private void crearSalaEnFirebase(String password) {
        try {
            String uid = MainApp.usuarioActualUID;
            String token = MainApp.usuarioActualToken;

            if (uid == null || token == null) {
                Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.noUsuario"));
                return;
            }

            FirebaseDatabaseService db = new FirebaseDatabaseService();

            // 1. Generar código único
            String codigo = generarCodigoUnico(db, token);
            System.out.println("Código de sala generado: " + codigo);

            // 2. Crear datos de la sala
            Map<String, Object> sala = new HashMap<>();
            sala.put("host", uid);
            sala.put("password", password);
            sala.put("maxJugadores", 6);
            sala.put("creadaEn", System.currentTimeMillis());

            // Subnodo jugadores: el host entra automáticamente
            Map<String, Object> jugadores = new HashMap<>();
            Map<String, Object> hostData = new HashMap<>();
            hostData.put("joinedAt", System.currentTimeMillis());
            jugadores.put(uid, hostData);
            sala.put("jugadores", jugadores);

            // 3. Guardar sala en Firebase
            // NECESITAS un método en FirebaseDatabaseService como:
            // public void guardarSala(String codigo, Map<String,Object> datos, String token)
            // que haga un PUT/PATCH en "salas/" + codigo + ".json"
            db.guardarSala(codigo, sala, token);

            // 4. Actualizar usuario con salaActual = codigo
            Map<String, Object> datosUsuario = new HashMap<>();
            datosUsuario.put("salaActual", codigo);
            db.actualizarCamposUsuario(uid, datosUsuario, token);

            // 5. Guardar código en memoria para la pantalla de sala
            SalaContext.codigoSalaActual = codigo;

            // 6. Cargar pantalla de sala
            MainApp.cambiarEscena("salaOnline.fxml", 800, 600);

        } catch (Exception e) {
            e.printStackTrace();
            Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.crearSala") + e.getMessage());
        }
    }

    // =========================
    // UNIRSE A SALA (LO DEJAMOS IGUAL POR AHORA)
    // =========================
    private void mostrarPopupUnirseSala() {
        try {

            overlayOscuro.setVisible(true);
            overlayOscuro.toFront();

            GaussianBlur blur = new GaussianBlur(20);
            rootMenu.setEffect(blur);

            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("popUpUnirseSala.fxml"),
                    IdiomaManager.getBundle()
            );

            Parent root = loader.load();

            Stage popup = new Stage();
            popup.initModality(Modality.APPLICATION_MODAL);
            popup.initStyle(StageStyle.TRANSPARENT);

            Scene scene = new Scene(root);
            scene.setFill(Color.TRANSPARENT);
            popup.setScene(scene);

            PopUpUnirseController controller = loader.getController();
            controller.setStage(popup);

            popup.showAndWait();

            overlayOscuro.setVisible(false);
            rootMenu.setEffect(null);

            String codigo = controller.getCodigo();
            String password = controller.getPassword();

            if (codigo == null || password == null
                    || codigo.isEmpty() || password.isEmpty()) {
                return;
            }

            unirseASala(codigo, password);

        } catch (Exception e) {
            e.printStackTrace();
            mostrarError("Error al abrir el popup: " + e.getMessage());
        }
    }

    private void unirseASala(String codigo, String password) {
        try {
            String uid = MainApp.usuarioActualUID;
            String token = MainApp.usuarioActualToken;

            if (uid == null || token == null) {
                Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.noUsuario"));
                return;
            }

            FirebaseDatabaseService db = new FirebaseDatabaseService();
            String estadoPartida = db.leerNodo("salas/" + codigo + "/partida/estado", token);

            // 1. Leer la sala
            String jsonSala = db.leerNodo("salas/" + codigo, token);

            if (jsonSala == null || jsonSala.equals("null")) {
                Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.salaNoExiste"));
                return;
            }

            Map<String, Object> sala = new com.google.gson.Gson().fromJson(jsonSala, Map.class);

            // Comprobar si estoy expulsado
            uid = MainApp.usuarioActualUID;
            Map<String, Object> expulsados = (Map<String, Object>) sala.get("expulsados");

            if (expulsados != null && expulsados.containsKey(uid)) {
                Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.expulsado"));
                return;
            }

            // 2. Validar contraseña
            String passwordReal = sala.get("password").toString();
            if (!passwordReal.equals(password)) {
                Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.password"));
                return;
            }

            // 3. Validar que el usuario NO esté ya en una sala
            String jsonUsuario = db.leerNodo("usuarios/" + uid, token);
            Map<String, Object> datosUsuario = new com.google.gson.Gson().fromJson(jsonUsuario, Map.class);

            if (datosUsuario.get("salaActual") != null && !datosUsuario.get("salaActual").equals("null")) {
                Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.yaEnSala"));
                return;
            }

            // Comprobar si la sala está en partida
            if (estadoPartida != null && !estadoPartida.equals("null")) {

                String estadoLimpio = estadoPartida.replace("\"", "");

                if (estadoLimpio.equals("iniciada")) {
                    Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.enPartida"));
                    return;
                }
            }

            // 4. Validar que la sala NO esté llena
            Map<String, Object> jugadores = (Map<String, Object>) sala.get("jugadores");
            double maxJugadores = (double) sala.get("maxJugadores");

            if (jugadores.size() >= maxJugadores) {
                Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.salaLlena"));
                return;
            }

            // 5. Añadir al jugador a la sala
            Map<String, Object> data = new HashMap<>();
            data.put("joinedAt", System.currentTimeMillis());
            jugadores.put(uid, data);
            sala.put("jugadores", jugadores);

            db.guardarSala(codigo, sala, token);

            // 6. Actualizar usuario
            Map<String, Object> updateUser = new HashMap<>();
            updateUser.put("salaActual", codigo);
            db.actualizarCamposUsuario(uid, updateUser, token);

            // 7. Guardar código en memoria
            SalaContext.codigoSalaActual = codigo;

            // 8. Entrar en la sala
            MainApp.cambiarEscena("salaOnline.fxml", 800, 600);

        } catch (Exception e) {
            e.printStackTrace();
            Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.unirse") + e.getMessage());
        }
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
        MainApp.cambiarEscena("menuOnline.fxml", 800, 600);
    }

}
