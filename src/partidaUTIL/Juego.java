/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Interface.java to edit this template
 */
package partidaUTIL;

import java.util.List;
import java.util.Map;

/**
 * Interfaz que define el comportamiento básico de un motor de juego de cartas
 * adaptado a los actuales modos de juego disponibles en Tokaleda Cards Game.
 *
 * <p>
 * Todos los modos de juego implementan esta interfaz, lo que permite a
 * {@link ui.PartidaControllerBase} y sus subclases trabajar con cualquier motor
 * de forma polimórfica sin conocer los detalles internos de cada modo. Las
 * implementaciones concretas son:</p>
 * <ul>
 * <li>{@link JuegoPescaito}: implementa las reglas del modo Pescaito.</li>
 * <li>{@link JuegoYusa}: implementa las reglas del modo Yusa.</li>
 * </ul>
 *
 * <h2>Estructura del estado de juego</h2>
 * <p>
 * El estado se representa siempre mediante tres estructuras de datos
 * compartidas entre el controlador y el motor. El motor las recibe como
 * parámetros (nunca las guarda de forma interna permanente para evitar
 * inconsistencias con Firebase):</p>
 * <ul>
 * <li><strong>manos:</strong> {@code Map<String, List<String>>} - mapa de UID
 * de jugador a lista de rutas de imagen de sus cartas (p.ej.
 * {@code "ia_1" - ["/ui/.../coronas_7.png"]}).</li>
 * <li><strong>baraja:</strong> {@code List<String>} - lista ordenada de rutas
 * de imagen del mazo de robo. El índice 0 es la carta del top del mazo.</li>
 * <li><strong>descarte:</strong> {@code List<String>} - pila de cartas
 * descartadas. El último elemento es la carta visible en la cima.</li>
 * </ul>
 *
 * <h2>Rutas de imagen de las cartas</h2>
 * <p>
 * Las cartas se identifican por su ruta de recurso en el classpath, con el
 * formato:</p>
 * <pre>
 * /ui/graphicResources/cartas/{palo}_{numero}.png
 * Ejemplo: /ui/graphicResources/cartas/coronas_9.png
 * </pre>
 * <p>
 * El método {@link #obtenerNumeroCarta(String)} extrae el número de esta ruta.
 * Los palos válidos son: coronas, balanzas, dianas, corazones.</p>
 *
 * <h2>Diseño como interfaz (no clase abstracta)</h2>
 * <p>
 * Se eligió interfaz en lugar de clase abstracta porque:</p>
 * <ul>
 * <li>Los motores de juego no comparten estado ni implementación de métodos
 * (cada modo tiene reglas completamente diferentes).</li>
 * <li>El método {@link #obtenerNumeroCarta(String)} se implementa como
 * {@code default} porque es pura lógica de parseo de cadena, idéntica en todos
 * los modos y sin dependencias de estado.</li>
 * <li>El enum {@link AccionTurno} se anida aquí porque es parte del contrato y
 * todos los modos deben implementar {@link #accionInicioTurno}.</li>
 * </ul>
 *
 * @author Javier Coronilla Castellano.
 *
 */
public interface Juego {

    // =========================================================================
    //  1. CONFIGURACIÓN INICIAL
    // =========================================================================
    /**
     * Configura el estado inicial del motor de juego antes de la primera ronda.
     *
     * <p>
     * En Pescaito, este método puede inicializar estructuras internas del motor
     * (como contadores de pescaitos) y distribuir las cartas iniciales a cada
     * jugador.</p>
     *
     * <p>
     * En Yusa, este método inicializa la lista de jugadores vivos a partir de
     * las claves del mapa {@code manos} (todos los jugadores que participan),
     * pero no reparte cartas: el reparto lo hace {@link #repartirCartas} al
     * inicio de cada ronda. Se llama una sola vez al comenzar la partida.</p>
     *
     * <p>
     * La separación entre {@code iniciarPartida} y {@code repartirCartas}
     * permite que la inicialización global (vidas, estado) ocurra una sola vez,
     * mientras que el reparto por ronda pueda repetirse tantas veces como sea
     * necesario.</p>
     *
     * @param manos mapa UID - lista de cartas de cada jugador (puede estar
     * vacío)
     * @param baraja lista de rutas de imagen del mazo completo, ya barajado
     */
    void iniciarPartida(Map<String, List<String>> manos, List<String> baraja);

    // =========================================================================
    //  2. REPARTO DE CARTAS
    // =========================================================================
    /**
     * Reparte cartas del mazo a los jugadores al inicio de una ronda o partida.
     *
     * <p>
     * En Pescaito: distribuye un número configurable de cartas a cada jugador
     * desde el top del mazo ({@code baraja.remove(0)} repetidamente).</p>
     *
     * <p>
     * En Yusa: reparte exactamente 1 carta a cada jugador vivo. Solo los
     * jugadores que sigan vivos reciben carta; los eliminados se ignoran.</p>
     *
     * <p>
     * El método modifica directamente los parámetros {@code manos} y
     * {@code baraja}, ambos mutables, por lo que el llamante verá los cambios
     * reflejados inmediatamente después de la llamada.</p>
     *
     * @param manos mapa UID - lista de cartas que recibirá las cartas
     * repartidas
     * @param baraja mazo del que se extraen las cartas (se modifica: se
     * eliminan las repartidas)
     */
    void repartirCartas(Map<String, List<String>> manos, List<String> baraja);

    // =========================================================================
    //  3. ROBAR CARTA
    // =========================================================================
    /**
     * Comprueba si un jugador puede robar una carta del mazo en el estado
     * actual.
     *
     * <p>
     * La condición exacta depende del modo:</p>
     * <ul>
     * <li>Pescaito: puede robar si falló una pregunta y la baraja no está
     * vacía.</li>
     * <li>Yusa: la comprobación es interna a la fase; generalmente siempre que
     * haya baraja disponible y la fase así lo permita.</li>
     * </ul>
     *
     * <p>
     * Este método se usa en los controladores para habilitar o deshabilitar
     * visualmente el mazo de robo (el {@code ImageView} del mazo).</p>
     *
     * @param uidJugador UID del jugador que quiere robar
     * @return {@code true} si el jugador puede robar en este momento
     */
    boolean puedeRobar(String uidJugador);

    /**
     * Ejecuta el robo de una carta del top del mazo para el jugador indicado.
     *
     * <p>
     * Extrae la primera carta de {@code baraja} ({@code baraja.remove(0)}) y la
     * añade al final de la mano del jugador
     * ({@code manos.get(uid).add(carta)}). Si el modo lo requiere (p.ej.
     * Pescaito), también puede verificar si se forma un grupo y ejecutar el
     * descarte automático.</p>
     *
     * <p>
     * El llamante es responsable de verificar previamente con
     * {@link #puedeRobar} que la operación es válida.</p>
     *
     * @param uidJugador UID del jugador que roba
     * @param manos mapa UID - lista de cartas (se modifica: se añade la carta
     * robada)
     * @param baraja mazo de robo (se modifica: se elimina la carta del top)
     */
    void robarCarta(String uidJugador, Map<String, List<String>> manos, List<String> baraja);

    /**
     * Comprueba si un jugador puede descartar una carta concreta en el estado
     * actual.
     *
     * <p>
     * En Pescaito, las cartas no se descartan manualmente: se descartan
     * automáticamente al completar un grupo de 4. En Yusa, las cartas se
     * descartan al final de cada ronda.</p>
     *
     * <p>
     * La implementación concreta define qué condiciones deben cumplirse (turno
     * correcto, carta en la mano del jugador, etc.).</p>
     *
     * @param uidJugador UID del jugador que quiere descartar
     * @param carta ruta de imagen de la carta a descartar
     * @return {@code true} si el descarte es válido en el estado actual
     */
    boolean puedeDescartar(String uidJugador, String carta);

    /**
     * Ejecuta el descarte de una carta de la mano del jugador a la pila de
     * descartes.
     *
     * <p>
     * Elimina {@code carta} de {@code manos.get(uidJugador)} y la añade al
     * final de {@code descarte}. Si el modo requiere alguna acción adicional al
     * descartar (p.ej. comprobar condición de fin), también se gestiona
     * aquí.</p>
     *
     * @param uidJugador UID del jugador que descarta
     * @param carta ruta de imagen de la carta a descartar
     * @param manos mapa UID - lista de cartas (se modifica: se elimina la
     * carta)
     * @param baraja mazo de robo (necesario para algunas comprobaciones
     * post-descarte)
     * @param descarte pila de descartes (se modifica: se añade la carta
     * descartada)
     */
    void descartarCarta(String uidJugador, String carta,
            Map<String, List<String>> manos,
            List<String> baraja,
            List<String> descarte);

    // =========================================================================
    //  5. GESTIÓN DE TURNOS
    // =========================================================================
    /**
     * Calcula el UID del siguiente jugador en el orden de turnos.
     *
     * <p>
     * Busca la posición de {@code turnoActual} en la lista {@code jugadores} y
     * devuelve el siguiente usando el operador módulo para circular al inicio:
     * {@code jugadores.get((index + 1) % jugadores.size())}.</p>
     *
     * <p>
     * La lista {@code jugadores} puede ser la lista completa de participantes
     * (Pescaito) o solo los jugadores vivos (Yusa), según lo que pase el
     * llamante. En Yusa, el controlador filtra los vivos antes de llamar a este
     * método.</p>
     *
     * <p>
     * Si {@code turnoActual} no está en la lista (jugador eliminado o error),
     * la implementación debe devolver el primer elemento como fallback para no
     * quedarse bloqueada.</p>
     *
     * @param turnoActual UID del jugador cuyo turno acaba de terminar
     * @param jugadores lista ordenada de UIDs entre los que rotar el turno
     * @return UID del siguiente jugador al que le toca el turno
     */
    String siguienteTurno(String turnoActual, List<String> jugadores);

    // =========================================================================
    //  6. FIN DE PARTIDA
    // =========================================================================
    /**
     * Determina si la partida ha terminado según las reglas del modo.
     *
     * <p>
     * En Pescaito: la partida termina cuando todas las manos están vacías Y la
     * baraja también está vacía, es decir, cuando se han producido todos los
     * pescaitos posibles. Mientras queden cartas en la baraja, los jugadores
     * sin mano pueden robar automáticamente.</p>
     *
     * <p>
     * En Yusa: la partida termina cuando solo queda 1 jugador vivo (con vidas >
     * 0). Las condiciones de manos y baraja son secundarias en Yusa.</p>
     *
     * <p>
     * Se llama al final de cada acción relevante (robo, pregunta, cierre de
     * ronda) para detectar el momento exacto en que la partida termina.</p>
     *
     * @param manos mapa UID - lista de cartas de cada jugador
     * @param baraja mazo de robo actual
     * @param descarte pila de descartes actual
     * @return {@code true} si la partida ha terminado y debe mostrarse el
     * resultado
     */
    boolean haTerminado(Map<String, List<String>> manos, List<String> baraja, List<String> descarte);

    // =========================================================================
    //  7. PUNTUACIONES
    // =========================================================================
    /**
     * Calcula las puntuaciones finales de todos los jugadores al terminar la
     * partida.
     *
     * <p>
     * El significado de la puntuación depende del modo:</p>
     * <ul>
     * <li>Pescaito: número de pescaitos (grupos de 4 completados) por jugador.
     * Gana quien más pescaitos tenga.</li>
     * <li>Yusa: número de vidas restantes por jugador. El último superviviente
     * gana.</li>
     * </ul>
     *
     * <p>
     * Se usa en {@link ui.PartidaControllerBase#obtenerGanador(Map)} para
     * determinar quién ganó la partida y construir los datos del popup
     * final.</p>
     *
     * @param manos mapa UID - lista de cartas final de cada jugador
     * @return mapa UID - puntuación de cada jugador (mayor = mejor)
     */
    Map<String, Integer> calcularPuntuaciones(Map<String, List<String>> manos);

    // =========================================================================
    //  MÉTODO DEFAULT - utilidad compartida
    // =========================================================================
    /**
     * Extrae el número de una carta a partir de su ruta de imagen.
     *
     * <p>
     * Este método se implementa como {@code default} en la interfaz porque:</p>
     * <ol>
     * <li>La lógica es idéntica en todos los modos de juego (la convención de
     * nombres de archivos es la misma para todos).</li>
     * <li>No depende de ningún estado interno del motor, por lo que no necesita
     * acceso a campos de instancia.</li>
     * <li>Evitar duplicar este parseo en cada implementación concreta.</li>
     * </ol>
     *
     * <p>
     * El algoritmo de parseo, paso a paso:</p>
     * <ol>
     * <li>{@code ruta.lastIndexOf("/") + 1}: encuentra la posición del último
     * separador de ruta y avanza 1 para apuntar al inicio del nombre de
     * archivo. {@code lastIndexOf} devuelve -1 si no hay "/", y
     * {@code -1 + 1 = 0} hace que {@code substring(0)} devuelva la cadena
     * completa (fallback correcto).</li>
     * <li>{@code nombre.split("_")}: divide el nombre de archivo por el guión
     * bajo. Para {@code "coronas_7.png"} produce
     * {@code ["coronas", "7.png"]}.</li>
     * <li>{@code partes[1].replace(".png", "")}: elimina la extensión del
     * segundo fragmento, obteniendo {@code "7"}.</li>
     * <li>{@code Integer.parseInt(numeroStr)}: convierte la cadena a
     * entero.</li>
     * </ol>
     *
     * <p>
     * <strong>Ejemplo:</strong>
     * {@code "/ui/graphicResources/cartas/coronas_7.png"} - {@code 7}</p>
     *
     * <p>
     * <strong>Precondición:</strong> la ruta debe seguir el formato
     * {@code {palo}_{numero}.png}. Si no se cumple, {@code split("_")} puede
     * devolver un array de tamaño 1 y el acceso a {@code partes[1]} lanzará
     * {@code ArrayIndexOutOfBoundsException}.</p>
     *
     * @param ruta ruta completa del recurso de imagen de la carta
     * @return número entero de la carta (p.ej. 7, 12)
     */
    default int obtenerNumeroCarta(String ruta) {
        String nombre = ruta.substring(ruta.lastIndexOf("/") + 1);
        String[] partes = nombre.split("_");
        String numeroStr = partes[1].replace(".png", "");
        return Integer.parseInt(numeroStr);
    }

    // =========================================================================
    //  ENUM AccionTurno
    // =========================================================================
    /**
     * Enum que describe la acción que debe realizar un jugador al inicio de su
     * turno.
     *
     * <p>
     * Este enum permite que {@link #accionInicioTurno(String, Map, List)}
     * comunique de forma explícita y tipada qué debe hacer el controlador, en
     * lugar de usar booleans o cadenas de texto que serían ambiguos.</p>
     *
     * <p>
     * Se usa principalmente en el modo Pescaito online
     * ({@link ui.PartidaControllerPescaito#iniciarTurnoLocal()}) para decidir
     * el flujo al principio de cada turno del jugador local.</p>
     */
    enum AccionTurno {

        /**
         * El jugador tiene cartas en mano y hay rivales disponibles. Puede
         * hacer su acción normal (preguntar en Pescaito, participar en Yusa).
         */
        JUGAR_NORMAL,
        /**
         * La mano del jugador está vacía pero el mazo de robo tiene cartas. El
         * jugador debe robar automáticamente una carta antes de poder actuar.
         * El turno no pasa al siguiente jugador hasta que robe.
         */
        ROBAR_AUTOMATICO,
        /**
         * La mano del jugador está vacía Y el mazo también está vacío. El
         * jugador no puede hacer nada: su turno pasa automáticamente al
         * siguiente.
         */
        PASAR_TURNO
    }

    /**
     * Determina qué acción debe ejecutar el jugador al inicio de su turno.
     *
     * <p>
     * Consulta el estado actual (mano del jugador y baraja) y devuelve una de
     * las tres acciones del enum {@link AccionTurno}:</p>
     * <ul>
     * <li>{@link AccionTurno#JUGAR_NORMAL}: la mano del jugador no está
     * vacía.</li>
     * <li>{@link AccionTurno#ROBAR_AUTOMATICO}: la mano está vacía pero hay
     * baraja.</li>
     * <li>{@link AccionTurno#PASAR_TURNO}: la mano y la baraja están ambas
     * vacías.</li>
     * </ul>
     *
     * <p>
     * El controlador usa este método en lugar de repetir la misma lógica
     * condicional en varios lugares. Centralizar esta decisión en el motor
     * garantiza que todos los modos aplican las mismas reglas de inicio de
     * turno.</p>
     *
     * <p>
     * En Yusa este método no se usa porque los turnos no siguen el modelo de
     * "un jugador actúa y pasa": en Yusa todos los jugadores vivos participan
     * en cada ronda simultáneamente y el flujo lo gestiona el sistema de
     * {@code estadoRonda}.</p>
     *
     * @param uidJugador UID del jugador cuyo turno va a comenzar
     * @param manos mapa UID - lista de cartas actual
     * @param baraja mazo de robo actual
     * @return la acción que debe ejecutar el controlador para este jugador
     */
    AccionTurno accionInicioTurno(String uidJugador,
            Map<String, List<String>> manos,
            List<String> baraja);
}
