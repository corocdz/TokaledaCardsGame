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
import javafx.scene.layout.StackPane;
import ui.audio.ButtonSound;

/**
 * Controlador encargado de gestionar la pantalla de registro de nuevos
 * usuarios.
 *
 * <p>
 * Esta clase forma parte de la capa de presentación (UI) y actúa como puente
 * entre la interfaz gráfica definida en {@code registro.fxml} y los servicios
 * de autenticación y base de datos proporcionados por Firebase.
 * </p>
 *
 * <h2>Responsabilidades principales:</h2>
 * <ul>
 * <li>Validar los datos introducidos por el usuario antes de enviarlos a
 * Firebase.</li>
 * <li>Crear una nueva cuenta mediante Firebase Authentication.</li>
 * <li>Registrar los datos iniciales del usuario en Firebase Realtime
 * Database.</li>
 * </ul>
 *
 * <h2>Importancia dentro del proyecto:</h2>
 * <p>
 * El registro es el punto de entrada para nuevos usuarios. Su correcto
 * funcionamiento garantiza que los datos se almacenen de forma consistente y
 * que la experiencia de usuario sea clara, segura y fluida.
 * </p>
 *
 * <h2>Decisiones de diseño:</h2>
 * <ul>
 * <li>Se aplican limitadores de caracteres mediante {@link TextFormatter} para
 * evitar entradas inválidas.</li>
 * <li>Se separa la validación local de la validación remota.</li>
 * <li>Se almacenan datos iniciales del usuario en Firebase para mantener
 * coherencia entre sesiones.</li>
 * <li>Se utiliza {@link ResourceBundle} para soportar múltiples idiomas.</li>
 * </ul>
 *
 *
 * @author Javier Coronilla Castellano.
 *
 */
public class RegistroController {

    // -------------------------------
    // ELEMENTOS GRÁFICOS
    // -------------------------------
    /**
     * TextField para el nombre de usuario.
     */
    @FXML
    private TextField txtNombre;

    /**
     * TextField para el email del usuario.
     */
    @FXML
    private TextField txtEmail;

    /**
     * Password para la contraseña del usuario.
     */
    @FXML
    private PasswordField txtPassword;

    /**
     * Password para el segundo campo de la contraseña del usuario.
     */
    @FXML
    private PasswordField txtConfirmar;

    /**
     * Imagen del botón de registro.
     */
    @FXML
    private ImageView btnRegistroImage;

    /**
     * Botón de registro.
     */
    @FXML
    private Button btnRegistro;

    /**
     * Imagen del botón de volver.
     */
    @FXML
    private ImageView btnVolverImage;

    /**
     * Botón de opciones.
     */
    @FXML
    private Button botonOpciones;

    /**
     * Imagen del botón de opciones.
     */
    @FXML
    private ImageView botonOpcionesImage;

    /**
     * Botón de volver.
     */
    @FXML
    private Button btnVolver;

    /**
     * Imagen del logo.
     */
    @FXML
    private ImageView logoImage;

    /**
     * Imagen del botón de idioma.
     */
    @FXML
    private ImageView btnIdiomaImage;

    /**
     * Botón de idioma.
     */
    @FXML
    private Button btnIdioma;

    /**
     * Texto para título de la pantalla.
     */
    @FXML
    private Label tituloRegistro;

    /**
     * Otro texto para la pantalla.
     */
    @FXML
    private Label textoVolver;

    /**
     * Capa raíz.
     */
    @FXML
    private StackPane rootRegistro;

    // -------------------------------
    // ELEMENTOS GENERALES
    // -------------------------------
    /**
     * Bundle de idioma cargado dinámicamente según la elección del usuario.
     */
    private final ResourceBundle bundle = IdiomaManager.getBundle();

    /**
     * Servicio encargado de gestionar la autenticación con Firebase.
     */
    private final FirebaseAuthService authService = new FirebaseAuthService();

    /**
     * Servicio encargado de leer y actualizar datos del usuario en Firebase.
     */
    private final FirebaseDatabaseService dbService = new FirebaseDatabaseService();

    /**
     * Método de inicialización automática ejecutado por JavaFX al cargar el
     * FXML.
     *
     * <p>
     * Configura animaciones, imágenes, efectos sonoros, limitadores de texto y
     * listeners de botones. También aplica un efecto de entrada suave a la
     * pantalla.
     * </p>
     */
    @FXML
    private void initialize() {

        // Animación de entrada de la pantalla.
        // Ponemos la animación en la cola para que se ejecute cuando esté la UI lista.
        Platform.runLater(() -> Animaciones.fadeIn(rootRegistro));

        // Cargamos imagen del logo.
        logoImage.setImage(new Image(
                getClass().getResource("/ui/graphicResources/imagenes/tokaledaCardsGame.png").toExternalForm()
        ));

        // Limitamos nombre de usuario a 10 caracteres máximo.
        txtNombre.setTextFormatter(new TextFormatter<>(c
                -> c.getControlNewText().length() <= 10 ? c : null
        ));

        // Limitamos email de usuario a 50 caracteres máximo.
        txtEmail.setTextFormatter(new TextFormatter<>(c
                -> c.getControlNewText().length() <= 50 ? c : null
        ));

        // Limitamos contraseña de usuario a 32 caracteres máximo.
        txtPassword.setTextFormatter(new TextFormatter<>(c
                -> c.getControlNewText().length() <= 32 ? c : null
        ));

        // Limitamos el campo de confirmar contraseña de usuario a 32 caracteres máximo.
        txtConfirmar.setTextFormatter(new TextFormatter<>(c
                -> c.getControlNewText().length() <= 32 ? c : null
        ));

        // Cargamos botones en el idioma detectado. Estos 3 botones tendrán una imagen u otra en función al idioma detectado por IdiomaManager.
        btnIdiomaImage.setImage(IdiomaManager.cargarImagen("btnIdioma"));
        btnRegistroImage.setImage(IdiomaManager.cargarImagen("btnRegistro"));
        btnVolverImage.setImage(IdiomaManager.cargarImagen("btnVolver"));

        // Animaciones visuales para los elementos de la pantalla.
        Animaciones.animarLogo(logoImage);
        Animaciones.animarBoton(btnVolver);
        Animaciones.animarBoton(btnRegistro);
        Animaciones.animarLabelGeneral(tituloRegistro);
        Animaciones.animarLabelSecundario(textoVolver);

        // Sonidos de botones.
        ButtonSound.activar(btnIdioma);
        ButtonSound.activar(btnRegistro);
        ButtonSound.activar(btnVolver);
        ButtonSound.activar(botonOpciones);

        // Listeners para los botones.
        btnIdioma.setOnAction(e -> cambiarIdioma()); // Cambiamos idioma.
        btnRegistro.setOnAction(e -> registrar()); // Intentamos registrar usuario nuevo.
        btnVolver.setOnAction(e -> MainApp.cambiarEscena("login.fxml", 1200, 1000)); // Cambiamos escena a pantalla de Login
        botonOpciones.setOnAction(e -> Animaciones.mostrarPopupSonido(rootRegistro)); // popUp de sonido.
    }

    /**
     * Gestiona el proceso de registro de un nuevo usuario.
     *
     * <p>
     * Realiza validación local, interpreta errores devueltos por Firebase y
     * registra los datos iniciales del usuario en la base de datos. Si el
     * registro es correcto, redirige al menú principal.
     * </p>
     */
    private void registrar() {

        // Recogemos los datos introducidos sin espacios sobrantes.
        String nombre = txtNombre.getText().trim();
        String email = txtEmail.getText().trim();
        String password = txtPassword.getText().trim();
        String confirmar = txtConfirmar.getText().trim();

        // Validamos que todos los campos estén rellenados.
        if (nombre.isEmpty() || email.isEmpty() || password.isEmpty() || confirmar.isEmpty()) {
            Animaciones.mostrarError(rootRegistro, bundle.getString("registroController.error.campos"));
            return;
        }

        // Validación de formato de email.
        if (!emailValido(email)) {
            Animaciones.mostrarError(rootRegistro, bundle.getString("registro.errorFormatoEmail"));
            return;
        }

        // Validación de formato de contraseña.
        if (!passwordValida(password)) {
            Animaciones.mostrarError(rootRegistro, bundle.getString("registro.errorFormatoPassword"));
            return;
        }

        // Validación de coincidencia entre los campos para la contraseña.
        if (!password.equals(confirmar)) {
            Animaciones.mostrarError(rootRegistro, bundle.getString("registroController.error.password"));
            return;
        }

        try {

            // Enviamos la solicitud de registro del usuario a Firebase Authentication.
            // Recogemos lo devuelto por Firebase y lo tratamos.
            String result = authService.register(email, password);

            switch (result) { // Dependiendo de lo recibido... Flujo normal (OK) o errores de Firebase.

                case "OK": // Registro correcto. Obtenemos el UID y el token del nuevo usuario.

                    String uid = authService.getLocalId();
                    String token = authService.getIdToken();

                    // Datos iniciales del usuario en la base de datos.
                    Map<String, Object> datos = new HashMap<>();
                    datos.put("email", email); // Email.
                    datos.put("nombre", nombre); // Nombre.
                    datos.put("avatar", "default.png"); // Avatar por defecto. No implementado.
                    datos.put("conectado", true); // Estado de conexión actual.
                    datos.put("ultimaConexion", System.currentTimeMillis()); // Timestamp de última conexión del usuario.
                    datos.put("idioma", IdiomaManager.getCodigoIdioma()); // Idioma para el usuario.

                    // Guardamos el nuevo usuario en el nodo de usuarios en Firebase.
                    dbService.guardarUsuario(uid, datos, token);

                    // Guardamos sesión en memoria.
                    MainApp.usuarioActualUID = uid;
                    MainApp.usuarioActualToken = token;

                    // Al ser exitoso el registro del usuario, entramos directamente en el menú principal de la apliación.
                    MainApp.cambiarEscena("menuPrincipal.fxml", 1200, 1000);
                    return;

                case "EMAIL_EXISTS": // Si el email ya existía en Firebase Authentication, error.
                    Animaciones.mostrarError(rootRegistro, bundle.getString("registro.errorEmailRegistrado"));
                    return;

                case "INVALID_EMAIL": // Si el email no es válido según Firebase, error.
                    Animaciones.mostrarError(rootRegistro, bundle.getString("registro.errorFormatoEmail"));
                    return;

                case "WEAK_PASSWORD": // Si la contraseña es débil según Firebase, error.
                    Animaciones.mostrarError(rootRegistro, bundle.getString("registro.errorFormatoPassword"));
                    return;

                case "MISSING_PASSWORD": // Cuando se envía un registro sin contraseña.
                    Animaciones.mostrarError(rootRegistro, bundle.getString("registro.errorSinPassword"));
                    return;

                case "NETWORK_ERROR": // Cuando se produce un error de conexión.
                    Animaciones.mostrarError(rootRegistro, bundle.getString("registro.errorConexion"));
                    return;

                default: // Error no contemplado.
                    Animaciones.mostrarError(rootRegistro, bundle.getString("registro.errorDesconocido"));
                    return;
            }

        } catch (Exception ex) { // Capturamos posibles excepciones.
            Animaciones.mostrarError(rootRegistro, bundle.getString("registroController.error.excepcion") + ex.getMessage());
        }
    }

    /**
     * Valida el formato del email mediante expresión regular.
     *
     * Debe cumplir: Una parte inicial con letras o números + (+_.-) Un solo @
     * Dominio con letras/numeros/puntos/guiones Punto final seguido de una
     * extensión de mínimo 2 letras
     *
     * @param email El email introducido por el usuario.
     * @return True si es válido, false si no.
     */
    private boolean emailValido(String email) {
        return email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    /**
     * Valida la fortaleza de la contraseña mediante expresión regular.
     *
     * Debe cumplir: Mínimo 6 caracteres. Al menos 1 letra mayúscula. Debe
     * contener al menos 2 dígitos. Debe contener al menos un símbolo especial.
     *
     * @param password La contraseña introducida por el usuario.
     * @return True si es válida, false si no.
     */
    private boolean passwordValida(String password) {
        return password.matches("^(?=.*[A-Z])(?=(?:.*\\d){2,})(?=.*[^A-Za-z0-9]).{6,}$");
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

        // Guardam idioma en Firebase si el usuario ya está logueado. (aplicable en otras pantallas como menú principal, por ejemplo.)
        if (MainApp.usuarioActualUID != null && MainApp.usuarioActualToken != null) {
            try {
                Map<String, Object> cambios = new HashMap<>();
                cambios.put("idioma", nuevo);
                dbService.actualizarCamposUsuario(MainApp.usuarioActualUID, cambios, MainApp.usuarioActualToken);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        // Recargamos la escena para mostrar la pantalla con el idioma cambiado.
        MainApp.cambiarEscena("registro.fxml", 1200, 1000);
    }

}
