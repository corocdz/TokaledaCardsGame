package ui;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import partidaUTIL.Baraja;
import partidaUTIL.Carta;
import partidaUTIL.Juego;
import partidaUTIL.JuegoPescaito;

/**
 * Modo Pescaito OFFLINE — extiende PartidaControllerBase para reutilizar
 * exactamente los mismos abanicos, layout y render visual del modo online.
 *
 * Lo que sobreescribimos: - init() → versión offline que no toca Firebase -
 * prepararEstadoInicial() → repartir en local - registrarListenersComunes() →
 * no hay Firebase, no hay listeners - registrarListenersPropios() → vacío -
 * finalizarPartida() → sin escribir en Firebase - crearJuego(),
 * onCambioTurno(), onClickMazo(), onZonaRivalClick(), onCartaLocalClick(),
 * construirDatosPopUpFinal()
 *
 * Punto de entrada: iniciarOffline(numIAs) tras loader.load()
 */
public class PartidaOfflinePescaitoController extends PartidaControllerBase {

    // ─── Constantes ───────────────────────────────────────────────────────────
    private static final String UID_JUGADOR = "jugador";
    private static final String PREFIJO_IA = "ia_";
    private static final double DELAY_IA_SEG = 2.0; // 5 

    // ─── Estado offline ────────────────────────────────────────────────────────
    private final Map<String, Integer> pescaitosPorJugador = new HashMap<>();
    private int numIAs = 1;

    private boolean esperandoRobo = false;
    private boolean uiBloqueada = false;
    private Integer numeroSeleccionado = null;
    private Integer numeroPreguntadoAntesDeRobar = null;
    private String uidJugadorObjetivo = null;

    /**
     * PRUEBAS
     */
    // Campo — añade junto a los otros campos
    private Button btnIAManual = null;
    private static final boolean MODO_DEBUG_IA = false; // ← cambia a false para volver al modo automático
    // ── CAMPO: guardar qué preguntó la IA antes de robar ─────────────────────
    private final Map<String, Integer> numeroPreguntadoPorIA = new HashMap<>();

    // =========================================================================
    //  PUNTO DE ENTRADA
    // =========================================================================
    /**
     * Llamar justo después de loader.load(). No usar init() heredado — usa éste
     * en su lugar.
     */
    public void iniciarOffline(int n) {
        this.numIAs = Math.max(1, Math.min(3, n));

        // ── Rellenar campos que la base necesita ─────────────────────────────
        this.uidLocal = UID_JUGADOR;
        this.codigoSala = "offline";
        this.idToken = "";

        // ── Nombres ──────────────────────────────────────────────────────────
        nombres.put(UID_JUGADOR, "Tu");
        for (int i = 1; i <= numIAs; i++) {
            nombres.put(PREFIJO_IA + i, "IA " + i);
        }

        // ── Orden global de jugadores (la base lo usa para abanicos) ─────────
        ordenJugadoresGlobal.clear();
        ordenJugadoresGlobal.add(UID_JUGADOR);
        for (int i = 1; i <= numIAs; i++) {
            ordenJugadoresGlobal.add(PREFIJO_IA + i);
        }

        // ── Pescaitos ─────────────────────────────────────────────────────────
        for (String uid : ordenJugadoresGlobal) {
            pescaitosPorJugador.put(uid, 0);
        }

        // ── Baraja ────────────────────────────────────────────────────────────
        Baraja b = new Baraja();
        b.barajar();
        for (Carta c : b.getCartasRestantes()) {
            baraja.add(c.getRutaImagen());
        }

        // ── Manos vacías ──────────────────────────────────────────────────────
        for (String uid : ordenJugadoresGlobal) {
            manos.put(uid, new ArrayList<>());
        }

        // ── Crear motor ───────────────────────────────────────────────────────
        juego = new JuegoPescaito();
        juego.repartirCartas(manos, baraja);
        repartoInicialHecho = true;

        // ── Turno inicial: el jugador humano ──────────────────────────────────
        uidTurnoActual = UID_JUGADOR;

        // ── Configurar eventos de mano y mazo (método de la base) ─────────────
        configurarEventosManoJugador();
        configurarEventosRobar();

        // ── Renderizar ────────────────────────────────────────────────────────
        // Al final de iniciarOffline(), dentro del Platform.runLater:
        Platform.runLater(() -> {
            Stage stage = (Stage) zonaAbajo.getScene().getWindow();
            if (stage != null) {
                configurarLayoutEscena(stage);
            }
            actualizarInterfaz();
            narrarGlobal("¡Partida offline de Pescaito! " + numIAs + " IA(s). ¡Comienza tu turno!");
            iniciarTurnoJugador(UID_JUGADOR);
        });
    }

    // =========================================================================
    //  SOBREESCRITURAS DE LA BASE — desactivar Firebase
    // =========================================================================
    @Override
    public void init(String codigoSala, String uidLocal, String idToken) {
        // No usar — usar iniciarOffline() en su lugar
    }

    @Override
    protected void prepararEstadoInicial(Map<String, Object> partida) {
        // No se usa en offline
    }

    protected void registrarListenersComunes() {
        // Sin Firebase, sin listeners
    }

    @Override
    protected void registrarListenersPropios() {
        // Sin listeners propios
    }

    @Override
    protected void cargarOrdenJugadoresGlobal() {
        // Ya se cargó en iniciarOffline()
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

    // =========================================================================
    //  HOOKS ABSTRACTOS
    // =========================================================================
    @Override
    protected Juego crearJuego(String modo) {
        return new JuegoPescaito();
    }

    @Override
    protected void onCambioTurno(String nuevoTurno) {
        // En offline gestionamos los turnos nosotros, no por Firebase
    }

    @Override
    protected void onClickMazo() {
        if (!UID_JUGADOR.equals(uidTurnoActual)) {
            narrarPrivado(UID_JUGADOR, "No es tu turno.");
            return;
        }
        if (!esperandoRobo) {
            narrarPrivado(UID_JUGADOR, "No puedes robar ahora.");
            return;
        }
        realizarRoboManual();
    }

    @Override
    protected void onZonaRivalClick(String uidRival) {
        if (!UID_JUGADOR.equals(uidTurnoActual)) {
            narrarPrivado(UID_JUGADOR, "No es tu turno.");
            return;
        }
        if (uiBloqueada) {
            narrarPrivado(UID_JUGADOR, "Espera.");
            return;
        }
        if (esperandoRobo) {
            narrarPrivado(UID_JUGADOR, "Debes robar antes de continuar.");
            return;
        }
        if (numeroSeleccionado == null) {
            narrarPrivado(UID_JUGADOR, "Primero selecciona un número de tu mano.");
            return;
        }
        if (uidRival == null || uidRival.equals(UID_JUGADOR)) {
            return;
        }

        List<String> manoRival = manos.get(uidRival);
        if (manoRival == null || manoRival.isEmpty()) {
            boolean hayOtros = ordenJugadoresGlobal.stream()
                    .filter(u -> !u.equals(UID_JUGADOR))
                    .anyMatch(u -> {
                        List<String> m = manos.get(u);
                        return m != null && !m.isEmpty();
                    });
            if (!hayOtros && !baraja.isEmpty()) {
                narrarPrivado(UID_JUGADOR, "No hay rivales con cartas. Pasas el turno.");
                pasarTurno(UID_JUGADOR);
            } else {
                narrarPrivado(UID_JUGADOR, "Ese jugador no tiene cartas. Elige otro.");
            }
            return;
        }

        uidJugadorObjetivo = uidRival;
        ejecutarPreguntaHumano();
    }

    @Override
    protected void onCartaLocalClick(String rutaCarta) {
        if (!UID_JUGADOR.equals(uidTurnoActual)) {
            narrarPrivado(UID_JUGADOR, "No es tu turno.");
            return;
        }
        if (esperandoRobo) {
            narrarPrivado(UID_JUGADOR, "Debes robar antes de continuar.");
            return;
        }
        int numero = juego.obtenerNumeroCarta(rutaCarta);
        numeroSeleccionado = numero;
        narrarPrivado(UID_JUGADOR, "Seleccionaste el " + numero + ". Ahora elige un jugador.");
    }

    // =========================================================================
    //  TURNO HUMANO
    // =========================================================================
    private void ejecutarPreguntaHumano() {
        if (numeroSeleccionado == null || uidJugadorObjetivo == null) {
            return;
        }
        uiBloqueada = true;

        int numPreguntado = numeroSeleccionado;  // guardar antes de limpiar
        String objPreguntado = uidJugadorObjetivo;

        narrarPrivado(UID_JUGADOR, "Preguntas a " + nombres.get(objPreguntado)
                + " por el " + numPreguntado + ".");

        Map<String, Object> res = pescaito().preguntar(
                UID_JUGADOR, objPreguntado, numPreguntado, manos, baraja, descarte);

        // Limpiar SIEMPRE tras preguntar, independientemente del resultado
        numeroSeleccionado = null;
        uidJugadorObjetivo = null;
        uiBloqueada = false;

        procesarResultado(UID_JUGADOR, objPreguntado, res, numPreguntado);
        if (UID_JUGADOR.equals(uidTurnoActual) && !esperandoRobo) {
            activarInteraccion();
        }
    }

    private void realizarRoboManual() {
        if (!esperandoRobo) {
            return;
        }
        uiBloqueada = true;
        desactivarInteraccion();

        pescaito().robarCarta(UID_JUGADOR, manos, baraja);
        List<String> mano = manos.get(UID_JUGADOR);
        String cartaRobada = mano.get(mano.size() - 1);
        int numRobado = juego.obtenerNumeroCarta(cartaRobada);
        

        boolean haPescado = pescaito().haPescadoAlRobar(cartaRobada, numeroPreguntadoAntesDeRobar);
        boolean pescaito = pescaito().esPescaitoPorRobo(UID_JUGADOR, manos, descarte);
        if (pescaito) {
            pescaitosPorJugador.merge(UID_JUGADOR, 1, Integer::sum);
            narrarGlobal("¡PESCAITO de " + nombres.get(UID_JUGADOR) + "! Total: "
                    + pescaitosPorJugador.get(UID_JUGADOR));
        }

        esperandoRobo = false;
        numeroPreguntadoAntesDeRobar = null;
        numeroSeleccionado = null;
        uiBloqueada = false;
        actualizarInterfaz();
        logEstadoPartida("Robo manual: " + nombres.getOrDefault(UID_JUGADOR, "Tú"));

        if (verificarFin()) {
            return;
        }

        if (haPescado) {
            narrarGlobal("Tú pescaste al robar el " + numRobado
                    + " — ¡Es el número que buscabas! Mantienes el turno.");
            PauseTransition delay = new PauseTransition(Duration.seconds(2));
            delay.setOnFinished(ev -> iniciarTurnoJugador(UID_JUGADOR));
            delay.play();
        } else {
            narrarGlobal("Tú robas el " + numRobado + " — No es el que buscabas. Pasas el turno.");
            PauseTransition delay = new PauseTransition(Duration.seconds(2));
            delay.setOnFinished(ev -> pasarTurno(UID_JUGADOR));
            delay.play();
        }
    }

    // =========================================================================
    //  PROCESADO DE RESULTADO (compartido humano e IA)
    // =========================================================================
    private void procesarResultado(String preguntador, String objetivo,
            Map<String, Object> res, int numeroPreguntado) {

        boolean acierto = (boolean) res.get("acierto");
        boolean pescaitoFlag = (boolean) res.get("pescaito");
        boolean debeRobar = (boolean) res.get("debeRobar");
        boolean mantieneTurno = (boolean) res.get("mantieneTurno");
        int cartasRec = (int) res.get("cartasRecibidas");

        String nomPreg = nombres.getOrDefault(preguntador, preguntador);
        String nomObj = nombres.getOrDefault(objetivo, objetivo);

        if (acierto) {
            narrarGlobal(nomPreg + " preguntó a " + nomObj + " por el "
                    + numeroPreguntado + " → ¡Lo tenía! Roba "
                    + cartasRec + " carta(s).");
            if (pescaitoFlag) {
                int num = (int) res.get("numeroPescaito");
                pescaitosPorJugador.merge(preguntador, 1, Integer::sum);
                narrarGlobal("¡PESCAITO de " + nomPreg + "! (Nº" + num
                        + ") — Total: " + pescaitosPorJugador.get(preguntador));
            }

            actualizarInterfaz();
            logEstadoPartida("Pregunta: " + nomPreg + " → " + nomObj + " | Acierto: true");
            if (verificarFin()) {
                return;
            }

            // Delay de 2 segundos para que el mensaje sea legible antes de continuar
            final String preguntadorFinal = preguntador;
            PauseTransition delay = new PauseTransition(Duration.seconds(2));
            delay.setOnFinished(ev -> {
                if (mantieneTurno) {
                    narrarGlobal("Turno de: " + nomPreg + " (mantiene turno).");
                    iniciarTurnoJugador(preguntadorFinal);
                } else {
                    pasarTurno(preguntadorFinal);
                }
            });
            delay.play();
            return; // salir aquí — el delay gestiona el resto

        } else {
            narrarGlobal(nomPreg + " preguntó a " + nomObj + " por el "
                    + numeroPreguntado + " → No lo tenía. Debe robar.");
        }

        actualizarInterfaz();
        logEstadoPartida("Pregunta: " + nombres.getOrDefault(preguntador, preguntador)
                + " → " + nombres.getOrDefault(objetivo, objetivo)
                + " | Acierto: " + acierto);
        if (verificarFin()) {
            return;
        }

        if (debeRobar) {
            if (esIA(preguntador)) {
                // Guardar qué número preguntó la IA para comparar al robar
                // .put(preguntador, (int) res.getOrDefault("numeroPreguntado", -1));
                // Pero res no tiene numeroPreguntado — hay que guardarlo antes de llamar a preguntar
                // Ver ejecutarTurnoIA() más abajo
                PauseTransition p = new PauseTransition(Duration.seconds(1));
                p.setOnFinished(ev -> ejecutarRoboIA(preguntador));
                p.play();
            } else {
                if (baraja.isEmpty()) {
                    narrarGlobal("No quedan cartas. " + nomPreg + " pasa turno.");
                    pasarTurno(preguntador);
                    return;
                }
                // Delay de 2 segundos antes de activar el robo
                final int numParaRobar = numeroPreguntado; // ← capturar ANTES del delay
                PauseTransition delay = new PauseTransition(Duration.seconds(1));
                delay.setOnFinished(ev -> {
                    numeroPreguntadoAntesDeRobar = numParaRobar; // ← asignar aquí
                    esperandoRobo = true;
                    desactivarInteraccion();
                    imgMazo.setDisable(false);
                    imgMazo.setOpacity(1.0);
                });
                delay.play();
            }
            return;
        }

        if (mantieneTurno) {
            narrarGlobal("Turno de: " + nomPreg + " (mantiene turno).");
            iniciarTurnoJugador(preguntador);  // ← todos pasan por aquí
        } else {
            pasarTurno(preguntador);
        }
    }

    // =========================================================================
    //  TURNO IA
    // =========================================================================
    /**
     * private void programarTurnoIA() { if (!esIA(uidTurnoActual)) { return; }
     * desactivarInteraccion(); PauseTransition p = new
     * PauseTransition(Duration.seconds(DELAY_IA_SEG)); p.setOnFinished(ev ->
     * ejecutarTurnoIA(uidTurnoActual)); p.play(); }
     */
    private void programarTurnoIA() {
        if (!esIA(uidTurnoActual)) {
            return;
        }
        desactivarInteraccion();

        if (MODO_DEBUG_IA) {
            // Modo debug: mostrar botón y esperar a que el humano lo pulse
            mostrarBotonIAManual();
        } else {
            // Modo normal: esperar 2 segundos y actuar automáticamente
            PauseTransition p = new PauseTransition(Duration.seconds(DELAY_IA_SEG));
            p.setOnFinished(ev -> ejecutarTurnoIA(uidTurnoActual));
            p.play();
        }
    }

    private void mostrarBotonIAManual() {
        // Quitar el anterior si existía
        if (btnIAManual != null) {
            rootSala.getChildren().remove(btnIAManual);
            btnIAManual = null;
        }

        String nombreIA = nombres.getOrDefault(uidTurnoActual, uidTurnoActual);
        btnIAManual = new Button("▶  " + nombreIA + " pregunta");
        btnIAManual.setStyle(
                "-fx-font-size:16px;"
                + "-fx-padding:12 28;"
                + "-fx-background-color:#1565c0;"
                + "-fx-text-fill:white;"
                + "-fx-background-radius:8;"
                + "-fx-cursor:hand;");

        // Posicionar en la parte inferior derecha
        StackPane.setAlignment(btnIAManual, javafx.geometry.Pos.BOTTOM_RIGHT);
        btnIAManual.setTranslateX(-40);
        btnIAManual.setTranslateY(-40);

        final String uidIAActual = uidTurnoActual; // capturar antes del lambda
        btnIAManual.setOnAction(e -> {
            ocultarBotonIAManual();
            ejecutarTurnoIA(uidIAActual);
        });

        rootSala.getChildren().add(btnIAManual);
    }

    private void ocultarBotonIAManual() {
        if (btnIAManual != null) {
            rootSala.getChildren().remove(btnIAManual);
            btnIAManual = null;
        }
    }

    private void ejecutarTurnoIA(String uidIA) {
        ocultarBotonIAManual();
        if (partidaFinalizada) {
            return;
        }
        if (!uidIA.equals(uidTurnoActual)) {
            return;
        }

        /**
         * List<String> manoIA = manos.get(uidIA); // NOTA: si llega aquí,
         * siempre tiene carta porque iniciarTurnoJugador ya robó if (manoIA ==
         * null || manoIA.isEmpty()) { pasarTurno(uidIA); return; }
         */
        List<String> manoIA = manos.get(uidIA);
        // Si no tiene carta aquí es un estado inválido — no debería ocurrir
        if (manoIA == null || manoIA.isEmpty()) {
            System.out.println("WARN: ejecutarTurnoIA llamado sin carta para " + uidIA);
            return; // no hacer nada, iniciarTurnoJugador lo resolverá
        }

        // Elegir número aleatorio de su mano
        Set<Integer> nums = new HashSet<>();
        for (String c : manoIA) {
            nums.add(juego.obtenerNumeroCarta(c));
        }
        List<Integer> listaNum = new ArrayList<>(nums);
        int numPreguntado = listaNum.get(new Random().nextInt(listaNum.size()));
        numeroPreguntadoPorIA.put(uidIA, numPreguntado);

        List<String> rivalesConCartas = ordenJugadoresGlobal.stream()
                .filter(u -> !u.equals(uidIA))
                .filter(u -> {
                    List<String> m = manos.get(u);
                    return m != null && !m.isEmpty();
                })
                .collect(Collectors.toList());

        if (rivalesConCartas.isEmpty()) {
            // No hay rivales con cartas pero hay baraja → igual pasa turno
            // (no puede preguntar a nadie)
            narrarGlobal(nombres.get(uidIA) + " no tiene a quien preguntar. Pasa turno.");
            logEstadoPartida("IA sin rivales: " + nombres.get(uidIA) + " pasa turno");
            pasarTurno(uidIA);
            return;
        }

        String objetivo = rivalesConCartas.get(new Random().nextInt(rivalesConCartas.size()));
        narrarGlobal(nombres.get(uidIA) + " pregunta a " + nombres.get(objetivo) + " por el " + numPreguntado + ".");

        Map<String, Object> res = pescaito().preguntar(uidIA, objetivo, numPreguntado, manos, baraja, descarte);
        boolean tenia = (boolean) res.get("acierto");
        logEstadoPartida("IA pregunta: " + nombres.get(uidIA) + " → " + nombres.get(objetivo)
                + " por el " + numPreguntado + " | " + (tenia ? "¡LO TENÍA!" : "No lo tenía"));
        procesarResultado(uidIA, objetivo, res, numPreguntado);
    }

    private void ejecutarRoboIA(String uidIA) {
        if (baraja.isEmpty()) {
            narrarGlobal(nombres.get(uidIA) + " no puede robar. Pasa turno.");
            logEstadoPartida("IA sin baraja: " + nombres.get(uidIA) + " pasa turno");
            pasarTurno(uidIA);
            return;
        }
        pescaito().robarCarta(uidIA, manos, baraja);
        List<String> mano = manos.get(uidIA);
        String cartaRobada = mano.get(mano.size() - 1);
        int numRobado = juego.obtenerNumeroCarta(cartaRobada);

        // Usar el número que realmente preguntó (no numRobado)
        Integer numQuePreguntoAntes = numeroPreguntadoPorIA.getOrDefault(uidIA, -1);
        numeroPreguntadoPorIA.remove(uidIA); // limpiar

        narrarGlobal(nombres.get(uidIA) + " roba un " + numRobado + ".");

        boolean haPescado = pescaito().haPescadoAlRobar(cartaRobada, numQuePreguntoAntes);
        boolean pesc = pescaito().esPescaitoPorRobo(uidIA, manos, descarte);

        if (pesc) {
            pescaitosPorJugador.merge(uidIA, 1, Integer::sum);
            narrarGlobal("¡PESCAITO de " + nombres.get(uidIA) + "! Total: "
                    + pescaitosPorJugador.get(uidIA));
        }

        actualizarInterfaz();
        logEstadoPartida("Robo IA: " + nombres.get(uidIA)
                + " robó el " + numRobado
                + " | Preguntó el " + numQuePreguntoAntes
                + " | Pesca: " + haPescado);

        if (verificarFin()) {
            return;
        }

        if (haPescado) {
            narrarGlobal(nombres.get(uidIA) + " pescó. Mantiene turno.");
            iniciarTurnoJugador(uidIA);  // ← en lugar de programarTurnoIA()
        } else {
            pasarTurno(uidIA);
        }
    }

    // =========================================================================
    //  GESTIÓN DE TURNOS
    // =========================================================================
    private void pasarTurno(String actual) {
        // Siguiente en orden circular
        int idx = ordenJugadoresGlobal.indexOf(actual);
        String siguiente = ordenJugadoresGlobal.get((idx + 1) % ordenJugadoresGlobal.size());
        uidTurnoActual = siguiente;
        narrarGlobal("Turno de: " + nombres.getOrDefault(siguiente, siguiente));
        actualizarInterfaz();
        if (verificarFin()) {
            return;
        }

        iniciarTurnoJugador(siguiente);
    }

    /**
     * Punto de entrada al turno de cualquier jugador. Comprueba si tiene cartas
     * o debe robar antes de jugar.
     */
    private void iniciarTurnoJugador(String uid) {
        List<String> mano = manos.get(uid);
        boolean tieneCarta = mano != null && !mano.isEmpty();

        if (!tieneCarta) {
            if (!baraja.isEmpty()) {
                // Sin carta pero hay baraja → robar automáticamente
                pescaito().robarCarta(uid, manos, baraja);
                String cartaRobada = manos.get(uid).get(manos.get(uid).size() - 1);
                int numRobado = juego.obtenerNumeroCarta(cartaRobada);

                // Comprobar pescaito por robo automático
                boolean pescaito = pescaito().esPescaitoPorRobo(uid, manos, descarte);
                if (pescaito) {
                    pescaitosPorJugador.merge(uid, 1, Integer::sum);
                    narrarGlobal("¡PESCAITO de " + nombres.getOrDefault(uid, uid)
                            + "! Total: " + pescaitosPorJugador.get(uid));
                }

                narrarGlobal(nombres.getOrDefault(uid, uid)
                        + " no tenía cartas. Roba un " + numRobado + " automáticamente.");
                actualizarInterfaz();
                logEstadoPartida("Robo automático: " + nombres.getOrDefault(uid, uid) + " → " + numRobado);

                if (verificarFin()) {
                    return;
                }

                // Si después del robo sigue sin carta (hizo pescaito), volver a comprobar
                List<String> manoTrasRobo = manos.get(uid);
                if (manoTrasRobo == null || manoTrasRobo.isEmpty()) {
                    // Hizo pescaito con el robo automático → volver a iniciarTurnoJugador
                    iniciarTurnoJugador(uid);
                    return;
                }
            } else {
                // Sin carta y sin baraja → pasa turno
                narrarGlobal(nombres.getOrDefault(uid, uid)
                        + " no tiene cartas ni hay baraja. Pasa turno.");
                pasarTurno(uid);
                return;
            }
        }

        // Caso 4: solo este jugador tiene cartas pero nadie tiene cartas que preguntarle
        // (todos los rivales tienen mano vacía y no hay baraja para que rellenen)
        boolean hayRivalesConCartas = ordenJugadoresGlobal.stream()
                .filter(u -> !u.equals(uid))
                .anyMatch(u -> {
                    List<String> m = manos.get(u);
                    return m != null && !m.isEmpty();
                });

        if (!hayRivalesConCartas && baraja.isEmpty()) {
            narrarGlobal(nombres.getOrDefault(uid, uid)
                    + " es el único con cartas y no hay baraja. Pasa turno.");
            pasarTurno(uid);
            return;
        }

        // Tiene carta y puede jugar → activar su turno
        uidTurnoActual = uid; // asegurar que el turno es de este jugador
        if (esIA(uid)) {
            final String uidCapturado = uid;
            desactivarInteraccion();
            if (MODO_DEBUG_IA) {
                // Reusar mostrarBotonIAManual pero apuntando a uid concreto
                if (btnIAManual != null) {
                    rootSala.getChildren().remove(btnIAManual);
                }
                btnIAManual = new Button("▶  " + nombres.getOrDefault(uid, uid) + " pregunta");
                btnIAManual.setStyle("-fx-font-size:16px;-fx-padding:12 28;"
                        + "-fx-background-color:#1565c0;-fx-text-fill:white;"
                        + "-fx-background-radius:8;-fx-cursor:hand;");
                StackPane.setAlignment(btnIAManual, javafx.geometry.Pos.BOTTOM_RIGHT);
                btnIAManual.setTranslateX(-40);
                btnIAManual.setTranslateY(-40);
                btnIAManual.setOnAction(e -> {
                    ocultarBotonIAManual();
                    ejecutarTurnoIA(uidCapturado); // uid capturado, no uidTurnoActual
                });
                rootSala.getChildren().add(btnIAManual);
            } else {
                PauseTransition p = new PauseTransition(Duration.seconds(DELAY_IA_SEG));
                p.setOnFinished(ev -> ejecutarTurnoIA(uidCapturado));
                p.play();
            }
        } else {
            activarInteraccion();
        }
    }

    private String siguienteConCartas(String actual) {
        int idx = ordenJugadoresGlobal.indexOf(actual);
        for (int i = 1; i <= ordenJugadoresGlobal.size(); i++) {
            String c = ordenJugadoresGlobal.get((idx + i) % ordenJugadoresGlobal.size());
            List<String> m = manos.get(c);
            if (m != null && !m.isEmpty()) {
                return c;
            }
        }
        return ordenJugadoresGlobal.get((idx + 1) % ordenJugadoresGlobal.size());
    }

    private boolean esIA(String uid) {
        return uid != null && uid.startsWith(PREFIJO_IA);
    }

    private boolean verificarFin() {
        if (!juego.haTerminado(manos, baraja, descarte)) {
            return false;
        }
        finalizarPartida();
        return true;
    }

    private JuegoPescaito pescaito() {
        return (JuegoPescaito) juego;
    }

    // =========================================================================
    //  UI — delegar en la base
    // =========================================================================
    private void activarInteraccion() {
        zonaArriba.setDisable(false);
        zonaArriba.setOpacity(1.0);
        zonaIzquierda.setDisable(false);
        zonaIzquierda.setOpacity(1.0);
        zonaDerecha.setDisable(false);
        zonaDerecha.setOpacity(1.0);
        zonaAbajo.setDisable(false);
        zonaAbajo.setOpacity(1.0);
    }

    private void desactivarInteraccion() {
        zonaArriba.setDisable(true);
        zonaArriba.setOpacity(0.5);
        zonaIzquierda.setDisable(true);
        zonaIzquierda.setOpacity(0.5);
        zonaDerecha.setDisable(true);
        zonaDerecha.setOpacity(0.5);
        zonaAbajo.setDisable(true);
        zonaAbajo.setOpacity(0.5);
    }

    // =========================================================================
    //  POPUP FINAL — offline sin Firebase
    // =========================================================================
    public void reiniciarPartida() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/ui/partidaOffline.fxml"));

            PartidaOfflinePescaitoController nuevoCtrl = new PartidaOfflinePescaitoController();
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

    // Añade estos dos métodos en PartidaOfflinePescaitoController:
    @Override
    protected void narrarGlobal(String texto) {
        // En offline: mostrar directamente en el label sin Firebase
        Platform.runLater(() -> narradorLabel.setText(texto));
    }

    @Override
    protected void narrarPrivado(String uidDestino, String texto) {
        // En offline: solo el jugador local ve mensajes privados
        if (UID_JUGADOR.equals(uidDestino)) {
            Platform.runLater(() -> encolarMensajePrivado(texto));
        }
        // Los mensajes privados a las IAs se ignoran (no tienen pantalla)
    }

    @Override
    protected void cargarNombresJugadores() {
        // En offline los nombres ya están en el mapa, no leer Firebase
        if (lblAbajo != null) {
            lblAbajo.setText(nombres.getOrDefault(uidLocal, "Tú"));
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

    /**
     * Imprime en consola el estado completo de la partida para monitorización.
     * Llamar tras cada robo, pregunta o cambio de turno.
     */
    private void logEstadoPartida(String evento) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n╔══════════════════════════════════════════════════════╗\n");
        sb.append("║  EVENTO : ").append(evento).append("\n");
        sb.append("║  TURNO  : ")
                .append(nombres.getOrDefault(uidTurnoActual, uidTurnoActual)).append("\n");
        sb.append("╠══════════════════════════════════════════════════════╣\n");

        for (String uid : ordenJugadoresGlobal) {
            List<String> mano = manos.get(uid);
            String nombre = nombres.getOrDefault(uid, uid);
            int numCartas = mano == null ? 0 : mano.size();
            int pesc = pescaitosPorJugador.getOrDefault(uid, 0);
            String turnoMarca = uid.equals(uidTurnoActual) ? " ◄" : "";

            // Números de las cartas en mano
            String cartasStr;
            if (mano == null || mano.isEmpty()) {
                cartasStr = "[vacía]";
            } else {
                cartasStr = mano.stream()
                        .map(c -> String.valueOf(juego.obtenerNumeroCarta(c)))
                        .sorted()
                        .collect(Collectors.joining(", ", "[", "]"));
            }

            sb.append(String.format("║  %-10s%s  cartas(%d): %-30s  🐟%d\n",
                    nombre, turnoMarca, numCartas, cartasStr, pesc));
        }

        sb.append("╠══════════════════════════════════════════════════════╣\n");
        sb.append(String.format("║  Baraja: %-3d  Descarte: %-3d\n",
                baraja.size(), descarte.size()));
        sb.append("╚══════════════════════════════════════════════════════╝");
        System.out.println(sb.toString());
    }

    @Override
    protected DatosPopUp construirDatosPopUpFinal() {
        try {
            // 1. Puntuaciones ya están en memoria
            Map<String, Integer> puntuaciones = new HashMap<>(pescaitosPorJugador);

            // 2. Encontrar el máximo
            int maxPescaitos = puntuaciones.values().stream()
                    .max(Integer::compare)
                    .orElse(0);

            // 3. Lista de ganadores (puede haber empate)
            List<String> ganadores = puntuaciones.entrySet().stream()
                    .filter(e -> e.getValue() == maxPescaitos)
                    .map(Map.Entry::getKey)
                    .toList();

            String resultado;
            String detalle;
            String icono;

            // 4. Construir textos igual que en Yusa
            if (ganadores.size() == 1) {
                String uidGanador = ganadores.get(0);
                String nombre = nombres.getOrDefault(uidGanador, "Jugador");

                resultado = "¡Ha ganado " + nombre + "!";
                detalle = maxPescaitos + " pescaito" + (maxPescaitos != 1 ? "s" : "");

                icono = "/ui/graphicResources/imagenes/imgGanador.png";

            } else {
                // Empate
                resultado = "¡Empate!";
                detalle = "más de 1 jugador ha sacado " + maxPescaitos + " pecaitos.";

                icono = "/ui/graphicResources/imagenes/imgEmpate.png";
            }

            return new DatosPopUp(icono, resultado, detalle);

        } catch (Exception e) {
            e.printStackTrace();
            return new DatosPopUp(
                    "/ui/graphicResources/imagenes/imgEmpate.png",
                    "La partida ha terminado.",
                    ""
            );
        }
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
            Label fallback = new Label("La partida ha terminado.");
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
