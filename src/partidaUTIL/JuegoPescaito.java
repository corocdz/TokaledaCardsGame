package partidaUTIL;

import java.util.*;

/**
 * Motor de juego que implementa las reglas del modo <strong>Pescaito</strong>.
 *
 * <p>
 * Implementa la interfaz {@link Juego} y contiene toda la lógica específica del
 * modo Pescaito, completamente independiente de Firebase o de la interfaz
 * gráfica. Los controladores ({@link ui.PartidaControllerPescaito} y
 * {@link ui.PartidaOfflinePescaitoController}) delegan aquí las decisiones de
 * juego y aplican los resultados al estado compartido.</p>
 *
 * <h2>Reglas de Pescaito</h2>
 * <ol>
 * <li>Cada jugador empieza con {@value #CARTAS_INICIALES} cartas en mano.</li>
 * <li>En su turno, un jugador elige un número de su mano y pregunta a otro
 * jugador si lo tiene.</li>
 * <li>Si el rival lo tiene - transfiere todas sus cartas de ese número al
 * preguntador, que mantiene el turno.</li>
 * <li>Si el rival no lo tiene - el preguntador debe robar del mazo. Si la carta
 * robada tiene el mismo número que preguntó, "pesca" y mantiene el turno; si
 * no, el turno pasa al siguiente.</li>
 * <li>Cuando un jugador acumula {@value #CARTAS_POR_PESCAITO} cartas del mismo
 * número, forma un "pescaito": las 4 cartas se descartan y el jugador anota 1
 * punto.</li>
 * <li>La partida termina cuando todas las manos están vacías y la baraja
 * también. Gana quien más pescaitos haya formado.</li>
 * </ol>
 *
 * <h2>Diseño stateless del motor</h2>
 * <p>
 * Esta clase no guarda ningún estado propio de la partida en campos de
 * instancia. Todos los datos (manos, baraja, descarte) se pasan como parámetros
 * en cada llamada. Esto permite que el controlador sea el único dueño del
 * estado, facilitando la sincronización con Firebase y evitando inconsistencias
 * entre el motor y la base de datos.</p>
 *
 * @author Javier Coronilla Castellano.
 */
public class JuegoPescaito implements Juego {

    // =========================================================================
    //  CONSTANTES
    // =========================================================================
    /**
     * Número de cartas que se reparten a cada jugador al inicio de la partida.
     * Valor: {@value}. Se usa en {@link #repartirCartas(Map, List)} para saber
     * cuántas cartas extraer del mazo para cada jugador.
     */
    private static final int CARTAS_INICIALES = 5;

    /**
     * Número de cartas del mismo número necesarias para formar un pescaito.
     * Valor: {@value}. Un pescaito solo puede formarse cuando el jugador tiene
     * las 4 cartas (o palos) de un mismo número.
     */
    private static final int CARTAS_POR_PESCAITO = 4;

    /**
     * Agrupa las cartas de una mano por su número, devolviendo un mapa de
     * número - cartas.
     *
     * <p>
     * Se usa en {@link #preguntar} para detectar si, tras recibir cartas del
     * rival, se ha completado un grupo de {@value #CARTAS_POR_PESCAITO} cartas
     * iguales (pescaito).</p>
     *
     * <p>
     * {@code computeIfAbsent(num, k -> new ArrayList<>())}: si la clave
     * {@code num} no existe en el mapa, crea una nueva lista vacía y la
     * inserta. Devuelve la lista (existente o recién creada) a la que se añade
     * la carta.</p>
     *
     * @param mano lista de rutas de imagen de las cartas de un jugador
     * @return mapa número - lista de cartas de ese número en la mano
     */
    private Map<Integer, List<String>> agruparPorNumero(List<String> mano) {
        Map<Integer, List<String>> grupos = new HashMap<>();
        for (String carta : mano) {
            int num = obtenerNumeroCarta(carta);
            // computeIfAbsent: crea la lista si no existe, devuelve la existente si ya hay
            grupos.computeIfAbsent(num, k -> new ArrayList<>()).add(carta);
        }
        return grupos;
    }

    /**
     * Comprueba si una mano contiene al menos una carta del número indicado.
     *
     * <p>
     * Se usa en {@link #preguntar} para validar que el jugador que pregunta
     * realmente tiene el número que va a pedir, ya que las reglas del Pescaito
     * exigen que solo se pueda preguntar por números que tienes disponibles en
     * tu mano.</p>
     *
     * @param mano lista de cartas en las que buscar
     * @param numero número de carta a buscar (p.ej. 7)
     * @return {@code true} si hay al menos una carta con ese número en la mano
     */
    private boolean tieneNumeroEnMano(List<String> mano, int numero) {
        for (String carta : mano) {
            if (obtenerNumeroCarta(carta) == numero) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    //  MÉTODO PRINCIPAL: PREGUNTAR
    // =========================================================================
    /**
     * Ejecuta la acción central del Pescaito: un jugador pregunta a otro si
     * tiene un número concreto.
     *
     * <p>
     * Este método resuelve completa una pregunta y devuelve un mapa con el
     * resultado de la acción. Los controladores usan ese mapa para narrar el
     * resultado, actualizar Firebase y decidir el flujo del turno.</p>
     *
     * <h3>Estructura del mapa devuelto</h3>
     * <ul>
     * <li>{@code "acierto"} (boolean): si el objetivo tenía el número.</li>
     * <li>{@code "cartasRecibidas"} (int): cuántas cartas se transfirieron (0
     * si fallo).</li>
     * <li>{@code "pescaito"} (boolean): si el acierto completó un grupo de
     * 4.</li>
     * <li>{@code "numeroPescaito"} (int): número del pescaito (solo si pescaito
     * = true).</li>
     * <li>{@code "debeRobar"} (boolean): si el preguntador debe robar del
     * mazo.</li>
     * <li>{@code "mantieneTurno"} (boolean): si el preguntador conserva su
     * turno.</li>
     * <li>{@code "mensaje"} (String): mensaje de error o descripción de la
     * acción.</li>
     * </ul>
     *
     * <h3>Flujo del algoritmo</h3>
     * <ol>
     * <li><strong>Inicializar resultado</strong> con todos los valores por
     * defecto (false y 0). Esto garantiza que el mapa siempre tiene todas las
     * claves, aunque el método salga antes de completarse.</li>
     * <li><strong>Validar</strong> que el preguntador tiene el número en su
     * mano. Si no lo tiene, retornar con acierto = false sin modificar las
     * manos.</li>
     * <li><strong>Buscar coincidencias</strong> en la mano del objetivo:
     * recopilar en {@code cartasCoincidentes} todas las cartas del número
     * pedido.</li>
     * <li><strong>Si acierta:</strong>
     * <ul>
     * <li>Eliminar las cartas del objetivo ({@code manoObj.removeAll(...)}) y
     * añadirlas al preguntador ({@code manoPreg.addAll(...)}).</li>
     * <li>Comprobar si ahora el preguntador tiene 4 cartas del mismo número
     * usando {@link #agruparPorNumero}.</li>
     * <li>Si hay pescaito: extraer las 4 cartas de la mano y moverlas al
     * descarte. {@code break} para salir del bucle en cuanto se detecte el
     * primer pescaito (solo puede haber uno por pregunta).</li>
     * </ul>
     * </li>
     * <li><strong>Si falla:</strong> el preguntador deberá robar
     * ({@code debeRobar = true}) y el turno no se debería mantener (salvo que
     * pesque) ({@code mantieneTurno = false}).</li>
     * </ol>
     *
     * <p>
     * <strong>Nota sobre {@code removeAll} y {@code addAll}:</strong> estos
     * métodos modifican las listas directamente (son referencias al mapa
     * {@code manos}), por lo que el estado del mapa queda actualizado
     * inmediatamente. No es necesario volver a insertar las listas en el
     * mapa.</p>
     *
     * @param jugadorQuePregunta UID del jugador que realiza la pregunta
     * @param jugadorObjetivo UID del jugador al que se pregunta
     * @param numeroPreguntado número de carta por el que se pregunta
     * @param manos mapa UID - lista de cartas (se modifica si hay acierto)
     * @param baraja mazo de robo actual (necesario para el resultado)
     * @param descarte pila de descartes (se modifica si hay pescaito)
     * @return mapa con los resultados de la acción
     */
    public Map<String, Object> preguntar(
            String jugadorQuePregunta,
            String jugadorObjetivo,
            int numeroPreguntado,
            Map<String, List<String>> manos,
            List<String> baraja,
            List<String> descarte
    ) {
        // Inicialización del resultado con valores por defecto (todas las claves siempre presentes)
        Map<String, Object> resultado = new HashMap<>();
        resultado.put("acierto", false);
        resultado.put("cartasRecibidas", 0);
        resultado.put("pescaito", false);
        resultado.put("debeRobar", false);
        resultado.put("mantieneTurno", false);

        List<String> manoPreg = manos.get(jugadorQuePregunta);
        List<String> manoObj = manos.get(jugadorObjetivo);

        // Validamos que el jugador que pregunta tiene ese número en su mano
        if (!tieneNumeroEnMano(manoPreg, numeroPreguntado)) {
            resultado.put("mensaje", "No puedes preguntar por un número que no tienes.");
            return resultado;
        }

        // Buscamos las cartas del número en el objetivo
        List<String> cartasCoincidentes = new ArrayList<>();
        for (String carta : manoObj) {
            if (obtenerNumeroCarta(carta) == numeroPreguntado) {
                cartasCoincidentes.add(carta);
            }
        }

        // Si acierta
        if (!cartasCoincidentes.isEmpty()) {
            manoObj.removeAll(cartasCoincidentes); // se eliminan del objetivo
            // Tranferir cartas del objetivo al preguntador
            manoPreg.addAll(cartasCoincidentes);

            resultado.put("acierto", true);
            resultado.put("cartasRecibidas", cartasCoincidentes.size());
            resultado.put("mantieneTurno", true); // acierta - mantiene turno

            // Comprobamos si ahora forma pescaito (4 cartas del mismo número)
            Map<Integer, List<String>> grupos = agruparPorNumero(manoPreg);
            for (Map.Entry<Integer, List<String>> entry : grupos.entrySet()) {
                if (entry.getValue().size() == CARTAS_POR_PESCAITO) {

                    int numeroPescaito = entry.getKey();
                    resultado.put("pescaito", true);
                    resultado.put("numeroPescaito", numeroPescaito);

                    // Extraemos las 4 cartas del pescaito y las movemos al descarte
                    List<String> aDescartar = entry.getValue();
                    manoPreg.removeAll(aDescartar); // eliminamos las 4 cartas

                    // Añadimos al descarte
                    if (descarte != null) {
                        descarte.addAll(aDescartar);
                    }

                    break; // Un pescaito por pregunta solo - salimos del bucle

                }
            }

            return resultado;
        }

        // Si falla, el preguntador debe robar y pierde turno
        resultado.put("debeRobar", true);
        resultado.put("mantieneTurno", false);
        resultado.put("mensaje", "No tenía ese número.");

        return resultado;
    }

    // =========================================================================
    //  1. INICIO DE PARTIDA
    // =========================================================================
    /**
     * Inicializa el motor y reparte las cartas iniciales a cada jugador.
     *
     * <p>
     * Primero garantiza que todos los jugadores tienen una lista de mano
     * (aunque sea vacía). Esto cubre el caso en que el controlador haya creado
     * el mapa de manos con valores {@code null} en lugar de listas vacías. Si
     * el controlador ya creó las listas, el bucle de comprobación no hace
     * nada.</p>
     *
     * <p>
     * Después delega en {@link #repartirCartas(Map, List)} para distribuir
     * {@value #CARTAS_INICIALES} cartas a cada jugador.</p>
     *
     * @param manos mapa UID - lista de cartas (se inicializa y rellena)
     * @param baraja mazo del que se extraen las cartas iniciales
     */
    @Override
    public void iniciarPartida(Map<String, List<String>> manos, List<String> baraja) {
        // Asegurarse de que cada jugador tiene una lista (aunque esté vacía).
        // Si el controlador ya las creó, este bucle no hace nada.
        // Si no las creó, las crea aquí para que repartirCartas() funcione.
        for (String uid : manos.keySet()) {
            if (manos.get(uid) == null) {
                manos.put(uid, new ArrayList<>());
            }
        }

        // Delegamos el reparto al método reutilizable
        repartirCartas(manos, baraja);
    }

    // =========================================================================
    //  2. REPARTO DE CARTAS
    // =========================================================================
    /**
     * Reparte {@value #CARTAS_INICIALES} cartas del top del mazo a cada
     * jugador.
     *
     * <p>
     * El bucle externo itera sobre todos los jugadores del mapa. Para cada uno,
     * el bucle interno extrae cartas del índice 0 de la baraja
     * ({@code baraja.remove(0)}) hasta completar {@value #CARTAS_INICIALES}
     * cartas o agotar la baraja.</p>
     *
     * <p>
     * La condición {@code !baraja.isEmpty()} en el bucle interno garantiza que
     * no se lanza {@code IndexOutOfBoundsException} si la baraja se agota antes
     * de completar el reparto (p.ej. si hay más jugadores que cartas
     * disponibles).</p>
     *
     * <p>
     * Si la mano del jugador es {@code null}, se crea una lista nueva antes de
     * añadir cartas, para no lanzar {@code NullPointerException}.</p>
     *
     * @param manos mapa UID - lista de cartas que recibirá las cartas
     * repartidas
     * @param baraja mazo del que se extraen (se modifica: se eliminan las
     * cartas repartidas)
     */
    @Override
    public void repartirCartas(Map<String, List<String>> manos, List<String> baraja) {
        for (String uid : manos.keySet()) {
            List<String> mano = manos.get(uid);
            if (mano == null) {
                mano = new ArrayList<>();
                manos.put(uid, mano); // sustituir null por lista real
            }

            for (int i = 0; i < CARTAS_INICIALES && !baraja.isEmpty(); i++) {
                String carta = baraja.remove(0); // extraemos top del mazo
                mano.add(carta); // añadimos a la mano del jugador
            }
        }
    }

    // =========================================================================
    //  3. ROBAR CARTA
    // =========================================================================
    /**
     * En Pescaito siempre se puede robar (cuando el turno lo permita). La
     * validación de si corresponde robar se hace en el controlador.
     *
     * @param uidJugador UID del jugador (ignorado en esta implementación)
     * @return siempre {@code true}
     */
    @Override
    public boolean puedeRobar(String uidJugador) {
        return true;
    }

    /**
     * Roba la carta del top del mazo y la añade a la mano del jugador.
     *
     * <p>
     * {@code baraja.remove(0)}: elimina y devuelve el elemento en el índice 0,
     * que representa la carta superior del mazo. El índice 0 se usa como "top"
     * por convención en este proyecto.</p>
     *
     * <p>
     * Si la baraja está vacía, el método retorna sin hacer nada. El llamante
     * debe comprobar esto previamente si quiere gestionar el caso de forma
     * explícita.</p>
     *
     * <p>
     * Si la mano del jugador es {@code null} (caso defensivo), se crea antes de
     * añadir la carta para evitar {@code NullPointerException}.</p>
     *
     * @param uidJugador UID del jugador que roba
     * @param manos mapa UID - lista de cartas (se modifica: se añade la carta
     * robada)
     * @param baraja mazo de robo (se modifica: se elimina la carta del top)
     */
    @Override
    public void robarCarta(String uidJugador,
            Map<String, List<String>> manos,
            List<String> baraja) {

        if (baraja.isEmpty()) {
            return; // baraja agotada: no hay carta que robar
        }

        List<String> mano = manos.get(uidJugador);
        if (mano == null) {
            mano = new ArrayList<>();
            manos.put(uidJugador, mano);
        }

        String carta = baraja.remove(0); // roba la primera carta
        mano.add(carta);                 // la añade a la mano
    }

    // =========================================================================
    //  4. DESCARTAR CARTA
    // =========================================================================
    /**
     * En Pescaito siempre se puede descartar (el descarte automático lo
     * gestiona el motor).
     *
     * @param uidJugador UID del jugador (ignorado)
     * @param carta ruta de la carta (ignorada)
     * @return siempre {@code true}
     */
    @Override
    public boolean puedeDescartar(String uidJugador, String carta) {
        return true;
    }

    /**
     * Descarta un grupo completo de {@value #CARTAS_POR_PESCAITO} cartas del
     * mismo número.
     *
     * <p>
     * En Pescaito, el descarte solo ocurre cuando se completa un pescaito
     * (grupo de exactamente {@value #CARTAS_POR_PESCAITO} cartas del mismo
     * número). El método recopila todas las cartas del número indicado en
     * {@code aDescartar} y, solo si hay exactamente
     * {@value #CARTAS_POR_PESCAITO}, las elimina de la mano y las mueve al
     * descarte.</p>
     *
     * <p>
     * La comprobación {@code aDescartar.size() == CARTAS_POR_PESCAITO} es
     * importante: si el jugador tiene 1, 2 o 3 cartas del número (aún no
     * completa el grupo), el método no hace nada.</p>
     *
     * @param uidJugador UID del jugador que descarta
     * @param carta ruta de una de las cartas del grupo (define el número a
     * descartar)
     * @param manos mapa UID - lista de cartas (se modifica si hay pescaito
     * completo)
     * @param baraja mazo (no se usa en este método; requerido por la interfaz)
     * @param descarte pila de descartes (se modifica si hay pescaito completo)
     */
    @Override
    public void descartarCarta(String uidJugador,
            String carta,
            Map<String, List<String>> manos,
            List<String> baraja,
            List<String> descarte) {

        List<String> mano = manos.get(uidJugador);
        if (mano == null || mano.isEmpty()) {
            return; // mano vacía: nada que descartar
        }

        int numeroObjetivo = obtenerNumeroCarta(carta); // número de la carta a descartar

        // Recopilar todas las cartas de ese número en la mano
        List<String> aDescartar = new ArrayList<>();
        for (String c : mano) {
            if (obtenerNumeroCarta(c) == numeroObjetivo) {
                aDescartar.add(c);
            }
        }

        // Solo descartar si hay exactamente 4 cartas (pescaito completo)
        if (aDescartar.size() == CARTAS_POR_PESCAITO) {
            mano.removeAll(aDescartar); // eliminar el cuarteto de la mano
            if (descarte != null) {
                descarte.addAll(aDescartar); // mover al descarte
            }
        }
    }

    // =========================================================================
    //  5. TURNOS
    // =========================================================================
    /**
     * Calcula el UID del siguiente jugador en el orden circular de la lista.
     *
     * <p>
     * Busca el índice de {@code turnoActual} en {@code jugadores} y devuelve el
     * elemento siguiente usando el operador módulo para circular al inicio:</p>
     * <pre>
     * siguiente = (idx + 1) % jugadores.size()
     * </pre>
     * <p>
     * Cuando {@code idx} es el último índice de la lista,
     * {@code (idx + 1) % size} devuelve 0 (el primero), completando la rotación
     * circular.</p>
     *
     * <p>
     * Si {@code turnoActual} no está en la lista (jugador eliminado o error),
     * devuelve el primer elemento como fallback para no bloquear el flujo.</p>
     *
     * @param turnoActual UID del jugador cuyo turno acaba de terminar
     * @param jugadores lista ordenada de UIDs entre los que rotar
     * @return UID del siguiente jugador
     */
    @Override
    public String siguienteTurno(String turnoActual, List<String> jugadores) {
        if (jugadores == null || jugadores.isEmpty()) {
            return turnoActual; // lista vacía: devolvemos el mismo para no fallar
        }

        int idx = jugadores.indexOf(turnoActual);
        if (idx == -1) {
            return jugadores.get(0); // no encontrado: primer jugador como fallback
        }

        int siguiente = (idx + 1) % jugadores.size(); // módulo para circular
        return jugadores.get(siguiente);
    }

    // =========================================================================
    //  6. FIN DE PARTIDA
    // =========================================================================
    /**
     * Determina si la partida de Pescaito ha terminado.
     *
     * <p>
     * La partida termina cuando <strong>todas</strong> las manos están vacías
     * <strong>Y</strong> la baraja también está vacía. Esta condición compuesta
     * es importante: si hay cartas en la baraja, los jugadores con mano vacía
     * recibirán cartas automáticamente al inicio de su siguiente turno, por lo
     * que la partida debe continuar.</p>
     *
     * <p>
     * El algoritmo:</p>
     * <ol>
     * <li>Si el mapa de manos es nulo o vacío - partida terminada (no hay
     * jugadores).</li>
     * <li>Iterar sobre los valores del mapa. Si alguna mano tiene cartas - no
     * terminó.</li>
     * <li>Si se llega al final del bucle (todas vacías) - comprobar si la
     * baraja también está vacía: solo entonces la partida ha terminado.</li>
     * </ol>
     *
     * @param manos mapa UID - lista de cartas de cada jugador
     * @param baraja mazo de robo actual
     * @param descarte pila de descartes (no se usa en Pescaito para esta
     * comprobación)
     * @return {@code true} si la partida ha terminado
     */
    @Override
    public boolean haTerminado(Map<String, List<String>> manos, List<String> baraja, List<String> descarte) {

        if (manos == null || manos.isEmpty()) {
            return true; // sin jugadores: la partida no puede continuar
        }

        // La partida de Pescaito termina cuando TODOS los jugadores
        // tienen la mano vacía Y además la baraja está agotada.
        // Si aún hay cartas en la baraja, el juego puede continuar
        // (los jugadores con mano vacía robarán automáticamente).
        // Comprobamos si algún jugador todavía tiene cartas
        for (List<String> mano : manos.values()) {
            if (mano != null && !mano.isEmpty()) {
                return false; // aún hay cartas en alguna mano
            }
        }

        // Todas las manos vacías - solo termina si la baraja también está vacía
        return baraja == null || baraja.isEmpty();
    }

    // =========================================================================
    //  7. PUNTUACIONES
    // =========================================================================
    /**
     * Convierte la estructura de pescaitos guardada en Firebase a un mapa
     * simple {@code uid - cantidad de pescaitos}.
     *
     * <p>
     * En Firebase, los pescaitos se almacenan como:</p>
     * <pre>
     * partida/pescaitos/{uid}/{n_7: true, n_3: true, ...}
     * </pre>
     * <p>
     * Cada clave bajo el UID es un evento de pescaito (p.ej. {@code "n_7"}), y
     * el número de claves es el número de pescaitos del jugador. Este método
     * cuenta esas claves con {@code mapa.size()}.</p>
     *
     * <p>
     * Si el valor del UID es {@code null} o un mapa vacío, se registra 0
     * pescaitos para ese jugador en lugar de lanzar una excepción, garantizando
     * robustez frente a datos parciales de Firebase.</p>
     *
     * @param pescaitosBD mapa UID - {clave: valor} leído de Firebase con
     * {@link firebase.BDPartidaService#leerPescaitos}
     * @return mapa UID - número de pescaitos
     */
    public Map<String, Integer> calcularPuntuacionesDesdeBD(Map<String, Map<String, Object>> pescaitosBD) {
        Map<String, Integer> resultado = new HashMap<>();

        if (pescaitosBD == null) {
            return resultado; // Firebase devolvió null: mapa vacío como fallback
        }

        for (Map.Entry<String, Map<String, Object>> entry : pescaitosBD.entrySet()) {
            String uid = entry.getKey();
            Map<String, Object> mapa = entry.getValue();

            // Si el mapa es null - 0 pescaitos; si no - contar sus entradas
            int cantidad = (mapa != null) ? mapa.size() : 0;
            resultado.put(uid, cantidad);
        }

        return resultado;
    }

    /**
     * En Pescaito, la puntuación real viene de los pescaitos registrados en
     * Firebase, no de las cartas en mano al finalizar. Devuelve un mapa vacío
     * porque esta información no está disponible en el mapa de manos.
     *
     * <p>
     * Para obtener las puntuaciones reales usar
     * {@link #calcularPuntuacionesDesdeBD(Map)} con los datos leídos de
     * Firebase.</p>
     *
     * @param manos mapa de manos (ignorado en Pescaito)
     * @return mapa vacío; usar {@link #calcularPuntuacionesDesdeBD} para
     * puntuaciones reales
     */
    @Override
    public Map<String, Integer> calcularPuntuaciones(Map<String, List<String>> manos) {
        // En Pescaito la puntuación real viene de los pescaitos, no de las manos.
        return new HashMap<>();
    }

    // =========================================================================
    //  MÉTODOS ESPECÍFICOS DE PESCAITO
    // =========================================================================
    /**
     * Comprueba si la carta que acaba de robar un jugador completa un grupo de
     * {@value #CARTAS_POR_PESCAITO} (pescaito por robo), y si es así ejecuta el
     * descarte.
     *
     * <p>
     * Se llama desde los controladores inmediatamente después de
     * {@link #robarCarta} para detectar si el robo completó un grupo. A
     * diferencia de la detección en {@link #preguntar} (que usa
     * {@link #agruparPorNumero}), este método usa un {@code Stream} con
     * {@code filter} y {@code count()} para ser más conciso.</p>
     *
     * <p>
     * La carta robada siempre es la última de la mano
     * ({@code mano.get(mano.size() - 1)}) porque {@link #robarCarta} la añade
     * al final con {@code mano.add(carta)}.</p>
     *
     * <p>
     * Si se detecta un pescaito:</p>
     * <ol>
     * <li>{@code mano.stream().filter(...).toList()}: filtra todas las cartas
     * del mismo número usando un stream y las recoge en una lista
     * inmutable.</li>
     * <li>{@code descarte.addAll(cuarteto)}: añade las 4 cartas al
     * descarte.</li>
     * <li>{@code mano.removeAll(cuarteto)}: elimina las 4 cartas de la mano.
     * Importante: {@code removeAll} modifica la lista en su lugar.</li>
     * </ol>
     *
     * @param uid UID del jugador que acaba de robar
     * @param manos mapa UID - lista de cartas (se modifica si hay pescaito)
     * @param descarte pila de descartes (se modifica si hay pescaito)
     * @return {@code true} si el robo completó un grupo de 4 (se hizo pescaito)
     */
    public boolean esPescaitoPorRobo(String uid,
            Map<String, List<String>> manos,
            List<String> descarte) {

        List<String> mano = manos.get(uid);
        if (mano == null || mano.isEmpty()) {
            return false;
        }

        // La carta robada siempre es la última: robarCarta() la añade con mano.add(carta)
        String cartaRobada = mano.get(mano.size() - 1);
        int numero = obtenerNumeroCarta(cartaRobada);

        // Contamos cuántas cartas de ese número tiene el jugador
        long count = mano.stream().filter(c -> obtenerNumeroCarta(c) == numero) // filtrar por número
                .count(); // contar coincidencias

        // Si se produce pescaito
        if (count == CARTAS_POR_PESCAITO) {
            // Extraemos el cuarteto
            List<String> cuarteto = mano.stream().filter(c -> obtenerNumeroCarta(c) == numero)
                    .toList();

            // Mover al descarte
            descarte.addAll(cuarteto);

            // Eliminar de la mano
            mano.removeAll(cuarteto);

            return true;
        }

        return false; // No hay 4 cartas del mismo numero aun
    }

    /**
     * Determina qué debe hacer el jugador al inicio de su turno según el estado
     * actual.
     *
     * <p>
     * El algoritmo es sencillo: si el jugador tiene cartas puede jugar
     * normalmente. Si no tiene cartas, la baraja decide:</p>
     * <ul>
     * <li>Con baraja disponible - robar automáticamente (el turno no pasa
     * aún).</li>
     * <li>Sin baraja - pasar turno (no puede hacer nada).</li>
     * </ul>
     *
     * @param uidJugador UID del jugador cuyo turno comienza
     * @param manos mapa UID - lista de cartas actual
     * @param baraja mazo de robo actual
     * @return la {@link AccionTurno} que debe ejecutar el controlador
     */
    @Override
    public AccionTurno accionInicioTurno(String uidJugador,
            Map<String, List<String>> manos,
            List<String> baraja) {

        List<String> mano = manos.get(uidJugador);
        boolean manoVacia = (mano == null || mano.isEmpty());

        if (!manoVacia) {
            return AccionTurno.JUGAR_NORMAL; // tiene cartas: puede preguntar
        }

        // Mano vacía: la baraja decide qué pasa
        if (!baraja.isEmpty()) {
            return AccionTurno.ROBAR_AUTOMATICO; // puede reponerse robando
        }

        return AccionTurno.PASAR_TURNO; // sin cartas y sin baraja: no puede actuar
    }

    /**
     * Determina si la carta recién robada coincide con el número que el jugador
     * había preguntado antes de fallar (condición de "pesca").
     *
     * <p>
     * En Pescaito, "pescar" significa que al robar del mazo el jugador saca
     * exactamente el número que había pedido. En ese caso mantiene el turno,
     * como si hubiera acertado la pregunta original.</p>
     *
     * <p>
     * El parámetro {@code numeroPreguntadoAntes} puede ser {@code null} si el
     * jugador roba en algún contexto diferente (p.ej. robo automático por mano
     * vacía, no por fallo de pregunta). En ese caso se devuelve {@code false}
     * directamente.</p>
     *
     * <p>
     * {@code obtenerNumeroCarta(cartaRobada)}: usa el método {@code default} de
     * la interfaz {@link Juego} para extraer el número de la ruta de
     * imagen.</p>
     *
     * @param cartaRobada ruta de imagen de la carta que acaba de robar el
     * jugador
     * @param numeroPreguntadoAntes número exacto por el que preguntó antes de
     * robar, o {@code null} si no hubo pregunta previa
     * @return {@code true} si la carta robada tiene el mismo número que
     * preguntó
     */
    public boolean haPescadoAlRobar(String cartaRobada, Integer numeroPreguntadoAntes) {

        if (numeroPreguntadoAntes == null) {
            return false; // no hubo pregunta previa - imposible pescar
        }

        int numeroRobado = obtenerNumeroCarta(cartaRobada);  // extrae el número de la carta
        return numeroRobado == numeroPreguntadoAntes; // compara con el número preguntado
    }

}
