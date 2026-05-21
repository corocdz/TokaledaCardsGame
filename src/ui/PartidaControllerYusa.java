package ui;

import i18n.IdiomaManager;
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
 * Controlador del modo de juego <strong>Yusa Online</strong>.
 *
 * <p>
 * Extiende {@link PartidaControllerBase} e implementa toda la lógica específica
 * del juego Yusa: fases de ronda, sistema de director/cliente, revelación de
 * cartas, duelos de yusa y rondas de desempate.</p>
 *
 * <h2>Arquitectura: Estado Único - Director / Cliente</h2>
 * <p>
 * En Yusa hay un <em>director</em> (el jugador con {@code uidTurnoActual}) que
 * orquesta cada ronda. El resto son <em>clientes</em> que reaccionan a lo que
 * el director publica. El rol rota entre jugadores vivos cada ronda.</p>
 *
 * <p>
 * Dos canales de comunicación vía Firebase (polling cada ~200ms):</p>
 * <ul>
 * <li><strong>Director - Todos:</strong> {@code partida/estadoRonda} - el
 * director publica acciones que todos deben ejecutar.</li>
 * <li><strong>No-director - Director:</strong> {@code partida/decisionJugador}
 * - los jugadores no-director envían sus decisiones al director.</li>
 * </ul>
 *
 * <h2>Acciones de estadoRonda.accion</h2>
 * <ul>
 * <li>{@code ESPERANDO_DECISION} - turnoUid = jugador que decide (no es el
 * último).</li>
 * <li>{@code ESPERANDO_DECISION_ULTIMO} - turnoUid = último jugador de la
 * ronda.</li>
 * <li>{@code JUGAR_DOCE} - turnoUid = quién tiene el 12.</li>
 * <li>{@code ELEGIR_OBJETIVO} - turnoUid = poseedor de yusa que elige a quién
 * preguntar.</li>
 * <li>{@code ELEGIR_PALO} - turnoUid = objetivo que debe adivinar el palo.</li>
 * <li>{@code REVELAR_CARTAS} - turnoUid = null (todos muestran su carta).</li>
 * <li>{@code ESPECTADOR_DESEMPATE} - turnoUid = UIDs de empatados separados por
 * coma.</li>
 * <li>{@code FIN_DESEMPATE} - turnoUid = null (volver a ronda normal).</li>
 * </ul>
 *
 * <h2>Flujo completo de una ronda (desde el director)</h2>
 * <ol>
 * <li>{@link #iniciarRondaYusa()} reparte cartas y determina la fase.</li>
 * <li>Fase NORMAL: cada jugador decide si quedarse o cambiar carta en
 * orden.</li>
 * <li>Fase DOCE: quien tiene el 12 tiene 20 segundos para jugarlo.</li>
 * <li>Fase YUSA: cada poseedor elige objetivo; el objetivo adivina el
 * palo.</li>
 * <li>{@link #publicarRevelarCartas()} - todos muestran su carta 5
 * segundos.</li>
 * <li>Tras 5s: el director resuelve quién pierde vida y cierra la ronda.</li>
 * <li>{@link #finalizarCicloRonda()} limpia Firebase y rota el director.</li>
 * </ol>
 *
 * <h2>Protección contra doble revelación</h2>
 * <p>
 * El polling puede entregar el evento {@code REVELAR_CARTAS} más de una vez por
 * latencia alta o reconexión. El sistema usa {@link #ultimoEstadoTs} para
 * deduplicar por timestamp, y {@link #VENTANA_PROTECCION_MS} como segunda
 * barrera temporal para ignorar revelaciones seguidas.</p>
 *
 * @author Javier Coronilla Castellano
 */
public class PartidaControllerYusa extends PartidaControllerBase {

    // =========================================================================
    //  MÉTODO AUXILIAR DEL MOTOR
    // =========================================================================
    /**
     * Devuelve el motor de juego casteado a {@link JuegoYusa}.
     * <p>
     * Evita repetir {@code (JuegoYusa) juego} en cada llamada, mejorando la
     * legibilidad. Se usa en todos los métodos de esta clase.</p>
     *
     * @return el campo {@code juego} de la clase base como {@link JuegoYusa}
     */
    private JuegoYusa yusa() {
        return (JuegoYusa) juego;
    }

    // =========================================================================
    //  ESTADO DE RONDA
    // =========================================================================
    /**
     * Indica si hay una ronda en curso en este controlador.
     * <p>
     * {@code true} desde {@link #iniciarRondaYusa()} hasta
     * {@link #resetearEstadoLocal()}. Declarado {@code volatile} porque puede
     * ser leído desde el hilo del listener de estadoRonda y escrito desde el
     * hilo de JavaFX - sin {@code volatile}, el compilador o la JVM podrían
     * cachear el valor y el listener vería un valor obsoleto.</p>
     * <p>
     * Evita que {@link #onCambioTurno(String)} inicie una segunda ronda
     * mientras el director ya está ejecutando la actual.</p>
     */
    private volatile boolean rondaEnCurso = false;

    /**
     * Fase de la ronda actualmente en curso: {@code NORMAL}, {@code DOCE} o
     * {@code YUSA}.
     * <p>
     * Se determina en {@link #iniciarRondaYusa()} analizando las cartas
     * repartidas. Puede cambiar a mitad de ronda si el último jugador roba una
     * carta especial de la baraja en {@link #ejecutarRoboUltimo(String)}.</p>
     */
    private JuegoYusa.FaseRonda faseRondaActual = null;

    /**
     * Contador de rondas completadas en la partida. Se incrementa al final de
     * cada ronda en {@link #incrementarNumeroRonda()} y se publica en Firebase
     * para trazabilidad.
     */
    private int numeroRondaYusa = 0;

    /**
     * Instantánea de las cartas de cada jugador tomada justo antes de la
     * revelación.
     * <p>
     * Clave: UID del jugador. Valor: ruta de imagen de su carta.</p>
     *
     * <p>
     * <strong>Por qué es necesario:</strong> al publicar
     * {@code REVELAR_CARTAS}, el director tiene las cartas correctas
     * localmente. Pero cuando llega el evento a los clientes (tras el ciclo de
     * polling), las manos pueden haber sido modificadas en Firebase. El
     * snapshot preserva el estado exacto para que {@link #resolverFinDeRonda()}
     * use las cartas correctas del momento de la revelación.</p>
     *
     * <p>
     * En la fase YUSA, los snapshots de los poseedores se guardan
     * individualmente en {@link #onElegirObjetivoYusa(String)}, no en
     * {@link #guardarSnapshot()}, porque la carta puede ya estar descartada
     * cuando se llama al método general.</p>
     */
    private final Map<String, String> snapshotCartas = new HashMap<>();

    /**
     * Timestamp del último estado de ronda procesado correctamente.
     * <p>
     * Se compara con el timestamp de cada estado recibido del polling para
     * detectar y descartar duplicados. El valor {@code -1L} indica que no se ha
     * procesado ningún estado aún (estado inicial de la ronda).</p>
     *
     * <p>
     * <strong>IMPORTANTE:</strong> este campo NO se resetea a {@code -1L} en
     * {@link #resetearEstadoLocal()}, a diferencia de lo que podría parecer
     * lógico. Si se reseteara, el listener podría re-procesar el
     * {@code REVELAR_CARTAS} de la ronda anterior (que todavía puede estar en
     * Firebase mientras {@code limpiarEstadoRonda()} se propaga). Solo se
     * resetea al inicio de {@link #iniciarRondaYusa()} cuando el director
     * establece el nuevo contexto.</p>
     */
    private long ultimoEstadoTs = -1L;

    /**
     * Timestamp de la última decisión de jugador procesada por el director.
     * <p>
     * Análogo a {@link #ultimoEstadoTs} pero para el canal
     * {@code decisionJugador}. Solo el director lee este canal. Se resetea a
     * {@code -1L} en {@link #resetearEstadoLocal()} porque el canal de
     * decisiones no tiene el problema de re-procesamiento que sí tiene
     * {@code estadoRonda}.</p>
     */
    private long ultimaDecisionTs = -1L;

    /**
     * Referencia a la {@link PauseTransition} activa durante la revelación de 5
     * segundos.
     * <p>
     * Se guarda para poder cancelarla con {@code stop()} si llega un nuevo
     * {@code REVELAR_CARTAS} antes de que termine el anterior, evitando así
     * revelaciones anidadas. Se establece a {@code null} al finalizar la
     * pausa.</p>
     */
    private PauseTransition pausaRevelado = null;

    /**
     * {@link PauseTransition} de 1 segundo que implementa el countdown del
     * botón del 12.
     * <p>
     * Se llama "tick" porque ejecuta un "tick" cada segundo, decrementando el
     * contador visible en el botón. Se reinicia con {@code playFromStart()} en
     * cada tick hasta que el contador llega a 0 o el jugador pulsa el botón. Se
     * detiene en {@link #detenerTickDoce()} y se limpia a {@code null}.</p>
     */
    private PauseTransition tickDoce = null;

    /**
     * Timestamp en milisegundos de cuando terminó la última revelación de
     * cartas.
     * <p>
     * Se registra en {@link #ocultarCartasDeRonda()} con
     * {@code System.currentTimeMillis()}. Se usa junto con
     * {@link #VENTANA_PROTECCION_MS} para rechazar eventos
     * {@code REVELAR_CARTAS} que lleguen demasiado pronto, protegiendo contra
     * el bug de doble revelación causado por reconexión o latencia alta de
     * Firebase.</p>
     */
    private long tiempoFinUltimaRevelacion = 0L;

    /**
     * Duración en milisegundos de la ventana de protección contra doble
     * revelación.
     * <p>
     * Si llega un evento {@code REVELAR_CARTAS} dentro de los 3 segundos
     * posteriores a la última revelación, se ignora. Este valor cubre el tiempo
     * que Firebase puede tardar en borrar el nodo {@code estadoRonda} tras
     * llamar a {@code limpiarEstadoRonda()} en
     * {@link #finalizarCicloRonda()}.</p>
     */
    private static final long VENTANA_PROTECCION_MS = 3000; // 3 segundos

    /**
     * UID del poseedor de yusa que está preguntando en el turno actual, visto
     * desde el cliente que es el <em>objetivo</em> de la pregunta.
     * <p>
     * Se almacena en {@link #procesarAccion(String, String)} al recibir
     * {@code ELEGIR_OBJETIVO} con un turnoUid diferente al local. Se usa en
     * {@link #onElegirPaloYusa(JuegoYusa.Palo)} para construir el mensaje del
     * narrador sin tener que buscar en {@link #objetivosPorPoseedor}, que en el
     * cliente objetivo puede estar vacío.</p>
     */
    private String uidPoseedorYusaActual = null;

    /**
     * Lista de UIDs participantes en la ronda de desempate activa.
     * <p>
     * {@code null} o vacía indica ronda normal donde participan todos los
     * jugadores vivos. Se establece en {@link #resolverFinDeRonda()} al
     * detectar un empate y se limpia en {@link #resetearEstadoLocal()} al
     * terminar el desempate.</p>
     */
    private List<String> empatadosActuales = null;

    /**
     * {@code true} cuando hay una ronda de desempate activa.
     * <p>
     * Se usa para mostrar el overlay de espectador a los jugadores que no
     * participan en el desempate, y para que {@link #resolverFinDeRonda()}
     * publique {@code FIN_DESEMPATE} al terminar.</p>
     */
    private boolean enDesempate = false;

    /**
     * Referencia al overlay semitransparente que bloquea la pantalla de los
     * jugadores espectadores durante un desempate.
     * <p>
     * Solo existe en los clientes que no participan en el desempate. Se guarda
     * para poder eliminarlo de la escena en
     * {@link #ocultarOverlayEspectador()}. {@code null} cuando no hay overlay
     * activo. Si el jugador ya tiene el overlay de eliminado, no se crea el de
     * espectador.</p>
     */
    private javafx.scene.layout.StackPane overlayEspectador = null;

    /**
     * Overlay de texto "ESTÁS ELIMINADO" que aparece al jugador local cuando
     * pierde todas sus vidas.
     * <p>
     * Es persistente entre rondas: no desaparece hasta que se muestra el popup
     * final. Se añade al {@code overlayFinal} con índice 0 para que quede
     * <em>debajo</em> del popup final si este llega después. {@code null}
     * cuando el jugador no ha sido eliminado aún.</p>
     */
    private javafx.scene.layout.StackPane overlayEliminado = null;

    /**
     * Lista ordenada de UIDs que deben tomar su decisión en la ronda NORMAL
     * actual.
     * <p>
     * Solo la usa y gestiona el director. Se construye en
     * {@link #iniciarFaseNormal()} y se va vaciando conforme cada jugador
     * decide. Cuando está vacía, todos decidieron y se revelan las cartas.</p>
     */
    private final List<String> ordenRondaActual = new ArrayList<>();

    // =========================================================================
    //  ESTADO DE LA FASE YUSA
    // =========================================================================
    /**
     * Mapa que relaciona cada poseedor de yusa con el objetivo que eligió
     * preguntar.
     * <p>
     * Clave: UID del poseedor. Valor: UID del objetivo elegido.</p>
     * <p>
     * Solo lo mantiene actualizado el director. Se usa en
     * {@link #procesarPaloRecibido(String, String)} para identificar a qué
     * poseedor corresponde cada respuesta de palo recibida, especialmente
     * cuando hay múltiples yusas simultáneas.</p>
     */
    private final Map<String, String> objetivosPorPoseedor = new HashMap<>();

    /**
     * {@code true} cuando este cliente es el objetivo activo de una pregunta de
     * yusa.
     * <p>
     * Se establece a {@code true} en {@link #procesarAccion(String, String)} al
     * recibir {@code ELEGIR_PALO} con turnoUid == uidLocal. Protege a
     * {@link #onElegirPaloYusa(JuegoYusa.Palo)} de ejecutarse en clientes que
     * no son el objetivo de la pregunta actual.</p>
     */
    private boolean soyObjetivoDeYusa = false;

    /**
     * {@code true} durante los 5 segundos en que se muestran las cartas
     * frontales.
     * <p>
     * Bloquea {@link #actualizarInterfaz()} para evitar que el ciclo de polling
     * de Firebase sobreescriba las cartas reveladas con las cartas de espalda
     * antes de que terminen los 5 segundos de visión.</p>
     */
    private boolean mostrandoCartas = false;

    /**
     * Número máximo de intentos de carga de manos de Firebase antes de revelar.
     * <p>
     * El polling puede entregar el evento {@code REVELAR_CARTAS} antes de que
     * las manos estén completamente sincronizadas. Se reintenta hasta
     * {@code MAX_INTENTOS_MANOS} veces antes de proceder con lo disponible.</p>
     */
    private static final int MAX_INTENTOS_MANOS = 10;

    /**
     * Milisegundos de espera entre cada intento de carga de manos de Firebase.
     * <p>
     * 400ms da tiempo suficiente para que el polling reciba las manos sin
     * saturar Firebase con peticiones HTTP excesivas en un tiempo muy
     * corto.</p>
     */
    private static final int DELAY_REINTENTO_MS = 400;

    /**
     * Cola de poseedores de yusa que aún deben preguntar en la ronda actual.
     * <p>
     * El primer elemento de la lista es quien pregunta ahora. Cuando un
     * poseedor termina su pregunta, se elimina del frente de la cola en
     * {@link #procesarPaloRecibido(String, String)}. Se baraja aleatoriamente
     * con {@code Collections.shuffle()} en {@link #iniciarFaseYusa()} para que
     * el orden de preguntas sea diferente cada ronda.</p>
     */
    private final List<String> colaYusas = new ArrayList<>();

    /**
     * Lista de todos los duelos de yusa que deben resolverse tras la revelación
     * de 5 segundos.
     * <p>
     * Cada entrada es un {@link DueloYusa} creado en
     * {@link #procesarPaloRecibido(String, String)} cuando el objetivo
     * responde. Se resuelven todos juntos en
     * {@link #resolverDueloYusaTrasRevelacion()} después de los 5 segundos,
     * permitiendo múltiples yusas simultáneas.</p>
     */
    private final List<DueloYusa> duelosPendientesYusa = new ArrayList<>();

    /**
     * Record inmutable que encapsula el contexto completo de un duelo de yusa.
     *
     * <p>
     * Un record es una clase de datos inmutable cuyo constructor, getters,
     * {@code equals()}, {@code hashCode()} y {@code toString()} se generan
     * automáticamente. Los campos se acceden con {@code duelo.poseedor()},
     * {@code duelo.objetivo()} y {@code duelo.paloElegido()}.</p>
     *
     * @param poseedor UID del jugador que tiene la yusa y realizó la pregunta.
     * @param objetivo UID del jugador que intentó adivinar el palo.
     * @param paloElegido palo que eligió el objetivo como respuesta.
     */
    private record DueloYusa(String poseedor, String objetivo, JuegoYusa.Palo paloElegido) {

    }

    // =========================================================================
    //  UI
    // =========================================================================
    /**
     * Referencia al panel de botones de decisión visible actualmente en
     * pantalla.
     * <p>
     * Puede contener botones de decisión de ronda normal, de palo de yusa o el
     * botón del 12. {@code null} cuando no hay ningún panel visible. Se elimina
     * del {@code rootSala} en {@link #ocultarPanelDecision()}.</p>
     */
    private HBox panelDecision = null;

    // =========================================================================
    //  HOOKS - implementación de los métodos abstractos de la base
    // =========================================================================
    /**
     * Crea el motor {@link JuegoYusa} y carga las vidas almacenadas en
     * Firebase.
     *
     * <p>
     * Solo crea el motor si el modo es exactamente "Yusa". Después de
     * instanciar {@link JuegoYusa}, intenta leer las vidas del nodo
     * {@code partida/vidas} de Firebase y cargarlas en el motor. Esto es
     * esencial para cubrir el caso de reconexión a mitad de partida: sin cargar
     * las vidas desde Firebase, el motor empezaría con las vidas iniciales y
     * los jugadores que ya habían perdido vidas las recuperarían.</p>
     *
     * @param modo cadena del modo de juego leída del nodo {@code modo} de
     * Firebase
     * @return nueva instancia de {@link JuegoYusa} con vidas cargadas, o
     * {@code null} si el modo no es "Yusa"
     */
    @Override
    protected Juego crearJuego(String modo) {
        if (!"Yusa".equals(modo)) {
            return null;
        }
        JuegoYusa j = new JuegoYusa();
        try {
            Map<String, Integer> v = bd.leerVidas(codigoSala, idToken);
            if (v != null && !v.isEmpty()) {
                j.cargarVidasDesdeBD(v); // restaurar vidas reales, no usar las iniciales del constructor
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return j;
    }

    /**
     * Prepara el estado inicial de la partida Yusa. Solo el host ejecuta la
     * inicialización completa; el resto espera a que el host publique en
     * Firebase.
     *
     * <p>
     * Pasos que realiza:</p>
     * <ol>
     * <li>Si la lista de jugadores vivos está vacía (primera carga), la
     * inicializa con todos los UIDs que tienen mano asignada.</li>
     * <li>Lee el UID del host desde Firebase y compara con {@code uidLocal}. El
     * host es el jugador que creó la sala, no necesariamente el primero.</li>
     * <li>Si es el host: inicializa la partida en el motor (que en Yusa solo
     * prepara la lista de vivos), publica las vidas iniciales en Firebase para
     * que todos los clientes las vean, y lanza {@link #iniciarRondaYusa()} en
     * el hilo de JavaFX con {@code Platform.runLater} para dar tiempo a que la
     * escena esté completamente construida.</li>
     * <li>Imprime logs de diagnóstico del mapa de nombres para verificar que
     * {@link PartidaControllerBase#precargarNombres()} funcionó
     * correctamente.</li>
     * </ol>
     *
     * @param partida mapa con todos los campos del nodo de partida de Firebase
     */
    @Override
    protected void prepararEstadoInicial(Map<String, Object> partida) {
        // Si la lista de vivos está vacía pero hay manos, inicializar con todos los que tienen mano
        if (yusa().getJugadoresVivos().isEmpty() && !manos.isEmpty()) {
            yusa().inicializarJugadoresVivos(new ArrayList<>(manos.keySet()));
        }

        try {
            // Firebase devuelve strings entre comillas: "\"uid123\"" - quitar las comillas
            String uidHost = db.leerNodo("salas/" + codigoSala + "/host", idToken)
                    .replace("\"", "");
            if (uidLocal.equals(uidHost)) {
                // Solo el host inicia la partida para evitar que dos clientes repartan a la vez
                juego.iniciarPartida(manos, baraja);
                bd.actualizarVidas(codigoSala, yusa().getTodasLasVidas(), idToken);
                // Platform.runLater: diferir al hilo de JavaFX para que la escena esté lista
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
        // Logs de diagnóstico: verificar que precargarNombres() llenó el mapa correctamente
        System.out.println("[NOMBRES] mapa: " + nombres);
        System.out.println("[NOMBRES] uidLocal: " + uidLocal);
        System.out.println("[NOMBRES] uidTurnoActual: " + uidTurnoActual);
    }

    /**
     * Sobreescritura que protege la revelación de cartas de ser interrumpida
     * por el ciclo de polling de Firebase.
     *
     * <p>
     * Durante los 5 segundos en que se muestran las cartas frontales
     * ({@link #mostrandoCartas} == {@code true}), el método de la clase base
     * habría redibujado los abanicos con las cartas de espalda, destruyendo
     * visualmente la revelación. Esta sobreescritura bloquea el redibujado
     * mientras la revelación está activa. Cuando termina,
     * {@link #ocultarCartasDeRonda()} establece {@code mostrandoCartas = false}
     * y llama a {@code super.actualizarInterfaz()} para restaurar el estado
     * visual normal.</p>
     */
    @Override
    protected void actualizarInterfaz() {
        if (mostrandoCartas) {
            System.out.println("[YUSA] actualizarInterfaz() bloqueada - cartas mostrándose.");
            return;
        }
        super.actualizarInterfaz();
    }

    /**
     * Registra los tres listeners de Firebase específicos del modo Yusa.
     *
     * <p>
     * <strong>Listener 1 - estadoRonda:</strong> canal director - todos. Recibe
     * las acciones que el director publica. Incluye dos niveles de protección
     * contra duplicados:</p>
     * <ul>
     * <li>Deduplicación por timestamp ({@link #ultimoEstadoTs}): descarta
     * estados cuyo {@code ts} ya fue procesado.</li>
     * <li>Ventana de protección ({@link #VENTANA_PROTECCION_MS}): ignora
     * {@code REVELAR_CARTAS} que lleguen demasiado pronto tras la última
     * revelación.</li>
     * </ul>
     * <p>
     * Firebase devuelve el timestamp como {@link Number} genérico porque Gson
     * puede deserializarlo como {@code Double} o {@code Long} dependiendo del
     * valor. {@code ((Number) tsObj).longValue()} convierte de forma segura
     * cualquiera de los dos a {@code long}.</p>
     * <p>
     * Firebase serializa {@code null} como la cadena {@code "null"} en JSON,
     * por eso se comprueba {@code "null".equals(turnoUid)} y se convierte a
     * {@code null} Java real.</p>
     *
     * <p>
     * <strong>Listener 2 - decisionJugador:</strong> canal no-director -
     * director. Solo el director procesa este canal. La guarda inicial
     * {@code !uidLocal.equals(uidTurnoActual)} hace que los no-directores
     * ignoren completamente todas las decisiones recibidas.</p>
     *
     * <p>
     * <strong>Listener 3 - vidaJugador:</strong> escucha cambios en las vidas
     * del jugador local en {@code partida/vidas/{uidLocal}}. Cuando llegan a 0,
     * muestra el overlay de eliminado si no estaba visible. La comprobación
     * {@code overlayEliminado == null} evita crear el overlay dos veces.</p>
     */
    @Override
    protected void registrarListenersPropios() {

        // -- Canal principal: estadoRonda - director publica, todos escuchan -----
        registrarHiloListener(bd.escucharEstadoRonda(codigoSala, idToken, estado -> Platform.runLater(() -> {
            if (estado == null) {
                return;
            }
            Object tsObj = estado.get("ts");
            if (tsObj == null) {
                return;
            }
            // ((Number) tsObj).longValue(): Gson puede deserializar como Double o Long;
            // acceder mediante la interfaz Number es seguro con ambos tipos
            long ts = ((Number) tsObj).longValue();
            if (ts == ultimoEstadoTs) { // mismo timestamp = duplicado del polling, ignorar
                return;
            }

            // AÑADIR: si no hay ronda en curso y la acción es REVELAR_CARTAS, ignorar
            // Evita re-procesar el estadoRonda de la ronda anterior
            String accion = estado.get("accion") != null ? estado.get("accion").toString() : "";
            if ("REVELAR_CARTAS".equals(accion)) {
                long ahora = System.currentTimeMillis();
                if (ahora - tiempoFinUltimaRevelacion < VENTANA_PROTECCION_MS) {
                    // REVELAR_CARTAS dentro de la ventana de 3 segundos - ignorar
                    System.out.println("[YUSA] REVELAR_CARTAS ignorado - muy cerca de la última revelación (" + (ahora - tiempoFinUltimaRevelacion) + "ms)");
                    ultimoEstadoTs = ts; // marcar como procesado para no repetir este log
                    return;
                }
            }
            ultimoEstadoTs = ts; // actualizar el último timestamp procesado

            String fase = extraerString(estado, "fase");
            String turnoUid = extraerString(estado, "turnoUid");
            // Firebase serializa null Java como la cadena "null" en JSON; corregir
            if ("null".equals(turnoUid)) {
                turnoUid = null;
            }

            if (fase != null) {
                try {
                    // FaseRonda.valueOf() convierte la cadena al enum; lanza excepción si es desconocida
                    faseRondaActual = JuegoYusa.FaseRonda.valueOf(fase);
                } catch (IllegalArgumentException ignored) {
                    // Valor de fase desconocido: mantener el valor anterior sin romper
                }
            }

            procesarAccion(accion, turnoUid);
        })));

        // ----- Canal de respuesta: decisionJugador - solo lo procesa el director -------
        registrarHiloListener(bd.escucharDecisionJugador(codigoSala, idToken, datos -> Platform.runLater(() -> {
            // Guarda de director: si no soy el que tiene el turno, ignorar completamente
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
            if (ts == ultimaDecisionTs) { // deduplicar por timestamp
                return;
            }
            ultimaDecisionTs = ts;

            String uid = extraerString(datos, "uid");
            String decision = extraerString(datos, "decision");
            if (uid == null || decision == null) {
                return;
            }

            procesarDecisionRecibida(uid, decision);
        })));

        // ----- Listener de vidas: detectar eliminación del jugador local ---------
        registrarHiloListener(bd.escucharVidaJugador(codigoSala, uidLocal, idToken, vidas -> {
            Platform.runLater(() -> {
                if (controladorDestruido) {
                    return;
                }
                // Mostrar overlay solo si: vidas = 0, no hay overlay ya, y la partida no terminó
                if (vidas == 0 && overlayEliminado == null && !partidaFinalizada) {
                    System.out.println("[ELIMINADO] Detectado por listener de vidas: " + uidLocal);
                    mostrarMensajeEliminado();
                }
            });
        }));

    }

    /**
     * Método auxiliar que extrae un valor {@code String} de un mapa de forma
     * segura.
     * <p>
     * Evita repetir el patrón {@code value == null ? null : value.toString()} a
     * lo largo del código. Si la clave no existe o su valor es {@code null},
     * devuelve {@code null}.</p>
     *
     * @param map mapa del que extraer el valor
     * @param key clave a buscar
     * @return el valor como {@code String}, o {@code null} si no existe o es
     * nulo
     */
    private String extraerString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    /**
     * Método central que interpreta cada acción recibida del canal estadoRonda
     * y ejecuta la lógica correspondiente en <em>este</em> cliente.
     *
     * <p>
     * Cada acción tiene un efecto diferente según si el cliente es el
     * destinatario directo (turnoUid == uidLocal) o un observador.</p>
     *
     * <h3>Descripción por acción</h3>
     * <ul>
     * <li><strong>ESPERANDO_DECISION:</strong> si soy turnoUid, mostrar botones
     * "Me la quedo" / "La cambio". El lambda captura si el texto del botón
     * pulsado contiene "quedo" para determinar la decisión.</li>
     * <li><strong>ESPERANDO_DECISION_ULTIMO:</strong> si soy turnoUid, mostrar
     * botones de última decisión (quedarse o cambiar por la baraja).</li>
     * <li><strong>JUGAR_DOCE:</strong> si soy turnoUid, mostrar el botón del 12
     * con countdown; si no, mostrar mensaje privado informativo.</li>
     * <li><strong>ELEGIR_OBJETIVO:</strong> si soy el poseedor (turnoUid ==
     * uidLocal), instrucción privada de elegir rival. Si es otro jugador,
     * guardar su UID en {@link #uidPoseedorYusaActual} para usarlo en la
     * narración posterior.</li>
     * <li><strong>ELEGIR_PALO:</strong> si soy turnoUid, establecer
     * {@link #soyObjetivoDeYusa} = true y mostrar botones de palo. La
     * comprobación {@code panelDecision == null} evita crear un segundo panel
     * si el listener llega dos veces.</li>
     * <li><strong>REVELAR_CARTAS:</strong> cancelar cualquier revelación previa
     * pendiente (parar {@link #pausaRevelado}) y cargar manos frescas para
     * revelar.</li>
     * <li><strong>ESPECTADOR_DESEMPATE:</strong> {@code turnoUid} contiene los
     * UIDs de empatados separados por coma. Se separan con {@code split(",")}.
     * Si no estoy en la lista, mostrar overlay bloqueante de espectador.</li>
     * <li><strong>FIN_DESEMPATE:</strong> limpiar estado de desempate y ocultar
     * overlay.</li>
     * </ul>
     *
     * @param accion cadena con la acción (p.ej. "REVELAR_CARTAS")
     * @param turnoUid UID del jugador destinatario, o {@code null} para todos
     */
    private void procesarAccion(String accion, String turnoUid) {
        if (accion == null) {
            return;
        }
        switch (accion) {

            case "ESPERANDO_DECISION" -> {
                if (uidLocal.equals(turnoUid)) {
                    narrarPrivado(uidLocal,
                            IdiomaManager.get("yusaOnline.privado.teGustaTuCarta"));
                    mostrarBotonesDecision(
                            IdiomaManager.get("yusaOnline.ui.meLaQuedo"),
                            IdiomaManager.get("yusaOnline.ui.laCambioConSiguiente"),
                            dec -> enviarDecision(dec.contains("quedo") ? "QUEDAR" : "CAMBIAR")
                    );
                }
            }

            case "ESPERANDO_DECISION_ULTIMO" -> {
                if (uidLocal.equals(turnoUid)) {
                    narrarPrivado(uidLocal,
                            IdiomaManager.get("yusaOnline.privado.ultimoTeQuedasOCambias"));
                    mostrarBotonesDecision(
                            IdiomaManager.get("yusaOnline.ui.meLaQuedo"),
                            IdiomaManager.get("yusaOnline.ui.cambioPorBaraja"),
                            dec -> enviarDecision(dec.contains("quedo") ? "QUEDAR_ULTIMO" : "ROBAR_ULTIMO")
                    );
                }
            }

            case "JUGAR_DOCE" -> {
                if (uidLocal.equals(turnoUid)) {
                    mostrarBotonJugarDoce();
                } else {
                    // Informar a los demás de quién tiene el 12 y que esperen
                    narrarPrivado(uidLocal, IdiomaManager.get("yusaOnline.privado.otroTieneDoce",
                            nombres.getOrDefault(turnoUid, turnoUid)
                    )
                    );
                }
            }

            case "ELEGIR_OBJETIVO" -> {
                // turnoUid = poseedor que debe elegir AHORA
                if (turnoUid != null && turnoUid.equals(uidLocal) && tieneYusa(uidLocal)) {
                    // Soy el poseedor que debe elegir objetivo: instrucción privada
                    narrarPrivado(uidLocal, IdiomaManager.get("yusaOnline.privado.esTuTurnoYusa"));
                } else if (turnoUid != null && !turnoUid.equals(uidLocal)) {
                    // Otro jugador es el poseedor: guardar para narración en onElegirPaloYusa
                    uidPoseedorYusaActual = turnoUid;
                    narrarPrivado(uidLocal, IdiomaManager.get("yusaOnline.privado.otroEligiendoObjetivo",
                            nombres.getOrDefault(turnoUid, turnoUid)
                    )
                    );
                }
            }

            case "ELEGIR_PALO" -> {
                if (uidLocal.equals(turnoUid)) {
                    soyObjetivoDeYusa = true; // habilitar respuesta en este cliente
                    if (panelDecision == null) {  // evitar doble panel si el evento llega dos veces
                        narrarPrivado(uidLocal, IdiomaManager.get("yusaOnline.privado.tePreguntanPalo")
                        );
                        mostrarBotonesPalo();
                    }
                }
            }

            case "REVELAR_CARTAS" -> {
                // Cancelar revelación previa si estaba en curso (evita anidamiento)
                if (pausaRevelado != null) {
                    pausaRevelado.stop();
                    pausaRevelado = null;
                }
                cargarManosYRevelar();
            }

            case "ESPECTADOR_DESEMPATE" -> {
                // turnoUid = "uid1,uid2": split(",") divide por coma
                // Arrays.asList() crea una lista no modificable a partir del array
                if (turnoUid != null) {
                    List<String> empatados = Arrays.asList(turnoUid.split(","));
                    empatadosActuales = empatados;
                    enDesempate = true;

                    if (!empatados.contains(uidLocal)) {
                        // No participo en el desempate: bloquear mi pantalla como espectador
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

    /**
     * Inicia una nueva ronda si es el turno local y no hay una ronda ya activa.
     *
     * <p>
     * La comprobación {@code rondaEnCurso} es esencial para evitar la condición
     * de carrera en la que el listener de turno llega mientras el director ya
     * está ejecutando una ronda: el listener dispararía {@code onCambioTurno}
     * que sin esa guarda llamaría a {@link #iniciarRondaYusa()} de nuevo,
     * creando dos rondas paralelas con estado inconsistente.</p>
     *
     * @param nuevoTurno UID del jugador al que le toca el turno
     */
    @Override
    protected void onCambioTurno(String nuevoTurno) {
        if (!uidLocal.equals(nuevoTurno)) { // solo actuar si el turno es mío
            return;
        }
        if (rondaEnCurso) { // ya hay ronda activa: no iniciar otra
            return;
        }
        try {
            iniciarRondaYusa();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * En Yusa el mazo no se usa con clic directo; las acciones se hacen con
     * botones. Informa al jugador del mecanismo correcto.
     */
    @Override
    protected void onClickMazo() {
        narrarPrivado(uidLocal, IdiomaManager.get("yusaOnline.privado.usaBotones"));
    }

    /**
     * Clic en zona de rival: solo actúa en la fase YUSA para que el poseedor
     * elija su objetivo. En cualquier otra fase, el clic no tiene efecto.
     *
     * @param zone UID del rival cuya zona fue pulsada
     */
    @Override
    protected void onZonaRivalClick(String zone) {
        if (faseRondaActual == JuegoYusa.FaseRonda.YUSA) {
            onElegirObjetivoYusa(zone);
        }
    }

    @Override
    protected void onCartaLocalClick(String ruta) {
        /* no se usa */ }

    /**
     * Construye los datos del popup de fin de partida basándose en las vidas
     * restantes.
     *
     * <p>
     * Lee las vidas directamente de Firebase en lugar de usar el estado local,
     * para garantizar que <em>todos</em> los clientes muestren exactamente el
     * mismo resultado. Si usaran sus estados locales, pequeñas
     * desincronizaciones de red podrían hacer que clientes distintos mostraran
     * ganadores distintos.</p>
     *
     * <p>
     * Lógica de resultado:</p>
     * <ul>
     * <li>1 superviviente: gana ese jugador.</li>
     * <li>0 supervivientes: empate (todos murieron en la misma ronda).</li>
     * <li>Más de 1 superviviente: situación anormal en Yusa; se muestra al que
     * tiene más vidas usando {@code Comparator.comparingInt(vidas::get)}, que
     * compara los UIDs por el valor en el mapa {@code vidas}.</li>
     * </ul>
     *
     * <p>
     * El plural de "vida/vidas" y "restante/restantes" se gestiona con
     * operadores ternarios: {@code (v != 1 ? "s" : "")} añade la "s" solo
     * cuando hay más de uno.</p>
     *
     * @return {@link DatosPopUp} con icono, texto de resultado y texto de
     * detalle
     */
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
                .filter(e -> e.getValue() > 0) // vida positiva
                .map(Map.Entry::getKey) // extraemos solo UID
                .collect(java.util.stream.Collectors.toList());

        String resultado;
        String detalle;

        if (supervivientes.size() == 1) {
            String uidGanador = supervivientes.get(0);
            String nombre = nombres.getOrDefault(uidGanador, uidGanador);
            int vidasGanador = vidas.getOrDefault(uidGanador, 1);
            // Siempre el mismo mensaje para todos los clientes
            resultado = IdiomaManager.get(
                    "yusaOnline.popUpFinal.victoria",
                    nombre
            );
            // Plural condicional: "1 vida restante" vs "2 vidas restantes"
            detalle = detalle = IdiomaManager.get(
                    "yusaOnline.popUpFinal.detalleVictoria",
                    vidasGanador
            );
        } else if (supervivientes.isEmpty()) {
            resultado = IdiomaManager.get("yusaOnline.popUpFinal.empate");
            detalle = IdiomaManager.get("yusaOnline.popUpFinal.detalleEmpateNadie");
        } else {
            // Más de un superviviente (no debería ocurrir en Yusa normal)
            // Mostrar al que tiene más vidas
            String uidMasVidas = supervivientes.stream()
                    .max(java.util.Comparator.comparingInt(vidas::get))
                    .orElse(supervivientes.get(0));
            String nombre = nombres.getOrDefault(uidMasVidas, uidMasVidas);
            int vidasMax = vidas.getOrDefault(uidMasVidas, 1);
            resultado = IdiomaManager.get(
                    "yusaOnline.popUpFinal.victoria",
                    nombre
            );
            detalle = IdiomaManager.get(
                    "yusaOnline.popUpFinal.detalleVictoria",
                    vidasMax
            );
        }

        String icono;
        // Icono: ganador si hay 1 superviviente, empate si no
        if (supervivientes.size() == 1) {
            icono = "/ui/graphicResources/imagenes/imgGanador.png";
        } else {
            icono = "/ui/graphicResources/imagenes/imgEmpate.png";
        }

        return new DatosPopUp(icono, resultado, detalle);
    }

    // =========================================================================
    //  INICIO DE RONDA - solo el director
    // =========================================================================
    /**
     * Inicia una nueva ronda de Yusa. Solo lo ejecuta el director
     * (uidTurnoActual).
     *
     * <p>
     * Pasos en orden:</p>
     * <ol>
     * <li>Establecer {@code rondaEnCurso = true} y resetear todos los campos de
     * estado.</li>
     * <li>Leer las vidas actuales de Firebase (pueden haber cambiado desde la
     * última ronda si hubo empates o eliminaciones).</li>
     * <li>Repartir 1 carta a cada jugador vivo con
     * {@link JuegoYusa#repartirCartas}.</li>
     * <li>Publicar las manos y la baraja actualizada en Firebase.</li>
     * <li>Determinar la fase según las cartas: si alguien tiene el número
     * especial yusa - fase YUSA; si alguien tiene el 12 - fase DOCE; si no -
     * fase NORMAL.</li>
     * <li>Narrar el inicio de ronda.</li>
     * <li>Arrancar la fase correspondiente.</li>
     * </ol>
     *
     * <p>
     * {@code ultimoEstadoTs = -1L} al inicio resetea el timestamp para que el
     * listener no ignore los nuevos eventos de esta ronda por tener el mismo
     * timestamp que los de la ronda anterior.</p>
     *
     * @throws IOException si alguna llamada a Firebase falla
     */
    private void iniciarRondaYusa() throws IOException {
        rondaEnCurso = true;
        ultimoEstadoTs = -1L; // resetear para no ignorar los eventos de la nueva ronda
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
                yusa().cargarVidasDesdeBD(v); // sincronizar vidas antes de repartir
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        yusa().repartirCartas(manos, baraja); // reparte 1 carta a cada jugador vivo
        bd.publicarManosYusa(codigoSala, manos, idToken); // publicar para todos los clientes
        bd.actualizarBaraja(codigoSala, baraja, idToken); // actualizamos baraja

        faseRondaActual = yusa().determinarFaseRonda(manos); // analizar las cartas repartidas
        narrarGlobal(
                IdiomaManager.get(
                        "yusaOnline.global.rondaNueva",
                        textoFase(faseRondaActual)
                )
        );

        narrarGlobal(
                IdiomaManager.get(
                        "yusaOnline.global.comienzaRonda",
                        nombres.getOrDefault(uidTurnoActual, uidTurnoActual),
                        textoFase(faseRondaActual)
                )
        );

        // dependiendo de la fase, se jugará de una manera u otra
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
     * Convierte el enum {@link JuegoYusa.FaseRonda} a un texto legible para el
     * narrador.
     *
     * @param f fase de ronda a describir
     * @return cadena descriptiva de la fase
     */
    private String textoFase(JuegoYusa.FaseRonda f) {
        return switch (f) {
            case YUSA ->
                IdiomaManager.get("yusaOnline.fase.yusa");
            case DOCE ->
                IdiomaManager.get("yusaOnline.fase.doce");
            case NORMAL ->
                IdiomaManager.get("yusaOnline.fase.normal");
        };
    }

    // =========================================================================
    //  RONDA NORMAL - turno de decisiones
    // =========================================================================
    /**
     * Inicia la fase normal calculando el orden de decisiones y publicando la
     * primera.
     *
     * @throws IOException si {@link #publicarSiguienteDecision()} falla
     */
    private void iniciarFaseNormal() throws IOException {
        ordenRondaActual.clear();
        ordenRondaActual.addAll(calcularOrdenRonda()); // construir la lista rotada
        publicarSiguienteDecision();
    }

    /**
     * Publica en Firebase la siguiente decisión pendiente de la lista.
     *
     * <p>
     * Si la lista está vacía, todos los jugadores ya decidieron - publicar
     * {@code REVELAR_CARTAS}. Si queda exactamente 1 jugador, es el último y
     * tiene opciones adicionales (puede cambiar por la baraja). Si quedan más,
     * es una decisión normal (quedarse o cambiar con el siguiente).</p>
     *
     * <p>
     * <strong>Optimización de latencia:</strong> si el siguiente jugador soy yo
     * (el director), se muestran los botones directamente sin esperar al
     * listener propio. Esto evita un ciclo de polling innecesario (publicar -
     * leer - procesar) que añadiría latencia al director.</p>
     *
     * @throws IOException si las llamadas a Firebase fallan
     */
    private void publicarSiguienteDecision() throws IOException {
        if (ordenRondaActual.isEmpty()) {
            publicarRevelarCartas(); // todos deciden - revelar cartas
            return;
        }

        String siguiente = ordenRondaActual.get(0); // el primero de la lista es quien decide ahora
        boolean esUltimo = ordenRondaActual.size() == 1; // true si solo queda él
        String accion = esUltimo ? "ESPERANDO_DECISION_ULTIMO" : "ESPERANDO_DECISION";

        bd.publicarEstadoRonda(codigoSala, accion, faseRondaActual.name(), siguiente, idToken);

        // OPTIMIZACIÓN: Si soy yo el que debe decidir, mostrar botones directamente
        if (siguiente.equals(uidLocal)) {
            if (esUltimo) {
                narrarPrivado(uidLocal, IdiomaManager.get("yusaOnline.privado.ultimoTeQuedasOCambias"));
                mostrarBotonesDecision(
                        IdiomaManager.get("yusaOnline.ui.meLaQuedo"),
                        IdiomaManager.get("yusaOnline.ui.cambioPorBaraja"),
                        dec -> procesarDecisionPropia(dec.contains("quedo") ? "QUEDAR_ULTIMO" : "ROBAR_ULTIMO")
                );
            } else {
                narrarPrivado(uidLocal, IdiomaManager.get("yusaOnline.privado.teGustaTuCarta")
                );
                mostrarBotonesDecision(
                        IdiomaManager.get("yusaOnline.ui.meLaQuedo"),
                        IdiomaManager.get("yusaOnline.ui.laCambioConSiguiente"),
                        dec -> procesarDecisionPropia(dec.contains("quedo") ? "QUEDAR" : "CAMBIAR")
                );
            }
        }
    }

    /**
     * Calcula el orden en que los jugadores deben tomar sus decisiones en la
     * ronda.
     *
     * <p>
     * En ronda normal participan todos los jugadores vivos. En desempate solo
     * participan los jugadores en {@link #empatadosActuales}.</p>
     *
     * <p>
     * El orden respeta el orden global de la sala, pero rotado para que el
     * director (uidTurnoActual) vaya primero. La rotación usa
     * {@code subList}:</p>
     * <ul>
     * <li>{@code subList(index, size)}: elementos desde el director hasta el
     * final.</li>
     * <li>{@code subList(0, index)}: elementos del inicio hasta el director.</li>
     * </ul>
     * <p>
     * La concatenación de ambas sublistas produce la lista rotada. Si el
     * director no está en la lista (índice -1), se devuelve la lista sin rotar
     * como fallback.</p>
     *
     * @return lista de UIDs en el orden de decisión, con el director primero
     */
    private List<String> calcularOrdenRonda() {
        // En desempate, solo participan los empatados
        List<String> base = (empatadosActuales != null && !empatadosActuales.isEmpty())
                ? empatadosActuales
                : yusa().getJugadoresVivos();

        // Respetar el orden global de sala
        Set<String> baseSet = new HashSet<>(base);
        List<String> ordenados = ordenJugadoresGlobal.stream()
                .filter(baseSet::contains).collect(Collectors.toList());

        int index = ordenados.indexOf(uidTurnoActual);
        if (index < 0) {
            return ordenados; // el director no está en la lista: devolver sin rotar
        }

        // Rotar la lista para que el director quede en el índice 0
        List<String> r = new ArrayList<>();
        r.addAll(ordenados.subList(index, ordenados.size())); // (director...último)
        r.addAll(ordenados.subList(0, index)); // (primero ... anterior al director)
        return r;
    }

    /**
     * El director toma su propia decisión directamente, sin pasar por Firebase.
     * <p>
     * Oculta el panel de botones y llama a
     * {@link #ejecutarDecision(String, String)} directamente en lugar de enviar
     * al canal {@code decisionJugador}.</p>
     *
     * @param decision cadena de la decisión ("QUEDAR", "CAMBIAR",
     * "QUEDAR_ULTIMO", "ROBAR_ULTIMO")
     */
    private void procesarDecisionPropia(String decision) {
        ocultarPanelDecision();
        try {
            ejecutarDecision(uidLocal, decision);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * El director recibe la decisión de un no-director desde el canal
     * decisionJugador.
     *
     * <p>
     * Primero limpia el nodo {@code decisionJugador} en Firebase para evitar
     * que si el listener hace polling rápido procese la misma decisión dos
     * veces. Si la decisión comienza con "PALO_", es una respuesta de yusa y se
     * delega a {@link #procesarPaloRecibido(String, String)}. Si no, es una
     * decisión de ronda normal y se ejecuta con
     * {@link #ejecutarDecision(String, String)}.</p>
     *
     * @param uid UID del jugador que tomó la decisión
     * @param decision cadena con la decisión recibida
     */
    private void procesarDecisionRecibida(String uid, String decision) {
        try {
            bd.limpiarDecisionJugador(codigoSala, idToken); // limpiar para no reprocesar
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (decision.startsWith("PALO_")) {
            procesarPaloRecibido(uid, decision); // decisión de yusa
            return;
        }

        try {
            ejecutarDecision(uid, decision); // decisión de ronda normal
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Ejecuta la decisión de un jugador y avanza al siguiente en la lista.
     *
     * <p>
     * Decisiones con "ULTIMO": el último jugador de la ronda puede robar de la
     * baraja o quedarse. Si roba, se llama a
     * {@link #ejecutarRoboUltimo(String)} que puede cambiar la fase. Se elimina
     * el jugador de la lista y se retorna.</p>
     *
     * <p>
     * Decisiones normales:</p>
     * <ul>
     * <li>{@code CAMBIAR}: intercambiar carta con el siguiente de la lista
     * ({@code ordenRondaActual.get(1)}). Se requiere que haya al menos 2
     * jugadores restantes para que exista un "siguiente".</li>
     * <li>{@code QUEDAR}: no hacer nada, solo narrar.</li>
     * </ul>
     *
     * <p>
     * Al final, {@code ordenRondaActual.remove(0)} elimina al jugador que acaba
     * de decidir (siempre es el primero de la lista), y se publica la siguiente
     * decisión.</p>
     *
     * @param uid UID del jugador que tomó la decisión
     * @param decision cadena de la decisión
     * @throws IOException si las llamadas a Firebase fallan
     */
    private void ejecutarDecision(String uid, String decision) throws IOException {
        if (decision.contains("ULTIMO")) {
            ordenRondaActual.remove(uid); // el último no estará en la lista al llamar a publicarRevela...
            if (decision.contains("ROBAR")) {
                ejecutarRoboUltimo(uid); // cambiar carta por la baraja
            } else {
                publicarRevelarCartas(); // quedarse: revelar directamente
            }
            return;
        }
        if ("CAMBIAR".equals(decision) && ordenRondaActual.size() > 1) {
            String sig = ordenRondaActual.get(1); // el siguiente en la lista = con quien cambia
            yusa().intercambiarCartas(uid, sig, manos); // motor: intercambiar cartas en el mapa
            bd.publicarManosYusa(codigoSala, manos, idToken); // todos deben ver el intercambio
            narrarGlobal(
                    IdiomaManager.get("yusaOnline.global.intercambiaCarta",
                            nombres.getOrDefault(uid, uid),
                            nombres.getOrDefault(sig, sig)
                    )
            );
        } else if ("QUEDAR".equals(decision)) {
            narrarGlobal(
                    IdiomaManager.get("yusaOnline.global.seQuedaCarta",
                            nombres.getOrDefault(uid, uid)
                    )
            );
        }
        ordenRondaActual.remove(0); // eliminar al jugador que acaba de decidir (siempre el primero)
        publicarSiguienteDecision(); // publicar la decisión del siguiente jugador
    }

    /**
     * El no-director envía su decisión al canal {@code decisionJugador} de
     * Firebase.
     * <p>
     * El director la leerá en su listener {@code escucharDecisionJugador} y la
     * procesará en {@link #procesarDecisionRecibida(String, String)}.</p>
     *
     * @param decision cadena con la decisión a enviar (p.ej. "QUEDAR",
     * "PALO_CORONAS")
     */
    private void enviarDecision(String decision) {
        ocultarPanelDecision();
        try {
            bd.publicarDecisionJugador(codigoSala, uidLocal, decision, idToken);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * El último jugador de la ronda roba una carta de la baraja en lugar de la
     * suya.
     *
     * <p>
     * Proceso:</p>
     * <ol>
     * <li>Si la baraja está vacía, no puede robar - revelar directamente (caso
     * imposible)</li>
     * <li>{@code computeIfAbsent}: obtiene la mano si existe, la crea vacía si
     * no. Más idiomático que comprobar {@code containsKey} + {@code put}.</li>
     * <li>Descartar la carta actual añadiéndola a {@code descarte} y limpiar la
     * mano.</li>
     * <li>{@code baraja.remove(0)}: robar la primera carta del mazo (top del
     * mazo). Se añade a la mano del jugador.</li>
     * <li>Publicar los cambios en Firebase.</li>
     * <li>Re-evaluar la fase con las nuevas cartas. Si la carta robada es
     * especial (yusa o 12), la fase cambia y se inicia la nueva fase en lugar
     * de revelar.</li>
     * </ol>
     *
     * @param uid UID del último jugador que roba de la baraja
     * @throws IOException si las llamadas a Firebase fallan
     */
    private void ejecutarRoboUltimo(String uid) throws IOException {
        if (baraja.isEmpty()) {
            narrarGlobal(IdiomaManager.get("yusaOnline.global.cambioBarajaVacia",
                    nombres.getOrDefault(uid, uid)
            )
            );
            publicarRevelarCartas();
            return;
        }
        // computeIfAbsent: crea la mano vacía si no existe, o la devuelve si ya existe
        List<String> mano = manos.computeIfAbsent(uid, k -> new ArrayList<>());
        if (!mano.isEmpty()) {
            descarte.add(mano.get(0)); // añadir la carta actual al descarte
            mano.clear(); // vaciar la mano antes de robar
        }
        mano.add(baraja.remove(0)); // robar la carta del top del mazo

        bd.publicarManosYusa(codigoSala, manos, idToken);
        bd.actualizarBaraja(codigoSala, baraja, idToken);
        bd.actualizarDescarte(codigoSala, descarte, idToken);
        narrarGlobal(IdiomaManager.get("yusaOnline.global.cambioPorBaraja",
                nombres.getOrDefault(uid, uid)
        )
        );

        // Re-evaluar la fase: la carta robada puede cambiar de NORMAL a DOCE o YUSA
        JuegoYusa.FaseRonda nueva = yusa().determinarFaseRonda(manos);
        if (nueva != JuegoYusa.FaseRonda.NORMAL) {
            faseRondaActual = nueva;
            narrarGlobal(
                    IdiomaManager.get(
                            "yusaOnline.global.cambiaFase",
                            textoFase(nueva)
                    )
            );
            switch (nueva) {
                case DOCE ->
                    iniciarFaseDoce();
                case YUSA ->
                    iniciarFaseYusa();
                default ->
                    publicarRevelarCartas();
            }
        } else {
            publicarRevelarCartas(); // fase sigue siendo NORMAL - revelar
        }
    }

    // =========================================================================
    //  RONDA DOCE - el jugador con el 12 tiene 20 segundos
    // =========================================================================
    /**
     * Inicia la fase del 12: busca al jugador que tiene el número especial y lo
     * notifica.
     *
     * <p>
     * Si {@link #encontrarJugadorConNumero(int)} devuelve {@code null}, las
     * manos no están aún sincronizadas en este cliente. Se reintenta tras 400ms
     * con un {@link PauseTransition} en lugar de un bucle bloqueante, para no
     * congelar el hilo de JavaFX.</p>
     *
     * <p>
     * Una vez encontrado el jugador, publica {@code JUGAR_DOCE} en Firebase. Si
     * soy yo el director y tengo el 12, muestro el botón directamente sin
     * esperar al listener propio (optimización de latencia).</p>
     *
     * @throws IOException si la publicación en Firebase falla
     */
    private void iniciarFaseDoce() throws IOException {
        String uidDoce = encontrarJugadorConNumero(JuegoYusa.NUMERO_DOCE);
        if (uidDoce == null) {
            // Manos no sincronizadas aún: reintento no bloqueante tras 400ms
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
        narrarGlobal(
                IdiomaManager.get("yusaOnline.global.tieneDoceQueLoLance",
                        nombres.getOrDefault(uidDoce, uidDoce)
                )
        );

        if (uidDoce.equals(uidLocal)) {
            mostrarBotonJugarDoce(); // director local con el 12: optimización sin esperar listener
        }
    }

    /**
     * El jugador con el 12 lo juega: construye un mensaje con las cartas de
     * todos al descubierto y publica {@code REVELAR_CARTAS}.
     */
    private void onDoceJugado() {
        ocultarPanelDecision();
        StringBuilder sb = new StringBuilder(IdiomaManager.get("yusaOnline.global.cartasDescubiertas"));
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
    //  RONDA YUSA - cola de poseedores y duelos
    // =========================================================================
    /**
     * Inicia la fase yusa: construye la cola de poseedores y publica la primera
     * pregunta.
     *
     * <p>
     * La cola se baraja aleatoriamente con
     * {@code Collections.shuffle(colaYusas)}, que usa un
     * {@link java.util.Random} interno para reorganizar la lista. Esto hace que
     * el orden en que preguntan los poseedores sea diferente cada ronda,
     * evitando que el mismo jugador siempre vaya primero cuando hay múltiples
     * yusas.</p>
     *
     * @throws IOException si {@link #publicarSiguientePreguntaYusa()} falla
     */
    private void iniciarFaseYusa() throws IOException {
        colaYusas.clear();
        for (String uid : yusa().getJugadoresVivos()) {
            if (tieneYusa(uid)) {
                colaYusas.add(uid); // solo los que tienen la carta de yusa
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
            narrarGlobal(IdiomaManager.get("yusaOnline.global.unPoseedorYusa",
                    nombres.getOrDefault(colaYusas.get(0), colaYusas.get(0))
            )
            );

        } else {
            // Collectors.joining(", "): une todos los nombres con coma y espacio
            String nombresYusas = colaYusas.stream()
                    .map(u -> nombres.getOrDefault(u, u))
                    .collect(Collectors.joining(", "));

            narrarGlobal(IdiomaManager.get("yusaOnline.global.variasYusas",
                    colaYusas.size(),
                    nombresYusas
            )
            );
        }

        publicarSiguientePreguntaYusa();
    }

    /**
     * El poseedor de yusa elige al rival al que va a preguntar el palo.
     *
     * <p>
     * Validaciones antes de proceder:</p>
     * <ol>
     * <li>El jugador local debe tener la carta de yusa
     * ({@link #tieneYusa}).</li>
     * <li>No puede elegirse a sí mismo.</li>
     * <li>No puede elegir objetivo si ya lo eligió en esta ronda (protección
     * contra doble clic en la zona de un rival).</li>
     * <li>Si soy el director, debo ser el primero de la cola (mi turno de
     * yusa).</li>
     * </ol>
     *
     * <p>
     * Si pasa todas las validaciones:</p>
     * <ul>
     * <li>Guarda el objetivo en {@link #objetivosPorPoseedor}.</li>
     * <li>Guarda el snapshot de la carta propia ANTES de que las manos puedan
     * modificarse. Esto es crucial porque
     * {@link #resolverDueloYusaTrasRevelacion()} necesita saber qué carta tenía
     * el poseedor en el momento de la pregunta.</li>
     * <li>Publica el objetivo en Firebase y publica {@code ELEGIR_PALO} con
     * {@code turnoUid = uidObjetivo} para que el objetivo vea el panel de
     * palos.</li>
     * </ul>
     *
     * @param uidObjetivo UID del rival elegido como objetivo de la pregunta
     */
    private void onElegirObjetivoYusa(String uidObjetivo) {
        if (!tieneYusa(uidLocal)) {
            narrarPrivado(uidLocal,
                    IdiomaManager.get("yusaOnline.privado.noTienesYusa")
            );

            return;
        }
        if (uidObjetivo.equals(uidLocal)) {
            narrarPrivado(uidLocal,
                    IdiomaManager.get("yusaOnline.privado.noElegirte")
            );
            return;
        }
        if (objetivosPorPoseedor.containsKey(uidLocal)) {
            narrarPrivado(uidLocal,
                    IdiomaManager.get("yusaOnline.privado.objetivoYaElegido")
            );
            return;
        }
        // Si soy el director, verifico que soy el primero de la cola.
        if (uidLocal.equals(uidTurnoActual)
                && !colaYusas.isEmpty()
                && !colaYusas.get(0).equals(uidLocal)) {
            narrarPrivado(uidLocal, IdiomaManager.get("yusaOnline.privado.esperaTurnoYusa")
            );
            return;
        }

        objetivosPorPoseedor.put(uidLocal, uidObjetivo);

        // Guardar snapshot de la carta ANTES de que las manos se modifiquen
        List<String> m = manos.get(uidLocal);
        if (m != null && !m.isEmpty()) {
            snapshotCartas.put(uidLocal, m.get(0)); // snapshot individual del poseedor
        }

        narrarGlobal(IdiomaManager.get("yusaOnline.global.preguntaPaloYusa",
                nombres.getOrDefault(uidLocal, uidLocal),
                nombres.getOrDefault(uidObjetivo, uidObjetivo)
        )
        );

        try {
            bd.actualizarObjetivoYusa(codigoSala, uidLocal, uidObjetivo, idToken);
            // turnoUid = uidObjetivo: indica al objetivo que debe elegir el palo
            bd.publicarEstadoRonda(codigoSala, "ELEGIR_PALO",
                    JuegoYusa.FaseRonda.YUSA.name(), uidObjetivo, idToken);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * El objetivo de la yusa elige el palo que cree que tiene el poseedor.
     *
     * <p>
     * Solo actúa si {@link #soyObjetivoDeYusa} == {@code true} para evitar que
     * clientes no-objetivo ejecuten este método accidentalmente.</p>
     *
     * <p>
     * Envía la decisión al director con el formato
     * {@code "PALO_" + palo.name()}, por ejemplo
     * {@code "PALO_CORONAS"}, {@code "PALO_BALANZAS"}, etc.</p>
     *
     * <p>
     * {@link #uidPoseedorYusaActual} fue guardado en
     * {@link #procesarAccion(String, String)} al recibir
     * {@code ELEGIR_OBJETIVO}, para poder narrar el nombre del poseedor aquí
     * sin consultar Firebase.</p>
     *
     * @param palo palo elegido por el objetivo como respuesta (enum
     * {@link JuegoYusa.Palo})
     */
    private void onElegirPaloYusa(JuegoYusa.Palo palo) {
        if (!soyObjetivoDeYusa) { // protección: solo actuar si soy el objetivo
            return;
        }
        ocultarPanelDecision();
        soyObjetivoDeYusa = false; // limpiar para no responder dos veces
        enviarDecision("PALO_" + palo.name()); // formato "PALO_CORONAS", "PALO_BALANZAS", etc.
        narrarPrivado(uidLocal, IdiomaManager.get("yusaOnline.privado.elegistePalo",
                palo.name()
        )
        );
        // Usar el campo guardado en lugar de buscar en objetivosPorPoseedor
        String nomPoseedor = nombres.getOrDefault(uidPoseedorYusaActual, uidPoseedorYusaActual);
        narrarGlobal(IdiomaManager.get("yusaOnline.global.respondePaloYusa",
                nombres.getOrDefault(uidLocal, uidLocal),
                palo.name(),
                nomPoseedor
        )
        );
    }

    /**
     * El director recibe la respuesta de palo de un objetivo y gestiona la cola
     * de yusas.
     *
     * <p>
     * Este método lanza un hilo de background para hacer la lectura síncrona de
     * {@link firebase.BDPartidaService#leerObjetivosYusa} sin bloquear el hilo
     * de JavaFX. Cuando la lectura termina, vuelve al hilo de JavaFX con
     * {@code Platform.runLater} para modificar el estado y la UI.</p>
     *
     * <p>
     * Proceso dentro del {@code Platform.runLater}:</p>
     * <ol>
     * <li>Extraer el palo elegido del formato "PALO_NOMBRE" con
     * {@code replace}.</li>
     * <li>Combinar los objetivos de Firebase con los del mapa local usando
     * {@code putIfAbsent}: no sobreescribir datos locales más recientes.</li>
     * <li>Identificar al poseedor: primero busca en la cola (más fiable), luego
     * en el mapa como fallback para el caso de que la cola ya fue
     * avanzada.</li>
     * <li>Obtener la carta del poseedor: primero de la mano actual, luego del
     * snapshot como fallback (puede estar descartada en fase YUSA).</li>
     * <li>Guardar el duelo en {@link #duelosPendientesYusa} con todos los datos
     * necesarios para resolverlo tras la revelación.</li>
     * <li>Avanzar la cola eliminando al poseedor que ya preguntó.</li>
     * <li>Si quedan poseedores: publicar la siguiente
     * {@code ELEGIR_OBJETIVO}.</li>
     * <li>Si la cola está vacía: publicar {@code REVELAR_CARTAS}.</li>
     * </ol>
     *
     * @param uidObjetivo UID del jugador que eligió el palo
     * @param decision cadena con formato "PALO_NOMBRE" (p.ej. "PALO_CORONAS")
     */
    private void procesarPaloRecibido(String uidObjetivo, String decision) {
        String paloStr = decision.replace("PALO_", ""); // extraer el nombre del palo
        JuegoYusa.Palo paloElegido;
        try {
            paloElegido = JuegoYusa.Palo.valueOf(paloStr); // convertir String al enum
        } catch (IllegalArgumentException e) {
            System.out.println("WARN: palo desconocido: " + paloStr);
            return;
        }

        // Hilo de background: lectura síncrona de Firebase sin bloquear JavaFX
        new Thread(() -> {
            try {

                // Leer objetivos de Firebase para mapa completo
                Map<String, String> objetivosFirebase = bd.leerObjetivosYusa(codigoSala, idToken);
                if (objetivosFirebase != null) {
                    objetivosFirebase.forEach((pos, obj)
                            -> objetivosPorPoseedor.putIfAbsent(pos, obj));
                }

                // Volver al hilo de JavaFX para modificar el estado y la UI
                Platform.runLater(() -> {
                    String poseedor = null;

                    // Estrategia 1: buscar en la cola (el primero es quien acaba de responder)
                    if (!colaYusas.isEmpty()) {
                        poseedor = colaYusas.get(0);
                    } else {
                        // Estrategia 2: buscar en el mapa por el objetivo (fallback)
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

                    // Obtener la carta del poseedor: mano actual - snapshot como fallback
                    String cartaPoseedor = null;
                    List<String> mp = manos.get(poseedor);
                    if (mp != null && !mp.isEmpty()) {
                        cartaPoseedor = mp.get(0); // carta de la mano actual
                    } else {
                        cartaPoseedor = snapshotCartas.get(poseedor); // fallback: snapshot guardado en onElegirObjetivoYusa
                    }
                    if (cartaPoseedor == null) {
                        System.out.println("WARN: sin carta para " + poseedor);
                        return;
                    }

                    // Guardar el duelo con todos los datos para resolver después
                    duelosPendientesYusa.add(new DueloYusa(poseedor, uidObjetivo, paloElegido));
                    snapshotCartas.put(poseedor, cartaPoseedor); // asegurar snapshot

                    // Quitar poseedor de la cola
                    objetivosPorPoseedor.remove(poseedor);
                    if (!colaYusas.isEmpty()) {
                        colaYusas.remove(0); // avanzar la cola al siguiente poseedor
                    }

                    try {
                        if (!colaYusas.isEmpty()) {
                            // Quedan más poseedores - siguiente pregunta sin revelar aún
                            publicarSiguientePreguntaYusa();
                        } else {
                            // Todas las yusas respondidas - revelar cartas
                            // NO llamar guardarSnapshot(): ya guardados en onElegirObjetivoYusa
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

    /**
     * Descarta las manos al final de la ronda Yusa y llama a
     * {@link #finalizarCicloRonda()}.
     *
     * <p>
     * El reset de baraja NO se hace aquí para que los jugadores puedan ver las
     * cartas durante los 5 segundos de revelación antes de que se restablezca.
     * Se hace después en {@link #finalizarCicloRonda()}.</p>
     *
     * @throws IOException si las llamadas a Firebase fallan
     */
    private void cerrarRondaYusa() throws IOException {
        yusa().descartarManosAlFinDeRonda(manos, descarte); // mover cartas de manos al descarte
        bd.actualizarDescarte(codigoSala, descarte, idToken);
        bd.publicarManosYusa(codigoSala, manos, idToken); // publicar manos vacías

        finalizarCicloRonda();
    }

    // =========================================================================
    //  REVELAR Y RESOLVER
    // =========================================================================
    /**
     * Guarda el snapshot de cartas actuales y publica {@code REVELAR_CARTAS} en
     * Firebase.
     *
     * <p>
     * El snapshot se guarda <em>antes</em> de publicar para que cuando el
     * evento {@code REVELAR_CARTAS} llegue a los clientes (incluido el propio
     * director tras el ciclo de polling), el snapshot ya esté disponible para
     * {@link #resolverFinDeRonda()}.</p>
     *
     * <p>
     * El operador ternario
     * {@code faseRondaActual != null ? faseRondaActual.name() : "NORMAL"} evita
     * un {@code NullPointerException} si la fase no se estableció
     * correctamente.</p>
     *
     * @throws IOException si la publicación en Firebase falla
     */
    private void publicarRevelarCartas() throws IOException {
        guardarSnapshot(); // capturar estado antes de publicar
        bd.publicarEstadoRonda(codigoSala, "REVELAR_CARTAS",
                faseRondaActual != null ? faseRondaActual.name() : "NORMAL", null, idToken);
    }

    /**
     * Punto de entrada para la carga de manos y revelación. Empieza con intento
     * 0.
     */
    private void cargarManosYRevelar() {
        cargarManosYRevelarConReintento(0);
    }

    /**
     * Carga las manos frescas de Firebase con un sistema de reintentos, y luego
     * las revela.
     *
     * <p>
     * El evento {@code REVELAR_CARTAS} puede llegar antes de que las manos
     * estén completamente propagadas por Firebase. Este método reintenta la
     * carga hasta {@link #MAX_INTENTOS_MANOS} veces, con
     * {@link #DELAY_REINTENTO_MS} entre intentos.</p>
     *
     * <p>
     * La carga se hace en un hilo de background (nuevo {@code Thread}) para no
     * bloquear el hilo de JavaFX durante las peticiones HTTP a Firebase. Una
     * vez obtenidas las manos, se vuelve al hilo de JavaFX con
     * {@code Platform.runLater} para actualizar el estado y mostrar las
     * cartas.</p>
     *
     * <p>
     * La lectura manual del mapa de manos ({@code instanceof Map<?, ?> raw})
     * usa pattern matching para el cast con comprobación de tipo en una sola
     * expresión. Es más seguro que un cast directo porque evita
     * {@code ClassCastException} si la estructura de Firebase cambia.</p>
     *
     * <p>
     * Condición de validez {@code manasValidas}: acepta si al menos
     * {@code max(1, totalVivos-1)} jugadores vivos tienen carta. Esto tolera
     * que hasta 1 jugador vivo no tenga carta, cubriendo el caso de la fase
     * YUSA donde el poseedor puede ya no tener carta si fue descartada antes de
     * la revelación.</p>
     *
     * @param intento número del intento actual (0 = primer intento)
     */
    private void cargarManosYRevelarConReintento(int intento) {
        new Thread(() -> { // hilo de background para las peticiones HTTP
            Map<String, List<String>> frescas = new HashMap<>();
            try {
                Map<String, Object> partida = bd.leerPartida(codigoSala, idToken);
                if (partida != null) {
                    Object mo = partida.get("manos");
                    // instanceof Map<?, ?> raw: pattern matching - comprueba y castea en una línea
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
                                    l.clear(); // normalizar ["EMPTY"] - lista vacía real
                                }
                                if (!l.isEmpty()) {
                                    frescas.put(uid, l); // solo guardar si tiene cartas
                                }
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            // Validar si tenemos suficientes manos: la mayoría de los vivos deben tener carta
            long vivosConCarta = yusa().getJugadoresVivos().stream()
                    .filter(uid -> frescas.containsKey(uid)).count(); // cuántos vivos tienen carta en frescas
            long totalVivos = yusa().getJugadoresVivos().size();

            // Válido si tiene la mayoría (tolera jugadores sin carta en fase YUSA)
            boolean manasValidas = !frescas.isEmpty() && vivosConCarta >= Math.max(1, totalVivos - 1);

            if (!manasValidas && intento < MAX_INTENTOS_MANOS) {
                System.out.println("[YUSA] Manos no listas (intento " + (intento + 1)
                        + "/" + MAX_INTENTOS_MANOS + "). Reintentando en "
                        + DELAY_REINTENTO_MS + "ms..."); // debug
                try {
                    Thread.sleep(DELAY_REINTENTO_MS); // esperar antes de reintentar
                } catch (InterruptedException ignored) {
                }
                // Platform.runLater: el nuevo intento debe hacerse en el hilo de JavaFX
                Platform.runLater(() -> cargarManosYRevelarConReintento(intento + 1));
                return;
            }

            System.out.println("[YUSA] Manos listas tras " + intento + " intento(s). "
                    + "Jugadores vivos: " + yusa().getJugadoresVivos()
                    + " | Manos recibidas: " + frescas.keySet()); // debug

            // Volver al hilo de JavaFX para actualizar el estado y la UI
            final Map<String, List<String>> resultado = frescas; // final para usarlo en el lambda
            Platform.runLater(() -> {
                if (!resultado.isEmpty()) {
                    manos.clear();
                    manos.putAll(resultado); // reemplazar manos locales con las frescas de Firebase

                }

                mostrarCartasDeRonda();
            });
        }).start();
    }

    /**
     * Calcula quién pierde vida en la ronda normal o doce. <strong>Solo lo
     * ejecuta el director.</strong>
     *
     * <p>
     * Reconstruye el mapa de manos para la resolución usando el snapshot (más
     * fiable) o las manos actuales como fallback. Usa
     * {@code List.of(e.getValue())} para crear listas inmutables de 1 elemento
     * a partir del snapshot (cada jugador tenía exactamente 1 carta en
     * Yusa).</p>
     *
     * <h3>Caso empate (más de 1 jugador con carta más baja igual)</h3>
     * <p>
     * Se inicia una mini-ronda de desempate:</p>
     * <ol>
     * <li>Descartar las manos actuales.</li>
     * <li>Resetear baraja si es necesario.</li>
     * <li>Publicar {@code ESPECTADOR_DESEMPATE} con los UIDs separados por
     * coma. {@code String.join(",", perdedores)} une los UIDs con coma.</li>
     * <li>Esperar 1 segundo para que todos los clientes reciban el estado.</li>
     * <li>Iniciar la mini-ronda.</li>
     * </ol>
     *
     * <h3>Caso sin empate</h3>
     * <p>
     * El jugador con la carta más baja pierde 1 vida. Si llega a 0, es
     * eliminado. {@code yusa().perderVida(uid)} devuelve {@code true} si fue
     * eliminado. Se verifica fin de partida, se descartan manos y se cierra el
     * ciclo.</p>
     */
    private void resolverFinDeRonda() {
        if (!uidLocal.equals(uidTurnoActual)) { // solo el director resuelve
            return;
        }
        try {
            // Reconstruir manos desde snapshot o fallback
            Map<String, List<String>> manosReconstruidas = new HashMap<>();
            if (!snapshotCartas.isEmpty()) {
                // Snapshot: estado de las cartas en el momento exacto de la revelación
                for (Map.Entry<String, String> estadoManos : snapshotCartas.entrySet()) {

                    // List.of(): lista inmutable de 1 elemento; cada jugador tenía 1 carta en Yusa
                    manosReconstruidas.put(estadoManos.getKey(), List.of(estadoManos.getValue()));
                }
            } else {
                // Fallback: usar manos actuales si el snapshot está vacío
                for (Map.Entry<String, List<String>> estadoManos : manos.entrySet()) {
                    if (estadoManos.getValue() != null && !estadoManos.getValue().isEmpty()) {
                        manosReconstruidas.put(estadoManos.getKey(), new ArrayList<>(estadoManos.getValue()));
                    }
                }
            }

            if (manosReconstruidas.isEmpty()) {
                System.out.println("WARN: manosResolver vacío en resolverFinDeRonda.");
                bd.limpiarEstadoRonda(codigoSala, idToken);
                finalizarCicloRonda();
                return;
            }

            // Determinar participantes: si estamos en desempate, solo los empatados
            List<String> participantes = (empatadosActuales != null && !empatadosActuales.isEmpty())
                    ? new ArrayList<>(empatadosActuales)
                    : new ArrayList<>(yusa().getJugadoresVivos());

            List<String> perdedores = yusa().determinarPerdedores(manosReconstruidas, participantes);

            if (perdedores.size() > 1) {
                // EMPATE - iniciar mini-ronda de desempate entre los empatados
                String nombresEmp = perdedores.stream()
                        .map(u -> nombres.getOrDefault(u, u)).collect(Collectors.joining(", "));
                narrarGlobal(IdiomaManager.get("yusaOnline.global.empateDesempate", nombresEmp));

                // Descartar manos actuales
                yusa().descartarManosAlFinDeRonda(manos, descarte);
                bd.actualizarDescarte(codigoSala, descarte, idToken);
                bd.publicarManosYusa(codigoSala, manos, idToken);

                if (yusa().debeResetearBaraja(descarte)) {
                    yusa().resetearBaraja(baraja, descarte);
                    narrarGlobal(IdiomaManager.get("yusaOnline.global.seBarajanCartas"));
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

                // Pausa de 1s para que todos los clientes reciban ESPECTADOR_DESEMPATE
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

            // DESEMPATE RESUELTO O RONDA NORMAL SIN EMPATE
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
                boolean eliminado = yusa().perderVida(uid); // devuelve true si fue eliminado (0 vidas)
                narrarGlobal(IdiomaManager.get("yusaOnline.global.pierdeVida",
                        nombres.getOrDefault(uid, uid),
                        yusa().getVidas(uid)
                )
                );
                if (eliminado) {
                    narrarGlobal(IdiomaManager.get("yusaOnline.global.eliminado",
                            nombres.getOrDefault(uid, uid)
                    )
                    );
                    // Si el eliminado soy yo, mostrar el overlay
                    if (uid.equals(uidLocal)) {
                        mostrarMensajeEliminado();
                    }
                }
                bd.actualizarVidaJugador(codigoSala, uid, yusa().getVidas(uid), idToken);
            } else if (perdedores.size() > 1) {
                String nuevoEmpate = perdedores.stream()
                        .map(u -> nombres.getOrDefault(u, u)).collect(Collectors.joining(", "));
                narrarGlobal(IdiomaManager.get("yusaOnline.global.empatePierdenVida",
                        nuevoEmpate)
                );
                for (String uid : perdedores) { // Más de 1 perdedor: todos pierden vida
                    boolean eli = yusa().perderVida(uid);
                    if (eli) {
                        narrarGlobal(IdiomaManager.get("yusaOnline.global.eliminado",
                                nombres.getOrDefault(uid, uid))
                        );
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

            if (yusa().debeResetearBaraja(descarte)) { // reset de baraja si salieron 3 yusas
                yusa().resetearBaraja(baraja, descarte);
                narrarGlobal(IdiomaManager.get("yusaOnline.global.seBarajanCartas"));
                bd.actualizarBaraja(codigoSala, baraja, idToken);
                bd.actualizarDescarte(codigoSala, descarte, idToken);
            }

            bd.limpiarEstadoRonda(codigoSala, idToken);
            finalizarCicloRonda();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Incrementa el contador de rondas y lo publica en Firebase para
     * trazabilidad.
     */
    private void incrementarNumeroRonda() {
        numeroRondaYusa++;
        try {
            bd.incrementarRonda(codigoSala, numeroRondaYusa, idToken);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // =========================================================================
    //  FIN DE CICLO DE RONDA
    // =========================================================================
    /**
     * Cierra el ciclo de ronda: limpia Firebase y rota el director al siguiente
     * jugador vivo.
     *
     * <p>
     * Orden de operaciones (importante para la consistencia):</p>
     * <ol>
     * <li>Limpiar todos los nodos de estado de la ronda en Firebase:
     * estadoRonda, decisionJugador, objetivosYusa, palosYusa, nodo yusa.</li>
     * <li>Reset de baraja si necesario. Se hace aquí, <em>después</em> de los 5
     * segundos de revelación, para que los jugadores vean las cartas antes de
     * que la baraja se restablezca.</li>
     * <li>Limpiar el flag {@code doceJugado}.</li>
     * <li>Limpiar estado de desempate local.</li>
     * <li>Calcular el siguiente director filtrando solo los jugadores vivos del
     * orden global de la sala. {@code yusa().siguienteTurno()} hace la rotación
     * correcta saltando a los eliminados.</li>
     * <li>Sincronizar vidas desde Firebase para la próxima ronda.</li>
     * <li>Resetear el estado local del controlador.</li>
     * <li>Publicar el nuevo turno en Firebase. El siguiente director recibirá
     * este evento en su {@link #onCambioTurno(String)} y arrancará la nueva
     * ronda.</li>
     * </ol>
     *
     * @throws IOException si las llamadas a Firebase fallan
     */
    private void finalizarCicloRonda() throws IOException {
        bd.limpiarEstadoRonda(codigoSala, idToken);
        bd.limpiarDecisionJugador(codigoSala, idToken);
        bd.limpiarObjetivosYusa(codigoSala, idToken);
        bd.limpiarPalosYusa(codigoSala, idToken);
        db.borrarNodo("salas/" + codigoSala + "/partida/yusa", idToken);

        // Reset de baraja AQUÍ  después de haber mostrado las cartas 5s
        if (yusa().debeResetearBaraja(descarte)) {
            yusa().resetearBaraja(baraja, descarte);
            narrarGlobal(IdiomaManager.get("yusaOnline.global.seBarajanCartas"));
            bd.actualizarBaraja(codigoSala, baraja, idToken);
            bd.actualizarDescarte(codigoSala, descarte, idToken);
        }

        try {
            bd.limpiarDoceJugado(codigoSala, idToken);
        } catch (Exception e) {
            e.printStackTrace();
        }
        empatadosActuales = null;
        enDesempate = false;
        colaYusas.clear();

        // Filtrar el orden global para incluir solo los jugadores vivos
        List<String> vivos = ordenJugadoresGlobal.stream()
                .filter(uid -> yusa().getJugadoresVivos().contains(uid)) // solo los que siguen vivos
                .collect(Collectors.toList());
        String siguiente = yusa().siguienteTurno(uidTurnoActual, vivos); // calcular el siguiente director

        try {
            Map<String, Integer> v = bd.leerVidas(codigoSala, idToken);
            if (v != null) {
                yusa().cargarVidasDesdeBD(v); // sincronizar vidas antes del reset de estado
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        resetearEstadoLocal();
        bd.actualizarTurno(codigoSala, siguiente, idToken); // el siguiente recibe el turno y arranca nueva ronda
    }

    /**
     * Limpia todos los campos de estado local del controlador al finalizar la
     * ronda.
     *
     * <p>
     * <strong>IMPORTANTE:</strong> {@code ultimoEstadoTs} intencionalmente NO
     * se resetea a {@code -1L} aquí. Si se reseteara, el listener de
     * estadoRonda podría re-procesar el {@code REVELAR_CARTAS} de la ronda
     * anterior que puede seguir en Firebase durante el tiempo que tarda
     * {@code limpiarEstadoRonda()} en propagarse. {@code ultimoEstadoTs} solo
     * se resetea en {@link #iniciarRondaYusa()} cuando el director establece el
     * nuevo contexto.</p>
     */
    private void resetearEstadoLocal() {

        detenerTickDoce();
        rondaEnCurso = false;
        faseRondaActual = null;
        snapshotCartas.clear();
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

    }

    // =========================================================================
    //  MOSTRAR / OCULTAR CARTAS - revelación de 5 segundos
    // =========================================================================
    /**
     * Muestra las cartas frontales de todos los jugadores vivos durante 5
     * segundos.
     *
     * <p>
     * Establece {@link #mostrandoCartas} = {@code true} para bloquear
     * {@link #actualizarInterfaz()} durante la revelación.</p>
     *
     * <p>
     * Para cada jugador vivo con carta, limpia su zona y posiciona un
     * {@link ImageView} con la carta frontal. La posición y rotación de la
     * imagen varían según la zona:</p>
     * <ul>
     * <li>Abajo: 0° de rotación (orientación normal).</li>
     * <li>Arriba: 180° (carta invertida para el rival de enfrente).</li>
     * <li>Izquierda: 90° (carta girada para el rival lateral).</li>
     * <li>Derecha: -90° (carta girada al otro lado).</li>
     * </ul>
     *
     * <p>
     * {@code Math.max(zona.getWidth(), zona.getPrefWidth())}: cubre el caso en
     * que la zona aún no ha sido pintada por JavaFX y sus dimensiones reales
     * son 0. {@code getPrefWidth()} devuelve el ancho preferido definido en el
     * FXML.</p>
     *
     * <p>
     * Un {@link PauseTransition} de 5 segundos controla el tiempo de visión. Al
     * terminar, {@link #ocultarCartasDeRonda()} desbloquea
     * {@code actualizarInterfaz()} y el director decide qué resolver según la
     * fase. Los no-directores solo llaman a {@link #resetearEstadoLocal()} ya
     * que el director es quien decide.</p>
     *
     * <p>
     * Se usa {@link LinkedHashMap} para el mapa {@code zonas} para garantizar
     * el orden de iteración (abajo, arriba, izquierda, derecha).</p>
     */
    private void mostrarCartasDeRonda() {

        mostrandoCartas = true; // bloquear actualizarInterfaz() durante la revelación

        colocarJugadores(); // posicionar a los jugadores en sus zonas (sin dibujar cartas aún)

        // Log de qué cartas se van a mostrar
        System.out.println("[YUSA] Mostrando cartas:");

        // LinkedHashMap: garantiza el orden de iteración (abajo primero, luego arriba, etc.)
        Map<String, Pane> zonas = new LinkedHashMap<>();
        zonas.put(uidLocal, zonaAbajo);
        zonas.put(uidJugadorArriba, zonaArriba);
        zonas.put(uidJugadorIzquierda, zonaIzquierda);
        zonas.put(uidJugadorDerecha, zonaDerecha);

        for (Map.Entry<String, Pane> entry : zonas.entrySet()) {
            String uid = entry.getKey();
            Pane zona = entry.getValue();
            if (uid == null) { // posición sin jugador asignado (sala de menos de 4)
                continue;
            }

            if (!yusa().getJugadoresVivos().contains(uid)) {
                System.out.println("  " + nombres.getOrDefault(uid, uid) + " - ELIMINADO (sin carta)");
                continue; // no mostrar carta de jugadores eliminados
            }

            List<String> m = manos.get(uid);
            if (m == null || m.isEmpty()) {
                System.out.println("  " + nombres.getOrDefault(uid, uid) + " - SIN CARTA");
                continue;
            }

            String cartaRuta = m.get(0);
            System.out.println("  " + nombres.getOrDefault(uid, uid) + " - " + cartaRuta);

            zona.getChildren().clear(); // limpiar el abanico de espalda que estaba
            ImageView img = new ImageView(new Image(getClass().getResourceAsStream(cartaRuta)));
            img.setFitHeight(120);
            img.setPreserveRatio(true);

            // Math.max: cobertura para cuando la zona aún no tiene dimensiones reales (primer paint)
            double width = Math.max(zona.getWidth(), zona.getPrefWidth());
            double height = Math.max(zona.getHeight(), zona.getPrefHeight());
            double cx = width / 2;
            double cy = height / 2;

            // Posicionar según la zona, con rotación para que la carta sea legible desde cada posición
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

        System.out.println("[YUSA] Cartas mostradas. Iniciando pausa de 5 segundos..."); // debug

        // PauseTransition de 5s: controla cuánto tiempo son visibles las cartas
        pausaRevelado = new PauseTransition(Duration.seconds(5));
        pausaRevelado.setOnFinished(ev -> {
            System.out.println("[YUSA] 5 segundos completados. Ocultando cartas.");
            ocultarCartasDeRonda(); // desbloquear actualizarInterfaz() y redibujar
            pausaRevelado = null; // limpiar referencia para el siguiente REVELAR_CARTAS

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
                resetearEstadoLocal(); // no-director: solo limpiar estado local
            }
        });
        pausaRevelado.play();
    }

    /**
     * Desbloquea {@link #actualizarInterfaz()} tras los 5 segundos de
     * revelación. Registra el timestamp de fin para la ventana de protección
     * anti-doble-revelación y redibuja la interfaz con las cartas de espalda.
     */
    private void ocultarCartasDeRonda() {
        mostrandoCartas = false;  // desbloquear actualizarInterfaz()
        tiempoFinUltimaRevelacion = System.currentTimeMillis(); // registrar para VENTANA_PROTECCION_MS
        System.out.println("[YUSA] actualizarInterfaz() desbloqueada. Ocultando cartas.");
        actualizarInterfaz(); // redibujar con cartas de espalda normales
    }

    // =========================================================================
    //  BOTONES DE DECISIÓN
    // =========================================================================
    /**
     * Muestra dos botones de decisión en la parte inferior de la pantalla.
     *
     * <p>
     * Los botones se añaden al {@code rootSala} (el {@link StackPane} raíz) con
     * alineación {@code BOTTOM_CENTER} y un offset vertical de 140 píxeles
     * positivos, lo que en JavaFX (eje Y hacia abajo) los sitúa en la zona
     * inferior de la pantalla, justo encima del borde.</p>
     *
     * <p>
     * El callback {@code cb} es un
     * {@link java.util.function.Consumer Consumer&lt;String&gt;} que recibe el
     * texto del botón pulsado como argumento. Esto permite que el llamante
     * decida qué hacer según qué botón se pulsó.</p>
     *
     * @param opcion1 texto del primer botón (opción izquierda)
     * @param opcion2 texto del segundo botón (opción derecha)
     * @param callback callback que recibe el texto del botón pulsado
     */
    protected void mostrarBotonesDecision(String opcion1, String opcion2,
            java.util.function.Consumer<String> callback) {

        ocultarPanelDecision(); // limpiar cualquier panel anterior
        Button b1 = new Button(opcion1);
        Button b2 = new Button(opcion2);

        // Animación + sonido
        Animaciones.animarBoton(b1);
        Animaciones.animarBoton(b2);
        ButtonSound.activar(b1);
        ButtonSound.activar(b2);

        b1.setOnAction(e -> {
            ocultarPanelDecision();
            callback.accept(opcion1); // pasar texto al callback
        });

        b2.setOnAction(e -> {
            ocultarPanelDecision();
            callback.accept(opcion2); // pasar texto al callback
        });

        panelDecision = new HBox(16, b1, b2);
        panelDecision.setAlignment(Pos.CENTER);
        StackPane.setAlignment(panelDecision, Pos.BOTTOM_CENTER);
        panelDecision.setTranslateY(180);
        rootSala.getChildren().add(panelDecision);
    }

    /**
     * Crea un botón de decisión con fondo oscuro semitransparente.
     *
     * @param texto texto del botón
     * @param accion handler del evento de clic
     * @return botón configurado y listo para añadir a la escena
     */
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

    /**
     * Oculta y elimina el panel de decisión actual de la escena. También
     * detiene el tick del countdown del 12 si estaba activo.
     */
    private void ocultarPanelDecision() {
        detenerTickDoce(); // detener el countdown si hay un botón de 12 activo
        if (panelDecision != null) {
            rootSala.getChildren().remove(panelDecision);
            panelDecision = null;
        }
    }

    /**
     * Muestra los cuatro botones de palo para que el objetivo de la yusa elija
     * su respuesta.
     */
    private void mostrarBotonesPalo() {
        ocultarPanelDecision();
        String estilo = "-fx-font-size: 20px;"
                + "-fx-font-family: 'Minecraft';"
                + "-fx-text-fill: white;"
                + "-fx-background-color: rgba(255,105,180,0.55);"
                + "-fx-background-radius: 12px;"
                + "-fx-padding: 12 22;"
                + "-fx-border-color: rgba(255,255,255,0.7);"
                + "-fx-border-width: 2px;"
                + "-fx-border-radius: 12px;"
                + "-fx-cursor: hand;";

        Button bC = new Button(IdiomaManager.get("yusaOnline.privado.palo.coronas"));
        Button bV = new Button(IdiomaManager.get("yusaOnline.privado.palo.balanzas"));
        Button bD = new Button(IdiomaManager.get("yusaOnline.privado.palo.dianas"));
        Button bCz = new Button(IdiomaManager.get("yusaOnline.privado.palo.corazones"));

        for (Button b : new Button[]{bC, bV, bD, bCz}) {
            b.setStyle(estilo);
            Animaciones.animarBoton(b);
            ButtonSound.activar(b);
        }

        bC.setOnAction(e -> {
            ocultarPanelDecision();
            onElegirPaloYusa(JuegoYusa.Palo.CORONAS);
        });
        bV.setOnAction(e -> {
            ocultarPanelDecision();
            onElegirPaloYusa(JuegoYusa.Palo.BALANZAS);
        });
        bD.setOnAction(e -> {
            ocultarPanelDecision();
            onElegirPaloYusa(JuegoYusa.Palo.DIANAS);
        });
        bCz.setOnAction(e -> {
            ocultarPanelDecision();
            onElegirPaloYusa(JuegoYusa.Palo.CORAZONES);
        });

        panelDecision = new HBox(16, bC, bV, bD, bCz);
        panelDecision.setAlignment(Pos.CENTER);
        StackPane.setAlignment(panelDecision, Pos.BOTTOM_CENTER);
        panelDecision.setTranslateY(180);
        rootSala.getChildren().add(panelDecision);
    }

    /**
     * Muestra el botón "Jugar carta (20s)" con countdown regresivo para el
     * jugador con el 12.
     *
     * <p>
     * El countdown se implementa con un {@link PauseTransition} de 1 segundo
     * ({@link #tickDoce}) que se reinicia con {@code playFromStart()} en cada
     * tick hasta que el contador llega a 0.</p>
     *
     * <p>
     * {@code int[] seg = {20}}: array de un elemento en lugar de
     * {@code int seg = 20} porque los lambdas de Java solo pueden capturar
     * variables "efectivamente finales". Una variable {@code int} no puede
     * modificarse dentro de un lambda (el compilador lo rechazaría). Un array
     * puede modificarse porque la referencia al array es final, aunque su
     * contenido ({@code seg[0]}) cambie. Es la técnica estándar para contadores
     * mutables dentro de lambdas.</p>
     *
     * <p>
     * El {@code Runnable jugar} encapsula la acción de jugar la carta. Al
     * definirlo como {@code Runnable}, tanto el clic del botón como el timeout
     * del countdown ejecutan exactamente el mismo código sin duplicación.</p>
     *
     * <p>
     * {@code tickDoce.playFromStart()}: reinicia la transición desde el inicio
     * (desde 1s) en lugar de continuarla, creando el efecto de un tick
     * periódico.</p>
     */
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
                + "-fx-background-radius: 12px;"
                + "-fx-padding: 14 28;"
                + "-fx-border-color: rgba(255,255,255,0.7);"
                + "-fx-border-width: 2px;"
                + "-fx-border-radius: 12px;"
                + "-fx-cursor: hand;"
        );

        // int[]{20}: array de 1 elemento porque los lambdas no pueden capturar int mutables
        int[] seg = {20};

        // Runnable: encapsula la acción de jugar para usarla tanto en clic como en timeout
        Runnable jugar = () -> {
            detenerTickDoce(); // siempre parar el tick antes de actuar
            ocultarPanelDecision();
            onDoceJugado();
        };

        tickDoce = new PauseTransition(Duration.seconds(1));
        tickDoce.setOnFinished(ev -> {
            seg[0]--; // decrementar el contador (modifica el contenido del array)
            // actualizar el texto del botón
            btn.setText(IdiomaManager.get("yusaOnline.ui.jugarCartaCuentaAtras", seg[0]));
            if (seg[0] > 0) {
                tickDoce.playFromStart(); // reiniciar el tick para el siguiente segundo
            } else {
                jugar.run(); // tiempo agotado: jugar automáticamente
            }
        });

        btn.setOnAction(e -> jugar.run());  // clic del jugador: jugar.run() ya para el tick

        panelDecision = new HBox(btn);
        panelDecision.setAlignment(Pos.CENTER);
        StackPane.setAlignment(panelDecision, Pos.BOTTOM_CENTER);
        panelDecision.setTranslateY(180);
        rootSala.getChildren().add(panelDecision);
        tickDoce.play();
        narrarPrivado(uidLocal, IdiomaManager.get("yusaOnline.privado.tienesDoceJugarCarta"));

    }

    /**
     * Detiene y limpia el tick del countdown del 12. Si {@link #tickDoce} es
     * {@code null}, no hace nada (seguro contra null).
     */
    private void detenerTickDoce() {
        if (tickDoce != null) {
            tickDoce.stop();
            tickDoce = null; // limpiar referencia para evitar fugas
        }
    }

    // =========================================================================
    //  UTILIDADES
    // =========================================================================
    /**
     * Guarda una instantánea de las cartas actuales de todos los jugadores.
     * <p>
     * Solo guarda jugadores con mano no vacía. Se llama en
     * {@link #publicarRevelarCartas()} antes de publicar el evento, para
     * preservar el estado exacto del momento de la revelación.</p>
     */
    private void guardarSnapshot() {
        snapshotCartas.clear();
        for (Map.Entry<String, List<String>> e : manos.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                snapshotCartas.put(e.getKey(), e.getValue().get(0)); // primera (y única) carta en Yusa
            }
        }
    }

    /**
     * Busca el primer jugador vivo cuya carta tenga el número indicado.
     * <p>
     * Solo busca en la primera carta de la mano ({@code m.get(0)}) porque en
     * Yusa cada jugador tiene exactamente 1 carta.</p>
     *
     * @param numero número a buscar (p.ej. {@code JuegoYusa.NUMERO_DOCE})
     * @return UID del jugador con esa carta, o {@code null} si nadie la tiene
     */
    private String encontrarJugadorConNumero(int numero) {
        for (String uid : yusa().getJugadoresVivos()) {
            List<String> m = manos.get(uid);
            if (m != null && !m.isEmpty() && yusa().obtenerNumeroCarta(m.get(0)) == numero) {
                return uid;
            }
        }
        return null;
    }

    /**
     * Comprueba si un jugador tiene en su mano la carta de yusa.
     * <p>
     * {@code JuegoYusa.NUMERO_YUSA} es la constante que identifica el número
     * especial de la carta de yusa. Solo se comprueba la primera carta de la
     * mano.</p>
     *
     * @param uid UID del jugador a comprobar
     * @return {@code true} si la primera carta de su mano es la carta de yusa
     */
    private boolean tieneYusa(String uid) {
        List<String> m = manos.get(uid);
        return m != null && !m.isEmpty()
                && yusa().obtenerNumeroCarta(m.get(0)) == JuegoYusa.NUMERO_YUSA;
    }

    // =========================================================================
    //  OVERLAYS - espectador y eliminado
    // =========================================================================
    /**
     * Muestra un overlay semitransparente bloqueante para los jugadores
     * espectadores durante una ronda de desempate.
     *
     * <p>
     * Si el jugador ya tiene el overlay de eliminado visible, no se crea el de
     * espectador (el eliminado tiene prioridad visual).</p>
     *
     * <p>
     * El overlay se añade directamente al nodo raíz de la escena
     * ({@code zonaAbajo.getScene().getRoot()}) para que quede por encima de
     * todos los demás nodos. Se usa pattern matching de Java 16:
     * {@code scene.getRoot() instanceof Pane root} comprueba el tipo y castea
     * en una sola expresión.</p>
     *
     * <p>
     * {@code prefWidthProperty().bind(root.widthProperty())} y
     * {@code prefHeightProperty().bind(root.heightProperty())} vinculan el
     * tamaño del overlay al del contenedor raíz. Cuando la ventana cambia de
     * tamaño, el overlay se redimensiona automáticamente sin necesidad de
     * listeners manuales.</p>
     *
     * @param empatados lista de UIDs de los jugadores que participan en el
     * desempate
     */
    private void mostrarOverlayEspectador(List<String> empatados) {

        if (overlayEliminado != null) {
            return; // el overlay de eliminado tiene prioridad
        }

        ocultarOverlayEspectador(); // por si había uno anterior

        javafx.scene.text.Font fuenteMinecraft = javafx.scene.text.Font.loadFont(
                getClass().getResourceAsStream("/ui/graphicResources/fonts/Minecraft.ttf"), 38);
        System.out.println("[FUENTE] Minecraft: " + (fuenteMinecraft != null ? "cargada ✓" : "no encontrada ✗"));
        System.out.println("[FUENTE] Nombre interno: " + fuenteMinecraft.getName());

        // Collectors.joining(" vs "): une los nombres con " vs " - "Usuario1 vs Usuario2"
        String nombresEmpatados = empatados.stream()
                .map(uid -> nombres.getOrDefault(uid, uid))
                .collect(Collectors.joining(" vs "));

        javafx.scene.control.Label lblTitulo = new Label(IdiomaManager.get("yusaOnline.ui.desempateTitulo"));
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

        javafx.scene.control.Label lblInfo = new javafx.scene.control.Label(IdiomaManager.get("yusaOnline.ui.desempateInfo"));
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
        // instanceof Pane root: pattern matching - comprueba y castea en una línea
        if (scene != null && scene.getRoot() instanceof javafx.scene.layout.Pane root) {
            root.getChildren().add(overlayEspectador);
            overlayEspectador.prefWidthProperty().bind(root.widthProperty());
            overlayEspectador.prefHeightProperty().bind(root.heightProperty());
        }
    }

    /**
     * Elimina el overlay de espectador de la escena y limpia la referencia.
     * <p>
     * Obtiene el nodo padre del overlay y lo elimina de sus hijos. Pattern
     * matching: {@code parent instanceof Pane p} para acceder a
     * {@code getChildren()} sin un cast explícito.</p>
     */
    private void ocultarOverlayEspectador() {
        if (overlayEspectador != null) {
            javafx.scene.Parent parent = overlayEspectador.getParent();
            if (parent instanceof javafx.scene.layout.Pane p) {
                p.getChildren().remove(overlayEspectador);
            }
            overlayEspectador = null;
        }
    }

    /**
     * Inicia la mini-ronda de desempate entre los jugadores empatados.
     *
     * <p>
     * Resetea el estado de ronda pero <strong>sin tocar</strong>
     * {@link #empatadosActuales} ni {@link #enDesempate}, que deben mantenerse
     * activos durante toda la mini-ronda para que {@link #resolverFinDeRonda()}
     * sepa que está en desempate y use solo los empatados como
     * participantes.</p>
     *
     * <p>
     * Solo reparte 1 carta a los empatados. El resto de jugadores no reciben
     * carta. {@code manos.computeIfAbsent(uid, k -> new ArrayList<>())} crea la
     * mano si no existe, o devuelve la existente.</p>
     *
     * <p>
     * La fase se determina solo con las manos de los empatados (un subconjunto
     * del mapa {@code manos}). Esto evita que una yusa o un 12 de un jugador no
     * empatado influya en la fase del desempate.</p>
     *
     * @throws IOException si las llamadas a Firebase fallan
     */
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
            mano.clear(); // limpiar carta anterior si quedó alguna
            if (!baraja.isEmpty()) {
                mano.add(baraja.remove(0)); // robar del top del mazo
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
        faseRondaActual = yusa().determinarFaseRonda(manosEmpatados); // fase basada en cartas de empatados

        // Collectors.joining(" vs "): une los nombres para la narración del desempate
        String nombresEmp = empatadosActuales.stream()
                .map(u -> nombres.getOrDefault(u, u)).collect(Collectors.joining(" vs "));
        narrarGlobal(IdiomaManager.get("yusaOnline.global.desempateFase",
                nombresEmp,
                textoFase(faseRondaActual))
        );

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
     * Publica en Firebase la acción {@code ELEGIR_OBJETIVO} para el siguiente
     * poseedor de la cola.
     *
     * <p>
     * Antes de publicar, elimina de la cola los poseedores que puedan haber
     * sido eliminados durante la ronda (por una yusa anterior en la misma
     * ronda). Un bucle {@code while} asegura saltar todos los eliminados
     * consecutivos.</p>
     *
     * <p>
     * Si tras el saneamiento la cola está vacía, todos los poseedores
     * preguntaron (o fueron eliminados) - cerrar la ronda de yusa completa.</p>
     *
     * @throws IOException si las llamadas a Firebase fallan
     */
    private void publicarSiguientePreguntaYusa() throws IOException {
        // Saltar poseedores que ya no están vivos (eliminados durante la ronda)
        while (!colaYusas.isEmpty()
                && !yusa().getJugadoresVivos().contains(colaYusas.get(0))) {
            System.out.println("[YUSA] Saltando poseedor eliminado: " + colaYusas.get(0));
            objetivosPorPoseedor.remove(colaYusas.get(0));
            colaYusas.remove(0); // eliminar del frente de la cola
        }

        if (colaYusas.isEmpty()) {
            // Todos han preguntado (o fueron eliminados) - cerrar ronda
            cerrarRondaYusaCompleta();
            return;
        }

        String poseedor = colaYusas.get(0); // el primero de la cola es quien pregunta ahora
        narrarGlobal(IdiomaManager.get("yusaOnline.global.turnoYusaElegirObjetivo",
                nombres.getOrDefault(poseedor, poseedor),
                colaYusas.size())
        );

        // turnoUid = poseedor: indica a este jugador que debe elegir objetivo ahora
        bd.publicarEstadoRonda(codigoSala, "ELEGIR_OBJETIVO",
                JuegoYusa.FaseRonda.YUSA.name(), poseedor, idToken);

        // Si soy yo el que debe elegir y soy el director, no necesito
        // esperar al listener - la UI ya se activa en procesarAccion
    }

    /**
     * Cierra la ronda de yusa cuando todos los poseedores han preguntado.
     * <p>
     * Incrementa el número de ronda, limpia los nodos de Firebase y, si la
     * partida no ha terminado, llama a {@link #cerrarRondaYusa()}.</p>
     *
     * @throws IOException si las llamadas a Firebase fallan
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
     * Muestra un overlay persistente con el texto "ESTÁS ELIMINADO" al jugador
     * local.
     *
     * <p>
     * El overlay se añade al {@link PartidaControllerBase#overlayFinal} con
     * índice 0 ({@code add(0, overlayEliminado)}), que en un {@link StackPane}
     * corresponde a la capa más baja visualmente. Esto garantiza que si después
     * llega el popup final de resultados, quede por encima del mensaje de
     * eliminado.</p>
     *
     * <p>
     * {@code setPickOnBounds(false)}: el StackPane transparente no intercepta
     * los eventos de ratón aunque ocupe toda la pantalla. Sin esto, el jugador
     * eliminado no podría ver la partida porque el overlay bloquearía sus
     * clics.</p>
     *
     * <p>
     * {@code prefWidthProperty().bind(overlayFinal.widthProperty())}:
     * vinculación de tamaño al {@code overlayFinal} padre para que se adapte
     * automáticamente.</p>
     *
     * <p>
     * La {@link ScaleTransition} crea un efecto de pulso (crece al 110% y
     * vuelve):</p>
     * <ul>
     * <li>{@code setAutoReverse(true)}: invierte la animación
     * automáticamente.</li>
     * <li>{@code setCycleCount(Animation.INDEFINITE)}: bucle infinito hasta que
     * la partida termine y el overlay se elimine.</li>
     * </ul>
     */
    private void mostrarMensajeEliminado() {

        //debug
        System.out.println("[ELIMINADO] mostrarMensajeEliminado() llamado para: " + uidLocal);

        // Limpiar overlay anterior si existía (por si se llama dos veces)
        if (overlayEliminado != null) {
            overlayFinal.getChildren().remove(overlayEliminado);
            overlayEliminado = null;
        }

        javafx.scene.text.Font fuenteMinecraft = javafx.scene.text.Font.loadFont(
                getClass().getResourceAsStream("/ui/graphicResources/fonts/Minecraft.ttf"), 38);
        System.out.println("[FUENTE] Minecraft: " + (fuenteMinecraft != null ? "cargada ✓" : "no encontrada ✗"));
        System.out.println("[FUENTE] Nombre interno: " + fuenteMinecraft.getName());

        Label lblEliminado = new Label(IdiomaManager.get("yusaOnline.ui.eliminadoTitulo"));

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

        Label lblSub = new Label(IdiomaManager.get("yusaOnline.ui.eliminadoSub"));

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
        overlayEliminado.setPickOnBounds(false); // no interceptar eventos de ratón (permite ver la partida)
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
     * Elimina el overlay de eliminado del {@code overlayFinal}.
     * <p>
     * Si el {@code overlayFinal} queda sin hijos tras eliminar el overlay, se
     * oculta ({@code setVisible(false)}) para no mostrar un fondo vacío.</p>
     */
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
     * Resuelve todos los duelos de yusa pendientes tras los 5 segundos de
     * revelación.
     * <strong>Solo lo ejecuta el director.</strong>
     *
     * <p>
     * Para cada {@link DueloYusa} en {@link #duelosPendientesYusa}:</p>
     * <ol>
     * <li>Obtener la carta del poseedor del snapshot (guardado en
     * {@link #onElegirObjetivoYusa(String)}). Fallback: mano actual.</li>
     * <li>Determinar el palo real de la carta con
     * {@link JuegoYusa#obtenerPaloCarta(String)}.</li>
     * <li>Comparar con {@code duelo.paloElegido()} (lo que dijo el objetivo):
     * {@code boolean acerto = paloReal == duelo.paloElegido()} - comparación de
     * enums por identidad de referencia (seguro porque los enums son
     * singletons).</li>
     * <li>El perdedor: si acertó - pierde el poseedor; si falló - pierde el
     * objetivo. La lógica es: si el objetivo adivinó bien el palo, el poseedor
     * "pierde" porque su yusa fue descubierta.</li>
     * <li>Aplicar la pérdida de vida, narrar el resultado y actualizar
     * Firebase.</li>
     * </ol>
     *
     * <p>
     * Tras resolver todos los duelos, verificar fin de partida. Si no terminó,
     * cerrar la ronda con {@link #cerrarRondaYusa()}.</p>
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
            // Fallback: intentar obtener la carta de la mano actual si el snapshot no la tiene
            if (cartaPoseedor == null) {
                List<String> mp = manos.get(duelo.poseedor());
                if (mp != null && !mp.isEmpty()) {
                    cartaPoseedor = mp.get(0);
                }
            }
            if (cartaPoseedor == null) {
                System.out.println("WARN: sin carta para resolver duelo de " + duelo.poseedor());
                continue; // saltar este duelo si no hay carta disponible
            }

            JuegoYusa.Palo paloReal = yusa().obtenerPaloCarta(cartaPoseedor);
            // Comparación de enums
            boolean acerto = paloReal == duelo.paloElegido();
            // Si acertó: el poseedor pierde (su yusa fue descubierta)
            // Si falló:  el objetivo pierde (no adivinó el palo)

            String perdedor = acerto ? duelo.poseedor() : duelo.objetivo();

            System.out.println("[DUELO] Palo real: " + paloReal
                    + " | Acertó: " + acerto
                    + " | Pierde vida: " + nombres.getOrDefault(perdedor, perdedor));

            boolean eli = yusa().perderVida(perdedor); // true si fue eliminado (llegó a 0 vidas)

            narrarGlobal(IdiomaManager.get("yusaOnline.global.resultadoDueloYusa",
                    nombres.getOrDefault(duelo.poseedor(), duelo.poseedor()),
                    nombres.getOrDefault(duelo.objetivo(), duelo.objetivo()),
                    duelo.paloElegido().name(),
                    paloReal.name(),
                    acerto
                            ? IdiomaManager.get("yusaOnline.global.acerto")
                            : IdiomaManager.get("yusaOnline.global.fallo"),
                    nombres.getOrDefault(perdedor, perdedor),
                    yusa().getVidas(perdedor))
            );

            if (eli) {
                narrarGlobal(IdiomaManager.get("yusaOnline.global.eliminado",
                        nombres.getOrDefault(perdedor, perdedor))
                );

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

    /**
     * Sobreescritura que evita peticiones HTTP a Firebase en cada ciclo de
     * redibujado.
     *
     * <p>
     * La implementación base
     * ({@link PartidaControllerBase#cargarNombresJugadores()}) hace una
     * petición HTTP por cada jugador en cada llamada a
     * {@link #actualizarInterfaz()}, que puede ejecutarse varias veces por
     * segundo. En Yusa esto saturaria el límite de peticiones de Firebase y
     * ralentizaría la UI.</p>
     *
     * <p>
     * Los nombres ya se cargaron en
     * {@link PartidaControllerBase#precargarNombres()} al inicio de la partida
     * y no cambian durante la misma, por lo que no es necesario volver a
     * leerlos desde Firebase.</p>
     */
    protected void cargarNombresJugadores() {
        // Los nombres están en el mapa 'nombres', cargados al inicio con precargarNombres().
        // No consultar Firebase aquí para no saturar el límite de peticiones HTTP.
    }

}
