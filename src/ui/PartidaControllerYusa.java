package ui;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.util.Duration;
import partidaUTIL.Juego;
import partidaUTIL.JuegoYusa;
import ui.audio.ButtonSound;

/**
 * PartidaControllerYusa — arquitectura de estado único.
 *
 * CANAL DIRECTOR → TODOS: partida/estadoRonda (polling 200ms) CANAL NO-DIRECTOR
 * → DIR: partida/decisionJugador (polling 200ms)
 *
 * El director es quien tiene uidTurnoActual en ese momento. Rota cada ronda.
 * Solo él escribe resultados en Firebase.
 *
 * Acciones de estadoRonda.accion: ESPERANDO_DECISION turnoUid = jugador que
 * decide ESPERANDO_DECISION_ULTIMO turnoUid = último jugador JUGAR_DOCE
 * turnoUid = quien tiene el 12 ELEGIR_OBJETIVO turnoUid = null (todos los
 * poseedores) ELEGIR_PALO turnoUid = objetivo que debe elegir REVELAR_CARTAS
 * turnoUid = null (todos ven cartas)
 */
public class PartidaControllerYusa extends PartidaControllerBase {

    // ─── Motor ───────────────────────────────────────────────────────────────
    private JuegoYusa yusa() {
        return (JuegoYusa) juego;
    }

    // ─── Estado de ronda ─────────────────────────────────────────────────────
    private volatile boolean rondaEnCurso = false;
    private JuegoYusa.FaseRonda faseRondaActual = null;
    private int numeroRondaYusa = 0;
    private final Map<String, String> snapshotCartas = new HashMap<>();
    private long ultimoEstadoTs = -1L;
    private long ultimaDecisionTs = -1L;
    private PauseTransition pausaRevelado = null;
    // Añade junto a los otros campos de estado:
    private PauseTransition tickDoce = null;
    private long tiempoFinUltimaRevelacion = 0L;
    private static final long VENTANA_PROTECCION_MS = 3000; // 3 segundos
    private String uidPoseedorYusaActual = null;
    /**
     * Lista de UIDs que participan en la ronda de desempate actual. null o
     * vacía = ronda normal (participan todos los vivos).
     */
    private List<String> empatadosActuales = null;

    /**
     * true cuando hay una ronda de desempate activa. Se usa para mostrar el
     * overlay a los espectadores.
     */
    private boolean enDesempate = false;

    /**
     * Referencia al overlay de espectador para poder quitarlo. Solo existe en
     * los clientes que no participan en el desempate.
     */
    private javafx.scene.layout.StackPane overlayEspectador = null;
    /**
     * Overlay de eliminado — se muestra cuando el jugador pierde todas las
     * vidas
     */
    private javafx.scene.layout.StackPane overlayEliminado = null;

    // Orden de decisiones en ronda NORMAL, solo usado por el director
    private final List<String> ordenRondaActual = new ArrayList<>();

    // ─── Estado ronda YUSA ───────────────────────────────────────────────────
    private final Map<String, String> objetivosPorPoseedor = new HashMap<>();
    private boolean soyObjetivoDeYusa = false;
    // Añade este campo junto a los otros campos de estado:
    private boolean mostrandoCartas = false;
    private static final int MAX_INTENTOS_MANOS = 10;
    private static final int DELAY_REINTENTO_MS = 400;
    private final List<String> colaYusas = new ArrayList<>();
    /**
     * Contexto del duelo de yusa pendiente de resolver tras revelar cartas
     */
    private String yusaPendientePoseedor = null;
    private String yusaPendienteObjetivo = null;
    private JuegoYusa.Palo yusaPendientePalo = null;

    // Lista de duelos de yusa pendientes de resolver tras revelar cartas
    private final List<DueloYusa> duelosPendientesYusa = new ArrayList<>();

// Clase interna simple para guardar el contexto de un duelo
    private record DueloYusa(String poseedor, String objetivo, JuegoYusa.Palo paloElegido) {

    }

    // ─── UI ──────────────────────────────────────────────────────────────────
    private HBox panelDecision = null;

    // =========================================================================
    //  HOOKS
    // =========================================================================
    @Override
    protected Juego crearJuego(String modo) {
        if (!"Yusa".equals(modo)) {
            return null;
        }
        JuegoYusa j = new JuegoYusa();
        try {
            Map<String, Integer> v = bd.leerVidas(codigoSala, idToken);
            if (v != null && !v.isEmpty()) {
                j.cargarVidasDesdeBD(v);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return j;
    }

    @Override
    protected void prepararEstadoInicial(Map<String, Object> partida) {
        if (yusa().getJugadoresVivos().isEmpty() && !manos.isEmpty()) {
            yusa().inicializarJugadoresVivos(new ArrayList<>(manos.keySet()));
        }

        try {
            String uidHost = db.leerNodo("salas/" + codigoSala + "/host", idToken)
                    .replace("\"", "");
            if (uidLocal.equals(uidHost)) {
                juego.iniciarPartida(manos, baraja);
                bd.actualizarVidas(codigoSala, yusa().getTodasLasVidas(), idToken);
                Platform.runLater(() -> {
                    try {
                        iniciarRondaYusa();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        actualizarInterfaz();
        System.out.println("[NOMBRES] mapa: " + nombres);
        System.out.println("[NOMBRES] uidLocal: " + uidLocal);
        System.out.println("[NOMBRES] uidTurnoActual: " + uidTurnoActual);
    }

    // Sobreescribe actualizarInterfaz() para proteger la revelación:
    @Override
    protected void actualizarInterfaz() {
        if (mostrandoCartas) {
            System.out.println("[YUSA] actualizarInterfaz() bloqueada — cartas mostrándose.");
            return;
        }
        super.actualizarInterfaz();
    }

    @Override
    protected void registrarListenersPropios() {

        // ── Canal principal: estadoRonda ──────────────────────────────────────
        registrarHiloListener(bd.escucharEstadoRonda(codigoSala, idToken, estado -> Platform.runLater(() -> {
            if (estado == null) {
                return;
            }
            Object tsObj = estado.get("ts");
            if (tsObj == null) {
                return;
            }
            long ts = ((Number) tsObj).longValue();
            if (ts == ultimoEstadoTs) {
                return;
            }

            // AÑADIR: si no hay ronda en curso y la acción es REVELAR_CARTAS, ignorar
            // Evita re-procesar el estadoRonda de la ronda anterior
            String accion = estado.get("accion") != null ? estado.get("accion").toString() : "";
            if ("REVELAR_CARTAS".equals(accion)) {
                long ahora = System.currentTimeMillis();
                if (ahora - tiempoFinUltimaRevelacion < VENTANA_PROTECCION_MS) {
                    System.out.println("[YUSA] REVELAR_CARTAS ignorado — muy cerca de la última revelación ("
                            + (ahora - tiempoFinUltimaRevelacion) + "ms)");
                    ultimoEstadoTs = ts;
                    return;
                }
            }
            ultimoEstadoTs = ts;

            String fase = s(estado, "fase");
            String turnoUid = s(estado, "turnoUid");
            if ("null".equals(turnoUid)) {
                turnoUid = null;
            }

            if (fase != null) {
                try {
                    faseRondaActual = JuegoYusa.FaseRonda.valueOf(fase);
                } catch (IllegalArgumentException ignored) {
                }
            }

            procesarAccion(accion, turnoUid);
        })));

        // ── Canal de respuesta: decisionJugador (solo director) ───────────────
        registrarHiloListener(bd.escucharDecisionJugador(codigoSala, idToken, datos -> Platform.runLater(() -> {
            if (!uidLocal.equals(uidTurnoActual)) {
                return;
            }
            if (datos == null) {
                return;
            }
            Object tsObj = datos.get("ts");
            if (tsObj == null) {
                return;
            }
            long ts = ((Number) tsObj).longValue();
            if (ts == ultimaDecisionTs) {
                return;
            }
            ultimaDecisionTs = ts;

            String uid = s(datos, "uid");
            String decision = s(datos, "decision");
            if (uid == null || decision == null) {
                return;
            }

            procesarDecisionRecibida(uid, decision);
        })));

        // Escuchar cambios de vidas — detectar si fui eliminado
        registrarHiloListener(bd.escucharVidaJugador(codigoSala, uidLocal, idToken, vidas -> {
            Platform.runLater(() -> {
                if (controladorDestruido) {
                    return;
                }
                if (vidas == 0 && overlayEliminado == null && !partidaFinalizada) {
                    System.out.println("[ELIMINADO] Detectado por listener de vidas: " + uidLocal);
                    mostrarMensajeEliminado();
                }
            });
        }));

    }

    private String s(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    /**
     * Punto central: interpreta la acción y actúa en ESTE cliente
     */
    private void procesarAccion(String accion, String turnoUid) {
        if (accion == null) {
            return;
        }
        switch (accion) {

            case "ESPERANDO_DECISION" -> {
                if (uidLocal.equals(turnoUid)) {
                    narrarPrivado(uidLocal, "¿Te gusta tu carta?");
                    mostrarBotonesDecision("Me la quedo", "La cambio con el siguiente",
                            dec -> enviarDecision(dec.contains("quedo") ? "QUEDAR" : "CAMBIAR"));
                }
            }

            case "ESPERANDO_DECISION_ULTIMO" -> {
                if (uidLocal.equals(turnoUid)) {
                    narrarPrivado(uidLocal, "Eres el último. ¿Te quedas o cambias por la baraja?");
                    mostrarBotonesDecision("Me la quedo", "Cambio por la baraja",
                            dec -> enviarDecision(dec.contains("quedo") ? "QUEDAR_ULTIMO" : "ROBAR_ULTIMO"));
                }
            }

            case "JUGAR_DOCE" -> {
                if (uidLocal.equals(turnoUid)) {
                    mostrarBotonJugarDoce();
                } else {
                    narrarPrivado(uidLocal,
                            nombres.getOrDefault(turnoUid, turnoUid) + " tiene un 12. Esperando...");
                }
            }

            case "ELEGIR_OBJETIVO" -> {
                // turnoUid = poseedor que debe elegir AHORA
                if (turnoUid != null && turnoUid.equals(uidLocal) && tieneYusa(uidLocal)) {
                    narrarPrivado(uidLocal,
                            "Es tu turno de yusa. Pulsa la zona de un rival.");
                } else if (turnoUid != null && !turnoUid.equals(uidLocal)) {
                    uidPoseedorYusaActual = turnoUid;
                    narrarPrivado(uidLocal,
                            nombres.getOrDefault(turnoUid, turnoUid)
                            + " está eligiendo a quién preguntar su yusa...");
                }
            }

            case "ELEGIR_PALO" -> {
                if (uidLocal.equals(turnoUid)) {
                    soyObjetivoDeYusa = true;
                    if (panelDecision == null) {
                        narrarPrivado(uidLocal, "Te preguntan el palo. Elige.");
                        mostrarBotonesPalo();
                    }
                }
            }

            case "REVELAR_CARTAS" -> {
                if (pausaRevelado != null) {
                    pausaRevelado.stop();
                    pausaRevelado = null;
                }
                cargarManosYRevelar();
            }

            case "ESPECTADOR_DESEMPATE" -> {
                // turnoUid contiene los UIDs de los empatados separados por coma
                if (turnoUid != null) {
                    List<String> empatados = Arrays.asList(turnoUid.split(","));
                    empatadosActuales = empatados;
                    enDesempate = true;

                    if (!empatados.contains(uidLocal)) {
                        // Soy espectador: mostrar overlay bloqueante
                        mostrarOverlayEspectador(empatados);
                    }
                    // Los participantes del desempate no hacen nada aquí;
                    // recibirán las acciones normales (ESPERANDO_DECISION, etc.)
                }
            }

            case "FIN_DESEMPATE" -> {
                // La ronda de desempate terminó (hubo un perdedor)
                enDesempate = false;
                empatadosActuales = null;
                ocultarOverlayEspectador();
            }

        }
    }

    @Override
    protected void onCambioTurno(String nuevoTurno) {
        if (!uidLocal.equals(nuevoTurno)) {
            return;
        }
        if (rondaEnCurso) {
            return;
        }
        try {
            iniciarRondaYusa();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onClickMazo() {
        narrarPrivado(uidLocal, "Usa los botones.");
    }

    @Override
    protected void onZonaRivalClick(String r) {
        if (faseRondaActual == JuegoYusa.FaseRonda.YUSA) {
            onElegirObjetivoYusa(r);
        }
    }

    @Override
    protected void onCartaLocalClick(String ruta) {
        /* no se usa */ }

    @Override
    protected DatosPopUp construirDatosPopUpFinal() {
        // Leer vidas desde Firebase para que TODOS los clientes
        // vean exactamente el mismo resultado, independientemente
        // del estado local de cada uno.
        try {
            Map<String, Integer> vidasBD = bd.leerVidas(codigoSala, idToken);
            if (vidasBD != null && !vidasBD.isEmpty()) {
                // Actualizar también el estado local con los datos reales
                yusa().cargarVidasDesdeBD(vidasBD);
            }
        } catch (Exception e) {
            e.printStackTrace();
            // Si falla la lectura, usar el estado local como fallback
        }

        Map<String, Integer> vidas = yusa().getTodasLasVidas();

        // Encontrar al ganador (el único con vidas > 0)
        List<String> supervivientes = vidas.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());

        String resultado;
        String detalle;

        if (supervivientes.size() == 1) {
            String uidGanador = supervivientes.get(0);
            String nombre = nombres.getOrDefault(uidGanador, uidGanador);
            int vidasGanador = vidas.getOrDefault(uidGanador, 1);
            // Siempre el mismo mensaje para todos los clientes
            resultado = "¡Ha ganado " + nombre + "!";
            detalle = "Con " + vidasGanador + " vida"
                    + (vidasGanador != 1 ? "s" : "") + " restante"
                    + (vidasGanador != 1 ? "s" : "");
        } else if (supervivientes.isEmpty()) {
            resultado = "¡Empate!";
            detalle = "Ningún jugador sobrevivió.";
        } else {
            // Más de un superviviente (no debería ocurrir en Yusa normal)
            // Mostrar al que tiene más vidas
            String uidMasVidas = supervivientes.stream()
                    .max(java.util.Comparator.comparingInt(vidas::get))
                    .orElse(supervivientes.get(0));
            String nombre = nombres.getOrDefault(uidMasVidas, uidMasVidas);
            int vidasMax = vidas.getOrDefault(uidMasVidas, 1);
            resultado = "¡Ha ganado " + nombre + "!";
            detalle = "Con " + vidasMax + " vida"
                    + (vidasMax != 1 ? "s" : "") + " restante"
                    + (vidasMax != 1 ? "s" : "");
        }

        String icono;

        if (supervivientes.size() == 1) {
            icono = "/ui/graphicResources/imagenes/imgGanador.png";
        } else {
            icono = "/ui/graphicResources/imagenes/imgEmpate.png";
        }

        return new DatosPopUp(icono, resultado, detalle); // vacio ahi deberia ir la imagen 
    }

    // =========================================================================
    //  INICIO DE RONDA
    // =========================================================================
    private void iniciarRondaYusa() throws IOException {
        rondaEnCurso = true;
        ultimoEstadoTs = -1L;
        faseRondaActual = null;
        ordenRondaActual.clear();
        objetivosPorPoseedor.clear();
        soyObjetivoDeYusa = false;
        snapshotCartas.clear();
        ultimoEstadoTs = -1L;
        ultimaDecisionTs = -1L;
        ocultarPanelDecision();

        try {
            Map<String, Integer> v = bd.leerVidas(codigoSala, idToken);
            if (v != null) {
                yusa().cargarVidasDesdeBD(v);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        yusa().repartirCartas(manos, baraja);
        bd.publicarManosYusa(codigoSala, manos, idToken);
        bd.actualizarBaraja(codigoSala, baraja, idToken);

        faseRondaActual = yusa().determinarFaseRonda(manos);
        narrarGlobal("— Ronda nueva — " + textoFase(faseRondaActual));

        narrarGlobal(nombres.getOrDefault(uidTurnoActual, uidTurnoActual)
                + " comienza la ronda — " + textoFase(faseRondaActual));

        switch (faseRondaActual) {
            case NORMAL ->
                iniciarFaseNormal();
            case DOCE ->
                iniciarFaseDoce();
            case YUSA ->
                iniciarFaseYusa();
        }
    }

    private String textoFase(JuegoYusa.FaseRonda f) {
        return switch (f) {
            case YUSA ->
                "¡Hay Yusa!";
            case DOCE ->
                "¡Hay un 12!";
            case NORMAL ->
                "Ronda normal.";
        };
    }

    // =========================================================================
    //  RONDA NORMAL
    // =========================================================================
    private void iniciarFaseNormal() throws IOException {
        ordenRondaActual.clear();
        ordenRondaActual.addAll(calcularOrdenRonda());
        publicarSiguienteDecision();
    }

    private void publicarSiguienteDecision() throws IOException {
        if (ordenRondaActual.isEmpty()) {
            publicarRevelarCartas();
            return;
        }

        String siguiente = ordenRondaActual.get(0);
        boolean esUltimo = ordenRondaActual.size() == 1;
        String accion = esUltimo ? "ESPERANDO_DECISION_ULTIMO" : "ESPERANDO_DECISION";

        bd.publicarEstadoRonda(codigoSala, accion, faseRondaActual.name(), siguiente, idToken);

        // Si soy yo el que debe decidir, mostrar botones directamente
        if (siguiente.equals(uidLocal)) {
            if (esUltimo) {
                narrarPrivado(uidLocal, "Eres el último. ¿Te quedas o cambias por la baraja?");
                mostrarBotonesDecision("Me la quedo", "Cambio por la baraja",
                        dec -> procesarDecisionPropia(dec.contains("quedo") ? "QUEDAR_ULTIMO" : "ROBAR_ULTIMO"));
            } else {
                narrarPrivado(uidLocal, "¿Te gusta tu carta?");
                mostrarBotonesDecision("Me la quedo", "La cambio con el siguiente",
                        dec -> procesarDecisionPropia(dec.contains("quedo") ? "QUEDAR" : "CAMBIAR"));
            }
        }
    }

    private List<String> calcularOrdenRonda() {
        // En desempate, solo participan los empatados
        List<String> base = (empatadosActuales != null && !empatadosActuales.isEmpty())
                ? empatadosActuales
                : yusa().getJugadoresVivos();

        // Respetar el orden global de sala
        Set<String> baseSet = new HashSet<>(base);
        List<String> ordenados = ordenJugadoresGlobal.stream()
                .filter(baseSet::contains).collect(Collectors.toList());

        int idx = ordenados.indexOf(uidTurnoActual);
        if (idx < 0) {
            return ordenados;
        }

        List<String> r = new ArrayList<>();
        r.addAll(ordenados.subList(idx, ordenados.size()));
        r.addAll(ordenados.subList(0, idx));
        return r;
    }

    // Director toma su propia decisión directamente
    private void procesarDecisionPropia(String decision) {
        ocultarPanelDecision();
        try {
            ejecutarDecision(uidLocal, decision);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Director recibe decisión de un no-director
    private void procesarDecisionRecibida(String uid, String decision) {
        try {
            bd.limpiarDecisionJugador(codigoSala, idToken);
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (decision.startsWith("PALO_")) {
            procesarPaloRecibido(uid, decision);
            return;
        }

        try {
            ejecutarDecision(uid, decision);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void ejecutarDecision(String uid, String decision) throws IOException {
        if (decision.contains("ULTIMO")) {
            ordenRondaActual.remove(uid);
            if (decision.contains("ROBAR")) {
                ejecutarRoboUltimo(uid);
            } else {
                publicarRevelarCartas();
            }
            return;
        }
        if ("CAMBIAR".equals(decision) && ordenRondaActual.size() > 1) {
            String sig = ordenRondaActual.get(1);
            yusa().intercambiarCartas(uid, sig, manos);
            bd.publicarManosYusa(codigoSala, manos, idToken);
            narrarGlobal(nombres.getOrDefault(uid, uid) + " intercambia carta con "
                    + nombres.getOrDefault(sig, sig) + ".");
        } else if ("QUEDAR".equals(decision)) {
            narrarGlobal(nombres.getOrDefault(uid, uid) + " se queda su carta.");
        }
        ordenRondaActual.remove(0);
        publicarSiguienteDecision();
    }

    // El no-director envía su decisión al canal decisionJugador
    private void enviarDecision(String decision) {
        ocultarPanelDecision();
        try {
            bd.publicarDecisionJugador(codigoSala, uidLocal, decision, idToken);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void ejecutarRoboUltimo(String uid) throws IOException {
        if (baraja.isEmpty()) {
            narrarGlobal(nombres.getOrDefault(uid, uid) + " quiso cambiar pero la baraja está vacía.");
            publicarRevelarCartas();
            return;
        }
        List<String> mano = manos.computeIfAbsent(uid, k -> new ArrayList<>());
        if (!mano.isEmpty()) {
            descarte.add(mano.get(0));
            mano.clear();
        }
        mano.add(baraja.remove(0));

        bd.publicarManosYusa(codigoSala, manos, idToken);
        bd.actualizarBaraja(codigoSala, baraja, idToken);
        bd.actualizarDescarte(codigoSala, descarte, idToken);
        narrarGlobal(nombres.getOrDefault(uid, uid) + " cambió su carta por la baraja.");

        JuegoYusa.FaseRonda nueva = yusa().determinarFaseRonda(manos);
        if (nueva != JuegoYusa.FaseRonda.NORMAL) {
            faseRondaActual = nueva;
            narrarGlobal("¡La carta cambia la fase a " + textoFase(nueva) + "!");
            switch (nueva) {
                case DOCE ->
                    iniciarFaseDoce();
                case YUSA ->
                    iniciarFaseYusa();
                default ->
                    publicarRevelarCartas();
            }
        } else {
            publicarRevelarCartas();
        }
    }

    // =========================================================================
    //  RONDA DOCE
    // =========================================================================
    private void iniciarFaseDoce() throws IOException {
        String uidDoce = encontrarJugadorConNumero(JuegoYusa.NUMERO_DOCE);
        if (uidDoce == null) {
            PauseTransition r = new PauseTransition(Duration.millis(400));
            r.setOnFinished(ev -> {
                try {
                    iniciarFaseDoce();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            r.play();
            return;
        }
        bd.publicarEstadoRonda(codigoSala, "JUGAR_DOCE", JuegoYusa.FaseRonda.DOCE.name(), uidDoce, idToken);
        narrarGlobal(nombres.getOrDefault(uidDoce, uidDoce) + " tiene un 12. ¡Que lo lance!");
        if (uidDoce.equals(uidLocal)) {
            mostrarBotonJugarDoce();
        }
    }

    private void onDoceJugado() {
        ocultarPanelDecision();
        StringBuilder sb = new StringBuilder("Cartas al descubierto — ");
        for (String uid : yusa().getJugadoresVivos()) {
            List<String> m = manos.get(uid);
            if (m != null && !m.isEmpty()) {
                sb.append(nombres.getOrDefault(uid, uid)).append(": ")
                        .append(yusa().obtenerNumeroCarta(m.get(0))).append("  ");
            }
        }
        narrarGlobal(sb.toString());
        try {
            publicarRevelarCartas();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // =========================================================================
    //  RONDA YUSA
    // =========================================================================
    private void iniciarFaseYusa() throws IOException {
        colaYusas.clear();
        for (String uid : yusa().getJugadoresVivos()) {
            if (tieneYusa(uid)) {
                colaYusas.add(uid);
            }
        }
        Collections.shuffle(colaYusas); // orden aleatorio

        if (colaYusas.isEmpty()) {
            System.out.println("WARN: fase YUSA sin poseedores. Revelando cartas.");
            publicarRevelarCartas();
            return;
        }

        // Narrar cuántas yusas hay
        if (colaYusas.size() == 1) {
            narrarGlobal(nombres.getOrDefault(colaYusas.get(0), colaYusas.get(0))
                    + " tiene una yusa.");
        } else {
            String nombresYusas = colaYusas.stream()
                    .map(u -> nombres.getOrDefault(u, u))
                    .collect(Collectors.joining(", "));
            narrarGlobal("emojiDepolla".repeat(colaYusas.size())
                    + " ¡" + colaYusas.size() + " yusas simultáneas! ("
                    + nombresYusas + "). Preguntan por turnos.");
        }

        publicarSiguientePreguntaYusa();
    }

    private void onElegirObjetivoYusa(String uidObjetivo) {
        if (!tieneYusa(uidLocal)) {
            narrarPrivado(uidLocal, "No tienes yusa.");
            return;
        }
        if (uidObjetivo.equals(uidLocal)) {
            narrarPrivado(uidLocal, "No puedes elegirte a ti mismo.");
            return;
        }
        if (objetivosPorPoseedor.containsKey(uidLocal)) {
            narrarPrivado(uidLocal, "Ya elegiste objetivo en esta ronda.");
            return;
        }
        // Comprobar que es el turno de este poseedor.
        // Si soy el director, verifico que soy el primero de la cola.
        if (uidLocal.equals(uidTurnoActual)
                && !colaYusas.isEmpty()
                && !colaYusas.get(0).equals(uidLocal)) {
            narrarPrivado(uidLocal, "Espera tu turno de yusa.");
            return;
        }

        objetivosPorPoseedor.put(uidLocal, uidObjetivo);
        List<String> m = manos.get(uidLocal);
        if (m != null && !m.isEmpty()) {
            snapshotCartas.put(uidLocal, m.get(0));
        }

        narrarGlobal(nombres.getOrDefault(uidLocal, uidLocal)
                + " pregunta a " + nombres.getOrDefault(uidObjetivo, uidObjetivo)
                + " el palo de su yusa.");

        try {
            bd.actualizarObjetivoYusa(codigoSala, uidLocal, uidObjetivo, idToken);
            bd.publicarEstadoRonda(codigoSala, "ELEGIR_PALO",
                    JuegoYusa.FaseRonda.YUSA.name(), uidObjetivo, idToken);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void onElegirPaloYusa(JuegoYusa.Palo palo) {
        if (!soyObjetivoDeYusa) {
            return;
        }
        ocultarPanelDecision();
        soyObjetivoDeYusa = false;
        enviarDecision("PALO_" + palo.name());
        narrarPrivado(uidLocal, "Elegiste " + palo.name() + ". Esperando...");
        // Usar el campo guardado en lugar de buscar en objetivosPorPoseedor
        String nomPoseedor = nombres.getOrDefault(uidPoseedorYusaActual, uidPoseedorYusaActual);
        narrarGlobal(nombres.getOrDefault(uidLocal, uidLocal)
                + " ha respondido " + palo.name()
                + " a la yusa de " + nomPoseedor + ".");
    }

    private void procesarPaloRecibido(String uidObjetivo, String decision) {
        String paloStr = decision.replace("PALO_", "");
        JuegoYusa.Palo paloEleg;
        try {
            paloEleg = JuegoYusa.Palo.valueOf(paloStr);
        } catch (IllegalArgumentException e) {
            System.out.println("WARN: palo desconocido: " + paloStr);
            return;
        }

        new Thread(() -> {
            try {
                // Leer objetivos de Firebase para mapa completo
                Map<String, String> objetivosFirebase = bd.leerObjetivosYusa(codigoSala, idToken);
                if (objetivosFirebase != null) {
                    objetivosFirebase.forEach((pos, obj)
                            -> objetivosPorPoseedor.putIfAbsent(pos, obj));
                }

                // ── Bug 1: procesarPaloRecibido ──────────────────────────────────────────
// En lugar de publicar REVELAR_CARTAS inmediatamente, guardar el contexto
// y avanzar al siguiente poseedor. Solo revelar cuando la cola esté vacía.
                Platform.runLater(() -> {
                    String poseedor = null;
                    if (!colaYusas.isEmpty()) {
                        poseedor = colaYusas.get(0);
                    } else {
                        for (Map.Entry<String, String> p : objetivosPorPoseedor.entrySet()) {
                            if (p.getValue().equals(uidObjetivo)) {
                                poseedor = p.getKey();
                                break;
                            }
                        }
                    }
                    if (poseedor == null) {
                        System.out.println("WARN: sin poseedor para " + uidObjetivo);
                        return;
                    }

                    String cartaPoseedor = null;
                    List<String> mp = manos.get(poseedor);
                    if (mp != null && !mp.isEmpty()) {
                        cartaPoseedor = mp.get(0);
                    } else {
                        cartaPoseedor = snapshotCartas.get(poseedor);
                    }
                    if (cartaPoseedor == null) {
                        System.out.println("WARN: sin carta para " + poseedor);
                        return;
                    }

                    // Guardar contexto de ESTE duelo en la lista de duelos pendientes
                    duelosPendientesYusa.add(new DueloYusa(poseedor, uidObjetivo, paloEleg));
                    snapshotCartas.put(poseedor, cartaPoseedor); // asegurar snapshot

                    // Quitar poseedor de la cola
                    objetivosPorPoseedor.remove(poseedor);
                    if (!colaYusas.isEmpty()) {
                        colaYusas.remove(0);
                    }

                    try {
                        if (!colaYusas.isEmpty()) {
                            // Quedan más poseedores → siguiente pregunta sin revelar aún
                            publicarSiguientePreguntaYusa();
                        } else {
                            // Todas las yusas respondidas → revelar cartas
                            // NO llamar a guardarSnapshot() — los snapshots de los poseedores
                            // ya se guardaron individualmente en la línea 804
                            bd.publicarEstadoRonda(codigoSala, "REVELAR_CARTAS",
                                    JuegoYusa.FaseRonda.YUSA.name(), null, idToken);
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void cerrarRondaYusa() throws IOException {
        yusa().descartarManosAlFinDeRonda(manos, descarte);
        bd.actualizarDescarte(codigoSala, descarte, idToken);
        bd.publicarManosYusa(codigoSala, manos, idToken);

        // NO resetear la baraja aquí — se hace en finalizarCicloRonda()
        // para que los jugadores vean las cartas 5 segundos antes del reset
        finalizarCicloRonda();
    }

    // =========================================================================
    //  REVELAR Y RESOLVER
    // =========================================================================
    private void publicarRevelarCartas() throws IOException {
        guardarSnapshot();
        bd.publicarEstadoRonda(codigoSala, "REVELAR_CARTAS",
                faseRondaActual != null ? faseRondaActual.name() : "NORMAL", null, idToken);
    }

    private void cargarManosYRevelar() {
        cargarManosYRevelarConReintento(0);
    }

    private void cargarManosYRevelarConReintento(int intento) {
        new Thread(() -> {
            Map<String, List<String>> frescas = new HashMap<>();
            try {
                Map<String, Object> partida = bd.leerPartida(codigoSala, idToken);
                if (partida != null) {
                    Object mo = partida.get("manos");
                    if (mo instanceof Map<?, ?> raw) {
                        for (Map.Entry<?, ?> e : raw.entrySet()) {
                            String uid = e.getKey().toString();
                            if (e.getValue() instanceof List<?> lista) {
                                List<String> l = new ArrayList<>();
                                for (Object it : lista) {
                                    if (it != null) {
                                        l.add(it.toString());
                                    }
                                }
                                if (l.size() == 1 && "EMPTY".equals(l.get(0))) {
                                    l.clear();
                                }
                                if (!l.isEmpty()) {
                                    frescas.put(uid, l);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            // SUSTITUYE la condición manasValidas:
            long vivosConCarta = yusa().getJugadoresVivos().stream()
                    .filter(uid -> frescas.containsKey(uid)).count();
            long totalVivos = yusa().getJugadoresVivos().size();

            // Válido si tiene la mayoría (tolera jugadores sin carta en fase YUSA)
            boolean manasValidas = !frescas.isEmpty() && vivosConCarta >= Math.max(1, totalVivos - 1);

            if (!manasValidas && intento < MAX_INTENTOS_MANOS) {
                System.out.println("[YUSA] Manos no listas (intento " + (intento + 1)
                        + "/" + MAX_INTENTOS_MANOS + "). Reintentando en "
                        + DELAY_REINTENTO_MS + "ms...");
                try {
                    Thread.sleep(DELAY_REINTENTO_MS);
                } catch (InterruptedException ignored) {
                }
                Platform.runLater(() -> cargarManosYRevelarConReintento(intento + 1));
                return;
            }

            System.out.println("[YUSA] Manos listas tras " + intento + " intento(s). "
                    + "Jugadores vivos: " + yusa().getJugadoresVivos()
                    + " | Manos recibidas: " + frescas.keySet());

            final Map<String, List<String>> resultado = frescas;
            Platform.runLater(() -> {
                if (!resultado.isEmpty()) {
                    manos.clear();
                    manos.putAll(resultado);

                }

                mostrarCartasDeRonda();
            });
        }).start();
    }

    private void resolverFinDeRonda() {
        if (!uidLocal.equals(uidTurnoActual)) {
            return;
        }
        try {
            // Reconstruir manos desde snapshot o fallback
            Map<String, List<String>> mr = new HashMap<>();
            if (!snapshotCartas.isEmpty()) {
                for (Map.Entry<String, String> e : snapshotCartas.entrySet()) {
                    mr.put(e.getKey(), List.of(e.getValue()));
                }
            } else {
                for (Map.Entry<String, List<String>> e : manos.entrySet()) {
                    if (e.getValue() != null && !e.getValue().isEmpty()) {
                        mr.put(e.getKey(), new ArrayList<>(e.getValue()));
                    }
                }
            }

            if (mr.isEmpty()) {
                System.out.println("WARN: manosResolver vacío en resolverFinDeRonda.");
                bd.limpiarEstadoRonda(codigoSala, idToken);
                finalizarCicloRonda();
                return;
            }

            // Determinar participantes: si estamos en desempate, solo los empatados
            List<String> participantes = (empatadosActuales != null && !empatadosActuales.isEmpty())
                    ? new ArrayList<>(empatadosActuales)
                    : new ArrayList<>(yusa().getJugadoresVivos());

            List<String> perdedores = yusa().determinarPerdedores(mr, participantes);

            if (perdedores.size() > 1) {
                // ── EMPATE: arrancar mini-ronda entre los empatados ──────────────
                String nombresEmp = perdedores.stream()
                        .map(u -> nombres.getOrDefault(u, u)).collect(Collectors.joining(", "));
                narrarGlobal("¡Empate entre " + nombresEmp + "! Ronda de desempate.");

                // Descartar manos actuales
                yusa().descartarManosAlFinDeRonda(manos, descarte);
                bd.actualizarDescarte(codigoSala, descarte, idToken);
                bd.publicarManosYusa(codigoSala, manos, idToken);

                if (yusa().debeResetearBaraja(descarte)) {
                    yusa().resetearBaraja(baraja, descarte);
                    narrarGlobal("¡Se barajan todas las cartas de nuevo!");
                    bd.actualizarBaraja(codigoSala, baraja, idToken);
                    bd.actualizarDescarte(codigoSala, descarte, idToken);
                }

                // Publicar ESPECTADOR_DESEMPATE para bloquear a los no participantes
                // turnoUid = UIDs de empatados separados por coma
                String uidsEmpatados = String.join(",", perdedores);
                bd.publicarEstadoRonda(codigoSala, "ESPECTADOR_DESEMPATE",
                        faseRondaActual != null ? faseRondaActual.name() : "NORMAL",
                        uidsEmpatados, idToken);

                // Preparar la mini-ronda en el director
                empatadosActuales = new ArrayList<>(perdedores);
                enDesempate = true;

                // Pequeña pausa para que todos reciban el estado antes de arrancar
                PauseTransition pausa = new PauseTransition(Duration.seconds(1));
                pausa.setOnFinished(ev -> {
                    try {
                        iniciarMiniRondaDesempate();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
                pausa.play();
                return;
            }

            // ── DESEMPATE RESUELTO O RONDA NORMAL SIN EMPATE ────────────────────
            if (enDesempate) {
                // Avisar a todos que el desempate terminó
                bd.publicarEstadoRonda(codigoSala, "FIN_DESEMPATE",
                        faseRondaActual != null ? faseRondaActual.name() : "NORMAL",
                        null, idToken);
                enDesempate = false;
                empatadosActuales = null;
                ocultarOverlayEspectador();
            }

            if (perdedores.size() == 1) {
                String uid = perdedores.get(0);
                boolean eli = yusa().perderVida(uid);
                narrarGlobal(nombres.getOrDefault(uid, uid)
                        + " pierde una vida → " + yusa().getVidas(uid) + " restantes.");
                if (eli) {
                    narrarGlobal("¡" + nombres.getOrDefault(uid, uid) + " eliminado!");
                    // Si el eliminado soy yo, mostrar el overlay
                    if (uid.equals(uidLocal)) {
                        mostrarMensajeEliminado();
                    }
                }
                bd.actualizarVidaJugador(codigoSala, uid, yusa().getVidas(uid), idToken);
            } else if (perdedores.size() > 1) {
                String ne = perdedores.stream()
                        .map(u -> nombres.getOrDefault(u, u)).collect(Collectors.joining(", "));
                narrarGlobal("¡Empate entre " + ne + "! Todos pierden una vida.");
                for (String uid : perdedores) {
                    boolean eli = yusa().perderVida(uid);
                    if (eli) {
                        narrarGlobal("¡" + nombres.getOrDefault(uid, uid) + " eliminado!");
                        if (uid.equals(uidLocal)) {
                            mostrarMensajeEliminado();
                        }
                    }
                    bd.actualizarVidaJugador(codigoSala, uid, yusa().getVidas(uid), idToken);
                }
            }

            if (yusa().haTerminado(manos, baraja, descarte)) {
                bd.limpiarEstadoRonda(codigoSala, idToken);
                finalizarPartida();
                return;
            }

            yusa().descartarManosAlFinDeRonda(manos, descarte);
            bd.actualizarDescarte(codigoSala, descarte, idToken);
            bd.publicarManosYusa(codigoSala, manos, idToken);

            if (yusa().debeResetearBaraja(descarte)) {
                yusa().resetearBaraja(baraja, descarte);
                narrarGlobal("¡Se barajan todas las cartas de nuevo!");
                bd.actualizarBaraja(codigoSala, baraja, idToken);
                bd.actualizarDescarte(codigoSala, descarte, idToken);
            }

            bd.limpiarEstadoRonda(codigoSala, idToken);
            finalizarCicloRonda();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void incrementarNumeroRonda() {
        numeroRondaYusa++;
        try {
            bd.incrementarRonda(codigoSala, numeroRondaYusa, idToken);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // =========================================================================
    //  FIN DE CICLO
    // =========================================================================
    private void finalizarCicloRonda() throws IOException {
        bd.limpiarEstadoRonda(codigoSala, idToken);
        bd.limpiarDecisionJugador(codigoSala, idToken);
        bd.limpiarObjetivosYusa(codigoSala, idToken);
        bd.limpiarPalosYusa(codigoSala, idToken);
        db.borrarNodo("salas/" + codigoSala + "/partida/yusa", idToken);

        // Reset de baraja AQUÍ — después de haber mostrado las cartas 5s
        if (yusa().debeResetearBaraja(descarte)) {
            yusa().resetearBaraja(baraja, descarte);
            narrarGlobal("¡Se barajan todas las cartas de nuevo!");
            bd.actualizarBaraja(codigoSala, baraja, idToken);
            bd.actualizarDescarte(codigoSala, descarte, idToken);
        }

        // Añade junto a los otros borrarNodo:
        try {
            bd.limpiarDoceJugado(codigoSala, idToken);
        } catch (Exception e) {
            e.printStackTrace();
        }
        empatadosActuales = null;
        enDesempate = false;
        colaYusas.clear();
        // ocultarMensajeEliminado();

        List<String> vivos = ordenJugadoresGlobal.stream()
                .filter(uid -> yusa().getJugadoresVivos().contains(uid))
                .collect(Collectors.toList());
        String siguiente = yusa().siguienteTurno(uidTurnoActual, vivos);

        try {
            Map<String, Integer> v = bd.leerVidas(codigoSala, idToken);
            if (v != null) {
                yusa().cargarVidasDesdeBD(v);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        resetearEstadoLocal();
        bd.actualizarTurno(codigoSala, siguiente, idToken);
    }

    private void resetearEstadoLocal() {
        detenerTickDoce();
        rondaEnCurso = false;
        faseRondaActual = null;
        snapshotCartas.clear();
        //ultimoEstadoTs = -1L;
        ultimaDecisionTs = -1L;
        ordenRondaActual.clear();
        uidPoseedorYusaActual = null;
        objetivosPorPoseedor.clear();
        soyObjetivoDeYusa = false;
        ocultarPanelDecision();
        empatadosActuales = null;
        enDesempate = false;
        ocultarOverlayEspectador();
        colaYusas.clear();
        //ocultarMensajeEliminado();
        yusaPendientePoseedor = null;
        yusaPendienteObjetivo = null;
        yusaPendientePalo = null;
    }

    // =========================================================================
    //  MOSTRAR / OCULTAR CARTAS
    // =========================================================================
    private void mostrarCartasDeRonda() {
        mostrandoCartas = true;  // ← bloquear actualizarInterfaz()

        colocarJugadores();

        // Log de qué cartas se van a mostrar
        System.out.println("[YUSA] Mostrando cartas:");
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

            // ← AÑADIR: saltar jugadores eliminados
            if (!yusa().getJugadoresVivos().contains(uid)) {
                System.out.println("  " + nombres.getOrDefault(uid, uid) + " → ELIMINADO (sin carta)");
                continue;
            }

            List<String> m = manos.get(uid);
            if (m == null || m.isEmpty()) {
                System.out.println("  " + nombres.getOrDefault(uid, uid) + " → SIN CARTA");
                continue;
            }

            String cartaRuta = m.get(0);
            System.out.println("  " + nombres.getOrDefault(uid, uid) + " → " + cartaRuta);

            zona.getChildren().clear();
            ImageView img = new ImageView(new Image(getClass().getResourceAsStream(cartaRuta)));
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
                img.setLayoutY(cy + 90); // COLADA?
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

        System.out.println("[YUSA] Cartas mostradas. Iniciando pausa de 5 segundos...");

        pausaRevelado = new PauseTransition(Duration.seconds(5));
        pausaRevelado.setOnFinished(ev -> {
            System.out.println("[YUSA] 5 segundos completados. Ocultando cartas.");
            ocultarCartasDeRonda();
            pausaRevelado = null;

            if (uidLocal.equals(uidTurnoActual)) {
                // El director decide qué resolver según la fase
                if (faseRondaActual == JuegoYusa.FaseRonda.YUSA && !duelosPendientesYusa.isEmpty()) {
                    // Ronda YUSA: resolver el duelo pendiente
                    resolverDueloYusaTrasRevelacion();
                } else {
                    // Ronda NORMAL o DOCE: resolver quién pierde vida por carta más baja
                    resolverFinDeRonda();
                }
            } else {
                resetearEstadoLocal();
            }
        });
        pausaRevelado.play();
    }

    private void ocultarCartasDeRonda() {
        mostrandoCartas = false;  // ← desbloquear actualizarInterfaz()
        tiempoFinUltimaRevelacion = System.currentTimeMillis();
        System.out.println("[YUSA] actualizarInterfaz() desbloqueada. Ocultando cartas.");
        actualizarInterfaz();
    }

    // =========================================================================
    //  BOTONES
    // =========================================================================
    protected void mostrarBotonesDecision(String op1, String op2,
            java.util.function.Consumer<String> cb) {
        ocultarPanelDecision();
        Button b1 = new Button(op1), b2 = new Button(op2);
        // Animación + sonido
        Animaciones.animarBoton(b1);
        Animaciones.animarBoton(b2);
        ButtonSound.activar(b1);
        ButtonSound.activar(b2);
        b1.setOnAction(e -> {
            ocultarPanelDecision();
            cb.accept(op1);
        });
        b2.setOnAction(e -> {
            ocultarPanelDecision();
            cb.accept(op2);
        });
        panelDecision = new HBox(16, b1, b2);
        panelDecision.setAlignment(Pos.CENTER);
        StackPane.setAlignment(panelDecision, Pos.BOTTOM_CENTER);
        panelDecision.setTranslateY(140);
        rootSala.getChildren().add(panelDecision);
    }

    private Button crearBotonDecision(String texto, EventHandler<ActionEvent> accion) {
        Button btn = new Button(texto);

        btn.setStyle(
                "-fx-background-color: rgba(0,0,0,0.55);"
                + "-fx-background-radius: 12;"
                + "-fx-padding: 12 22;"
                + "-fx-font-size: 20px;"
                + "-fx-font-weight: bold;"
                + "-fx-text-fill: white;"
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.7), 10, 0.5, 0, 0);"
                + "-fx-cursor: hand;"
        );

        Animaciones.animarBoton(btn);
        ButtonSound.activar(btn);

        btn.setOnAction(accion);
        return btn;
    }

    private void ocultarPanelDecision() {
        detenerTickDoce();
        if (panelDecision != null) {
            rootSala.getChildren().remove(panelDecision);
            panelDecision = null;
        }
    }

    private void mostrarBotonesPalo() {
        ocultarPanelDecision();

        Button bC = crearBotonPalo("/ui/graphicResources/imagenes/btnYusaCoronas.png", e -> onElegirPaloYusa(JuegoYusa.Palo.CORONAS));
        Button bV = crearBotonPalo("/ui/graphicResources/imagenes/btnYusaBalanzas.png", e -> onElegirPaloYusa(JuegoYusa.Palo.BALANZAS));
        Button bD = crearBotonPalo("/ui/graphicResources/imagenes/btnYusaDianas.png", e -> onElegirPaloYusa(JuegoYusa.Palo.DIANAS));
        Button bCz = crearBotonPalo("/ui/graphicResources/imagenes/btnYusaCorazones.png", e -> onElegirPaloYusa(JuegoYusa.Palo.CORAZONES));

        panelDecision = new HBox(16, bC, bV, bD, bCz);
        panelDecision.setAlignment(Pos.CENTER);
        StackPane.setAlignment(panelDecision, Pos.BOTTOM_CENTER);
        panelDecision.setTranslateY(140); // positivo abajo
        rootSala.getChildren().add(panelDecision);
    }

    private Button crearBotonPalo(String rutaImagen,
            javafx.event.EventHandler<javafx.event.ActionEvent> accion) {
        ImageView img = new ImageView(
                new Image(getClass().getResourceAsStream(rutaImagen)));
        img.setFitWidth(250);   // ajusta el tamaño que quieras
        img.setFitHeight(250);
        img.setPreserveRatio(true);

        Button btn = new Button();
        btn.setGraphic(img);
        btn.setText("");       // sin texto
        btn.setStyle(
                "-fx-background-color: transparent;"
                + "-fx-border-color: transparent;"
                + "-fx-padding: 4;"
                + "-fx-cursor: hand;");

        Animaciones.animarBoton(btn);
        ButtonSound.activar(btn);

        btn.setOnAction(accion);
        return btn;
    }

    // ── SUSTITUYE mostrarBotonJugarDoce() completo ───────────────────────────
    private void mostrarBotonJugarDoce() {
        // Limpiar cualquier estado anterior ANTES de comprobar panelDecision
        detenerTickDoce();

        if (panelDecision != null) {
            // Si hay un panel visible, ocultarlo primero y crear uno nuevo
            rootSala.getChildren().remove(panelDecision);
            panelDecision = null;
        }

        Button btn = new Button("Jugar carta (20s)");
        btn.setStyle(
                "-fx-font-size: 24px;"
                + "-fx-font-family: 'Minecraft';"
                + "-fx-text-fill: white;"
                + "-fx-background-color: rgba(255,105,180,0.55);"
                + // rosita Tokaleda
                "-fx-background-radius: 12px;"
                + "-fx-padding: 14 28;"
                + "-fx-border-color: rgba(255,255,255,0.7);"
                + "-fx-border-width: 2px;"
                + "-fx-border-radius: 12px;"
                + "-fx-cursor: hand;"
        );
        int[] seg = {20};

        Runnable jugar = () -> {
            detenerTickDoce();       // ← siempre parar el tick antes de actuar
            ocultarPanelDecision();
            onDoceJugado();
        };

        tickDoce = new PauseTransition(Duration.seconds(1));
        tickDoce.setOnFinished(ev -> {
            seg[0]--;
            btn.setText("Jugar carta (" + seg[0] + "s)");
            if (seg[0] > 0) {
                tickDoce.playFromStart();
            } else {
                jugar.run();
            }
        });

        btn.setOnAction(e -> jugar.run());  // jugar.run() ya para el tick

        panelDecision = new HBox(btn);
        panelDecision.setAlignment(Pos.CENTER);
        StackPane.setAlignment(panelDecision, Pos.BOTTOM_CENTER);
        panelDecision.setTranslateY(140);
        rootSala.getChildren().add(panelDecision);
        tickDoce.play();
        narrarPrivado(uidLocal, "Tienes un 12. Pulsa 'Jugar carta'. (20s)");
    }

    private void detenerTickDoce() {
        if (tickDoce != null) {
            tickDoce.stop();
            tickDoce = null;
        }
    }

    // =========================================================================
    //  UTILIDADES
    // =========================================================================
    private void guardarSnapshot() {
        snapshotCartas.clear();
        for (Map.Entry<String, List<String>> e : manos.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                snapshotCartas.put(e.getKey(), e.getValue().get(0));
            }
        }
    }

    private String encontrarJugadorConNumero(int numero) {
        for (String uid : yusa().getJugadoresVivos()) {
            List<String> m = manos.get(uid);
            if (m != null && !m.isEmpty() && yusa().obtenerNumeroCarta(m.get(0)) == numero) {
                return uid;
            }
        }
        return null;
    }

    private boolean tieneYusa(String uid) {
        List<String> m = manos.get(uid);
        return m != null && !m.isEmpty()
                && yusa().obtenerNumeroCarta(m.get(0)) == JuegoYusa.NUMERO_YUSA;
    }

    private void mostrarOverlayEspectador(List<String> empatados) {

        if (overlayEliminado != null) {
            return;
        }

        ocultarOverlayEspectador(); // por si había uno anterior

        // ── AÑADIR: cargar fuente Minecraft ──────────────────────────────────
        javafx.scene.text.Font fuenteMinecraft = javafx.scene.text.Font.loadFont(
                getClass().getResourceAsStream("/ui/graphicResources/fonts/Minecraft.ttf"), 38);
        System.out.println("[FUENTE] Minecraft: " + (fuenteMinecraft != null ? "cargada ✓" : "no encontrada ✗"));
        System.out.println("[FUENTE] Nombre interno: " + fuenteMinecraft.getName());

        String nombresEmpatados = empatados.stream()
                .map(uid -> nombres.getOrDefault(uid, uid))
                .collect(Collectors.joining(" vs "));

        javafx.scene.control.Label lblTitulo = new javafx.scene.control.Label("DESEMPATE");
        if (fuenteMinecraft != null) {
            lblTitulo.setFont(fuenteMinecraft);
        }
        lblTitulo.setStyle(
                "-fx-font-family: 'Minecraft';"
                + "-fx-font-size:28px;-fx-font-weight:bold;"
                + "-fx-text-fill:#ff4444;");

        javafx.scene.control.Label lblJugadores = new javafx.scene.control.Label(nombresEmpatados);
        if (fuenteMinecraft != null) {
            lblJugadores.setFont(fuenteMinecraft);
        }
        lblJugadores.setStyle(
                "-fx-font-family: 'Minecraft';"
                + "-fx-font-size:18px;-fx-text-fill:white;"
                + "-fx-font-weight:bold;");

        javafx.scene.control.Label lblInfo = new javafx.scene.control.Label(
                "Ronda de desempate en curso.\nEspera a que termine.");
        if (fuenteMinecraft != null) {
            lblInfo.setFont(fuenteMinecraft);
        }
        lblInfo.setStyle("-fx-font-family: 'Minecraft';-fx-font-size:14px;-fx-text-fill:#cccccc;-fx-text-alignment:center;");
        lblInfo.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        VBox contenido = new VBox(12, lblTitulo, lblJugadores, lblInfo);
        contenido.setAlignment(Pos.CENTER);
        contenido.setStyle(
                "-fx-background-color:rgba(0,0,0,0.72);"
                + "-fx-padding:32px;"
                + "-fx-background-radius:14;");

        overlayEspectador = new javafx.scene.layout.StackPane(contenido);
        overlayEspectador.setStyle("-fx-background-color:rgba(0,0,0,0.45);");

        // Cubrir toda la escena
        javafx.scene.Scene scene = zonaAbajo.getScene();
        if (scene != null && scene.getRoot() instanceof javafx.scene.layout.Pane root) {
            root.getChildren().add(overlayEspectador);
            //root.getChildren().add(overlayEspectador);
            // Hacer que ocupe todo
            overlayEspectador.prefWidthProperty().bind(root.widthProperty());
            overlayEspectador.prefHeightProperty().bind(root.heightProperty());
        }
    }

    private void ocultarOverlayEspectador() {
        if (overlayEspectador != null) {
            javafx.scene.Parent parent = overlayEspectador.getParent();
            if (parent instanceof javafx.scene.layout.Pane p) {
                p.getChildren().remove(overlayEspectador);
            }
            overlayEspectador = null;
        }
    }

    private void iniciarMiniRondaDesempate() throws IOException {
        // Resetear estado de ronda SIN tocar empatadosActuales ni enDesempate
        faseRondaActual = null;
        ordenRondaActual.clear();
        objetivosPorPoseedor.clear();
        soyObjetivoDeYusa = false;
        snapshotCartas.clear();
        ultimoEstadoTs = -1L;
        ultimaDecisionTs = -1L;
        ocultarPanelDecision();
        colaYusas.clear();

        // Repartir 1 carta SOLO a los empatados
        for (String uid : empatadosActuales) {
            List<String> mano = manos.computeIfAbsent(uid, k -> new ArrayList<>());
            mano.clear();
            if (!baraja.isEmpty()) {
                mano.add(baraja.remove(0));
            }
        }

        bd.publicarManosYusa(codigoSala, manos, idToken);
        bd.actualizarBaraja(codigoSala, baraja, idToken);

        // Determinar fase solo con las manos de los empatados
        Map<String, List<String>> manosEmpatados = new HashMap<>();
        for (String uid : empatadosActuales) {
            List<String> m = manos.get(uid);
            if (m != null) {
                manosEmpatados.put(uid, m);
            }
        }
        faseRondaActual = yusa().determinarFaseRonda(manosEmpatados);

        String nombresEmp = empatadosActuales.stream()
                .map(u -> nombres.getOrDefault(u, u)).collect(Collectors.joining(" vs "));
        narrarGlobal("Desempate: " + nombresEmp + " — " + textoFase(faseRondaActual));

        switch (faseRondaActual) {
            case NORMAL ->
                iniciarFaseNormal();
            case DOCE ->
                iniciarFaseDoce();
            case YUSA ->
                iniciarFaseYusa();
        }
    }

    /**
     * Publica ELEGIR_OBJETIVO apuntando al siguiente de la cola. Si la cola
     * está vacía, cierra la ronda de yusa.
     */
    private void publicarSiguientePreguntaYusa() throws IOException {
        // Saltar poseedores que ya no están vivos (eliminados durante la ronda)
        while (!colaYusas.isEmpty()
                && !yusa().getJugadoresVivos().contains(colaYusas.get(0))) {
            System.out.println("[YUSA] Saltando poseedor eliminado: " + colaYusas.get(0));
            objetivosPorPoseedor.remove(colaYusas.get(0));
            colaYusas.remove(0);
        }

        if (colaYusas.isEmpty()) {
            // Todos han preguntado (o fueron eliminados) → cerrar ronda
            cerrarRondaYusaCompleta();
            return;
        }

        String poseedor = colaYusas.get(0);
        narrarGlobal("Turno de yusa: "
                + nombres.getOrDefault(poseedor, poseedor)
                + " elige a quién preguntar ("
                + colaYusas.size() + " yusa(s) pendiente(s)).");

        // turnoUid = UID del poseedor que debe elegir objetivo AHORA
        bd.publicarEstadoRonda(codigoSala, "ELEGIR_OBJETIVO",
                JuegoYusa.FaseRonda.YUSA.name(), poseedor, idToken);

        // Si soy yo el que debe elegir y soy el director, no necesito
        // esperar al listener — la UI ya se activa en procesarAccion
    }

    /**
     * Llamado cuando todos los poseedores ya preguntaron
     */
    private void cerrarRondaYusaCompleta() throws IOException {
        incrementarNumeroRonda();
        bd.limpiarDecisionJugador(codigoSala, idToken);
        bd.limpiarObjetivosYusa(codigoSala, idToken);
        bd.limpiarPalosYusa(codigoSala, idToken);

        if (yusa().haTerminado(manos, baraja, descarte)) {
            bd.limpiarEstadoRonda(codigoSala, idToken);
            finalizarPartida();
            return;
        }
        cerrarRondaYusa();
    }

    /**
     * Muestra un overlay rojo persistente con el mensaje "ESTÁS ELIMINADO".
     * Incluye una animación de pulso (crece/encoge en loop) para llamar la
     * atención. El overlay se queda visible hasta que se muestre la pantalla
     * final. Solo aparece si el jugador local ha sido eliminado.
     */
    // ── SUSTITUYE mostrarMensajeEliminado() completo ─────────────────────────
    // Soluciona: overlay que desaparece al actualizarInterfaz() y fuente
    private void mostrarMensajeEliminado() {
        System.out.println("[ELIMINADO] mostrarMensajeEliminado() llamado para: " + uidLocal);

        // Limpiar anterior si existía
        if (overlayEliminado != null) {
            overlayFinal.getChildren().remove(overlayEliminado);
            overlayEliminado = null;
        }

        // ── AÑADIR: cargar fuente Minecraft ──────────────────────────────────
        javafx.scene.text.Font fuenteMinecraft = javafx.scene.text.Font.loadFont(
                getClass().getResourceAsStream("/ui/graphicResources/fonts/Minecraft.ttf"), 38);
        System.out.println("[FUENTE] Minecraft: " + (fuenteMinecraft != null ? "cargada ✓" : "no encontrada ✗"));
        System.out.println("[FUENTE] Nombre interno: " + fuenteMinecraft.getName());

        Label lblEliminado = new Label("ESTAS ELIMINADO");
        if (fuenteMinecraft != null) {
            lblEliminado.setFont(fuenteMinecraft);
        }
        lblEliminado.setStyle(
                "-fx-font-family: 'Minecraft';"
                + "-fx-font-size: 38px;"
                + "-fx-font-weight: bold;"
                + "-fx-text-fill: #ff2222;"
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.95), 14, 0.7, 0, 0);"
        );

        Label lblSub = new Label("Espera a que termine la partida");
        if (fuenteMinecraft != null) {
            lblEliminado.setFont(fuenteMinecraft);
        }
        lblSub.setStyle(
                "-fx-font-family: 'Minecraft';"
                + "-fx-font-size:18px;"
                + "-fx-text-fill:#ffaaaa;");

        VBox contenido = new VBox(12, lblEliminado, lblSub);
        contenido.setAlignment(Pos.CENTER);
        contenido.setStyle("-fx-background-color:transparent;");

        overlayEliminado = new StackPane(contenido);
        overlayEliminado.setStyle("-fx-background-color:transparent;");
        overlayEliminado.setPickOnBounds(false);
        overlayEliminado.prefWidthProperty().bind(overlayFinal.widthProperty());
        overlayEliminado.prefHeightProperty().bind(overlayFinal.heightProperty());

        // Añadir al overlayFinal ANTES del popup para que quede debajo si llega la final
        overlayFinal.getChildren().add(0, overlayEliminado); // índice 0 = debajo de todo
        overlayFinal.setVisible(true);

        ScaleTransition pulso = new ScaleTransition(Duration.seconds(1.0), contenido);
        pulso.setFromX(1.0);
        pulso.setFromY(1.0);
        pulso.setToX(1.10);
        pulso.setToY(1.10);
        pulso.setAutoReverse(true);
        pulso.setCycleCount(Animation.INDEFINITE);
        pulso.play();

        System.out.println("[ELIMINADO] Overlay añadido a overlayFinal. Hijos: "
                + overlayFinal.getChildren().size());
    }

    /**
     * Quita el overlay de eliminado (se llama si la partida termina y aparece
     * el popup final)
     */
    // ── SUSTITUYE ocultarMensajeEliminado() ──────────────────────────────────
    private void ocultarMensajeEliminado() {
        if (overlayEliminado != null) {
            overlayFinal.getChildren().remove(overlayEliminado);
            // Si overlayFinal no tiene más hijos visibles, ocultarlo
            if (overlayFinal.getChildren().isEmpty()) {
                overlayFinal.setVisible(false);
            }
            overlayEliminado = null;
        }
    }

    /**
     * Resuelve el duelo de yusa pendiente tras haber mostrado las cartas 5s.
     * Solo lo ejecuta el director.
     */
    private void resolverDueloYusaTrasRevelacion() {
        if (duelosPendientesYusa.isEmpty()) {
            return;
        }

        for (DueloYusa duelo : duelosPendientesYusa) {
            String cartaPoseedor = snapshotCartas.getOrDefault(duelo.poseedor(), null);

            // LOG para verificar
            System.out.println("[DUELO] Poseedor: " + nombres.getOrDefault(duelo.poseedor(), duelo.poseedor())
                    + " | Carta: " + cartaPoseedor
                    + " | Objetivo: " + nombres.getOrDefault(duelo.objetivo(), duelo.objetivo())
                    + " | Palo elegido: " + duelo.paloElegido());

            if (cartaPoseedor == null) {
                List<String> mp = manos.get(duelo.poseedor());
                if (mp != null && !mp.isEmpty()) {
                    cartaPoseedor = mp.get(0);
                }
            }
            if (cartaPoseedor == null) {
                System.out.println("WARN: sin carta para resolver duelo de " + duelo.poseedor());
                continue;
            }

            JuegoYusa.Palo paloReal = yusa().obtenerPaloCarta(cartaPoseedor);
            boolean acerto = paloReal == duelo.paloElegido();
            String perdedor = acerto ? duelo.poseedor() : duelo.objetivo();

            System.out.println("[DUELO] Palo real: " + paloReal
                    + " | Acertó: " + acerto
                    + " | Pierde vida: " + nombres.getOrDefault(perdedor, perdedor));

            boolean eli = yusa().perderVida(perdedor);

            narrarGlobal("Yusa " + nombres.getOrDefault(duelo.poseedor(), duelo.poseedor())
                    + " → " + nombres.getOrDefault(duelo.objetivo(), duelo.objetivo())
                    + ": dijo " + duelo.paloElegido().name()
                    + ", era " + paloReal.name() + ". "
                    + (acerto ? "¡Acertó! " : "¡Falló! ")
                    + nombres.getOrDefault(perdedor, perdedor)
                    + " pierde una vida → " + yusa().getVidas(perdedor) + " restantes.");

            if (eli) {
                narrarGlobal("¡" + nombres.getOrDefault(perdedor, perdedor) + " eliminado!");
                if (perdedor.equals(uidLocal)) {
                    mostrarMensajeEliminado();
                }
            }

            try {
                bd.actualizarVidaJugador(codigoSala, perdedor, yusa().getVidas(perdedor), idToken);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        duelosPendientesYusa.clear();
        // Los campos de yusaPendiente* ya no se usan, pero limpiar por limpieza
        yusaPendientePoseedor = null;
        yusaPendienteObjetivo = null;
        yusaPendientePalo = null;

        incrementarNumeroRonda();

        try {
            bd.limpiarDecisionJugador(codigoSala, idToken);
            bd.limpiarObjetivosYusa(codigoSala, idToken);
            bd.limpiarPalosYusa(codigoSala, idToken);

            if (yusa().haTerminado(manos, baraja, descarte)) {
                bd.limpiarEstadoRonda(codigoSala, idToken);
                finalizarPartida();
                return;
            }
            cerrarRondaYusa();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    protected void cargarNombresJugadores() {
        // Los nombres ya se cargaron en precargarNombres() al inicio.
        // No hacer peticiones HTTP aquí para no saturar Firebase en cada redibujado.
        // El mapa 'nombres' ya tiene los valores correctos.
    }

}
