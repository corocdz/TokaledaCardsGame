package ui;

import com.google.gson.Gson;
import firebase.BDPartidaService;
import firebase.FirebaseDatabaseService;
import i18n.IdiomaManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import partidaUTIL.Baraja;
import partidaUTIL.Carta;
import partidaUTIL.JuegoYusa;
import ui.audio.ButtonSound;

/**
 * Controlador del popUp de fin de partida.
 *
 * <p>
 * Este popUp se muestra al finalizar una partida, tanto en modo online como
 * offline.
 * </p>
 *
 * <h2>Su responsabilidad principal es:</h2>
 * <ul>
 * <li>Mostrar el resultado y un detalle del final de la partida.</li>
 * <li>Permitir al host iniciar una nueva partida con la misma
 * configuración.</li>
 * <li>Permitir al host volver a la sala online.</li>
 * <li>En modo offline, delegar en el controlador de partida las acciones de
 * reinicio o vuelta al menú.</li>
 * </ul>
 *
 * <p>
 * En modo online, solo el host puede pulsar los botones de acción. El resto de
 * jugadores únicamente ve el resultado, con los botones deshabilitados y
 * visualmente atenuados.
 * </p>
 *
 *
 * @author Javier Coronilla Castellano.
 */
public class PopUpFinalPartidaController {

    // -------------------------------
    // ELEMENTOS GRÁFICOS
    // -------------------------------
    /**
     * Capa raíz.
     */
    @FXML
    private StackPane rootPopUpFinal;

    /**
     * Titulo del popUp (opcional).
     */
    @FXML
    private Label lblTitulo;

    /**
     * Texto principal con el resultado.
     */
    @FXML
    private Label lblResultado;

    /**
     * Texto secundario con más información.
     */
    @FXML
    private Label lblDetalle;

    /**
     * Botón "Jugar de nuevo".
     */
    @FXML
    private Button btnVolverJugar;

    /**
     * Botón "Ir a sala".
     */
    @FXML
    private Button btnIrSala;

    /**
     * Imagen para el botón de "Ir a sala".
     */
    @FXML
    private ImageView btnIrSalaImage;

    /**
     * Imagen para el botón de "Volver a jugar".
     */
    @FXML
    private ImageView btnVolverJugarImage;

    /**
     * Imagen para el icono.
     */
    @FXML
    private ImageView imgIcono;

    // -------------------------------
    // ELEMENTOS GENERALES
    // -------------------------------
    /**
     * Código de la sala online.
     */
    private String codigoSala;

    /**
     * Modo de juego actual (por ejemplo, {@code "Pescaito"} o {@code "Yusa"}).
     */
    private String modo;

    /**
     * Servicio de acceso a Firebase Realtime Database.
     */
    private final FirebaseDatabaseService db = new FirebaseDatabaseService();

    /**
     * Servicio específico para operaciones sobre el nodo de partida.
     */
    private final BDPartidaService bd = new BDPartidaService(db);

    /**
     * Controlador de partida en modo offline.
     *
     * <p>
     * Solo aplicable en caso de Modo Offline. Cuando no hay conexión online,
     * este controlador permite reiniciar la partida o volver al menú offline
     * sin tocar Firebase.
     * </p>
     */
    private PartidaControllerBase controladorOffline = null;

    /**
     * Referencia al controlador de la partida actual (online u offline).
     *
     * <p>
     * Se usa para destruir la partida actual antes de iniciar una nueva o
     * volver a la sala.
     * </p>
     */
    private PartidaControllerBase controladorActual;

    /**
     * Inicializa los elementos visuales del popUp tras la carga del FXML.
     *
     * <p>
     * Este método se ejecuta automáticamente cuando JavaFX termina de cargar el
     * archivo FXML asociado. Aquí se:
     * </p>
     *
     * <ul>
     * <li>Cargan las imágenes de los botones según el idioma actual.</li>
     * <li>Aplican animaciones de hover a los botones.</li>
     * <li>Activan los sonidos de click en los botones.</li>
     * </ul>
     *
     * <p>
     * La información específica de la partida (resultado, detalle, icono, etc.)
     * se configura posteriormente mediante los métodos {@code init} e
     * {@code initOffline}.
     * </p>
     */
    @FXML
    private void initialize() {
        btnVolverJugarImage.setImage(IdiomaManager.cargarImagen("btnVolverJugar"));
        btnIrSalaImage.setImage(IdiomaManager.cargarImagen("btnIrSala"));

        Animaciones.animarBoton(btnVolverJugar);
        Animaciones.animarBoton(btnIrSala);

        ButtonSound.activar(btnVolverJugar);
        ButtonSound.activar(btnIrSala);
    }

    /**
     * Inicializa el popUp de fin de partida en modo online.
     *
     * <p>
     * Este método:
     * </p>
     * <ul>
     * <li>Carga las imágenes de los botones según el idioma.</li>
     * <li>Aplica animaciones y sonidos a los botones.</li>
     * <li>Configura los textos de resultado y detalle.</li>
     * <li>Determina si el usuario actual es host y habilita o deshabilita los
     * botones en consecuencia.</li>
     * </ul>
     *
     * @param codigoSala Código de la sala online.
     * @param uidLocal UID del usuario local.
     * @param idToken Token de autenticación del usuario.
     * @param modo Modo de juego actual.
     * @param icono Ruta del icono a mostrar.
     * @param resultado Texto principal del resultado.
     * @param detalle Texto adicional con detalles del final de partida.
     * @param controladorActual Controlador de la partida actual.
     */
    public void init(String codigoSala, String uidLocal, String idToken,
            String modo, String icono, String resultado, String detalle,
            PartidaControllerBase controladorActual) {

        this.codigoSala = codigoSala;
        this.modo = modo;
        this.controladorActual = controladorActual;

        if (icono != null && !icono.isEmpty()) {
            imgIcono.setImage(new Image(getClass().getResource(icono).toExternalForm()));
        }

        if (resultado != null) {
            lblResultado.setText(resultado);
        }
        if (detalle != null) {
            lblDetalle.setText(detalle);
        }

        // Determinar si el usuario actual es host. Importante ya que en el popUp para 
        // modo online, el host es el único que puede tomar acciones sobre los botones.
        boolean esHost = esHost();
        btnVolverJugar.setDisable(!esHost);
        btnIrSala.setDisable(!esHost);

        // Si no es host, atenuar los botones visualmente.
        if (!esHost) {
            btnVolverJugar.setStyle("-fx-background-color: transparent; -fx-padding: 0; -fx-opacity: 0.55;");
            btnIrSala.setStyle("-fx-background-color: transparent; -fx-padding: 0; -fx-opacity: 0.55;");
        }

    }

    /**
     * Comprueba si el usuario actual es el host de la sala.
     *
     * <p>
     * Para ello, lee en Firebase el valor almacenado en
     * {@code salas/{codigoSala}/host} y lo compara con el UID del usuario
     * actual. En caso de error, se devuelve {@code false} y se registra la
     * excepción en la salida estándar.
     * </p>
     *
     * @return {@code true} si el usuario actual es el host, {@code false} en
     * caso contrario.
     */
    private boolean esHost() {
        try { // Leemos "host" de la sala y comparamos UIDS del host actual y del host de la sala.
            String uidHost = db.leerNodo(
                    "salas/" + codigoSala + "/host",
                    MainApp.usuarioActualToken).replace("\"", "");
            return MainApp.usuarioActualUID.equals(uidHost);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // =========================================================================
    //  BOTÓN: JUGAR DE NUEVO - solo host
    // =========================================================================
    /**
     * Acción asociada al botón "Jugar de nuevo".
     *
     * <p>
     * El comportamiento depende del contexto:
     * </p>
     * <ul>
     * <li><b>Modo offline:</b> se delega en el controlador offline para
     * reiniciar la partida local.</li>
     * <li><b>Modo online (host):</b> se destruye el controlador actual, se
     * borra la partida en Firebase y se crea una nueva partida con la misma
     * estructura que al iniciar desde la sala.</li>
     * <li><b>Modo online (no host):</b> la acción se ignora.</li>
     * </ul>
     */
    @FXML
    private void onJugarDeNuevo() {

        // Si hay controlador offline, delegar el reinicio y salir
        if (controladorOffline != null) {
            controladorOffline.reiniciarPartidaOffline();
            return;
        }

        // Destruir la partida actual si existe un controlador.
        if (controladorActual != null) {
            controladorActual.destruir();
        }

        // Solo el host puede iniciar una nueva partida online.
        if (!esHost()) {
            return;
        }

        // Deshabilitar botones para evitar pulsaciones múltiples.
        btnVolverJugar.setDisable(true);
        btnIrSala.setDisable(true);

        // Operaciones de Firebase en un hilo separado.
        new Thread(() -> {
            try {

                // Recuperamos token y codigo de la sala para operar en Firebase.
                String token = MainApp.usuarioActualToken;
                String codigo = SalaContext.codigoSalaActual;

                // Borramos partida anterior.
                bd.borrarPartida(codigo, token);

                // Pausa breve para evitar condiciones de carrera entre borrado y creaación.
                Thread.sleep(400);

                // Leemos la sala completa desde FB.
                String jsonSala = db.leerNodo("salas/" + codigo, token);
                Map<String, Object> sala = new Gson().fromJson(jsonSala, Map.class);

                // Obtenemos la lista de jugadores actual.
                Map<String, Object> jugadores = (Map<String, Object>) sala.get("jugadores");

                // Nos aseguramos de que haya como mínimo 2 jugadores actualmente en la sala para poder volver a jugar una partida.
                if (jugadores.size() < 2) {
                    System.out.println("[PopUp] No se puede iniciar partida: solo hay " + jugadores.size() + " jugador(es)."); // debug

                    // Reactivamos los botones.
                    Platform.runLater(() -> {
                        btnVolverJugar.setDisable(false);
                        btnIrSala.setDisable(false);

                        // Mostrar popUp de error.
                        Animaciones.mostrarError(rootPopUpFinal, "popUpFinalPartidaController.error.volverJugar");
                    });

                    // Salimos del hilo sin crear partida.
                    return;
                }

                // Si somos 2 o más jugadores, podemos volver a jugar.
                // Creamos una baraja nueva y la barajamos.
                Baraja baraja = new Baraja();
                baraja.barajar();

                // Convertimos las cartas restantes en su ruta para que FB pueda manejarlas.
                List<String> barajaRestante = new ArrayList<>();
                for (Carta c : baraja.getCartasRestantes()) {
                    barajaRestante.add(c.getRutaImagen());
                }

                // Creamos un mapa de manos vacías para cada jugador.
                // Las manos de cada jugador se identifican por UID, siendo la clave del mapa. Se crean manos vacías.
                // Luego, recorremos todos los jugadores y le asignamos a cada jugador una mano vacía.
                Map<String, Object> manos = new HashMap<>();
                for (String uid : jugadores.keySet()) { // cada jugador con su mano
                    manos.put(uid, null); // vacía inicialmente.
                }

                // Asignamos vidas iniciales a cada jugador para el modo Yusa.
                // Mismo mecanismo de antes con las manos, pero le asignamos a cada jugador sus vidas.
                Map<String, Integer> vidas = new HashMap<>();
                for (String uid : jugadores.keySet()) {
                    vidas.put(uid, JuegoYusa.VIDAS_INICIALES);
                }

                // Creamos un narrador inicial para mostrar un mensaje global.
                // Los narradores podrán lanzar mensajes globales o privados.
                Map<String, Object> narrador = new HashMap<>();
                narrador.put("tipo", "global");
                narrador.put("texto", "¡Nueva partida!");
                narrador.put("uid", null);

                // Estructura completa de partida tal y como la espera FB
                Map<String, Object> datosPartida = new HashMap<>();
                datosPartida.put("estado", "iniciada");
                datosPartida.put("modo", modo);
                datosPartida.put("manos", manos);
                datosPartida.put("baraja", barajaRestante);
                datosPartida.put("descarte", new ArrayList<String>());
                datosPartida.put("turno", sala.get("host"));
                datosPartida.put("ronda", 1);
                datosPartida.put("vidas", vidas);
                datosPartida.put("narrador", narrador);

                // Guardamos la nueva partida en FB.
                bd.iniciarPartida(codigo, datosPartida, token);
                System.out.println("[PopUp] Nueva partida iniciada. Modo: " + modo); // debug

                // El host también recarga la pantalla de partida como los demás.
                // Así todos (host y no-host) pasan por el mismo flujo limpio.
                Platform.runLater(() -> {
                    try { // volvemos a cargar la vista de partida.

                        FXMLLoader loader = new FXMLLoader(PopUpFinalPartidaController.class
                                .getResource("/ui/partida.fxml"));

                        // Seleccionamos el controlador adecuado según el modo
                        PartidaControllerBase controller;

                        // Si el modo es "Pescaito" le asignamos el controller de pescaito Online.
                        if ("Pescaito".equals(modo)) {
                            controller = new PartidaControllerPescaito();

                            // Si el modo es "Yusa" le asignamos el controller de Yusa Online.
                        } else if ("Yusa".equals(modo)) {
                            controller = new PartidaControllerYusa();
                        } else { // Si no es ninguno, salimos.
                            return;
                        }

                        // Cargamos la vista y la inicializamos
                        loader.setController(controller);
                        Parent root = loader.load();
                        controller.init(SalaContext.codigoSalaActual,
                                MainApp.usuarioActualUID, MainApp.usuarioActualToken);

                        // Cambiamos la escena actual por la nueva partida.
                        Stage stage = (Stage) btnVolverJugar.getScene().getWindow();
                        stage.setMaximized(false); // reset
                        stage.setScene(new Scene(root));
                        stage.setMaximized(true);  // maximizar bien
                        stage.show();

                    } catch (Exception e) { // Capturamos posible excepción.
                        e.printStackTrace();
                    }
                });

            } catch (Exception e) { // Capturamos posible excepción.
                e.printStackTrace();

                // Si algo falla, reactivamos los botones desde el hilo de JavaFX.
                Platform.runLater(() -> {
                    btnVolverJugar.setDisable(false);
                    btnIrSala.setDisable(false);
                });
            }
        }).start();
    }

    // =========================================================================
    //  BOTÓN: VOLVER A LA SALA - solo host
    // =========================================================================
    /**
     * Acción asociada al botón "Volver a la sala".
     *
     * <p>
     * El comportamiento depende del modo de juego:
     * </p>
     * <ul>
     * <li><b>Modo offline:</b> vuelve al menú offline sin tocar Firebase.</li>
     * <li><b>Modo online (host):</b> escribe un flag en Firebase para que todos
     * los jugadores regresen a la sala y luego cambia la escena del host.</li>
     * <li><b>Modo online (no host):</b> la acción se ignora (en esta
     * versión).</li>
     * </ul>
     */
    @FXML
    private void onVolverSala() {

        // Si estamos en modo offline, delegamos la acción en el controlador offline.
        if (controladorOffline != null) {
            controladorOffline.volverAlMenuOffline();
            return;
        }

        // Si existe un controlador de partida activo, lo destruimos para liberar recursos.
        if (controladorActual != null) {
            controladorActual.destruir();
        }

        // Solo el host puede activar la vuelta global a la sala online.
        if (!esHost()) {
            return;
        }

        // Deshabilitamos botones para evitar pulsaciones múltiples.
        btnIrSala.setDisable(true);
        btnVolverJugar.setDisable(true);

        new Thread(() -> {
            try {
                String token = MainApp.usuarioActualToken;
                String codigo = SalaContext.codigoSalaActual;

                // Escribimos un flag de "volverSala" en FB.
                // Los clientes lo detectarán y navegarán automáticamente a la sala.
                db.actualizarNodo("salas/" + codigo + "/volverSala",
                        System.currentTimeMillis(), token);

                // Espera de 3 segundos para que todos los clientes lean el flag.
                Thread.sleep(3000);

                // Ahora sí borrar el flag
                db.borrarNodo("salas/" + codigo + "/volverSala", token);

            } catch (Exception e) { // Capturamos posible excepción.
                e.printStackTrace();
            }

            // El host navega de inmediato a la sala online junto al resto de jugadores.
            Platform.runLater(() -> MainApp.cambiarEscena("salaOnline.fxml", 1200, 1000));
        }).start();
    }

    // =========================================================================
    //  OCULTAR OVERLAY (para "Jugar de nuevo" en el host)
    // =========================================================================
    /**
     * Método que oculta el overlay final de partida si existe en la escena.
     *
     * <p>
     * Busca un nodo con id {@code overlayFinal} en la escena actual y, si es un
     * {@link StackPane}, lo oculta y elimina sus hijos. Este método está
     * pensado para escenarios en los que el popUp se muestra dentro de un
     * overlay reutilizable.
     * </p>
     */
    private void ocultarOverlay() {

        // Buscamos el overlayFinal en la escena y ocultarlo
        if (btnVolverJugar.getScene() != null) {
            javafx.scene.Node overlay
                    = btnVolverJugar.getScene().lookup("#overlayFinal");
            if (overlay instanceof StackPane sp) { // Si el overlay es un StackPane...
                sp.setVisible(false); // Lo oculta.
                sp.getChildren().clear(); // Y elimina sus hijos.
            }
        }
    }

    /**
     * Inicializa el popUp de fin de partida en modo offline.
     *
     * <p>
     * En este modo no se realizan operaciones sobre Firebase. El controlador de
     * partida offline se encarga de gestionar el reinicio de la partida o la
     * vuelta al menú cuando se pulsan los botones.
     * </p>
     *
     * @param resultado Texto principal del resultado.
     * @param detalle Texto adicional con detalles del final de partida.
     * @param icono Ruta del icono a mostrar.
     * @param controladorOffline Controlador de la partida offline.
     */
    public void initOffline(String resultado, String detalle, String icono, PartidaControllerBase controladorOffline) {

        // Guardamos referencia al controlador offline.
        this.controladorOffline = controladorOffline;

        // Configuramos icono si se ha proporcionado ruta
        if (icono != null && !icono.isEmpty()) {
            imgIcono.setImage(new Image(
                    getClass().getResource(icono).toExternalForm()
            ));
        }

        // Configuramos textos de reesultado y de detalles.
        if (resultado != null) {
            lblResultado.setText(resultado);
        }
        if (detalle != null) {
            lblDetalle.setText(detalle);
        }

        // En offline, todos los botones están disponibles para el jugador humano.
        btnVolverJugar.setDisable(false);
        btnIrSala.setDisable(false);
    }

}
