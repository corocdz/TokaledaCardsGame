package ui;

import java.io.IOException;
import java.util.*;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import partidaUTIL.Juego;
import partidaUTIL.JuegoPescaito;

/**
 * Controlador para el modo de juego Pescaito. Extiende PartidaBaseController y
 * solo contiene lógica exclusiva de Pescaito: selección de número, preguntar,
 * robar, detectar pescaito.
 */
public class PartidaControllerPescaito extends PartidaControllerBase {

    // ─── Estado exclusivo de Pescaito ─────────────────────────────────────────
    private Integer numeroSeleccionado = null;
    private Integer numeroPreguntadoAntesDeRobar = null;
    private boolean esperandoRobo = false;
    private boolean uiBloqueadaPorAccion = false;
    private String uidJugadorObjetivo = null;

    // =========================================================================
    //  HOOKS DE LA BASE
    // =========================================================================
    @Override
    protected Juego crearJuego(String modo) {
        if ("Pescaito".equals(modo)) {
            return new JuegoPescaito();
        }
        return null; // modo no reconocido en este controlador
    }

    @Override
    protected void registrarListenersPropios() {
        // Pescaito no necesita listeners adicionales.
        // El listener de turno ya está en la base y llama a onCambioTurno().
    }

    /**
     * En Pescaito, cuando cambia el turno solo narramos. iniciarTurnoLocal() se
     * dispara desde actualizarDesdeModelo(), no desde aquí, para evitar el
     * bucle de turnos infinito.
     */
    @Override
    protected void onCambioTurno(String nuevoTurno) {
        narrarGlobal("Turno de: " + nombres.getOrDefault(nuevoTurno, ""));
    }

    @Override
    protected void onClickMazo() {
        if (!uidLocal.equals(uidTurnoActual)) {
            narrarPrivado(uidLocal, "No es tu turno.");
            return;
        }
        if (!esperandoRobo) {
            narrarPrivado(uidLocal, "No puedes robar ahora.");
            return;
        }
        realizarRoboManual();
    }

    @Override
    protected void onZonaRivalClick(String uidRival) {
        if (!uidLocal.equals(uidTurnoActual)) {
            narrarPrivado(uidLocal, "No es tu turno.");
            return;
        }
        if (esperandoRobo) {
            narrarPrivado(uidLocal, "Debes robar antes de continuar.");
            return;
        }
        if (numeroSeleccionado == null) {
            narrarPrivado(uidLocal, "Primero selecciona un número de tu mano.");
            return;
        }
        if (uidRival == null || uidRival.equals(uidLocal)) {
            return;
        }

        List<String> manoObjetivo = manos.get(uidRival);
        if (manoObjetivo == null || manoObjetivo.isEmpty()) {
            // Comprobar si hay algún otro rival con cartas
            boolean hayOtros = manos.entrySet().stream()
                    .anyMatch(e -> !e.getKey().equals(uidLocal)
                    && e.getValue() != null && !e.getValue().isEmpty());
            if (!hayOtros && !baraja.isEmpty()) {
                // Nadie tiene cartas → pasar turno automáticamente
                narrarPrivado(uidLocal, "No hay rivales con cartas. Pasas el turno.");
                try {
                    bd.actualizarTurno(codigoSala, obtenerSiguienteJugador(uidTurnoActual), idToken);
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            } else {
                narrarPrivado(uidLocal, "Ese jugador no tiene cartas. Elige otro.");
            }
            return;
        }

        uidJugadorObjetivo = uidRival;
        ejecutarPregunta();
    }

    @Override
    protected void onCartaLocalClick(String rutaCarta) {
        if (!uidLocal.equals(uidTurnoActual)) {
            narrarPrivado(uidLocal, "No es tu turno.");
            return;
        }
        if (esperandoRobo) {
            narrarPrivado(uidLocal, "Debes robar antes de continuar.");
            return;
        }

        int numero = juego.obtenerNumeroCarta(rutaCarta);
        numeroSeleccionado = numero;
        narrarPrivado(uidLocal, "Has seleccionado el número " + numero + ".\nAhora elige un jugador.");
    }

    @Override
    protected DatosPopUp construirDatosPopUpFinal() {
        try {
            // 1. Leer pescaitos desde Firebase
            Map<String, Map<String, Object>> pescaitosBD
                    = bd.leerPescaitos(codigoSala, idToken);

            // 2. Convertir BD → puntuaciones reales
            Map<String, Integer> puntuaciones
                    = ((JuegoPescaito) juego).calcularPuntuacionesDesdeBD(pescaitosBD);

            // 3. Encontrar el máximo
            int maxPescaitos = puntuaciones.values().stream()
                    .max(Integer::compare)
                    .orElse(0);

            // 4. Lista de ganadores (puede haber empate)
            List<String> ganadores = puntuaciones.entrySet().stream()
                    .filter(e -> e.getValue() == maxPescaitos)
                    .map(Map.Entry::getKey)
                    .toList();

            String resultado;
            String detalle;
            String icono;

            // 5. Construir textos igual que en Yusa
            if (ganadores.size() == 1) {
                String uidGanador = ganadores.get(0);
                String nombre = nombres.getOrDefault(uidGanador, "Jugador");

                resultado = "¡Ha ganado " + nombre + "!";
                detalle = maxPescaitos + " pescaito" + (maxPescaitos != 1 ? "s" : "");

                icono = "/ui/graphicResources/imagenes/imgGanador.png";

            } else {
                // Empate
                resultado = "¡Empate!";
                detalle = maxPescaitos + " pescaitos cada uno";

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

    // =========================================================================
    //  ACTUALIZAR MODELO — sobreescribir para llamar a iniciarTurnoLocal()
    // =========================================================================
    /**
     * En Pescaito, tras sincronizar el modelo, si es mi turno inicio el turno
     * local. Sobreescribimos actualizarDesdeModelo() para añadir este paso.
     */
    @Override
    public void actualizarDesdeModelo(Map<String, List<String>> manosBD,
            List<String> barajaBD,
            List<String> descarteBD) throws IOException {
        super.actualizarDesdeModelo(manosBD, barajaBD, descarteBD);

        // En Pescaito, el turno se activa cuando cambian manos/baraja, no cuando cambia el nodo turno
        if (uidLocal.equals(uidTurnoActual)) {
            iniciarTurnoLocal();
        }
    }

    // =========================================================================
    //  INICIO DE TURNO — exclusivo de Pescaito
    // =========================================================================
    private void iniciarTurnoLocal() throws IOException {
        if (!repartoInicialHecho) {
            activarInteraccion();
            return;
        }
        if (partidaFinalizada) {
            return;
        }
        if (esperandoRobo || uiBloqueadaPorAccion) {
            return;
        }
        if (esFinDePartida()) {
            finalizarPartida();
            return;
        }

        Juego.AccionTurno accion = juego.accionInicioTurno(uidLocal, manos, baraja);

        switch (accion) {
            case PASAR_TURNO:
                boolean alguienTieneCartas = manos.values().stream()
                        .anyMatch(m -> m != null && !m.isEmpty());
                if (!alguienTieneCartas && baraja.isEmpty()) {
                    finalizarPartida();
                    return;
                }
                narrarGlobal(nombres.getOrDefault(uidLocal, uidLocal)
                        + " no tiene cartas ni puede robar. Pasa turno.");
                PauseTransition delay = new PauseTransition(Duration.seconds(1));
                delay.setOnFinished(ev -> {
                    try {
                        bd.actualizarTurno(codigoSala,
                                obtenerSiguienteJugadorConCartas(), idToken);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
                delay.play();
                break;

            case ROBAR_AUTOMATICO:
                narrarGlobal(nombres.getOrDefault(uidLocal, uidLocal)
                        + " no tenía cartas → roba automáticamente del mazo.");
                juego.robarCarta(uidLocal, manos, baraja);
                bd.actualizarMano(codigoSala, uidLocal, manos.get(uidLocal), idToken);
                bd.actualizarBaraja(codigoSala, baraja, idToken);
                break;

            case JUGAR_NORMAL:
            default:
                // Comprobar si hay algún rival con cartas a quien preguntar
                boolean hayRivalesConCartas = manos.entrySet().stream()
                        .anyMatch(e -> !e.getKey().equals(uidLocal)
                        && e.getValue() != null
                        && !e.getValue().isEmpty());

                if (!hayRivalesConCartas) {
                    // Nadie tiene cartas — comprobar si hay baraja
                    if (!baraja.isEmpty()) {
                        // Hay baraja pero nadie a quien preguntar → pasar turno
                        // Los demás jugadores robarán automáticamente cuando les llegue
                        narrarPrivado(uidLocal,
                                "No hay jugadores con cartas a quien preguntar.\nPasas el turno.");
                        narrarGlobal(nombres.getOrDefault(uidLocal, "Jugador")
                                + " pasa turno: no hay rivales con cartas.");
                        bd.actualizarTurno(codigoSala,
                                obtenerSiguienteJugador(uidTurnoActual), idToken);
                    }
                    return;
                }

                // Hay rivales con cartas → juego normal
                activarInteraccion();
                break;
        }
    }

    private String obtenerSiguienteJugadorConCartas() {
        String candidato = obtenerSiguienteJugador(uidTurnoActual);
        for (int i = 0; i < ordenJugadoresGlobal.size(); i++) {
            List<String> mano = manos.get(candidato);
            if (mano != null && !mano.isEmpty()) {
                return candidato;
            }
            candidato = obtenerSiguienteJugador(candidato);
        }
        return obtenerSiguienteJugador(uidTurnoActual);
    }

    private boolean esFinDePartida() {
        return juego != null && juego.haTerminado(manos, baraja, descarte);
    }

    // =========================================================================
    //  FLUJO DE PREGUNTA
    // =========================================================================
    private void ejecutarPregunta() {
        if (!uidLocal.equals(uidTurnoActual)) {
            narrarPrivado(uidLocal, "No es tu turno.");
            return;
        }
        if (esperandoRobo) {
            narrarPrivado(uidLocal, "Debes robar antes de continuar.");
            return;
        }
        if (numeroSeleccionado == null) {
            narrarPrivado(uidLocal, "No has seleccionado número.");
            return;
        }
        if (uidJugadorObjetivo == null) {
            narrarPrivado(uidLocal, "No has seleccionado jugador objetivo.");
            return;
        }
        if (juego == null) {
            return;
        }

        uiBloqueadaPorAccion = true;
        zonaCentro.setDisable(true);

        narrarPrivado(uidLocal, "Has preguntado a " + nombres.get(uidJugadorObjetivo)
                + "\npor el " + numeroSeleccionado + ".");

        try {
            Map<String, Object> resultado = ((JuegoPescaito) juego).preguntar(
                    uidLocal, uidJugadorObjetivo, numeroSeleccionado, manos, baraja, descarte);
            procesarResultadoPregunta(resultado);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            uidJugadorObjetivo = null;
            uiBloqueadaPorAccion = false;
            if (uidLocal.equals(uidTurnoActual) && !esperandoRobo) {
                activarInteraccion();
            }
            if (!esperandoRobo) {
                zonaCentro.setDisable(false);
            }
        }
    }

    private void procesarResultadoPregunta(Map<String, Object> resultado) throws IOException {
        if (!uidLocal.equals(uidTurnoActual)) {
            return;
        }
        if (esperandoRobo) {
            return;
        }

        boolean acierto = (boolean) resultado.get("acierto");
        boolean pescaito = (boolean) resultado.get("pescaito");
        boolean debeRobar = (boolean) resultado.get("debeRobar");
        boolean mantieneTurno = (boolean) resultado.get("mantieneTurno");
        int cartasRecibidas = (int) resultado.get("cartasRecibidas");

        if (acierto) {
            // Mensaje global para que todos vean qué pasó
            narrarGlobal(nombres.getOrDefault(uidLocal, uidLocal)
                    + " le ha preguntado a " + nombres.getOrDefault(uidJugadorObjetivo, uidJugadorObjetivo)
                    + " por el " + numeroSeleccionado
                    + " → ¡Lo tenía! Roba " + cartasRecibidas + " carta(s) del " + numeroSeleccionado + ".");
            if (pescaito) {
                int numeroPescaito = (int) resultado.get("numeroPescaito");
                narrarGlobal("¡PESCAITO de " + nombres.getOrDefault(uidLocal, uidLocal)
                        + "! Número " + numeroPescaito + ". Mantiene turno.");
            } else if (mantieneTurno) {
                narrarPrivado(uidLocal, "Mantienes el turno.");
            }
        } else {
            narrarGlobal(nombres.getOrDefault(uidLocal, uidLocal)
                    + " le ha preguntado a " + nombres.getOrDefault(uidJugadorObjetivo, uidJugadorObjetivo)
                    + " por el " + numeroSeleccionado
                    + " → Fallo. " + nombres.getOrDefault(uidJugadorObjetivo, uidJugadorObjetivo)
                    + " no tenía el " + numeroSeleccionado + ".");
            if (debeRobar) {
                narrarPrivado(uidLocal, "Debes robar una carta del mazo.");
            }
        }

        if (pescaito) {
            int numeroPescaito = (int) resultado.get("numeroPescaito");
            bd.registrarPescaito(codigoSala, uidLocal, numeroPescaito, idToken);
            bd.actualizarDescarte(codigoSala, descarte, idToken);
        }

        if (debeRobar) {
            if (baraja.isEmpty()) {
                narrarGlobal(nombres.getOrDefault(uidLocal, uidLocal)
                        + " no puede robar — baraja vacía. Pasa turno.");
                PauseTransition delay = new PauseTransition(Duration.seconds(1));
                delay.setOnFinished(ev -> {
                    try {
                        bd.actualizarTurno(codigoSala,
                                obtenerSiguienteJugador(uidTurnoActual), idToken);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
                delay.play();
                numeroSeleccionado = null;
                numeroPreguntadoAntesDeRobar = null;
                esperandoRobo = false;
                return;
            }
            numeroPreguntadoAntesDeRobar = numeroSeleccionado;
            esperandoRobo = true;
            desactivarInteraccion();
            zonaCentro.setDisable(false);
            imgMazo.setDisable(false);
            imgMazo.setOpacity(1.0);
            uiBloqueadaPorAccion = false;
            return;
        }

        bd.actualizarMano(codigoSala, uidLocal, manos.get(uidLocal), idToken);
        bd.actualizarMano(codigoSala, uidJugadorObjetivo, manos.get(uidJugadorObjetivo), idToken);

        if (mantieneTurno) {
            bd.actualizarTurno(codigoSala, uidLocal, idToken);
        } else {
            bd.actualizarTurno(codigoSala, obtenerSiguienteJugador(uidTurnoActual), idToken);
        }

        zonaCentro.setDisable(false);
        numeroSeleccionado = null;
        uidJugadorObjetivo = null;
    }

    private void realizarRoboManual() {
        if (!uidLocal.equals(uidTurnoActual)) {
            narrarPrivado(uidLocal, "No es tu turno.");
            return;
        }
        if (!esperandoRobo) {
            narrarPrivado(uidLocal, "No estás obligado a robar.");
            return;
        }

        uiBloqueadaPorAccion = true;
        zonaArriba.setDisable(true);
        zonaIzquierda.setDisable(true);
        zonaDerecha.setDisable(true);
        zonaAbajo.setDisable(true);

        try {
            ((JuegoPescaito) juego).robarCarta(uidLocal, manos, baraja);
            List<String> mano = manos.get(uidLocal);
            String cartaRobada = mano.get(mano.size() - 1);
            int numeroRobado = juego.obtenerNumeroCarta(cartaRobada);

            bd.actualizarMano(codigoSala, uidLocal, mano, idToken);
            bd.actualizarBaraja(codigoSala, baraja, idToken);
            narrarGlobal(nombres.getOrDefault(uidLocal, uidLocal)
                    + " roba del mazo."); //narrarPrivado(uidLocal, "Has robado un " + numeroRobado);

            boolean haPescado = ((JuegoPescaito) juego)
                    .haPescadoAlRobar(cartaRobada, numeroPreguntadoAntesDeRobar);
            boolean pescaito = ((JuegoPescaito) juego)
                    .esPescaitoPorRobo(uidLocal, manos, descarte);

            if (pescaito) {
                narrarGlobal("¡PESCAITO de " + nombres.getOrDefault(uidLocal, uidLocal)
                        + "! Número " + juego.obtenerNumeroCarta(cartaRobada) + ".");
                bd.actualizarMano(codigoSala, uidLocal, manos.get(uidLocal), idToken);
                bd.actualizarDescarte(codigoSala, descarte, idToken);
                bd.registrarPescaito(codigoSala, uidLocal, juego.obtenerNumeroCarta(cartaRobada), idToken);
            }

            if (haPescado) {
                narrarGlobal("¡"
                        + nombres.getOrDefault(uidLocal, uidLocal)
                        + " ha pescado el " + numeroRobado
                        + "! Mantiene el turno.");
                PauseTransition delay = new PauseTransition(Duration.seconds(1));
                delay.setOnFinished(ev -> {
                    try {
                        bd.actualizarTurno(codigoSala, uidLocal, idToken);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
                delay.play();
            } else {
                narrarGlobal(nombres.getOrDefault(uidLocal, uidLocal)
                        + " no ha pescado. Pasa el turno.");
                PauseTransition delay = new PauseTransition(Duration.seconds(1));
                delay.setOnFinished(ev -> {
                    try {
                        bd.actualizarTurno(codigoSala,
                                obtenerSiguienteJugador(uidTurnoActual), idToken);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
                delay.play();
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            esperandoRobo = false;
            numeroPreguntadoAntesDeRobar = null;
            numeroSeleccionado = null;
            uiBloqueadaPorAccion = false;
            if (uidLocal.equals(uidTurnoActual)) {
                zonaCentro.setDisable(false);
                activarInteraccion();
            } else {
                zonaCentro.setDisable(true);
            }
        }
    }

    // =========================================================================
    //  GESTIÓN DE LA UI — exclusiva de Pescaito
    // =========================================================================
    private void activarInteraccion() {
        if (uiBloqueadaPorAccion) {
            return;
        }
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

}
