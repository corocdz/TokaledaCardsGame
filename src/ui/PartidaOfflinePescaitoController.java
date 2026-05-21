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
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import partidaUTIL.Baraja;
import partidaUTIL.Carta;
import partidaUTIL.Juego;
import partidaUTIL.JuegoPescaito;

/**
 * Controlador del modo <strong>Pescaito Offline</strong> (humano contra IAs).
 *
 * <p>
 * Extiende {@link PartidaControllerBase} para reutilizar exactamente los mismos
 * abanicos de cartas, el layout visual de la mesa y el sistema de render del
 * modo online. Las diferencias respecto al controlador online son:</p>
 * <ul>
 * <li>No hay Firebase: todo el estado de la partida vive en memoria.</li>
 * <li>No hay listeners de polling: los turnos se gestionan localmente con
 * {@link PauseTransition}.</li>
 * <li>Los rivales son IAs que toman decisiones aleatorias con un delay
 * configurable.</li>
 * <li>Los pescaitos se acumulan en el mapa {@link #pescaitosPorJugador} en
 * lugar de registrarse en Firebase.</li>
 * </ul>
 *
 * <h2>Punto de entrada</h2>
 * <p>
 * No se usa el método {@link #init(String, String, String)} heredado de la
 * base. En su lugar se llama a {@link #iniciarOffline(int)} justo después de
 * {@code loader.load()}, pasando el número de IAs deseado (1, 2 o 3).</p>
 *
 * <h2>Flujo de un turno completo (humano)</h2>
 * <ol>
 * <li>Clic en carta propia - {@link #onCartaLocalClick} guarda el número en
 * {@link #numeroSeleccionado}.</li>
 * <li>Clic en zona de rival - {@link #onZonaRivalClick} llama a
 * {@link #ejecutarPreguntaHumano()}.</li>
 * <li>{@link #ejecutarPreguntaHumano} llama al motor y delega en
 * {@link #procesarResultado}.</li>
 * <li>Si acierta: mantiene o pasa turno según el resultado.</li>
 * <li>Si falla: establece {@link #esperandoRobo} = {@code true}. El jugador
 * debe clicar el mazo.</li>
 * <li>Clic en mazo -
 * {@link #onClickMazo()} - {@link #realizarRoboManual()}.</li>
 * </ol>
 *
 * <h2>Flujo de un turno de IA</h2>
 * <ol>
 * <li>{@link #iniciarTurnoJugador(String)} detecta que es una IA y llama a
 * {@link #programarTurnoIA()}.</li>
 * <li>Un {@link PauseTransition} de {@value #DELAY_IA_SEG} segundos simula el
 * "pensamiento" de la IA antes de actuar como si fuese un humano (o en modo
 * debug, un botón manual).</li>
 * <li>{@link #ejecutarTurnoIA(String)} elige un número aleatorio de su mano y
 * un rival aleatorio con cartas.</li>
 * <li>Si falla, {@link #ejecutarRoboIA(String)} realiza el robo
 * automáticamente.</li>
 * </ol>
 *
 * <h2>Modo debug de IA</h2>
 * <p>
 * Cuando {@link #MODO_DEBUG_IA} = {@code true}, en lugar del delay automático
 * se muestra un botón en pantalla que el desarrollador pulsa manualmente para
 * avanzar el turno de la IA. Útil para depurar el flujo de la partida paso a
 * paso sin esperar.</p>
 *
 * @author Javier Coronilla Castellano
 */
public class PartidaOfflinePescaitoController extends PartidaControllerBase {

    // =========================================================================
    //  CONSTANTES
    // =========================================================================
    /**
     * UID que identifica al jugador humano en toda la partida offline. Valor
     * fijo {@value}. Se usa como clave en los mapas de manos, pescaitos y
     * nombres para identificar al jugador local sin ambigüedad.
     */
    private static final String UID_JUGADOR = "jugador";

    /**
     * Prefijo del UID de cada IA. Las IAs tienen UIDs {@code "ia_1"}, {@code "ia_2"},
     * {@code "ia_3"}. Se usa en {@link #esIA(String)} para detectar si un UID
     * corresponde a una IA comprobando {@code uid.startsWith(PREFIJO_IA)}.
     */
    private static final String PREFIJO_IA = "ia_";

    /**
     * Segundos de delay antes de que la IA actúe en modo automático. Valor:
     * {@value}. Da tiempo al jugador humano a leer el narrador y ver el estado
     * de la mesa antes de que la IA tome su turno. Se aplica con un
     * {@link PauseTransition} en {@link #programarTurnoIA()}.
     */
    private static final double DELAY_IA_SEG = 3.0; // antes estaba en 5 

    /**
     * Controla el modo de debug de la IA.
     * <ul>
     * <li>{@code false} (producción): la IA actúa automáticamente tras el
     * delay.</li>
     * <li>{@code true} (debug): en lugar del delay, aparece un botón en
     * pantalla que el desarrollador pulsa manualmente para avanzar el turno de
     * la IA, permitiendo depurar el flujo paso a paso.</li>
     * </ul>
     * Cambiar a {@code false} antes de la entrega final.
     */
    private static final boolean MODO_DEBUG_IA = false; // cambiar a true para activar modo debug

    // =========================================================================
    //  ESTADO OFFLINE
    // =========================================================================
    /**
     * Acumulador de pescaitos por jugador. Clave: UID. Valor: cantidad de
     * grupos de 4 completados. Se actualiza con
     * {@code merge(uid, 1, Integer::sum)} cada vez que un jugador completa un
     * pescaito, tanto en {@link #procesarResultado} como en
     * {@link #iniciarTurnoJugador} (pescaito por robo automático) y
     * {@link #ejecutarRoboIA(String)}. Sustituye al registro en Firebase que
     * hace el modo online.
     */
    private final Map<String, Integer> pescaitosPorJugador = new HashMap<>();

    /**
     * Número de IAs que participan en la partida. Valor entre 1 y 3,
     * establecido en {@link #iniciarOffline(int)}. Define cuántos UIDs
     * {@code ia_1}, {@code ia_2}, {@code ia_3} se crean.
     */
    private int numIAs = 1; //  minimo es 1 por regla

    /**
     * {@code true} cuando el jugador humano falló una pregunta y debe robar del
     * mazo. Mientras sea {@code true}, las zonas de rivales están bloqueadas y
     * solo el mazo está activo. Se limpia en {@link #realizarRoboManual()}.
     */
    private boolean esperandoRobo = false;

    /**
     * {@code true} mientras se procesa una pregunta del humano. Bloquea nuevas
     * interacciones para evitar doble ejecución. Se establece al inicio de
     * {@link #ejecutarPreguntaHumano()} y se restaura al terminar.
     */
    private boolean uiBloqueada = false;

    /**
     * Número de carta seleccionado por el jugador humano. Se establece en
     * {@link #onCartaLocalClick(String)} y se limpia a {@code null} al terminar
     * cada acción. {@code null} indica que no hay número elegido aún.
     */
    private Integer numeroSeleccionado = null; // nos interesa el valor de null, así que debe ser Integer y no int

    /**
     * Número que el jugador humano preguntó antes de fallar y tener que robar.
     * Se guarda en {@link #procesarResultado} cuando {@code debeRobar = true},
     * para compararlo en {@link #realizarRoboManual()} con la carta robada y
     * determinar si "pescó". Se limpia al terminar el robo.
     */
    private Integer numeroPreguntadoAntesDeRobar = null;

    /**
     * UID del rival al que el jugador humano va a preguntar. Se establece en
     * {@link #onZonaRivalClick(String)} y se limpia al finalizar la pregunta.
     */
    private String uidJugadorObjetivo = null;

    /**
     * Referencia al botón de avance manual del turno de IA (solo en modo
     * debug). Se crea en {@link #mostrarBotonIAManual()} y se elimina en
     * {@link #ocultarBotonIAManual()}. {@code null} cuando no está visible.
     */
    private Button btnIAManual = null;

    /**
     * Mapa que guarda el número que cada IA preguntó antes de tener que robar.
     * Clave: UID de la IA. Valor: número preguntado en su último turno. Se
     * escribe en {@link #ejecutarTurnoIA(String)} antes de llamar a
     * {@link JuegoPescaito#preguntar}, y se lee y limpia en
     * {@link #ejecutarRoboIA(String)} para comprobar si la IA "pescó" al robar.
     */
    private final Map<String, Integer> numeroPreguntadoPorIA = new HashMap<>();

    // =========================================================================
    //  PUNTO DE ENTRADA
    // =========================================================================
    /**
     * Inicializa la partida offline. Debe llamarse justo después de
     * {@code loader.load()}, en lugar de {@link #init(String, String, String)}.
     *
     * <p>
     * Realiza toda la configuración que en el modo online hace {@link #init}
     * más los listeners de Firebase, pero de forma completamente local:</p>
     * <ol>
     * <li>Limita {@code numIAs} al rango válido [1, 3].</li>
     * <li>Asigna valores a los campos que la clase base necesita:
     * {@code uidLocal}, {@code codigoSala}, {@code idToken}.</li>
     * <li>Rellena el mapa {@link PartidaControllerBase#nombres} con los nombres
     * del jugador y de cada IA.</li>
     * <li>Construye {@link PartidaControllerBase#ordenJugadoresGlobal} con el
     * jugador humano primero y las IAs a continuación.</li>
     * <li>Inicializa el contador de pescaitos a 0 para cada participante.</li>
     * <li>Crea y baraja la baraja completa con {@link Baraja}.</li>
     * <li>Crea las manos vacías para todos los participantes.</li>
     * <li>Instancia {@link JuegoPescaito} y llama a
     * {@link JuegoPescaito#repartirCartas} para el reparto inicial.</li>
     * <li>Llama a {@code Platform.runLater} para que la UI esté renderizada
     * antes de configurar el layout y comenzar el primer turno.</li>
     * </ol>
     *
     * @param n número de IAs deseado; se ajusta automáticamente al rango [1, 3]
     */
    public void iniciarOffline(int n) {
        this.numIAs = Math.max(1, Math.min(3, n));

        // Campos requeridos por la clase base
        this.uidLocal = UID_JUGADOR;
        this.codigoSala = "offline";  // valor ficticio, no se usa en Firebase
        this.idToken = ""; // sin autenticación en modo offline

        // Nombres de los participantes
        nombres.put(UID_JUGADOR, "jugador"); // nombre por default del jugador para ambos idiomas
        for (int i = 1; i <= numIAs; i++) {
            nombres.put(PREFIJO_IA + i, "IA " + i); // nombre por default para las IAs. IA + num que la identifica
        }

        // Orden global de jugadores (la base lo usa para abanicos)
        ordenJugadoresGlobal.clear(); // lo limpiamos de manera inicial
        ordenJugadoresGlobal.add(UID_JUGADOR); // el humano siempre va en posición "abajo"
        for (int i = 1; i <= numIAs; i++) {
            ordenJugadoresGlobal.add(PREFIJO_IA + i);
        }

        // Contadores de pescaitos a 0 de manera inicial
        for (String uid : ordenJugadoresGlobal) {
            pescaitosPorJugador.put(uid, 0);
        }

        // Baraja completa barajada
        Baraja b = new Baraja();
        b.barajar();
        for (Carta c : b.getCartasRestantes()) {
            baraja.add(c.getRutaImagen()); // añadimos rutas de imagen al List<String> baraja de la base
        }

        // Manos vacías (la base las necesita inicializadas antes de repartir
        for (String uid : ordenJugadoresGlobal) {
            manos.put(uid, new ArrayList<>());
        }

        // Crear motor y reparto inicial
        juego = new JuegoPescaito();
        juego.repartirCartas(manos, baraja); // reparto
        repartoInicialHecho = true; // guardia de reparto

        // Turno inicial: el jugador humano empieza siempre
        uidTurnoActual = UID_JUGADOR;

        // Configurar eventos de interacción (métodos de la base)
        configurarEventosManoJugador();
        configurarEventosRobar();

        // Renderizar la UI en el hilo de JavaFX
        // Platform.runLater: garantizar que la escena esté completamente renderizada antes de configurar el layout y comenzar el turno
        Platform.runLater(() -> {
            Stage stage = (Stage) zonaAbajo.getScene().getWindow();
            if (stage != null) {
                configurarLayoutEscena(stage); // ajustar tamaño de la ventana al layout
            }
            actualizarInterfaz(); // renderizar abanicos y cartas iniciales
            narrarGlobal(IdiomaManager.get("pescaitoOffline.global.inicio", numIAs));
            iniciarTurnoJugador(UID_JUGADOR); // comenzar el primer turno del humano
        });
    }

    // =========================================================================
    //  SOBREESCRITURAS DE LA BASE - desactivar Firebase
    // =========================================================================
    /**
     * No se usa en el modo offline. Usar {@link #iniciarOffline(int)} en su
     * lugar. Sobreescribir con cuerpo vacío evita que la base intente conectar
     * con Firebase.
     */
    @Override
    public void init(String codigoSala, String uidLocal, String idToken) {
        // No usar - usar iniciarOffline() en su lugar
    }

    /**
     * No se usa en offline. El estado inicial ya se prepara en
     * {@link #iniciarOffline(int)}.
     */
    @Override
    protected void prepararEstadoInicial(Map<String, Object> partida) {
        // Sin Firebase: el estado se inicializa en iniciarOffline()
    }

    /**
     * En offline no hay Firebase ni listeners. Sobreescribir como vacío evita
     * que la base registre listeners de polling que intentarían acceder a
     * Firebase con un token vacío y generarían errores.
     */
    protected void registrarListenersComunes() {
        // Sin Firebase, sin listeners
    }

    /**
     * Sin listeners propios en Pescaito offline.
     */
    @Override
    protected void registrarListenersPropios() {
        // Sin listeners propios
    }

    /**
     * El orden de jugadores ya se cargó en {@link #iniciarOffline(int)}.
     * Sobreescribir como vacío evita que la base intente leer el orden de
     * Firebase.
     */
    @Override
    protected void cargarOrdenJugadoresGlobal() {
        // Ya se cargó en iniciarOffline()
    }

    /**
     * Finaliza la partida offline deshabilitando la UI y mostrando el popup.
     *
     * <p>
     * Guardia: si {@link PartidaControllerBase#partidaFinalizada} ya es
     * {@code true}, sale inmediatamente para evitar mostrar el popup dos veces
     * (puede ocurrir si {@link #verificarFin()} se llama desde varios puntos
     * casi simultáneamente).</p>
     *
     * <p>
     * Deshabilita todas las zonas de interacción y llama a
     * {@link PartidaControllerBase#mostrarPantallaFinal()} via
     * {@code Platform.runLater} para garantizar que se ejecuta en el hilo de
     * JavaFX.</p>
     */
    @Override
    protected void finalizarPartida() {
        // Guardia
        if (partidaFinalizada) {
            return;
        }
        partidaFinalizada = true;

        // Deshabilitar todas las zonas de interacción
        zonaArriba.setDisable(true);
        zonaIzquierda.setDisable(true);
        zonaDerecha.setDisable(true);
        zonaAbajo.setDisable(true);
        zonaCentro.setDisable(true);

        Platform.runLater(this::mostrarPantallaFinal); // popUp Final de partida
    }

    // =========================================================================
    //  HOOKS ABSTRACTOS DE LA BASE
    // =========================================================================
    /**
     * Crea siempre un {@link JuegoPescaito} independientemente del parámetro
     * modo, ya que este controlador solo gestiona el modo Pescaito.
     *
     * @param modo ignorado en el modo offline
     * @return nueva instancia de {@link JuegoPescaito}
     */
    @Override
    protected Juego crearJuego(String modo) {
        return new JuegoPescaito();
    }

    /**
     * En offline los turnos se gestionan localmente, no mediante el listener de
     * Firebase. Este método se sobreescribe vacío porque la base lo llamaría si
     * hubiera un listener de turno activo, pero en offline no lo hay.
     *
     * @param nuevoTurno ignorado
     */
    @Override
    protected void onCambioTurno(String nuevoTurno) {
        // En offline gestionamos los turnos nosotros, no por Firebase
    }

    /**
     * El jugador humano hace clic en el mazo para robar.
     *
     * <p>
     * Validaciones:</p>
     * <ol>
     * <li>Debe ser el turno del jugador humano (no de una IA).</li>
     * <li>Debe estar en estado {@link #esperandoRobo} = {@code true}.</li>
     * </ol>
     */
    @Override
    protected void onClickMazo() {

        if (!UID_JUGADOR.equals(uidTurnoActual)) { // si no es turno de humano, lo decimos
            narrarPrivado(UID_JUGADOR, IdiomaManager.get("pescaitoOffline.privado.noEsTuTurno"));
            return;
        }
        if (!esperandoRobo) { // si es su turno pero no tiene que robar, lo mostramos
            narrarPrivado(UID_JUGADOR, IdiomaManager.get("pescaitoOffline.privado.noPuedesRobar"));
            return;
        }
        realizarRoboManual(); // robo
    }

    /**
     * El jugador humano hace clic en la zona de un rival para preguntar.
     *
     * <p>
     * Validaciones en orden:</p>
     * <ol>
     * <li>Debe ser el turno del humano.</li>
     * <li>La UI no debe estar bloqueada por otra acción en curso.</li>
     * <li>No debe estar esperando robar.</li>
     * <li>Debe haber seleccionado un número previamente.</li>
     * <li>El rival no puede ser {@code null} ni el propio jugador.</li>
     * <li>El rival debe tener cartas; si no, verificar si hay otros rivales o
     * pasar turno automáticamente.</li>
     * </ol>
     *
     * @param uidRival UID del rival cuya zona fue pulsada
     */
    @Override
    protected void onZonaRivalClick(String uidRival) {

        // Bloque de validaciones en orden como dice arriba. Mostramos mensajes
        if (!UID_JUGADOR.equals(uidTurnoActual)) {
            narrarPrivado(UID_JUGADOR, IdiomaManager.get("pescaitoOffline.privado.noEsTuTurno"));
            return;
        }
        if (uiBloqueada) {
            narrarPrivado(UID_JUGADOR, IdiomaManager.get("pescaitoOffline.privado.espera"));
            return;
        }
        if (esperandoRobo) {
            narrarPrivado(UID_JUGADOR, IdiomaManager.get("pescaitoOffline.privado.debesRobar"));
            return;
        }
        if (numeroSeleccionado == null) {
            narrarPrivado(UID_JUGADOR, IdiomaManager.get("pescaitoOffline.privado.seleccionaNumero"));
            return;
        }
        if (uidRival == null || uidRival.equals(UID_JUGADOR)) { // Si uid rival es nulo o es igual que el del jugador, no hacemos nada
            return;
        }

        List<String> manoRival = manos.get(uidRival);
        if (manoRival == null || manoRival.isEmpty()) {
            boolean hayOtros = ordenJugadoresGlobal.stream() // El rival no tiene cartas: comprobar si hay algún otro rival con cartas (menos nosotros mismos)
                    .filter(u -> !u.equals(UID_JUGADOR))
                    .anyMatch(u -> {
                        List<String> m = manos.get(u);
                        return m != null && !m.isEmpty();
                    });
            if (!hayOtros && !baraja.isEmpty()) {
                // Nadie tiene cartas pero hay baraja - pasamos turnos
                narrarPrivado(UID_JUGADOR, IdiomaManager.get("pescaitoOffline.privado.noRivalesConCartas"));
                pasarTurno(UID_JUGADOR);
            } else {
                // Jugador escogido sin cartas, coger otro
                narrarPrivado(UID_JUGADOR, IdiomaManager.get("pescaitoOffline.privado.rivalSinCartas"));
            }
            return;
        }

        uidJugadorObjetivo = uidRival;
        ejecutarPreguntaHumano();
    }

    /**
     * El jugador humano hace clic en una de sus cartas para seleccionar el
     * número. Extrae el número con {@link Juego#obtenerNumeroCarta} y lo guarda
     * en {@link #numeroSeleccionado}. Narra el número elegido de forma privada.
     *
     * @param rutaCarta ruta de imagen de la carta pulsada
     */
    @Override
    protected void onCartaLocalClick(String rutaCarta) {
        if (!UID_JUGADOR.equals(uidTurnoActual)) {
            narrarPrivado(UID_JUGADOR, IdiomaManager.get("pescaitoOffline.privado.noEsTuTurno"));
            return;
        }
        if (esperandoRobo) { // debemos robar. No podemos intentar robar
            narrarPrivado(UID_JUGADOR, IdiomaManager.get("pescaitoOffline.privado.debesRobar"));
            return;
        }
        // Extraer el número de la ruta de imagen
        int numero = juego.obtenerNumeroCarta(rutaCarta);
        numeroSeleccionado = numero;
        narrarPrivado(UID_JUGADOR, IdiomaManager.get("pescaitoOffline.privado.seleccionNumero", numero));
    }

    // =========================================================================
    //  TURNO HUMANO
    // =========================================================================
    /**
     * Ejecuta la pregunta del jugador humano al rival seleccionado.
     *
     * <p>
     * Guarda el número y el objetivo en variables locales antes de limpiar los
     * campos del controlador. Importante porque {@link JuegoPescaito#preguntar}
     * puede modificar las manos, y si limpiáramos los campos antes de guardar
     * las referencias locales podríamos perder información necesaria para
     * narrar el resultado.</p>
     *
     * <p>
     * El bloque {@code uiBloqueada = true/false} evita que el jugador pulse
     * otra zona mientras se procesa la pregunta (aunque en el modo offline no
     * hay async real, es buena práctica mantenerlo por consistencia).</p>
     */
    private void ejecutarPreguntaHumano() {

        // Guardia inicial. numero seleccionado y uid objetivo necesarios
        if (numeroSeleccionado == null || uidJugadorObjetivo == null) {
            return;
        }

        uiBloqueada = true; // bloqueamos UI

        int numPreguntado = numeroSeleccionado;  // capturar antes de limpiar
        String objPreguntado = uidJugadorObjetivo; // capturar antes de limpiar

        narrarPrivado(UID_JUGADOR, IdiomaManager.get("pescaitoOffline.privado.preguntasPorNumero",
                nombres.get(objPreguntado),
                numPreguntado)
        );

        // Llamar al motor: resuelve la lógica completa de la pregunta
        Map<String, Object> res = pescaito().preguntar( // mapa del resultado de la pregunta
                UID_JUGADOR, objPreguntado, numPreguntado, manos, baraja, descarte);

        // Limpiar SIEMPRE tras preguntar, independientemente del resultado
        numeroSeleccionado = null;
        uidJugadorObjetivo = null;
        uiBloqueada = false;

        procesarResultado(UID_JUGADOR, objPreguntado, res, numPreguntado);

        // Reactivamos interacción si el turno sigue siendo del humano y no espera robar
        if (UID_JUGADOR.equals(uidTurnoActual) && !esperandoRobo) {
            activarInteraccion();
        }
    }

    /**
     * El jugador humano roba una carta del mazo tras haber fallado una
     * pregunta.
     *
     * <p>
     * La carta robada siempre es la última de la mano
     * ({@code mano.get(mano.size() - 1)}) porque
     * {@link JuegoPescaito#robarCarta} la añade al final con
     * {@code mano.add(carta)}.</p>
     *
     * <p>
     * {@code merge(uid, 1, Integer::sum)}: si la clave ya existe, suma 1 al
     * valor actual; si no existe, la crea con valor 1. Es equivalente a:
     * {@code map.put(uid, map.getOrDefault(uid, 0) + 1)}, pero más conciso.</p>
     *
     * <p>
     * Tras el robo siempre se llama a {@link #actualizarInterfaz()} y
     * {@link #logEstadoPartida(String)} para mantener la UI y el log
     * sincronizados.</p>
     */
    private void realizarRoboManual() {
        // Guardia inicial
        if (!esperandoRobo) {
            return;
        }

        // bloqueamos UI e interacciones
        uiBloqueada = true;
        desactivarInteraccion();

        // Método de robar
        pescaito().robarCarta(UID_JUGADOR, manos, baraja);
        List<String> mano = manos.get(UID_JUGADOR);
        // La carta robada es siempre la última: robarCarta() la añade con mano.add()
        String cartaRobada = mano.get(mano.size() - 1);
        int numRobado = juego.obtenerNumeroCarta(cartaRobada);

        boolean haPescado = pescaito().haPescadoAlRobar(cartaRobada, numeroPreguntadoAntesDeRobar);
        boolean pescaito = pescaito().esPescaitoPorRobo(UID_JUGADOR, manos, descarte);
        if (pescaito) {
            // Incrementar contador local (equivale a registrarPescaito en Firebase en el modo online)
            pescaitosPorJugador.merge(UID_JUGADOR, 1, Integer::sum); //  si la clave ya existe, suma 1 al valor actual; si no existe, la crea con valor 1
            narrarGlobal(IdiomaManager.get("pescaitoOffline.global.pescaitoRobo",
                    nombres.get(UID_JUGADOR),
                    pescaitosPorJugador.get(UID_JUGADOR))
            );
        }

        // Limpiar el estado de robo antes de continuar
        esperandoRobo = false;
        numeroPreguntadoAntesDeRobar = null;
        numeroSeleccionado = null;
        uiBloqueada = false;

        actualizarInterfaz(); // Actualizamos estado de la interfaz
        logEstadoPartida("Robo manual: " + nombres.getOrDefault(UID_JUGADOR, "jugador"));

        if (verificarFin()) { // comprobar si el robo terminó la partida
            return;
        }

        if (haPescado) { // "Pescó": la carta robada tiene el mismo número que preguntó - mantiene turno

            narrarGlobal(IdiomaManager.get("pescaitoOffline.global.roboAcertado", numRobado));

            // PauseTransition para dar tiempo al jugador para leer el resultado antes de continuar
            PauseTransition delay = new PauseTransition(Duration.seconds(2));
            delay.setOnFinished(ev -> iniciarTurnoJugador(UID_JUGADOR));
            delay.play();
        } else {
            // Si no termina la partida ni ha pescado, pasa turno
            narrarGlobal(IdiomaManager.get("pescaitoOffline.global.roboFallado", numRobado));
            PauseTransition delay = new PauseTransition(Duration.seconds(2));
            delay.setOnFinished(ev -> pasarTurno(UID_JUGADOR));
            delay.play();
        }
    }

    // =========================================================================
    //  PROCESADO DE RESULTADO (compartido humano e IA)
    // =========================================================================
    /**
     * Procesa el mapa de resultados de {@link JuegoPescaito#preguntar} y
     * actualiza la UI, los contadores y el flujo de turno tanto para el humano
     * como para las IAs.
     *
     * <p>
     * Este método centraliza toda la lógica de respuesta a una pregunta. Es
     * compartido por humano e IA para evitar duplicar el código de narración,
     * actualización de contadores y decisión de siguiente turno.</p>
     *
     * <p>
     * Flujos principales:</p>
     * <ul>
     * <li><strong>Acierto:</strong> narrar, incrementar contador si hay
     * pescaito, actualizar UI y esperar 2 segundos (PauseTransition) antes de
     * continuar el turno o pasarlo.</li>
     * <li><strong>Fallo + IA:</strong> programar el robo de la IA con 1 segundo
     * de delay llamando a {@link #ejecutarRoboIA(String)}.</li>
     * <li><strong>Fallo + humano + baraja vacía:</strong> narrar y pasar turno
     * sin necesidad de robar.</li>
     * <li><strong>Fallo + humano + baraja con cartas:</strong> tras 1 segundo,
     * establecer {@link #esperandoRobo} = {@code true}, desactivar la
     * interacción y dejar solo el mazo activo.</li>
     * </ul>
     *
     * <p>
     * {@code final String preguntadorFinal = preguntador}: necesario porque las
     * variables usadas dentro de lambdas deben ser finales. {@code preguntador}
     * podría cambiar en futuras iteraciones si no se capturara.</p>
     *
     * @param preguntador UID del jugador que preguntó (humano o IA)
     * @param objetivo UID del jugador al que se preguntó
     * @param res mapa devuelto por {@link JuegoPescaito#preguntar}
     * @param numeroPreguntado número por el que se preguntó
     */
    private void procesarResultado(String preguntador, String objetivo,
            Map<String, Object> res, int numeroPreguntado) {

        // Extraer todos los valores del resultado con cast explícito
        boolean acierto = (boolean) res.get("acierto");
        boolean pescaitoFlag = (boolean) res.get("pescaito");
        boolean debeRobar = (boolean) res.get("debeRobar");
        boolean mantieneTurno = (boolean) res.get("mantieneTurno");
        int cartasRec = (int) res.get("cartasRecibidas");

        String nomPreg = nombres.getOrDefault(preguntador, preguntador);
        String nomObj = nombres.getOrDefault(objetivo, objetivo);

        // Caso en el que hay acierto
        if (acierto) {
            narrarGlobal(IdiomaManager.get("pescaitoOffline.global.aciertoPregunta",
                    nomPreg, nomObj, numeroPreguntado, cartasRec));

            if (pescaitoFlag) { // Si se produce un pescaito, aumentar el contador de pescaitos
                int num = (int) res.get("numeroPescaito");
                // merge incrementa el contador del preguntador en 1
                pescaitosPorJugador.merge(preguntador, 1, Integer::sum);
                narrarGlobal(
                        IdiomaManager.get("pescaitoOffline.global.pescaitoAcierto",
                                nomPreg, num, pescaitosPorJugador.get(preguntador)));
            }

            actualizarInterfaz();
            logEstadoPartida("Pregunta: " + nomPreg + " - " + nomObj + " | Acierto: true"); // debug
            if (verificarFin()) { // Si termina partida, salimos
                return;
            }

            // Delay de 2 segundos para que el mensaje sea legible antes de continuar
            // final necesario para usar preguntador dentro del lambda
            final String preguntadorFinal = preguntador;
            PauseTransition delay = new PauseTransition(Duration.seconds(2));
            delay.setOnFinished(ev -> {
                if (mantieneTurno) {
                    narrarGlobal(IdiomaManager.get(
                            "pescaitoOffline.global.mantieneTurno", nomPreg));
                    iniciarTurnoJugador(preguntadorFinal); // Continua preguntando el que preguntó inicialmente
                } else {
                    pasarTurno(preguntadorFinal); // si nomantiene turno, pasarlo
                }
            });
            delay.play();
            return; // salimos y dejamos al delay gestionar el resto del flujo

        } else { // Si la pregunta se valida como fallo:
            narrarGlobal(IdiomaManager.get(
                    "pescaitoOffline.global.falloPregunta", nomPreg, nomObj, numeroPreguntado));
        }

        actualizarInterfaz();
        logEstadoPartida("Pregunta: " + nombres.getOrDefault(preguntador, preguntador)
                + " - " + nombres.getOrDefault(objetivo, objetivo)
                + " | Acierto: " + acierto);

        if (verificarFin()) {
            return;
        }

        if (debeRobar) {
            if (esIA(preguntador)) { // Si había preguntado la IA
                // Robo de IA: delay de 1s antes de ejecutar el robo
                PauseTransition p = new PauseTransition(Duration.seconds(1));
                p.setOnFinished(ev -> ejecutarRoboIA(preguntador));
                p.play();
            } else {
                // Si el que preguntó fue humano
                if (baraja.isEmpty()) {
                    // Baraja vacía: no puede robar - pasar turno directamente
                    narrarGlobal(IdiomaManager.get("pescaitoOffline.global.noQuedanCartas", nomPreg));
                    pasarTurno(preguntador);
                    return;
                }
                // Baraja con cartas: activar estado de espera de robo tras 1s
                // numParaRobar: captura el valor antes del delay para usarlo en el lambda
                final int numParaRobar = numeroPreguntado;
                PauseTransition delay = new PauseTransition(Duration.seconds(1));
                delay.setOnFinished(ev -> {
                    numeroPreguntadoAntesDeRobar = numParaRobar; // guardar para comparar al robar
                    esperandoRobo = true;
                    desactivarInteraccion();
                    imgMazo.setDisable(false); // solo el mazo queda activo
                    imgMazo.setOpacity(1.0);
                });
                delay.play();
            }
            return;
        }

        // Sin robo pendiente, gestionamos turno
        if (mantieneTurno) {
            narrarGlobal(IdiomaManager.get("pescaitoOffline.global.mantieneTurno", nomPreg));
            iniciarTurnoJugador(preguntador);
        } else {
            pasarTurno(preguntador);
        }
    }

    // =========================================================================
    //  TURNO IA
    // =========================================================================
    /**
     * Programa el turno de la IA actual con un delay o un botón manual.
     *
     * <p>
     * Guardia: sale inmediatamente si
     * {@link PartidaControllerBase#uidTurnoActual} no corresponde a una IA
     * (evita activar el delay en el turno del humano).</p>
     *
     * <p>
     * Desactiva la interacción humana antes de programar el turno, para que el
     * jugador no pueda hacer clic mientras la IA "piensa".</p>
     *
     * <p>
     * Según {@link #MODO_DEBUG_IA}:</p>
     * <ul>
     * <li>{@code false}: {@link PauseTransition} de {@value #DELAY_IA_SEG}s -
     * llama a {@link #ejecutarTurnoIA(String)}.</li>
     * <li>{@code true}: llama a {@link #mostrarBotonIAManual()} para avance
     * manual.</li>
     * </ul>
     */
    private void programarTurnoIA() {

        // Guardia de si es IA o HUMANOS
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

    /**
     * Muestra el botón de avance manual del turno de IA (solo en modo debug).
     *
     * <p>
     * Si ya había un botón anterior, lo elimina antes de crear el nuevo. El
     * botón captura {@code uidTurnoActual} en el momento de su creación para
     * evitar que el lambda use el valor que tenga el campo cuando se pulse
     * (podría haber cambiado).</p>
     *
     * <p>
     * {@code StackPane.setAlignment} posiciona el botón en la esquina inferior
     * derecha del rootSala. {@code setTranslateX/Y} añade un margen visual.</p>
     */
    private void mostrarBotonIAManual() {

// Quitar el anterior si existía
        if (btnIAManual != null) {
            rootSala.getChildren().remove(btnIAManual);
            btnIAManual = null;
        }

        // Recogemos la IA que sea para mostrarla en el botón
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

        // Capturar uidTurnoActual
        final String uidIAActual = uidTurnoActual;
        btnIAManual.setOnAction(e -> {
            ocultarBotonIAManual();
            ejecutarTurnoIA(uidIAActual); // usar la captura, no el campo
        });

        rootSala.getChildren().add(btnIAManual);
    }

    /**
     * Elimina el botón de avance manual de la UI si existe. Es seguro llamarlo
     * aunque {@link #btnIAManual} sea {@code null}.
     */
    private void ocultarBotonIAManual() {
        if (btnIAManual != null) {
            rootSala.getChildren().remove(btnIAManual);
            btnIAManual = null;
        }
    }

    /**
     * Ejecuta el turno de la IA indicada: elige número y rival al azar y
     * pregunta.
     *
     * <p>
     * Guards de entrada:</p>
     * <ul>
     * <li>La partida no debe estar finalizada.</li>
     * <li>El UID indicado debe ser el turno actual (evita actuar si el turno
     * cambió durante el delay del {@link PauseTransition}).</li>
     * <li>La mano de la IA no debe estar vacía (estado inválido; no debería
     * ocurrir porque {@link #iniciarTurnoJugador} siempre roba antes de llamar
     * aquí si la mano está vacía).</li>
     * </ul>
     *
     * <p>
     * La selección de número es aleatoria entre los números que tiene en mano.
     * Se usa un {@link Set} para deduplicar (si tiene dos cartas del mismo
     * número, solo aparece una vez en las opciones).</p>
     *
     * <p>
     * Guarda el número preguntado en {@link #numeroPreguntadoPorIA} para que
     * {@link #ejecutarRoboIA(String)} pueda compararlo con la carta robada.</p>
     *
     * @param uidIA UID de la IA cuyo turno se ejecuta
     */
    private void ejecutarTurnoIA(String uidIA) {
        ocultarBotonIAManual();
        if (partidaFinalizada) {
            return;
        }

        // el turno pudo cambiar durante el delay
        if (!uidIA.equals(uidTurnoActual)) {
            return;
        }

        List<String> manoIA = manos.get(uidIA);
        // Si no tiene carta aquí es un estado inválido - no debería ocurrir
        if (manoIA == null || manoIA.isEmpty()) {
            System.out.println("WARN: ejecutarTurnoIA llamado sin carta para " + uidIA);
            return; // no hacer nada, iniciarTurnoJugador lo resolverá
        }

        // Obtener números únicos de la mano para elegir uno al azar
        Set<Integer> nums = new HashSet<>();
        for (String c : manoIA) {
            nums.add(juego.obtenerNumeroCarta(c));
        }
        List<Integer> listaNum = new ArrayList<>(nums);
        int numPreguntado = listaNum.get(new Random().nextInt(listaNum.size()));

        // Guardar el número preguntado para comparar al robar si falla
        numeroPreguntadoPorIA.put(uidIA, numPreguntado);

        // Filtrar rivales que tengan cartas
        List<String> rivalesConCartas = ordenJugadoresGlobal.stream()
                .filter(u -> !u.equals(uidIA))
                .filter(u -> {
                    List<String> m = manos.get(u);
                    return m != null && !m.isEmpty();
                })
                .collect(Collectors.toList());

        if (rivalesConCartas.isEmpty()) {
            // Sin rivales con cartas: pasar turno sin preguntar
            narrarGlobal(IdiomaManager.get("pescaitoOffline.global.iaSinRivales",
                    nombres.get(uidIA)));
            logEstadoPartida("IA sin rivales: " + nombres.get(uidIA) + " pasa turno");
            pasarTurno(uidIA);
            return;
        }

        // Elegir un rival al azar entre los que tienen cartas
        String objetivo = rivalesConCartas.get(new Random().nextInt(rivalesConCartas.size()));
        narrarGlobal(IdiomaManager.get(
                "pescaitoOffline.global.iaPregunta",
                nombres.get(uidIA),
                nombres.get(objetivo),
                numPreguntado));

        // Hacemos la pregunta y recogemos el resultado en mapa
        Map<String, Object> res = pescaito().preguntar(uidIA, objetivo, numPreguntado, manos, baraja, descarte);
        boolean tenia = (boolean) res.get("acierto");
        logEstadoPartida("IA pregunta: " + nombres.get(uidIA) + " - " + nombres.get(objetivo)
                + " por el " + numPreguntado + " | " + (tenia ? "¡LO TENÍA!" : "No lo tenía"));
        // Procesamso resultado de la pregunta de la IA
        procesarResultado(uidIA, objetivo, res, numPreguntado);
    }

    /**
     * Ejecuta el robo de una carta de la IA tras haber fallado una pregunta.
     *
     * <p>
     * Lee el número preguntado desde {@link #numeroPreguntadoPorIA} y lo
     * elimina del mapa al terminar el robo. Si la baraja está vacía, pasa el
     * turno sin robar.</p>
     *
     * <p>
     * Comprueba tanto si "pescó" ({@link JuegoPescaito#haPescadoAlRobar}) como
     * si completó un pescaito ({@link JuegoPescaito#esPescaitoPorRobo}) para
     * actualizar contadores y continuar el flujo correctamente.</p>
     *
     * @param uidIA UID de la IA que debe robar
     */
    private void ejecutarRoboIA(String uidIA) {
        if (baraja.isEmpty()) { // Si no hay baraja disponibble, mostrar mensaje y pasar turno a otro jugador
            narrarGlobal(IdiomaManager.get("pescaitoOffline.global.iaNoPuedeRobar",
                    nombres.get(uidIA)));
            logEstadoPartida("IA sin baraja: " + nombres.get(uidIA) + " pasa turno");
            pasarTurno(uidIA);
            return;
        }

        // robar carta
        pescaito().robarCarta(uidIA, manos, baraja);
        List<String> mano = manos.get(uidIA);
        // La carta robada es siempre la última: robarCarta() la añade con mano.add()
        String cartaRobada = mano.get(mano.size() - 1);
        int numRobado = juego.obtenerNumeroCarta(cartaRobada);

        // Leer y limpiar el número que preguntó la IA antes de robar
        Integer numQuePreguntoAntes = numeroPreguntadoPorIA.getOrDefault(uidIA, -1);
        numeroPreguntadoPorIA.remove(uidIA); // limpiar

        narrarGlobal(IdiomaManager.get("pescaitoOffline.global.iaRoba",
                nombres.get(uidIA),
                numRobado));

        boolean haPescado = pescaito().haPescadoAlRobar(cartaRobada, numQuePreguntoAntes); // Boolean por si pesca la IA
        boolean pesc = pescaito().esPescaitoPorRobo(uidIA, manos, descarte); // Boolean por si hace pescaito la IA

        if (pesc) { // Si hace pescaito la IA
            pescaitosPorJugador.merge(uidIA, 1, Integer::sum); // Se lo sumamos
            narrarGlobal(IdiomaManager.get(
                    "pescaitoOffline.global.iaPescaito",
                    nombres.get(uidIA),
                    pescaitosPorJugador.get(uidIA)));
        }

        actualizarInterfaz();
        logEstadoPartida("Robo IA: " + nombres.get(uidIA)
                + " robó el " + numRobado
                + " | Preguntó el " + numQuePreguntoAntes
                + " | Pesca: " + haPescado);

        if (verificarFin()) {
            return;
        }

        if (haPescado) { // Si pesca la IA
            narrarGlobal(IdiomaManager.get("pescaitoOffline.global.iaMantieneTurno",
                    nombres.get(uidIA)));
            iniciarTurnoJugador(uidIA);  // iniciar un nuevo turno para la misma IA
        } else {
            pasarTurno(uidIA); // Pasa turno
        }
    }

    // =========================================================================
    //  GESTIÓN DE TURNOS
    // =========================================================================
    /**
     * Pasa el turno al siguiente jugador en el orden circular.
     *
     * <p>
     * Calcula el índice del jugador actual en
     * {@link PartidaControllerBase#ordenJugadoresGlobal} y avanza al siguiente
     * usando el operador módulo para circular al inicio cuando llega al final:
     * {@code (index + 1) % size}.</p>
     *
     * <p>
     * Actualiza {@link PartidaControllerBase#uidTurnoActual}, narra el cambio,
     * actualiza la interfaz, comprueba fin de partida y llama a
     * {@link #iniciarTurnoJugador(String)} para el siguiente.</p>
     *
     * @param actual UID del jugador cuyo turno acaba de terminar
     */
    private void pasarTurno(String actual) {

        int index = ordenJugadoresGlobal.indexOf(actual);
        // Módulo para circular al inicio cuando se llega al último jugador
        String siguiente = ordenJugadoresGlobal.get((index + 1) % ordenJugadoresGlobal.size());
        uidTurnoActual = siguiente;
        narrarGlobal(IdiomaManager.get("pescaitoOffline.global.turnoDe",
                nombres.getOrDefault(siguiente, siguiente)));

        actualizarInterfaz();
        if (verificarFin()) {
            return;
        }

        iniciarTurnoJugador(siguiente); // Pasar turno
    }

    /**
     * Punto de entrada al turno de cualquier jugador (humano o IA). Gestiona
     * los cuatro casos posibles al inicio de un turno:
     *
     * <ol>
     * <li><strong>Tiene carta:</strong> caso normal. Si es IA, programar su
     * turno; si es humano, activar la interacción.</li>
     * <li><strong>Sin carta, con baraja:</strong> robar automáticamente. Si el
     * robo completa un pescaito (mano vacía de nuevo), llamar recursivamente a
     * {@code iniciarTurnoJugador} para repetir el ciclo.</li>
     * <li><strong>Sin carta, sin baraja:</strong> pasar turno al siguiente
     * jugador.</li>
     * <li><strong>Es el único con cartas y no hay baraja:</strong> pasar turno
     * (no tiene a quien preguntar y no puede robar).</li>
     * </ol>
     *
     * <p>
     * La llamada recursiva en el caso 2 es segura porque solo ocurre si el
     * jugador hizo pescaito (mano vacía otra vez), y en ese caso la baraja se
     * fue reduciendo. El juego termina antes de que la recursión sea
     * infinita.</p>
     *
     * @param uid UID del jugador cuyo turno debe comenzar
     */
    private void iniciarTurnoJugador(String uid) {
        List<String> mano = manos.get(uid);
        boolean tieneCarta = mano != null && !mano.isEmpty(); // Nos aseguramos de que la mano del jugador no sea ni null ni este vacía

        if (!tieneCarta) { // Si no hay carta
            if (!baraja.isEmpty()) { // Pero la baraja no está vacía - robar automáticamente
                pescaito().robarCarta(uid, manos, baraja);
                String cartaRobada = manos.get(uid).get(manos.get(uid).size() - 1);
                int numRobado = juego.obtenerNumeroCarta(cartaRobada);

                // Comprobar pescaito por robo automático
                boolean pescaito = pescaito().esPescaitoPorRobo(uid, manos, descarte);
                if (pescaito) {
                    pescaitosPorJugador.merge(uid, 1, Integer::sum); // Sumamos pescaito por robar automaticamente
                    narrarGlobal(IdiomaManager.get(
                            "pescaitoOffline.global.pescaitoRobo",
                            nombres.getOrDefault(uid, uid),
                            pescaitosPorJugador.get(uid)));
                }

                narrarGlobal(IdiomaManager.get(
                        "pescaitoOffline.global.roboAutomatico",
                        nombres.getOrDefault(uid, uid),
                        numRobado));

                actualizarInterfaz();
                logEstadoPartida("Robo automático: " + nombres.getOrDefault(uid, uid) + " - " + numRobado);

                if (verificarFin()) {
                    return;
                }

                // Si después del robo sigue sin carta (hizo pescaito), volver a comprobar
                List<String> manoTrasRobo = manos.get(uid);
                if (manoTrasRobo == null || manoTrasRobo.isEmpty()) {
                    // Hizo pescaito con el robo automático - volver a iniciarTurnoJugador
                    iniciarTurnoJugador(uid);
                    return;
                }
            } else {
                // Sin carta y sin baraja - pasa turno
                narrarGlobal(IdiomaManager.get(
                        "pescaitoOffline.global.sinCartasSinBaraja",
                        nombres.getOrDefault(uid, uid)));
                pasarTurno(uid);
                return;
            }
        }

        // Verificar si este jugador es el único con cartas y no hay baraja
        boolean hayRivalesConCartas = ordenJugadoresGlobal.stream()
                .filter(u -> !u.equals(uid))
                .anyMatch(u -> {
                    List<String> m = manos.get(u);
                    return m != null && !m.isEmpty();
                });

        if (!hayRivalesConCartas && baraja.isEmpty()) {
            // Único con cartas y sin baraja: no puede preguntar a nadie
            narrarGlobal(IdiomaManager.get(
                    "pescaitoOffline.global.unicoConCartas",
                    nombres.getOrDefault(uid, uid)));
            pasarTurno(uid);
            return;
        }

        // Tiene carta y puede jugar - activar su turno
        uidTurnoActual = uid; // asegurar que el turno es de este jugador
        if (esIA(uid)) {
            final String uidCapturado = uid; // final para lambda
            desactivarInteraccion();
            if (MODO_DEBUG_IA) {
                // Modo debug: botón manual con UID capturado
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
                // Modo normal: delay automático
                PauseTransition p = new PauseTransition(Duration.seconds(DELAY_IA_SEG));
                p.setOnFinished(ev -> ejecutarTurnoIA(uidCapturado));
                p.play();
            }
        } else {
            activarInteraccion(); // turno del humano: habilitar interacción
        }
    }

    /**
     * Busca el siguiente jugador en el orden circular que tenga al menos una
     * carta. Itera hasta una vuelta completa; si nadie tiene cartas, devuelve
     * el siguiente en orden normal como fallback.
     *
     * @param actual UID del jugador actual
     * @return UID del siguiente jugador con cartas, o el siguiente en orden si
     * nadie tiene
     */
    private String siguienteConCartas(String actual) {
        int index = ordenJugadoresGlobal.indexOf(actual);
        for (int i = 1; i <= ordenJugadoresGlobal.size(); i++) {
            String uidSiguiente = ordenJugadoresGlobal.get((index + i) % ordenJugadoresGlobal.size());
            List<String> mano = manos.get(uidSiguiente);
            if (mano != null && !mano.isEmpty()) {
                return uidSiguiente;
            }
        }
        return ordenJugadoresGlobal.get((index + 1) % ordenJugadoresGlobal.size()); // fallback
    }

    /**
     * Comprueba si el UID dado corresponde a una IA. La detección se basa en el
     * prefijo {@value #PREFIJO_IA} del UID.
     *
     * @param uid UID a comprobar
     * @return {@code true} si el UID empieza por {@value #PREFIJO_IA}
     */
    private boolean esIA(String uid) {
        return uid != null && uid.startsWith(PREFIJO_IA); // Si su prefijo no empieza porel de ia, no es IA
    }

    /**
     * Comprueba si la partida ha terminado y la finaliza si es necesario.
     * Método de conveniencia para evitar repetir la misma llamada doble en
     * múltiples puntos del código.
     *
     * @return {@code true} si la partida ha terminado y se inició la
     * finalización
     */
    private boolean verificarFin() {
        if (!juego.haTerminado(manos, baraja, descarte)) {
            return false;
        }
        finalizarPartida();
        return true;
    }

    /**
     * Castea el motor al tipo concreto {@link JuegoPescaito} para acceder a
     * métodos exclusivos del modo Pescaito (preguntar, haPescadoAlRobar, etc.).
     * Método de conveniencia que evita el cast en cada llamada.
     *
     * @return el motor de juego casteado a {@link JuegoPescaito}
     */
    private JuegoPescaito pescaito() {
        return (JuegoPescaito) juego;
    }

    // =========================================================================
    //  GESTIÓN DE LA UI
    // =========================================================================
    /**
     * Habilita y restaura la opacidad de todas las zonas de interacción. La
     * opacidad se restaura a 1.0 porque {@link #desactivarInteraccion()} la
     * reduce a 0.5 para feedback visual.
     */
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

    /**
     * Deshabilita y oscurece todas las zonas de interacción. La opacidad 0.5 da
     * feedback visual al jugador de que las zonas no son interactuables sin
     * necesidad de texto adicional.
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

    // =========================================================================
    //  SOBREESCRITURAS DE NARRACIÓN - sin Firebase
    // =========================================================================
    /**
     * En offline los mensajes globales se muestran directamente en el label del
     * narrador sin pasar por Firebase.
     *
     * <p>
     * {@code Platform.runLater}: garantiza que la actualización del label se
     * ejecuta en el hilo de JavaFX, aunque este método se llame desde un hilo
     * de background (p.ej. desde un PauseTransition).</p>
     *
     * @param texto mensaje a mostrar en el narrador
     */
    @Override
    protected void narrarGlobal(String texto) {
        // En offline: mostrar directamente en el label sin Firebase
        Platform.runLater(() -> narradorLabel.setText(texto));
    }

    /**
     * En offline los mensajes privados solo los ve el jugador local (el
     * humano). Los mensajes dirigidos a las IAs se ignoran porque no tienen
     * pantalla propia.
     *
     * @param uidDestino UID del destinatario del mensaje
     * @param texto mensaje privado
     */
    @Override
    protected void narrarPrivado(String uidDestino, String texto) {
        // En offline: solo el jugador local ve mensajes privados
        if (UID_JUGADOR.equals(uidDestino)) {
            Platform.runLater(() -> encolarMensajePrivado(texto));
        }
        // Los mensajes privados a las IAs se ignoran (no tienen pantalla)
    }

    /**
     * En offline los nombres ya están en el mapa
     * {@link PartidaControllerBase#nombres}. Sobreescribir como lectura directa
     * del mapa evita que la base intente leer los nombres de Firebase.
     */
    @Override
    protected void cargarNombresJugadores() {
        // En offline los nombres ya están en el mapa, no leer Firebase
        if (lblAbajo != null) {
            lblAbajo.setText(nombres.getOrDefault(uidLocal, "jugador"));
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
    //  LOG DE DIAGNÓSTICO
    // =========================================================================
    /**
     * Imprime en consola el estado completo de la partida con formato visual.
     * Útil para monitorizar el flujo durante el desarrollo y la depuración.
     * Debe llamarse tras cada robo, pregunta o cambio de turno significativo.
     *
     * <p>
     * El StringBuilder acumula las líneas del log para hacer una única llamada
     * a {@code System.out.println}, lo que es más eficiente que múltiples
     * llamadas separadas al buffer de salida estándar.</p>
     *
     * @param evento descripción breve del evento que desencadenó el log
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

            // Números de las cartas en mano, ordenados para facilitar lectura
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

    // =========================================================================
    //  POPUP FINAL Y REINICIO
    // =========================================================================
    /**
     * Construye los datos del popup de fin de partida desde el mapa local
     * {@link #pescaitosPorJugador}, sin leer Firebase.
     *
     * <p>
     * A diferencia de
     * {@link PartidaControllerPescaito#construirDatosPopUpFinal()}, que lee los
     * pescaitos de Firebase, aquí se usan directamente los valores acumulados
     * durante la partida en memoria. El algoritmo es idéntico: encontrar el
     * máximo, recopilar los empatados y construir el texto.</p>
     */
    @Override
    protected DatosPopUp construirDatosPopUpFinal() {
        try {
            // Puntuaciones ya están en memoria
            Map<String, Integer> puntuaciones = new HashMap<>(pescaitosPorJugador);

            // Encontrar el máximo
            int maxPescaitos = puntuaciones.values().stream()
                    .max(Integer::compare)
                    .orElse(0);

            // Lista de ganadores (puede haber empate)
            List<String> ganadores = puntuaciones.entrySet().stream()
                    .filter(e -> e.getValue() == maxPescaitos)
                    .map(Map.Entry::getKey)
                    .toList();

            String resultado;
            String detalle;
            String icono;

            // Construir textos igual que en Yusa
            if (ganadores.size() == 1) { // 1 Ganador
                String uidGanador = ganadores.get(0);
                String nombre = nombres.getOrDefault(uidGanador, "Jugador");

                resultado = IdiomaManager.get("pescaitoOffline.popUpFinal.victoria", nombre);

                detalle = IdiomaManager.get("pescaitoOffline.popUpFinal.detalleVictoria", maxPescaitos);

                icono = "/ui/graphicResources/imagenes/imgGanador.png";

            } else {
                // Empate
                resultado = IdiomaManager.get("pescaitoOffline.popUpFinal.empate");

                detalle = IdiomaManager.get("pescaitoOffline.popUpFinal.detalleEmpate", maxPescaitos);

                icono = "/ui/graphicResources/imagenes/imgEmpate.png";
            }

            return new DatosPopUp(icono, resultado, detalle);

        } catch (Exception e) {
            e.printStackTrace();
            return new DatosPopUp(
                    "/ui/graphicResources/imagenes/imgEmpate.png",
                    IdiomaManager.get("pescaitoOffline.popUpFinal.finPartida"),
                    ""
            );
        }
    }

    /**
     * Muestra el popup de fin de partida cargando el FXML de
     * {@code popUpFinalPartida.fxml}.
     *
     * <p>
     * En offline usa {@link PopUpFinalPartidaController#initOffline} en lugar
     * de {@link PopUpFinalPartidaController#init}, que espera parámetros de
     * Firebase. Si el FXML falla, muestra un label de fallback para que la
     * partida no quede bloqueada en la pantalla de juego.</p>
     */
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
                    this // controlador offline actual
            );

            overlayFinal.getChildren().clear();
            overlayFinal.getChildren().add(popUpPane);
            overlayFinal.setVisible(true);

        } catch (IOException e) {
            e.printStackTrace();
            // Fallback: label simple si el FXML no carga
            Label fallback = new Label(
                    IdiomaManager.get("pescaitoOffline.popUpFinal.finPartida")
            );
            fallback.setStyle("-fx-text-fill:white;-fx-font-size:24px;");
            overlayFinal.getChildren().clear();
            overlayFinal.getChildren().add(fallback);
            overlayFinal.setVisible(true);
        }
    }

    /**
     * Reinicia la partida offline creando un nuevo controlador y cargando la
     * escena.
     *
     * <p>
     * El ciclo desmaximizar - cambiar escena - maximizar es necesario porque
     * JavaFX puede no ajustar correctamente el layout de la nueva escena si la
     * ventana está maximizada cuando se cambia la escena.</p>
     */
    public void reiniciarPartida() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/ui/partidaOffline.fxml"));

            PartidaOfflinePescaitoController nuevoCtrl = new PartidaOfflinePescaitoController();
            loader.setController(nuevoCtrl);

            Parent root = loader.load();
            nuevoCtrl.iniciarOffline(numIAs); // iniciar con el mismo número de IAs

            Stage stage = (Stage) overlayFinal.getScene().getWindow();
            stage.setMaximized(false);   // Desmaximizar
            stage.setScene(new Scene(root)); // Cambiar escena
            stage.setMaximized(true);    // Maximizar de nuevo
            stage.show();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Navega de vuelta al menú offline.
     */
    public void volverAlMenu() {
        MainApp.cambiarEscena("menuOffline.fxml", 1200, 1000);
    }

    /**
     * Implementación del hook de la base para reiniciar partida offline. Delega
     * en {@link #reiniciarPartida()}.
     */
    @Override
    public void reiniciarPartidaOffline() {
        reiniciarPartida();
    }

    /**
     * Implementación del hook de la base para volver al menú offline. Delega en
     * {@link #volverAlMenu()}.
     */
    @Override
    public void volverAlMenuOffline() {
        volverAlMenu();
    }

}
