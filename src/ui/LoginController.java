package ui;

import firebase.FirebaseAuthService;
import firebase.FirebaseDatabaseService;
import java.util.HashMap;
import java.util.Map;
import javafx.fxml.FXML;
import i18n.IdiomaManager;
import java.util.ResourceBundle;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import ui.MainApp;
import ui.audio.ButtonSound;

/**
 *
 * Controlador encargado de gestionar la pantalla de inicio de sesión de la
 * aplicación.
 *
 * <p>
 * Esta clase forma parte de la capa de presentación de la interfaz de usuario
 * (UI) y actúa como puente entre la interfaz gráfica definida en
 * {@code login.fxml} y los servicios de autenticación y base de datos
 * proporcionados por Firebase.
 * </p>
 *
 * <h2>Responsabilidades principales:</h2>
 * <ul>
 * <li>Gestionar el inicio de sesión normal y el inicio de sesión rápido (para
 * pruebas).</li>
 * <li>Validar datos básicos introducidos por el usuario.</li>
 * <li>Interpretar los códigos de error devueltos por Firebase
 * Authentication.</li>
 * <li>Actualizar el estado del usuario en Firebase Realtime Database (marcar
 * como conectado, actualizar idioma, registrar última conexión).</li>
 * <li>Gestionar el cambio de idioma desde la pantalla de login.</li>
 * <li>Aplicar animaciones y efectos sonoros a los elementos de la
 * interfaz.</li>
 * </ul>
 *
 * <h2>Importancia dentro del proyecto:</h2>
 * <p>
 * El login es el punto de entrada principal del usuario a la apliación. Desde
 * aquí se establece la sesión, se carga el idioma preferido del usuario y se
 * garantiza que no existan sesiones duplicadas. Su correcto funcionamiento es
 * esencial para la integridad del sistema y la experiencia de usuario.
 * </p>
 *
 * <h2>Decisiones de diseño:</h2>
 * <ul>
 * <li>Se utiliza un sistema de códigos de error personalizados para interpretar
 * correctamente las respuestas de Firebase.</li>
 * <li>Se evita mostrar mensajes genéricos y se prioriza la retroalimentación
 * clara y específica al usuario.</li>
 * <li>Se separa la validación local de la validación remota para mejorar la UX
 * y reducir llamadas innecesarias al servidor.</li>
 * <li>Se actualiza el idioma del usuario en Firebase para mantener coherencia
 * entre sesiones.</li>
 * </ul>
 *
 * @author Javier Coronilla Castellano
 */
public class LoginController {

    // -------------------------------
    // ELEMENTOS GRÁFICOS
    // -------------------------------
    @FXML
    private TextField txtEmail; // TextField para email 
    @FXML
    private PasswordField txtPassword; // PasswordField para contraseña
    @FXML
    private Button btnLogin; // Botón para iniciar sesión
    @FXML
    private Button btnRegistro; // Botón para registrar nueva cuenta
    @FXML
    private Button btnLoginJ1; // Botón de inicio de sesión rapido Jugador1 (debug)
    @FXML
    private Button btnLoginJ2; // Botón de inicio de sesión rapido Jugador2 (debug)
    @FXML
    private Button btnLoginJ3; // Botón de inicio de sesión rapido Jugador3 (debug)
    @FXML
    private Button btnLoginJ4; // Botón de inicio de sesión rapido Jugador4 (debug)
    @FXML
    private Button botonOpciones; // Botón de opciones
    @FXML
    private Button btnIdioma; // Botón de cambiar idioma
    @FXML
    private ImageView botonOpcionesImage; // Imagen de botón de opciones
    @FXML
    private ImageView logoImage; // Imagen de logo de app
    @FXML
    private ImageView btnRegistroImage; // Imagen de botón de registro
    @FXML
    private ImageView btnIniciarSesionImage; // Imagen de botón iniciar sesión
    @FXML
    private ImageView btnIdiomaImage; // imagen de botón de cambiar idioma
    @FXML
    private Label tituloBienvenida; // Texto bienvenida
    @FXML
    private Label textoRegistro; // Texto registro
    @FXML
    private StackPane rootLogin; // Capa raíz

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
     * <p>
     * Configura animaciones, imágenes, efectos sonoros y listeners de botones.
     * También aplica un efecto de entrada suave a la pantalla.
     * </p>
     */
    @FXML
    private void initialize() {

        // Animación de entrada de la pantalla.
        Platform.runLater(() -> Animaciones.fadeIn(rootLogin)); // Ponemos la animación en la cola para que se ejecute cuando esté la UI lista.

        // Cargamos imagen para el logo de la aplicación.
        logoImage.setImage(new Image(
                getClass().getResource("/ui/graphicResources/imagenes/tokaledaCardsGame.png").toExternalForm() // Le pasamos String con la URL de la imagen en el proyecto y la aplicamos al logo. 
        ));

        // Limitamos el campo del email a 50 caracteres máximo. Utilizamos TextFormatter para aceptar o no las actualizaciones del textField (si tiene 50 o menos se permite, si no, no.)
        txtEmail.setTextFormatter(new TextFormatter<>(c
                -> c.getControlNewText().length() <= 50 ? c : null
        ));

        // Limitamos el campo del password a 32 caracteres máximo. Utilizamos TextFormatter para aceptar o no las actualizaciones del passwordField (si tiene 32 o menos se permite, si no, no.)
        txtPassword.setTextFormatter(new TextFormatter<>(c
                -> c.getControlNewText().length() <= 32 ? c : null
        ));

        // Cargamos botones en el idioma detectado. Estos 3 botones tendrán una imagen u otra en función al idioma detectado por IdiomaManager.
        btnIdiomaImage.setImage(IdiomaManager.cargarImagen("btnIdioma"));
        btnRegistroImage.setImage(IdiomaManager.cargarImagen("btnRegistro"));
        btnIniciarSesionImage.setImage(IdiomaManager.cargarImagen("btnIniciarSesion"));

        // Animaciones visuales para los elementos de la pantalla.
        Animaciones.animarLogo(logoImage);
        Animaciones.animarLabelGeneral(tituloBienvenida);
        Animaciones.animarLabelSecundario(textoRegistro);
        Animaciones.animarBoton(btnLogin);
        Animaciones.animarBoton(btnRegistro);
        Animaciones.animarBoton(botonOpciones);
        Animaciones.animarBoton(btnIdioma);

        // Sonidos de botones.
        ButtonSound.activar(btnIdioma);
        ButtonSound.activar(btnRegistro);
        ButtonSound.activar(btnLogin);
        ButtonSound.activar(botonOpciones);

        // Listeners para los botones
        btnLogin.setOnAction(e -> login()); // Login 
        btnRegistro.setOnAction(e -> MainApp.cambiarEscena("registro.fxml", 600, 400)); // Usuario se dirige a la pantalla de Registro Controller.
        btnIdioma.setOnAction(e -> cambiarIdioma()); // Cambia el idioma de la aplicación
        botonOpciones.setOnAction(e -> Animaciones.mostrarPopupSonido(rootLogin)); // Abre el popUp para controlar el volumen de la aplicación.

        // Logins rápidos para el modo debug. Recomendado su uso para tareas de debug.
        btnLoginJ1.setOnAction(e -> loginRapido("jugador1@test.com", "123456"));
        btnLoginJ2.setOnAction(e -> loginRapido("jugador2@test.com", "123456"));
        btnLoginJ3.setOnAction(e -> loginRapido("jugador3@test.com", "123456"));
        btnLoginJ4.setOnAction(e -> loginRapido("jugador4@test.com", "Coro11."));
    }

    /**
     * Método que gestiona el inicio de sesión normal y corriente introducido
     * por un usuario.
     * <p>
     * Realiza validación local, interpreta errores de Firebase y actualiza el
     * estado del usuario en la base de datos. Si el login es correcto, redirige
     * al usuario al menú principal.
     * </p>
     */
    private void login() {

        // Recogemos datos sin espacios sobrantes.
        String email = txtEmail.getText().trim();
        String password = txtPassword.getText().trim();

        // Validación local mínima. Nos aseguramos de que los campos no estén vacíos.
        if (email.isEmpty() || password.isEmpty()) {
            Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.campos"));
            return;
        }

        // Bloque con las validaciones de los campos por Firebase.
        try {

            // Se envían las credenciales al servicio de autenticación.
            // Devuelve un mensaje significativo del motivo causante del error y lo recogemos.
            String result = authService.login(email, password);

            switch (result) { // Dependiendo de lo recibido... Flujo normal (OK) o errores de Firebase.

                case "OK": // Login correcto. Flujo normal de aplicación.
                    break;

                case "CREDENCIALES_INCORRECTAS": // Error genérico de credenciales incorrectas. Ya sea email o contraseña.
                    Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.credenciales"));
                    return;

                case "USER_DISABLED": // La cuenta existe, pero se encuentra deshabilitada.
                    Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.cuentaDesactivada"));
                    return;

                case "INVALID_EMAIL": // El formato del correo no es válido.
                    Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.emailNoValido"));
                    return;

                case "NETWORK_ERROR": // Error de red.
                    Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.desconexion"));
                    return;

                default: // Cualquier otro tipo de error.
                    Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.desconocido"));
                    return;
            }

            // -------------------------
            // LOGIN CORRECTO
            // -------------------------
            // Obtenemos el UID del usuario autenticado.
            String uid = authService.getLocalId();

            // Obtenemos el token de sesión devuelto por Firebase.
            String token = authService.getIdToken();

            // Guardamos los datos en MainApp para mantener la sesión activa.
            MainApp.usuarioActualUID = uid;
            MainApp.usuarioActualToken = token;

            // Leemos el nodo del usuario en la base de datos para obtener su información.
            String nodoJson = dbService.leerNodo("usuarios/" + uid, token);

            boolean yaConectado = false;

            // Si el nodo existe, convertimos el JSON en un mapa para acceder a sus campos.
            if (nodoJson != null && !nodoJson.equals("null")) {
                Map<String, Object> datosExistentes = new com.google.gson.Gson().fromJson(nodoJson, Map.class);

                if (datosExistentes != null) {

                    // Cargamos idioma del usuario. 
                    Object idioma = datosExistentes.get("idioma");
                    if (idioma != null) {
                        IdiomaManager.setIdioma(idioma.toString()); // * Nota. Al iniciar sesión se iniciará con el idioma guardado en Firebase por dicho usuario. 
                    }

                    // Comprobamos si el usuairo ya está conectado desde otro dispositivo.
                    Object conectado = datosExistentes.get("conectado");
                    if (conectado != null && Boolean.TRUE.equals(conectado)) { // Si no lo está, cambiamos su estado a "conectado"
                        yaConectado = true;
                    }
                }
            }

            // Si ya está conectado, bloqueamos login para evitar sesiones duplicadas.
            if (yaConectado) {
                Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.activo"));

                // Reseteamos datos del MainApp.
                MainApp.usuarioActualUID = null;
                MainApp.usuarioActualToken = null;
                return;
            }

            // Mapa para cambios: marcamos como conectado y actualizamos última conexión.
            Map<String, Object> cambios = new HashMap<>();
            cambios.put("conectado", true); // Usuario conectado.
            cambios.put("ultimaConexion", System.currentTimeMillis()); // Nueva última conexión.                       

            // Si el nodo no existía, metemos datos básicos para el usuario.
            if (nodoJson == null || nodoJson.equals("null")) {
                cambios.put("email", email); // Email
                cambios.put("nombre", "Usuario"); // Nombre
                cambios.put("avatar", "default.png"); // Avatar
                cambios.put("idioma", IdiomaManager.getCodigoIdioma()); // Idioma detectado al momento de iniciar sesión
            }

            // Enviamos los cambios a Firebase.
            dbService.actualizarCamposUsuario(uid, cambios, token);

            // Se cambia la pantalla al menú principal luego del login exitoso.
            MainApp.cambiarEscena("menuPrincipal.fxml", 800, 600);
            System.out.println("UID en login: " + uid); // debug

        } catch (Exception ex) { // Capturamos cualquier posible excepción y la mostramos al usario.
            Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.inicio") + ex.getMessage());
        }
    }

    /**
     * Realiza un inicio de sesión automático utilizando credenciales
     * predefinidas.
     * <p>
     * Este método se utiliza exclusivamente para pruebas rápidas durante el
     * desarrollo. Su comportamiento es idéntico al login normal, pero omite la
     * validación local.
     * </p>
     *
     * @param email Correo electrónico del usuario de prueba.
     * @param password Contraseña del usuario de prueba.
     */
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

            String uid = authService.getLocalId();
            String token = authService.getIdToken();
            MainApp.usuarioActualUID = uid;
            MainApp.usuarioActualToken = token;

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

            if (yaConectado) {
                Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.activo"));
                MainApp.usuarioActualUID = null;
                MainApp.usuarioActualToken = null;
                return;
            }

            Map<String, Object> cambios = new HashMap<>();
            cambios.put("conectado", true);
            cambios.put("ultimaConexion", System.currentTimeMillis());

            // SIEMPRE actualizar idioma al iniciar sesión (debug. Esto NO está aplicado para inicios normales con ánimo de utilizar lo guardado en Firebase. Si se quiere cambiar dicho valor en BD, cambiar idioma en pantallas como menú principal.)
            cambios.put("idioma", IdiomaManager.getCodigoIdioma());

            if (nodoJson == null || nodoJson.equals("null")) {
                cambios.put("email", email);
                cambios.put("nombre", "Usuario");
                cambios.put("avatar", "default.png");
                cambios.put("idioma", IdiomaManager.getCodigoIdioma());
            }

            dbService.actualizarCamposUsuario(uid, cambios, token);

            MainApp.cambiarEscena("menuPrincipal.fxml", 800, 600);
            System.out.println("UID en login rápido: " + uid);

        } catch (Exception ex) {
            Animaciones.mostrarError(rootLogin, bundle.getString("loginController.error.rapido") + ex.getMessage());
        }
    }

    /**
     * Cambia el idioma de la interfaz entre español e inglés y recarga la escena.
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

        // Guardamos idioma en Firebase si el usuario ya está logueado. (aplicable en otras pantallas como menú principal, por ejemplo.)
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
        MainApp.cambiarEscena("login.fxml", 800, 600);
    }

}
