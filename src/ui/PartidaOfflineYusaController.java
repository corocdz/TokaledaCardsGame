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
 * Controlador del modo de juego <strong>Yusa Offline</strong>.
 *
 * <p>
 * Extiende {@link PartidaControllerBase} para reutilizar íntegramente el
 * sistema de renderizado visual (abanicos, layout, zonas de jugadores) y el
 * sistema de mensajes del narrador, sin necesidad de reimplementarlos. La
 * diferencia fundamental con el modo online es que <strong>no usa
 * Firebase</strong>: todo el estado de la partida vive en memoria local y los
 * turnos y fases se gestionan directamente desde el controlador.</p>
 *
 * <h2>Arquitectura offline</h2>
 * <p>
 * Para desactivar la integración con Firebase, esta clase sobreescribe con
 * cuerpos vacíos todos los métodos de la base que tocan la base de datos: null {@link #init}, {@link #prepararEstadoInicial}, {@link #registrarListenersComunes},
 * {@link #registrarListenersPropios} y {@link #cargarOrdenJugadoresGlobal}. El
 * punto de entrada es {@link #iniciarOffline(int)}, que inicializa el estado
 * localmente y arranca la primera ronda.</p>
 *
 * <h2>Gestión de fases y turnos</h2>
 * <p>
 * A diferencia del modo online donde el director publica en Firebase y los
 * clientes reaccionan, aquí toda la lógica de orquestación de fases es local y
 * sincrónica (con delays usando {@link PauseTransition} para simular el tiempo
 * de "pensamiento" de las IAs).</p>
 *
 * <h2>Inteligencia Artificial</h2>
 * <p>
 * Las IAs toman decisiones completamente aleatorias:</p>
 * <ul>
 * <li>En ronda normal: 50% probabilidad de cambiar o quedarse.</li>
 * <li>En fase yusa: eligen objetivo y palo al azar entre los disponibles.</li>
 * </ul>
 * <p>
 * Sus acciones se ejecutan con un delay de {@link #DELAY_IA_SEG} segundos a
 * través de {@link #programarAccionIA(String, Runnable)}.</p>
 *
 * <h2>Modo Debug de IA</h2>
 * <p>
 * {@link #MODO_DEBUG_IA} permite avanzar los turnos de IA manualmente con un
 * botón, facilitando la depuración durante el desarrollo.</p>
 *
 * <h2>Localización</h2>
 * <p>
 * Todos los mensajes del narrador se obtienen mediante
 * {@link IdiomaManager#get}, permitiendo mostrar los textos en el idioma
 * configurado por el usuario.</p>
 *
 * @author Javier Coronilla Castellano.
 *
 *
 */
public class PartidaOfflineYusaController extends PartidaControllerBase {

    // =========================================================================
    //  CONSTANTES
    // =========================================================================
    /**
     * UID ficticio del jugador humano en modo offline. Se usa como clave en
     * {@code manos}, {@code nombres} y todas las estructuras de datos en lugar
     * de un UID real de Firebase, que no existe en modo offline.
     */
    private static final String UID_JUGADOR = "jugador";

    /**
     * Prefijo de los UIDs de los jugadores controlados por IA. Patrón:
     * {@code "ia_1"}, {@code "ia_2"}, {@code "ia_3"}. Se usa en
     * {@link #esIA(String)} para distinguir humanos de IAs.
     */
    private static final String PREFIJO_IA = "ia_";

    /**
     * Segundos de espera antes de que una IA ejecute su acción. Simula el
     * tiempo de "reflexión" de la IA, dando tiempo al jugador humano para leer
     * los mensajes del narrador entre acciones. Solo aplica cuando
     * {@link #MODO_DEBUG_IA} es {@code false}.
     */
    private static final double DELAY_IA_SEG = 3.0;

    /**
     * Activa el modo de depuración de IA cuando es {@code true}. En modo debug,
     * la IA no actúa automáticamente: aparece un botón en pantalla que el
     * desarrollador debe pulsar para avanzar cada acción. Esto permite
     * inspeccionar el estado del juego entre acciones de la IA. En producción
     * debe ser {@code false}.
     */
    private static final boolean MODO_DEBUG_IA = false; // cambiar a true para activar modo debug

    // =========================================================================
    //  ESTADO DE PARTIDA
    // =========================================================================
    /**
     * Número de IAs de la partida actual (1–3). Se guarda para poder reiniciar
     * la partida con la misma configuración desde el popup final.
     */
    private int numIAs = 1;

    /**
     * Fase de la ronda actualmente en curso: {@code NORMAL}, {@code DOCE} o
     * {@code YUSA}. Se determina en {@link #iniciarRondaLocal()} analizando las
     * cartas repartidas y puede cambiar en {@link #ejecutarRoboUltimo(String)}
     * si el último jugador roba una carta especial.
     */
    private JuegoYusa.FaseRonda faseRondaActual = null;

    /**
     * Referencia al overlay de desempate que bloquea la pantalla del jugador
     * humano cuando no participa en la mini-ronda de desempate. {@code null}
     * cuando no hay overlay activo.
     */
    private StackPane overlayDesempate = null;

    /**
     * Referencia al botón de depuración que aparece cuando
     * {@link #MODO_DEBUG_IA} es {@code true}. El desarrollador lo pulsa para
     * avanzar el turno de la IA. {@code null} cuando el botón no está visible.
     */
    private Button btnIADebug = null;

    /**
     * Acción pendiente de la IA que se ejecutará cuando el desarrollador pulse
     * {@link #btnIADebug} en modo debug. {@code null} cuando no hay acción
     * pendiente.
     */
    private Runnable accionIAPendiente = null;

    /**
     * Lista ordenada de UIDs que deben tomar su decisión en la ronda NORMAL
     * activa. El índice 0 es siempre el jugador que decide ahora. Se va
     * vaciando conforme cada jugador decide (se elimina con {@code remove(0)}).
     * Cuando está vacía, todos decidieron y se revelan las cartas.
     */
    private final List<String> ordenDecisiones = new ArrayList<>();

    /**
     * Instantánea de las cartas de cada jugador capturada antes de la
     * revelación. Clave: UID del jugador. Valor: ruta de imagen de su carta. Se
     * usa en {@link #resolverFaseNormalODoce()} para determinar el perdedor con
     * el estado exacto del momento de la revelación. En fase YUSA se rellena
     * individualmente en {@link #onElegirObjetivoHumano(String)} y
     * {@link #elegirObjetivoIA(String)}.
     */
    private final Map<String, String> snapshotLocal = new HashMap<>();

    /**
     * Lista de duelos de yusa pendientes de resolver tras la revelación de 5
     * segundos. Cada entrada guarda el contexto completo de un duelo (poseedor,
     * objetivo, palo). Se resuelven todos en {@link #resolverFaseYusa()}.
     */
    private final List<DueloYusa> duelosPendientes = new ArrayList<>();

    /**
     * Cola de poseedores de yusa que deben preguntar en la ronda actual. El
     * índice 0 es quien pregunta ahora. Se baraja aleatoriamente en
     * {@link #iniciarFaseYusa()} para variar el orden cada ronda. Se va
     * vaciando en {@link #publicarSiguientePreguntaYusa()}.
     */
    private final List<String> colaYusas = new ArrayList<>();

    /**
     * Lista de UIDs que participan en la mini-ronda de desempate activa.
     * {@code null} cuando no hay desempate en curso. Se establece en
     * {@link #resolverFaseNormalODoce()} al detectar empate y se limpia en
     * {@link #cerrarRonda()}.
     */
    private List<String> empatadosActuales = null;

    /**
     * {@code true} cuando hay una mini-ronda de desempate activa. Se usa en
     * {@link #iniciarFaseNormal()} para que solo participen los empatados, y en
     * {@link #resolverFaseNormalODoce()} para distinguir si es el primer empate
     * o la resolución del mismo.
     */
    private boolean enDesempate = false;

    // =========================================================================
    //  ESTADO UI
    // =========================================================================
    /**
     * Overlay de texto "ESTÁS ELIMINADO" para el jugador local. Persistente
     * entre rondas. Se añade al {@code overlayFinal} con índice 0 para que el
     * popup final quede por encima. {@code null} si el jugador no ha sido
     * eliminado aún.
     */
    private StackPane overlayEliminado = null;

    /**
     * {@code true} durante los 5 segundos en que se muestran las cartas
     * frontales. Bloquea {@link #actualizarInterfaz()} para evitar que el
     * redibujado sobreescriba las cartas reveladas con las de espalda antes de
     * que terminen los 5 segundos.
     */
    private boolean mostrandoCartas = false;

    /**
     * Countdown del botón del 12 para el jugador humano en modo offline.
     * Análogo al {@code tickDoce} del modo online. Se guarda para poder
     * detenerlo cuando el jugador pulsa el botón o cuando se oculta el panel.
     */
    private PauseTransition tickDoceLocal = null;

    /**
     * Referencia al panel de botones de decisión actualmente visible en
     * pantalla. Puede contener botones de decisión normal, botones de palo o el
     * botón del 12. {@code null} cuando no hay ningún panel visible. Se elimina
     * de {@code rootSala} en {@link #ocultarPanelDecisionLocal()}.
     */
    private javafx.scene.layout.HBox panelDecisionLocal = null;

    // =========================================================================
    //  RECORD DE DUELO
    // =========================================================================
    /**
     * Record inmutable que encapsula el contexto completo de un duelo de yusa.
     *
     * <p>
     * Los records de Java 16+ generan automáticamente constructor, getters null null     ({@code duelo.poseedor()}, {@code duelo.objetivo()}, {@code duelo.paloElegido()}),
     * {@code equals()}, {@code hashCode()} y {@code toString()}.</p>
     *
     * @param poseedor UID del jugador que tiene la yusa y realizó la pregunta.
     * @param objetivo UID del jugador que intentó adivinar el palo.
     * @param paloElegido palo que eligió el objetivo como respuesta.
     */
    private record DueloYusa(String poseedor, String objetivo, JuegoYusa.Palo paloElegido) {

    }

    // =========================================================================
    //  PUNTO DE ENTRADA
    // =========================================================================
    /**
     * Inicializa y arranca una partida de Yusa offline con el número indicado
     * de IAs. Es el único punto de entrada válido para el modo offline.
     *
     * <p>
     * Pasos de inicialización:</p>
     * <ol>
     * <li>Número de IAs al rango válido [1, 3].</li>
     * <li>Rellenar los campos de sesión con valores ficticios (sin
     * Firebase).</li>
     * <li>Construir el mapa de nombres: "jugador" para el humano, "IA 1/2/3"
     * para las IAs.</li>
     * <li>Construir el orden global de jugadores (humano primero, luego
     * IAs).</li>
     * <li>Crear y barajar el mazo completo usando {@link Baraja}.</li>
     * <li>Crear las manos vacías para todos los jugadores.</li>
     * <li>Instanciar el motor {@link JuegoYusa}, iniciar la partida e
     * inicializar la lista de jugadores vivos con todos los participantes.</li>
     * <li>Configurar los eventos de interacción de la mano del jugador.</li>
     * <li>En el hilo de JavaFX ({@code Platform.runLater}): configurar el
     * layout, actualizar la interfaz, mostrar el mensaje de inicio e iniciar la
     * primera ronda.</li>
     * </ol>
     *
     * <p>
     * El último bloque se ejecuta en {@code Platform.runLater} porque necesita
     * que la escena esté completamente construida para poder obtener el
     * {@link Stage} y configurar el layout dinámico.</p>
     *
     * @param n número de IAs con las que jugar (rango [1, 3])
     */
    public void iniciarOffline(int n) {

        // 1 o 3 IAs.
        this.numIAs = Math.max(1, Math.min(3, n));

        // Valores ficticios de sesión: en offline no hay Firebase ni autenticación
        this.uidLocal = UID_JUGADOR;
        this.codigoSala = "offline";
        this.idToken = "";

        // Registrar nombres: el humano se llama "jugador", las IAs "IA 1", "IA 2", etc.
        nombres.put(UID_JUGADOR, "jugador");
        for (int i = 1; i <= numIAs; i++) {
            nombres.put(PREFIJO_IA + i, "IA " + i);
        }

        // Humano índice 0 (abajo)
        ordenJugadoresGlobal.clear();
        ordenJugadoresGlobal.add(UID_JUGADOR);
        for (int i = 1; i <= numIAs; i++) {
            ordenJugadoresGlobal.add(PREFIJO_IA + i);
        }

        // Crear baraja y barajarla
        Baraja b = new Baraja();
        b.barajar();
        for (Carta c : b.getCartasRestantes()) {
            baraja.add(c.getRutaImagen()); // almacenar rutas de imagen, no objetos Carta
        }

        // Crear manos vacías para todos (el motor las rellenará en iniciarPartida)
        for (String uid : ordenJugadoresGlobal) {
            manos.put(uid, new ArrayList<>());
        }

        // Instanciar motor e iniciar partida
        juego = new JuegoYusa();
        juego.iniciarPartida(manos, baraja); // Preparamos motor internamente
        // Inicializar lista de vivos con TODOS los jugadores (aún nadie ha perdido vidas)
        yusa().inicializarJugadoresVivos(new ArrayList<>(ordenJugadoresGlobal));
        repartoInicialHecho = true;  // marcar para que prepararEstadoInicial no reparta de nuevo
        uidTurnoActual = UID_JUGADOR; // el humano siempre comienza el primer turno

        configurarEventosManoJugador(); // eventos de hover y clic sobre las cartas del humano

        // Diferimos al hilo de JAvaFX. Se necesita la escena construida para obtener el Stage
        Platform.runLater(() -> {
            // Igual que pescaito: configurar layout ANTES de actualizar interfaz
            Stage stage = (Stage) zonaAbajo.getScene().getWindow();
            if (stage != null) {
                configurarLayoutEscena(stage); // posicionar zonas correctamente en pantalla
            }
            actualizarInterfaz();
            narrarGlobal(IdiomaManager.get("yusaOffline.global.offline.inicio", numIAs));
            iniciarRondaLocal(); // arrancar primera ronda
        });
    }

    // =========================================================================
    //  SOBREESCRITURAS - desactivar Firebase
    // =========================================================================
    /**
     * Sobreescritura vacía: usar {@link #iniciarOffline(int)} como punto de
     * entrada. Este método no debe llamarse en modo offline.
     */
    @Override
    public void init(String c, String u, String t) {
        /* no usar */ }

    /**
     * Sobreescritura vacía: el estado se inicializa en
     * {@link #iniciarOffline(int)}. No hay Firebase que consultar.
     */
    @Override
    protected void prepararEstadoInicial(Map<String, Object> p) {
    }

    /**
     * Sobreescritura vacía: sin Firebase, no hay listeners comunes que
     * registrar.
     */
    protected void registrarListenersComunes() {
    }

    /**
     * Sobreescritura vacía: sin Firebase, no hay listeners propios que
     * registrar.
     */
    @Override
    protected void registrarListenersPropios() {
    }

    /**
     * Sobreescritura vacía: el orden de jugadores ya se cargó en
     * {@link #iniciarOffline(int)}, no hay Firebase que consultar.
     */
    @Override
    protected void cargarOrdenJugadoresGlobal() {
    }

    /**
     * Finaliza la partida en modo offline, deshabilitando la UI y mostrando el
     * popup. A diferencia de la versión online, no escribe en Firebase. El flag
     * {@link #partidaFinalizada} evita doble ejecución.
     */
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

    /**
     * Muestra un mensaje global directamente en {@link #narradorLabel} sin
     * Firebase. {@code Platform.runLater} garantiza ejecución en el hilo de
     * JavaFX.
     *
     * @param texto texto a mostrar en el narrador
     */
    @Override
    protected void narrarGlobal(String texto) {
        Platform.runLater(() -> narradorLabel.setText(texto));
    }

    /**
     * Muestra un mensaje privado solo si el destinatario es el jugador humano.
     * Los mensajes a las IAs se ignoran.
     *
     * @param uid UID del destinatario
     * @param texto texto del mensaje privado
     */
    @Override
    protected void narrarPrivado(String uid, String texto) {
        if (UID_JUGADOR.equals(uid)) {
            Platform.runLater(() -> narradorLabel.setText(texto));
        }
    }

    /**
     * Sobreescritura que bloquea el redibujado durante los 5 segundos de
     * revelación. Cuando {@link #mostrandoCartas} es {@code true}, el
     * redibujado sobreescribiría las cartas frontales con las de espalda antes
     * de que terminen los 5 segundos.
     */
    @Override
    protected void actualizarInterfaz() {
        if (mostrandoCartas) {
            return; // no redibujar mientras se muestran las cartas frontales
        }
        super.actualizarInterfaz();  // delegar al método de la base
    }

    /**
     * Actualiza las etiquetas de nombre desde el mapa local sin consultar
     * Firebase. Comprueba {@code != null} porque este método puede llamarse
     * antes de que los nodos FXML estén completamente inicializados.
     */
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
    /**
     * Crea el motor de juego Yusa, ignorando el parámetro {@code modo} porque
     * en modo offline el modo siempre es Yusa.
     *
     * @param modo ignorado en esta implementación
     * @return nueva instancia de {@link JuegoYusa}
     */
    @Override
    protected Juego crearJuego(String modo) {
        return new JuegoYusa();
    }

    /**
     * Vacío: en modo offline los turnos se gestionan internamente, no a través
     * del sistema de listeners de Firebase.
     */
    @Override
    protected void onCambioTurno(String t) {
    }

    /**
     * El mazo no se usa en Yusa con clic directo; informa al humano del
     * mecanismo correcto.
     */
    @Override
    protected void onClickMazo() {
        narrarPrivado(UID_JUGADOR, IdiomaManager.get("yusaOffline.privado.avisoUsarBotones"));
    }

    /**
     * Clic en zona de rival: solo actúa en fase YUSA cuando es el turno del
     * humano en la cola de poseedores.
     *
     * <p>
     * La comprobación {@code colaYusas.get(0).equals(UID_JUGADOR)} verifica que
     * es el turno del humano específicamente (no solo que la fase sea YUSA).
     * Esto evita que el humano elija objetivo cuando es otra IA quien
     * pregunta.</p>
     *
     * @param uidRival UID del rival cuya zona fue pulsada
     */
    @Override
    protected void onZonaRivalClick(String uidRival) {
        if (faseRondaActual == JuegoYusa.FaseRonda.YUSA
                && !colaYusas.isEmpty()
                && colaYusas.get(0).equals(UID_JUGADOR)) {  // ← es el turno del humano en la cola
            onElegirObjetivoHumano(uidRival);
        }
    }

    /**
     * No se usa en Yusa: no hay acción directa sobre las cartas propias.
     *
     * @param ruta ignorado
     */
    @Override
    protected void onCartaLocalClick(String ruta) {
    }

    /**
     * Construye los datos del popup de fin de partida desde el estado local. No
     * consulta Firebase. Lee las vidas desde el motor {@link JuegoYusa}.
     *
     * <p>
     * Lógica de resultado:</p>
     * <ul>
     * <li>1 superviviente: gana ese jugador.</li>
     * <li>0 o más de 1: empate. La lista de supervivientes se une con
     * coma.</li>
     * </ul>
     *
     * @return {@link DatosPopUp} con icono, resultado y detalle localizados
     */
    @Override
    protected DatosPopUp construirDatosPopUpFinal() {
        Map<String, Integer> vidas = yusa().getTodasLasVidas();

        // Filtramos jugadores con al menos 1 vida
        List<String> supervivientes = vidas.entrySet().stream()
                .filter(e -> e.getValue() > 0).map(Map.Entry::getKey)
                .collect(Collectors.toList());

        String resultado, detalle;
        String icono;

        // En caso de victoria (lo normal en Yusa)
        if (supervivientes.size() == 1) {

            // Obtenemos el uid del superviviente
            String uid = supervivientes.get(0);
            String nombre = nombres.getOrDefault(uid, "Jugador"); // Obtenemos el nombre
            int v = vidas.get(uid); // y sus vidas

            // Le pasamos la información al DatosPopUp
            resultado = IdiomaManager.get("yusaOffline.popUpFinal.victoria", nombre);
            detalle = IdiomaManager.get("yusaOffline.popUpFinal.detalle", v);
            icono = "/ui/graphicResources/imagenes/imgGanador.png";

        } else { // en empate

            resultado = IdiomaManager.get("yusaOffline.popUpFinal.empate");
            // Collectors.joining(", "): une los nombres de supervivientes con coma
            String lista = supervivientes.stream()
                    .map(u -> nombres.getOrDefault(u, u))
                    .collect(Collectors.joining(", "));

            // mostramos los detalles del empate.
            detalle = IdiomaManager.get("yusaOffline.popUpFinal.detalleEmpate", lista);
            icono = "/ui/graphicResources/imagenes/imgEmpate.png";
        }

        return new DatosPopUp(icono, resultado, detalle);
    }

    // =========================================================================
    //  INICIO DE RONDA
    // =========================================================================
    /**
     * Inicia una nueva ronda de Yusa offline. Punto de entrada del ciclo de
     * ronda.
     *
     * <p>
     * Pasos:</p>
     * <ol>
     * <li>Salir si la partida ya terminó (guarda contra llamadas
     * residuales).</li>
     * <li>Limpiar todo el estado de la ronda anterior.</li>
     * <li>Repartir 1 carta a cada jugador vivo con
     * {@link JuegoYusa#repartirCartas}.</li>
     * <li>Actualizar la interfaz para mostrar las nuevas cartas.</li>
     * <li>Determinar la fase según las cartas repartidas.</li>
     * <li>Narrar el inicio de ronda.</li>
     * <li>Tras 800ms de pausa (para que el jugador lea el mensaje), arrancar la
     * fase.</li>
     * </ol>
     *
     * <p>
     * La pausa de 800ms con {@link PauseTransition} evita que la siguiente
     * acción ocurra instantáneamente tras el mensaje de inicio de ronda, dando
     * tiempo al jugador para procesar qué fase toca.</p>
     */
    private void iniciarRondaLocal() {
        if (partidaFinalizada) {
            return;
        }

        logEstadoYusa("Nueva ronda - Fase: " + faseRondaActual);
        // Resetear estado de la ronda anterior
        faseRondaActual = null;
        ordenDecisiones.clear();
        snapshotLocal.clear();
        duelosPendientes.clear();
        colaYusas.clear();

        yusa().repartirCartas(manos, baraja); // 1 carta a cada jugador vivo
        actualizarInterfaz();

        faseRondaActual = yusa().determinarFaseRonda(manos); // NORMAL, YUSA O DOCE
        narrarGlobal( // Método para mostrar los mensajes por medio de un narrador global para todos
                //y con IdiomaMaanager.get podemos traducir nuestra aplicación con nuestros archivos de properties
                IdiomaManager.get("yusaOffline.global.ronda.nueva", textoFase(faseRondaActual))
        );

        // Pausa breve antes de iniciar la fase
        PauseTransition pausa = new PauseTransition(Duration.millis(800));
        pausa.setOnFinished(ev -> {
            // Dependiendo del tipo de fase, se ejecutará un camino lógico u otro.
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

    /**
     * Convierte el enum {@link JuegoYusa.FaseRonda} a texto localizado para el
     * narrador.
     *
     * @param fase fase de ronda a describir
     * @return cadena localizada de la fase (p.ej. "¡Hay Yusa!")
     */
    private String textoFase(JuegoYusa.FaseRonda fase) {
        return switch (fase) {
            case YUSA ->
                IdiomaManager.get("fase.yusa");
            case DOCE ->
                IdiomaManager.get("fase.doce");
            case NORMAL ->
                IdiomaManager.get("fase.normal");
        };
    }

    /**
     * Método helper que devuelve el motor casteado a {@link JuegoYusa}. Evita
     * repetir {@code (JuegoYusa) juego} en todos los métodos.
     *
     * @return el motor de juego como {@link JuegoYusa}
     */
    private JuegoYusa yusa() {
        return (JuegoYusa) juego;
    }

    // =========================================================================
    //  RONDA NORMAL
    // =========================================================================
    /**
     * Inicia la fase normal: construye el orden de decisiones y procesa la
     * primera.
     *
     * <p>
     * El orden respeta el orden global de la sala, rotado desde
     * {@code uidTurnoActual}. El algoritmo de rotación usa el operador módulo:
     * {@code (index + i) % ordenJugadoresGlobal.size()} para circular al
     * inicio.</p>
     *
     * <p>
     * En desempate, solo participan los empatados ({@link #empatadosActuales}).
     * La condición ternaria selecciona la lista correcta de participantes.</p>
     */
    private void iniciarFaseNormal() {
        ordenDecisiones.clear();

        // Si hay desempate activo, solo participan los empatados
        List<String> participantes = (enDesempate && empatadosActuales != null && !empatadosActuales.isEmpty())
                ? empatadosActuales
                : yusa().getJugadoresVivos().stream()
                        .filter(uid -> ordenJugadoresGlobal.contains(uid)) // solo los que están en el orden global
                        .collect(Collectors.toList());

        // Rotado desde uidTurnoActual
        int index = ordenJugadoresGlobal.indexOf(uidTurnoActual);

        // Rotar la lista para que uidTurnoActual vaya primero
        // (index + i) % size: cicla por todos los jugadores comenzando desde index
        for (int i = 0; i < ordenJugadoresGlobal.size(); i++) {
            String uid = ordenJugadoresGlobal.get((index + i) % ordenJugadoresGlobal.size());
            if (participantes.contains(uid)) {
                ordenDecisiones.add(uid); // Solo añadir los que participan
            }
        }
        procesarSiguienteDecision();

    }

    /**
     * Procesa la decisión del siguiente jugador en {@link #ordenDecisiones}.
     *
     * <p>
     * Si la lista está vacía - todos decidieron - guardar snapshot y revelar.
     * Si el primero es IA - programar su decisión con delay. Si el primero es
     * humano - mostrar botones de decisión.</p>
     *
     * <p>
     * En desempate sin el humano, si por algún error el humano aparece en la
     * lista, se avanza automáticamente sin mostrar botones para evitar que se
     * bloquee la partida.</p>
     *
     * <p>
     * Los botones de decisión usan lambdas que comparan el texto del botón
     * pulsado con la cadena localizada de la opción 2 para determinar la
     * elección del jugador.</p>
     */
    private void procesarSiguienteDecision() {
        if (ordenDecisiones.isEmpty()) {
            // Todos decidieron - revelar
            guardarSnapshotLocal(); // Capturamos estado antes de revelar
            revelarCartas();
            return;
        }

        String actual = ordenDecisiones.get(0);
        boolean esUltimo = ordenDecisiones.size() == 1; // True si es el último jugador en decidir.

        if (esIA(actual)) {
            // IA: esperar DELAY_IA_SEG y luego decidir aleatoriamente
            PauseTransition p = new PauseTransition(Duration.seconds(DELAY_IA_SEG));
            p.setOnFinished(ev -> decisionIA_Normal(actual, esUltimo));
            p.play();
        } else {
            // Comprobamos si como humanos debemos participar
            boolean humanoParticipa = !enDesempate
                    || (empatadosActuales != null && empatadosActuales.contains(UID_JUGADOR));

            if (!humanoParticipa) {
                // El humano no debería estar en ordenDecisiones durante un desempate sin él
                // pero por seguridad, avanzar sin botones
                avanzarDecisionNormal(UID_JUGADOR, false);
                return;
            }
            if (esUltimo) {
                // Último jugador: puede quedarse o cambiar por la baraja
                // narrador privado para mensajes únicos a cada cliente.
                narrarPrivado(
                        UID_JUGADOR,
                        IdiomaManager.get("yusaOffline.privado.ultimo")
                );

                mostrarBotonesDecision(
                        IdiomaManager.get("yusaOffline.privado.ultimo.op1"),
                        IdiomaManager.get("yusaOffline.privado.ultimo.op2"),
                        dec -> {
                            ocultarPanelDecisionLocal();
                            // Si pulsó op2 (cambiar por baraja) - ejecutar robo a baraja
                            if (dec.equals(IdiomaManager.get("yusaOffline.privado.ultimo.op2"))) {
                                ejecutarRoboUltimo(UID_JUGADOR);
                            } else {
                                avanzarDecisionNormal(UID_JUGADOR, false); // se queda con la carta
                            }
                        }
                );

            } else {
                // Jugador normal puede quedarse o cambiar con el siguiente
                narrarPrivado(UID_JUGADOR,
                        IdiomaManager.get("yusaOffline.privado.normal")
                );

                mostrarBotonesDecision(
                        IdiomaManager.get("yusaOffline.privado.normal.op1"),
                        IdiomaManager.get("yusaOffline.privado.normal.op2"),
                        dec -> {
                            ocultarPanelDecisionLocal();
                            // Si pulsó op2 (cambiar) - cambia = true
                            boolean cambia = dec.equals(IdiomaManager.get("yusaOffline.privado.normal.op2"));
                            avanzarDecisionNormal(UID_JUGADOR, cambia);
                        }
                );
            }

        }
    }

    /**
     * La IA toma su decisión de ronda normal (quedarse o cambiar) de forma
     * aleatoria.
     *
     * <p>
     * Usa {@link #programarAccionIA(String, Runnable)} que en modo normal
     * ejecuta la acción tras el delay, y en modo debug muestra un botón para
     * avanzar manualmente.</p>
     *
     * <p>
     * Si es la última: 50% de probabilidad de robar de la baraja. Si es normal:
     * 50% de probabilidad de cambiar con el siguiente.</p>
     *
     * @param uidIA UID de la IA que decide
     * @param esUltimo true si es la última en decidir en esta ronda
     */
    private void decisionIA_Normal(String uidIA, boolean esUltimo) {

        programarAccionIA(
                IdiomaManager.get("yusaOffline.global.ia.decide", nombres.get(uidIA)),
                () -> {
                    boolean cambia = new Random().nextBoolean(); // 50% aleatorio
                    String nomIA = nombres.get(uidIA);

                    if (esUltimo) {
                        boolean roba = new Random().nextBoolean(); // 50% de robar
                        narrarGlobal(
                                IdiomaManager.get(
                                        roba ? "yusaOffline.global.ia.ultimo.op2"
                                                : "yusaOffline.global.ia.ultimo.op1", nomIA
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
                                                : "yusaOffline.global.ia.normal.op1", nomIA
                                )
                        );

                        avanzarDecisionNormal(uidIA, cambia);
                    }
                }
        );
    }

    /**
     * Ejecuta la decisión de un jugador en ronda normal y avanza al siguiente.
     *
     * <p>
     * Si {@code cambia} es true y hay al menos 2 jugadores en la lista,
     * intercambia la carta del jugador con la del siguiente (índice 1) usando
     * {@link JuegoYusa#intercambiarCartas}.</p>
     *
     * <p>
     * {@code ordenDecisiones.remove(0)}: elimina al jugador que acaba de
     * decidir (siempre el primero), avanzando la lista al siguiente.</p>
     *
     * @param uid UID del jugador que acaba de decidir
     * @param cambia {@code true} si el jugador elige cambiar su carta con el
     * siguiente
     */
    private void avanzarDecisionNormal(String uid, boolean cambia) {
        if (cambia && ordenDecisiones.size() > 1) {
            String siguiente = ordenDecisiones.get(1); // índice 1 = el siguiente en decidir
            yusa().intercambiarCartas(uid, siguiente, manos); // intercambio en el motor
            actualizarInterfaz();
        }
        ordenDecisiones.remove(0); // eliminar al jugador actual de la lista de decisiones
        procesarSiguienteDecision(); // procesar siguiente
    }

    /**
     * El último jugador roba una carta de la baraja en lugar de quedarse la
     * suya.
     *
     * <p>
     * Si la baraja está vacía, no puede robar - revelar directamente.</p>
     *
     * <p>
     * {@code computeIfAbsent(uid, k -> new ArrayList<>())}: obtiene la mano si
     * existe, o la crea vacía si no. Más idiomático que comprobar
     * {@code containsKey}.</p>
     *
     * <p>
     * Tras robar, re-evalúa la fase. Si la carta robada es especial (yusa o
     * 12), la fase cambia y se inicia la nueva fase tras 1 segundo de pausa
     * para que el jugador pueda leer el mensaje.</p>
     *
     * @param uid UID del jugador que roba (humano o IA)
     */
    private void ejecutarRoboUltimo(String uid) {

        String nombre = nombres.getOrDefault(uid, "Jugador");

        if (baraja.isEmpty()) {
            narrarGlobal(IdiomaManager.get("yusaOffline.global.ultimo.barajaVacia", nombre));
            guardarSnapshotLocal();
            revelarCartas();
            return;
        }

        // computeIfAbsent: crea la mano si no existe, o devuelve la existente
        List<String> mano = manos.computeIfAbsent(uid, k -> new ArrayList<>());
        if (!mano.isEmpty()) {
            descarte.add(mano.get(0)); // descartar carta actual
            mano.clear();
        }

        mano.add(baraja.remove(0)); // robar priemra carta en el mazo

        narrarGlobal(IdiomaManager.get("yusaOffline.global.ultimo.cambia", nombre));
        actualizarInterfaz();

        // Re-evaluar la fase, ya que al robar una carta de la baraja, podemos robar un 1 o un 12 y debemos valorarlo
        JuegoYusa.FaseRonda nueva = yusa().determinarFaseRonda(manos);

        if (nueva != JuegoYusa.FaseRonda.NORMAL) { // Si es un 1 o un 12 cambiamos la fase de ronda

            faseRondaActual = nueva;
            narrarGlobal(IdiomaManager.get("yusaOffline.global.fase.cambia", textoFase(nueva)));

            // Pausa para leer el mensaje informativo
            PauseTransition p = new PauseTransition(Duration.seconds(1));
            p.setOnFinished(ev -> { // Ejecutamos la nueva ronda que diga la carta nueva
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
    /**
     * Inicia la fase del 12: busca al jugador con el número especial y gestiona
     * su turno.
     *
     * <p>
     * Si el jugador con el 12 es una IA, espera {@link #DELAY_IA_SEG} segundos
     * y lo juega automáticamente. Si es el jugador humano, muestra un botón con
     * countdown de 10 segundos.</p>
     *
     * <p>
     * El countdown del humano usa el mismo patrón que el modo online
     * ({@code int[]}) para permitir la modificación del contador dentro del
     * lambda.</p>
     *
     * <p>
     * Si no se encuentra ningún jugador con el 12 (situación inesperada), se
     * revelan las cartas directamente.</p>
     */
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
            // Situación inesperada: nadie tiene el 12 aunque la fase es DOCE
            guardarSnapshotLocal();
            revelarCartas();
            return;
        }

        final String uidFinal = uidDoce; // variable final para capturar en lambda

        if (esIA(uidDoce)) {
            // IA con el 12: lo juega automáticamente tras el delay
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
            // Humano con el 12: botón con countdown de 10 segundos
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
                seg[0]--; // decrementar el contador (modifica el contenido del array)
                btn.setText(IdiomaManager.get("yusaOffline.privado.doce.boton", seg[0]));
                if (seg[0] > 0) {
                    tickDoceLocal.playFromStart();
                } else {
                    jugar.run();
                }
            });
            Animaciones.animarBoton(btn);
            btn.setOnAction(e -> jugar.run());
            panelDecisionLocal = new javafx.scene.layout.HBox(btn);
            panelDecisionLocal.setAlignment(javafx.geometry.Pos.CENTER);
            javafx.scene.layout.StackPane.setAlignment(panelDecisionLocal, javafx.geometry.Pos.BOTTOM_CENTER);
            panelDecisionLocal.setTranslateY(180);
            rootSala.getChildren().add(panelDecisionLocal);
            tickDoceLocal.play();
            narrarPrivado(
                    UID_JUGADOR,
                    IdiomaManager.get("yusaOffline.privado.doce.tienes", 10)
            );
        }
    }

    // =========================================================================
    //  RONDA YUSA
    // =========================================================================
    /**
     * Inicia la fase yusa: construye la cola de poseedores y arranca la primera
     * pregunta.
     *
     * <p>
     * {@code Collections.shuffle(colaYusas)}: baraja la cola aleatoriamente
     * para que el orden en que preguntan los poseedores varíe cada ronda.</p>
     *
     * <p>
     * Solo añade a la cola los jugadores cuya primera carta en mano tenga el
     * número especial de yusa ({@link JuegoYusa#NUMERO_YUSA}).</p>
     */
    private void iniciarFaseYusa() {
        colaYusas.clear();
        for (String uid : yusa().getJugadoresVivos()) {
            List<String> m = manos.get(uid);
            if (m != null && !m.isEmpty() && yusa().obtenerNumeroCarta(m.get(0)) == JuegoYusa.NUMERO_YUSA) {
                colaYusas.add(uid); // tiene la carta de yusa
            }
        }
        Collections.shuffle(colaYusas); // orden aleatorio

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

    /**
     * Procesa la siguiente pregunta de yusa de la cola.
     *
     * <p>
     * Salta poseedores eliminados con el bucle {@code while}: un jugador puede
     * ser eliminado por una yusa anterior en la misma ronda antes de que llegue
     * su turno de preguntar.</p>
     *
     * <p>
     * Si la cola está vacía tras el saneamiento, todas las yusas se
     * respondieron (o todos los poseedores fueron eliminados) - guardar
     * snapshot y revelar.</p>
     *
     * <p>
     * Si el poseedor es una IA, se programa su turno con delay. Si es el
     * humano, solo se narra el mensaje (el humano actuará pulsando la zona de
     * un rival, lo que dispara {@link #onZonaRivalClick(String)}).</p>
     */
    private void publicarSiguientePreguntaYusa() {
        // Saltar eliminados
        while (!colaYusas.isEmpty() && !yusa().getJugadoresVivos().contains(colaYusas.get(0))) {
            colaYusas.remove(0);
        }

        if (colaYusas.isEmpty()) {
            // Todas las yusas respondidas - revelar
            guardarSnapshotLocal();
            revelarCartas();
            return;
        }

        String poseedor = colaYusas.get(0); // el primero de la cola pregunta ahora
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
            // El humano hará clic en la zona del rival - onZonaRivalClick - onElegirObjetivoHumano
        }
    }

    /**
     * El jugador humano elige a qué rival preguntar pulsando su zona.
     *
     * <p>
     * Validaciones:</p>
     * <ol>
     * <li>Verificar que es realmente el turno del humano en la cola.</li>
     * <li>No puede elegirse a sí mismo.</li>
     * <li>El rival elegido debe tener carta (no puede ser un eliminado).</li>
     * </ol>
     *
     * <p>
     * Tras validar, guarda el snapshot de la carta del humano (para la
     * resolución posterior) y avanza la cola. Si el objetivo es una IA, esta
     * elige el palo aleatoriamente tras un delay. Si el objetivo es el humano
     * mismo (imposible en este flujo, pero por seguridad), se llama a
     * {@link #pedirPaloAlHumano}.</p>
     *
     * @param uidObjetivo UID del rival seleccionado como objetivo
     */
    private void onElegirObjetivoHumano(String uidObjetivo) {
        // Verificar que sigue siendo el turno del humano en la cola
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

        colaYusas.remove(0); // Avanzar la cola

        // El objetivo elige palo
        if (esIA(uidObjetivo)) {
            PauseTransition p = new PauseTransition(Duration.seconds(DELAY_IA_SEG));
            p.setOnFinished(ev -> {
                // palosArray()[new Random().nextInt(4)]: índice aleatorio entre 0 y 3
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
            // El objetivo es el jugador humano - pedir que elija palo
            pedirPaloAlHumano(UID_JUGADOR, uidObjetivo);
        }
    }

    /**
     * Una IA con yusa elige objetivo aleatorio y gestiona la respuesta del
     * objetivo.
     *
     * <p>
     * Usa {@link #programarAccionIA(String, Runnable)} para respetar el delay
     * (o el modo debug). Dentro del Runnable:</p>
     * <ol>
     * <li>Filtra candidatos: jugadores vivos con carta, distintos del
     * poseedor.</li>
     * <li>Elige uno al azar con
     * {@code new Random().nextInt(candidatos.size())}.</li>
     * <li>Guarda el snapshot de la carta del poseedor.</li>
     * <li>Avanza la cola.</li>
     * <li>Si el objetivo es IA: elige palo aleatorio tras delay. Si es humano:
     * pedir palo al humano con botones.</li>
     * </ol>
     *
     * <p>
     * Si no hay candidatos disponibles, la IA pasa sin preguntar y se avanza la
     * cola.</p>
     *
     * @param poseedor UID de la IA que pregunta
     */
    private void elegirObjetivoIA(String poseedor) {
        programarAccionIA(
                IdiomaManager.get("yusaOffline.global.ia.eligeObjetivo", nombres.get(poseedor)),
                () -> {
                    // Filtrar candidatos: vivos, con carta y distintos del poseedor
                    List<String> candidatos = yusa().getJugadoresVivos().stream()
                            .filter(u -> !u.equals(poseedor))
                            .filter(u -> {
                                List<String> m = manos.get(u);
                                return m != null && !m.isEmpty(); // solo los que tienen carta
                            })
                            .collect(Collectors.toList());

                    if (candidatos.isEmpty()) {
                        narrarGlobal(
                                IdiomaManager.get(
                                        "yusaOffline.global.ia.sinCandidatos",
                                        nombres.get(poseedor)
                                )
                        );
                        colaYusas.remove(0); // avanzar aunque no haya candidatos
                        publicarSiguientePreguntaYusa();
                        return;
                    }

                    // Elegir objetivo aleatorio entre los candidatos válidos
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
                        // Ambos son IA: el objetivo elige palo aleatorio tras delay
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
                        // El objetivo es el humano - pedirle palo
                        pedirPaloAlHumano(poseedor, UID_JUGADOR);
                    }
                });
    }

    /**
     * Muestra los cuatro botones de palo al jugador humano cuando es el
     * objetivo de una yusa.
     *
     * <p>
     * Al pulsar un botón, oculta el panel, narra la respuesta, guarda el duelo
     * en {@link #duelosPendientes} y avanza a la siguiente pregunta de la
     * cola.</p>
     *
     * @param poseedor UID del jugador que tiene la yusa
     * @param objetivo UID del jugador que debe adivinar el palo (el humano)
     */
    private void pedirPaloAlHumano(String poseedor, String objetivo) {
        narrarPrivado(
                UID_JUGADOR,
                IdiomaManager.get(
                        "yusaOffline.privado.pedirPalo",
                        nombres.getOrDefault(poseedor, "tu rival")
                )
        );
        // Consumer<JuegoYusa.Palo>: callback que recibe el palo elegido
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

    /**
     * Devuelve todos los valores del enum {@link JuegoYusa.Palo} como array.
     * Método auxiliar para acceder a los palos de forma indexada en la lógica
     * aleatoria.
     *
     * @return array con todos los palos del juego
     */
    private JuegoYusa.Palo[] palosArray() {
        return JuegoYusa.Palo.values();
    }

    // =========================================================================
    //  REVELAR CARTAS
    // =========================================================================
    /**
     * Guarda el snapshot de las cartas actuales para la resolución posterior.
     *
     * <p>
     * Solo se ejecuta para las fases NORMAL y DOCE. En la fase YUSA los
     * snapshots ya se guardaron individualmente en
     * {@link #onElegirObjetivoHumano(String)} y
     * {@link #elegirObjetivoIA(String)} (snapshot del poseedor), por lo que
     * llamar a este método en fase YUSA sobreescribiría snapshots ya guardados
     * con manos potencialmente vacías.</p>
     */
    private void guardarSnapshotLocal() {

        if (faseRondaActual != JuegoYusa.FaseRonda.YUSA) {
            snapshotLocal.clear();
            for (Map.Entry<String, List<String>> e : manos.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    snapshotLocal.put(e.getKey(), e.getValue().get(0));
                }
            }
        }
        // En fase YUSA no hacer nada: los snapshots ya están guardados individualmente
    }

    /**
     * Inicia la secuencia de revelación de cartas: muestra las cartas 5
     * segundos y luego resuelve la ronda.
     *
     * <p>
     * Establece {@link #mostrandoCartas} = {@code true} para bloquear el
     * redibujado, muestra las cartas frontales, espera 5 segundos con
     * {@link PauseTransition}, y al terminar desbloquea el redibujado y llama a
     * {@link #resolverRondaLocal()}.</p>
     */
    private void revelarCartas() {

        logEstadoYusa("Revelando cartas - Fase: " + faseRondaActual);

        mostrandoCartas = true; // bloquear actualizarInterfaz() durante la revelación
        mostrarCartasDeRondaLocal();  // dibujar cartas frontales en las zonas
        System.out.println("[YUSA-OFF] Cartas mostradas. Pausa 5s...");

        PauseTransition p = new PauseTransition(Duration.seconds(5));
        p.setOnFinished(ev -> {
            mostrandoCartas = false; // desbloquear
            actualizarInterfaz(); // redibujar con cartas de espalda normales
            resolverRondaLocal(); // calcular quién pierde vida
        });
        p.play();
    }

    /**
     * Dibuja las cartas frontales de todos los jugadores vivos en sus zonas.
     *
     * <p>
     * Usa un {@link LinkedHashMap} para garantizar el orden de iteración
     * (abajo, arriba, izquierda, derecha). Para cada jugador vivo con carta,
     * limpia su zona y posiciona un {@link ImageView} con la carta frontal.</p>
     *
     * <p>
     * La posición y rotación varían según la zona:</p>
     * <ul>
     * <li>Abajo: 0° (orientación normal para el jugador local).</li>
     * <li>Arriba: 180° (invertida para que sea legible desde esa
     * posición).</li>
     * <li>Izquierda: 90° (girada para el rival lateral izquierdo).</li>
     * <li>Derecha: -90° (girada para el rival lateral derecho).</li>
     * </ul>
     *
     * <p>
     * {@code Math.max(zona.getWidth(), zona.getPrefWidth())}: cobertura para
     * cuando la zona aún no tiene dimensiones reales (antes del primer layout
     * pass de JavaFX).</p>
     */
    private void mostrarCartasDeRondaLocal() {
        colocarJugadores(); // posicionar jugadores en sus zonas (sin cartas aún)
        // LinkedHashMap: garantiza el orden de iteración (abajo primero)
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
            if (!yusa().getJugadoresVivos().contains(uid)) { // saltar eliminados
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
            // Posicionar y rotar según la zona
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
    /**
     * Delega la resolución de la ronda al método correspondiente según la fase.
     *
     * <p>
     * Las fases DOCE y NORMAL usan la misma lógica de resolución (quien tiene
     * la carta más baja pierde vida), por eso se agrupan en el mismo caso.</p>
     */
    private void resolverRondaLocal() {
        switch (faseRondaActual) {
            case YUSA ->
                resolverFaseYusa();
            case DOCE, NORMAL ->
                resolverFaseNormalODoce();
        }
    }

    /**
     * Resuelve todos los duelos de yusa pendientes tras la revelación.
     *
     * <p>
     * Para cada {@link DueloYusa} en {@link #duelosPendientes}:</p>
     * <ol>
     * <li>Obtener la carta del poseedor del snapshot (guardado en
     * {@link #onElegirObjetivoHumano} o {@link #elegirObjetivoIA}). Fallback:
     * mano actual.</li>
     * <li>Determinar el palo real con {@link JuegoYusa#obtenerPaloCarta}.</li>
     * <li>Comparar con {@code duelo.paloElegido()}: si acertó (paloReal ==
     * paloElegido) pierde el poseedor; si falló pierde el objetivo.</li>
     * <li>Aplicar pérdida de vida, narrar y mostrar overlay si fue eliminado el
     * local.</li>
     * </ol>
     *
     * <p>
     * Tras resolver todos los duelos, cierra la ronda con
     * {@link #cerrarRonda()}.</p>
     */
    private void resolverFaseYusa() {
        for (DueloYusa duelo : duelosPendientes) {
            String cartaPoseedor = snapshotLocal.get(duelo.poseedor());

            // Fallback: intentar obtener de la mano actual si el snapshot no la tiene
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
            // Comparación de enums: == es seguro porque los enums son singletons
            boolean acerto = paloReal == duelo.paloElegido();
            // Si acertó: poseedor pierde (su yusa fue descubierta)
            // Si falló: objetivo pierde (no adivinó el palo)
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

    /**
     * Resuelve la fase NORMAL o DOCE: determina quién tiene la carta más baja y
     * pierde vida.
     *
     * <p>
     * Reconstruye el mapa de manos para la resolución usando el snapshot
     * (estado del momento de la revelación) o las manos actuales como fallback.
     * {@code List.of(e.getValue())}: crea una lista inmutable de 1
     * elemento.</p>
     *
     * <h3>Caso empate (más de 1 perdedor)</h3>
     * <p>
     * Se inicia una mini-ronda de desempate: establece
     * {@link #empatadosActuales} y {@link #enDesempate}, y llama a
     * {@link #iniciarMiniRondaDesempateLocal()} tras 1 segundo de pausa para
     * que el jugador lea el mensaje.</p>
     *
     * <h3>Caso sin empate</h3>
     * <p>
     * El jugador con la carta más baja pierde 1 vida. Se verifica si fue
     * eliminado. Finalmente se cierra la ronda con {@link #cerrarRonda()}.</p>
     */
    private void resolverFaseNormalODoce() {
        Map<String, List<String>> mr = new HashMap<>();
        if (!snapshotLocal.isEmpty()) {
            // Usar snapshot: estado exacto del momento de la revelación
            for (Map.Entry<String, String> e : snapshotLocal.entrySet()) {
                mr.put(e.getKey(), List.of(e.getValue())); // lista inmutable de 1 elemento
            }
        } else {
            // Fallback: manos actuales
            for (Map.Entry<String, List<String>> e : manos.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    mr.put(e.getKey(), new ArrayList<>(e.getValue()));
                }
            }
        }

        // Participantes: empatados en desempate, todos los vivos en ronda normal
        List<String> participantes = (empatadosActuales != null && !empatadosActuales.isEmpty())
                ? new ArrayList<>(empatadosActuales)
                : new ArrayList<>(yusa().getJugadoresVivos());

        List<String> perdedores = yusa().determinarPerdedores(mr, participantes);

        if (perdedores.size() > 1) {
            // Empate - mini-ronda
            String ne = perdedores.stream().map(u -> nombres.getOrDefault(u, u)).collect(Collectors.joining(", "));
            narrarGlobal(
                    IdiomaManager.get("yusaOffline.global.desempate.empate", ne)
            );
            empatadosActuales = new ArrayList<>(perdedores);
            enDesempate = true;
            // Pausa de 1s para que el jugador lea el mensaje antes de iniciar el desempate
            PauseTransition p = new PauseTransition(Duration.seconds(1));
            p.setOnFinished(ev -> iniciarMiniRondaDesempateLocal());
            p.play();
            return;
        }

        // Limpiar estado de desempate si lo había
        if (enDesempate) {
            enDesempate = false;
            empatadosActuales = null;
        }

        if (perdedores.size() == 1) {
            String uid = perdedores.get(0);
            boolean eli = yusa().perderVida(uid); // true si llegó a 0 vidas
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

    /**
     * Inicia la mini-ronda de desempate entre los jugadores empatados.
     *
     * <p>
     * Resetea el estado de ronda SIN modificar {@link #empatadosActuales} ni
     * {@link #enDesempate}, que deben mantenerse activos durante todo el
     * desempate.</p>
     *
     * <p>
     * Solo reparte 1 carta a los empatados. Los demás jugadores vivos reciben
     * su mano limpiada ({@code m.clear()}) para que no interfieran en la
     * determinación de la fase (que solo debe analizar las cartas de los
     * empatados).</p>
     *
     * <p>
     * Si el humano no está en los empatados y no tiene overlay de eliminado, se
     * muestra el overlay de espectador del desempate. Esto bloquea visualmente
     * la pantalla del espectador mientras el desempate se resuelve.</p>
     *
     * <p>
     * La condición {@code overlayEliminado != null} comprueba si el humano ya
     * fue eliminado previamente, en cuyo caso el overlay de eliminado tiene
     * prioridad y no se muestra el de desempate encima.</p>
     */
    private void iniciarMiniRondaDesempateLocal() {
        // Resetear estado de ronda SIN tocar empatadosActuales ni enDesempate
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
                    m.clear(); // mano vacía = no participa en el desempate
                }
            }
        }

        // Construir subconjunto de manos solo con los empatados para determinar la fase
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

        // Mostrar overlay de espectador solo si el humano no participa y no está ya eliminado
        boolean humanoEliminado = overlayEliminado != null; // ya tiene el overlay de eliminado
        if (!empatadosActuales.contains(UID_JUGADOR) && !humanoEliminado) {
            mostrarOverlayDesempateLocal(ne);
        }

        // Pausa de 800ms antes de iniciar la fase del desempate
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

    /**
     * Muestra un overlay semitransparente que informa al humano de que está en
     * espectador. Se añade al root de la escena y se vincula a su tamaño con
     * {@code bind()}.
     *
     * @param nombresEmpatados cadena con los nombres de los empatados,
     * separados por " vs "
     */
    private void mostrarOverlayDesempateLocal(String nombresEmpatados) {
        ocultarOverlayDesempateLocal(); // limpiar overlay anterior si existía
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

        // Añadir al root de la escena para cubrir toda la pantalla
        javafx.scene.Scene scene = zonaAbajo.getScene();
        if (scene != null && scene.getRoot() instanceof Pane root) {
            root.getChildren().add(overlayDesempate);
            // bind(): vinculación dinámica al tamaño del contenedor raíz
            overlayDesempate.prefWidthProperty().bind(root.widthProperty());
            overlayDesempate.prefHeightProperty().bind(root.heightProperty());
        }
    }

    /**
     * Elimina el overlay de desempate de la escena y limpia la referencia.
     */
    private void ocultarOverlayDesempateLocal() {
        if (overlayDesempate != null) {
            // instanceof Pane pane: pattern matching para acceder a getChildren()
            javafx.scene.Parent p = overlayDesempate.getParent();
            if (p instanceof Pane pane) {
                pane.getChildren().remove(overlayDesempate);
            }
            overlayDesempate = null;
        }
    }

    /**
     * Cierra el ciclo de ronda: actualiza vidas, descarta manos, rota el
     * director y arranca la siguiente ronda.
     *
     * <p>
     * Pasos en orden:</p>
     * <ol>
     * <li>Ocultar overlay de desempate si estaba visible.</li>
     * <li>Limpiar estado de desempate.</li>
     * <li>Verificar fin de partida: si todas las condiciones se cumplen,
     * finalizar.</li>
     * <li>Descartar todas las manos al descarte.</li>
     * <li>Resetear baraja si es necesario
     * ({@link JuegoYusa#debeResetearBaraja}).</li>
     * <li>Calcular el siguiente director filtrando solo los jugadores vivos.
     * {@code yusa().siguienteTurno()} hace la rotación saltando a los
     * eliminados.</li>
     * <li>Actualizar la interfaz con el nuevo estado.</li>
     * <li>Tras 1 segundo de pausa, iniciar la siguiente ronda.</li>
     * </ol>
     */
    private void cerrarRonda() {

        ocultarOverlayDesempateLocal();

        // Limpiar estado de desempate al cerrar la ronda
        if (enDesempate) {
            enDesempate = false;
            empatadosActuales = null;
        }

        if (yusa().haTerminado(manos, baraja, descarte)) {
            finalizarPartida();
            return;
        }

        yusa().descartarManosAlFinDeRonda(manos, descarte); // mover cartas al descarte

        if (yusa().debeResetearBaraja(descarte)) {
            yusa().resetearBaraja(baraja, descarte);
            narrarGlobal(IdiomaManager.get("yusaOffline.global.ronda.barajar"));
        }

        // Calcular el siguiente director entre los vivos (en el orden global de la sala)
        List<String> vivos = ordenJugadoresGlobal.stream()
                .filter(uid -> yusa().getJugadoresVivos().contains(uid))
                .collect(Collectors.toList());
        uidTurnoActual = yusa().siguienteTurno(uidTurnoActual, vivos); // rotar sin saltar eliminados

        actualizarInterfaz();
        logEstadoYusa("Fin de ronda");
        PauseTransition p = new PauseTransition(Duration.seconds(1));
        p.setOnFinished(ev -> iniciarRondaLocal());
        p.play();
    }

    // =========================================================================
    //  UI - BOTONES DE DECISIÓN
    // =========================================================================
    /**
     * Muestra dos botones de decisión en la parte inferior de la pantalla.
     *
     * <p>
     * Los botones se añaden al {@code rootSala} con {@code TranslateY(-40)},
     * que en JavaFX (eje Y apunta hacia abajo) desplaza el panel 40 píxeles
     * <em>hacia arriba</em> respecto al centro del StackPane.</p>
     *
     * <p>
     * El callback {@code cb} recibe el texto del botón pulsado como argumento,
     * permitiendo al llamante distinguir qué opción eligió el jugador.</p>
     *
     * @param op1 texto del primer botón (opción izquierda)
     * @param op2 texto del segundo botón (opción derecha)
     * @param cb callback que recibe el texto del botón pulsado
     */
    private void mostrarBotonesDecision(String op1, String op2,
            java.util.function.Consumer<String> cb) {
        ocultarPanelDecisionLocal(); // limpiar panel anterior

        Button b1 = new Button(op1), b2 = new Button(op2);
        String est = "-fx-font-size:14px;-fx-padding:8 18;";
        b1.setStyle(est);
        b2.setStyle(est);

        Animaciones.animarBoton(b2);
        Animaciones.animarBoton(b1);

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
        panelDecisionLocal.setTranslateY(180);
        rootSala.getChildren().add(panelDecisionLocal);
    }

    /**
     * Muestra los cuatro botones de palo para que el humano elija en una
     * pregunta de yusa.
     *
     * <p>
     * Cada botón llama al callback con el {@link JuegoYusa.Palo}
     * correspondiente. Los textos de los botones están localizados vía
     * {@link IdiomaManager}.</p>
     *
     * @param cb callback que recibe el palo elegido por el jugador
     */
    private void mostrarBotonesPalo(java.util.function.Consumer<JuegoYusa.Palo> cb) {
        ocultarPanelDecisionLocal();
        String est = "-fx-font-size:13px;-fx-padding:7 14;";

        Button bC = new Button("CORONAS");
        Button bV = new Button("BALANZAS");
        Button bD = new Button("DIANAS");
        Button bCz = new Button("CORAZONES");

        bC.setStyle(est);
        bV.setStyle(est);
        bD.setStyle(est);
        bCz.setStyle(est);

        Animaciones.animarBoton(bCz);
        Animaciones.animarBoton(bV);
        Animaciones.animarBoton(bD);
        Animaciones.animarBoton(bC);

        // Cada botón pasa su Palo correspondiente al callback al ser pulsado
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
        panelDecisionLocal.setTranslateY(180);
        rootSala.getChildren().add(panelDecisionLocal);
    }

    /**
     * Oculta y elimina el panel de decisión actual de la escena. También
     * detiene el tick del countdown del 12 si estaba activo.
     */
    private void ocultarPanelDecisionLocal() {
        if (tickDoceLocal != null) {
            tickDoceLocal.stop();
            tickDoceLocal = null; // limpiar referencia para evitar fugas
        }
        if (panelDecisionLocal != null) {
            rootSala.getChildren().remove(panelDecisionLocal);
            panelDecisionLocal = null;
        }
    }

    // =========================================================================
    //  OVERLAY DE ELIMINADO
    // =========================================================================
    /**
     * Muestra el overlay persistente "ESTÁS ELIMINADO" cuando el jugador local
     * pierde.
     *
     * <p>
     * La comprobación {@code overlayEliminado != null} al inicio evita crear el
     * overlay dos veces si el método se llama más de una vez.</p>
     *
     * <p>
     * El overlay se añade a {@code overlayFinal} con índice 0 (capa más baja),
     * para que el popup final de resultados quede por encima cuando llegue.</p>
     *
     * <p>
     * {@code setPickOnBounds(false)}: el StackPane transparente no intercepta
     * eventos de ratón, permitiendo que el jugador pueda seguir viendo la
     * partida.</p>
     *
     * <p>
     * La animación de pulso ({@link javafx.animation.ScaleTransition}) crece el
     * contenido al 110% y vuelve con {@code setAutoReverse(true)}, en bucle
     * infinito con {@code setCycleCount(Animation.INDEFINITE)}.</p>
     */
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
    /**
     * Determina si un UID corresponde a una IA. Todos los UIDs de IAs siguen el
     * patrón "ia_N".
     *
     * @param uid UID a comprobar
     * @return {@code true} si empieza por {@link #PREFIJO_IA}
     */
    private boolean esIA(String uid) {
        return uid != null && uid.startsWith(PREFIJO_IA);
    }

    /**
     * Reinicia la partida creando un nuevo controlador completamente limpio.
     *
     * <p>
     * En lugar de limpiar el estado del controlador actual (arriesgado por
     * transiciones en curso y listeners pendientes), crea una nueva instancia y
     * carga el FXML de nuevo. Garantiza un estado inicial perfectamente
     * limpio.</p>
     *
     * <p>
     * Se llama desde {@link PopUpFinalPartidaController} al pulsar "Jugar de
     * nuevo".</p>
     */
    public void reiniciarPartida() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/ui/partidaOffline.fxml"));

            PartidaOfflineYusaController nuevoCtrl = new PartidaOfflineYusaController();
            loader.setController(nuevoCtrl);

            Parent root = loader.load();
            nuevoCtrl.iniciarOffline(numIAs);

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
     * Navega al menú offline usando {@link MainApp#cambiarEscena}. Se llama
     * desde {@link PopUpFinalPartidaController} al pulsar "Volver al menú".
     */
    public void volverAlMenu() {
        MainApp.cambiarEscena("menuOffline.fxml", 1200, 1000);
    }

    /**
     * Programa la ejecución de una acción de IA según el modo activo.
     *
     * <p>
     * Modo normal ({@link #MODO_DEBUG_IA} == false): espera
     * {@link #DELAY_IA_SEG} segundos con {@link PauseTransition} y ejecuta la
     * acción automáticamente.</p>
     *
     * <p>
     * Modo debug ({@link #MODO_DEBUG_IA} == true): muestra un botón con el
     * nombre de la acción en la esquina inferior derecha. El desarrollador
     * pulsa el botón para avanzar. Esto permite inspeccionar el estado entre
     * cada acción de la IA.</p>
     *
     * <p>
     * {@code StackPane.setAlignment(btnIADebug, Pos.BOTTOM_RIGHT)} posiciona el
     * botón en la esquina inferior derecha del {@code rootSala}.
     * {@code setTranslateX(-40)} y {@code setTranslateY(-40)} lo desplazan 40px
     * hacia el interior para que no quede pegado al borde.</p>
     *
     * @param nombreAccion nombre descriptivo de la acción (para el botón en
     * modo debug)
     * @param accion {@link Runnable} que ejecuta la lógica de la IA
     */
    private void programarAccionIA(String nombreAccion, Runnable accion) {
        if (!MODO_DEBUG_IA) {
            // Modo normal: delay automático
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

    /**
     * Elimina el botón de debug de la escena si existe.
     */
    private void ocultarBtnIADebug() {
        if (btnIADebug != null) {
            rootSala.getChildren().remove(btnIADebug);
            btnIADebug = null;
        }
    }

    /**
     * Imprime en consola el estado completo de la partida en formato tabulado.
     *
     * <p>
     * Muestra el evento, la fase actual, el turno activo y para cada
     * jugador:</p>
     * <ul>
     * <li>Nombre y marca de turno (◄).</li>
     * <li>Vidas restantes en emojis (❤ por cada vida) o "💀 ELIMINADO".</li>
     * <li>Carta en mano: número, palo y marcas especiales (🃏 si es yusa, ⚡ si
     * es 12).</li>
     * </ul>
     *
     * <p>
     * También muestra el tamaño de la baraja y el descarte.</p>
     *
     * <p>
     * {@code "❤".repeat(vidas)}: Java permite multiplicar strings con
     * {@code repeat()}. Produce tantos emojis de corazón como vidas tenga el
     * jugador.</p>
     *
     * @param evento descripción del evento que motivó el log
     */
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

    // =========================================================================
    //  POPUP FINAL Y CICLO DE VIDA
    // =========================================================================
    /**
     * Muestra el popup de fin de partida en modo offline.
     *
     * <p>
     * A diferencia de la versión online (que usa un hilo de background para
     * leer Firebase), esta versión es síncrona: los datos ya están en memoria.
     * Usa {@link PopUpFinalPartidaController#initOffline} para que el popup no
     * intente acceder a Firebase ni comprobar si el jugador es host.</p>
     *
     * <p>
     * Si la carga del FXML falla, muestra un {@link Label} con el mensaje de
     * fin de partida como fallback para que el jugador siempre vea algo.</p>
     */
    @Override
    protected void mostrarPantallaFinal() {
        DatosPopUp datos = construirDatosPopUpFinal();

        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/ui/popUpFinalPartida.fxml"));
            StackPane popUpPane = loader.load();

            PopUpFinalPartidaController popUpCtrl = loader.getController();

            // initOffline: inicialización sin Firebase, sin host, con referencia al controlador actual
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
            // Fallback: label de texto si el FXML no se puede cargar
            Label fallback = new Label(
                    IdiomaManager.get("yusaOffline.global.final.fallback")
            );
            fallback.setStyle("-fx-text-fill:white;-fx-font-size:24px;");
            overlayFinal.getChildren().clear();
            overlayFinal.getChildren().add(fallback);
            overlayFinal.setVisible(true);
        }
    }

    /**
     * Implementación del hook de reinicio offline de
     * {@link PartidaControllerBase}. Delega en {@link #reiniciarPartida()}.
     */
    @Override
    public void reiniciarPartidaOffline() {
        reiniciarPartida();
    }

    /**
     * Implementación del hook de vuelta al menú offline de
     * {@link PartidaControllerBase}. Delega en {@link #volverAlMenu()}.
     */
    @Override
    public void volverAlMenuOffline() {
        volverAlMenu();
    }

}
