/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package ui;

import i18n.IdiomaManager;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import partidaUTIL.Baraja;
import partidaUTIL.Carta;
import partidaUTIL.Juego;
import partidaUTIL.JuegoYusa;

/**
 * Modo Yusa OFFLINE — extiende PartidaControllerBase para reutilizar
 * exactamente el mismo layout visual del modo online.
 *
 * Sin Firebase. Todo en memoria local. La IA toma sus decisiones con un delay
 * de 2-3 segundos.
 *
 * Punto de entrada: iniciarOffline(numIAs) tras loader.load()
 */
public class PartidaOfflineYusaController extends PartidaControllerBase {

    // ─── Constantes ───────────────────────────────────────────────────────────
    private static final String UID_JUGADOR = "jugador";
    private static final String PREFIJO_IA = "ia_";
    private static final double DELAY_IA_SEG = 2.5;
    private StackPane overlayDesempate = null;

    // ─── Estado de partida ────────────────────────────────────────────────────
    private int numIAs = 1;
    private JuegoYusa.FaseRonda faseRondaActual = null;

    // ── Campos debug ──────────────────────────────────────────────────────────
    private static final boolean MODO_DEBUG_IA = false;
    private Button btnIADebug = null;
    private Runnable accionIAPendiente = null;

    /**
     * Orden de decisiones en la ronda NORMAL (índice 0 = decide ahora)
     */
    private final List<String> ordenDecisiones = new ArrayList<>();

    /**
     * Snapshot de cartas antes de revelar
     */
    private final Map<String, String> snapshotLocal = new HashMap<>();

    /**
     * Duelos pendientes de resolver en fase YUSA
     */
    private final List<DueloYusa> duelosPendientes = new ArrayList<>();

    /**
     * Cola de poseedores de yusa que deben preguntar
     */
    private final List<String> colaYusas = new ArrayList<>();

    /**
     * Empatados actuales para la mini-ronda de desempate
     */
    private List<String> empatadosActuales = null;
    private boolean enDesempate = false;

    // ─── Estado UI ────────────────────────────────────────────────────────────
    private StackPane overlayEliminado = null;

    /**
     * true si estamos mostrando cartas (bloquea la UI 5s)
     */
    private boolean mostrandoCartas = false;

    /**
     * record para guardar un duelo de yusa
     */
    private record DueloYusa(String poseedor, String objetivo, JuegoYusa.Palo paloElegido) {

    }

    // =========================================================================
    //  PUNTO DE ENTRADA
    // =========================================================================
    public void iniciarOffline(int n) {
        this.numIAs = Math.max(1, Math.min(3, n));

        this.uidLocal = UID_JUGADOR;
        this.codigoSala = "offline";
        this.idToken = "";

        nombres.put(UID_JUGADOR, "jugador");
        for (int i = 1; i <= numIAs; i++) {
            nombres.put(PREFIJO_IA + i, "IA " + i);
        }

        ordenJugadoresGlobal.clear();
        ordenJugadoresGlobal.add(UID_JUGADOR);
        for (int i = 1; i <= numIAs; i++) {
            ordenJugadoresGlobal.add(PREFIJO_IA + i);
        }

        Baraja b = new Baraja();
        b.barajar();
        for (Carta c : b.getCartasRestantes()) {
            baraja.add(c.getRutaImagen());
        }

        for (String uid : ordenJugadoresGlobal) {
            manos.put(uid, new ArrayList<>());
        }

        juego = new JuegoYusa();
        juego.iniciarPartida(manos, baraja);
        yusa().inicializarJugadoresVivos(new ArrayList<>(ordenJugadoresGlobal));

        repartoInicialHecho = true;
        uidTurnoActual = UID_JUGADOR;

        configurarEventosManoJugador();

        Platform.runLater(() -> {
            // Igual que pescaito: configurar layout ANTES de actualizar interfaz
            Stage stage = (Stage) zonaAbajo.getScene().getWindow();
            if (stage != null) {
                configurarLayoutEscena(stage);
            }
            actualizarInterfaz();
            narrarGlobal(IdiomaManager.get("yusaOffline.global.offline.inicio", numIAs));
            iniciarRondaLocal();
        });
    }

    // =========================================================================
    //  SOBREESCRITURAS — desactivar Firebase
    // =========================================================================
    @Override
    public void init(String c, String u, String t) {
        /* no usar */ }

    @Override
    protected void prepararEstadoInicial(Map<String, Object> p) {
    }

    protected void registrarListenersComunes() {
    }

    @Override
    protected void registrarListenersPropios() {
    }

    @Override
    protected void cargarOrdenJugadoresGlobal() {
    }

    @Override
    protected void finalizarPartida() {
        if (partidaFinalizada) {
            return;
        }
        partidaFinalizada = true;

        zonaArriba.setDisable(true);
        zonaIzquierda.setDisable(true);
        zonaDerecha.setDisable(true);
        zonaAbajo.setDisable(true);
        zonaCentro.setDisable(true);

        Platform.runLater(this::mostrarPantallaFinal);
    }

    @Override
    protected void narrarGlobal(String texto) {
        Platform.runLater(() -> narradorLabel.setText(texto));
    }

    @Override
    protected void narrarPrivado(String uid, String texto) {
        if (UID_JUGADOR.equals(uid)) {
            Platform.runLater(() -> narradorLabel.setText(texto));
        }
    }

    @Override
    protected void actualizarInterfaz() {
        if (mostrandoCartas) {
            return;
        }
        super.actualizarInterfaz(); // usa los abanicos perfectos de la base
    }

    @Override
    protected void cargarNombresJugadores() {
        if (lblAbajo != null) {
            lblAbajo.setText(nombres.getOrDefault(uidLocal, "Tu"));
        }
        if (lblArriba != null) {
            lblArriba.setText(nombres.getOrDefault(uidJugadorArriba, ""));
        }
        if (lblIzquierda != null) {
            lblIzquierda.setText(nombres.getOrDefault(uidJugadorIzquierda, ""));
        }
        if (lblDerecha != null) {
            lblDerecha.setText(nombres.getOrDefault(uidJugadorDerecha, ""));
        }
    }

    // =========================================================================
    //  HOOKS ABSTRACTOS
    // =========================================================================
    @Override
    protected Juego crearJuego(String modo) {
        return new JuegoYusa();
    }

    @Override
    protected void onCambioTurno(String t) {
    }

    @Override
    protected void onClickMazo() {
        narrarPrivado(UID_JUGADOR, IdiomaManager.get("yusaOffline.privado.avisoUsarBotones"));
    }

    // ── CORRECCIÓN 1: onZonaRivalClick ───────────────────────────────────────
    // El humano puede ser poseedor aunque no sea uidTurnoActual
    @Override
    protected void onZonaRivalClick(String uidRival) {
        if (faseRondaActual == JuegoYusa.FaseRonda.YUSA
                && !colaYusas.isEmpty()
                && colaYusas.get(0).equals(UID_JUGADOR)) {  // ← es el turno del humano en la cola
            onElegirObjetivoHumano(uidRival);
        }
    }

    @Override
    protected void onCartaLocalClick(String ruta) {
    }

    @Override
    protected DatosPopUp construirDatosPopUpFinal() {
        Map<String, Integer> vidas = yusa().getTodasLasVidas();
        List<String> supervivientes = vidas.entrySet().stream()
                .filter(e -> e.getValue() > 0).map(Map.Entry::getKey)
                .collect(Collectors.toList());

        String resultado, detalle;
        String icono;

        if (supervivientes.size() == 1) {

            String uid = supervivientes.get(0);
            String nombre = nombres.getOrDefault(uid, "Jugador");
            int v = vidas.get(uid);

            resultado = IdiomaManager.get("yusaOffline.popUpFinal.victoria", nombre);
            detalle = IdiomaManager.get("yusaOffline.popUpFinal.detalle", v);
            icono = "/ui/graphicResources/imagenes/imgGanador.png";

        } else {

            resultado = IdiomaManager.get("yusaOffline.popUpFinal.empate");

            String lista = supervivientes.stream()
                    .map(u -> nombres.getOrDefault(u, u))
                    .collect(Collectors.joining(", "));

            detalle = IdiomaManager.get("yusaOffline.popUpFinal.detalleEmpate", lista);
            icono = "/ui/graphicResources/imagenes/imgEmpate.png";
        }

        return new DatosPopUp(icono, resultado, detalle);
    }

    // =========================================================================
    //  INICIO DE RONDA
    // =========================================================================
    private void iniciarRondaLocal() {
        if (partidaFinalizada) {
            return;
        }

        logEstadoYusa("Nueva ronda — Fase: " + faseRondaActual);

        faseRondaActual = null;
        ordenDecisiones.clear();
        snapshotLocal.clear();
        duelosPendientes.clear();
        colaYusas.clear();

        yusa().repartirCartas(manos, baraja);
        actualizarInterfaz();

        faseRondaActual = yusa().determinarFaseRonda(manos);
        narrarGlobal(
                IdiomaManager.get("yusaOffline.global.ronda.nueva", textoFase(faseRondaActual))
        );

        PauseTransition pausa = new PauseTransition(Duration.millis(800));
        pausa.setOnFinished(ev -> {
            switch (faseRondaActual) {
                case NORMAL ->
                    iniciarFaseNormal();
                case DOCE ->
                    iniciarFaseDoce();
                case YUSA ->
                    iniciarFaseYusa();
            }
        });
        pausa.play();
    }

    private String textoFase(JuegoYusa.FaseRonda f) {
        return switch (f) {
            case YUSA ->
                IdiomaManager.get("fase.yusa");
            case DOCE ->
                IdiomaManager.get("fase.doce");
            case NORMAL ->
                IdiomaManager.get("fase.normal");
        };
    }

    private JuegoYusa yusa() {
        return (JuegoYusa) juego;
    }

    // =========================================================================
    //  RONDA NORMAL
    // =========================================================================
    private void iniciarFaseNormal() {
        ordenDecisiones.clear();

        // Si hay desempate activo, solo participan los empatados
        List<String> participantes = (enDesempate && empatadosActuales != null && !empatadosActuales.isEmpty())
                ? empatadosActuales
                : yusa().getJugadoresVivos().stream()
                        .filter(uid -> ordenJugadoresGlobal.contains(uid))
                        .collect(Collectors.toList());

        // Rotado desde uidTurnoActual
        int idx = ordenJugadoresGlobal.indexOf(uidTurnoActual);

        /**
         *
         *
         * for (int i = 0; i < ordenJugadoresGlobal.size(); i++) { String uid =
         * ordenJugadoresGlobal.get((idx + i) % ordenJugadoresGlobal.size()); if
         * (yusa().getJugadoresVivos().contains(uid)) {
         * ordenDecisiones.add(uid); } } procesarSiguienteDecision();
         *
         */
        for (int i = 0; i < ordenJugadoresGlobal.size(); i++) {
            String uid = ordenJugadoresGlobal.get((idx + i) % ordenJugadoresGlobal.size());
            if (participantes.contains(uid)) {
                ordenDecisiones.add(uid);
            }
        }
        procesarSiguienteDecision();

    }

    private void procesarSiguienteDecision() {
        if (ordenDecisiones.isEmpty()) {
            // Todos decidieron → revelar
            guardarSnapshotLocal();
            revelarCartas();
            return;
        }

        String actual = ordenDecisiones.get(0);
        boolean esUltimo = ordenDecisiones.size() == 1;

        if (esIA(actual)) {
            PauseTransition p = new PauseTransition(Duration.seconds(DELAY_IA_SEG));
            p.setOnFinished(ev -> decisionIA_Normal(actual, esUltimo));
            p.play();
        } else {
            // Humano — solo mostrar botones si participa en esta ronda
            boolean humanoParticipa = !enDesempate
                    || (empatadosActuales != null && empatadosActuales.contains(UID_JUGADOR));

            if (!humanoParticipa) {
                // El humano no debería estar en ordenDecisiones durante un desempate sin él
                // pero por seguridad, avanzar sin botones
                avanzarDecisionNormal(UID_JUGADOR, false);
                return;
            }
            if (esUltimo) {

                narrarPrivado(
                        UID_JUGADOR,
                        IdiomaManager.get("yusaOffline.privado.ultimo")
                );

                mostrarBotonesDecision(
                        IdiomaManager.get("yusaOffline.privado.ultimo.op1"),
                        IdiomaManager.get("yusaOffline.privado.ultimo.op2"),
                        dec -> {
                            ocultarPanelDecisionLocal();

                            if (dec.equals(IdiomaManager.get("yusaOffline.privado.ultimo.op2"))) {
                                ejecutarRoboUltimo(UID_JUGADOR);
                            } else {
                                avanzarDecisionNormal(UID_JUGADOR, false);
                            }
                        }
                );

            } else {

                narrarPrivado(
                        UID_JUGADOR,
                        IdiomaManager.get("yusaOffline.privado.normal")
                );

                mostrarBotonesDecision(
                        IdiomaManager.get("yusaOffline.privado.normal.op1"),
                        IdiomaManager.get("yusaOffline.privado.normal.op2"),
                        dec -> {
                            ocultarPanelDecisionLocal();

                            boolean cambia = dec.equals(IdiomaManager.get("yusaOffline.privado.normal.op2"));
                            avanzarDecisionNormal(UID_JUGADOR, cambia);
                        }
                );
            }

        }
    }

    private void decisionIA_Normal(String uidIA, boolean esUltimo) {

        programarAccionIA(
                IdiomaManager.get("yusaOffline.global.ia.decide", nombres.get(uidIA)),
                () -> {

                    boolean cambia = new Random().nextBoolean();
                    String nomIA = nombres.get(uidIA);

                    if (esUltimo) {

                        boolean roba = new Random().nextBoolean();

                        narrarGlobal(
                                IdiomaManager.get(
                                        roba ? "yusaOffline.global.ia.ultimo.op2"
                                                : "yusaOffline.global.ia.ultimo.op1",
                                        nomIA
                                )
                        );

                        if (roba) {
                            ejecutarRoboUltimo(uidIA);
                        } else {
                            avanzarDecisionNormal(uidIA, false);
                        }

                    } else {

                        narrarGlobal(
                                IdiomaManager.get(
                                        cambia ? "yusaOffline.global.ia.normal.op2"
                                                : "yusaOffline.global.ia.normal.op1",
                                        nomIA
                                )
                        );

                        avanzarDecisionNormal(uidIA, cambia);
                    }
                }
        );
    }

    private void avanzarDecisionNormal(String uid, boolean cambia) {
        if (cambia && ordenDecisiones.size() > 1) {
            String siguiente = ordenDecisiones.get(1);
            yusa().intercambiarCartas(uid, siguiente, manos);
            actualizarInterfaz();
        }
        ordenDecisiones.remove(0);
        procesarSiguienteDecision();
    }

    private void ejecutarRoboUltimo(String uid) {

        String nombre = nombres.getOrDefault(uid, "Jugador");

        if (baraja.isEmpty()) {
            narrarGlobal(IdiomaManager.get("yusaOffline.global.ultimo.barajaVacia", nombre));
            guardarSnapshotLocal();
            revelarCartas();
            return;
        }

        List<String> mano = manos.computeIfAbsent(uid, k -> new ArrayList<>());
        if (!mano.isEmpty()) {
            descarte.add(mano.get(0));
            mano.clear();
        }

        mano.add(baraja.remove(0));

        narrarGlobal(IdiomaManager.get("yusaOffline.global.ultimo.cambia", nombre));
        actualizarInterfaz();

        JuegoYusa.FaseRonda nueva = yusa().determinarFaseRonda(manos);

        if (nueva != JuegoYusa.FaseRonda.NORMAL) {

            faseRondaActual = nueva;

            narrarGlobal(
                    IdiomaManager.get("yusaOffline.global.fase.cambia", textoFase(nueva))
            );

            PauseTransition p = new PauseTransition(Duration.seconds(1));
            p.setOnFinished(ev -> {
                switch (nueva) {
                    case DOCE ->
                        iniciarFaseDoce();
                    case YUSA ->
                        iniciarFaseYusa();
                    default -> {
                        guardarSnapshotLocal();
                        revelarCartas();
                    }
                }
            });
            p.play();

        } else {
            guardarSnapshotLocal();
            revelarCartas();
        }
    }

    // =========================================================================
    //  RONDA DOCE
    // =========================================================================
    private void iniciarFaseDoce() {
        // Buscar quién tiene el 12
        String uidDoce = null;
        for (String uid : yusa().getJugadoresVivos()) {
            List<String> m = manos.get(uid);
            if (m != null && !m.isEmpty() && yusa().obtenerNumeroCarta(m.get(0)) == JuegoYusa.NUMERO_DOCE) {
                uidDoce = uid;
                break;
            }
        }
        if (uidDoce == null) {
            guardarSnapshotLocal();
            revelarCartas();
            return;
        }

        final String uidFinal = uidDoce;

        if (esIA(uidDoce)) {
            narrarGlobal(
                    IdiomaManager.get("yusaOffline.global.doce.ia.tiene",
                            nombres.get(uidDoce),
                            (int) DELAY_IA_SEG)
            );
            PauseTransition p = new PauseTransition(Duration.seconds(DELAY_IA_SEG));
            p.setOnFinished(ev -> {
                narrarGlobal(
                        IdiomaManager.get("yusaOffline.global.doce.ia.juega",
                                nombres.get(uidFinal))
                );
                guardarSnapshotLocal();
                revelarCartas();
            });
            p.play();
        } else {
            // Humano: botón con countdown
            Button btn = new Button(IdiomaManager.get("yusaOffline.privado.doce.boton", 10));
            btn.setStyle("-fx-font-size:14px;-fx-padding:8 18;");
            int[] seg = {10};
            Runnable jugar = () -> {
                if (tickDoceLocal != null) {
                    tickDoceLocal.stop();
                    tickDoceLocal = null;
                }
                ocultarPanelDecisionLocal();
                guardarSnapshotLocal();
                revelarCartas();
            };
            tickDoceLocal = new PauseTransition(Duration.seconds(1));
            tickDoceLocal.setOnFinished(ev -> {
                seg[0]--;
                btn.setText(IdiomaManager.get("yusaOffline.privado.doce.boton", seg[0]));
                if (seg[0] > 0) {
                    tickDoceLocal.playFromStart();
                } else {
                    jugar.run();
                }
            });
            btn.setOnAction(e -> jugar.run());
            panelDecisionLocal = new javafx.scene.layout.HBox(btn);
            panelDecisionLocal.setAlignment(javafx.geometry.Pos.CENTER);
            javafx.scene.layout.StackPane.setAlignment(panelDecisionLocal, javafx.geometry.Pos.BOTTOM_CENTER);
            panelDecisionLocal.setTranslateY(-40);
            rootSala.getChildren().add(panelDecisionLocal);
            tickDoceLocal.play();
            narrarPrivado(
                    UID_JUGADOR,
                    IdiomaManager.get("yusaOffline.privado.doce.tienes", 10)
            );
        }
    }

    private PauseTransition tickDoceLocal = null;
    private javafx.scene.layout.HBox panelDecisionLocal = null;

    // =========================================================================
    //  RONDA YUSA
    // =========================================================================
    private void iniciarFaseYusa() {
        colaYusas.clear();
        for (String uid : yusa().getJugadoresVivos()) {
            List<String> m = manos.get(uid);
            if (m != null && !m.isEmpty() && yusa().obtenerNumeroCarta(m.get(0)) == JuegoYusa.NUMERO_YUSA) {
                colaYusas.add(uid);
            }
        }
        Collections.shuffle(colaYusas);

        if (colaYusas.size() == 1) {
            narrarGlobal(
                    IdiomaManager.get(
                            "yusaOffline.global.faseYusa.uno",
                            nombres.getOrDefault(colaYusas.get(0), "Jugador")
                    )
            );
        } else {
            narrarGlobal(
                    IdiomaManager.get(
                            "yusaOffline.global.faseYusa.varias",
                            colaYusas.size()
                    )
            );
        }

        publicarSiguientePreguntaYusa();
    }

    private void publicarSiguientePreguntaYusa() {
        // Saltar eliminados
        while (!colaYusas.isEmpty() && !yusa().getJugadoresVivos().contains(colaYusas.get(0))) {
            colaYusas.remove(0);
        }

        if (colaYusas.isEmpty()) {
            // Todas las yusas respondidas → revelar
            guardarSnapshotLocal();
            revelarCartas();
            return;
        }

        String poseedor = colaYusas.get(0);
        narrarGlobal(
                IdiomaManager.get(
                        "yusaOffline.global.pregunta.turno",
                        nombres.getOrDefault(poseedor, "Jugador")
                )
        );

        if (esIA(poseedor)) {
            PauseTransition p = new PauseTransition(Duration.seconds(DELAY_IA_SEG));
            p.setOnFinished(ev -> elegirObjetivoIA(poseedor));
            p.play();
        } else {
            narrarPrivado(
                    UID_JUGADOR,
                    IdiomaManager.get("yusaOffline.privado.pregunta.tienes")
            );
            // El humano hará clic en la zona del rival → onZonaRivalClick → onElegirObjetivoHumano
        }
    }

    /**
     * Humano elige objetivo pulsando zona rival
     */
    private void onElegirObjetivoHumano(String uidObjetivo) {
        if (colaYusas.isEmpty() || !colaYusas.get(0).equals(UID_JUGADOR)) {
            return;
        }
        if (uidObjetivo == null || uidObjetivo.equals(UID_JUGADOR)) {
            narrarPrivado(
                    UID_JUGADOR,
                    IdiomaManager.get("yusaOffline.privado.objetivo.noTuMismo")
            );
            return;
        }

        List<String> manoObj = manos.get(uidObjetivo);
        if (manoObj == null || manoObj.isEmpty()) {
            narrarPrivado(
                    UID_JUGADOR,
                    IdiomaManager.get("yusaOffline.privado.objetivo.sinCarta")
            );
            return;
        }

        narrarGlobal(
                IdiomaManager.get(
                        "yusaOffline.global.objetivo.pregunta",
                        nombres.get(UID_JUGADOR),
                        nombres.getOrDefault(uidObjetivo, "")
                )
        );

        // Guardar snapshot de la carta del poseedor
        List<String> manoHumano = manos.get(UID_JUGADOR);
        if (manoHumano != null && !manoHumano.isEmpty()) {
            snapshotLocal.put(UID_JUGADOR, manoHumano.get(0));
        }

        colaYusas.remove(0);

        // El objetivo elige palo
        if (esIA(uidObjetivo)) {
            PauseTransition p = new PauseTransition(Duration.seconds(DELAY_IA_SEG));
            p.setOnFinished(ev -> {
                JuegoYusa.Palo paloAleatorio = palosArray()[new Random().nextInt(4)];
                narrarGlobal(
                        IdiomaManager.get(
                                "yusaOffline.global.objetivo.respuesta",
                                nombres.getOrDefault(uidObjetivo, ""),
                                paloAleatorio.name()
                        )
                );
                duelosPendientes.add(new DueloYusa(UID_JUGADOR, uidObjetivo, paloAleatorio));
                publicarSiguientePreguntaYusa();
            });
            p.play();
        } else {
            // El objetivo es el jugador humano — pedir que elija palo
            pedirPaloAlHumano(UID_JUGADOR, uidObjetivo);
        }
    }

    /**
     * IA elige objetivo aleatorio y el objetivo responde
     */
    private void elegirObjetivoIA(String poseedor) {
        programarAccionIA(
                IdiomaManager.get("yusaOffline.global.ia.eligeObjetivo", nombres.get(poseedor)),
                () -> {

                    List<String> candidatos = yusa().getJugadoresVivos().stream()
                            .filter(u -> !u.equals(poseedor))
                            .filter(u -> {
                                List<String> m = manos.get(u);
                                return m != null && !m.isEmpty();
                            })
                            .collect(Collectors.toList());

                    if (candidatos.isEmpty()) {
                        narrarGlobal(
                                IdiomaManager.get(
                                        "yusaOffline.global.ia.sinCandidatos",
                                        nombres.get(poseedor)
                                )
                        );
                        colaYusas.remove(0);
                        publicarSiguientePreguntaYusa();
                        return;
                    }

                    String objetivo = candidatos.get(new Random().nextInt(candidatos.size()));
                    narrarGlobal(
                            IdiomaManager.get(
                                    "yusaOffline.global.ia.pregunta",
                                    nombres.getOrDefault(poseedor, "IA"),
                                    nombres.getOrDefault(objetivo, "")
                            )
                    );

                    // Guardar snapshot
                    List<String> manoPos = manos.get(poseedor);
                    if (manoPos != null && !manoPos.isEmpty()) {
                        snapshotLocal.put(poseedor, manoPos.get(0));
                    }

                    colaYusas.remove(0);

                    if (esIA(objetivo)) {
                        // Ambos son IA
                        PauseTransition p = new PauseTransition(Duration.seconds(DELAY_IA_SEG));
                        p.setOnFinished(ev -> {
                            JuegoYusa.Palo palo = palosArray()[new Random().nextInt(4)];
                            narrarGlobal(
                                    IdiomaManager.get(
                                            "yusaOffline.global.ia.respuesta",
                                            nombres.getOrDefault(objetivo, ""),
                                            palo.name()
                                    )
                            );
                            duelosPendientes.add(new DueloYusa(poseedor, objetivo, palo));
                            publicarSiguientePreguntaYusa();
                        });
                        p.play();
                    } else {
                        // El objetivo es el humano → pedirle palo
                        pedirPaloAlHumano(poseedor, UID_JUGADOR);
                    }
                });
    }

    /**
     * Muestra botones de palo al jugador humano cuando es el objetivo
     */
    private void pedirPaloAlHumano(String poseedor, String objetivo) {
        narrarPrivado(
                UID_JUGADOR,
                IdiomaManager.get(
                        "yusaOffline.privado.pedirPalo",
                        nombres.getOrDefault(poseedor, "tu rival")
                )
        );
        mostrarBotonesPalo(palo -> {
            ocultarPanelDecisionLocal();
            narrarGlobal(
                    IdiomaManager.get(
                            "yusaOffline.global.humano.responde",
                            nombres.get(UID_JUGADOR),
                            palo.name()
                    )
            );
            duelosPendientes.add(new DueloYusa(poseedor, objetivo, palo));
            publicarSiguientePreguntaYusa();
        });
    }

    private JuegoYusa.Palo[] palosArray() {
        return JuegoYusa.Palo.values();
    }

    // =========================================================================
    //  REVELAR CARTAS
    // =========================================================================
    private void guardarSnapshotLocal() {
        // Solo en YUSA ya guardamos el snapshot del poseedor; aquí lo completamos
        // para NORMAL y DOCE
        if (faseRondaActual != JuegoYusa.FaseRonda.YUSA) {
            snapshotLocal.clear();
            for (Map.Entry<String, List<String>> e : manos.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    snapshotLocal.put(e.getKey(), e.getValue().get(0));
                }
            }
        }
    }

    private void revelarCartas() {

        logEstadoYusa("Revelando cartas — Fase: " + faseRondaActual);

        mostrandoCartas = true;
        mostrarCartasDeRondaLocal();
        System.out.println("[YUSA-OFF] Cartas mostradas. Pausa 5s...");

        PauseTransition p = new PauseTransition(Duration.seconds(5));
        p.setOnFinished(ev -> {
            mostrandoCartas = false;
            actualizarInterfaz();
            resolverRondaLocal();
        });
        p.play();
    }

    private void mostrarCartasDeRondaLocal() {
        colocarJugadores();
        Map<String, Pane> zonas = new LinkedHashMap<>();
        zonas.put(uidLocal, zonaAbajo);
        zonas.put(uidJugadorArriba, zonaArriba);
        zonas.put(uidJugadorIzquierda, zonaIzquierda);
        zonas.put(uidJugadorDerecha, zonaDerecha);

        for (Map.Entry<String, Pane> entry : zonas.entrySet()) {
            String uid = entry.getKey();
            Pane zona = entry.getValue();
            if (uid == null) {
                continue;
            }
            if (!yusa().getJugadoresVivos().contains(uid)) {
                continue;
            }
            List<String> m = manos.get(uid);
            if (m == null || m.isEmpty()) {
                continue;
            }

            zona.getChildren().clear();
            ImageView img = new ImageView(new Image(getClass().getResourceAsStream(m.get(0))));
            img.setFitHeight(120);
            img.setPreserveRatio(true);
            double w = Math.max(zona.getWidth(), zona.getPrefWidth());
            double h = Math.max(zona.getHeight(), zona.getPrefHeight());
            double cx = w / 2, cy = h / 2;
            if (zona == zonaAbajo) {
                img.setLayoutX(cx - 5);
                img.setLayoutY(cy - 15);
                img.setRotate(0);
            } else if (zona == zonaArriba) {
                img.setLayoutX(cx - 5);
                img.setLayoutY(cy + 90);
                img.setRotate(180);
            } else if (zona == zonaIzquierda) {
                img.setLayoutX(cx + 40);
                img.setLayoutY(cy - 150);
                img.setRotate(90);
            } else if (zona == zonaDerecha) {
                img.setLayoutX(cx - 60);
                img.setLayoutY(cy + 150);
                img.setRotate(-90);
            }
            zona.getChildren().add(img);
        }
    }

    // =========================================================================
    //  RESOLVER RONDA
    // =========================================================================
    private void resolverRondaLocal() {
        switch (faseRondaActual) {
            case YUSA ->
                resolverFaseYusa();
            case DOCE, NORMAL ->
                resolverFaseNormalODoce();
        }
    }

    private void resolverFaseYusa() {
        for (DueloYusa duelo : duelosPendientes) {
            String cartaPoseedor = snapshotLocal.get(duelo.poseedor());
            if (cartaPoseedor == null) {
                List<String> mp = manos.get(duelo.poseedor());
                if (mp != null && !mp.isEmpty()) {
                    cartaPoseedor = mp.get(0);
                }
            }
            if (cartaPoseedor == null) {
                System.out.println("WARN: sin carta para " + duelo.poseedor());
                continue;
            }

            JuegoYusa.Palo paloReal = yusa().obtenerPaloCarta(cartaPoseedor);
            boolean acerto = paloReal == duelo.paloElegido();
            String perdedor = acerto ? duelo.poseedor() : duelo.objetivo();
            boolean eli = yusa().perderVida(perdedor);

            logEstadoYusa("Duelo Yusa: " + nombres.get(duelo.poseedor()) + " vs " + nombres.get(duelo.objetivo()) + " | Palo real: " + paloReal + " | Dijo: " + duelo.paloElegido() + " | Pierde: " + nombres.get(perdedor));

            narrarGlobal(
                    IdiomaManager.get(
                            "yusaOffline.global.duelo.resultado",
                            nombres.getOrDefault(duelo.poseedor(), duelo.poseedor()),
                            nombres.getOrDefault(duelo.objetivo(), duelo.objetivo()),
                            duelo.paloElegido().name(),
                            paloReal.name(),
                            IdiomaManager.get(acerto ? "yusaOffline.global.duelo.acerto" : "yusaOffline.global.duelo.fallo"),
                            nombres.getOrDefault(perdedor, perdedor),
                            yusa().getVidas(perdedor)
                    )
            );
            if (eli) {
                narrarGlobal(
                        IdiomaManager.get(
                                "yusaOffline.global.duelo.eliminado",
                                nombres.getOrDefault(perdedor, perdedor)
                        )
                );
                if (perdedor.equals(uidLocal)) {
                    mostrarMensajeEliminadoLocal();
                }
            }
        }
        duelosPendientes.clear();
        cerrarRonda();
    }

    private void resolverFaseNormalODoce() {
        Map<String, List<String>> mr = new HashMap<>();
        if (!snapshotLocal.isEmpty()) {
            for (Map.Entry<String, String> e : snapshotLocal.entrySet()) {
                mr.put(e.getKey(), List.of(e.getValue()));
            }
        } else {
            for (Map.Entry<String, List<String>> e : manos.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    mr.put(e.getKey(), new ArrayList<>(e.getValue()));
                }
            }
        }

        List<String> participantes = (empatadosActuales != null && !empatadosActuales.isEmpty())
                ? new ArrayList<>(empatadosActuales)
                : new ArrayList<>(yusa().getJugadoresVivos());

        List<String> perdedores = yusa().determinarPerdedores(mr, participantes);

        if (perdedores.size() > 1) {
            // Empate → mini-ronda
            String ne = perdedores.stream().map(u -> nombres.getOrDefault(u, u)).collect(Collectors.joining(", "));
            narrarGlobal(
                    IdiomaManager.get("yusaOffline.global.desempate.empate", ne)
            );
            empatadosActuales = new ArrayList<>(perdedores);
            enDesempate = true;
            PauseTransition p = new PauseTransition(Duration.seconds(1));
            p.setOnFinished(ev -> iniciarMiniRondaDesempateLocal());
            p.play();
            return;
        }

        if (enDesempate) {
            enDesempate = false;
            empatadosActuales = null;
        }

        if (perdedores.size() == 1) {
            String uid = perdedores.get(0);
            boolean eli = yusa().perderVida(uid);
            narrarGlobal(
                    IdiomaManager.get(
                            "yusaOffline.global.normal.perdedor",
                            nombres.getOrDefault(uid, uid),
                            yusa().getVidas(uid)
                    )
            );
            if (eli) {
                narrarGlobal(
                        IdiomaManager.get(
                                "yusaOffline.global.normal.eliminado",
                                nombres.getOrDefault(uid, uid)
                        )
                );
                if (uid.equals(uidLocal)) {
                    mostrarMensajeEliminadoLocal();
                }
            }
        }
        logEstadoYusa("Perdedor(es): " + perdedores.stream().map(u -> nombres.getOrDefault(u, u)).collect(Collectors.joining(", ")));
        cerrarRonda();
    }

    private void iniciarMiniRondaDesempateLocal() {
        faseRondaActual = null;
        snapshotLocal.clear();
        duelosPendientes.clear();
        colaYusas.clear();

        // Repartir solo a los empatados
        for (String uid : empatadosActuales) {
            List<String> mano = manos.computeIfAbsent(uid, k -> new ArrayList<>());
            mano.clear();
            if (!baraja.isEmpty()) {
                mano.add(baraja.remove(0));
            }
        }
        // Limpiar manos de no-empatados para que la fase se determine solo con los empatados
        for (String uid : yusa().getJugadoresVivos()) {
            if (!empatadosActuales.contains(uid)) {
                List<String> m = manos.get(uid);
                if (m != null) {
                    m.clear();
                }
            }
        }

        Map<String, List<String>> manosEmp = new HashMap<>();
        for (String uid : empatadosActuales) {
            List<String> m = manos.get(uid);
            if (m != null) {
                manosEmp.put(uid, m);
            }
        }
        faseRondaActual = yusa().determinarFaseRonda(manosEmp);
        actualizarInterfaz();

        String ne = empatadosActuales.stream()
                .map(u -> nombres.getOrDefault(u, u)).collect(Collectors.joining(" vs "));
        narrarGlobal(
                IdiomaManager.get(
                        "yusaOffline.global.desempate.inicio",
                        ne,
                        textoFase(faseRondaActual)
                )
        );
        logEstadoYusa("Inicio desempate: " + ne);

        boolean humanoEliminado = overlayEliminado != null; // ya tiene el overlay de eliminado
        if (!empatadosActuales.contains(UID_JUGADOR) && !humanoEliminado) {
            mostrarOverlayDesempateLocal(ne);
        }

        PauseTransition p = new PauseTransition(Duration.millis(800));
        p.setOnFinished(ev -> {
            switch (faseRondaActual) {
                case NORMAL ->
                    iniciarFaseNormal();
                case DOCE ->
                    iniciarFaseDoce();
                case YUSA ->
                    iniciarFaseYusa();
            }
        });
        p.play();
    }

    private void mostrarOverlayDesempateLocal(String nombresEmpatados) {
        ocultarOverlayDesempateLocal();
        Label lbl = new Label(IdiomaManager.get("yusaOffline.privado.overlay.desempate.titulo"));
        lbl.setStyle("-fx-font-size:26px;-fx-font-weight:bold;-fx-text-fill:#ff4444;");
        Label lblN = new Label(nombresEmpatados);
        lblN.setStyle("-fx-font-size:18px;-fx-text-fill:white;-fx-font-weight:bold;");
        Label info = new Label(IdiomaManager.get("yusaOffline.privado.overlay.desempate.info"));
        info.setStyle("-fx-font-size:14px;-fx-text-fill:#cccccc;-fx-text-alignment:center;");
        info.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        VBox contenido = new VBox(12, lbl, lblN, info);
        contenido.setAlignment(javafx.geometry.Pos.CENTER);
        contenido.setStyle("-fx-background-color:rgba(0,0,0,0.75);-fx-padding:32;-fx-background-radius:14;");
        overlayDesempate = new StackPane(contenido);
        overlayDesempate.setStyle("-fx-background-color:rgba(0,0,0,0.45);");
        javafx.scene.Scene scene = zonaAbajo.getScene();
        if (scene != null && scene.getRoot() instanceof Pane root) {
            root.getChildren().add(overlayDesempate);
            overlayDesempate.prefWidthProperty().bind(root.widthProperty());
            overlayDesempate.prefHeightProperty().bind(root.heightProperty());
        }
    }

    private void ocultarOverlayDesempateLocal() {
        if (overlayDesempate != null) {
            javafx.scene.Parent p = overlayDesempate.getParent();
            if (p instanceof Pane pane) {
                pane.getChildren().remove(overlayDesempate);
            }
            overlayDesempate = null;
        }
    }

    private void cerrarRonda() {

        ocultarOverlayDesempateLocal(); // ← AÑADIR
        if (enDesempate) {
            enDesempate = false;
            empatadosActuales = null;
        }

        if (yusa().haTerminado(manos, baraja, descarte)) {
            finalizarPartida();
            return;
        }

        yusa().descartarManosAlFinDeRonda(manos, descarte);

        if (yusa().debeResetearBaraja(descarte)) {
            yusa().resetearBaraja(baraja, descarte);
            narrarGlobal(IdiomaManager.get("yusaOffline.global.ronda.barajar"));
        }

        // Rotar director
        List<String> vivos = ordenJugadoresGlobal.stream()
                .filter(uid -> yusa().getJugadoresVivos().contains(uid))
                .collect(Collectors.toList());
        uidTurnoActual = yusa().siguienteTurno(uidTurnoActual, vivos);

        actualizarInterfaz();
        logEstadoYusa("Fin de ronda");
        PauseTransition p = new PauseTransition(Duration.seconds(1));
        p.setOnFinished(ev -> iniciarRondaLocal());
        p.play();
    }

    // =========================================================================
    //  UI — BOTONES
    // =========================================================================
    private void mostrarBotonesDecision(String op1, String op2,
            java.util.function.Consumer<String> cb) {
        ocultarPanelDecisionLocal();
        Button b1 = new Button(op1), b2 = new Button(op2);
        String est = "-fx-font-size:14px;-fx-padding:8 18;";
        b1.setStyle(est);
        b2.setStyle(est);
        b1.setOnAction(e -> {
            ocultarPanelDecisionLocal();
            cb.accept(op1);
        });
        b2.setOnAction(e -> {
            ocultarPanelDecisionLocal();
            cb.accept(op2);
        });
        panelDecisionLocal = new javafx.scene.layout.HBox(16, b1, b2);
        panelDecisionLocal.setAlignment(javafx.geometry.Pos.CENTER);
        javafx.scene.layout.StackPane.setAlignment(panelDecisionLocal, javafx.geometry.Pos.BOTTOM_CENTER);
        panelDecisionLocal.setTranslateY(-40);
        rootSala.getChildren().add(panelDecisionLocal);
    }

    private void mostrarBotonesPalo(java.util.function.Consumer<JuegoYusa.Palo> cb) {
        ocultarPanelDecisionLocal();
        String est = "-fx-font-size:13px;-fx-padding:7 14;";

        Button bC = new Button(IdiomaManager.get("yusaOffline.privado.palo.coronas"));
        Button bV = new Button(IdiomaManager.get("yusaOffline.privado.palo.balanzas"));
        Button bD = new Button(IdiomaManager.get("yusaOffline.privado.palo.dianas"));
        Button bCz = new Button(IdiomaManager.get("yusaOffline.privado.palo.corazones"));

        bC.setStyle(est);
        bV.setStyle(est);
        bD.setStyle(est);
        bCz.setStyle(est);
        bC.setOnAction(e -> {
            ocultarPanelDecisionLocal();
            cb.accept(JuegoYusa.Palo.CORONAS);
        });
        bV.setOnAction(e -> {
            ocultarPanelDecisionLocal();
            cb.accept(JuegoYusa.Palo.BALANZAS);
        });
        bD.setOnAction(e -> {
            ocultarPanelDecisionLocal();
            cb.accept(JuegoYusa.Palo.DIANAS);
        });
        bCz.setOnAction(e -> {
            ocultarPanelDecisionLocal();
            cb.accept(JuegoYusa.Palo.CORAZONES);
        });
        panelDecisionLocal = new javafx.scene.layout.HBox(12, bC, bV, bD, bCz);
        panelDecisionLocal.setAlignment(javafx.geometry.Pos.CENTER);
        javafx.scene.layout.StackPane.setAlignment(panelDecisionLocal, javafx.geometry.Pos.BOTTOM_CENTER);
        panelDecisionLocal.setTranslateY(-40);
        rootSala.getChildren().add(panelDecisionLocal);
    }

    private void ocultarPanelDecisionLocal() {
        if (tickDoceLocal != null) {
            tickDoceLocal.stop();
            tickDoceLocal = null;
        }
        if (panelDecisionLocal != null) {
            rootSala.getChildren().remove(panelDecisionLocal);
            panelDecisionLocal = null;
        }
    }

    // =========================================================================
    //  OVERLAY ELIMINADO
    // =========================================================================
    private void mostrarMensajeEliminadoLocal() {
        if (overlayEliminado != null) {
            return;
        }

        javafx.scene.text.Font fuente = javafx.scene.text.Font.loadFont(
                getClass().getResourceAsStream("/ui/graphicResources/fonts/Minecraft.ttf"), 36);

        Label lbl = new Label(IdiomaManager.get("yusaOffline.privado.eliminado.titulo"));
        if (fuente != null) {
            lbl.setFont(fuente);
        }
        lbl.setStyle("-fx-font-size:36px;-fx-text-fill:#ff2222;-fx-font-weight:bold;"
                + "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.95),14,0.7,0,0);");

        Label sub = new Label(IdiomaManager.get("yusaOffline.privado.eliminado.sub"));
        if (fuente != null) {
            sub.setFont(
                    javafx.scene.text.Font.loadFont(getClass().getResourceAsStream("/ui/graphicResources/fonts/Minecraft.ttf"), 16));
        }
        sub.setStyle("-fx-font-size:16px;-fx-text-fill:#ffaaaa;");

        VBox contenido = new VBox(12, lbl, sub);
        contenido.setAlignment(javafx.geometry.Pos.CENTER);
        contenido.setStyle("-fx-background-color:transparent;");

        overlayEliminado = new StackPane(contenido);
        overlayEliminado.setStyle("-fx-background-color:transparent;");
        overlayEliminado.setPickOnBounds(false);
        overlayEliminado.prefWidthProperty().bind(overlayFinal.widthProperty());
        overlayEliminado.prefHeightProperty().bind(overlayFinal.heightProperty());
        overlayFinal.getChildren().add(0, overlayEliminado);
        overlayFinal.setVisible(true);

        javafx.animation.ScaleTransition pulso
                = new javafx.animation.ScaleTransition(Duration.seconds(1.0), contenido);
        pulso.setFromX(1.0);
        pulso.setFromY(1.0);
        pulso.setToX(1.10);
        pulso.setToY(1.10);
        pulso.setAutoReverse(true);
        pulso.setCycleCount(javafx.animation.Animation.INDEFINITE);
        pulso.play();
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================
    private boolean esIA(String uid) {
        return uid != null && uid.startsWith(PREFIJO_IA);
    }

    public void reiniciarPartida() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/ui/partidaOffline.fxml"));

            PartidaOfflineYusaController nuevoCtrl = new PartidaOfflineYusaController();
            loader.setController(nuevoCtrl);

            Parent root = loader.load();
            nuevoCtrl.iniciarOffline(numIAs);

            Stage stage = (Stage) overlayFinal.getScene().getWindow();
            stage.setScene(new Scene(root));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void volverAlMenu() {
        MainApp.cambiarEscena("menuOffline.fxml", 800, 600);
    }

    // ── Mostrar botón debug ───────────────────────────────────────────────────
    private void programarAccionIA(String nombreAccion, Runnable accion) {
        if (!MODO_DEBUG_IA) {
            PauseTransition p = new PauseTransition(Duration.seconds(DELAY_IA_SEG));
            p.setOnFinished(ev -> accion.run());
            p.play();
            return;
        }
        // Modo debug: esperar a que el humano pulse el botón
        accionIAPendiente = accion;
        ocultarBtnIADebug();

        btnIADebug = new Button("▶ " + nombreAccion);
        btnIADebug.setStyle(
                "-fx-font-size:14px;-fx-padding:10 22;"
                + "-fx-background-color:#1565c0;-fx-text-fill:white;"
                + "-fx-background-radius:8;-fx-cursor:hand;");
        StackPane.setAlignment(btnIADebug, javafx.geometry.Pos.BOTTOM_RIGHT);
        btnIADebug.setTranslateX(-40);
        btnIADebug.setTranslateY(-40);
        btnIADebug.setOnAction(e -> {
            ocultarBtnIADebug();
            if (accionIAPendiente != null) {
                accionIAPendiente.run();
                accionIAPendiente = null;
            }
        });
        rootSala.getChildren().add(btnIADebug);
    }

    private void ocultarBtnIADebug() {
        if (btnIADebug != null) {
            rootSala.getChildren().remove(btnIADebug);
            btnIADebug = null;
        }
    }

    private void logEstadoYusa(String evento) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n╔══════════════════════════════════════════════════════╗\n");
        sb.append("║  EVENTO : ").append(evento).append("\n");
        sb.append("║  FASE   : ").append(faseRondaActual).append("\n");
        sb.append("║  TURNO  : ").append(nombres.getOrDefault(uidTurnoActual, uidTurnoActual)).append("\n");
        sb.append("╠══════════════════════════════════════════════════════╣\n");

        for (String uid : ordenJugadoresGlobal) {
            List<String> mano = manos.get(uid);
            String nombre = nombres.getOrDefault(uid, uid);
            int vidas = yusa().getVidas(uid);
            boolean vivo = yusa().getJugadoresVivos().contains(uid);
            String turnoMarca = uid.equals(uidTurnoActual) ? " ◄" : "";
            String estadoVida = vivo ? ("❤".repeat(vidas)) : "💀 ELIMINADO";

            String cartaStr;
            if (mano == null || mano.isEmpty()) {
                cartaStr = "[sin carta]";
            } else {
                String ruta = mano.get(0);
                int num = juego.obtenerNumeroCarta(ruta);
                JuegoYusa.Palo palo = yusa().obtenerPaloCarta(ruta);
                cartaStr = num + " de " + palo.name();
                if (num == JuegoYusa.NUMERO_YUSA) {
                    cartaStr += " 🃏 YUSA";
                }
                if (num == JuegoYusa.NUMERO_DOCE) {
                    cartaStr += " ⚡ DOCE";
                }
            }

            sb.append(String.format("║  %-10s%s  %s  carta: %s\n",
                    nombre, turnoMarca, estadoVida, cartaStr));
        }

        sb.append("╠══════════════════════════════════════════════════════╣\n");
        sb.append(String.format("║  Baraja: %-3d  Descarte: %-3d\n", baraja.size(), descarte.size()));
        sb.append("╚══════════════════════════════════════════════════════╝");
        System.out.println(sb.toString());
    }

    @Override
    protected void mostrarPantallaFinal() {
        DatosPopUp datos = construirDatosPopUpFinal();

        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/ui/popUpFinalPartida.fxml"));
            StackPane popUpPane = loader.load();

            PopUpFinalPartidaController popUpCtrl = loader.getController();

            popUpCtrl.initOffline(
                    datos.resultado,
                    datos.detalle,
                    datos.icono,
                    this // <-- controlador offline actual
            );

            overlayFinal.getChildren().clear();
            overlayFinal.getChildren().add(popUpPane);
            overlayFinal.setVisible(true);

        } catch (IOException e) {
            e.printStackTrace();
            Label fallback = new Label(
                    IdiomaManager.get("yusaOffline.global.final.fallback")
            );
            fallback.setStyle("-fx-text-fill:white;-fx-font-size:24px;");
            overlayFinal.getChildren().clear();
            overlayFinal.getChildren().add(fallback);
            overlayFinal.setVisible(true);
        }
    }

    @Override
    public void reiniciarPartidaOffline() {
        reiniciarPartida();
    }

    @Override
    public void volverAlMenuOffline() {
        volverAlMenu();
    }

}
