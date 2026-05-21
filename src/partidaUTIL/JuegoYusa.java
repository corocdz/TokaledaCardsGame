package partidaUTIL;

import java.util.*;

/**
 * Motor de juego que implementa las reglas del modo <strong>La Yusa</strong>.
 *
 * <p>
 * Implementa la interfaz {@link Juego} y contiene toda la lógica específica del
 * modo Yusa, completamente independiente de Firebase o de la interfaz gráfica.
 * Los controladores ({@link ui.PartidaControllerYusa} y
 * {@link ui.PartidaOfflineYusaController}) delegan aquí las decisiones de juego
 * y aplican los resultados al estado compartido.</p>
 *
 * <h2>Estructura de una ronda</h2>
 * <ol>
 * <li>Repartir 1 carta a cada jugador vivo.</li>
 * <li>¿Alguno tiene un 1 (yusa)? - {@link FaseRonda#YUSA}</li>
 * <li>Si no, ¿alguno tiene un 12? - {@link FaseRonda#DOCE}</li>
 * <li>Si no - {@link FaseRonda#NORMAL}</li>
 * </ol>
 *
 * <h2>Ronda NORMAL</h2>
 * <ul>
 * <li>Cada jugador decide QUEDARSE o PASAR su carta al siguiente.</li>
 * <li>El receptor no puede rechazarla.</li>
 * <li>El ÚLTIMO jugador puede QUEDARSE o ROBAR de la baraja.</li>
 * <li>Si el último roba - volver a comprobar yusa/doce.</li>
 * <li>El jugador con la carta más baja pierde una vida.</li>
 * <li>Empate - ronda extra entre los empatados.</li>
 * </ul>
 *
 * <h2>Ronda DOCE</h2>
 * <ul>
 * <li>Se muestran todas las cartas tal cual, sin intercambios.</li>
 * <li>El jugador con la carta más baja pierde una vida.</li>
 * <li>Empate - ronda extra entre los empatados.</li>
 * </ul>
 *
 * <h2>Ronda YUSA</h2>
 * <ul>
 * <li>Cada poseedor de yusa elige un jugador objetivo y le pregunta el
 * palo.</li>
 * <li>Todos los objetivos eligen palo simultáneamente y se revelan juntos.</li>
 * <li>Si el objetivo acierta el palo - pierde vida el POSEEDOR.</li>
 * <li>Si el objetivo falla el palo - pierde vida el OBJETIVO.</li>
 * </ul>
 *
 * <h2>Reset de baraja</h2>
 * <p>
 * Cuando en el descarte hay {@value #YUSAS_PARA_RESET} o más yusas (cartas con
 * número {@value #NUMERO_YUSA}), al terminar esa ronda se mezclan todas las
 * cartas y se forma un nuevo mazo.</p>
 *
 * <h2>Estado interno</h2>
 * <p>
 * A diferencia de {@link JuegoPescaito}, este motor sí guarda estado en campos
 * de instancia: las vidas de cada jugador ({@link #vidas}) y la lista de
 * jugadores vivos ({@link #jugadoresVivos}). Esto es necesario porque las vidas
 * persisten entre rondas y son la condición de fin de partida.</p>
 *
 * @author Javier Coronilla Castellano
 */
public class JuegoYusa implements Juego {

    // =========================================================================
    //  CONSTANTES
    // =========================================================================
    /**
     * Vidas con las que empieza cada jugador al inicio de la partida. Valor:
     * {@value}. Cuando un jugador llega a 0 vidas es eliminado.
     */
    public static final int VIDAS_INICIALES = 3;

    /**
     * Número de carta que representa la yusa (el comodín del juego). Valor:
     * {@value}. Es el número más bajo de la baraja y activa la
     * {@link FaseRonda#YUSA} cuando algún jugador lo recibe.
     */
    public static final int NUMERO_YUSA = 1;

    /**
     * Número de carta del doce, el más alto de la baraja. Valor: {@value}.
     * Activa la {@link FaseRonda#DOCE} cuando algún jugador lo recibe y ninguno
     * tiene yusa (la yusa tiene prioridad).
     */
    public static final int NUMERO_DOCE = 12;

    /**
     * Número de cartas que se reparten a cada jugador por ronda. Valor:
     * {@value}. En Yusa cada jugador recibe exactamente 1 carta al inicio de
     * cada ronda.
     */
    public static final int CARTAS_POR_RONDA = 1;

    /**
     * Número mínimo de yusas en el descarte para activar el reset de baraja.
     * Valor: {@value}. Cuando hay {@value} o más cartas con número
     * {@value #NUMERO_YUSA} en el descarte al finalizar una ronda,
     * {@link #debeResetearBaraja(List)} devuelve {@code true} y el controlador
     * debe llamar a {@link #resetearBaraja(List, List)}.
     */
    public static final int YUSAS_PARA_RESET = 3;

    // =========================================================================
    //  ENUMERACIONES
    // =========================================================================
    /**
     * Palos posibles de una carta de yusa (número {@value #NUMERO_YUSA}). En la
     * ronda YUSA, el objetivo debe adivinar cuál de estos palos tiene el
     * poseedor de la yusa.
     */
    public enum Palo {
        CORONAS, BALANZAS, DIANAS, CORAZONES
    }

    /**
     * Tipo de ronda que corresponde al estado de las manos tras el reparto. Se
     * determina en {@link #determinarFaseRonda(Map)} con la prioridad YUSA &gt;
     * DOCE &gt; NORMAL.
     */
    public enum FaseRonda {
        /**
         * Algún jugador tiene la carta de yusa (número {@value #NUMERO_YUSA}).
         */
        YUSA,
        /**
         * Algún jugador tiene el 12 y nadie tiene yusa.
         */
        DOCE,
        /**
         * Ningún jugador tiene yusa ni 12. Ronda de intercambios normal.
         */
        NORMAL
    }

    /**
     * Qué debe hacer el controlador en el turno de un jugador durante la ronda
     * normal.
     */
    public enum AccionTurno {
        JUGAR_NORMAL, ROBAR_AUTOMATICO, PASAR_TURNO
    }

    // =========================================================================
    //  ESTADO INTERNO
    // =========================================================================
    /**
     * Mapa de vidas de cada jugador. Clave: UID. Valor: vidas restantes (0–3).
     * Se inicializa en {@link #iniciarPartida} y se actualiza en
     * {@link #perderVida}. Persiste entre rondas: es el estado global de la
     * partida Yusa.
     */
    private final Map<String, Integer> vidas = new HashMap<>();

    /**
     * Lista de jugadores que siguen vivos (vidas &gt; 0). Se inicializa en
     * {@link #iniciarPartida} y se actualiza en {@link #perderVida} cuando un
     * jugador llega a 0 vidas (se elimina de la lista). Se usa en
     * {@link #repartirCartas} para repartir solo a los vivos.
     */
    private final List<String> jugadoresVivos = new ArrayList<>();

    // =========================================================================
    //  UTILIDADES DE CARTA
    // =========================================================================
    /**
     * Extrae el número de una carta a partir de su ruta de recurso.
     *
     * <p>
     * Sobreescribe el método {@code default} de {@link Juego} para garantizar
     * que esta implementación usa exactamente la misma lógica de parseo. El
     * formato esperado es {@code ".../palo_numero.png"}, por ejemplo
     * {@code "/cartas/coronas_7.png"} - {@code 7}.</p>
     *
     * <p>
     * Pasos del parseo:</p>
     * <ol>
     * <li>{@code lastIndexOf("/") + 1}: posición del inicio del nombre de
     * archivo.</li>
     * <li>{@code split("_")}: divide en {@code ["coronas", "7.png"]}.</li>
     * <li>{@code replace(".png", "")}: elimina la extensión - {@code "7"}.</li>
     * <li>{@code parseInt}: convierte a entero.</li>
     * </ol>
     *
     * @param ruta ruta completa del recurso de imagen de la carta
     * @return número entero de la carta (p.ej. 1, 7, 12)
     */
    @Override
    public int obtenerNumeroCarta(String ruta) {
        String nombre = ruta.substring(ruta.lastIndexOf("/") + 1);
        String[] partes = nombre.split("_");
        return Integer.parseInt(partes[1].replace(".png", ""));
    }

    /**
     * Extrae el palo de una carta a partir de su ruta de recurso.
     *
     * <p>
     * El formato esperado es {@code ".../palo_numero.png"}, por ejemplo
     * {@code "/cartas/coronas_1.png"} - {@link Palo#CORONAS}.</p>
     *
     * <p>
     * Pasos del parseo:</p>
     * <ol>
     * <li>Extraer el nombre de archivo desde el último {@code "/"}.</li>
     * <li>{@code split("_")[0]}: tomar el primer fragmento antes del guión
     * bajo, que es el nombre del palo en minúsculas (p.ej.
     * {@code "coronas"}).</li>
     * <li>{@code toUpperCase()}: convertir a mayúsculas para que coincida con
     * el nombre del enum ({@code "CORONAS"}).</li>
     * <li>{@code Palo.valueOf(paloStr)}: convertir la cadena al enum.</li>
     * </ol>
     *
     * @param ruta ruta completa del recurso de imagen de la carta
     * @return el {@link Palo} de la carta
     */
    public Palo obtenerPaloCarta(String ruta) {
        String nombre = ruta.substring(ruta.lastIndexOf("/") + 1);
        // split("_")[0]: tomar solo el fragmento antes del "_" - nombre del palo
        String paloStr = nombre.split("_")[0].toUpperCase();
        return Palo.valueOf(paloStr); // convertir cadena al enum Palo
    }

    // =========================================================================
    //  1. CONFIGURACIÓN INICIAL
    // =========================================================================
    /**
     * Inicializa las vidas de todos los jugadores y prepara las manos vacías.
     *
     * <p>
     * <strong>Importante:</strong> en Yusa este método NO reparte cartas. El
     * reparto lo hace {@link #repartirCartas(Map, List)} al inicio de cada
     * ronda, llamado desde {@code iniciarRondaYusa()} en el controlador. Si se
     * llamara a {@code repartirCartas} aquí, las cartas se repartirían dos
     * veces en la primera ronda.</p>
     *
     * <p>
     * Para cada jugador en el mapa de manos:</p>
     * <ol>
     * <li>Asigna {@link #VIDAS_INICIALES} vidas en el mapa {@link #vidas}.</li>
     * <li>Lo añade a {@link #jugadoresVivos} si no está ya (guarda contra
     * llamadas múltiples).</li>
     * <li>Si su mano es {@code null}, la inicializa a lista vacía.</li>
     * </ol>
     *
     * @param manos mapa UID - lista de cartas (se inicializan las nulls a lista
     * vacía)
     * @param baraja mazo de robo (no se usa en este método en Yusa)
     */
    @Override
    public void iniciarPartida(Map<String, List<String>> manos, List<String> baraja) {
        // En Yusa, iniciarPartida SOLO inicializa vidas y jugadores.
        // El reparto de cartas lo hace repartirCartas() llamado desde
        // iniciarRondaYusa() en el controlador.        
        for (String uid : manos.keySet()) {
            vidas.put(uid, VIDAS_INICIALES); // todas las vidas iniciales
            if (!jugadoresVivos.contains(uid)) {
                jugadoresVivos.add(uid); // evitar duplicados si se llama más de una vez
            }
            if (manos.get(uid) == null) {
                manos.put(uid, new ArrayList<>()); // inicializar mano null a lista vacía
            }
        }
    }

    // =========================================================================
    //  2. REPARTO DE CARTAS
    // =========================================================================
    /**
     * Reparte exactamente {@value #CARTAS_POR_RONDA} carta a cada jugador vivo.
     *
     * <p>
     * Se llama al inicio de cada ronda, no solo al inicio de la partida. Por
     * eso primero limpia las manos de los jugadores vivos
     * ({@code mano.clear()}) para eliminar cualquier carta residual de la ronda
     * anterior antes de repartir.</p>
     *
     * <p>
     * Solo reparte a los jugadores en {@link #jugadoresVivos}, ignorando a los
     * eliminados. Si la baraja se agota antes de completar el reparto, los
     * jugadores restantes se quedan sin carta, situación que el controlador
     * gestiona verificando si hay baraja disponible.</p>
     *
     * <p>
     * {@code baraja.remove(0)}: extrae la carta del top del mazo (índice 0).
     * Por convención, el índice 0 es siempre la carta superior del mazo.</p>
     *
     * @param manos mapa UID - lista de cartas (se modifica: cartas anteriores
     * eliminadas, nueva carta añadida)
     * @param baraja mazo de robo (se modifica: se extrae 1 carta por jugador
     * vivo)
     */
    @Override
    public void repartirCartas(Map<String, List<String>> manos, List<String> baraja) {

        // Limpiar manos antes de cada ronda (solo 1 carta por ronda)
        for (String uid : jugadoresVivos) {
            List<String> mano = manos.get(uid);
            if (mano == null) {
                mano = new ArrayList<>();
                manos.put(uid, mano);
            }
            mano.clear(); // eliminar carta de la ronda anterior
        }

        // Repartir 1 carta a cada jugador vivo
        for (String uid : jugadoresVivos) {
            if (!baraja.isEmpty()) {
                manos.get(uid).add(baraja.remove(0)); // extraer top del mazo y añadir a la mano
            }
        }
    }

    // =========================================================================
    //  3. ROBAR CARTA
    // =========================================================================
    /**
     * En Yusa siempre se puede robar si hay jugadores vivos. La validación de
     * si el jugador concreto puede robar la gestiona el controlador.
     *
     * @param uidJugador UID del jugador (no se usa)
     * @return {@code true} si hay jugadores vivos en la partida
     */
    @Override
    public boolean puedeRobar(String uidJugador) {
        return !jugadoresVivos.isEmpty();
    }

    /**
     * El último jugador de la ronda normal roba la carta superior del mazo,
     * reemplazando la que tenía.
     *
     * <p>
     * {@code mano.clear()}: descarta la carta actual de la mano antes de añadir
     * la nueva. El descarte físico a la pila lo gestiona el controlador (que
     * necesita saber qué carta se descartó para publicarlo en Firebase).</p>
     *
     * <p>
     * Si la baraja está vacía, retorna sin hacer nada. El controlador comprueba
     * previamente si hay baraja disponible antes de llamar a este método.</p>
     *
     * @param uidJugador UID del jugador que roba
     * @param manos mapa UID - lista de cartas (se modifica: carta anterior
     * eliminada, nueva carta añadida)
     * @param baraja mazo de robo (se modifica: se extrae la carta del top)
     */
    @Override
    public void robarCarta(String uidJugador,
            Map<String, List<String>> manos,
            List<String> baraja) {

        if (baraja.isEmpty()) {
            return;
        }

        List<String> mano = manos.get(uidJugador);
        if (mano == null) {
            mano = new ArrayList<>();
            manos.put(uidJugador, mano);
        }

        mano.clear(); // descartar la carta actual (el controlador la mueve al descarte)
        mano.add(baraja.remove(0)); // añadir la carta del top del mazo
    }

    // =========================================================================
    //  4. DESCARTAR CARTA
    // =========================================================================
    /**
     * En Yusa siempre se puede descartar.
     *
     * @param uidJugador UID del jugador (no se usa)
     * @param carta carta a descartar (no se valida aquí)
     * @return siempre {@code true}
     */
    @Override
    public boolean puedeDescartar(String uidJugador, String carta) {
        return true;
    }

    /**
     * Descarta una carta concreta de la mano de un jugador a la pila de
     * descarte.
     *
     * <p>
     * Se usa en dos situaciones:</p>
     * <ul>
     * <li>En la ronda YUSA: los jugadores sin yusa descartan su carta.</li>
     * <li>Cuando el último jugador decide robar: descarta su carta
     * anterior.</li>
     * </ul>
     *
     * <p>
     * {@code mano.remove(carta)}: elimina la primera ocurrencia de la carta en
     * la lista. Como en Yusa cada mano tiene exactamente 1 carta, elimina esa
     * única carta.</p>
     *
     * @param uidJugador UID del jugador que descarta
     * @param carta ruta de imagen de la carta a descartar
     * @param manos mapa UID - lista de cartas (se modifica: se elimina la
     * carta)
     * @param baraja mazo (no se usa en este método)
     * @param descarte pila de descartes (se modifica: se añade la carta)
     */
    @Override
    public void descartarCarta(String uidJugador,
            String carta,
            Map<String, List<String>> manos,
            List<String> baraja,
            List<String> descarte) {
        List<String> mano = manos.get(uidJugador);
        if (mano != null) {
            mano.remove(carta); // eliminar la primera ocurrencia de la carta
        }
        if (descarte != null) {
            descarte.add(carta); // añadir a la pila de descarte
        }
    }

    // =========================================================================
    //  INICIALIZACIÓN DE JUGADORES VIVOS
    // =========================================================================
    /**
     * Inicializa la lista de jugadores vivos con la lista proporcionada.
     *
     * <p>
     * Se usa en dos contextos:</p>
     * <ul>
     * <li>Al inicio de la partida offline, donde el controlador conoce los
     * jugadores antes de llamar a {@link #iniciarPartida}.</li>
     * <li>Al reconectar, donde el controlador reconstruye el estado desde
     * Firebase y necesita sobreescribir la lista de vivos.</li>
     * </ul>
     *
     * <p>
     * {@code jugadoresVivos.clear()} elimina todos los vivos anteriores antes
     * de añadir los nuevos, garantizando que no haya duplicados.</p>
     *
     * @param jugadores lista de UIDs de los jugadores que deben estar vivos
     */
    public void inicializarJugadoresVivos(List<String> jugadores) {
        jugadoresVivos.clear();
        jugadoresVivos.addAll(jugadores);
    }

    // =========================================================================
    //  5. TURNOS
    // =========================================================================
    /**
     * Calcula el UID del siguiente jugador en el orden circular.
     *
     * <p>
     * Busca el índice de {@code turnoActual} en {@code jugadores} y devuelve el
     * siguiente con el operador módulo para circular:
     * {@code (index + 1) % jugadores.size()}.</p>
     *
     * <p>
     * Si {@code turnoActual} no está en la lista (eliminado o error), devuelve
     * el primer elemento como fallback para no bloquear el flujo.</p>
     *
     * @param turnoActual UID del jugador cuyo turno acaba de terminar
     * @param jugadores lista ordenada de UIDs entre los que rotar
     * @return UID del siguiente jugador
     */
    @Override
    public String siguienteTurno(String turnoActual, List<String> jugadores) {
        if (jugadores == null || jugadores.isEmpty()) {
            return turnoActual;
        }
        int index = jugadores.indexOf(turnoActual);
        if (index == -1) {
            return jugadores.get(0); // fallback: no encontrado
        }
        return jugadores.get((index + 1) % jugadores.size()); // rotación circular
    }

    /**
     * En Yusa todos los jugadores vivos participan en cada ronda
     * simultáneamente; no hay un modelo de "turno individual con acción".
     * Siempre devuelve {@link Juego.AccionTurno#JUGAR_NORMAL} porque el flujo
     * de Yusa lo gestiona el sistema de {@code estadoRonda} del controlador, no
     * este método.
     *
     * @param uidJugador UID del jugador (ignorado)
     * @param manos manos actuales (ignorado)
     * @param baraja baraja actual (ignorado)
     * @return siempre {@link Juego.AccionTurno#JUGAR_NORMAL}
     */
    @Override
    public Juego.AccionTurno accionInicioTurno(String uidJugador,
            Map<String, List<String>> manos,
            List<String> baraja) {
        return Juego.AccionTurno.JUGAR_NORMAL;
    }

    /**
     * La partida termina cuando solo queda 1 jugador con vidas o ninguno.
     *
     * <p>
     * Usa un {@code Stream} con {@code filter} y {@code count} para contar
     * cuántos jugadores tienen vidas &gt; 0. La condición {@code <= 1} cubre
     * tanto el caso normal (1 superviviente = ganador) como el caso de empate
     * total (0 supervivientes = empate simultáneo).</p>
     *
     * <p>
     * La comprobación inicial {@code vidas.isEmpty()} evita que la partida se
     * dé por terminada antes de que se inicialicen las vidas.</p>
     *
     * @param manos mapa de manos (no se usa en Yusa para esta comprobación)
     * @param baraja baraja actual (no se usa en Yusa para esta comprobación)
     * @param descarte pila de descartes (no se usa)
     * @return {@code true} si la partida ha terminado
     */
    @Override
    public boolean haTerminado(Map<String, List<String>> manos,
            List<String> baraja, List<String> descarte) {
        if (vidas.isEmpty()) { // vidas aún no inicializadas
            return false;
        }
        // Contar cuántos jugadores tienen al menos 1 vida
        long vivos = vidas.values().stream().filter(v -> v > 0).count();
        return vivos <= 1;
    }

    // =========================================================================
    //  7. PUNTUACIONES
    // =========================================================================
    /**
     * Devuelve las vidas restantes de cada jugador como puntuación. En Yusa, el
     * ganador es el jugador con vidas &gt; 0 al terminar.
     *
     * <p>
     * Devuelve una copia del mapa {@link #vidas} para que el llamante no pueda
     * modificar el estado interno del motor.</p>
     *
     * @param manos mapa de manos (ignorado en Yusa)
     * @return mapa UID - vidas restantes (mayor = mejor)
     */
    @Override
    public Map<String, Integer> calcularPuntuaciones(Map<String, List<String>> manos) {
        return new HashMap<>(vidas);
    }

    // =========================================================================
    //  LÓGICA ESPECÍFICA DE YUSA
    // =========================================================================
    /**
     * Analiza las manos actuales y determina qué tipo de ronda corresponde.
     *
     * <p>
     * La prioridad es: YUSA &gt; DOCE &gt; NORMAL. Si algún jugador tiene la
     * carta de yusa (número {@value #NUMERO_YUSA}), la fase es YUSA
     * independientemente de si otro tiene el 12.</p>
     *
     * <p>
     * El bucle itera sobre los valores del mapa (las listas de cartas).
     * {@code mano.get(0)}: en Yusa cada jugador tiene exactamente 1 carta, por
     * lo que siempre se comprueba el índice 0.</p>
     *
     * <p>
     * Se usan dos flags booleanos ({@code hayYusa} y {@code hayDoce}) en lugar
     * de retornar inmediatamente al encontrar YUSA, porque el bucle también
     * necesita detectar DOCE. Se podría retornar al encontrar YUSA pero la
     * legibilidad sería menor.</p>
     *
     * @param manos manos actuales de todos los jugadores (incluyendo
     * eliminados)
     * @return la {@link FaseRonda} que debe iniciar el controlador
     */
    public FaseRonda determinarFaseRonda(Map<String, List<String>> manos) {

        boolean hayYusa = false;
        boolean hayDoce = false;

        for (List<String> mano : manos.values()) {
            if (mano == null || mano.isEmpty()) {
                continue; // ignorar manos vacías
            }
            // Detectamos que tipo de fase es
            int numero = obtenerNumeroCarta(mano.get(0)); // única carta de cada jugador
            if (numero == NUMERO_YUSA) {
                hayYusa = true;
            }
            if (numero == NUMERO_DOCE) {
                hayDoce = true;
            }
        }

        // Devolver tipo de ronda por boolean
        // Prioridad: YUSA > DOCE > NORMAL
        if (hayYusa) {
            return FaseRonda.YUSA;
        }
        if (hayDoce) {
            return FaseRonda.DOCE;
        }
        return FaseRonda.NORMAL;
    }

    /**
     * Intercambia la carta del jugador actual con la del siguiente en el orden.
     *
     * <p>
     * En la ronda normal, cuando un jugador decide NO quedarse su carta, la
     * pasa al siguiente jugador. El siguiente no puede negarse: recibe la carta
     * del anterior y le da la suya.</p>
     *
     * <p>
     * El intercambio se hace directamente con {@code set(0, carta)}: sustituye
     * el elemento en el índice 0 (la única carta de cada mano en Yusa). Primero
     * se guardan las cartas en variables temporales ({@code cartaActual} y
     * {@code cartaSiguiente}) para no perder ninguna durante el
     * intercambio.</p>
     *
     * <p>
     * Si alguna mano está vacía, el método retorna sin hacer nada. Esto cubre
     * el caso de jugadores eliminados o sin carta por agotamiento de la
     * baraja.</p>
     *
     * @param uidActual UID del jugador que inicia el intercambio (pasa su
     * carta)
     * @param uidSiguiente UID del jugador que recibe la carta (no puede
     * negarse)
     * @param manos mapa UID - lista de cartas (se modifica: se intercambian las
     * cartas)
     */
    public void intercambiarCartas(String uidActual,
            String uidSiguiente,
            Map<String, List<String>> manos) {

        List<String> manoActual = manos.get(uidActual);
        List<String> manoSiguiente = manos.get(uidSiguiente);

        if (manoActual == null || manoActual.isEmpty()) {
            return;
        }
        if (manoSiguiente == null || manoSiguiente.isEmpty()) {
            return;
        }

        // Guardar ambas cartas en temporales antes de intercambiar
        String cartaActual = manoActual.get(0);
        String cartaSiguiente = manoSiguiente.get(0);

        // set(0, x): sustituir el elemento en el índice 0 (única carta en Yusa)
        manoActual.set(0, cartaSiguiente);
        manoSiguiente.set(0, cartaActual);
    }

    /**
     * Determina los perdedores de la ronda: los jugadores con la carta de menor
     * valor entre los participantes.
     *
     * <p>
     * El valor de las cartas es su número directamente (2 es la más baja, 11 la
     * más alta en ronda normal). En caso de empate, devuelve todos los
     * empatados para que el controlador inicie una ronda extra entre ellos.</p>
     *
     * <p>
     * El algoritmo usa dos pasadas sobre la lista:</p>
     * <ol>
     * <li>Primera pasada: encontrar el valor mínimo ({@code Integer.MAX_VALUE}
     * como inicial garantiza que cualquier número de carta lo supere).</li>
     * <li>Segunda pasada: recopilar todos los UIDs cuya carta tiene ese valor
     * mínimo.</li>
     * </ol>
     *
     * <p>
     * El parámetro {@code uids} puede ser un subconjunto de los jugadores
     * totales (p.ej. en una ronda de desempate donde solo participan los
     * empatados).</p>
     *
     * @param manos manos de los jugadores que participan en esta ronda
     * @param uids lista de UIDs que participan (puede ser subconjunto en ronda
     * extra)
     * @return lista de UIDs que pierden (1 si no hay empate, varios si hay
     * empate)
     */
    public List<String> determinarPerdedores(Map<String, List<String>> manos,
            List<String> uids) {

        // Primera pasada: encontrar el valor mínimo entre los participantes
        int minValor = Integer.MAX_VALUE;
        for (String uid : uids) {
            List<String> mano = manos.get(uid);
            if (mano == null || mano.isEmpty()) {
                continue;
            }
            int valor = obtenerNumeroCarta(mano.get(0));
            if (valor < minValor) {
                minValor = valor;
            }
        }

        // Segunda pasada: recopilar todos los UIDs con la carta mínima
        List<String> perdedores = new ArrayList<>();
        for (String uid : uids) {
            List<String> mano = manos.get(uid);
            if (mano == null || mano.isEmpty()) {
                continue;
            }
            if (obtenerNumeroCarta(mano.get(0)) == minValor) {
                perdedores.add(uid);
            }
        }

        return perdedores;
    }

    /**
     * Aplica la pérdida de una vida al jugador indicado y lo elimina si llega a
     * 0.
     *
     * <p>
     * {@code Math.max(0, vidasActuales - 1)}: garantiza que las vidas nunca
     * sean negativas, aunque se llame más veces de las esperadas.</p>
     *
     * <p>
     * Si el jugador llega a 0 vidas, se elimina de {@link #jugadoresVivos} con
     * {@code jugadoresVivos.remove(uid)} y el método devuelve {@code true} para
     * que el controlador sepa que debe mostrar el mensaje de eliminación.</p>
     *
     * @param uid UID del jugador que pierde la vida
     * @return {@code true} si el jugador ha sido eliminado (llegó a 0 vidas)
     */
    public boolean perderVida(String uid) {

        int vidasActuales = vidas.getOrDefault(uid, 0);
        int nuevasVidas = Math.max(0, vidasActuales - 1); // nunca negativo
        vidas.put(uid, nuevasVidas);

        if (nuevasVidas == 0) { // Si vida es == 0 ha perdido y s eelimina
            jugadoresVivos.remove(uid);
            return true; // el jugador ha sido eliminado
        }

        return false; // el jugador sigue vivo
    }

    /**
     * Comprueba si el descarte acumula suficientes yusas para resetear la
     * baraja.
     *
     * <p>
     * Usa un {@code Stream} con {@code filter} y {@code count} para contar
     * cuántas cartas con número {@value #NUMERO_YUSA} hay en el descarte.
     * Cuando llegan a {@value #YUSAS_PARA_RESET} o más, significa que la
     * mayoría de las yusas ya han circulado y la baraja debe renovarse para que
     * el juego no se quede sin cartas útiles.</p>
     *
     * @param descarte pila de descarte actual
     * @return {@code true} si hay {@value #YUSAS_PARA_RESET} o más yusas en el
     * descarte
     */
    public boolean debeResetearBaraja(List<String> descarte) {

        if (descarte == null) {
            return false;
        }

        // Contar cuántas cartas del descarte tienen número NUMERO_YUSA (= 1)
        long yusasEnDescarte = descarte.stream()
                .filter(c -> obtenerNumeroCarta(c) == NUMERO_YUSA)
                .count();

        return yusasEnDescarte >= YUSAS_PARA_RESET;
    }

    /**
     * Resetea la baraja: mueve todas las cartas del descarte al mazo y las
     * baraja.
     *
     * <p>
     * Pasos:</p>
     * <ol>
     * <li>{@code baraja.addAll(descarte)}: añade todas las cartas del descarte
     * al mazo. Si el mazo tenía cartas (pocas, casi agotado), se mezclan con
     * las del descarte.</li>
     * <li>{@code descarte.clear()}: vacía la pila de descarte.</li>
     * <li>{@code Collections.shuffle(baraja)}: baraja aleatoriamente el nuevo
     * mazo.</li>
     * </ol>
     *
     * <p>
     * Debe llamarse al <strong>final de la ronda</strong> en que se detectó el
     * umbral, no al inicio de la siguiente, para que los jugadores puedan ver
     * sus cartas durante la revelación antes del reset.</p>
     *
     * @param baraja mazo de robos actual (se modifica: recibe las cartas del
     * descarte)
     * @param descarte pila de descarte actual (se modifica: queda vacía)
     */
    public void resetearBaraja(List<String> baraja, List<String> descarte) {

        baraja.addAll(descarte); // mover todas las cartas del descarte al mazo
        descarte.clear(); // vaciar el descarte
        Collections.shuffle(baraja); // barajar el nuevo mazo aleatoriamente
    }

    /**
     * Descarta las manos de todos los jugadores al final de la ronda.
     *
     * <p>
     * Itera sobre todas las entradas del mapa de manos (incluyendo eliminados,
     * cuyas manos estarán vacías). Para cada mano no vacía:</p>
     * <ol>
     * <li>{@code descarte.addAll(mano)}: mueve todas las cartas al
     * descarte.</li>
     * <li>{@code mano.clear()}: vacía la mano.</li>
     * </ol>
     *
     * <p>
     * Debe llamarse <strong>siempre</strong> al terminar una ronda, antes de
     * repartir la siguiente, para que el estado de manos sea consistente. La
     * excepción es cuando {@link #resetearBaraja} ya gestiona el descarte, pero
     * en ese caso este método también es seguro (las manos ya estaban
     * vacías).</p>
     *
     * @param manos mapa UID - lista de cartas (se modifica: todas las manos
     * quedan vacías)
     * @param descarte pila de descarte (se modifica: recibe todas las cartas de
     * las manos)
     */
    public void descartarManosAlFinDeRonda(Map<String, List<String>> manos,
            List<String> descarte) {
        for (Map.Entry<String, List<String>> entry : manos.entrySet()) {
            List<String> mano = entry.getValue();
            if (mano != null && !mano.isEmpty()) {
                descarte.addAll(mano); // mover cartas al descarte
                mano.clear(); // vaciar la mano
            }
        }
    }

    // =========================================================================
    //  GETTERS DE ESTADO
    // =========================================================================
    /**
     * Devuelve las vidas actuales de un jugador. {@code getOrDefault(uid, 0)}:
     * devuelve 0 si el UID no existe en el mapa (jugador desconocido o ya
     * eliminado antes de registrarse).
     *
     * @param uid UID del jugador
     * @return vidas restantes (0 si fue eliminado o no existe)
     */
    public int getVidas(String uid) {
        return vidas.getOrDefault(uid, 0);
    }

    /**
     * Devuelve el mapa completo de vidas de todos los jugadores.
     *
     * <p>
     * {@code Collections.unmodifiableMap(vidas)}: devuelve una vista inmutable
     * del mapa interno. El llamante puede leer los valores pero no modificarlos
     * directamente, protegiendo la integridad del estado del motor.</p>
     *
     * @return mapa UID - vidas (inmutable)
     */
    public Map<String, Integer> getTodasLasVidas() {
        return Collections.unmodifiableMap(vidas);
    }

    /**
     * Devuelve la lista de jugadores que siguen vivos.
     *
     * <p>
     * {@code Collections.unmodifiableList(jugadoresVivos)}: vista inmutable de
     * la lista interna. Evita que el llamante modifique la lista directamente;
     * los cambios deben pasar siempre por {@link #perderVida(String)} o
     * {@link #inicializarJugadoresVivos(List)}.</p>
     *
     * @return lista de UIDs de jugadores vivos (inmutable)
     */
    public List<String> getJugadoresVivos() {
        return Collections.unmodifiableList(jugadoresVivos);
    }

    /**
     * Inicializa el estado de vidas desde un mapa leído de Firebase.
     *
     * <p>
     * Se usa en dos situaciones:</p>
     * <ul>
     * <li>Al reconectar: el controlador lee las vidas de Firebase y las carga
     * aquí para que el motor tenga el estado correcto.</li>
     * <li>Al inicio de cada ronda online: el director lee las vidas
     * actualizadas de Firebase para sincronizar el estado local con la fuente
     * de verdad.</li>
     * </ul>
     *
     * <p>
     * Lógica:</p>
     * <ol>
     * <li>{@code vidas.clear()} y {@code jugadoresVivos.clear()}: limpiar el
     * estado anterior.</li>
     * <li>Para cada entrada del mapa: añadir las vidas y, si son &gt; 0, añadir
     * el UID a la lista de vivos.</li>
     * </ol>
     *
     * <p>
     * La comprobación {@code entry.getValue() > 0} garantiza que los jugadores
     * con 0 vidas (eliminados) no se añaden a {@link #jugadoresVivos}.</p>
     *
     * @param vidasBD mapa UID - vidas leído de Firebase
     */
    public void cargarVidasDesdeBD(Map<String, Integer> vidasBD) {
        vidas.clear();
        jugadoresVivos.clear();
        for (Map.Entry<String, Integer> entry : vidasBD.entrySet()) {
            vidas.put(entry.getKey(), entry.getValue());
            if (entry.getValue() > 0) {
                jugadoresVivos.add(entry.getKey()); // solo añadir si tiene vidas
            }
        }
    }
}
