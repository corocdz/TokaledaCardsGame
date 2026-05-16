package ui;

import com.google.gson.Gson;
import firebase.BDPartidaService;
import firebase.FirebaseDatabaseService;
import java.io.IOException;
import java.util.*;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;
import partidaUTIL.Juego;
import ui.audio.MusicManager;

/**
 * Clase base abstracta para todos los modos de juego de cartas.
 *
 * Contiene toda la lógica que es IDÉNTICA en cualquier modo: - Estado
 * compartido (manos, baraja, descarte, nombres, sesión) - Nodos FXML comunes
 * (zonas, labels, mazo, descarte, overlay, narrador) - Servicios Firebase -
 * Carga inicial y reparto - Actualización del modelo desde Firebase -
 * Renderizado de la interfaz (abanicos, colocarJugadores, mazo, descarte) -
 * Sistema de mensajes (narrador global y privado con cola) - Listener de
 * narrador y de estado "finalizada" (comunes a todos los modos) - Listener de
 * turno con hook abstracto onCambioTurno() - Configuración de eventos de mano y
 * mazo - configurarLayoutEscena() - mostrarPantallaFinal() (estructura común,
 * contenido por cada modo)
 *
 * Cada subclase DEBE implementar: - crearJuego(String modo) → instanciar su
 * motor de juego - registrarListenersPropios() → listeners específicos del modo
 * - onCambioTurno(String nuevoTurno) → qué hacer cuando cambia el turno global
 * - onClickMazo() → comportamiento al clicar el mazo - onZonaRivalClick(String
 * uidRival) → comportamiento al clicar zona rival - onCartaLocalClick(String
 * ruta) → comportamiento al clicar carta propia -
 * construirContenidoPantallaFinal() → retorna el VBox con el contenido final
 */
public abstract class PartidaControllerBase {

    // ─── Constantes de abanico ────────────────────────────────────────────────
    // ─── Constantes de posicionamiento del abanico ───────────────────────────
    private static final double RADIO_BASE = 577.8;
    private static final double ANGULO_BASE = 5.0;
    private static final double ALTURA_BASE = 80.0;
    private static final double ROTACION_BASE = 0.8;
    private static final double BASE_Y = 200.0;

    // ─── Nodos FXML (comunes a todos los modos) ───────────────────────────────
    @FXML
    protected StackPane rootSala;
    @FXML
    protected Pane zonaArriba;
    @FXML
    protected Pane zonaIzquierda;
    @FXML
    protected Pane zonaDerecha;
    @FXML
    protected Pane zonaAbajo;
    @FXML
    protected Label lblArriba;
    @FXML
    protected Label lblIzquierda;
    @FXML
    protected Label lblDerecha;
    @FXML
    protected Label lblAbajo;
    @FXML
    protected Label narradorLabel;
    @FXML
    protected Label lblInfo;
    @FXML
    protected AnchorPane zonaCentro;
    @FXML
    protected ImageView imgMazo;
    @FXML
    protected ImageView imgDescarte;
    @FXML
    protected StackPane overlayFinal;

    // ─── Servicios Firebase ───────────────────────────────────────────────────
    protected final FirebaseDatabaseService db = new FirebaseDatabaseService();
    protected final BDPartidaService bd = new BDPartidaService(db);

    // ─── Sesión ───────────────────────────────────────────────────────────────
    protected String idToken;
    protected String codigoSala;
    protected String uidLocal;

    // ─── Estado de partida compartido ─────────────────────────────────────────
    protected final Map<String, List<String>> manos = new HashMap<>();
    protected final List<String> baraja = new ArrayList<>();
    protected final List<String> descarte = new ArrayList<>();
    protected final Map<String, String> nombres = new HashMap<>();
    private final List<Thread> hilosListeners = new ArrayList<>();

    protected boolean partidaFinalizada = false;
    protected boolean repartoInicialHecho = false;

    protected Juego juego;
    protected String uidTurnoActual;
    protected List<String> ordenJugadoresGlobal = new ArrayList<>();

    // Posiciones de los rivales en pantalla (asignadas en colocarJugadores)
    protected String uidJugadorArriba;
    protected String uidJugadorIzquierda;
    protected String uidJugadorDerecha;

    // ─── Sistema de mensajes privados ─────────────────────────────────────────
    protected final Queue<String> colaPrivados = new LinkedList<>();
    protected boolean narrandoPrivado = false;

    // ─── Animación de mano ────────────────────────────────────────────────────
    protected boolean manoAbierta = false;
    protected int cartaSeleccionada = -1;

    protected volatile boolean controladorDestruido = false;

    /**
     * Datos que cada modo de juego proporciona para el popup de fin de partida.
     */
    public static class DatosPopUp {

        public final String icono;
        public final String resultado;   // texto principal (ganador, empate...)
        public final String detalle;     // texto secundario (puntos, vidas...)

        public DatosPopUp(String icono, String resultado, String detalle) {
            this.icono = icono;
            this.resultado = resultado;
            this.detalle = detalle;
        }
    }

    // =========================================================================
    //  PUNTO DE ENTRADA — igual para todos los modos
    // =========================================================================
    public void init(String codigoSala, String uidLocal, String idToken) {

        Platform.runLater(() -> Animaciones.fadeIn(rootSala));

        this.codigoSala = codigoSala;
        this.uidLocal = uidLocal;
        this.idToken = idToken;

        // 1. Crear el motor de juego e inicializar el estado desde Firebase
        cargarJuegoDesdeBD();

        // 2. Eventos de mano y mazo (idénticos en todos los modos)
        configurarEventosManoJugador();
        cargarOrdenJugadoresGlobal();
        precargarNombres();
        configurarEventosRobar();

        // 3. Listeners comunes (narrador, estado finalizada, turno)
        registrarListenersComunes();

        // 4. Listeners específicos del modo (implementado en cada subclase)
        registrarListenersPropios();

        Platform.runLater(() -> {
            Stage stage = (Stage) zonaAbajo.getScene().getWindow();
            configurarLayoutEscena(stage);
        });

        System.out.println(">>> PartidaControllerBase.init() ejecutado");
        MusicManager.playPartidaMusic();
    }

    // =========================================================================
    //  HOOKS ABSTRACTOS — cada subclase los implementa
    // =========================================================================
    /**
     * Crea e inicializa el motor de juego concreto (JuegoPescaito, JuegoYusa…)
     */
    protected abstract Juego crearJuego(String modo);

    /**
     * Registra los listeners de Firebase propios del modo (turno de ronda,
     * decisiones, etc.)
     */
    protected abstract void registrarListenersPropios();

    /**
     * Qué hace el modo cuando cambia el turno global en Firebase. Pescaito:
     * solo narra. Yusa: lanza iniciarRondaYusa() si no hay ronda en curso.
     * Futuros modos: lo que necesiten.
     */
    protected abstract void onCambioTurno(String nuevoTurno);

    /**
     * Comportamiento al hacer clic en el mazo (robar, no permitido, etc.)
     */
    protected abstract void onClickMazo();

    /**
     * Comportamiento al hacer clic en la zona de un rival
     */
    protected abstract void onZonaRivalClick(String uidRival);

    /**
     * Comportamiento al hacer clic en una de las cartas propias
     */
    protected abstract void onCartaLocalClick(String rutaCarta);

    protected Runnable obtenerCallbackReiniciar() {
        return null; // null = el popup vuelve a la sala como fallback
    }

    /**
     * Cada modo devuelve los datos para el popup de fin de partida. Se llama en
     * un hilo de background desde mostrarPantallaFinal(), así que puede hacer
     * lecturas de Firebase sin bloquear la UI.
     */
    protected abstract DatosPopUp construirDatosPopUpFinal();

    // =========================================================================
    //  CARGA INICIAL
    // =========================================================================
    private void cargarJuegoDesdeBD() {
        try {
            Object modoObj = bd.leerCampo(codigoSala, "modo", idToken);
            if (modoObj == null) {
                System.out.println("Modo no encontrado.");
                return;
            }

            String modo = modoObj.toString();
            System.out.println("Modo de juego cargado: " + modo);

            juego = crearJuego(modo);
            if (juego == null) {
                System.out.println("Modo no reconocido: " + modo);
                return;
            }

            Map<String, Object> partida = bd.leerPartida(codigoSala, idToken);
            if (partida == null) {
                System.out.println("Partida no encontrada.");
                return;
            }

            actualizarDesdeModelo(
                    (Map<String, List<String>>) partida.get("manos"),
                    (List<String>) partida.get("baraja"),
                    (List<String>) partida.get("descarte")
            );

            prepararEstadoInicial(partida);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Carga el orden de jugadores de la sala desde Firebase. El orden se usa
     * para rotar abanicos y calcular el siguiente turno.
     */
    protected void cargarOrdenJugadoresGlobal() {
        try {
            String json = db.leerNodo("salas/" + codigoSala + "/jugadores", idToken);
            if (json == null || "null".equals(json)) {
                return;
            }
            Map<String, Object> mapa = new Gson().fromJson(json, Map.class);
            ordenJugadoresGlobal.clear();
            ordenJugadoresGlobal.addAll(mapa.keySet());
            System.out.println("Orden global de jugadores: " + ordenJugadoresGlobal);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Solo el host reparte en el primer arranque de la partida. Las subclases
     * pueden sobreescribir para añadir lógica de modo (por ejemplo, Yusa guarda
     * las vidas iniciales en Firebase). Si lo sobreescriben, deben llamar a
     * super.prepararEstadoInicial(partida).
     */
    protected void prepararEstadoInicial(Map<String, Object> partida) {
        if (juego == null) {
            return;
        }

        boolean manosVacias = manos.values().stream().allMatch(m -> m == null || m.isEmpty());

        if (!manosVacias) {
            System.out.println("Las manos ya están repartidas. No se hace nada.");
            actualizarInterfaz();
            return;
        }

        try {
            String uidHost = db.leerNodo("salas/" + codigoSala + "/host", idToken)
                    .replace("\"", "");
            if (!uidLocal.equals(uidHost)) {
                System.out.println("No soy el host, espero a que el host reparta.");
                actualizarInterfaz();
                return;
            }

            System.out.println("Soy el host. Repartiendo cartas iniciales...");

            if (manos.isEmpty()) {
                String jsonSala = db.leerNodo("salas/" + codigoSala, idToken);
                Map<String, Object> sala = new Gson().fromJson(jsonSala, Map.class);
                Map<String, Object> jugadores = (Map<String, Object>) sala.get("jugadores");
                for (String uid : jugadores.keySet()) {
                    manos.put(uid, null);
                }
            }

            juego.iniciarPartida(manos, baraja);

            for (String uid : manos.keySet()) {
                bd.actualizarMano(codigoSala, uid, manos.get(uid), idToken);
            }
            bd.actualizarBaraja(codigoSala, baraja, idToken);

            actualizarInterfaz();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // =========================================================================
    //  ACTUALIZAR MODELO DESDE FIREBASE
    // =========================================================================
    /**
     * Sincroniza el estado local (manos, baraja, descarte, turno) con Firebase.
     * Llamado cada vez que el polling detecta un cambio en el nodo de partida.
     * Al final pinta la UI y, si es el turno local, llama a onCambioTurno().
     */
    public void actualizarDesdeModelo(Map<String, List<String>> manosBD,
            List<String> barajaBD,
            List<String> descarteBD) throws IOException {
        if (partidaFinalizada) {
            return;
        }

        if (manosBD == null) {
            manosBD = new HashMap<>();
        }

        // Normalizar ["EMPTY"] → lista vacía real
        for (Map.Entry<String, List<String>> entry : manosBD.entrySet()) {
            List<String> lista = entry.getValue();
            if (lista != null && lista.size() == 1 && "EMPTY".equals(lista.get(0))) {
                entry.setValue(new ArrayList<>());
            }
        }

        this.manos.clear();
        this.manos.putAll(manosBD);
        this.baraja.clear();
        this.baraja.addAll(barajaBD != null ? barajaBD : new ArrayList<>());
        this.descarte.clear();
        this.descarte.addAll(descarteBD != null ? descarteBD : new ArrayList<>());

        try {
            String turno = db.leerNodo("salas/" + codigoSala + "/partida/turno", idToken);
            if (turno != null) {
                uidTurnoActual = turno.replace("\"", "");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        int totalCartas = manos.values().stream()
                .filter(Objects::nonNull).mapToInt(List::size).sum();
        if (!repartoInicialHecho && totalCartas > 0) {
            repartoInicialHecho = true;
        }

        actualizarInterfaz();
    }

    // =========================================================================
    //  LISTENERS COMUNES
    // =========================================================================
    private void registrarListenersComunes() {

        hilosListeners.add(
                bd.escucharNarrador(codigoSala, idToken, mensaje
                        -> Platform.runLater(() -> {
                    if (controladorDestruido) {
                        return;
                    }
                    procesarMensajeNarrador(mensaje);
                }))
        );

        hilosListeners.add(
                bd.escucharEstadoPartida(codigoSala, idToken, estado -> {
                    if (controladorDestruido) {
                        return;
                    }
                    String limpio = estado == null ? "" : estado.replace("\"", "").trim().toLowerCase();
                    if (!"finalizada".equals(limpio)) {
                        return;
                    }
                    if (partidaFinalizada) {
                        return;
                    }
                    Platform.runLater(() -> {
                        partidaFinalizada = true;
                        mostrarPantallaFinal();
                    });
                })
        );

        hilosListeners.add(
                bd.escucharTurno(codigoSala, idToken, nuevoTurno
                        -> Platform.runLater(() -> {
                    if (controladorDestruido) {
                        return;
                    }
                    uidTurnoActual = nuevoTurno;
                    onCambioTurno(nuevoTurno);
                }))
        );

        hilosListeners.add(
                bd.escucharPartida(codigoSala, idToken, partida -> {
                    Platform.runLater(() -> {
                        if (controladorDestruido) {
                            return;
                        }
                        try {
                            if (partida == null) {
                                return;
                            }
                            Map<String, List<String>> manosBD = (Map<String, List<String>>) partida.get("manos");
                            List<String> barajaBD = (List<String>) partida.get("baraja");
                            List<String> descarteBD = (List<String>) partida.get("descarte");
                            actualizarDesdeModelo(manosBD, barajaBD, descarteBD);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                })
        );

        hilosListeners.add(
                bd.escucharVolverSala(codigoSala, idToken, ts -> Platform.runLater(() -> {
            if (controladorDestruido) {
                return;
            }

            // NO borrar el flag aquí — lo borra el host desde el popup.
            // Si cada cliente lo borrara, podría quitárselo a otros clientes
            // antes de que lo lean.
            prepararNavegacion();
            MainApp.cambiarEscena("salaOnline.fxml", 1280, 720);
        }))
        );

        /**
         * *
         *
         * hilosListeners.add( bd.escucharVolverSala(codigoSala, idToken, ts ->
         * Platform.runLater(() -> { if (controladorDestruido) { return; } new
         * Thread(() -> { try { db.borrarNodo("salas/" + codigoSala +
         * "/volverSala", idToken); } catch (Exception e) { e.printStackTrace();
         * } }).start(); if (!MainApp.usuarioActualUID.equals(obtenerUidHost()))
         * { prepararNavegacion(); MainApp.cambiarEscena("salaOnline.fxml",
         * 1280, 720); } })) );
         */
        hilosListeners.add(
                bd.escucharNuevaPartida(codigoSala, idToken, modo -> Platform.runLater(() -> {
            if (controladorDestruido) {
                return;
            }
            if (!partidaFinalizada) {
                return;
            }
            prepararNavegacion();
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/ui/partida.fxml"));
                PartidaControllerBase controller;
                if ("Pescaito".equals(modo)) {
                    controller = new PartidaControllerPescaito();
                } else if ("Yusa".equals(modo)) {
                    controller = new PartidaControllerYusa();
                } else {
                    System.out.println("WARN: modo desconocido: " + modo);
                    return;
                }
                loader.setController(controller);
                Parent root = loader.load();
                controller.init(codigoSala, MainApp.usuarioActualUID, MainApp.usuarioActualToken);
                Stage stage = (Stage) overlayFinal.getScene().getWindow();
                if (stage != null) {
                    stage.setScene(new Scene(root));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }))
        );
    }

    // =========================================================================
    //  FIN DE PARTIDA
    // =========================================================================
    /**
     * Cierra la interacción, escribe "finalizada" en Firebase y muestra la
     * pantalla final. Seguro contra doble llamada.
     */
    protected void finalizarPartida() {
        if (partidaFinalizada) {
            return;
        }

        zonaArriba.setDisable(true);
        zonaIzquierda.setDisable(true);
        zonaDerecha.setDisable(true);
        zonaAbajo.setDisable(true);
        zonaCentro.setDisable(true);

        try {
            bd.actualizarEstadoPartida(codigoSala, "finalizada", idToken);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Limpiar el narrador para no dejar mensajes obsoletos en Firebase
        // Borrar el narrador al finalizar para no dejar residuos en Firebase
        try {
            db.borrarNodo("salas/" + codigoSala + "/partida/narrador", idToken);
        } catch (Exception e) {
            e.printStackTrace();
        }

        partidaFinalizada = true;

        // Mostrar directamente (no esperar al listener, que puede llegar tarde)
        Platform.runLater(this::mostrarPantallaFinal);
    }

    /**
     * Construye y muestra el overlay final. La estructura (fondo, VBox
     * centrado, botón volver) es común. El contenido interior lo decide cada
     * subclase en construirContenidoPantallaFinal().
     */
    protected void mostrarPantallaFinal() {
        // Calcular datos en background (puede leer Firebase)
        new Thread(() -> {
            DatosPopUp datos = construirDatosPopUpFinal();

            Platform.runLater(() -> {
                try {
                    FXMLLoader loader = new FXMLLoader(
                            getClass().getResource("/ui/popUpFinalPartida.fxml"));
                    // Cargar el FXML — el controller ya está declarado en el fx:controller
                    StackPane popUpPane = loader.load();

                    PopUpFinalPartidaController popUpCtrl = loader.getController();

                    // Recuperar el modo de juego desde Firebase o usar un campo
                    String modo = ""; // se rellena abajo
                    try {
                        Object modoObj = bd.leerCampo(codigoSala, "modo", idToken);
                        if (modoObj != null) {
                            modo = modoObj.toString();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    popUpCtrl.init(
                            codigoSala,
                            uidLocal,
                            idToken,
                            modo,
                            datos.icono,
                            datos.resultado,
                            datos.detalle,
                            obtenerCallbackReiniciar(),
                            this
                    );

                    // Insertar en el overlayFinal que ya existe en el FXML de partida
                    overlayFinal.getChildren().clear();
                    overlayFinal.getChildren().add(popUpPane);
                    overlayFinal.setVisible(true);

                } catch (IOException e) {
                    e.printStackTrace();
                    // Fallback: mostrar un texto simple si falla el FXML
                    Label fallback = new Label("La partida ha terminado.");
                    fallback.setStyle("-fx-text-fill:white;-fx-font-size:24px;");
                    overlayFinal.getChildren().clear();
                    overlayFinal.getChildren().add(fallback);
                    overlayFinal.setVisible(true);
                }
            });
        }).start();
    }

    // =========================================================================
    //  UTILIDADES COMPARTIDAS
    // =========================================================================
    /**
     * Siguiente jugador en orden circular usando ordenJugadoresGlobal. Funciona
     * para Pescaito (todos los jugadores) y para Yusa cuando se rota el turno
     * global entre rondas.
     */
    protected String obtenerSiguienteJugador(String uidActual) {
        if (ordenJugadoresGlobal == null || ordenJugadoresGlobal.isEmpty()) {
            return uidActual;
        }
        int idx = ordenJugadoresGlobal.indexOf(uidActual);
        if (idx == -1) {
            return ordenJugadoresGlobal.get(0);
        }
        return ordenJugadoresGlobal.get((idx + 1) % ordenJugadoresGlobal.size());
    }

    /**
     * Ganador = jugador con mayor valor en el mapa de puntuaciones. Sirve tanto
     * para pescaitos (Integer) como para vidas (Integer).
     */
    protected String obtenerGanador(Map<String, Integer> puntuaciones) {
        return puntuaciones.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    // =========================================================================
    //  SISTEMA DE MENSAJES
    // =========================================================================
    protected void narrarGlobal(String texto) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("tipo", "global");
        msg.put("texto", texto);
        msg.put("uid", null);
        try {
            bd.actualizarNarrador(codigoSala, msg, idToken);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected void narrarPrivado(String uidDestino, String texto) {
        if (uidDestino.equals(uidLocal)) {
            Platform.runLater(() -> encolarMensajePrivado(texto));
            return;
        }
        Map<String, Object> msg = new HashMap<>();
        msg.put("tipo", "privado");
        msg.put("texto", texto);
        msg.put("uid", uidDestino);
        try {
            bd.actualizarNarrador(codigoSala, msg, idToken);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void procesarMensajeNarrador(Map<String, Object> msg) {
        String tipo = (String) msg.get("tipo");
        String texto = (String) msg.get("texto");
        String uid = (String) msg.get("uid");
        if ("global".equals(tipo)) {
            narradorLabel.setText(texto);
        } else if ("privado".equals(tipo) && uid != null && uid.equals(uidLocal)) {
            encolarMensajePrivado(texto);
        }
    }

    protected void encolarMensajePrivado(String texto) {
        colaPrivados.add(texto);
        if (!narrandoPrivado) {
            narrarSiguientePrivado();
        }
    }

    private void narrarSiguientePrivado() {
        String texto = colaPrivados.poll();
        if (texto == null) {
            narrandoPrivado = false;
            return;
        }
        narrandoPrivado = true;
        lblInfo.setText(texto);
        FadeTransition fi = new FadeTransition(Duration.millis(200), lblInfo);
        fi.setFromValue(0.0);
        fi.setToValue(1.0);
        FadeTransition fo = new FadeTransition(Duration.seconds(1.5), lblInfo);
        fo.setFromValue(1.0);
        fo.setToValue(0.0);
        fo.setDelay(Duration.seconds(0.5));
        fo.setOnFinished(e -> narrarSiguientePrivado());
        new javafx.animation.SequentialTransition(fi, fo).play();
    }

    // =========================================================================
    //  INTERFAZ — RENDERIZADO
    // =========================================================================
    /**
     * Repinta toda la interfaz: zonas, abanicos, mazo, descarte. Llamado tras
     * cada actualización del modelo. Las subclases pueden sobreescribir para
     * añadir elementos propios (por ejemplo, Yusa puede añadir el panel de
     * vidas). Si sobreescriben, deben llamar a super.actualizarInterfaz().
     */
    protected void actualizarInterfaz() {
        limpiarZonas();
        cargarNombresJugadores();
        colocarJugadores();
        mostrarMazo();
        mostrarDescarte();
        dibujarAbanicoAbajo(zonaAbajo, manos.get(uidLocal));
    }

    private void limpiarZonas() {
        zonaArriba.getChildren().clear();
        zonaIzquierda.getChildren().clear();
        zonaDerecha.getChildren().clear();
        zonaAbajo.getChildren().clear();
    }

    protected void cargarNombresJugadores() {
        for (String uid : manos.keySet()) {
            try {
                String json = db.leerNodo("usuarios/" + uid + "/nombre", idToken);
                nombres.put(uid, json.replace("\"", ""));
            } catch (Exception e) {
                /* ignorar */ }
        }
    }

    protected void colocarJugadores() {
        if (ordenJugadoresGlobal == null || ordenJugadoresGlobal.isEmpty()) {
            return;
        }
        int idxLocal = ordenJugadoresGlobal.indexOf(uidLocal);
        if (idxLocal == -1) {
            return;
        }

        List<String> rotado = new ArrayList<>();
        for (int i = 0; i < ordenJugadoresGlobal.size(); i++) {
            rotado.add(ordenJugadoresGlobal.get((idxLocal + i) % ordenJugadoresGlobal.size()));
        }

        lblAbajo.setText(nombres.getOrDefault(rotado.get(0), ""));

        String derecha = rotado.size() > 1 ? rotado.get(1) : null;
        String arriba = rotado.size() > 2 ? rotado.get(2) : null;
        String izquierda = rotado.size() > 3 ? rotado.get(3) : null;

        if (derecha != null) {
            uidJugadorDerecha = derecha;
            dibujarAbanicoDerecha(zonaDerecha, manos.get(derecha));
            lblDerecha.setText(nombres.getOrDefault(derecha, ""));
            lblDerecha.setRotate(-90);
            final String d = derecha;
            zonaDerecha.setOnMouseClicked(e -> onZonaRivalClick(d));
        }
        if (arriba != null) {
            uidJugadorArriba = arriba;
            dibujarAbanicoArriba(zonaArriba, manos.get(arriba));
            lblArriba.setText(nombres.getOrDefault(arriba, ""));
            final String a = arriba;
            zonaArriba.setOnMouseClicked(e -> onZonaRivalClick(a));
        }
        if (izquierda != null) {
            uidJugadorIzquierda = izquierda;
            dibujarAbanicoIzquierda(zonaIzquierda, manos.get(izquierda));
            lblIzquierda.setText(nombres.getOrDefault(izquierda, ""));
            lblIzquierda.setRotate(90);
            final String iz = izquierda;
            zonaIzquierda.setOnMouseClicked(e -> onZonaRivalClick(iz));
        }
    }

    protected void mostrarMazo() {
        if (baraja == null || baraja.isEmpty()) {
            imgMazo.setVisible(false);
            return;
        }
        imgMazo.setImage(new Image("/ui/graphicResources/cartas/parteTrasera.png"));
        imgMazo.setVisible(true);
    }

    protected void mostrarDescarte() {
        if (descarte == null || descarte.isEmpty()) {
            imgDescarte.setVisible(false);
            return;
        }
        imgDescarte.setImage(new Image(descarte.get(descarte.size() - 1)));
        imgDescarte.setVisible(true);
    }

    // =========================================================================
    //  EVENTOS DE MANO Y MAZO
    // =========================================================================
    protected void configurarEventosManoJugador() {
        zonaAbajo.setOnMouseEntered(e -> {
            manoAbierta = true;
            redibujarManoLocal();
        });
        zonaAbajo.setOnMouseExited(e -> {
            manoAbierta = false;
            cartaSeleccionada = -1;
            redibujarManoLocal();
        });
        zonaAbajo.setOnMouseMoved(e -> {
            int nueva = calcularCartaSeleccionada(e.getX());
            if (nueva != cartaSeleccionada) {
                cartaSeleccionada = nueva;
                dibujarAbanicoAbajo(zonaAbajo, manos.get(uidLocal));
            }
        });
    }

    protected void configurarEventosRobar() {
        imgMazo.setOnMouseEntered(e -> {
            DropShadow glow = new DropShadow();
            glow.setColor(Color.LIMEGREEN);
            glow.setRadius(25);
            glow.setSpread(0.4);
            imgMazo.setEffect(glow);
        });
        imgMazo.setOnMouseExited(e -> imgMazo.setEffect(null));
        imgMazo.setOnMouseClicked(e -> onClickMazo());
    }

    protected void redibujarManoLocal() {
        List<String> cartas = manos.get(uidLocal);
        if (cartas != null) {
            dibujarAbanicoAbajo(zonaAbajo, cartas);
        }
    }

    protected int calcularCartaSeleccionada(double mouseX) {
        for (int i = 0; i < zonaAbajo.getChildren().size(); i++) {
            Bounds b = zonaAbajo.getChildren().get(i).getBoundsInParent();
            if (mouseX >= b.getMinX() && mouseX <= b.getMaxX()) {
                return i;
            }
        }
        return -1;
    }

    // =========================================================================
    //  LAYOUT DE ESCENA
    // =========================================================================
    public void configurarLayoutEscena(Stage stage) {
        final double OAX = -40, OAY = -10, ORRX = -40, ORRY = -110;
        final double OIX = -70, OIY = 100, ODX = 0, ODY = -210, OCX = 0, OCY = 0;

        Runnable recolocar = () -> {
            double w = stage.getWidth(), h = stage.getHeight();
            double cx = w / 2, cy = h / 2;
            zonaCentro.setLayoutX(cx - zonaCentro.getWidth() / 2 + OCX);
            zonaCentro.setLayoutY(cy - zonaCentro.getHeight() / 2 + OCY);
            zonaAbajo.setLayoutX(cx - zonaAbajo.getWidth() / 2 + OAX);
            zonaAbajo.setLayoutY(h - zonaAbajo.getHeight() - 20 + OAY);
            zonaArriba.setLayoutX(cx - zonaArriba.getWidth() / 2 + ORRX);
            zonaArriba.setLayoutY(20 + ORRY);
            zonaIzquierda.setLayoutX(20 + OIX);
            zonaIzquierda.setLayoutY(cy - zonaIzquierda.getHeight() / 2 + OIY);
            zonaDerecha.setLayoutX(w - zonaDerecha.getWidth() - 20 + ODX);
            zonaDerecha.setLayoutY(cy - zonaDerecha.getHeight() / 2 + ODY);
        };

        Platform.runLater(recolocar);
        stage.widthProperty().addListener((obs, o, n) -> recolocar.run());
        stage.heightProperty().addListener((obs, o, n) -> recolocar.run());
    }

    // =========================================================================
    //  DIBUJO DE ABANICOS — idénticos en todos los modos
    // =========================================================================
    protected void dibujarAbanicoAbajo(Pane zona, List<String> cartas) {
        zona.getChildren().clear();
        if (cartas == null || cartas.isEmpty()) {
            return;
        }
        int n = cartas.size();

        if (n == 1) {
            Image imgRaw = new Image(getClass().getResourceAsStream(cartas.get(0)));
            ImageView img = new ImageView(imgRaw);
            img.setFitHeight(140);
            img.setPreserveRatio(true);
            double y = BASE_Y - Math.cos(0) * (ALTURA_BASE + 3) - 20;
            double centroPaneX = zona.getWidth() / 2;
            img.setLayoutX(centroPaneX - img.getFitWidth() / 2 - 10);
            img.setLayoutY(y);
            String ruta = cartas.get(0);
            img.setOnMouseClicked(e -> onCartaLocalClick(ruta));
            if (manoAbierta) {
                img.setScaleX(1.25);
                img.setScaleY(1.25);
                img.setTranslateY(-25);
                DropShadow g = new DropShadow();
                g.setColor(Color.RED);
                g.setRadius(25);
                img.setEffect(g);
            }
            zona.getChildren().add(img);
            return;
        }

        double radio = RADIO_BASE + (n * 20) + (manoAbierta ? 60 : 0);
        double anguloTotal = ANGULO_BASE + (n * 2) + (manoAbierta ? 20 : 0);
        double alturaArco = ALTURA_BASE + (n * 3) + (manoAbierta ? 15 : 0);
        double divisorRot = ROTACION_BASE + (n * 0.05);
        double anguloInic = -anguloTotal / 2;
        double centroPaneX = zona.getWidth() / 2;

        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>(), rots = new ArrayList<>();
        List<ImageView> imgs = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            ImageView img = new ImageView(new Image(cartas.get(i)));
            img.setFitHeight(140);
            img.setPreserveRatio(true);
            String ruta = cartas.get(i);
            img.setOnMouseClicked(e -> onCartaLocalClick(ruta));
            double angulo = anguloInic + (anguloTotal / (n - 1)) * i;
            double rad = Math.toRadians(angulo);
            double x = Math.sin(rad) * radio, y = BASE_Y - Math.cos(rad) * alturaArco;
            double rot = angulo / divisorRot;
            double compY = Math.sin(Math.toRadians(Math.abs(rot))) * (img.getFitHeight() * 0.35);
            x -= img.getFitHeight() / 4;
            y += compY;
            if (manoAbierta && cartaSeleccionada != -1) {
                int dist = Math.abs(i - cartaSeleccionada);
                double sep = dist * 12.0;
                if (i < cartaSeleccionada) {
                    x -= sep;
                } else if (i > cartaSeleccionada) {
                    x += sep;
                }
            }
            xs.add(x);
            ys.add(y);
            rots.add(rot);
            imgs.add(img);
        }

        double minX = xs.stream().min(Double::compare).get();
        double maxX = xs.stream().max(Double::compare).get();
        double offsetX = centroPaneX - (minX + maxX) / 2;

        for (int i = 0; i < n; i++) {
            ImageView img = imgs.get(i);
            img.setLayoutX(xs.get(i) + offsetX);
            img.setLayoutY(ys.get(i));
            img.setRotate(rots.get(i));
            zona.getChildren().add(img);
            if (manoAbierta && i == cartaSeleccionada) {
                img.setScaleX(1.25);
                img.setScaleY(1.25);
                img.setTranslateY(-25);
                DropShadow g = new DropShadow();
                g.setColor(Color.RED);
                g.setRadius(25);
                img.setEffect(g);
            } else {
                img.setScaleX(1.0);
                img.setScaleY(1.0);
                img.setTranslateY(0);
                img.setEffect(null);
            }
        }
    }

    protected void dibujarAbanicoArriba(Pane zona, List<String> cartas) {
        zona.getChildren().clear();
        if (cartas == null || cartas.isEmpty()) {
            return;
        }
        int n = cartas.size();
        if (n == 1) {
            ImageView img = new ImageView(new Image("/ui/graphicResources/cartas/parteTrasera.png"));
            img.setFitHeight(120);
            img.setPreserveRatio(true);
            double y = (zona.getHeight() - 40) - Math.cos(0) * (ALTURA_BASE + 3) - 25;
            double cx = zona.getWidth() / 2, cy = zona.getHeight() / 2;
            double x0 = cx - img.getFitWidth() / 2;
            img.setLayoutX(2 * cx - x0);
            img.setLayoutY(2 * cy - y);
            img.setRotate(180);
            zona.getChildren().add(img);
            return;
        }
        double radio = RADIO_BASE + (n * 20), anguloTotal = ANGULO_BASE + (n * 2), alturaArco = ALTURA_BASE + (n * 3);
        double divisorRot = ROTACION_BASE + (n * 0.05), anguloInic = -anguloTotal / 2;
        double centroPaneX = zona.getWidth() / 2, cx = zona.getWidth() / 2, cy = zona.getHeight() / 2;
        double baseYLocal = zona.getHeight() - 40;
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>(), rots = new ArrayList<>();
        List<ImageView> imgs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ImageView img = new ImageView(new Image("/ui/graphicResources/cartas/parteTrasera.png"));
            img.setFitHeight(120);
            img.setPreserveRatio(true);
            double angulo = anguloInic + (anguloTotal / (n - 1)) * i;
            double rad = Math.toRadians(angulo);
            double x = Math.sin(rad) * radio, y = baseYLocal - Math.cos(rad) * alturaArco;
            double rot = angulo / divisorRot;
            x -= img.getFitHeight() / 4;
            y += Math.sin(Math.toRadians(Math.abs(rot))) * (img.getFitHeight() * 0.35);
            xs.add(x);
            ys.add(y);
            rots.add(rot);
            imgs.add(img);
        }
        double minX = xs.stream().min(Double::compare).get(), maxX = xs.stream().max(Double::compare).get();
        double offsetX = centroPaneX - (minX + maxX) / 2;
        for (int i = 0; i < n; i++) {
            ImageView img = imgs.get(i);
            double x0 = xs.get(i) + offsetX, y0 = ys.get(i);
            img.setLayoutX(2 * cx - x0);
            img.setLayoutY(2 * cy - y0);
            img.setRotate(rots.get(i) + 180);
            zona.getChildren().add(img);
        }
    }

    protected void dibujarAbanicoIzquierda(Pane zona, List<String> cartas) {
        zona.getChildren().clear();
        if (cartas == null || cartas.isEmpty()) {
            return;
        }
        int n = cartas.size();
        if (n == 1) {
            ImageView img = new ImageView(new Image("/ui/graphicResources/cartas/parteTrasera.png"));
            img.setFitHeight(120);
            img.setPreserveRatio(true);
            double cx = zona.getWidth() / 2, cy = zona.getHeight() / 2;
            double y0 = cy - img.getFitHeight() / 2 + 40, x0 = -40.0;
            double dx = x0 - cx, dy = y0 - cy;
            img.setLayoutX(cx - dy);
            img.setLayoutY(cy + dx);
            img.setRotate(90);
            zona.getChildren().add(img);
            return;
        }
        double radio = RADIO_BASE + (n * 20), anguloTotal = ANGULO_BASE + (n * 2), alturaArco = ALTURA_BASE + (n * 3);
        double divisorRot = ROTACION_BASE + (n * 0.05), anguloInic = -anguloTotal / 2;
        double centroPaneY = zona.getHeight() / 2, cx = zona.getWidth() / 2, cy = zona.getHeight() / 2;
        double baseYLocal = zona.getHeight() / 2 + 80;
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>(), rots = new ArrayList<>();
        List<ImageView> imgs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ImageView img = new ImageView(new Image("/ui/graphicResources/cartas/parteTrasera.png"));
            img.setFitHeight(120);
            img.setPreserveRatio(true);
            double angulo = anguloInic + (anguloTotal / (n - 1)) * i;
            double rad = Math.toRadians(angulo);
            double x = Math.sin(rad) * radio, y = baseYLocal - Math.cos(rad) * alturaArco;
            double rot = angulo / divisorRot;
            x -= img.getFitHeight() / 4;
            y += Math.sin(Math.toRadians(Math.abs(rot))) * (img.getFitHeight() * 0.35);
            xs.add(x);
            ys.add(y);
            rots.add(rot);
            imgs.add(img);
        }
        double minY = ys.stream().min(Double::compare).get(), maxY = ys.stream().max(Double::compare).get();
        double offsetY = centroPaneY - (minY + maxY) / 2;
        for (int i = 0; i < n; i++) {
            ImageView img = imgs.get(i);
            double x0 = xs.get(i), y0 = ys.get(i) + offsetY;
            double dx = x0 - cx, dy = y0 - cy;
            img.setLayoutX(cx - dy);
            img.setLayoutY(cy + dx);
            img.setRotate(rots.get(i) + 90);
            zona.getChildren().add(img);
        }
    }

    protected void dibujarAbanicoDerecha(Pane zona, List<String> cartas) {
        zona.getChildren().clear();
        if (cartas == null || cartas.isEmpty()) {
            return;
        }
        int n = cartas.size();
        if (n == 1) {
            ImageView img = new ImageView(new Image("/ui/graphicResources/cartas/parteTrasera.png"));
            img.setFitHeight(120);
            img.setPreserveRatio(true);
            double cx = zona.getWidth() / 2, cy = zona.getHeight() / 2;
            double y0 = cy - img.getFitHeight() / 2 + 15, x0 = 0.0;
            double dx = x0 - cx, dy = y0 - cy;
            img.setLayoutX(cx + dy);
            img.setLayoutY(cy - dx);
            img.setRotate(-90);
            zona.getChildren().add(img);
            return;
        }
        double radio = RADIO_BASE + (n * 20), anguloTotal = ANGULO_BASE + (n * 2), alturaArco = ALTURA_BASE + (n * 3);
        double divisorRot = ROTACION_BASE + (n * 0.05), anguloInic = -anguloTotal / 2;
        double centroPaneY = zona.getHeight() / 2, cx = zona.getWidth() / 2, cy = zona.getHeight() / 2;
        double baseYLocal = zona.getHeight() / 2 + 80;
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>(), rots = new ArrayList<>();
        List<ImageView> imgs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ImageView img = new ImageView(new Image("/ui/graphicResources/cartas/parteTrasera.png"));
            img.setFitHeight(120);
            img.setPreserveRatio(true);
            double angulo = anguloInic + (anguloTotal / (n - 1)) * i;
            double rad = Math.toRadians(angulo);
            double x = Math.sin(rad) * radio, y = baseYLocal - Math.cos(rad) * alturaArco;
            double rot = angulo / divisorRot;
            x -= img.getFitHeight() / 4;
            y += Math.sin(Math.toRadians(Math.abs(rot))) * (img.getFitHeight() * 0.35);
            xs.add(x);
            ys.add(y);
            rots.add(rot);
            imgs.add(img);
        }
        double minY = ys.stream().min(Double::compare).get(), maxY = ys.stream().max(Double::compare).get();
        double offsetY = centroPaneY - (minY + maxY) / 2;
        for (int i = 0; i < n; i++) {
            ImageView img = imgs.get(i);
            double x0 = xs.get(i), y0 = ys.get(i) + offsetY;
            double dx = x0 - cx, dy = y0 - cy;
            img.setLayoutX(cx + dy);
            img.setLayoutY(cy - dx);
            img.setRotate(rots.get(i) - 90);
            zona.getChildren().add(img);
        }
    }

    private String obtenerUidHost() {
        try {
            return db.leerNodo("salas/" + codigoSala + "/host", idToken)
                    .replace("\"", "");
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    /**
     * Las subclases llaman a esto en registrarListenersPropios() para que
     * destruir() también interrumpa sus hilos.
     */
    protected void registrarHiloListener(Thread hilo) {
        if (hilo != null) {
            hilosListeners.add(hilo);
        }
    }

    // En PartidaControllerBase, añade este método:
    private void precargarNombres() {
        new Thread(() -> {
            try {
                String json = db.leerNodo("salas/" + codigoSala + "/jugadores", idToken);
                if (json == null || "null".equals(json)) {
                    return;
                }
                Map<String, Object> jugadores = new Gson().fromJson(json, Map.class);
                for (String uid : jugadores.keySet()) {
                    // Poner UID como fallback inmediatamente
                    nombres.putIfAbsent(uid, uid);
                    try {
                        String nombreJson = db.leerNodo("usuarios/" + uid + "/nombre", idToken);
                        if (nombreJson != null) {
                            nombres.put(uid, nombreJson.replace("\"", ""));
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // Método público — para que el popup pueda llamarlo
    public void destruir() {
        controladorDestruido = true;
        int n = hilosListeners.size();
        for (Thread hilo : hilosListeners) {
            hilo.interrupt();
        }
        hilosListeners.clear();
        System.out.println("[Base] Controlador destruido. " + n + " listeners parados.");
    }

// Método privado — para uso interno antes de navegar
    private void prepararNavegacion() {
        controladorDestruido = true;
    }

    public void reiniciarPartidaOffline() {
        // por defecto no hace nada
    }

    public void volverAlMenuOffline() {
        // por defecto no hace nada
    }

}
