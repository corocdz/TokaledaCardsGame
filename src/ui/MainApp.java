package ui;

import i18n.IdiomaManager;
import firebase.BDPartidaService;
import firebase.FirebaseDatabaseService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.stage.Stage;
import javafx.util.Duration;
import ui.audio.MusicManager;

/**
 * Clase principal de Tokaleda Cards Game.
 *
 * <p>
 * Gestiona el ciclo de vida completo de la aplicación, incluyendo:
 * </p>
 *
 * <ul>
 * <li>Inicialización de la ventana principal.</li>
 * <li>Carga dinámica de escenas mediante FXML.</li>
 * <li>Gestión de la sesión del usuario (UID y token).</li>
 * <li>Control de música global según el contexto (menú o partida).</li>
 * <li>Configuración de vista según la pantalla activa.</li>
 * <li>Sincronización periódica del estado de la partida online.</li>
 * <li>Limpieza automática al cerrar la aplicación mediante shutdown hook.</li>
 * </ul>
 *
 * <h2>Importancia dentro del proyecto</h2>
 * <p>
 * Esta clase es el núcleo del sistema. Centraliza la gestión de escenas,
 * controla la sesión del usuario y coordina la transición entre pantallas.
 * Además, garantiza que el usuario quede correctamente desconectado incluso si
 * la aplicación se cierra de forma inesperada, evitando errores de sesiones
 * abiertas en los usuarios.
 * </p>
 *
 * <h2>Decisiones de diseño</h2>
 * <ul>
 * <li>Se utiliza un método estático {@code cambiarEscena()} para permitir que
 * cualquier controlador pueda solicitar un cambio de pantalla.</li>
 * <li>Se almacenan UID y token como variables estáticas para mantener la sesión
 * accesible desde cualquier parte del programa.</li>
 * <li>Se emplea un shutdown hook para asegurar la desconexión del usuario
 * incluso si se cierra con Alt+F4 o la X de la ventana.</li>
 * <li>Se integra un refresco periódico mediante {@link Timeline} para
 * actualizar el estado de la partida online sin bloquear la interfaz.</li>
 * </ul>
 *
 * @author Javier Coronilla Castellano.
 */
public class MainApp extends Application {

    /**
     * Ventana principal de la aplicación.
     */
    private static Stage primaryStage;

    /**
     * UID del usuario autenticado actualmente.
     */
    public static String usuarioActualUID = null;

    /**
     * Token de sesión devuelto por Firebase Authentication.
     */
    public static String usuarioActualToken = null;

    /**
     * Controlador activo de la sala online, si existe.
     */
    public static SalaOnlineController controladorSalaActual = null;

    /**
     * Método de entrada principal de JavaFX.
     *
     * <p>
     * Configura la ventana principal de Tokaleda Cards Game. Se inicializa la
     * interfaz gráfica, se carga la pantalla de login (ventana principal de
     * esta aplicación) con el idioma correspondiente y activa la música desde
     * dicha pantalla inicial. También registra un shutdown hook para garantizar
     * la desconexión del usuario incluso si la aplicación se cierra de forma
     * abrupta.
     * </p>
     *
     * @param stage Ventana principal proporcionada por JavaFX.
     * @throws Exception Si ocurre un error durante la inicialización de la interfaz.
     */
    @Override
    public void start(Stage stage) throws Exception {

        // Guardamos la referencia a la ventana principal para pòder manipularla desde otros métodos estáticos (como cambiarEscena()).
        primaryStage = stage;
        primaryStage.setTitle("Tokaleda Cards Game"); // Título de la ventana principal.
        primaryStage.getIcons().add(new Image(
                getClass().getResourceAsStream("/ui/graphicResources/imagenes/logoApp.png")
        )); // Cargamos un logo personalizado para la aplicación.

        // Cargamos pantalla principal con el idioma actual. Necesario el uso de FXMLLoader.
        FXMLLoader loader = new FXMLLoader(getClass().getResource("login.fxml"));
        // Debemos asignar el ResourceBundle para que los textos se muestren en el idioma seleccionado.
        loader.setResources(IdiomaManager.getBundle());
        Parent root = loader.load(); // Árbol de nodos fxml.

        // Dimensiones, centrado y mostramos UI.
        primaryStage.setScene(new Scene(root, 1200, 1000));
        primaryStage.centerOnScreen();
        primaryStage.show();

        // Música del menú principal. Se extiende en loop por toda la aplicación menos en las partidas
        MusicManager.playMenuMusic();

        // Indicamos a JavaFX que debe cerrar la aplicación cuando se cierra una ventana. Así no se ejecuta en segundo plano.
        Platform.setImplicitExit(true);

        // Hook de cierre para desconectar al usuario definitivamente incluso si se cierra con Alt+F4 o cierre de ventana normal (X).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println(">>> SHUTDOWN HOOK EJECUTADO <<<"); // debug
            desconectarUsuario();
        }));

    }

    /**
     * Cambia la escena actual por otra definida en un archivo FXML.
     *
     * <p>
     * Este método centraliza la carga de pantallas y aplica configuraciones
     * adicionales según el tipo de controlador cargado:
     * </p>
     *
     * @param fxml Nombre del archivo FXML a cargar.
     * @param width Ancho de la nueva escena.
     * @param height Alto de la nueva escena.
     * @return El controlador asociado al FXML cargado.
     */
    public static Object cambiarEscena(String fxml, int width, int height) {
        try {

            // Creamos un FXMLLoader para el nuevo archivo fxml solicitado.
            FXMLLoader loader = new FXMLLoader(MainApp.class.getResource(fxml));

            // Nuevamente, asignamos el ResourceBundle para que los textos se carguen en le idioma indicado.
            loader.setResources(IdiomaManager.getBundle());

            Parent root = loader.load();

            // Obtenemos controlador asociado al fxml cargado.
            Object controller = loader.getController();

            // Cambiamos a una nueva escenna con el tamaño solicitado.
            Scene scene = new Scene(root, width, height);

            // Asignamos la escena a la ventana principal.
            primaryStage.setScene(scene);
            primaryStage.centerOnScreen();
            primaryStage.show();

            // Si la escena es de una sala online, guardamos el controlador.
            if (controller instanceof SalaOnlineController) {
                controladorSalaActual = (SalaOnlineController) controller;
            } else {
                controladorSalaActual = null;
            }

            // debug
            System.out.println(">>> Controlador cargado: " + controller.getClass().getName());

            // Si la escena es de una partida (cualquier modo), lo tratamos.
            if (controller instanceof PartidaControllerBase) {
                System.out.println(">>> ENTRANDO EN PARTIDA: " + controller.getClass().getName()); // debug

                // Cambiamos la música de fondo a la música para partidas.
                if (!MusicManager.isPlaying("partida")) {
                    System.out.println(">>> CAMBIANDO A MÚSICA DE PARTIDA"); // debug
                    MusicManager.playPartidaMusic(); // Método que pone la música de partida de fondo activa.
                }
                // Modo pantalla completa.
                primaryStage.setFullScreenExitHint("");
                primaryStage.setFullScreenExitKeyCombination(
                        new KeyCodeCombination(KeyCode.ESCAPE)
                );
                primaryStage.setFullScreen(true);

                PartidaControllerBase pc = (PartidaControllerBase) controller;

                pc.init(SalaContext.codigoSalaActual, MainApp.usuarioActualUID, MainApp.usuarioActualToken);
                pc.configurarLayoutEscena(primaryStage);

                // REFRESCO DE PARTIDA
                BDPartidaService bdPartida = new BDPartidaService(new FirebaseDatabaseService());

                Timeline timeline = new Timeline(
                        new KeyFrame(Duration.millis(700), e -> {
                            try {
                                Map<String, Object> partida = bdPartida.leerPartida(
                                        SalaContext.codigoSalaActual,
                                        MainApp.usuarioActualToken
                                );

                                if (partida != null) {
                                    Map<String, List<String>> manos = (Map<String, List<String>>) partida.get("manos");
                                    List<String> baraja = (List<String>) partida.get("baraja");
                                    List<String> descarte = (List<String>) partida.get("descarte");

                                    pc.actualizarDesdeModelo(manos, baraja, descarte);
                                }

                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        })
                );

                timeline.setCycleCount(Animation.INDEFINITE);
                timeline.play();

            } else {

                // ⭐ SI NO ES PARTIDA - MÚSICA GENERAL ⭐
                if (!MusicManager.isPlaying("menu")) {
                    MusicManager.playMenuMusic();
                }
            }

            return controller;

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Error cargando escena: " + fxml);
            return null;
        }
    }

    /**
     * Método llamado automáticamente cuando la aplicación se cierra.
     *
     * <p>
     * Garantiza que el usuario quede marcado como desconectado en Firebase,
     * evitando sesiones fantasma o estados inconsistentes.
     * </p>
     */
    @Override
    public void stop() {
        System.out.println(">>> STOP() EJECUTADO <<<");
        MainApp.desconectarUsuario();
    }

    /**
     * Desconecta al usuario de Firebase de forma segura.
     *
     * <p>
     * Se considera que un usuario está correctamente desconectado cuando:
     * </p>
     * <ul>
     * <li>Su campo <code>conectado</code> en Firebase pasa a
     * <code>false</code>.</li>
     * <li>No mantiene ninguna referencia a una sala activa mediante el campo
     * <code>salaActual</code>.</li>
     * </ul>
     *
     * <p>
     * Este método se ejecuta tanto al cerrar sesión como al cerrar la
     * aplicación, incluyendo cierres inesperados gracias al shutdown hook
     * registrado en {@link #start(Stage)}. Su objetivo es evitar sesiones
     * fantasma y garantizar la integridad del estado de las salas online.
     * </p>
     */
    public static void desconectarUsuario() {
        try {
            
            // Si no hay usuario logeado, no hay nada que desconectar.
            if (usuarioActualUID != null && usuarioActualToken != null) {
                
                // Servicio para interactuar con Firebase Realtime Database.
                FirebaseDatabaseService db = new FirebaseDatabaseService();

                // Creamos un mapa con los cambios que queremos aplicar al nodo del usuario.
                Map<String, Object> datos = new HashMap<>();
                
                // Lo desconectamos en BD.
                datos.put("conectado", false);
                
                // Actualizamos el nodo del usuario con el nuevo valor.
                db.actualizarCamposUsuario(usuarioActualUID, datos, usuarioActualToken);

                // Si SalaContext tiene un código de sala, significa que el usuario estaba dentro de una.
                if (SalaContext.codigoSalaActual != null) { // Si es distinto de null, tenemos que quitar el nodo de salaActual de dicho usuario.
                    
                    // Recogemos el codigo de la sala.
                    String codigo = SalaContext.codigoSalaActual;
                    
                    // Leemos el nodo de la sala para saber su estado actual.
                    String jsonSala = db.leerNodo("salas/" + codigo, usuarioActualToken);
                    
                    // Si no es null ni "null" (doble seguridad para tratar con Firebase), la analizamos.
                    if (jsonSala != null && !jsonSala.equals("null")) {

                        // Convertimos el JSON de la sala en un mapa para acceder a sus nodos
                        Map<String, Object> sala = new com.google.gson.Gson().fromJson(jsonSala, Map.class);
                        
                        // Obtenemos el UID del host de la sala.
                        String hostUID = sala.get("host").toString();

                        // Si el usuario no es HOST, solo borraremos al jugador de la sala.
                        if (!usuarioActualUID.equals(hostUID)) {
                            
                            // lo borramos de la lista de jugadores de la sala.
                            db.borrarNodo("salas/" + codigo + "/jugadores/" + usuarioActualUID, usuarioActualToken);
                            
                            // borramos el nodo salaActual del usuario.
                            db.borrarNodo("usuarios/" + usuarioActualUID + "/salaActual", usuarioActualToken);

                        } else { // Pero si el usuario era el HOST, eliminamos la sala al completo.
                            
                            // Obtenemos la lista de jugadores completa.
                            Map<String, Object> jugadores = (Map<String, Object>) sala.get("jugadores");

                            // Y para cada jugador, eliminamos la referencia de la sala.
                            for (String jugadorUID : jugadores.keySet()) {
                                db.borrarNodo("usuarios/" + jugadorUID + "/salaActual", usuarioActualToken);
                            }
                            
                            // Por último, borramos la sala al completo de la Firebase.
                            db.borrarNodo("salas/" + codigo, usuarioActualToken);
                        }
                    }
                }

            }
        } catch (Exception e) { // Capturamos cualquier posible excepción.
            e.printStackTrace();
        }
    }

    /**
     * Método principal estándar de Java.
     */
    public static void main(String[] args) {
        launch(args);
    }
}
