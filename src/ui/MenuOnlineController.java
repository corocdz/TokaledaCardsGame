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

/**
 * Controlador encargado de gestionar el menú del modo online.
 *
 * <p>
 * Desde esta pantalla el usuario puede crear una sala, unirse a una sala
 * ({@link SalaOnlineController}) existente, cambiar el idioma, acceder a
 * opciones o volver al menú principal ({@link MenuController}).
 * </p>
 *
 * <h2>Responsabilidades principales:</h2>
 * <ul>
 * <li>Configurar la interfaz del menú online.</li>
 * <li>Gestionar la creación de salas online en Firebase.</li>
 * <li>Gestionar la unión a salas existentes.</li>
 * <li>Actualizar el idioma del usuario.</li>
 * <li>Desconectar al usuario al salir.</li>
 * </ul>
 *
 * <h2>Importancia dentro del proyecto:</h2>
 * <p>
 * Este menú es el punto de entrada al sistema online del juego. Su correcto
 * funcionamiento garantiza que la creación y unión a salas sea segura,
 * intuitiva y consistente con la arquitectura de Firebase.
 * </p>
 * 
 * @author Javier Coronilla Castellano.
 */
public class MenuOnlineController {

    // -------------------------------
    // ELEMENTOS GRÁFICOS
    // -------------------------------
    /**
     * Botón de idioma.
     */
    @FXML
    private Button btnIdioma;
    
    /**
     * Botón para crear sala online.
     */
    @FXML
    private Button btnCrearSala;
    
    /**
     * Botón para unirse a una sala online.
     */
    @FXML
    private Button btnUnirseSala;
    
    /**
     * Botón para volver al menú principal.
     */
    @FXML
    private Button btnVolver;
    
    /**
     * Botón para cerrar la aplicación.
     */
    @FXML
    private Button btnSalir;
    
    /**
     * Botón del popUp de sonido.
     */
    @FXML
    private Button btnOpciones;
    
    /**
     * Imagen para el logo.
     */
    @FXML
    private ImageView logoImage;
    
    /**
     * Imagen para el botón de crear sala.
     */
    @FXML
    private ImageView btnCrearSalaImage;
    
    /**
     * Imagen para el botón de idioma.
     */
    @FXML
    private ImageView btnIdiomaImage;
    
    /**
     * Imagen para el botón de unirse a sala.
     */
    @FXML
    private ImageView btnUnirseSalaImage;
    
    /**
     * Imagen para el botón de volver al menú principal.
     */
    @FXML
    private ImageView btnVolverImage;
    
    /**
     * Capa raíz.
     */
    @FXML
    private StackPane rootMenu;
    
    /**
     * Overlay oscuro para cuando sale el popUp de crear y unirse a sala.
     */
    @FXML
    private Pane overlayOscuro;      

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
     * Método de inicialización automática ejecutado por JavaFX al cargar el
     * FXML.
     * <p>
     * Configura animaciones, imágenes, efectos sonoros y listeners de botones.
     * También aplica un efecto de entrada suave a la pantalla.
     * </p>
     */
    @FXML
    private void initialize() { // Se repite mismo codigo documentado en clases anteriores. Documentaré lo destacble en esta.

        Platform.runLater(() -> Animaciones.fadeIn(rootMenu));

        // Cargar imagen
        logoImage.setImage(new Image(
                getClass().getResource("/ui/graphicResources/imagenes/tokaledaCardsGame.png").toExternalForm()
        ));

        btnIdiomaImage.setImage(IdiomaManager.cargarImagen("btnIdioma"));
        btnCrearSalaImage.setImage(IdiomaManager.cargarImagen("btnCrearSala"));
        btnUnirseSalaImage.setImage(IdiomaManager.cargarImagen("btnUnirseSala"));
        btnVolverImage.setImage(IdiomaManager.cargarImagen("btnVolver"));

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

        btnIdioma.setOnAction(e -> cambiarIdioma());
        btnCrearSala.setOnAction(e -> mostrarPopupCrearSala()); // Muestra el popUp de crearSala.
        btnUnirseSala.setOnAction(e -> mostrarPopupUnirseSala()); // Muestra el popUp de unirseSala.
        btnVolver.setOnAction(e -> MainApp.cambiarEscena("menuPrincipal.fxml", 1200, 1000));
        btnOpciones.setOnAction(e -> Animaciones.mostrarPopupSonido(rootMenu));
        btnSalir.setOnAction(e -> {
            MainApp.desconectarUsuario();
            Platform.exit();
        });
    }

    /**
     * PopUp para crear una sala.
     * <p>
     * En el momento que es pulsado se aplica un overlay oscuro y un desenfoque
     * al fondo para centrar la mirada del usuario en el popUp mostrado.
     * </p>
     *
     * <p>
     * Dentro del popUp, el usuario introducirá una contraseña para la sala y,
     * si es válida, se creará la sala en la base de datos de Firebase Realtime
     * Database.
     * </p>
     *
     * <p>
     * Nota: la contraseña será válida siempre y cuando no sea {@code null} y no
     * sea una cadena vacía.
     * </p>
     */
    private void mostrarPopupCrearSala() {
        try {

            // Activamos el overlay oscuro y lo traemos al frente para tapar la vista actual.
            overlayOscuro.setVisible(true);
            overlayOscuro.toFront();

            // Desenfoque Gaussiano al menú online.
            GaussianBlur blur = new GaussianBlur(20);
            rootMenu.setEffect(blur);

            // Cargamos el fxml con soporte de idioma.
            System.out.println(getClass().getResource("popUpCrearSala.fxml"));
            FXMLLoader loader = new FXMLLoader(getClass().getResource("popUpCrearSala.fxml"),
                    IdiomaManager.getBundle()
            );

            Parent root = loader.load();

            // Creamos el Stage del popUp de forma modal y transparente.
            Stage popUp = new Stage();
            popUp.initModality(Modality.APPLICATION_MODAL); // Modal para que bloquee la ventana principal.
            popUp.initStyle(StageStyle.TRANSPARENT);        // Transparente para que esté sin bordes, permitiendo transparencia.

            // Creamos la escena con trasparencia.
            Scene scene = new Scene(root);
            scene.setFill(Color.TRANSPARENT);
            popUp.setScene(scene);

            // Pasar el Stage al controlador del popup.
            PopUpCrearSalaController controller = loader.getController();
            controller.setStage(popUp);

            // Mostrar el popUp y esperar a que se cierre.
            popUp.showAndWait();

            // Restaurar estado visual.
            overlayOscuro.setVisible(false);
            rootMenu.setEffect(null);

            // Recogemos la contraseña introducida.
            String password = controller.getResultado();

            // Si no es null ni está vacía, se considera válida y usuario crea la sala
            if (password != null && !password.trim().isEmpty()) {
                crearSalaEnFirebase(password);
            }

        } catch (Exception e) { // Capturamos posibles excepciones.
            e.printStackTrace();
            mostrarError("Error al abrir el popup: " + e.getMessage());
        }
    }

    /**
     * Genera un código de sala aleatorio con formato {@code ABC23}.
     *
     * <p>
     * El código está compuesto por:
     * </p>
     * <ul>
     * <li>3 letras mayúsculas generadas aleatoriamente.</li>
     * <li>2 números entre 00 y 99, siempre formateados a dos dígitos.</li>
     * </ul>
     *
     * <p>
     * Este formato se utiliza para identificar salas online de forma sencilla y
     * legible.
     * </p>
     *
     * @return Código de sala generado, en formato {@code String}.
     */
    private String generarCodigoSala() {
        String letras = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

        // Hacemos uso de StringBuilder para ir creando el código de la sala.
        StringBuilder codigo = new StringBuilder();

        // 3 letras aleatorias.
        for (int i = 0; i < 3; i++) {
            int index = (int) (Math.random() * letras.length());
            codigo.append(letras.charAt(index)); // Añadimos la primera parte: las 3 letras primero.
        }

        // 2 números aleatrorios.
        int numeros = (int) (Math.random() * 100);
        codigo.append(String.format("%02d", numeros)); // Añadimos forzosamente que sean 2 dígitos. Si saliese de Math.random() el 3, sería 03.

        // Devolvemos el codigo en formato String.
        return codigo.toString();
    }

    /**
     * Genera un código único que no exista en Firebase.
     *
     * <p>
     * Se generan códigos aleatorios mediante el método
     * {@link #generarCodigoSala()} hasta encontrar uno cuyo nodo
     * {@code salas/CODIGO} no exista en la base de datos.
     * </p>
     *
     * @param db Servicio de base de datos para consultar Firebase.
     * @param token Token de autenticación del usuario.
     * @return Un código de sala único que no esté registrado en Firebase.
     * @throws IOException Si ocurre un error al leer desde Firebase.
     */
    private String generarCodigoUnico(FirebaseDatabaseService db, String token) throws IOException {

        String codigo;

        while (true) { // Se irán creando códigos y comprobando si existen en salas/codigo.

            codigo = generarCodigoSala();
            String nodo = db.leerNodo("salas/" + codigo, token);

            // Cuando se encuentre un código que de null, significa que no existe aún en la bd y podemos utilizar ese código para la sala.
            if (nodo == null || nodo.equals("null")) {
                break; // Código libre.
            }
        }

        // Devolvemos el código.
        return codigo;
    }

    /**
     * Crea una nueva sala en Firebase y redirige al usuario a la pantalla de
     * sala.
     *
     * <p>
     * Este método se ejecuta tras recibir una contraseña válida desde el popup
     * de creación de sala. Su función es generar un código único, construir la
     * estructura inicial de la sala, registrar al usuario como host y
     * actualizar su nodo personal con la sala actual.
     * </p>
     *
     * <p>
     * Durante el proceso se crean los siguientes nodos en Firebase:
     * </p>
     * <ul>
     * <li>{@code salas/CODIGO} - Contiene los datos completos de la sala.</li>
     * <li>{@code usuarios/UID/salaActual} - Indica en qué sala está el
     * usuario.</li>
     * </ul>
     *
     * <p>
     * Además, el código generado se almacena en
     * {@link SalaContext#codigoSalaActual} para que otras pantallas puedan
     * acceder a él sin necesidad de parámetros.
     * </p>
     *
     * @param password Contraseña definida por el usuario para proteger la sala.
     */
    private void crearSalaEnFirebase(String password) {
        try {

            // Obtenemos UID y token del usuario que crea la sala.
            // Esto es importante ya que debemos identificar claramente al host de la partida,
            // puesto que tendrá acceso a funcionalidades que el resto no tendrá.
            String uid = MainApp.usuarioActualUID;
            String token = MainApp.usuarioActualToken;

            // Si no está autenticado por lo que sea, abortamos la creación de la sala.
            if (uid == null || token == null) {
                Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.noUsuario"));
                return;
            }

            // Servicio de base de datos.
            FirebaseDatabaseService db = new FirebaseDatabaseService();

            /**
             * Generamos el codigo único de la sala. generarCodigoÚnico valida
             * los códigos que generarCodigoSala() le pasa cuando lo llama desde
             * dentro. Se valida viendo que no existan en Firebase. Se van
             * ejecutando hasta que se encuentra uno válido.
             */
            String codigo = generarCodigoUnico(db, token);
            System.out.println("Código de sala generado: " + codigo); // debug

            // Creamos un Map que representará la estructura principal del nodo "salas/CODIGO"
            Map<String, Object> sala = new HashMap<>();
            sala.put("host", uid); // El host será el creador de la sala. Guardamos su UID.
            sala.put("contraseña", password); // Guardamos la contraseña introducida en el popUp de crearSala.
            sala.put("maxJugadores", 4); // Definimos el máximo de jugadores permitidos en la sala.
            sala.put("creadaEn", System.currentTimeMillis()); // Timestamp de cuando se creó la sala.

            // Subnodo jugadores: el host entra automáticamente
            Map<String, Object> jugadores = new HashMap<>();

            // Datos específicos del host. Por ahora solo timestamp de cuando se unió.
            Map<String, Object> hostData = new HashMap<>();
            // Guardamos el momento en el que el host se une a la sala.
            hostData.put("unidoEn", System.currentTimeMillis());

            // Asociamos los datos del host a su UID dentro del mapa de jugadores.
            jugadores.put(uid, hostData);
            // Añadimos el subnodo "jugadores" a la estructura principal de la sala.
            sala.put("jugadores", jugadores);

            // Guardamos sala en Firebase
            // Persisistimos sala en el nodo "salas/CODIGO". PUT sobre "salas/CODIGO"
            db.guardarSala(codigo, sala, token);

            // Actualizamos usuario con salaActual = codigo.
            // Mapa con los cambios a aplicar en el nodo del usuario.
            Map<String, Object> datosUsuario = new HashMap<>();
            datosUsuario.put("salaActual", codigo);

            // Actualizamos usuario con salaActual = codigo
            db.actualizarCamposUsuario(uid, datosUsuario, token);

            // Guardar código en memoria para la pantalla de sala
            // código de sala estático para que otras pantallas puedan 
            // acceder a él sin tener que recibirlo como parámetro.
            SalaContext.codigoSalaActual = codigo;

            // Cargamos pantalla de Sala Online.
            MainApp.cambiarEscena("salaOnline.fxml", 1200, 1000);

        } catch (Exception e) { // Capturamos posible excepción por cualquier problema de conexión, con Firebase, etc.
            e.printStackTrace();
            Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.crearSala") + e.getMessage());
        }
    }

    /**
     * PopUp para unirse a una sala.
     * <p>
     * Muestra un popUp modal que permite al usuario introducir el código de
     * sala y la contraseña para unirse a una sala existente en Firebase.
     * Durante la visualización del popup, se aplica un overlay oscuro y un
     * desenfoque al menú principal para centrar la atención del usuario.
     * </p>
     *
     * <p>
     * Una vez el usuario cierra el popup, se recuperan los valores
     * introducidos. Si el código y la contraseña son válidos (no {@code null}
     * ni cadenas vacías), se llama al método
     * {@link #unirseASala(String, String)} para procesar la unión.
     * </p>
     */
    private void mostrarPopupUnirseSala() {
        try {

            // Mostramos overlay
            overlayOscuro.setVisible(true);
            overlayOscuro.toFront();

            // Desenfoque
            GaussianBlur blur = new GaussianBlur(20);
            rootMenu.setEffect(blur);

            // Cargamos el fxml del popUp con ResourceBundles para traducir textos.
            FXMLLoader loader = new FXMLLoader(getClass().getResource("popUpUnirseSala.fxml"),
                    IdiomaManager.getBundle()
            );

            Parent root = loader.load();

            // Creamos ventana modal para el popUP.
            Stage popup = new Stage();

            // Modalidad.
            popup.initModality(Modality.APPLICATION_MODAL);

            // Transparente.
            popup.initStyle(StageStyle.TRANSPARENT);

            // Creamos escena del popUp y permitimos transparencias.
            Scene scene = new Scene(root);
            scene.setFill(Color.TRANSPARENT);

            // Asignamos la escena al popUp.
            popup.setScene(scene);

            // Controlador del popUp
            PopUpUnirseController controller = loader.getController();

            // Le pasamos el stage para que pueda cerrarlo desde dentro.
            controller.setStage(popup);

            // Bloquea la ejecución hasta que usuario cieere el popUp.
            popup.showAndWait();

            // Quitamos desenfoque y overley del menú online.
            overlayOscuro.setVisible(false);
            rootMenu.setEffect(null);

            // Recuperamos los datos del popUp. Codigo y contraseña.
            String codigo = controller.getCodigo();
            String password = controller.getPassword();

            // Validamos los campos. Si no estan todos los campos, no hacemos nada
            if (codigo == null || password == null || codigo.isEmpty() || password.isEmpty()) {
                return;
            }

            // Llamamos al método que realiza todas las validaciones y unión real.
            unirseASala(codigo, password);

        } catch (Exception e) { // Capturamos posibles excepciones.
            e.printStackTrace();
            mostrarError("Error al abrir el popup: " + e.getMessage());
        }
    }

    /**
     * Intenta unir al usuario autenticado a una sala existente en Firebase.
     *
     * <p>
     * Este método realiza una serie de validaciones antes de permitir la
     * entrada del usuario a la sala:
     * </p>
     * <ul>
     * <li>Comprueba que el usuario esté autenticado.</li>
     * <li>Verifica que la sala exista en Firebase.</li>
     * <li>Comprueba que el usuario no esté expulsado de la sala.</li>
     * <li>Valida que la contraseña introducida coincida con la almacenada.</li>
     * <li>Verifica que el usuario no esté ya en otra sala.</li>
     * <li>Comprueba que la sala no esté en partida.</li>
     * <li>Comprueba que la sala no esté llena.</li>
     * </ul>
     *
     * <p>
     * Si todas las validaciones son correctas, el usuario se añade al subnodo
     * {@code jugadores} de la sala, se actualiza su nodo personal con
     * {@code salaActual}, se guarda el código en {@link SalaContext} y se
     * redirige a la pantalla de sala online.
     * </p>
     *
     * @param codigo Código de la sala a la que se desea acceder.
     * @param password Contraseña introducida por el usuario para validar el
     * acceso.
     */
    private void unirseASala(String codigo, String password) {
        try { // Documentaré lo relevante.

            String uid = MainApp.usuarioActualUID;
            String token = MainApp.usuarioActualToken;

            if (uid == null || token == null) {
                Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.noUsuario"));
                return;
            }

            FirebaseDatabaseService db = new FirebaseDatabaseService();

            // Leemos el estado de la partida (si existe) para saber si ha comenzado o no.
            String estadoPartida = db.leerNodo("salas/" + codigo + "/partida/estado", token);

            // leemos la sala completa desde Firebase.
            String jsonSala = db.leerNodo("salas/" + codigo, token);

            // Si sala no existe, error.
            if (jsonSala == null || jsonSala.equals("null")) {
                Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.salaNoExiste"));
                return;
            }

            // Convertimos el JSON de la sala a un Map para acceder a sus campos.
            Map<String, Object> sala = new com.google.gson.Gson().fromJson(jsonSala, Map.class);

            // Por seguridad, retomamos el uid del usuario. Aunque ya lo hicimos arriba.
            uid = MainApp.usuarioActualUID;

            // Comprobamos si el usuario que quiere unirse a la sala está dentro del nodo de expulsados (es decir, fue expulsado con anterioridad de la sala.)
            Map<String, Object> expulsados = (Map<String, Object>) sala.get("expulsados");

            // Si mapa no es nulo y contiene el uid del usuario significa que fue expulsado y NO se podrá unir.
            if (expulsados != null && expulsados.containsKey(uid)) {
                Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.expulsado"));
                return;
            }

            // Validamos contraseña obteniendo la contraseña almacenada en el nodo de dicha sala.
            String passwordReal = sala.get("contraseña").toString();

            // Si no coinciden contraseñas, error.
            if (!passwordReal.equals(password)) {
                Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.password"));
                return;
            }

            // Validamos que el usuario no esté ya en otra sala.
            String jsonUsuario = db.leerNodo("usuarios/" + uid, token);
            Map<String, Object> datosUsuario = new com.google.gson.Gson().fromJson(jsonUsuario, Map.class);

            // Si salaActual no es nulo ni "null" o no existe, el usuario ya estaría en una sala, error.
            if (datosUsuario.get("salaActual") != null && !datosUsuario.get("salaActual").equals("null")) {
                Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.yaEnSala"));
                return;
            }

            // Comprobamos si la sala está en partida. Si no es nulo ni null, comprobamos lo que pone.
            if (estadoPartida != null && !estadoPartida.equals("null")) {

                // Recibimos el estado limpio.
                String estadoLimpio = estadoPartida.replace("\"", "");

                // Si el estado recibido era iniciada, NO podremos entrar. Error.
                if (estadoLimpio.equals("iniciada")) {
                    Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.enPartida"));
                    return;
                }
            }

            // Validar que la sala no esté llena.
            Map<String, Object> jugadores = (Map<String, Object>) sala.get("jugadores");
            double maxJugadores = (double) sala.get("maxJugadores");

            // Si el número de jugadores acutales es mayor o igual al máximo, no se podrá entrar, la sala estaría llena. Error.
            if (jugadores.size() >= maxJugadores) {
                Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.salaLlena"));
                return;
            }

            // Si no ha saltado ningún error previo, permitimos al usuario entrar en la sala.
            // Creamos un mapa con los datos del jugador que se une.         
            Map<String, Object> data = new HashMap<>();

            // Guardamos el momento en el que se une.
            data.put("unidoEn", System.currentTimeMillis());

            // Insertamos al usuario en el subnodo "salas/CODIGO/jugadores" usando su UID como clave.
            jugadores.put(uid, data);
            sala.put("jugadores", jugadores);

            // Persistimos la sala actualizada en Firebase.
            db.guardarSala(codigo, sala, token);

            // Actualizamos usuario. Ponemos su nodo salaActual con el codigo de la sala a la que se ha unido.
            Map<String, Object> updateUser = new HashMap<>();
            updateUser.put("salaActual", codigo);

            // Actualizamos el nodo "usuarios/UID" en Firebase
            db.actualizarCamposUsuario(uid, updateUser, token);

            // Guardamos el código de la sala en estático.
            SalaContext.codigoSalaActual = codigo;

            // Entramos a la sala.
            MainApp.cambiarEscena("salaOnline.fxml", 1200, 1000);

        } catch (Exception e) { // Capturamos posibles excepciones.
            e.printStackTrace();
            Animaciones.mostrarError(rootMenu, bundle.getString("menuOnlineController.error.unirse") + e.getMessage());
        }
    }

    /**
     * Muestra un cuadro de diálogo de error con el mensaje indicado.
     *
     * <p>
     * Este método encapsula la creación de un {@link Alert} de tipo ERROR para
     * notificar al usuario de un fallo ocurrido durante la ejecución. El
     * mensaje se muestra de forma modal mediante {@code showAndWait()}.
     * </p>
     *
     * @param mensaje Texto del error que se mostrará al usuario.
     */
    private void mostrarError(String mensaje) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(null);
        alert.setContentText(mensaje);
        alert.showAndWait();
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

        MainApp.cambiarEscena("menuOnline.fxml", 1200, 1000);
    }

}
