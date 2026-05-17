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
import javafx.scene.Parent;
import javafx.scene.Scene;
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
 * Clase base abstracta que actúa como raíz de la jerarquía de controladores de
 * modos de juego de cartas de la aplicación Tokaleda Cards Game.
 *
 * <p>
 * Esta clase es el esquema que define el esqueleto del algoritmo de juego con
 * toda la lógica común y delega los pasos específicos de cada modo a las
 * subclases mediante métodos abstractos (hooks). De esta forma, el código
 * compartido se escribe una sola vez y los modos concretos (Pescaito, Yusa...)
 * solo añaden su lógica particular.</p>
 *
 * <h2>Responsabilidades de esta clase</h2>
 * <ul>
 * <li>Gestión del estado compartido: manos, baraja, descarte, nombres de
 * jugadores.</li>
 * <li>Integración con Firebase a través de polling HTTP, sin SDK nativo.</li>
 * <li>Carga y sincronización del modelo desde la base de datos.</li>
 * <li>Renderizado visual de la interfaz: abanicos de cartas en las cuatro
 * posiciones de la mesa (arriba, abajo, izquierda, derecha).</li>
 * <li>Sistema de mensajes del narrador: global (visible para todos via
 * Firebase) y privado (visible solo para el jugador local, con cola y
 * animación).</li>
 * <li>Registro y gestión del ciclo de vida de los hilos de polling.</li>
 * <li>Navegación entre pantallas al terminar la partida o volver a la
 * sala.</li>
 * <li>Popup de fin de partida compartido (popUpFinalPartida.fxml).</li>
 * </ul>
 *
 * <h2>Métodos que cada subclase DEBE implementar (hooks abstractos)</h2>
 * <ul>
 * <li>{@link #crearJuego(String)} — Instancia el motor de juego concreto.</li>
 * <li>{@link #registrarListenersPropios()} — Registra los listeners de Firebase
 * específicos del modo (p.ej. estadoRonda en Yusa).</li>
 * <li>{@link #onCambioTurno(String)} — Reacción al cambio de turno global.</li>
 * <li>{@link #onClickMazo()} — Reacción al clic en el mazo de robo.</li>
 * <li>{@link #onZonaRivalClick(String)} — Reacción al clic en la zona de un
 * rival.</li>
 * <li>{@link #onCartaLocalClick(String)} — Reacción al clic en una carta
 * propia.</li>
 * <li>{@link #construirDatosPopUpFinal()} — Datos de resultado para el popup
 * final.</li>
 * </ul>
 *
 * <h2>Arquitectura Firebase (polling HTTP)</h2>
 * <p>
 * La aplicación no usa el SDK de Firebase Realtime Database. En su lugar,
 * realiza peticiones HTTP GET periódicas (polling) a los nodos relevantes. Cada
 * listener es un {@link Thread} con bucle {@code while (!isInterrupted())} que
 * se puede detener llamando a {@link #destruir()}.</p>
 *
 * <h2>Gestión de hilos</h2>
 * <p>
 * Todos los hilos de polling se almacenan en {@code hilosListeners}. Al navegar
 * fuera de la pantalla de partida, el popup o el propio controlador llaman a
 * {@link #destruir()}, que interrumpe todos los hilos y establece
 * {@code controladorDestruido = true} para que los callbacks en el hilo de
 * JavaFX salgan inmediatamente sin actuar.</p>
 *
 * @author Javier Coronilla Castellano
 */
public abstract class PartidaControllerBase {

    // --------------------- Constantes de posicionamiento del abanico ---------------------------
    //
    // Estas constantes definen la geometría del abanico de cartas.
    // Se usan en los cuatro métodos dibujarAbanico*() y se calculan empíricamente
    // para obtener un aspecto visual agradable a 1280×720 px (modo fullscreen).
    //
    // RADIO_BASE: radio del arco imaginario sobre el que se distribuyen las cartas.
    //   Cuanto mayor, más plano es el abanico.
    // ANGULO_BASE: apertura angular mínima del abanico en grados.
    //   Se amplía dinámicamente según el número de cartas.
    // ALTURA_BASE: altura máxima del arco sobre el borde inferior del panel.
    //   Controla cuánto "salen" las cartas hacia arriba.
    // ROTACION_BASE: divisor de la rotación de cada carta.
    //   Cuanto mayor, menos inclinadas están las cartas en los extremos.
    // BASE_Y: posición Y base desde la que se calcula la altura del abanico.
    /**
     * Radio del arco sobre el que se distribuyen las cartas del abanico.
     */
    private static final double RADIO_BASE = 577.8;

    /**
     * Apertura angular mínima del abanico en grados. Se amplía con el número de
     * cartas.
     */
    private static final double ANGULO_BASE = 5.0;

    /**
     * Altura máxima del arco sobre el borde de la zona. Controla cuánto emergen
     * las cartas.
     */
    private static final double ALTURA_BASE = 80.0;

    /**
     * Divisor de la rotación individual de cada carta. Mayor valor = cartas más
     * verticales.
     */
    private static final double ROTACION_BASE = 0.8;

    /**
     * Posición Y base desde la que se calcula la curva del abanico inferior.
     */
    private static final double BASE_Y = 200.0;

    //---------------------Nodos FXML (comunes a todos los modos)------------------------------
    // Todos estos nodos están declarados en partida.fxml y son compartidos
    // por todos los modos de juego. Las subclases pueden acceder a ellos
    // directamente gracias al modificador protected.
    /**
     * Panel raíz de la escena. Contiene todas las zonas y el overlay final.
     */
    @FXML
    protected StackPane rootSala;

    /**
     * Zona de la pantalla donde se dibuja el abanico del jugador de arriba.
     */
    @FXML
    protected Pane zonaArriba;

    /**
     * Zona de la pantalla donde se dibuja el abanico del jugador de la
     * izquierda.
     */
    @FXML
    protected Pane zonaIzquierda;

    /**
     * Zona de la pantalla donde se dibuja el abanico del jugador de la derecha.
     */
    @FXML
    protected Pane zonaDerecha;

    /**
     * Zona de la pantalla donde se dibuja el abanico del jugador local (abajo).
     */
    @FXML
    protected Pane zonaAbajo;

    /**
     * Etiqueta con el nombre del jugador de arriba.
     */
    @FXML
    protected Label lblArriba;

    /**
     * Etiqueta con el nombre del jugador de la izquierda.
     */
    @FXML
    protected Label lblIzquierda;

    /**
     * Etiqueta con el nombre del jugador de la derecha.
     */
    @FXML
    protected Label lblDerecha;

    /**
     * Etiqueta con el nombre del jugador local (abajo).
     */
    @FXML
    protected Label lblAbajo;

    /**
     * Etiqueta central del narrador global. Muestra mensajes visibles para
     * todos los jugadores.
     */
    @FXML
    protected Label narradorLabel;

    /**
     * Etiqueta de información privada. Muestra mensajes solo para el jugador
     * local con animación de fade in/out.
     */
    @FXML
    protected Label lblInfo;

    /**
     * Panel central que contiene el narrador, el mazo y la pila de descartes.
     */
    @FXML
    protected AnchorPane zonaCentro;

    /**
     * Imagen del mazo de robo. Se oculta automáticamente si la baraja está
     * vacía.
     */
    @FXML
    protected ImageView imgMazo;

    /**
     * Imagen de la pila de descartes. Muestra la última carta descartada.
     */
    @FXML
    protected ImageView imgDescarte;

    /**
     * StackPane transparente que cubre toda la pantalla al finalizar la
     * partida. Contiene el popup de resultados (popUpFinalPartida.fxml).
     * También se usa para el overlay de jugador eliminado en Yusa.
     */
    @FXML
    protected StackPane overlayFinal;

    //------------------------------- Servicios Firebase --------------------------------------
    /**
     * Servicio de bajo nivel para operaciones HTTP contra Firebase Realtime
     * Database. Realiza peticiones GET, PUT y DELETE directamente sin SDK.
     */
    protected final FirebaseDatabaseService db = new FirebaseDatabaseService();

    /**
     * Servicio de alto nivel que abstrae las operaciones específicas de la
     * partida (leer manos, actualizar turno, escuchar narrador, etc.).
     * Construido sobre {@link #db}.
     */
    protected final BDPartidaService bd = new BDPartidaService(db);

    //-------------------------------  Sesión ----------------------------------------
    /**
     * Token de autenticación Firebase del jugador local. Se pasa en
     * {@link #init}.
     */
    protected String idToken;

    /**
     * Código único de la sala (p.ej. "ABCD12"). Se pasa en {@link #init}.
     */
    protected String codigoSala;

    /**
     * UID de Firebase del jugador local. Se pasa en {@link #init}.
     */
    protected String uidLocal;

    // ---------------------------- Estado de partida compartido ----------------------------------------
    /**
     * Manos de todos los jugadores. Clave: UID del jugador. Valor: lista de
     * rutas de imagen de sus cartas (p.ej.
     * "/ui/graphicResources/cartas/coronas_7.png"). Firebase representa la mano
     * vacía como ["EMPTY"], que se normaliza a lista vacía en
     * {@link #actualizarDesdeModelo}.
     */
    protected final Map<String, List<String>> manos = new HashMap<>();

    /**
     * Baraja de robo. Lista ordenada de rutas de imagen. El índice 0 es la
     * próxima carta a robar (top del mazo).
     */
    protected final List<String> baraja = new ArrayList<>();

    /**
     * Pila de descartes. Lista ordenada de rutas de imagen. El último elemento
     * es la carta visible en la pila.
     */
    protected final List<String> descarte = new ArrayList<>();

    /**
     * Mapa de nombres de los jugadores. Clave: UID. Valor: nombre de usuario.
     * Se carga de forma asíncrona en {@link #precargarNombres()}. Mientras no
     * carga, el UID actúa como fallback (putIfAbsent).
     */
    protected final Map<String, String> nombres = new HashMap<>();

    /**
     * Lista de hilos de polling activos. Se interrumpen todos al llamar a
     * {@link #destruir()}. Los hilos se añaden en
     * {@link #registrarListenersComunes()} y en
     * {@link #registrarHiloListener(Thread)} (para subclases).
     */
    private final List<Thread> hilosListeners = new ArrayList<>();

    /**
     * {@code true} cuando la partida ha finalizado. Evita doble llamada a
     * {@link #finalizarPartida()}.
     */
    protected boolean partidaFinalizada = false;

    /**
     * {@code true} cuando el host ya repartió las cartas iniciales. Evita que
     * {@link #prepararEstadoInicial} reparta varias veces si
     * {@link #actualizarDesdeModelo} se llama antes de que Firebase confirme el
     * reparto.
     */
    protected boolean repartoInicialHecho = false;

    /**
     * Motor de juego concreto (JuegoPescaito, JuegoYusa, etc.). Se crea en
     * {@link #crearJuego}.
     */
    protected Juego juego;

    /**
     * UID del jugador cuyo turno está activo en este momento.
     */
    protected String uidTurnoActual;

    /**
     * Orden global de los jugadores en la sala, en el orden en que se unieron.
     * Se usa para calcular la posición de cada jugador en pantalla (arriba,
     * izquierda, derecha) y para rotar el turno correctamente.
     */
    protected List<String> ordenJugadoresGlobal = new ArrayList<>();

    /**
     * UID del jugador posicionado arriba en pantalla.
     */
    protected String uidJugadorArriba;

    /**
     * UID del jugador posicionado a la izquierda en pantalla.
     */
    protected String uidJugadorIzquierda;

    /**
     * UID del jugador posicionado a la derecha en pantalla.
     */
    protected String uidJugadorDerecha;

    // --------------------------- Sistema de mensajes privados --------------------------------------
    /**
     * Cola FIFO de mensajes privados pendientes de mostrar al jugador local.
     * Los mensajes se encolan en {@link #encolarMensajePrivado} y se muestran
     * uno por uno en {@link #narrarSiguientePrivado()} con animación fade.
     */
    protected final Queue<String> colaPrivados = new LinkedList<>();

    /**
     * {@code true} mientras se está mostrando un mensaje privado animado. Evita
     * solapamientos.
     */
    protected boolean narrandoPrivado = false;

    // ------------------------------ Animación de mano --------------------------------------
    /**
     * {@code true} cuando el cursor está sobre la zona inferior (mano del
     * jugador local). Activa el modo expandido del abanico con mayor separación
     * entre cartas.
     */
    protected boolean manoAbierta = false;

    /**
     * Índice de la carta actualmente resaltada bajo el cursor del ratón.
     * {@code -1} si ninguna carta está seleccionada. Usado por
     * {@link #dibujarAbanicoAbajo} para aplicar el efecto de separación.
     */
    protected int cartaSeleccionada = -1;

    /**
     * Flag de seguridad para el ciclo de vida del controlador. Se establece a
     * {@code true} en {@link #destruir()} y en {@link #prepararNavegacion()}.
     * Todos los callbacks de los listeners comprueban este flag al inicio y
     * retornan inmediatamente si es {@code true}, evitando que instancias
     * antiguas del controlador actúen sobre nodos de escena que ya no están
     * activos (lo que causaría NullPointerException). Se declara
     * {@code volatile} porque es leído/escrito desde múltiples hilos.
     */
    protected volatile boolean controladorDestruido = false;

    // =========================================================================
    //  CLASE INTERNA DatosPopUp
    // =========================================================================
    /**
     * Objeto de transferencia de datos (DTO) que encapsula la información que
     * cada modo de juego proporciona al popup de fin de partida.
     *
     * <p>
     * Cada subclase implementa {@link #construirDatosPopUpFinal()} y devuelve
     * una instancia de esta clase. El popup muestra el icono, el resultado
     * principal y el detalle secundario.</p>
     *
     * <p>
     * Ejemplos de uso:</p>
     * <ul>
     * <li>Pescaito: icono="/ui/.../imgGanador.png", resultado="¡Ha ganado
     * Usuario1!", detalle="3 pescaitos"</li>
     * <li>Yusa: icono="/ui/.../imgGanador.png", resultado="¡Ha ganado
     * Usuario2!", detalle="Con 2 vidas restantes"</li>
     * </ul>
     */
    public static class DatosPopUp {

        /**
         * Ruta del recurso de imagen a mostrar como icono en el popup.
         */
        public final String icono;

        /**
         * Texto principal del resultado (p.ej. "¡Ha ganado Usuario1!").
         */
        public final String resultado;

        /**
         * Texto secundario con detalles adicionales (p.ej. "3 pescaitos").
         */
        public final String detalle;

        /**
         * Construye un nuevo objeto DatosPopUp con los datos de resultado.
         *
         * @param icono ruta del recurso de imagen para el icono del popup
         * @param resultado texto principal a mostrar (ganador, empate, etc.)
         * @param detalle texto secundario con información adicional
         */
        public DatosPopUp(String icono, String resultado, String detalle) {
            this.icono = icono;
            this.resultado = resultado;
            this.detalle = detalle;
        }
    }

    // =========================================================================
    //  PUNTO DE ENTRADA
    // =========================================================================
    /**
     * Inicializa el controlador de partida. Es el punto de entrada común para
     * todos los modos de juego online.
     *
     * <p>
     * El método sigue este orden de inicialización:</p>
     * <ol>
     * <li>Aplica animación de fade-in a la pantalla.</li>
     * <li>Almacena los datos de sesión (sala, UID, token).</li>
     * <li>Carga el estado de la partida desde Firebase
     * ({@link #cargarJuegoDesdeBD()}).</li>
     * <li>Configura los eventos de interacción (clic en cartas, clic en
     * mazo).</li>
     * <li>Carga el orden de jugadores de la sala.</li>
     * <li>Precarga los nombres de los jugadores de forma asíncrona.</li>
     * <li>Registra los listeners comunes a todos los modos (narrador, turno,
     * etc.).</li>
     * <li>Registra los listeners específicos del modo (delegado a
     * subclases).</li>
     * <li>Configura el layout de la escena para que las zonas se posicionen
     * correctamente al redimensionar la ventana.</li>
     * <li>Inicia la música de partida.</li>
     * </ol>
     *
     * <p>
     * <strong>Nota:</strong> Los modos offline sobreescriben este método con
     * una implementación vacía y usan su propio método
     * {@code iniciarOffline()}.</p>
     *
     * @param codigoSala código único de la sala (p.ej. "ABCD12")
     * @param uidLocal UID de Firebase del jugador local
     * @param idToken token de autenticación de Firebase
     */
    public void init(String codigoSala, String uidLocal, String idToken) {

        // Animación de entrada: la pantalla de partida aparece con fundido
        Platform.runLater(() -> Animaciones.fadeIn(rootSala));

        this.codigoSala = codigoSala;
        this.uidLocal = uidLocal;
        this.idToken = idToken;

        // Creamos el motor de juego e inicializar el estado desde Firebase
        cargarJuegoDesdeBD();

        // Eventos de mano y mazo (idénticos en todos los modos)
        configurarEventosManoJugador();
        cargarOrdenJugadoresGlobal();
        precargarNombres();
        configurarEventosRobar();

        // Listeners comunes (narrador, estado finalizada, turno)
        registrarListenersComunes();

        // Listeners específicos del modo (implementado en cada subclase)
        registrarListenersPropios();

        // Configuraramos el layout una vez que la escena ya está construida
        Platform.runLater(() -> {
            Stage stage = (Stage) zonaAbajo.getScene().getWindow();
            configurarLayoutEscena(stage);
        });

        System.out.println(">>> PartidaControllerBase.init() ejecutado"); // debug
        MusicManager.playPartidaMusic(); // Musica de partida
    }

    // =========================================================================
    //  HOOKS ABSTRACTOS — cada subclase los implementa
    // =========================================================================
    /**
     * Crea e inicializa el motor de juego concreto según el modo indicado.
     *
     * <p>
     * Se llama desde {@link #cargarJuegoDesdeBD()} con el valor del campo
     * "modo" leído desde Firebase. Cada subclase instancia su propio motor:</p>
     * <ul>
     * <li>{@code PartidaControllerPescaito} devuelve
     * {@code new JuegoPescaito()}</li>
     * <li>{@code PartidaControllerYusa} devuelve {@code new JuegoYusa()}</li>
     * </ul>
     *
     * @param modo cadena del modo de juego leída desde Firebase (p.ej.
     * "Pescaito", "Yusa")
     * @return instancia del motor de juego, o {@code null} si el modo no se
     * reconoce
     */
    protected abstract Juego crearJuego(String modo);

    /**
     * Registra los listeners de Firebase específicos del modo de juego.
     *
     * <p>
     * Se llama al final de {@link #init} para que cada subclase pueda añadir
     * los listeners que necesita. Por ejemplo:</p>
     * <ul>
     * <li>Yusa añade listeners para estadoRonda y decisionJugador.</li>
     * <li>Pescaito no añade listeners adicionales (el turno lo gestiona la
     * base).</li>
     * </ul>
     *
     * <p>
     * Los hilos deben registrarse con {@link #registrarHiloListener(Thread)}
     * para que {@link #destruir()} los interrumpa correctamente.</p>
     */
    protected abstract void registrarListenersPropios();

    /**
     * Define la reacción del modo de juego cuando cambia el turno global en
     * Firebase.
     *
     * <p>
     * Este hook se llama desde el listener de turno en
     * {@link #registrarListenersComunes()} cada vez que el campo
     * {@code partida/turno} cambia en la base de datos.</p>
     *
     * <p>
     * Ejemplos de implementación:</p>
     * <ul>
     * <li>Pescaito: narra "Turno de: NombreJugador" en el narradorLabel.</li>
     * <li>Yusa: comprueba si debe iniciar una nueva ronda.</li>
     * </ul>
     *
     * @param nuevoTurno UID del jugador cuyo turno comienza
     */
    protected abstract void onCambioTurno(String nuevoTurno);

    /**
     * Define el comportamiento al hacer clic en el mazo de robo (imgMazo).
     *
     * <p>
     * Pescaito: roba la carta superior del mazo si el jugador está esperando
     * robo. Yusa: ignora el clic (no hay robo manual en Yusa).</p>
     */
    protected abstract void onClickMazo();

    /**
     * Define el comportamiento al hacer clic en la zona de un jugador rival.
     *
     * <p>
     * Se llama cuando el jugador local pulsa sobre la zona de cartas de un
     * rival. El UID del rival se determina en {@link #colocarJugadores()} y se
     * captura en los listeners de clic de las zonas.</p>
     *
     * @param uidRival UID del jugador rival cuya zona fue pulsada
     */
    protected abstract void onZonaRivalClick(String uidRival);

    /**
     * Define el comportamiento al hacer clic en una carta de la mano local.
     *
     * <p>
     * Se llama cuando el jugador local pulsa sobre una de sus propias cartas.
     * Pescaito: selecciona el número de esa carta para la siguiente pregunta.
     * Yusa: no tiene acción directa en carta propia durante la fase normal.</p>
     *
     * @param rutaCarta ruta de recurso de la carta pulsada (p.ej.
     * "/ui/graphicResources/cartas/coronas_7.png")
     */
    protected abstract void onCartaLocalClick(String rutaCarta);

    /**
     * Construye los datos de resultado para el popup de fin de partida.
     *
     * <p>
     * Este método se ejecuta en un hilo de background (ver
     * {@link #mostrarPantallaFinal()}), por lo que puede realizar lecturas de
     * Firebase de forma segura sin bloquear la UI.</p>
     *
     * <p>
     * Debe devolver un objeto {@link DatosPopUp} con:</p>
     * <ul>
     * <li>{@code icono}: ruta de la imagen a mostrar (ganador o empate).</li>
     * <li>{@code resultado}: texto principal (p.ej. "¡Ha ganado
     * Usuario1!").</li>
     * <li>{@code detalle}: texto secundario (p.ej. "3 pescaitos").</li>
     * </ul>
     *
     * @return objeto con los datos del resultado de la partida
     */
    protected abstract DatosPopUp construirDatosPopUpFinal();

    // =========================================================================
    //  CARGA INICIAL
    // =========================================================================
    /**
     * Carga el modo de juego y el estado de la partida desde Firebase al
     * iniciar.
     *
     * <p>
     * Pasos que realiza:</p>
     * <ol>
     * <li>Lee el campo "modo" del nodo de partida en Firebase.</li>
     * <li>Crea el motor de juego llamando a {@link #crearJuego(String)}.</li>
     * <li>Lee la partida completa (manos, baraja, descarte) y sincroniza el
     * modelo local.</li>
     * <li>Delega la inicialización específica del modo a
     * {@link #prepararEstadoInicial}.</li>
     * </ol>
     *
     * <p>
     * Si algún paso falla (modo no encontrado, partida nula), el método imprime
     * un aviso y retorna sin lanzar excepción, dejando el controlador en estado
     * vacío.</p>
     */
    private void cargarJuegoDesdeBD() {
        try { // lee nodo modo
            Object modoObj = bd.leerCampo(codigoSala, "modo", idToken);
            if (modoObj == null) {
                System.out.println("Modo no encontrado."); // Si no lo encuentra volvemos
                return;
            }

            String modo = modoObj.toString();
            System.out.println("Modo de juego cargado: " + modo);

            // crea el motor de juego con el modo extraído
            juego = crearJuego(modo);
            if (juego == null) {
                System.out.println("Modo no reconocido: " + modo);
                return;
            }

            Map<String, Object> partida = bd.leerPartida(codigoSala, idToken);
            if (partida == null) {
                System.out.println("Partida no encontrada."); // Si no encuentra partida, volvemos.
                return;
            }

            // Sincronizar el modelo local con el estado leído de Firebase
            actualizarDesdeModelo(
                    (Map<String, List<String>>) partida.get("manos"),
                    (List<String>) partida.get("baraja"),
                    (List<String>) partida.get("descarte")
            );

            prepararEstadoInicial(partida); // preparamos estado inicial

        } catch (Exception e) { // Capturas de posibles excepciones
            e.printStackTrace();
        }
    }

    /**
     * Carga la lista de jugadores de la sala desde Firebase y la almacena en
     * {@link #ordenJugadoresGlobal}.
     *
     * <p>
     * El orden en que los jugadores aparecen en el nodo
     * {@code salas/{codigo}/jugadores} determina su posición visual en la mesa
     * (quién está arriba, a la izquierda, a la derecha) y cómo rota el turno en
     * Pescaito. Es importante preservar este orden durante toda la partida.</p>
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
     * Prepara el estado inicial de la partida, repartiendo las cartas si es
     * necesario.
     *
     * <p>
     * Solo el host (el primer jugador en unirse a la sala) reparte las cartas.
     * Los demás jugadores esperan a que el host las reparta y las publique en
     * Firebase. Este diseño evita condiciones de carrera en el reparto
     * inicial.</p>
     *
     * <p>
     * El método comprueba si las manos ya están repartidas (no vacías) para
     * evitar repartir dos veces en caso de reconexión o recarga.</p>
     *
     * <p>
     * Las subclases pueden sobreescribir este método para añadir lógica propia
     * (p.ej. Yusa inicializa las vidas de los jugadores en Firebase).</p>
     *
     * @param partida mapa con todos los campos del nodo de partida leídos de
     * Firebase
     */
    protected void prepararEstadoInicial(Map<String, Object> partida) {
        if (juego == null) {
            return;
        }

        boolean manosVacias = manos.values().stream().allMatch(m -> m == null || m.isEmpty());

        if (!manosVacias) { // Si las manos ya están repartidas, vovlemos
            System.out.println("Las manos ya están repartidas. No se hace nada.");
            actualizarInterfaz();
            return;
        }

        try { // Si no, es el host quien tiene que repartirlas
            String uidHost = db.leerNodo("salas/" + codigoSala + "/host", idToken)
                    .replace("\"", "");
            if (!uidLocal.equals(uidHost)) { // Si no eres host, esperas.
                System.out.println("No soy el host, espero a que el host reparta.");
                actualizarInterfaz();
                return;
            }

            System.out.println("Soy el host. Repartiendo cartas iniciales...");

            // Si el mapa de manos local está vacío, construirlo con los jugadores de la sala
            if (manos.isEmpty()) {
                String jsonSala = db.leerNodo("salas/" + codigoSala, idToken);
                Map<String, Object> sala = new Gson().fromJson(jsonSala, Map.class);
                Map<String, Object> jugadores = (Map<String, Object>) sala.get("jugadores");
                for (String uid : jugadores.keySet()) {
                    manos.put(uid, null);
                }
            }

            // El motor de juego distribuye las cartas de la baraja a las manos
            juego.iniciarPartida(manos, baraja);

            // Publicar las manos y la baraja actualizada en Firebase para todos los jugadores
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
     * Sincroniza el estado local del controlador con los datos más recientes de
     * Firebase.
     *
     * <p>
     * Este método es el punto central de actualización del modelo. Se
     * llama:</p>
     * <ul>
     * <li>Desde el listener de partida completa (polling de 500ms).</li>
     * <li>Desde el Timeline de refresco en
     * {@code MainApp.cambiarEscena()}.</li>
     * <li>Directamente al cargar la partida en
     * {@link #cargarJuegoDesdeBD()}.</li>
     * </ul>
     *
     * <p>
     * Pasos que realiza:</p>
     * <ol>
     * <li>Sale inmediatamente si la partida ya finalizó (evita actualizaciones
     * fantasma).</li>
     * <li>Normaliza el marcador especial {@code ["EMPTY"]} a lista vacía real.
     * Firebase no puede almacenar listas vacías nativas, por lo que se usa esta
     * convención para representar una mano sin cartas.</li>
     * <li>Reemplaza el contenido de manos, baraja y descarte locales con los
     * datos de Firebase.</li>
     * <li>Lee el turno actual del nodo {@code partida/turno}.</li>
     * <li>Marca {@link #repartoInicialHecho} cuando detecta que hay
     * cartas.</li>
     * <li>Llama a {@link #actualizarInterfaz()} para repintar la UI.</li>
     * </ol>
     *
     * @param manosBD mapa de manos recibido de Firebase (puede contener
     * ["EMPTY"])
     * @param barajaBD lista de cartas de la baraja recibida de Firebase
     * @param descarteBD lista de cartas del descarte recibida de Firebase
     * @throws IOException si la lectura del turno desde Firebase falla
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

        // Normalizar ["EMPTY"] - lista vacía real
        for (Map.Entry<String, List<String>> entry : manosBD.entrySet()) {
            List<String> lista = entry.getValue();
            if (lista != null && lista.size() == 1 && "EMPTY".equals(lista.get(0))) {
                entry.setValue(new ArrayList<>());
            }
        }

        // Reemplazar el estado local con los datos frescos de Firebase
        this.manos.clear();
        this.manos.putAll(manosBD);
        this.baraja.clear();
        this.baraja.addAll(barajaBD != null ? barajaBD : new ArrayList<>());
        this.descarte.clear();
        this.descarte.addAll(descarteBD != null ? descarteBD : new ArrayList<>());

        // Leer quién tiene el turno actualmente
        try {
            String turno = db.leerNodo("salas/" + codigoSala + "/partida/turno", idToken);
            if (turno != null) {
                // Eliminamos las comillas de Firebase con replace
                uidTurnoActual = turno.replace("\"", "");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Detectamos el momento en que las cartas se reparten por primera vez
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
    /**
     * Registra los listeners de Firebase que son comunes a todos los modos de
     * juego.
     *
     * <p>
     * Cada listener es un hilo de polling que consulta periódicamente un nodo
     * de Firebase. Al detectar un cambio, ejecuta su callback en el hilo de
     * JavaFX mediante {@code Platform.runLater()}. Todos comprueban
     * {@link #controladorDestruido} al inicio para no actuar si el controlador
     * ya fue destruido.</p>
     *
     * <p>
     * Listeners registrados:</p>
     * <ul>
     * <li><strong>Narrador</strong>: escucha {@code partida/narrador} y procesa
     * mensajes globales y privados.</li>
     * <li><strong>Estado partida</strong>: escucha {@code partida/estado} y
     * activa el popup final cuando el valor es "finalizada".</li>
     * <li><strong>Turno</strong>: escucha {@code partida/turno} y llama a
     * {@link #onCambioTurno(String)} cuando cambia.</li>
     * <li><strong>Partida completa</strong>: escucha el nodo {@code partida}
     * completo y llama a {@link #actualizarDesdeModelo} con los nuevos
     * datos.</li>
     * <li><strong>Volver a sala</strong>: escucha el flag {@code volverSala}
     * que el host escribe al pulsar "Volver a la sala" en el popup final.</li>
     * <li><strong>Nueva partida</strong>: escucha cuando el host inicia una
     * nueva partida del mismo modo, recargando la pantalla de juego.</li>
     * </ul>
     */
    private void registrarListenersComunes() {

        // Listener del narrador: muestra mensajes globales y privados
        hilosListeners.add(bd.escucharNarrador(codigoSala, idToken, mensaje -> Platform.runLater(() -> {
            if (controladorDestruido) {
                return;
            }
            procesarMensajeNarrador(mensaje);
        }))
        );

        // Listener del estado de partida: detecta cuando la partida termina
        hilosListeners.add(bd.escucharEstadoPartida(codigoSala, idToken, estado -> {
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

        // Listener del turno: notifica a la subclase cuando cambia el jugador activo
        hilosListeners.add(bd.escucharTurno(codigoSala, idToken, nuevoTurno -> Platform.runLater(() -> {
            if (controladorDestruido) {
                return;
            }
            uidTurnoActual = nuevoTurno;
            onCambioTurno(nuevoTurno);
        }))
        );

        // Listener de la partida completa: sincroniza manos, baraja y descarte
        hilosListeners.add(bd.escucharPartida(codigoSala, idToken, partida -> {
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

        // Listener del flag volverSala: redirige a todos los jugadores a la sala online.
        // NOTA: el flag lo borra el host con un delay de 3s después de escribirlo,
        // para asegurar que todos los clientes lo leen antes de que desaparezca.
        hilosListeners.add(bd.escucharVolverSala(codigoSala, idToken, ts -> Platform.runLater(() -> {
            if (controladorDestruido) {
                return;
            }
            prepararNavegacion();
            MainApp.cambiarEscena("salaOnline.fxml", 1200, 1000);
        }))
        );

        // Listener de nueva partida: recarga la pantalla de juego cuando el host
        // inicia otra partida del mismo modo desde el popUp final.
        hilosListeners.add(bd.escucharNuevaPartida(codigoSala, idToken, modo -> Platform.runLater(() -> {
            if (controladorDestruido) {
                return;
            }
            if (!partidaFinalizada) { // solo actuar si la partida había terminado
                return;
            }
            prepararNavegacion();
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/ui/partida.fxml"));
                PartidaControllerBase controller;
                if ("Pescaito".equals(modo)) { // leemos el modo para volver a cargarlo
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
     * Cierra la partida: deshabilita la interacción del usuario, escribe el
     * estado "finalizada" en Firebase y muestra el popup de resultados.
     *
     * <p>
     * El método es seguro contra doble llamada gracias al flag
     * {@link #partidaFinalizada}. Si se llama más de una vez, la segunda
     * llamada retorna inmediatamente sin hacer nada.</p>
     *
     * <p>
     * También borra el nodo {@code partida/narrador} de Firebase para evitar
     * que las salas queden con mensajes obsoletos visibles si se inicia una
     * nueva partida.</p>
     *
     * <p>
     * El popup se muestra directamente mediante {@code Platform.runLater} en
     * lugar de esperar al listener de estado, para garantizar que aparece
     * incluso si el listener llega tarde o tiene problemas de conectividad.</p>
     */
    protected void finalizarPartida() {
        if (partidaFinalizada) {
            return;
        }

        // Deshabilitar todas las zonas de interacción para que el jugador no pueda actuar
        zonaArriba.setDisable(true);
        zonaIzquierda.setDisable(true);
        zonaDerecha.setDisable(true);
        zonaAbajo.setDisable(true);
        zonaCentro.setDisable(true);

        try { // Escribimos "finalizada" para que tolos los clientes sean notificados
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
     * Carga y muestra el popup de fin de partida (popUpFinalPartida.fxml).
     *
     * <p>
     * El proceso es asíncrono en dos fases para no bloquear la UI:</p>
     * <ol>
     * <li>Un hilo de background llama a {@link #construirDatosPopUpFinal()},
     * que puede hacer lecturas de Firebase sin bloquear JavaFX.</li>
     * <li>Cuando los datos están listos, {@code Platform.runLater} carga el
     * FXML, inyecta los datos en el controller e inserta el popup en
     * {@link #overlayFinal}.</li>
     * </ol>
     *
     * <p>
     * Si la carga del FXML falla, se muestra un label de texto simple como
     * fallback para que el jugador siempre vea algo aunque haya un error.</p>
     */
    protected void mostrarPantallaFinal() {
        // Calcular datos en background (puede leer Firebase)
        new Thread(() -> {
            // Calcular datos de resultado en background (puede leer Firebase)
            DatosPopUp datos = construirDatosPopUpFinal();

            Platform.runLater(() -> {
                try {
                    FXMLLoader loader = new FXMLLoader(
                            getClass().getResource("/ui/popUpFinalPartida.fxml"));
                    // Cargar el FXML - el controller ya está declarado en el fx:controller
                    StackPane popUpPane = loader.load();

                    PopUpFinalPartidaController popUpCtrl = loader.getController();

                    /// Leer el modo de juego para que el popup pueda reiniciar correctamente
                    String modo = ""; // se rellena abajo
                    try {
                        Object modoObj = bd.leerCampo(codigoSala, "modo", idToken);
                        if (modoObj != null) {
                            modo = modoObj.toString();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    // Inicializar el popup con todos los datos necesarios
                    popUpCtrl.init(codigoSala, uidLocal, idToken,
                            modo, datos.icono, datos.resultado, datos.detalle, this
                    ); // Con this refereciamos al controlador para poder destruirlo.

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
     * Calcula el UID del siguiente jugador en orden circular usando
     * {@link #ordenJugadoresGlobal}.
     *
     * <p>
     * Se usa en Pescaito para rotar el turno entre todos los jugadores, y en
     * Yusa para rotar el "director" (quien inicia la ronda) al finalizar cada
     * ronda.</p>
     *
     * <p>
     * Si el UID actual no se encuentra en la lista (p.ej. fue eliminado),
     * devuelve el primer jugador de la lista como fallback.</p>
     *
     * @param uidActual UID del jugador cuyo turno acaba de terminar
     * @return UID del siguiente jugador en el orden circular
     */
    protected String obtenerSiguienteJugador(String uidActual) {
        if (ordenJugadoresGlobal == null || ordenJugadoresGlobal.isEmpty()) {
            return uidActual;
        }
        int idx = ordenJugadoresGlobal.indexOf(uidActual);
        if (idx == -1) {
            // El jugador no está en la lista (eliminado o error): devolver el primero
            return ordenJugadoresGlobal.get(0);
        }
        // Módulo para circular al inicio cuando se llega al final de la lista
        return ordenJugadoresGlobal.get((idx + 1) % ordenJugadoresGlobal.size());
    }

    /**
     * Encuentra el jugador con mayor puntuación en el mapa dado.
     *
     * <p>
     * Funciona tanto para pescaitos (conteo de grupos completados) como para
     * vidas restantes en Yusa (mayor número de vidas = ganador).</p>
     *
     * @param puntuaciones mapa UID → puntuación entera
     * @return UID del jugador con la puntuación más alta, o {@code null} si el
     * mapa está vacío
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
    /**
     * Envía un mensaje global visible para todos los jugadores de la sala.
     *
     * <p>
     * El mensaje se escribe en el nodo {@code partida/narrador} de Firebase con
     * tipo "global". El listener de narrador de cada cliente lo recibe y
     * actualiza su {@link #narradorLabel} con el texto.</p>
     *
     * <p>
     * Los mensajes globales se muestran indefinidamente en el narradorLabel
     * hasta que se recibe el siguiente mensaje.</p>
     *
     * @param texto texto del mensaje a mostrar a todos los jugadores
     */
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

    /**
     * Envía un mensaje privado visible solo para un jugador específico.
     *
     * <p>
     * Si el destinatario es el jugador local, el mensaje se encola directamente
     * en la cola de mensajes privados sin pasar por Firebase (optimización de
     * latencia). Si el destinatario es otro jugador, se escribe en Firebase con
     * tipo "privado" y el UID del destinatario.</p>
     *
     * <p>
     * Los mensajes privados se muestran en {@link #lblInfo}. Si hay varios
     * mensajes en cola, se muestran secuencialmente uno tras otro.</p>
     *
     * @param uidDestino UID del jugador que debe ver el mensaje
     * @param texto texto del mensaje privado
     */
    protected void narrarPrivado(String uidDestino, String texto) {
        if (uidDestino.equals(uidLocal)) {
            // Optimización: si es para mí mismo, encolar directamente sin ir a Firebase
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

    /**
     * Procesa un mensaje del narrador recibido desde Firebase.
     *
     * <p>
     * Los mensajes globales se muestran directamente en {@link #narradorLabel}.
     * Los mensajes privados se encolan si el destinatario es el jugador
     * local.</p>
     *
     * @param msg mapa con las claves "tipo", "texto" y "uid"
     */
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

    /**
     * Añade un mensaje privado a la cola y lo muestra si no hay otro en curso.
     *
     * <p>
     * Este método gestiona la sincronización de la cola: si
     * {@link #narrandoPrivado} es {@code false}, inicia inmediatamente la
     * animación del mensaje. Si ya hay un mensaje mostrándose, el nuevo mensaje
     * espera en la cola.</p>
     *
     * @param texto texto del mensaje a encolar
     */
    protected void encolarMensajePrivado(String texto) {
        colaPrivados.add(texto);
        if (!narrandoPrivado) {
            narrarSiguientePrivado();
        }
    }

    /**
     * Extrae el siguiente mensaje de la cola y lo muestra con animación fade
     * in/out.
     *
     * <p>
     * El mensaje aparece en {@link #lblInfo} con un fade in de 200ms, permanece
     * visible 500ms y desaparece con un fade out de 1500ms. Al terminar, se
     * llama recursivamente para mostrar el siguiente mensaje de la cola.</p>
     *
     * <p>
     * Si la cola está vacía, establece {@link #narrandoPrivado} a {@code false}
     * para que el siguiente mensaje que llegue inicie el ciclo
     * inmediatamente.</p>
     */
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
    //  INTERFAZ - RENDERIZADO
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

    /**
     * Elimina todos los nodos hijo de las cuatro zonas de jugadores. Debe
     * llamarse al inicio de cada redibujado para evitar acumulación de
     * ImageViews.
     */
    private void limpiarZonas() {
        zonaArriba.getChildren().clear();
        zonaIzquierda.getChildren().clear();
        zonaDerecha.getChildren().clear();
        zonaAbajo.getChildren().clear();
    }

    /**
     * Actualiza las etiquetas de nombre de los jugadores.
     *
     * <p>
     * Los nombres se leen del mapa {@link #nombres}, que se carga de forma
     * asíncrona en {@link #precargarNombres()}. Si un nombre no está disponible
     * todavía, la etiqueta muestra una cadena vacía.</p>
     *
     * <p>
     * Las subclases offline sobreescriben este método para usar sus propios
     * mapas de nombres sin acceder a Firebase.</p>
     */
    protected void cargarNombresJugadores() {
        for (String uid : manos.keySet()) {
            try {
                String json = db.leerNodo("usuarios/" + uid + "/nombre", idToken);
                nombres.put(uid, json.replace("\"", ""));
            } catch (Exception e) {
                /* ignorar */ }
        }
    }

    /**
     * Distribuye a los jugadores en las cuatro posiciones de la mesa y dibuja
     * sus abanicos de cartas boca abajo.
     *
     * <p>
     * La posición de cada jugador es relativa al jugador local, que siempre
     * ocupa la posición "abajo". El resto se distribuye en sentido horario:</p>
     * <ul>
     * <li>Posición 1 (siguiente en la lista rotada): derecha.</li>
     * <li>Posición 2: arriba.</li>
     * <li>Posición 3: izquierda.</li>
     * </ul>
     *
     * <p>
     * La lista se rota desde el índice del jugador local para que siempre esté
     * en la posición 0. Los abanicos de rivales muestran la parte trasera de
     * las cartas para mantener el secreto del juego.</p>
     *
     * <p>
     * También configura los listeners de clic en cada zona de rival, capturando
     * el UID en una variable final para evitar el problema de captura de
     * variables mutables en lambdas.</p>
     */
    protected void colocarJugadores() {
        if (ordenJugadoresGlobal == null || ordenJugadoresGlobal.isEmpty()) {
            return;
        }
        int idxLocal = ordenJugadoresGlobal.indexOf(uidLocal);
        if (idxLocal == -1) {
            return;
        }

        // Rotamos la lista para que el jugador local quede en el índice 0
        List<String> rotado = new ArrayList<>();
        for (int i = 0; i < ordenJugadoresGlobal.size(); i++) {
            rotado.add(ordenJugadoresGlobal.get((idxLocal + i) % ordenJugadoresGlobal.size()));
        }

        // Jugador local siempre abajo con índice 0 en la lista rotada
        lblAbajo.setText(nombres.getOrDefault(rotado.get(0), ""));

        // Asignar posiciones a los rivales según el tamaño de la lista
        String derecha = rotado.size() > 1 ? rotado.get(1) : null;
        String arriba = rotado.size() > 2 ? rotado.get(2) : null;
        String izquierda = rotado.size() > 3 ? rotado.get(3) : null;

        // Posicionamiento de cada jugador en la pantall
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

    /**
     * Actualiza la imagen del mazo de robo en la interfaz. Muestra la parte
     * trasera de una carta si hay cartas disponibles, o oculta el mazo si la
     * baraja está vacía.
     */
    protected void mostrarMazo() {
        if (baraja == null || baraja.isEmpty()) {
            imgMazo.setVisible(false);
            return;
        }
        imgMazo.setImage(new Image("/ui/graphicResources/cartas/parteTrasera.png"));
        imgMazo.setVisible(true);
    }

    /**
     * Actualiza la imagen de la pila de descartes en la interfaz. Muestra la
     * última carta descartada, o oculta la pila si está vacía.
     */
    protected void mostrarDescarte() {
        if (descarte == null || descarte.isEmpty()) {
            imgDescarte.setVisible(false);
            return;
        }
        // El último elemento de la lista es la carta visible en la pila de descartes
        imgDescarte.setImage(new Image(descarte.get(descarte.size() - 1)));
        imgDescarte.setVisible(true);
    }

    // =========================================================================
    //  EVENTOS DE MANO Y MAZO
    // =========================================================================
    /**
     * Configura los eventos de ratón sobre la zona de la mano del jugador
     * local.
     *
     * <p>
     * Eventos configurados:</p>
     * <ul>
     * <li><strong>mouseEntered</strong>: activa el modo expandido
     * ({@link #manoAbierta} = true) y redibuja el abanico con mayor separación
     * entre cartas.</li>
     * <li><strong>mouseExited</strong>: desactiva el modo expandido y resetea
     * la carta seleccionada, volviendo al abanico compacto.</li>
     * <li><strong>mouseMoved</strong>: calcula qué carta está bajo el cursor y,
     * si cambia, redibuja el abanico para resaltar la carta con efecto de
     * elevación y sombra.</li>
     * </ul>
     */
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
                // Solo redibujar si cambia la carta seleccionada (evita redibujados innecesarios: OPTMIZACION)
                dibujarAbanicoAbajo(zonaAbajo, manos.get(uidLocal));
            }
        });
    }

    /**
     * Configura los eventos de interacción sobre el mazo de robo (imgMazo).
     *
     * <p>
     * Añade:</p>
     * <ul>
     * <li>Efecto de resplandor verde al pasar el cursor por encima.</li>
     * <li>Delegación del clic a {@link #onClickMazo()} (implementado por la
     * subclase).</li>
     * </ul>
     */
    protected void configurarEventosRobar() {
        imgMazo.setOnMouseEntered(e -> {
            // Efecto visual de "disponible para robar" en verde brillante
            DropShadow glow = new DropShadow();
            glow.setColor(Color.LIMEGREEN);
            glow.setRadius(25);
            glow.setSpread(0.4);
            imgMazo.setEffect(glow);
        });
        imgMazo.setOnMouseExited(e -> imgMazo.setEffect(null));
        imgMazo.setOnMouseClicked(e -> onClickMazo());
    }

    /**
     * Redibuja únicamente el abanico de la mano local. Más eficiente que llamar
     * a {@link #actualizarInterfaz()} cuando solo cambia la animación de la
     * mano (hover, selección de carta).
     */
    protected void redibujarManoLocal() {
        List<String> cartas = manos.get(uidLocal);
        if (cartas != null) {
            dibujarAbanicoAbajo(zonaAbajo, cartas);
        }
    }

    /**
     * Determina el índice de la carta que está bajo la posición X del cursor.
     *
     * <p>
     * Itera sobre los nodos hijos de {@link #zonaAbajo} y comprueba si la
     * coordenada X del cursor cae dentro de los límites de cada carta. Devuelve
     * el índice del primer nodo que contiene el cursor, o -1 si ninguno lo
     * hace.</p>
     *
     * @param mouseX coordenada X del cursor relativa a la zona inferior
     * @return índice de la carta bajo el cursor, o -1 si no hay ninguna
     */
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
    /**
     * Configura el posicionamiento dinámico de las zonas de jugadores en la
     * escena.
     *
     * <p>
     * Las constantes de offset (OAX, OAY, etc.) ajustan fino la posición de
     * cada zona respecto al centro geométrico de la ventana. Se determinaron
     * empíricamente.</p>
     *
     * <p>
     * Se registran listeners en {@code widthProperty} y {@code heightProperty}
     * del Stage para que las zonas se reposicionen automáticamente cuando el
     * usuario redimensiona la ventana o cambia entre modo ventana y
     * fullscreen.</p>
     *
     * <p>
     * Convención de nombres de offsets:</p>
     * <ul>
     * <li>OA = Offset Abajo (zona inferior)</li>
     * <li>ORR = Offset Rival aRRiba (zona superior)</li>
     * <li>OI = Offset Izquierda</li>
     * <li>OD = Offset Derecha</li>
     * <li>OC = Offset Centro</li>
     * </ul>
     *
     * @param stage el Stage principal de la aplicación
     */
    public void configurarLayoutEscena(Stage stage) {
        // Offsets de ajuste para cada zona.
        final double OAX = -40, OAY = -10, ORRX = -40, ORRY = -110;
        final double OIX = -70, OIY = 100, ODX = 0, ODY = -210, OCX = 0, OCY = 0;

        Runnable recolocar = () -> {
            double w = stage.getWidth(), h = stage.getHeight();
            double cx = w / 2, cy = h / 2;
            // Centrar cada zona respecto al centro de la ventana, con offsets de ajuste
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
        // Redibujar layout al cambiar el tamaño de la ventana.
        stage.widthProperty().addListener((obs, o, n) -> recolocar.run());
        stage.heightProperty().addListener((obs, o, n) -> recolocar.run());
    }

    // =========================================================================
    //  DIBUJO DE ABANICOS — idénticos en todos los modos
    // =========================================================================
    /**
     * Dibuja el abanico de cartas del jugador local en la zona inferior.
     *
     * <p>
     * Este es el método de renderizado más complejo. El abanico se adapta
     * dinámicamente al número de cartas y al estado interactivo (mano
     * abierta/cerrada, carta seleccionada). El algoritmo:</p>
     * <ol>
     * <li>Para 1 carta: la centra sin ángulo.</li>
     * <li>Para N cartas: calcula posiciones sobre un arco circular definido por
     * {@link #RADIO_BASE} y {@link #ANGULO_BASE}, ambos ampliados
     * proporcionalmente al número de cartas.</li>
     * <li>Centra horizontalmente el conjunto de cartas en el panel.</li>
     * <li>Aplica efecto de separación lateral cuando el cursor está cerca de
     * una carta ({@link #cartaSeleccionada} != -1): las cartas vecinas se
     * separan para facilitar la lectura y el clic.</li>
     * <li>Resalta la carta bajo el cursor con escala 1.25x, elevación y sombra
     * roja.</li>
     * </ol>
     *
     * <p>
     * El algoritmo de centrado calcula el bounding box de todas las cartas
     * antes de posicionarlas y aplica un offset para que el centro del conjunto
     * coincida con el centro del panel, independientemente del número de
     * cartas.</p>
     *
     * @param zona el panel JavaFX donde se dibujarán las cartas
     * @param cartas lista de rutas de imagen de las cartas a dibujar
     */
    protected void dibujarAbanicoAbajo(Pane zona, List<String> cartas) {
        zona.getChildren().clear();
        if (cartas == null || cartas.isEmpty()) {
            return;
        }
        int n = cartas.size();

        // Caso especial: una sola carta, centrada sin ángulo
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
                // Efecto de elevación y sombra cuando el ratón está sobre la zona
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

        // Parámetros del abanico: se amplían con el número de cartas para adaptarse
        double radio = RADIO_BASE + (n * 20) + (manoAbierta ? 60 : 0);
        double anguloTotal = ANGULO_BASE + (n * 2) + (manoAbierta ? 20 : 0);
        double alturaArco = ALTURA_BASE + (n * 3) + (manoAbierta ? 15 : 0);
        double divisorRot = ROTACION_BASE + (n * 0.05);
        double anguloInic = -anguloTotal / 2;
        double centroPaneX = zona.getWidth() / 2;

        // Listas para acumular posiciones antes de centrar
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>(), rots = new ArrayList<>();
        List<ImageView> imgs = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            ImageView img = new ImageView(new Image(cartas.get(i)));
            img.setFitHeight(140);
            img.setPreserveRatio(true);
            String ruta = cartas.get(i);
            img.setOnMouseClicked(e -> onCartaLocalClick(ruta));
            // Calcular posición de la carta sobre el arco circular
            double angulo = anguloInic + (anguloTotal / (n - 1)) * i;
            double rad = Math.toRadians(angulo);
            double x = Math.sin(rad) * radio, y = BASE_Y - Math.cos(rad) * alturaArco;
            double rot = angulo / divisorRot;
            // Compensación vertical: las cartas inclinadas en los extremos parecen más bajas
            double compY = Math.sin(Math.toRadians(Math.abs(rot))) * (img.getFitHeight() * 0.35);
            x -= img.getFitHeight() / 4; // Ajuste del punto de referencia al centro visual
            y += compY;

            // Efecto de separación lateral al pasar el cursor
            if (manoAbierta && cartaSeleccionada != -1) {
                int dist = Math.abs(i - cartaSeleccionada);
                double sep = dist * 12.0;
                if (i < cartaSeleccionada) {
                    x -= sep;// Las cartas a la izquierda se desplazan más a la izquierda
                } else if (i > cartaSeleccionada) {
                    x += sep; // Las cartas a la derecha se desplazan más a la derecha
                }
            }
            xs.add(x);
            ys.add(y);
            rots.add(rot);
            imgs.add(img);
        }

        // Calcular el offset para centrar horizontalmente el conjunto de cartas
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
                // Resaltar la carta bajo el cursor: ampliar, elevar y añadir sombra roja
                img.setScaleX(1.25);
                img.setScaleY(1.25);
                img.setTranslateY(-25);
                DropShadow g = new DropShadow();
                g.setColor(Color.RED);
                g.setRadius(25);
                img.setEffect(g);
            } else {
                // Restablecer a estado normal
                img.setScaleX(1.0);
                img.setScaleY(1.0);
                img.setTranslateY(0);
                img.setEffect(null);
            }
        }
    }

    /**
     * Dibuja el abanico de cartas del rival de arriba, boca abajo y girado
     * 180°.
     *
     * <p>
     * El algoritmo calcula las posiciones igual que
     * {@link #dibujarAbanicoAbajo}, pero luego aplica una transformación
     * especular respecto al centro del panel para que el abanico "cuelgue"
     * desde la parte superior de la zona. Todas las cartas muestran la parte
     * trasera para mantener el secreto.</p>
     *
     * <p>
     * Transformación: dado el punto (x0, y0) calculado con el algoritmo de
     * abanico, el punto final es (2·cx - x0, 2·cy - y0), que es la reflexión
     * respecto a (cx, cy).</p>
     *
     * @param zona panel donde se dibuja el abanico superior
     * @param cartas lista de cartas del rival (solo se usa el tamaño, no el
     * contenido)
     */
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
            // Reflejar respecto al centro del panel
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
            // Mostramos parte trasera de la c arta
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

    /**
     * Dibuja el abanico de cartas del rival de la izquierda, rotado 90° en
     * sentido antihorario.
     *
     * <p>
     * Usa el mismo algoritmo de abanico pero aplica una rotación de sistema de
     * coordenadas de 90°. Para cada punto (x, y) calculado con el algoritmo
     * estándar, la transformación es: layoutX = cx - dy, layoutY = cy + dx,
     * donde dx = x - cx, dy = y - cy. Esto equivale a rotar el sistema de
     * coordenadas 90° en sentido horario.
     * </p>
     *
     * @param zona panel donde se dibuja el abanico izquierdo
     * @param cartas lista de cartas del rival (solo se usa el tamaño)
     */
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
            // Rotación 90°: (cx - dy, cy + dx)
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
        // Centrar verticalmente en lugar de horizontalmente (abanico rotado)
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

    /**
     * Dibuja el abanico de cartas del rival de la derecha, rotado 90° en
     * sentido horario.
     *
     * <p>
     * Análogo a {@link #dibujarAbanicoIzquierda} pero con rotación inversa. La
     * transformación aplicada es: layoutX = cx + dy, layoutY = cy - dx,
     * equivalente a rotar el sistema de coordenadas 90° en sentido antihorario.
     * </p>
     *
     * @param zona panel donde se dibuja el abanico derecho
     * @param cartas lista de cartas del rival (solo se usa el tamaño)
     */
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
            // Rotación -90°: (cx + dy, cy - dx)
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

    // =========================================================================
    // GESTIÓN DE HILOS Y CICLO DE VIDA
    // =========================================================================
    /**
     * Devuelve el UID del host de la sala actual leyéndolo de Firebase.
     *
     * @return UID del host sin comillas, o cadena vacía si hay un error
     */
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
     * Registra un hilo de polling externo en la lista de hilos gestionados.
     *
     * <p>
     * Las subclases deben llamar a este método en
     * {@link #registrarListenersPropios()} para cada hilo que creen, de forma
     * que {@link #destruir()} los interrumpa todos al salir de la pantalla de
     * partida. Si no se registran, los hilos seguirían corriendo en background
     * indefinidamente, consumiendo recursos y causando excepciones de
     * NullPointerException al intentar actualizar nodos de escena inactivos.
     * </p>
     *
     * @param hilo hilo de polling a registrar; se ignora si es {@code null}
     */
    protected void registrarHiloListener(Thread hilo) {
        if (hilo != null) {
            hilosListeners.add(hilo);
        }
    }

    /**
     * Carga de forma asíncrona los nombres de todos los jugadores de la sala
     * desde Firebase.
     *
     * <p>
     * Este método se llama desde {@link #init} en un hilo de background para no
     * bloquear el hilo de JavaFX durante la inicialización. El proceso en dos
     * pasos:
     * </p>
     * <ol>
     * <li>Para cada UID, se añade inmediatamente el propio UID como nombre de
     * fallback ({@code putIfAbsent}). Esto evita que los mensajes del narrador
     * muestren "Jugador" durante los primeros segundos antes de que los nombres
     * lleguen.</li>
     * <li>Se consulta Firebase ({@code usuarios/{uid}/nombre}) para obtener el
     * nombre real de cada jugador y sobreescribir el fallback.</li>
     * </ol>
     *
     * <p>
     * El mapa {@link #nombres} es un {@link HashMap} estándar, no sincronizado.
     * Las escrituras ocurren en el hilo de background y las lecturas en el hilo
     * de JavaFX. Esto es seguro en la práctica porque las escrituras de
     * inicialización terminan antes de que cualquier mensaje del narrador
     * necesite los nombres, pero no es formalmente thread-safe. Para una
     * implementación más robusta, se podría usar
     * {@link java.util.concurrent.ConcurrentHashMap}.
     * </p>
     */
    private void precargarNombres() {
        new Thread(() -> {
            try {
                String json = db.leerNodo("salas/" + codigoSala + "/jugadores", idToken);
                if (json == null || "null".equals(json)) {
                    return;
                }
                Map<String, Object> jugadores = new Gson().fromJson(json, Map.class);
                for (String uid : jugadores.keySet()) {
                    // Fallback inmediato: usar el UID hasta que llegue el nombre real
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

    /**
     * Destruye el controlador interrumpiendo todos sus hilos de polling.
     *
     * <p>
     * Debe llamarse antes de navegar fuera de la pantalla de partida para
     * evitar fugas de memoria y errores de "stage is null". Los pasos que
     * realiza:
     * </p>
     * <ol>
     * <li>Establece {@link #controladorDestruido} a {@code true}. Todos los
     * callbacks de listeners comprueban este flag y retornan sin hacer nada si
     * está activo.</li>
     * <li>Interrumpe todos los hilos de la lista {@code hilosListeners}. La
     * interrupción hace que el {@code Thread.sleep()} del bucle de polling
     * lance {@link InterruptedException}, saliendo limpiamente del bucle.</li>
     * <li>Limpia la lista de hilos.</li>
     * </ol>
     *
     * <p>
     * Este método es llamado por {@link PopUpFinalPartidaController} antes de
     * navegar a una nueva partida o de volver a la sala online.
     * </p>
     */
    public void destruir() {
        controladorDestruido = true;
        int n = hilosListeners.size();
        for (Thread hilo : hilosListeners) {
            hilo.interrupt();
        }
        hilosListeners.clear();
        System.out.println("[Base] Controlador destruido. " + n + " listeners parados."); // debug
    }

    /**
     * Prepara el controlador para una navegación inminente estableciendo el
     * flag de destrucción. A diferencia de {@link #destruir()}, no interrumpe
     * los hilos explícitamente (el listener que navega ya está en ejecución y
     * se interrumpirá naturalmente cuando el controlador sea recolectado por el
     * GC).
     *
     * <p>
     * Se llama desde los listeners de {@code volverSala} y {@code nuevaPartida}
     * justo antes de cambiar la escena, para que los demás listeners del mismo
     * controlador no actúen durante la navegación.
     * </p>
     */
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
