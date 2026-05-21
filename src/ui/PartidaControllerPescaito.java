package ui;

import i18n.IdiomaManager;
import java.io.IOException;
import java.util.*;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import partidaUTIL.Juego;
import partidaUTIL.JuegoPescaito;

/**
 * Controlador del modo de juego <strong>Pescaito Online</strong>.
 *
 * <p>
 * Extiende {@link PartidaControllerBase} e implementa toda la lógica específica
 * del Pescaito online: selección de número de carta, pregunta a un rival, robo
 * del mazo, detección de pescaito y gestión del turno.</p>
 *
 * <h2>Flujo de un turno completo</h2>
 * <ol>
 * <li>El jugador local hace clic en una de sus cartas -
 * {@link #onCartaLocalClick} registra el número seleccionado en
 * {@link #numeroSeleccionado}.</li>
 * <li>El jugador hace clic en la zona de un rival - {@link #onZonaRivalClick}
 * guarda el UID del objetivo en {@link #uidJugadorObjetivo} y llama a
 * {@link #ejecutarPregunta()}.</li>
 * <li>{@link #ejecutarPregunta()} llama al motor
 * {@link JuegoPescaito#preguntar} y delega el resultado a
 * {@link #procesarResultadoPregunta}.</li>
 * <li>Si acierta - el turno se mantiene y vuelve al paso 1.</li>
 * <li>Si falla - {@link #esperandoRobo} = true. El jugador debe clicar el
 * mazo.</li>
 * <li>El jugador hace clic en el mazo - {@link #onClickMazo()} -
 *       {@link #realizarRoboManual()}.</li>
 * <li>Si la carta robada coincide con la preguntada ("pesca") - mantiene turno.
 * Si no - se pasa el turno al siguiente jugador.</li>
 * </ol>
 *
 * <h2>Punto de entrada del turno</h2>
 * <p>
 * En Pescaito el turno no se inicia desde {@link #onCambioTurno(String)} sino
 * desde {@link #actualizarDesdeModelo}, que se llama cada vez que Firebase
 * detecta un cambio en el estado de la partida. Cuando el modelo se actualiza y
 * {@code uidLocal == uidTurnoActual}, se invoca {@link #iniciarTurnoLocal()}.
 * Esto evita un bug en el que ocurría un bucle de turnos infinitos si el turno
 * se iniciaba directamente desde el listener de cambio de turno.</p>
 *
 * <h2>Bloqueo de la UI</h2>
 * <p>
 * Hay dos mecanismos de bloqueo complementarios:</p>
 * <ul>
 * <li>{@link #esperandoRobo}: bloquea todas las zonas de rivales y la carta
 * local. Solo permite clicar el mazo.</li>
 * <li>{@link #uiBloqueadaPorAccion}: bloquea la UI durante el procesamiento de
 * una pregunta o un robo (evita doble clic accidental).</li>
 * </ul>
 *
 * @author Javier Coronilla Castellano
 */
public class PartidaControllerPescaito extends PartidaControllerBase {

    // =========================================================================
    //  ESTADO EXCLUSIVO DE PESCAITO
    // =========================================================================
    /**
     * Número de carta seleccionado por el jugador local en el turno actual. Se
     * establece en {@link #onCartaLocalClick(String)} al hacer clic en una
     * carta propia, y se limpia a {@code null} al terminar cada acción
     * (pregunta o robo). {@code null} indica que no hay ningún número elegido.
     */
    private Integer numeroSeleccionado = null; // integer, ya que nos interesa el estado de null.

    /**
     * Número que el jugador preguntó antes de fallar y tener que robar. Se
     * guarda en {@link #procesarResultadoPregunta} cuando el resultado es
     * {@code debeRobar = true}, para que {@link #realizarRoboManual()} pueda
     * compararlo con la carta robada y determinar si "pescó". {@code null}
     * cuando no hay robo pendiente.
     */
    private Integer numeroPreguntadoAntesDeRobar = null;

    /**
     * {@code true} cuando el jugador local falló una pregunta y debe robar del
     * mazo antes de que su turno pase al siguiente jugador. Mientras sea
     * {@code true}, las zonas de rivales y la carta local están bloqueadas y
     * solo el mazo ({@code imgMazo}) está activo.
     */
    private boolean esperandoRobo = false;

    /**
     * {@code true} mientras se está procesando una pregunta o un robo. Bloquea
     * nuevas interacciones del jugador para evitar que un doble clic ejecute la
     * misma acción dos veces. Se establece a {@code true} al inicio de
     * {@link #ejecutarPregunta()} y de {@link #realizarRoboManual()}, y se
     * restaura a {@code false} en el bloque {@code finally} de cada uno.
     */
    private boolean uiBloqueadaPorAccion = false;

    /**
     * UID del rival al que el jugador local va a preguntar. Se establece en
     * {@link #onZonaRivalClick(String)} cuando el jugador hace clic en la zona
     * de un rival, y se limpia a {@code null} al finalizar la pregunta (tanto
     * en éxito como en fallo), en el bloque {@code finally} de
     * {@link #ejecutarPregunta()}.
     */
    private String uidJugadorObjetivo = null;

    // =========================================================================
    //  HOOKS DE LA BASE
    // =========================================================================
    /**
     * Crea el motor {@link JuegoPescaito} solo si el modo es "Pescaito".
     *
     * @param modo cadena del modo leída de Firebase
     * @return nueva instancia de {@link JuegoPescaito}, o {@code null} si el
     * modo no coincide
     */
    @Override
    protected Juego crearJuego(String modo) {
        if ("Pescaito".equals(modo)) {
            return new JuegoPescaito();
        }
        return null; // modo no reconocido en este controlador
    }

    /**
     * Pescaito no necesita listeners adicionales más allá de los que ya
     * registra la clase base (turno, partida, narrador, volverSala). El
     * listener de turno de la base llama a {@link #onCambioTurno(String)}, que
     * en Pescaito solo narra; el inicio real del turno ocurre en
     * {@link #actualizarDesdeModelo}.
     */
    @Override
    protected void registrarListenersPropios() {
        // Sin listeners adicionales.
    }

    /**
     * Cuando cambia el turno, narra globalmente el nombre del jugador activo.
     *
     * <p>
     * <strong>Importante:</strong> {@link #iniciarTurnoLocal()} NO se llama
     * aquí, sino desde {@link #actualizarDesdeModelo}. Si se llamara aquí, el
     * cambio de turno podría disparar el inicio del turno antes de que las
     * manos y la baraja estén sincronizadas con Firebase, causando un bucle de
     * turnos infinito o un estado inconsistente.</p>
     *
     * @param nuevoTurno UID del jugador al que le toca el turno
     */
    @Override
    protected void onCambioTurno(String nuevoTurno) {
        narrarGlobal(IdiomaManager.get("pescaitoOnline.global.turnoDe",
                nombres.getOrDefault(nuevoTurno, ""))
        );

    }

    /**
     * El jugador hace clic en el mazo de robo.
     *
     * <p>
     * Validaciones previas:</p>
     * <ol>
     * <li>Debe ser el turno del jugador local.</li>
     * <li>Debe estar en estado {@link #esperandoRobo} = {@code true}; si no, no
     * tiene sentido robar (no ha fallado ninguna pregunta aún).</li>
     * </ol>
     * <p>
     * Si ambas condiciones se cumplen, delega en
     * {@link #realizarRoboManual()}.</p>
     */
    @Override
    protected void onClickMazo() {
        if (!uidLocal.equals(uidTurnoActual)) {
            narrarPrivado(uidLocal, IdiomaManager.get("pescaitoOnline.privado.noEsTuTurno"));
            return;
        }
        if (!esperandoRobo) {
            narrarPrivado(uidLocal, IdiomaManager.get("pescaitoOnline.privado.noPuedesRobar"));
            return;
        }
        realizarRoboManual();
    }

    /**
     * El jugador hace clic en la zona de un rival para hacerle una pregunta.
     *
     * <p>
     * Validaciones en orden:</p>
     * <ol>
     * <li>Debe ser el turno del jugador local.</li>
     * <li>No debe estar esperando robar (primero roba, luego puede
     * preguntar).</li>
     * <li>Debe haber seleccionado un número previamente con
     * {@link #onCartaLocalClick(String)}.</li>
     * <li>El rival no puede ser {@code null} ni el propio jugador local.</li>
     * <li>El rival debe tener cartas en mano.</li>
     * </ol>
     *
     * <p>
     * Caso especial: si el rival no tiene cartas pero ningún otro rival las
     * tiene tampoco y la baraja no está vacía, se pasa el turno
     * automáticamente. Si la baraja sí está vacía, se narra que el rival no
     * tiene cartas sin pasar el turno.</p>
     *
     * @param uidRival UID del rival cuya zona fue pulsada
     */
    @Override
    protected void onZonaRivalClick(String uidRival) {

        if (!uidLocal.equals(uidTurnoActual)) {
            narrarPrivado(uidLocal, IdiomaManager.get("pescaitoOnline.privado.noEsTuTurno"));
            return;
        }
        if (esperandoRobo) {
            narrarPrivado(uidLocal, IdiomaManager.get("pescaitoOnline.privado.debesRobar"));
            return;
        }
        if (numeroSeleccionado == null) {
            narrarPrivado(uidLocal, IdiomaManager.get("pescaitoOnline.privado.seleccionaNumero"));
            return;
        }
        if (uidRival == null || uidRival.equals(uidLocal)) {
            return;
        }

        List<String> manoObjetivo = manos.get(uidRival);
        if (manoObjetivo == null || manoObjetivo.isEmpty()) {
            // El rival no tiene cartas: comprobar si hay algún otro rival con cartas
            boolean hayOtros = manos.entrySet().stream() // usamos un stream para buscar a algún jugador cuya mano no sea nula y no esté vacía
                    .anyMatch(e -> !e.getKey().equals(uidLocal) // pero nosotros no contamos
                    && e.getValue() != null && !e.getValue().isEmpty());
            if (!hayOtros && !baraja.isEmpty()) {
                // Nadie tiene cartas pero hay baraja - pasar turno automáticamente
                // (los demás rrobarán en su turno con ROBAR_AUTOMATICO)
                narrarPrivado(uidLocal, IdiomaManager.get("pescaitoOnline.privado.noRivalesConCartas"));
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

    /**
     * El jugador hace clic en una de sus propias cartas para seleccionar el
     * número.
     *
     * <p>
     * Extrae el número de la ruta de imagen con
     * {@link Juego#obtenerNumeroCarta} y lo guarda en
     * {@link #numeroSeleccionado}. Narra el número elegido al jugador local de
     * forma privada (los demás no lo ven).</p>
     *
     * <p>
     * Validaciones: debe ser el turno del jugador local y no debe estar
     * esperando robar.</p>
     *
     * @param rutaCarta ruta de imagen de la carta pulsada (p.ej.
     * {@code "/ui/.../coronas_7.png"})
     */
    @Override
    protected void onCartaLocalClick(String rutaCarta) {
        if (!uidLocal.equals(uidTurnoActual)) {
            narrarPrivado(uidLocal, IdiomaManager.get("pescaitoOnline.privado.noEsTuTurno"));
            return;
        }
        if (esperandoRobo) {
            narrarPrivado(uidLocal, IdiomaManager.get("pescaitoOnline.privado.debesRobar"));
            return;
        }

        int numero = juego.obtenerNumeroCarta(rutaCarta); // extraer número de la ruta
        numeroSeleccionado = numero;
        narrarPrivado(uidLocal, IdiomaManager.get("pescaitoOnline.privado.seleccionNumero", numero));
    }

    /**
     * Construye los datos del popup de fin de partida leyendo los pescaitos de
     * Firebase.
     *
     * <p>
     * En Pescaito la puntuación real no está en las manos finales sino en el
     * nodo {@code partida/pescaitos} de Firebase, donde cada pescaito se
     * registró con {@link firebase.BDPartidaService#registrarPescaito} durante
     * la partida. Por eso se lee directamente de Firebase en lugar de
     * calcularlo desde las manos.</p>
     *
     * <p>
     * Pasos:</p>
     * <ol>
     * <li>Leer el mapa de pescaitos de Firebase con
     * {@link firebase.BDPartidaService#leerPescaitos}.</li>
     * <li>Convertirlo a {@code Map<UID, cantidad>} con
     * {@link JuegoPescaito#calcularPuntuacionesDesdeBD}.</li>
     * <li>Encontrar la puntuación máxima con {@code Stream.max}.</li>
     * <li>Recopilar todos los jugadores con esa puntuación (puede haber
     * empate).</li>
     * <li>Construir el texto de resultado según si hay 1 ganador o empate.</li>
     * </ol>
     *
     * <p>
     * Si ocurre cualquier excepción (p.ej. Firebase no responde), se devuelve
     * un popup de fallback con texto genérico para que la partida no quede
     * bloqueada.</p>
     *
     * @return {@link DatosPopUp} con icono, resultado y detalle localizados
     */
    @Override
    protected DatosPopUp construirDatosPopUpFinal() {
        try {
            // Leer pescaitos desde Firebase
            Map<String, Map<String, Object>> pescaitosBD = bd.leerPescaitos(codigoSala, idToken);

            // Convertir BD  puntuaciones reales
            Map<String, Integer> puntuaciones
                    = ((JuegoPescaito) juego).calcularPuntuacionesDesdeBD(pescaitosBD);

            // Encontrar la puntuación más alta; orElse(0) si nadie tiene pescaitos
            int maxPescaitos = puntuaciones.values().stream().max(Integer::compare).orElse(0);

            // Recopilar todos los jugadores con esa puntuación (cubre el caso de empate)
            List<String> ganadores = puntuaciones.entrySet().stream()
                    .filter(e -> e.getValue() == maxPescaitos)
                    .map(Map.Entry::getKey)
                    .toList();

            String resultado;
            String detalle;
            String icono;

            // Construir textos
            if (ganadores.size() == 1) { // Un único ganador

                String uidGanador = ganadores.get(0);
                String nombre = nombres.getOrDefault(uidGanador, "Jugador");

                resultado = IdiomaManager.get("pescaitoOnline.popUpFinal.victoria", nombre);

                detalle = IdiomaManager.get("pescaitoOnline.popUpFinal.detalleVictoria", maxPescaitos);

                icono = "/ui/graphicResources/imagenes/imgGanador.png";

            } else { // Empate entre varios jugadores con la misma puntuación

                resultado = IdiomaManager.get("pescaitoOnline.popUpFinal.empate");

                detalle = IdiomaManager.get("pescaitoOnline.popUpFinal.detalleEmpate", maxPescaitos);

                icono = "/ui/graphicResources/imagenes/imgEmpate.png";
            }

            return new DatosPopUp(icono, resultado, detalle);

        } catch (Exception e) {
            e.printStackTrace();
            // Fallback: si falla la lectura de Firebase
            return new DatosPopUp("/ui/graphicResources/imagenes/imgEmpate.png",
                    IdiomaManager.get("pescaitoOnline.popUpFinal.finPartida"),
                    ""
            );
        }
    }

    // =========================================================================
    //  ACTUALIZAR MODELO
    // =========================================================================
    /**
     * Sobreescritura que añade el inicio de turno local tras sincronizar el
     * modelo.
     *
     * <p>
     * La clase base sincroniza manos, baraja y descarte con Firebase. Esta
     * sobreescritura añade un paso extra: si tras la sincronización es el turno
     * del jugador local, invoca {@link #iniciarTurnoLocal()}.</p>
     *
     * <p>
     * <strong>Por qué aquí y no en {@link #onCambioTurno}:</strong>
     * {@code onCambioTurno} se dispara cuando cambia el nodo {@code turno} en
     * Firebase, pero en ese momento las manos y la baraja pueden no estar
     * actualizadas aún. Aquí se garantiza que el modelo ya está sincronizado
     * antes de evaluar qué acción tomar.</p>
     *
     * @param manosBD manos leídas de Firebase
     * @param barajaBD baraja leída de Firebase
     * @param descarteBD descarte leído de Firebase
     * @throws IOException si alguna escritura en Firebase falla
     */
    @Override
    public void actualizarDesdeModelo(Map<String, List<String>> manosBD,
            List<String> barajaBD,
            List<String> descarteBD) throws IOException {
        super.actualizarDesdeModelo(manosBD, barajaBD, descarteBD); // sincronizar modelo base

        // Solo iniciar el turno si es el turno del jugador local
        if (uidLocal.equals(uidTurnoActual)) {
            iniciarTurnoLocal();
        }
    }

    // =========================================================================
    //  INICIO DE TURNO
    // =========================================================================
    /**
     * Evalúa el estado del turno local y decide qué acción tomar.
     *
     * <p>
     * Este método es el punto de entrada del turno local. Se ejecuta cada vez
     * que {@link #actualizarDesdeModelo} confirma que es el turno del jugador
     * local. Evalúa el estado y actúa según el resultado de
     * {@link Juego#accionInicioTurno}:</p>
     *
     * <ul>
     * <li>{@link Juego.AccionTurno#PASAR_TURNO}: la mano del jugador está vacía
     * y la baraja también. Si nadie tiene cartas, finalizar la partida. Si
     * alguien las tiene, narrar y pasar el turno con 1 segundo de delay para
     * que el mensaje sea legible antes del cambio.</li>
     * <li>{@link Juego.AccionTurno#ROBAR_AUTOMATICO}: la mano está vacía pero
     * hay baraja. Robar automáticamente una carta y publicar en Firebase.</li>
     * <li>{@link Juego.AccionTurno#JUGAR_NORMAL}: tiene cartas. Verificar si
     * hay rivales con cartas a quien preguntar. Si no hay, pasar turno. Si los
     * hay, activar la interacción para que el jugador juegue.</li>
     * </ul>
     *
     * <p>
     * Guardias de entrada: sale inmediatamente si el reparto inicial no está
     * hecho, si la partida ya terminó, o si hay un robo o acción pendiente.</p>
     *
     * @throws IOException si las llamadas a Firebase fallan
     */
    private void iniciarTurnoLocal() throws IOException {

        // Bloque de guardias iniciales
        if (!repartoInicialHecho) {
            activarInteraccion(); // solo habilitar UI antes del primer reparto
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

        switch (accion) { // Valoramos que hacer en base a la acción que toca
            case PASAR_TURNO:
                // Mano vacía y baraja vacía: verificar si alguien más tiene cartas
                boolean alguienTieneCartas = manos.values().stream()
                        .anyMatch(m -> m != null && !m.isEmpty());
                if (!alguienTieneCartas && baraja.isEmpty()) {
                    finalizarPartida(); // Si nadie tiene cartas y baraja vacía - finalizar partida
                    return;
                }
                narrarGlobal(IdiomaManager.get("pescaitoOnline.global.noCartasNoRobar",
                        nombres.getOrDefault(uidLocal, uidLocal))
                );
                // PauseTransition de 1s: dar tiempo al jugador para leer el mensaje
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
                // Mano vacía pero hay baraja: robar una carta automáticamente
                narrarGlobal(IdiomaManager.get("pescaitoOnline.global.roboAutomatico",
                        nombres.getOrDefault(uidLocal, uidLocal))
                );

                juego.robarCarta(uidLocal, manos, baraja); // actualizar estado local
                // Pubcliamos los cambios en firebase para que todos los clientes lo vean
                bd.actualizarMano(codigoSala, uidLocal, manos.get(uidLocal), idToken);
                bd.actualizarBaraja(codigoSala, baraja, idToken);
                break;

            case JUGAR_NORMAL:
            // Tiene cartas: verificar si hay rivales a quien preguntar
            default:
                // Comprobar si hay algún rival con cartas a quien preguntar
                boolean hayRivalesConCartas = manos.entrySet().stream()
                        .anyMatch(e -> !e.getKey().equals(uidLocal)
                        && e.getValue() != null
                        && !e.getValue().isEmpty());

                if (!hayRivalesConCartas) {
                    // Nadie tiene cartas - comprobar si hay baraja
                    if (!baraja.isEmpty()) {
                        // Hay baraja pero nadie a quien preguntar - pasar turno
                        // Los demás jugadores robarán automáticamente cuando les llegue
                        narrarPrivado(uidLocal,
                                IdiomaManager.get("pescaitoOnline.privado.noRivalesConCartasTurno"));
                        narrarGlobal(IdiomaManager.get("pescaitoOnline.global.pasaSinRivales",
                                nombres.getOrDefault(uidLocal, "Jugador"))
                        );
                        bd.actualizarTurno(codigoSala,
                                obtenerSiguienteJugador(uidTurnoActual), idToken);
                    }
                    return;
                }

                // Hay rivales con cartas - juego normal
                activarInteraccion();
                break;
        }
    }

    /**
     * Calcula el siguiente jugador en el orden global que tenga al menos una
     * carta.
     *
     * <p>
     * Itera hasta {@code ordenJugadoresGlobal.size()} veces (máximo una vuelta
     * completa) buscando el primer jugador con mano no vacía. Si ninguno tiene
     * cartas, devuelve el siguiente en el orden normal como fallback.</p>
     *
     * @return UID del siguiente jugador vivo con cartas, o el siguiente en
     * orden si nadie tiene
     */
    private String obtenerSiguienteJugadorConCartas() {
        String candidato = obtenerSiguienteJugador(uidTurnoActual);
        for (int i = 0; i < ordenJugadoresGlobal.size(); i++) {
            List<String> mano = manos.get(candidato);
            if (mano != null && !mano.isEmpty()) {
                return candidato; // este jugador tiene cartas
            }
            candidato = obtenerSiguienteJugador(candidato);
        }
        return obtenerSiguienteJugador(uidTurnoActual); // fallback: orden normal
    }

    /**
     * Comprueba si la partida ha terminado delegando en el motor. Guarda
     * defensiva contra {@code juego == null} durante la inicialización.
     *
     * @return {@code true} si la partida ha terminado según
     * {@link Juego#haTerminado}
     */
    private boolean esFinDePartida() {
        return juego != null && juego.haTerminado(manos, baraja, descarte);
    }

    // =========================================================================
    //  FLUJO DE PREGUNTA
    // =========================================================================
    /**
     * Ejecuta la pregunta del jugador local al rival seleccionado.
     *
     * <p>
     * Validaciones previas a la ejecución (guardias de entrada):</p>
     * <ul>
     * <li>Debe ser el turno local.</li>
     * <li>No debe estar esperando robar.</li>
     * <li>Debe haber número y rival seleccionados.</li>
     * <li>El motor no puede ser null.</li>
     * </ul>
     *
     * <p>
     * Durante la ejecución establece
     * {@link #uiBloqueadaPorAccion} = {@code true} y deshabilita
     * {@code zonaCentro} para evitar doble ejecución.</p>
     *
     * <p>
     * Llama a {@link JuegoPescaito#preguntar} que resuelve la lógica completa y
     * devuelve un mapa de resultados. Delega el procesamiento en
     * {@link #procesarResultadoPregunta}.</p>
     *
     * <p>
     * El bloque {@code finally} garantiza que {@link #uiBloqueadaPorAccion} se
     * restaura y la UI se reactiva aunque ocurra una excepción, evitando que el
     * juego quede bloqueado permanentemente.</p>
     */
    private void ejecutarPregunta() {

        // Bloque de guardias iniciales
        if (!uidLocal.equals(uidTurnoActual)) {
            narrarPrivado(uidLocal, IdiomaManager.get("pescaitoOnline.privado.noEsTuTurno"));
            return;
        }
        if (esperandoRobo) {
            narrarPrivado(uidLocal, IdiomaManager.get("pescaitoOnline.privado.debesRobar"));
            return;
        }
        if (numeroSeleccionado == null) {
            narrarPrivado(uidLocal, IdiomaManager.get("pescaitoOnline.privado.noHasSeleccionadoNumero"));
            return;
        }
        if (uidJugadorObjetivo == null) {
            narrarPrivado(uidLocal, IdiomaManager.get("pescaitoOnline.privado.noHasSeleccionadoJugador"));
            return;
        }
        if (juego == null) {
            return;
        }

        uiBloqueadaPorAccion = true;
        zonaCentro.setDisable(true); // Bloquear el mazo durante la pregunta

        narrarPrivado(uidLocal, IdiomaManager.get("pescaitoOnline.privado.hasPreguntado",
                nombres.get(uidJugadorObjetivo),
                numeroSeleccionado)
        );

        try {
            // Llamar al motor: resuelve transferencia de cartas y detección de pescaito
            Map<String, Object> resultado = ((JuegoPescaito) juego).preguntar( // almacenamos en mapa
                    uidLocal, uidJugadorObjetivo, numeroSeleccionado, manos, baraja, descarte);
            procesarResultadoPregunta(resultado); // procesamos resultado de pregunta
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // Siempre limpiar el objetivo y desbloquear, haya error o no
            uidJugadorObjetivo = null;
            uiBloqueadaPorAccion = false;
            if (uidLocal.equals(uidTurnoActual) && !esperandoRobo) {
                activarInteraccion();
            }
            if (!esperandoRobo) {
                zonaCentro.setDisable(false); // solo desbloquear el mazo si no hay robo pendiente
            }
        }
    }

    /**
     * Procesa el mapa de resultados devuelto por
     * {@link JuegoPescaito#preguntar} y actualiza Firebase y la UI según el
     * resultado.
     *
     * <p>
     * Extrae los valores del mapa con cast explícito. Los tipos son los que
     * garantiza {@link JuegoPescaito#preguntar}: {@code boolean} para acierto,
     * pescaito, debeRobar, mantieneTurno; {@code int} para cartasRecibidas y
     * numeroPescaito.</p>
     *
     * <p>
     * Flujos posibles:</p>
     * <ul>
     * <li><strong>Acierto + pescaito:</strong> narrar ambos eventos, registrar
     * el pescaito en Firebase y actualizar el descarte.</li>
     * <li><strong>Acierto sin pescaito:</strong> narrar el acierto y narrar
     * privadamente que mantiene el turno.</li>
     * <li><strong>Fallo + debe robar, baraja vacía:</strong> narrar, pasar
     * turno con delay y limpiar el estado de robo.</li>
     * <li><strong>Fallo + debe robar, baraja con cartas:</strong> guardar el
     * número preguntado, establecer {@link #esperandoRobo} = {@code true},
     * desactivar interacción excepto el mazo y retornar sin actualizar
     * turno.</li>
     * </ul>
     *
     * <p>
     * Al final (si no hay robo pendiente), actualiza ambas manos en Firebase y
     * publica el nuevo turno.</p>
     *
     * @param resultado mapa devuelto por {@link JuegoPescaito#preguntar}
     * @throws IOException si las llamadas a Firebase fallan
     */
    private void procesarResultadoPregunta(Map<String, Object> resultado) throws IOException {

        // Bloque de guardias iniciales
        if (!uidLocal.equals(uidTurnoActual)) {
            return;
        }
        if (esperandoRobo) {
            return;
        }

        // Extraemos todos los campos del mapa del resultado con cast explícito
        boolean acierto = (boolean) resultado.get("acierto");
        boolean pescaito = (boolean) resultado.get("pescaito");
        boolean debeRobar = (boolean) resultado.get("debeRobar");
        boolean mantieneTurno = (boolean) resultado.get("mantieneTurno");
        int cartasRecibidas = (int) resultado.get("cartasRecibidas");

        if (acierto) {
            // Mensaje global que narra el resultado de la pregunta para todos los clientes
            narrarGlobal(
                    IdiomaManager.get(
                            "pescaitoOnline.global.aciertoPregunta",
                            nombres.getOrDefault(uidLocal, uidLocal),
                            nombres.getOrDefault(uidJugadorObjetivo, uidJugadorObjetivo),
                            numeroSeleccionado,
                            cartasRecibidas)
            );
            if (pescaito) { // Si se produce pescaito

                int numeroPescaito = (int) resultado.get("numeroPescaito"); // recogemos numero que produce el pescaito

                narrarGlobal(IdiomaManager.get("pescaitoOnline.global.pescaitoAcierto",
                        nombres.getOrDefault(uidLocal, uidLocal),
                        numeroPescaito)
                );

            } else if (mantieneTurno) { // Si mantiene turno

                narrarPrivado(uidLocal, IdiomaManager.get("pescaitoOnline.privado.mantienesTurno"));

            }
        } else { // Si falla

            narrarGlobal(IdiomaManager.get("pescaitoOnline.global.falloPregunta",
                    nombres.getOrDefault(uidLocal, uidLocal),
                    nombres.getOrDefault(uidJugadorObjetivo, uidJugadorObjetivo),
                    numeroSeleccionado)
            );

            if (debeRobar) { // Debe robar

                narrarPrivado(uidLocal, IdiomaManager.get("pescaitoOnline.privado.debesRobarUna"));

            }
        }

        if (pescaito) { // Persisitimos pescaito en BD
            int numeroPescaito = (int) resultado.get("numeroPescaito");
            bd.registrarPescaito(codigoSala, uidLocal, numeroPescaito, idToken);
            bd.actualizarDescarte(codigoSala, descarte, idToken);
        }

        if (debeRobar) { // Gestion del robo pendiente
            if (baraja.isEmpty()) {

                // Baraja vacía: no puede robar - pasar turno directametente
                narrarGlobal(IdiomaManager.get("pescaitoOnline.global.noPuedeRobarBarajaVacia",
                        nombres.getOrDefault(uidLocal, uidLocal))
                );

                // PauseTransition: dar tiempo al jugador para leer el mensaje
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

                // Limpiar estado de robo
                numeroSeleccionado = null;
                numeroPreguntadoAntesDeRobar = null;
                esperandoRobo = false;
                return;
            }
            // Baraja con cartas: activar el estado de espera de robo
            numeroPreguntadoAntesDeRobar = numeroSeleccionado; // guardar para comprobar al robar
            esperandoRobo = true;
            desactivarInteraccion(); // bloquear zonas de rivales y carta local

            // Solo dejar activo el mazo para que el jugador pueda robar
            zonaCentro.setDisable(false);
            imgMazo.setDisable(false);
            imgMazo.setOpacity(1.0);
            uiBloqueadaPorAccion = false;
            return; // no actualizar turno aún: el jugador debe robar primero
        }

        // Actualizamos manos de ambos jugadores en Firebase (el preguntador y el preguntado)
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

    /**
     * El jugador local roba una carta del mazo tras haber fallado una pregunta.
     *
     * <p>
     * Pasos:</p>
     * <ol>
     * <li>Validar que es el turno local y que {@link #esperandoRobo} es
     * {@code true}.</li>
     * <li>Bloquear todas las zonas para evitar interacciones durante el
     * robo.</li>
     * <li>Llamar a {@link JuegoPescaito#robarCarta} para actualizar el estado
     * local.</li>
     * <li>Identificar la carta robada como la última de la mano
     * ({@code mano.get(mano.size() - 1)}) porque
     * {@link JuegoPescaito#robarCarta} la añade al final con
     * {@code mano.add(carta)}.</li>
     * <li>Publicar la mano actualizada y la baraja en Firebase.</li>
     * <li>Comprobar si "pescó" ({@link JuegoPescaito#haPescadoAlRobar}) y si
     * completó un pescaito ({@link JuegoPescaito#esPescaitoPorRobo}).</li>
     * <li>Si pescó: publicar el turno con el mismo UID (mantiene turno) tras
     * 1s.</li>
     * <li>Si no pescó: publicar el turno con el siguiente jugador tras 1s.</li>
     * </ol>
     *
     * <p>
     * El delay de 1 segundo con {@link PauseTransition} da tiempo al jugador
     * para leer el resultado antes de que el turno cambie y la UI se actualice.
     * El bloque {@code finally} siempre limpia el estado de robo y reactiva la
     * UI.</p>
     */
    private void realizarRoboManual() {

        // seguridad inicial
        if (!uidLocal.equals(uidTurnoActual)) {
            narrarPrivado(uidLocal, IdiomaManager.get("pescaitoOnline.privado.noEsTuTurno"));
            return;
        }
        if (!esperandoRobo) {
            narrarPrivado(uidLocal, IdiomaManager.get("pescaitoOnline.privado.noEstasObligadoRobar"));
            return;
        }

        uiBloqueadaPorAccion = true;

        // Bloquear todas las zonas durante el robo
        zonaArriba.setDisable(true);
        zonaIzquierda.setDisable(true);
        zonaDerecha.setDisable(true);
        zonaAbajo.setDisable(true);

        try {
            ((JuegoPescaito) juego).robarCarta(uidLocal, manos, baraja);
            List<String> mano = manos.get(uidLocal);
            // La carta robada es siempre la última: robarCarta() la añade con mano.add(carta)
            String cartaRobada = mano.get(mano.size() - 1);
            int numeroRobado = juego.obtenerNumeroCarta(cartaRobada);

            bd.actualizarMano(codigoSala, uidLocal, mano, idToken);
            bd.actualizarBaraja(codigoSala, baraja, idToken);
            narrarGlobal(IdiomaManager.get("pescaitoOnline.global.robaDelMazo",
                    nombres.getOrDefault(uidLocal, uidLocal))
            );

            // Comprobar si la carta robada coincide con el número preguntado ("pesca")
            boolean haPescado = ((JuegoPescaito) juego)
                    .haPescadoAlRobar(cartaRobada, numeroPreguntadoAntesDeRobar);

            // Comporbar si el robo completó un pescaito (grupo de 4 cartas del mismo número)
            boolean pescaito = ((JuegoPescaito) juego)
                    .esPescaitoPorRobo(uidLocal, manos, descarte);

            if (pescaito) { // Si es pescaito

                narrarGlobal(IdiomaManager.get("pescaitoOnline.global.pescaitoRobo",
                        nombres.getOrDefault(uidLocal, uidLocal),
                        juego.obtenerNumeroCarta(cartaRobada))
                );

                // Publicar mano actualizada (sin el pescaito), descarte y registro
                bd.actualizarMano(codigoSala, uidLocal, manos.get(uidLocal), idToken);
                bd.actualizarDescarte(codigoSala, descarte, idToken);
                bd.registrarPescaito(codigoSala, uidLocal, juego.obtenerNumeroCarta(cartaRobada), idToken);
            }

            if (haPescado) { // Si ha pescado

                narrarGlobal(IdiomaManager.get("pescaitoOnline.global.haPescadoMantiene",
                        nombres.getOrDefault(uidLocal, uidLocal),
                        numeroRobado)
                );

                // Delay de 1s para que el jugador lea el mensaje antes de que el turno cambie
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
                // No pescó: pasa el turno al siguiente jugador
                narrarGlobal(IdiomaManager.get("pescaitoOnline.global.noHaPescado",
                        nombres.getOrDefault(uidLocal, uidLocal))
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

            // Siempre limpiar el estado de robo al terminar, haya error o no
            esperandoRobo = false;
            numeroPreguntadoAntesDeRobar = null;
            numeroSeleccionado = null;
            uiBloqueadaPorAccion = false;

            // Reactivar UI si sigue siendo el turno local
            if (uidLocal.equals(uidTurnoActual)) {
                zonaCentro.setDisable(false);
                activarInteraccion();
            } else {
                zonaCentro.setDisable(true);
            }
        }
    }

    // =========================================================================
    //  GESTIÓN DE LA UI
    // =========================================================================
    /**
     * Habilita todas las zonas de interacción al inicio del turno del jugador
     * local.
     *
     * <p>
     * Restaura {@code opacity} a 1.0 además de habilitar las zonas, porque
     * {@link #desactivarInteraccion()} las oscurece a 0.5 para dar feedback
     * visual de que no son interactuables. Sin restaurar la opacidad quedarían
     * visualmente oscuras aunque estuvieran habilitadas.</p>
     *
     * <p>
     * Guardia: no hace nada si {@link #uiBloqueadaPorAccion} es {@code true},
     * para evitar que se reactive la UI mientras se está procesando una
     * acción.</p>
     */
    private void activarInteraccion() {

        // Guardia
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

    /**
     * Deshabilita y oscurece todas las zonas de interacción mientras el jugador
     * espera para robar o durante el procesamiento de una acción.
     *
     * <p>
     * La opacidad 0.5 da feedback visual inmediato al jugador de que las zonas
     * no son interactuables, sin necesidad de texto explicativo adicional.</p>
     */
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
