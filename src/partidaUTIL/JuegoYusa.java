package partidaUTIL;

import java.util.*;

/**
 * Motor de juego para el modo "La Yusa".
 *
 * ESTRUCTURA DE UNA RONDA: 1. Repartir 1 carta a cada jugador vivo. 2. ¿Alguno
 * tiene un 1 (yusa)? → FaseRonda.YUSA 3. Si no, ¿alguno tiene un 12? →
 * FaseRonda.DOCE 4. Si no → FaseRonda.NORMAL
 *
 * RONDA NORMAL: - Cada jugador (en orden) decide QUEDARSE o PASAR su carta al
 * siguiente. - El receptor no puede rechazarla; solo toma la decisión el que
 * "posee" la carta en su turno. - El ÚLTIMO jugador puede QUEDARSE o ROBAR de
 * la baraja. - Si el último roba → volver a comprobar yusa / doce. - Al final,
 * el jugador con la carta más baja pierde una vida. - Empate → ronda extra
 * entre los empatados.
 *
 * RONDA DOCE: - Se muestran todas las cartas tal cual. Sin intercambios. - El
 * que tenga la carta más baja pierde una vida. - Empate → ronda extra entre los
 * empatados.
 *
 * RONDA YUSA: - Los jugadores SIN yusa descartan su carta. - Cada poseedor de
 * yusa elige un jugador objetivo y le pregunta el palo. - Todos los objetivos
 * eligen palo simultáneamente; se revelan juntos. - Acierta el palo → pierde
 * vida el POSEEDOR de la yusa. - Falla el palo → pierde vida el OBJETIVO.
 *
 * RESET DE BARAJA: - Cuando en el descarte hay 3 o 4 yusas (cartas con número
 * 1), al terminar esa ronda se mezclan todas las cartas y se forma un nuevo
 * mazo de robos.
 */
public class JuegoYusa implements Juego {

    // ─── Constantes ──────────────────────────────────────────────────────────
    public static final int VIDAS_INICIALES = 3;
    public static final int NUMERO_YUSA = 1;
    public static final int NUMERO_DOCE = 12;
    public static final int CARTAS_POR_RONDA = 1;   // una carta por jugador por ronda
    public static final int YUSAS_PARA_RESET = 3;   // 3 o 4 yusas en descarte → reset baraja

    /**
     * Palos posibles de una yusa (el número 1).
     */
    public enum Palo {
        CORONAS, BALANZAS, DIANAS, CORAZONES
    }

    /**
     * Tipo de ronda que corresponde al estado actual de las manos.
     */
    public enum FaseRonda {
        YUSA, DOCE, NORMAL
    }

    /**
     * Qué debe hacer el controlador en el turno de un jugador durante la ronda
     * normal.
     */
    public enum AccionTurno {
        JUGAR_NORMAL, ROBAR_AUTOMATICO, PASAR_TURNO
    }

    // ─── Estado interno ───────────────────────────────────────────────────────
    /**
     * Vidas de cada jugador: uid → vidas restantes.
     */
    private final Map<String, Integer> vidas = new HashMap<>();

    /**
     * Jugadores que siguen vivos (vidas > 0).
     */
    private final List<String> jugadoresVivos = new ArrayList<>();

    // ─── Utilidades de carta ──────────────────────────────────────────────────
    /**
     * Extrae el número de una carta a partir de su ruta de recurso. Formato
     * esperado: ".../palo_numero.png" → p.ej. "/cartas/coronas_7.png" → 7
     */
    @Override
    public int obtenerNumeroCarta(String ruta) {
        String nombre = ruta.substring(ruta.lastIndexOf("/") + 1);
        String[] partes = nombre.split("_");
        return Integer.parseInt(partes[1].replace(".png", ""));
    }

    /**
     * Extrae el palo de una carta a partir de su ruta de recurso. Formato
     * esperado: ".../palo_numero.png" → p.ej. "/cartas/coronas_1.png" → CORONAS
     */
    public Palo obtenerPaloCarta(String ruta) {
        String nombre = ruta.substring(ruta.lastIndexOf("/") + 1);
        String paloStr = nombre.split("_")[0].toUpperCase();
        return Palo.valueOf(paloStr);
    }

    // ─── 1. Configuración inicial ─────────────────────────────────────────────
    /**
     * Inicializa las vidas de todos los jugadores y reparte la primera ronda.
     * Llamar una sola vez al arrancar la partida.
     */
    @Override
    public void iniciarPartida(Map<String, List<String>> manos, List<String> baraja) {
        // En Yusa, iniciarPartida SOLO inicializa vidas y jugadores.
        // El reparto de cartas lo hace repartirCartas() llamado desde
        // iniciarRondaYusa() en el controlador.
        // NO llamar a repartirCartas() aquí.
        for (String uid : manos.keySet()) {
            vidas.put(uid, VIDAS_INICIALES);
            if (!jugadoresVivos.contains(uid)) {
                jugadoresVivos.add(uid);
            }
            if (manos.get(uid) == null) {
                manos.put(uid, new ArrayList<>());
            }
        }
        // SIN repartirCartas(manos, baraja) aquí
    }

    // ─── 2. Reparto ───────────────────────────────────────────────────────────
    /**
     * Reparte UNA carta a cada jugador vivo desde la baraja. Si la baraja se
     * queda vacía antes de terminar, los jugadores restantes no reciben carta
     * (situación que el controlador debe gestionar).
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
            mano.clear();
        }

        // Dar 1 carta a cada jugador vivo
        for (String uid : jugadoresVivos) {
            if (!baraja.isEmpty()) {
                manos.get(uid).add(baraja.remove(0));
            }
        }
    }

    // ─── 3. Robar carta ───────────────────────────────────────────────────────
    @Override
    public boolean puedeRobar(String uidJugador) {
        return !jugadoresVivos.isEmpty();
    }

    /**
     * El último jugador de la ronda normal roba la carta superior de la baraja
     * y la pone en su mano, descartando la que tenía. OJO: el descarte de la
     * carta anterior lo gestiona el controlador (porque necesita saber qué
     * carta se descarta para actualizar Firebase).
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

        // Quitar la carta actual (la que no quería) antes de añadir la nueva
        mano.clear();
        mano.add(baraja.remove(0));
    }

    // ─── 4. Descartar carta ───────────────────────────────────────────────────
    @Override
    public boolean puedeDescartar(String uidJugador, String carta) {
        return true;
    }

    /**
     * Descarta la carta de un jugador a la pila de descarte. Usado cuando en la
     * ronda de yusa los jugadores sin yusa descartan, y también cuando el
     * último jugador decide robar (descarta la anterior).
     */
    @Override
    public void descartarCarta(String uidJugador,
            String carta,
            Map<String, List<String>> manos,
            List<String> baraja,
            List<String> descarte) {

        List<String> mano = manos.get(uidJugador);
        if (mano != null) {
            mano.remove(carta);
        }
        if (descarte != null) {
            descarte.add(carta);
        }
    }

    public void inicializarJugadoresVivos(List<String> jugadores) {
        jugadoresVivos.clear();
        jugadoresVivos.addAll(jugadores);
    }

    // ─── 5. Turnos ────────────────────────────────────────────────────────────
    @Override
    public String siguienteTurno(String turnoActual, List<String> jugadores) {
        if (jugadores == null || jugadores.isEmpty()) {
            return turnoActual;
        }
        int idx = jugadores.indexOf(turnoActual);
        if (idx == -1) {
            return jugadores.get(0);
        }
        return jugadores.get((idx + 1) % jugadores.size());
    }

    @Override
    public Juego.AccionTurno accionInicioTurno(String uidJugador,
            Map<String, List<String>> manos,
            List<String> baraja) {
        return Juego.AccionTurno.JUGAR_NORMAL;
    }

    // ─── 6. Fin de partida ────────────────────────────────────────────────────
    /**
     * La partida termina cuando solo queda un jugador con vida.
     */
    @Override
    public boolean haTerminado(Map<String, List<String>> manos,
            List<String> baraja, List<String> descarte) {
        // Si aún no hay vidas registradas, la partida no puede haber terminado
        if (vidas.isEmpty()) {
            return false;
        }
        long vivos = vidas.values().stream().filter(v -> v > 0).count();
        return vivos <= 1;
    }

    // ─── 7. Puntuaciones ─────────────────────────────────────────────────────
    /**
     * Devuelve las vidas restantes de cada jugador como puntuación. El ganador
     * es el que tiene vidas > 0 al terminar.
     */
    @Override
    public Map<String, Integer> calcularPuntuaciones(Map<String, List<String>> manos) {
        return new HashMap<>(vidas);
    }

    // ─── LÓGICA ESPECÍFICA DE LA YUSA ────────────────────────────────────────
    /**
     * Analiza las manos actuales y determina qué tipo de ronda corresponde.
     *
     * Prioridad: YUSA > DOCE > NORMAL
     *
     * @param manos manos actuales de todos los jugadores
     * @return la fase que debe iniciar el controlador
     */
    public FaseRonda determinarFaseRonda(Map<String, List<String>> manos) {

        boolean hayYusa = false;
        boolean hayDoce = false;

        for (List<String> mano : manos.values()) {
            if (mano == null || mano.isEmpty()) {
                continue;
            }
            int numero = obtenerNumeroCarta(mano.get(0));
            if (numero == NUMERO_YUSA) {
                hayYusa = true;
            }
            if (numero == NUMERO_DOCE) {
                hayDoce = true;
            }
        }

        if (hayYusa) {
            return FaseRonda.YUSA;
        }
        if (hayDoce) {
            return FaseRonda.DOCE;
        }
        return FaseRonda.NORMAL;
    }

    /**
     * Intercambia la carta del jugador actual con el siguiente en el orden. El
     * jugador actual pasa su carta al siguiente; el siguiente le da la suya.
     *
     * Usado en la ronda normal cuando un jugador decide NO quedarse su carta.
     *
     * @param uidActual jugador que inicia el intercambio
     * @param uidSiguiente jugador que recibe la carta (no puede negarse)
     * @param manos estado actual de las manos
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

        // Intercambio simple: cada uno se queda con la carta del otro
        String cartaActual = manoActual.get(0);
        String cartaSiguiente = manoSiguiente.get(0);

        manoActual.set(0, cartaSiguiente);
        manoSiguiente.set(0, cartaActual);
    }

    /**
     * Determina los perdedores de la ronda: el o los jugadores con la carta más
     * baja entre todos los jugadores activos en esa ronda.
     *
     * Si hay empate devuelve todos los empatados (el controlador gestionará la
     * ronda extra entre ellos).
     *
     * El valor de las cartas es su número directamente (2 es la más baja, 11 la
     * más alta en una ronda normal o de doce).
     *
     * @param manos manos de los jugadores que participan en esta ronda
     * @param uids lista de uids que participan (puede ser subconjunto en ronda
     * extra)
     * @return lista de uids que han perdido (1 jugador o varios si hay empate)
     */
    public List<String> determinarPerdedores(Map<String, List<String>> manos,
            List<String> uids) {

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
     * Aplica la pérdida de una vida al jugador indicado. Si llega a 0 vidas, lo
     * elimina de jugadoresVivos.
     *
     * @param uid jugador que pierde la vida
     * @return true si el jugador ha sido eliminado (vidas = 0)
     */
    public boolean perderVida(String uid) {

        int vidasActuales = vidas.getOrDefault(uid, 0);
        int nuevasVidas = Math.max(0, vidasActuales - 1);
        vidas.put(uid, nuevasVidas);

        if (nuevasVidas == 0) {
            jugadoresVivos.remove(uid);
            return true; // eliminado
        }

        return false;
    }

    /**
     * Resuelve la fase de yusa para UN poseedor concreto y SU objetivo.
     *
     * Llamar este método una vez por cada par (poseedor, objetivo) DESPUÉS de
     * que todos los objetivos hayan escogido su palo (revelación simultánea).
     *
     * @param uidPoseedor jugador que tiene el 1
     * @param uidObjetivo jugador al que se le preguntó el palo
     * @param palosElegidos mapa objetivoUid → Palo elegido por ese objetivo
     * @param manos manos actuales (para saber el palo real de la yusa)
     * @return uid del jugador que PIERDE la vida en este par
     */
    public String resolverYusa(String uidPoseedor,
            String uidObjetivo,
            Map<String, Palo> palosElegidos,
            Map<String, List<String>> manos) {

        // Palo real de la yusa del poseedor
        String cartaYusa = manos.get(uidPoseedor).get(0);
        Palo paloReal = obtenerPaloCarta(cartaYusa);

        // Palo que eligió el objetivo
        Palo paloElegido = palosElegidos.get(uidObjetivo);

        if (paloElegido == paloReal) {
            // Acertó → pierde el poseedor
            return uidPoseedor;
        } else {
            // Falló → pierde el objetivo
            return uidObjetivo;
        }
    }

    /**
     * Comprueba si el descarte acumula suficientes yusas para resetear la
     * baraja.
     *
     * @param descarte pila de descarte actual
     * @return true si hay 3 o 4 cartas con número 1 en el descarte
     */
    public boolean debeResetearBaraja(List<String> descarte) {

        if (descarte == null) {
            return false;
        }

        long yusasEnDescarte = descarte.stream()
                .filter(c -> obtenerNumeroCarta(c) == NUMERO_YUSA)
                .count();

        return yusasEnDescarte >= YUSAS_PARA_RESET;
    }

    /**
     * Resetea la baraja: mueve todas las cartas del descarte al mazo, las
     * baraja aleatoriamente, y vacía el descarte.
     *
     * Llamar al FINAL de la ronda en la que se detectó el umbral de yusas, no
     * al inicio de la siguiente.
     *
     * @param baraja mazo de robos actual (puede estar casi vacío)
     * @param descarte pila de descarte actual
     */
    public void resetearBaraja(List<String> baraja, List<String> descarte) {

        baraja.addAll(descarte);
        descarte.clear();
        Collections.shuffle(baraja);
    }

    /**
     * Descarta las manos de todos los jugadores vivos al final de la ronda. Las
     * cartas van a la pila de descarte.
     *
     * Llamar SIEMPRE al terminar una ronda, antes de repartir la siguiente,
     * excepto cuando resetearBaraja() ya lo haya gestionado.
     *
     * @param manos manos actuales
     * @param descarte pila de descarte
     */
    public void descartarManosAlFinDeRonda(Map<String, List<String>> manos,
            List<String> descarte) {

        for (Map.Entry<String, List<String>> entry : manos.entrySet()) {
            List<String> mano = entry.getValue();
            if (mano != null && !mano.isEmpty()) {
                descarte.addAll(mano);
                mano.clear();
            }
        }
    }

    // ─── Getters de estado ────────────────────────────────────────────────────
    /**
     * Devuelve las vidas actuales de un jugador (0 si ya fue eliminado).
     */
    public int getVidas(String uid) {
        return vidas.getOrDefault(uid, 0);
    }

    /**
     * Devuelve el mapa completo de vidas (uid → vidas).
     */
    public Map<String, Integer> getTodasLasVidas() {
        return Collections.unmodifiableMap(vidas);
    }

    /**
     * Devuelve la lista de jugadores que siguen vivos.
     */
    public List<String> getJugadoresVivos() {
        return Collections.unmodifiableList(jugadoresVivos);
    }

    /**
     * Inicializa el estado de vidas desde Firebase al reconectar. Llamar si el
     * controlador detecta que las vidas ya estaban guardadas en BD.
     *
     * @param vidasBD mapa uid → vidas leído de Firebase
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
