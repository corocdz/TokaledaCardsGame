package ui;

import i18n.IdiomaManager;
import java.io.IOException;
import java.util.*;
import javafx.animation.PauseTransition;
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
        narrarGlobal(
                IdiomaManager.get(
                        "pescaitoOnline.global.turnoDe",
                        nombres.getOrDefault(nuevoTurno, "")
                )
        );

    }

    @Override
    protected void onClickMazo() {
        if (!uidLocal.equals(uidTurnoActual)) {
            narrarPrivado(uidLocal,
                    IdiomaManager.get("pescaitoOnline.privado.noEsTuTurno"));
            return;
        }
        if (!esperandoRobo) {
            narrarPrivado(uidLocal,
                    IdiomaManager.get("pescaitoOnline.privado.noPuedesRobar"));
            return;
        }
        realizarRoboManual();
    }

    @Override
    protected void onZonaRivalClick(String uidRival) {
        if (!uidLocal.equals(uidTurnoActual)) {
            narrarPrivado(uidLocal,
                    IdiomaManager.get("pescaitoOnline.privado.noEsTuTurno"));
            return;
        }
        if (esperandoRobo) {
            narrarPrivado(uidLocal,
                    IdiomaManager.get("pescaitoOnline.privado.debesRobar"));
            return;
        }
        if (numeroSeleccionado == null) {
            narrarPrivado(uidLocal,
                    IdiomaManager.get("pescaitoOnline.privado.seleccionaNumero"));
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
                narrarPrivado(uidLocal,
                        IdiomaManager.get("pescaitoOnline.privado.noRivalesConCartas"));
                try {
                    bd.actualizarTurno(codigoSala, obtenerSiguienteJugador(uidTurnoActual), idToken);
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            } else {
                narrarPrivado(uidLocal,
                        IdiomaManager.get("pescaitoOnline.privado.rivalSinCartas"));
            }
            return;
        }

        uidJugadorObjetivo = uidRival;
        ejecutarPregunta();
    }

    @Override
    protected void onCartaLocalClick(String rutaCarta) {
        if (!uidLocal.equals(uidTurnoActual)) {
            narrarPrivado(uidLocal,
                    IdiomaManager.get("pescaitoOnline.privado.noEsTuTurno"));
            return;
        }
        if (esperandoRobo) {
            narrarPrivado(uidLocal,
                    IdiomaManager.get("pescaitoOnline.privado.debesRobar"));
            return;
        }

        int numero = juego.obtenerNumeroCarta(rutaCarta);
        numeroSeleccionado = numero;
        narrarPrivado(uidLocal,
                IdiomaManager.get("pescaitoOnline.privado.seleccionNumero", numero));
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

                resultado = IdiomaManager.get(
                        "pescaitoOnline.popUpFinal.victoria",
                        nombre
                );
                detalle = IdiomaManager.get(
                        "pescaitoOnline.popUpFinal.detalleVictoria",
                        maxPescaitos
                );

                icono = "/ui/graphicResources/imagenes/imgGanador.png";

            } else {
                // Empate
                resultado = IdiomaManager.get("pescaitoOnline.popUpFinal.empate");

                detalle = IdiomaManager.get(
                        "pescaitoOnline.popUpFinal.detalleEmpate",
                        maxPescaitos
                );

                icono = "/ui/graphicResources/imagenes/imgEmpate.png";
            }

            return new DatosPopUp(icono, resultado, detalle);

        } catch (Exception e) {
            e.printStackTrace();
            return new DatosPopUp(
                    "/ui/graphicResources/imagenes/imgEmpate.png",
                    IdiomaManager.get("pescaitoOnline.popUpFinal.finPartida"),
                    ""
            );
        }
    }

    // =========================================================================
    //  ACTUALIZAR MODELO - sobreescribir para llamar a iniciarTurnoLocal()
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
    //  INICIO DE TURNO - exclusivo de Pescaito
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
                narrarGlobal(
                        IdiomaManager.get(
                                "pescaitoOnline.global.noCartasNoRobar",
                                nombres.getOrDefault(uidLocal, uidLocal)
                        )
                );
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
                narrarGlobal(
                        IdiomaManager.get(
                                "pescaitoOnline.global.roboAutomatico",
                                nombres.getOrDefault(uidLocal, uidLocal)
                        )
                );
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
                    // Nadie tiene cartas - comprobar si hay baraja
                    if (!baraja.isEmpty()) {
                        // Hay baraja pero nadie a quien preguntar → pasar turno
                        // Los demás jugadores robarán automáticamente cuando les llegue
                        narrarPrivado(uidLocal,
                                IdiomaManager.get("pescaitoOnline.privado.noRivalesConCartasTurno"));
                        narrarGlobal(
                                IdiomaManager.get(
                                        "pescaitoOnline.global.pasaSinRivales",
                                        nombres.getOrDefault(uidLocal, "Jugador")
                                )
                        );
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
            narrarPrivado(uidLocal,
                    IdiomaManager.get("pescaitoOnline.privado.noEsTuTurno"));
            return;
        }
        if (esperandoRobo) {
            narrarPrivado(uidLocal,
                    IdiomaManager.get("pescaitoOnline.privado.debesRobar"));
            return;
        }
        if (numeroSeleccionado == null) {
            narrarPrivado(uidLocal,
                    IdiomaManager.get("pescaitoOnline.privado.noHasSeleccionadoNumero"));
            return;
        }
        if (uidJugadorObjetivo == null) {
            narrarPrivado(uidLocal,
                    IdiomaManager.get("pescaitoOnline.privado.noHasSeleccionadoJugador"));
            return;
        }
        if (juego == null) {
            return;
        }

        uiBloqueadaPorAccion = true;
        zonaCentro.setDisable(true);

        narrarPrivado(
                uidLocal,
                IdiomaManager.get(
                        "pescaitoOnline.privado.hasPreguntado",
                        nombres.get(uidJugadorObjetivo),
                        numeroSeleccionado
                )
        );

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
            narrarGlobal(
                    IdiomaManager.get(
                            "pescaitoOnline.global.aciertoPregunta",
                            nombres.getOrDefault(uidLocal, uidLocal),
                            nombres.getOrDefault(uidJugadorObjetivo, uidJugadorObjetivo),
                            numeroSeleccionado,
                            cartasRecibidas
                    )
            );
            if (pescaito) {
                int numeroPescaito = (int) resultado.get("numeroPescaito");
                narrarGlobal(
                        IdiomaManager.get(
                                "pescaitoOnline.global.pescaitoAcierto",
                                nombres.getOrDefault(uidLocal, uidLocal),
                                numeroPescaito
                        )
                );
            } else if (mantieneTurno) {
                narrarPrivado(
                        uidLocal,
                        IdiomaManager.get("pescaitoOnline.privado.mantienesTurno")
                );
            }
        } else {
            narrarGlobal(
                    IdiomaManager.get(
                            "pescaitoOnline.global.falloPregunta",
                            nombres.getOrDefault(uidLocal, uidLocal),
                            nombres.getOrDefault(uidJugadorObjetivo, uidJugadorObjetivo),
                            numeroSeleccionado
                    )
            );
            if (debeRobar) {
                narrarPrivado(
                        uidLocal,
                        IdiomaManager.get("pescaitoOnline.privado.debesRobarUna")
                );
            }
        }

        if (pescaito) {
            int numeroPescaito = (int) resultado.get("numeroPescaito");
            bd.registrarPescaito(codigoSala, uidLocal, numeroPescaito, idToken);
            bd.actualizarDescarte(codigoSala, descarte, idToken);
        }

        if (debeRobar) {
            if (baraja.isEmpty()) {
                narrarGlobal(
                        IdiomaManager.get(
                                "pescaitoOnline.global.noPuedeRobarBarajaVacia",
                                nombres.getOrDefault(uidLocal, uidLocal)
                        )
                );
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
            narrarPrivado(uidLocal,
                    IdiomaManager.get("pescaitoOnline.privado.noEsTuTurno"));
            return;
        }
        if (!esperandoRobo) {
            narrarPrivado(uidLocal,
                    IdiomaManager.get("pescaitoOnline.privado.noEstasObligadoRobar"));
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
            narrarGlobal(
                    IdiomaManager.get(
                            "pescaitoOnline.global.robaDelMazo",
                            nombres.getOrDefault(uidLocal, uidLocal)
                    )
            );

            boolean haPescado = ((JuegoPescaito) juego)
                    .haPescadoAlRobar(cartaRobada, numeroPreguntadoAntesDeRobar);
            boolean pescaito = ((JuegoPescaito) juego)
                    .esPescaitoPorRobo(uidLocal, manos, descarte);

            if (pescaito) {
                narrarGlobal(
                        IdiomaManager.get(
                                "pescaitoOnline.global.pescaitoRobo",
                                nombres.getOrDefault(uidLocal, uidLocal),
                                juego.obtenerNumeroCarta(cartaRobada)
                        )
                );
                bd.actualizarMano(codigoSala, uidLocal, manos.get(uidLocal), idToken);
                bd.actualizarDescarte(codigoSala, descarte, idToken);
                bd.registrarPescaito(codigoSala, uidLocal, juego.obtenerNumeroCarta(cartaRobada), idToken);
            }

            if (haPescado) {
                narrarGlobal(
                        IdiomaManager.get(
                                "pescaitoOnline.global.haPescadoMantiene",
                                nombres.getOrDefault(uidLocal, uidLocal),
                                numeroRobado
                        )
                );
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
                narrarGlobal(
                        IdiomaManager.get(
                                "pescaitoOnline.global.noHaPescado",
                                nombres.getOrDefault(uidLocal, uidLocal)
                        )
                );
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
    //  GESTIÓN DE LA UI - exclusiva de Pescaito
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
