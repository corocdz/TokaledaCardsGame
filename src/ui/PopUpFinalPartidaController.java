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
 * Controller del popup de fin de partida.
 *
 * SOLO EL HOST puede pulsar los botones de acción. Los demás jugadores solo ven
 * el resultado.
 *
 * Botón "Jugar de nuevo" (solo host): - Borra el nodo partida actual - Crea una
 * nueva partida idéntica a cuando el host pulsa "Iniciar" en la sala - Todos
 * los clientes la detectan por su listener (refrescarJugadores) y navegan solos
 * - El popup desaparece en el host
 *
 * Botón "Volver a la sala" (solo host): - Borra el nodo partida completamente
 * (así el nuevo modo arranca limpio) - Escribe un flag "volverSala" en Firebase
 * para que todos los clientes lo detecten - Todos navegan a salaOnline.fxml
 */
public class PopUpFinalPartidaController {

    @FXML
    private Label lblTitulo;
    @FXML
    private Label lblResultado;
    @FXML
    private Label lblDetalle;
    @FXML
    private Button btnVolverJugar;
    @FXML
    private Button btnIrSala;
    @FXML
    private ImageView btnIrSalaImage;
    @FXML
    private ImageView btnVolverJugarImage;
    @FXML
    private ImageView imgIcono;

    private String codigoSala;
    private String modo;

    private final FirebaseDatabaseService db = new FirebaseDatabaseService();
    private final BDPartidaService bd = new BDPartidaService(db);

    private PartidaControllerBase controladorOffline = null;

    private int numIAsOffline = 1;

    private PartidaControllerBase controladorActual;

    // =========================================================================
    //  INIT
    // =========================================================================
    public void init(String codigoSala, String uidLocal, String idToken,
            String modo, String icono, String resultado, String detalle,
            Runnable callbackReiniciar /* ignorado, ya no se usa */, PartidaControllerBase controladorActual) {

        btnVolverJugarImage.setImage(IdiomaManager.cargarImagen("btnVolverJugar"));
        btnIrSalaImage.setImage(IdiomaManager.cargarImagen("btnIrSala"));

        Animaciones.animarBoton(btnVolverJugar);
        Animaciones.animarBoton(btnIrSala);

        ButtonSound.activar(btnVolverJugar);
        ButtonSound.activar(btnIrSala);

        this.codigoSala = codigoSala;
        this.modo = modo;
        this.controladorActual = controladorActual;

        if (icono != null && !icono.isEmpty()) {
            imgIcono.setImage(new Image(
                    getClass().getResource(icono).toExternalForm()
            ));
        }

        if (resultado != null) {
            lblResultado.setText(resultado);
        }
        if (detalle != null) {
            lblDetalle.setText(detalle);
        }

        boolean esHost = esHost();

        btnVolverJugar.setDisable(!esHost);
        btnIrSala.setDisable(!esHost);

        if (!esHost) {
            btnVolverJugar.setStyle("-fx-background-color: transparent; -fx-padding: 0; -fx-opacity: 0.55;");
            btnIrSala.setStyle("-fx-background-color: transparent; -fx-padding: 0; -fx-opacity: 0.55;");
        }

    }

    private boolean esHost() {
        try {
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
    //  BOTÓN: JUGAR DE NUEVO — solo host
    // =========================================================================
    @FXML
    private void onJugarDeNuevo() {

        if (controladorOffline != null) {
            controladorOffline.reiniciarPartidaOffline();
            return;
        }

        if (controladorActual != null) {
            controladorActual.destruir();
        }

        if (!esHost()) {
            return;
        }

        btnVolverJugar.setDisable(true);
        btnIrSala.setDisable(true);

        new Thread(() -> {
            try {
                String token = MainApp.usuarioActualToken;
                String codigo = SalaContext.codigoSalaActual;

                // 1. Borrar partida anterior
                bd.borrarPartida(codigo, token);
                Thread.sleep(400);

                // 2. Crear nueva partida exactamente igual que SalaOnlineController.iniciarPartida()
                String jsonSala = db.leerNodo("salas/" + codigo, token);
                Map<String, Object> sala = new Gson().fromJson(jsonSala, Map.class);
                Map<String, Object> jugadores = (Map<String, Object>) sala.get("jugadores");

                // Baraja nueva barajada
                Baraja baraja = new Baraja();
                baraja.barajar();
                List<String> barajaRestante = new ArrayList<>();
                for (Carta c : baraja.getCartasRestantes()) {
                    barajaRestante.add(c.getRutaImagen());
                }

                // Manos vacías
                Map<String, Object> manos = new HashMap<>();
                for (String uid : jugadores.keySet()) {
                    manos.put(uid, null);
                }

                // Vidas iniciales
                Map<String, Integer> vidas = new HashMap<>();
                for (String uid : jugadores.keySet()) {
                    vidas.put(uid, JuegoYusa.VIDAS_INICIALES);
                }

                // Narrador inicial
                Map<String, Object> narrador = new HashMap<>();
                narrador.put("tipo", "global");
                narrador.put("texto", "¡Nueva partida!");
                narrador.put("uid", null);

                // Estructura completa igual que SalaOnlineController
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

                bd.iniciarPartida(codigo, datosPartida, token);
                System.out.println("[PopUp] Nueva partida iniciada. Modo: " + modo);

                // El host también recarga la pantalla de partida como los demás.
                // Así todos (host y no-host) pasan por el mismo flujo limpio.
                Platform.runLater(() -> {
                    try {
                        FXMLLoader loader = new FXMLLoader(
                                PopUpFinalPartidaController.class
                                        .getResource("/ui/partida.fxml"));

                        PartidaControllerBase controller;
                        if ("Pescaito".equals(modo)) {
                            controller = new PartidaControllerPescaito();
                        } else if ("Yusa".equals(modo)) {
                            controller = new PartidaControllerYusa();
                        } else {
                            return;
                        }

                        loader.setController(controller);
                        Parent root = loader.load();
                        controller.init(SalaContext.codigoSalaActual,
                                MainApp.usuarioActualUID, MainApp.usuarioActualToken);

                        Stage stage = (Stage) btnVolverJugar.getScene().getWindow();
                        stage.setScene(new Scene(root));

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    btnVolverJugar.setDisable(false);
                    btnIrSala.setDisable(false);
                });
            }
        }).start();
    }

    // =========================================================================
    //  BOTÓN: VOLVER A LA SALA — solo host
    // =========================================================================
    @FXML
    private void onVolverSala() {

        if (controladorOffline != null) {
            controladorOffline.volverAlMenuOffline();
            return;
        }

        if (controladorActual != null) {
            controladorActual.destruir();
        }

        if (!esHost()) {
            return;
        }

        btnIrSala.setDisable(true);
        btnVolverJugar.setDisable(true);

        new Thread(() -> {
            try {
                String token = MainApp.usuarioActualToken;
                String codigo = SalaContext.codigoSalaActual;

                /**
                 * // 1. Borrar nodo partida completo (limpia manos, baraja, //
                 * modo, estado — así el próximo modo arranca desde cero)
                 * bd.borrarPartida(codigo, token); System.out.println("[PopUp]
                 * Nodo partida borrado.");
                 *
                 * // 2. Escribir flag "volverSala" para que todos los clientes
                 * // detecten que deben navegar a la sala
                 * db.actualizarNodo("salas/" + codigo + "/volverSala",
                 * System.currentTimeMillis(), token);
                 */
                // En onVolverSala(), sustituye la parte de escritura del flag:
                db.actualizarNodo("salas/" + codigo + "/volverSala",
                        System.currentTimeMillis(), token);

                // Esperar 3 segundos para que todos los clientes lean el flag
                Thread.sleep(3000);

                // Ahora sí borrar el flag
                db.borrarNodo("salas/" + codigo + "/volverSala", token);

            } catch (Exception e) {
                e.printStackTrace();
            }

            // 3. El host navega de inmediato
            Platform.runLater(()
                    -> MainApp.cambiarEscena("salaOnline.fxml", 1280, 720));
        }).start();
    }

    // =========================================================================
    //  OCULTAR OVERLAY (para "Jugar de nuevo" en el host)
    // =========================================================================
    private void ocultarOverlay() {
        // Buscar el overlayFinal en la escena y ocultarlo
        if (btnVolverJugar.getScene() != null) {
            javafx.scene.Node overlay
                    = btnVolverJugar.getScene().lookup("#overlayFinal");
            if (overlay instanceof StackPane sp) {
                sp.setVisible(false);
                sp.getChildren().clear();
            }
        }
    }

    public void initOffline(String resultado, String detalle, String icono, PartidaControllerBase controladorOffline) {

        this.controladorOffline = controladorOffline;

        if (icono != null && !icono.isEmpty()) {
            imgIcono.setImage(new Image(
                    getClass().getResource(icono).toExternalForm()
            ));
        }

        if (resultado != null) {
            lblResultado.setText(resultado);
        }
        if (detalle != null) {
            lblDetalle.setText(detalle);
        }

        // En offline, todos los botones están disponibles
        btnVolverJugar.setDisable(false);
        btnIrSala.setDisable(false);
    }

}
