package firebase;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;

/**
 * Servicio de acceso a datos de partida en Firebase Realtime Database.
 *
 * <p>
 * Esta clase actúa como capa de abstracción de alto nivel entre los
 * controladores de partida ({@link ui.PartidaControllerBase} y subclases) y el
 * servicio HTTP de bajo nivel ({@link FirebaseDatabaseService}). Los
 * controladores nunca llaman a {@link FirebaseDatabaseService} directamente
 * para operaciones de partida; todo pasa por este servicio.</p>
 *
 * <h2>Responsabilidades:</h2>
 * <ul>
 * <li>Lectura y escritura de todos los nodos del subárbol
 * {@code salas/{codigo}/partida/} en Firebase.</li>
 * <li>Serialización/deserialización JSON con {@link Gson}.</li>
 * <li>Creación y gestión de hilos de polling para escuchar cambios en Firebase
 * (patrón Observer mediante callbacks).</li>
 * <li>Normalización de datos: p.ej. manos vacías - {@code ["EMPTY"]} (debido a
 * que Firebase no puede almacenar listas vacías nativas).</li>
 * </ul>
 *
 * <h2>Arquitectura de polling</h2>
 * <p>
 * La aplicación utiliza peticiones HTTP GET periódicas ("polling")
 * implementadas como hilos de background con {@link Thread#sleep}. Cada método
 * {@code escuchar*} crea un hilo daemon que consulta Firebase cada N
 * milisegundos y, cuando detecta un cambio, invoca el callback del
 * llamante.</p>
 *
 * <p>
 * Todos los hilos de polling son daemon ({@code t.setDaemon(true)}), lo que
 * significa que la JVM los terminará automáticamente cuando no queden hilos de
 * usuario vivos, evitando que bloqueen el cierre de la aplicación.</p>
 *
 * <p>
 * Los hilos se pueden detener llamando a {@code hilo.interrupt()}, que lanza
 * {@link InterruptedException} dentro del {@code Thread.sleep()}, haciendo que
 * el bucle salga limpiamente.</p>
 *
 * <h2>Estructura de nodos en Firebase</h2>
 * <pre>
 * salas/{codigo}/
 *   partida/
 *     manos/{uid}/         - cartas de cada jugador
 *     baraja/              - mazo de robo
 *     descarte/            - pila de descartes
 *     turno/               - UID del jugador con el turno
 *     estado/              - "iniciada" | "finalizada"
 *     narrador/            - último mensaje del narrador
 *     pescaitos/{uid}/     - registro de pescaitos por jugador (Pescaito)
 *     vidas/{uid}/         - vidas restantes (Yusa)
 *     estadoRonda/         - estado de la ronda (Yusa: accion, fase, turnoUid, ts)
 *     decisionJugador/     - decisión del no-director al director (Yusa)
 *     yusa/
 *       objetivos/{uid}/   - objetivo elegido por cada poseedor
 *     ronda/               - número de ronda actual
 *     modo/                - "Pescaito" | "Yusa"
 *   volverSala/            - timestamp cuando el host pulsa "Volver a sala"
 * </pre>
 *
 * @author Javier Coronilla Castellano
 */
public class BDPartidaService {

    /**
     * Servicio HTTP de bajo nivel que realiza las peticiones GET, PUT y DELETE
     * contra la API REST de Firebase Realtime Database. Todas las operaciones
     * de este servicio delegan en este objeto.
     */
    private final FirebaseDatabaseService db;

    /**
     * Instancia de Gson para serialización y deserialización JSON.
     */
    private final Gson gson = new Gson();

    /**
     * Construye el servicio inyectando el proveedor HTTP de Firebase.
     *
     * @param db proveedor HTTP de Firebase Realtime Database
     */
    public BDPartidaService(FirebaseDatabaseService db) {
        this.db = db;
    }

    // =========================================================================
    //  HELPER INTERNO - creación de hilos de polling
    // =========================================================================
    /**
     * Crea, configura y arranca un hilo de polling con la tarea indicada.
     *
     * <p>
     * Todos los métodos {@code escuchar*} de esta clase usan este helper. El
     * hilo creado tiene las siguientes características:</p>
     * <ul>
     * <li><strong>Daemon:</strong> {@code t.setDaemon(true)} hace que la JVM no
     * espere a este hilo para terminar. Si todos los hilos de usuario terminan
     * (p.ej. el usuario cierra la ventana), los hilos daemon se interrumpen
     * automáticamente.</li>
     * <li><strong>Interruptible:</strong> el hilo puede detenerse llamando a
     * {@code hilo.interrupt()}, que lanza {@link InterruptedException} dentro
     * del {@code Thread.sleep()} del bucle de polling.</li>
     * </ul>
     *
     * <p>
     * El llamante es responsable de guardar la referencia al hilo devuelto para
     * poder interrumpirlo cuando ya no sea necesario (p.ej. al destruir el
     * controlador).</p>
     *
     * @param tarea {@link Runnable} con el bucle de polling a ejecutar
     * @return el hilo daemon ya iniciado
     */
    private Thread crearHiloPolling(Runnable tarea) {
        Thread t = new Thread(tarea);
        t.setDaemon(true); // no bloquear el cierre de la JVM
        t.start();         // arrancar el hilo inmediatamente
        return t;
    }

    // =========================================================================
    //  LECTURA Y ESCRITURA DE LA PARTIDA COMPLETA
    // =========================================================================
    /**
     * Lee el nodo completo de la partida desde Firebase y lo deserializa.
     *
     * <p>
     * El nodo {@code salas/{codigo}/partida} contiene manos, baraja, descarte,
     * turno, estado y todos los demás campos de la partida como un único objeto
     * JSON. Gson lo deserializa a un {@code Map<String, Object>} sin tipo
     * específico, donde los valores pueden ser {@code String}, {@code Double},
     * {@code Boolean}, etc, según el tipo JSON.</p>
     *
     * <p>
     * Devuelve {@code null} en lugar de un mapa vacío cuando el nodo no existe
     * o tiene el valor JSON {@code null}.</p>
     *
     * @param codigoSala código único de la sala (p.ej. "ABCD12")
     * @param idToken token de autenticación Firebase del jugador local
     * @return mapa con los campos de la partida, o {@code null} si no existe
     * @throws IOException si la petición HTTP a Firebase falla
     */
    public Map<String, Object> leerPartida(String codigoSala, String idToken) throws IOException {
        String json = db.leerNodo("salas/" + codigoSala + "/partida", idToken);
        if (json == null || json.equals("null")) {
            return null; // distinguir entre nodo inexistente y mapa vacío
        }
        return gson.fromJson(json, Map.class);
    }

    /**
     * Escribe el nodo completo de la partida en Firebase al iniciarla.
     *
     * <p>
     * Reemplaza o crea el nodo {@code salas/{codigo}/partida} con el mapa
     * {@code datosPartida}, que normalmente contiene las manos iniciales, la
     * baraja, el turno inicial, el modo de juego y el estado "iniciada".</p>
     *
     * @param codigoSala código único de la sala
     * @param datosPartida mapa con todos los campos iniciales de la partida
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void iniciarPartida(String codigoSala, Map<String, Object> datosPartida, String idToken) throws IOException {
        db.actualizarNodo("salas/" + codigoSala + "/partida", datosPartida, idToken);
    }

    // =========================================================================
    //  MANOS
    // =========================================================================
    /**
     * Publica la mano de un jugador específico en Firebase.
     *
     * <p>
     * <strong>Convención {@code ["EMPTY"]}:</strong> Firebase Realtime Database
     * no puede almacenar listas vacías ({@code []}) de forma nativa; si se
     * envía una lista vacía, Firebase simplemente borra el nodo. Para
     * representar que un jugador tiene 0 cartas sin borrar el nodo, se usa
     * {@code ["EMPTY"]} (lista con un único elemento). Los controladores
     * normalizan este valor de vuelta a lista vacía en
     * {@link ui.PartidaControllerBase#actualizarDesdeModelo}.</p>
     *
     * @param codigoSala código de la sala
     * @param uid UID del jugador cuya mano se actualiza
     * @param mano lista de rutas de imagen de las cartas; puede ser vacía o
     * {@code null}
     * @param token token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void actualizarMano(String codigoSala, String uid, List<String> mano, String token) throws IOException {
        // IMPORTANTE: Normalizar lista vacía o null a ["EMPTY"] para preservar el nodo en Firebase
        List<String> manoSegura = (mano == null || mano.isEmpty()) ? List.of("EMPTY") : mano;
        db.actualizarNodo("salas/" + codigoSala + "/partida/manos/" + uid, manoSegura, token);
    }

    /**
     * Publica todas las manos de todos los jugadores en una sola operación.
     *
     * <p>
     * A diferencia de {@link #actualizarMano}, que actualiza de jugador en
     * jugador, este método escribe todo el nodo {@code partida/manos} de una
     * vez. Esto es más eficiente cuando cambian varias manos a la vez (p.ej. en
     * Yusa al repartir cartas o al intercambiar). También garantiza que todos
     * los clientes verán el estado consistente en el mismo ciclo de
     * polling.</p>
     *
     * <p>
     * Aplica la misma convención {@code ["EMPTY"]} que
     * {@link #actualizarMano}.</p>
     *
     * @param codigoSala código de la sala
     * @param manos mapa UID - lista de cartas de todos los jugadores
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void publicarManosYusa(String codigoSala, Map<String, List<String>> manos, String idToken) throws IOException {
        Map<String, Object> manosSeguras = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : manos.entrySet()) {
            List<String> mano = entry.getValue();
            // Normalización a ["EMPTY"]
            manosSeguras.put(entry.getKey(), (mano == null || mano.isEmpty()) ? List.of("EMPTY") : mano);
        }
        db.actualizarNodo("salas/" + codigoSala + "/partida/manos", manosSeguras, idToken);
    }

    // =========================================================================
    //  BARAJA, DESCARTE, TURNO
    // =========================================================================
    /**
     * Publica el estado actualizado del mazo de robo en Firebase. Se llama cada
     * vez que se roba una carta para que todos los clientes vean cuántas cartas
     * quedan.
     *
     * @param codigoSala código de la sala
     * @param baraja lista de rutas de imagen del mazo (orden: índice 0 = top
     * del mazo)
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void actualizarBaraja(String codigoSala, List<String> baraja, String idToken) throws IOException {
        db.actualizarNodo("salas/" + codigoSala + "/partida/baraja", baraja, idToken);
    }

    /**
     * Publica el estado actualizado de la pila de descartes en Firebase.
     *
     * @param codigoSala código de la sala
     * @param descarte lista de rutas de imagen del descarte (orden: último =
     * visible)
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void actualizarDescarte(String codigoSala, List<String> descarte, String idToken) throws IOException {
        db.actualizarNodo("salas/" + codigoSala + "/partida/descarte", descarte, idToken);
    }

    /**
     * Actualiza el nodo de turno en Firebase para indicar qué jugador debe
     * actuar.
     *
     * <p>
     * Todos los clientes escuchan este nodo con {@link #escucharTurno}. Cuando
     * cambia, su listener llama a
     * {@link ui.PartidaControllerBase#onCambioTurno(String)}.</p>
     *
     * @param codigoSala código de la sala
     * @param uidTurno UID del jugador cuyo turno comienza
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void actualizarTurno(String codigoSala, String uidTurno, String idToken) throws IOException {
        db.actualizarNodo("salas/" + codigoSala + "/partida/turno", uidTurno, idToken);
    }

    // =========================================================================
    // (modo Pescaito)
    // =========================================================================
    /**
     * Registra un pescaito individual en Firebase usando un timestamp como
     * clave.
     *
     * <p>
     * Cada pescaito se guarda como un evento separado bajo
     * {@code partida/pescaitos/{uid}/n_{numero}} con valor {@code true}. Al
     * contar los pescaitos, se cuentan las claves bajo el nodo del jugador.</p>
     *
     * @param codigoSala código de la sala
     * @param uidJugador UID del jugador que hizo el pescaito
     * @param numero número de la carta del pescaito (p.ej. 7)
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void registrarPescaito(String codigoSala, String uidJugador, int numero, String idToken) throws IOException {
        // "partida/pescaitos/{uid}/n_{numero} - true"
        String ruta = "salas/" + codigoSala + "/partida/pescaitos/" + uidJugador + "/n_" + numero;
        db.actualizarNodo(ruta, true, idToken);
    }

    /**
     * Lee el nodo de pescaitos de Firebase y lo convierte a una estructura
     * uniforme.
     *
     * <p>
     * El nodo tiene la forma {@code {uid: {n_7: true, n_3: true, ...}}}. Gson
     * lo deserializa como {@code Map<String, Object>} donde el valor de cada
     * UID puede ser un {@code Map} (objeto JSON) o una {@code List} (array JSON
     * si las claves son índices numéricos consecutivos). Este método normaliza
     * ambos casos al tipo uniforme {@code Map<String, Map<String, Object>>} que
     * espera el motor del juego.</p>
     *
     * <p>
     * El {@link TypeToken} es necesario porque Java borra los tipos genéricos
     * en tiempo de ejecución (type erasure).
     * {@code new TypeToken<Map<String, Object>>() {}.getType()} captura el tipo
     * {@code Map<String, Object>} como instancia de {@link Type} que Gson puede
     * usar para guiar la deserialización.</p>
     *
     * <h3>Casos manejados</h3>
     * <ul>
     * <li>Valor es {@code Map}: pasa directamente.</li>
     * <li>Valor es {@code List}: Firebase convirtió el mapa a array porque las
     * claves eran índices numéricos. Se reconstruye el mapa con índices como
     * claves String.</li>
     * <li>Cualquier otro valor: se crea un mapa vacío para ese UID.</li>
     * </ul>
     *
     * @param codigoSala código de la sala
     * @param idToken token de autenticación Firebase
     * @return mapa UID - {clave: valor} de los pescaitos, o mapa vacío si no
     * hay
     * @throws IOException si la petición HTTP falla
     */
    public Map<String, Map<String, Object>> leerPescaitos(String codigoSala, String idToken) throws IOException {
        String json = db.leerNodo("salas/" + codigoSala + "/partida/pescaitos", idToken);
        System.out.println("DEBUG leerPescaitos JSON: " + json);
        if (json == null || json.equals("null")) {
            return new HashMap<>();
        }

        // TypeToken: captura el tipo genérico Map<String, Object> para Gson
        // necesario porque Java borra los tipos genéricos en runtime (type erasure)
        Type tipoGenerico = new TypeToken<Map<String, Object>>() {
        }.getType();
        Map<String, Object> mapaRaw = gson.fromJson(json, tipoGenerico);
        if (mapaRaw == null) {
            return new HashMap<>();
        }

        Map<String, Map<String, Object>> resultado = new HashMap<>();
        for (Map.Entry<String, Object> entry : mapaRaw.entrySet()) {
            String uid = entry.getKey();
            Object valor = entry.getValue();
            if (valor instanceof Map) {
                // Caso normal: el valor es un objeto JSON, pasa directamente
                resultado.put(uid, (Map<String, Object>) valor);
            } else if (valor instanceof List) {
                // Firebase convirtió el mapa a array, reconstruimos con índices como claves
                List<?> lista = (List<?>) valor;
                Map<String, Object> convertido = new HashMap<>();
                for (int i = 0; i < lista.size(); i++) {
                    if (lista.get(i) != null) {
                        convertido.put(String.valueOf(i), lista.get(i));
                    }
                }
                resultado.put(uid, convertido);
            } else {
                // Valor inesperado, mapa vacío como fallback seguro
                resultado.put(uid, new HashMap<>());
            }
        }
        return resultado;
    }

    // =========================================================================
    //  ESTADO DE LA PARTIDA
    // =========================================================================
    /**
     * Actualiza el campo {@code estado} de la partida en Firebase.
     *
     * <p>
     * Valores usados en la aplicación:</p>
     * <ul>
     * <li>{@code "iniciada"}: la partida está en curso.</li>
     * <li>{@code "finalizada"}: la partida ha terminado.</li>
     * </ul>
     *
     * @param codigoSala código de la sala
     * @param estado nuevo estado de la partida
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void actualizarEstadoPartida(String codigoSala, String estado, String idToken) throws IOException {
        db.actualizarNodo("salas/" + codigoSala + "/partida/estado", estado, idToken);
    }

    /**
     * Crea un hilo de polling que escucha cambios en el estado de la partida.
     *
     * <p>
     * Patrón de polling común a todos los {@code escuchar*}:</p>
     * <ol>
     * <li>Leer el nodo cada 500ms.</li>
     * <li>Si el valor cambió respecto al último leído
     * ({@code !valor.equals(ultimo)}), actualizar {@code ultimo} e invocar el
     * callback.</li>
     * <li>Si el hilo es interrumpido ({@link InterruptedException}), llamar a
     * {@code interrupt()} de nuevo para restaurar el flag de interrupción y
     * salir del bucle.</li>
     * </ol>
     *
     * <p>
     * {@code valor.replace("\"", "")}: Firebase devuelve los strings JSON entre
     * comillas dobles (p.ej. {@code "\"finalizada\""}). Se eliminan para que el
     * callback reciba el valor limpio.</p>
     *
     * @param codigoSala código de la sala
     * @param idToken token de autenticación Firebase
     * @param callback función que recibe el estado limpio (sin comillas)
     * @return hilo daemon del polling; interrumpirlo para detenerlo
     */
    public Thread escucharEstadoPartida(String codigoSala, String idToken, Consumer<String> callback) {
        return crearHiloPolling(() -> {
            String ultimo = null; // último valor procesado; null = ninguno aún
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String valor = db.leerNodo("salas/" + codigoSala + "/partida/estado", idToken);
                    if (valor != null && !valor.equals(ultimo)) {
                        ultimo = valor;
                        callback.accept(valor.replace("\"", "")); // eliminamos comillas JSON
                    }
                    Thread.sleep(500); // esperamos 500ms entre peticiones
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // restauramos el flag de interrupción
                } catch (Exception e) {
                    e.printStackTrace(); // Capturamos execepciones HTTP
                }
            }
        });
    }

    /**
     * Borra el nodo completo de la partida de Firebase. Se llama desde
     * {@link ui.PopUpFinalPartidaController} cuando el host vuelve a la sala,
     * para que el nodo no quede obsoleto en la base de datos y se pueda jugar
     * nuevamente una partida nueva o se borre definitivamente el nodo de esa
     * sala si no se juega más.
     *
     * @param codigoSala código de la sala
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void borrarPartida(String codigoSala, String idToken) throws IOException {
        db.borrarNodo("salas/" + codigoSala + "/partida", idToken);
    }

    // =========================================================================
    //  CAMPO GENÉRICO / NARRADOR
    // =========================================================================
    /**
     * Lee un campo concreto del nodo de partida y lo devuelve como objeto
     * genérico.
     *
     * <p>
     * Gson deserializa los tipos primitivos JSON así:</p>
     * <ul>
     * <li>String JSON - {@code String}</li>
     * <li>Number JSON - {@code Double} (por defecto con
     * {@code Object.class})</li>
     * <li>Boolean JSON - {@code Boolean}</li>
     * <li>Object JSON - {@code Map<String, Object>}</li>
     * <li>Array JSON - {@code List<Object>}</li>
     * </ul>
     *
     * @param codigoSala código de la sala
     * @param campo nombre del campo a leer (p.ej. "modo", "turno")
     * @param idToken token de autenticación Firebase
     * @return el valor del campo deserializado como objeto, o {@code null} si
     * no existe
     * @throws IOException si la petición HTTP falla
     */
    public Object leerCampo(String codigoSala, String campo, String idToken) throws IOException {
        String json = db.leerNodo("salas/" + codigoSala + "/partida/" + campo, idToken);
        return gson.fromJson(json, Object.class);
    }

    /**
     * Publica un mensaje del narrador en Firebase.
     *
     * <p>
     * El mensaje es un mapa con las claves {@code tipo} ("global" o "privado"),
     * {@code texto} y opcionalmente {@code uid} (el destinatario del mensaje
     * privado). Todos los clientes reciben el mensaje a través de
     * {@link #escucharNarrador} y lo muestran en el {@code narradorLabel} si
     * corresponde.</p>
     *
     * @param codigoSala código de la sala
     * @param mensaje mapa con las claves tipo, texto y uid
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void actualizarNarrador(String codigoSala, Map<String, Object> mensaje, String idToken) throws IOException {
        db.actualizarNodo("salas/" + codigoSala + "/partida/narrador", mensaje, idToken);
    }

    /**
     * Crea un hilo de polling que escucha mensajes del narrador.
     *
     * <p>
     * Cuando llega un mensaje nuevo, lo deserializa a
     * {@code Map<String, Object>} e invoca el callback. El llamante
     * ({@link ui.PartidaControllerBase}) decide si el mensaje es para este
     * cliente basándose en las claves {@code tipo} y {@code uid}.</p>
     *
     * @param codigoSala código de la sala
     * @param idToken token de autenticación Firebase
     * @param callback función que recibe el mapa del mensaje
     * @return hilo daemon del polling
     */
    public Thread escucharNarrador(String codigoSala, String idToken, Consumer<Map<String, Object>> callback) {
        return crearHiloPolling(() -> {
            String ultimo = null;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String json = db.leerNodo("salas/" + codigoSala + "/partida/narrador", idToken);
                    if (json != null && !json.equals(ultimo)) {
                        ultimo = json;
                        Map<String, Object> mensaje = gson.fromJson(json, Map.class);
                        if (mensaje != null) {
                            callback.accept(mensaje);
                        }
                    }
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    // =========================================================================
    //  TURNO
    // =========================================================================
    /**
     * Crea un hilo de polling que escucha cambios en el nodo
     * {@code partida/turno}.
     *
     * <p>
     * Cuando cambia el UID del jugador con el turno, invoca el callback con el
     * UID limpio (sin comillas JSON). El controlador base usa este listener
     * para notificar a las subclases mediante
     * {@link ui.PartidaControllerBase#onCambioTurno(String)}.</p>
     *
     * @param codigoSala código de la sala
     * @param idToken token de autenticación Firebase
     * @param callback función que recibe el UID del nuevo jugador con turno
     * @return hilo daemon del polling
     */
    public Thread escucharTurno(String codigoSala, String idToken, Consumer<String> callback) {
        return crearHiloPolling(() -> {
            String ultimo = null;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String valor = db.leerNodo("salas/" + codigoSala + "/partida/turno", idToken);
                    if (valor != null && !valor.equals(ultimo)) {
                        ultimo = valor;
                        callback.accept(valor.replace("\"", ""));
                    }
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    // =========================================================================
    //  PARTIDA COMPLETA
    // =========================================================================
    /**
     * Crea un hilo de polling que escucha cambios en el nodo completo de la
     * partida.
     *
     * <p>
     * Este listener recibe el JSON completo de {@code partida/} incluyendo
     * manos, baraja, descarte y turno. Se usa en
     * {@link ui.PartidaControllerBase} para sincronizar el modelo local con
     * Firebase cada vez que otro jugador actúa.</p>
     *
     * <p>
     * La comprobación {@code !"null".equals(json)} evita invocar el callback
     * cuando Firebase devuelve la cadena literal {@code "null"} (nodo
     * borrado).</p>
     *
     * @param codigoSala código de la sala
     * @param idToken token de autenticación Firebase
     * @param callback función que recibe el mapa completo de la partida
     * @return hilo daemon del polling
     */
    public Thread escucharPartida(String codigoSala, String idToken, Consumer<Map<String, Object>> callback) {
        return crearHiloPolling(() -> {
            String ultimo = null;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String json = db.leerNodo("salas/" + codigoSala + "/partida", idToken);
                    if (json != null && !json.equals(ultimo)) {
                        ultimo = json;
                        if (!"null".equals(json)) { // evitamos procesar el nodo borrado
                            Map<String, Object> partida = gson.fromJson(json, Map.class);
                            if (partida != null) {
                                callback.accept(partida);
                            }
                        }
                    }
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    // =========================================================================
    //  VOLVER A SALA / NUEVA PARTIDA
    // =========================================================================
    /**
     * Escucha el nodo {@code salas/{codigo}/volverSala} que el host escribe al
     * pulsar "Volver a la sala" en el popup final.
     *
     * <p>
     * El host escribe el timestamp actual como valor. Los clientes lo detectan
     * aquí y navegan a la sala online. El {@code try/catch} de
     * {@code NumberFormatException} cubre el caso de que el valor no sea un
     * número (nunca debería ocurrir).</p>
     *
     * @param codigoSala código de la sala
     * @param idToken token de autenticación Firebase
     * @param callback función que recibe el timestamp del evento (o 0L si no
     * parsea)
     * @return hilo daemon del polling
     */
    public Thread escucharVolverSala(String codigoSala, String idToken, Consumer<Long> callback) {
        return crearHiloPolling(() -> {
            String ultimo = null;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String val = db.leerNodo("salas/" + codigoSala + "/volverSala", idToken);
                    // !"null".equals(val): ignoramos cuando el nodo no existe
                    if (val != null && !val.equals(ultimo) && !"null".equals(val)) {
                        ultimo = val;
                        try {
                            callback.accept(Long.parseLong(val.trim())); // parsear timestamp
                        } catch (NumberFormatException e) {
                            callback.accept(0L); // fallback seguro si el valor no es número
                        }
                    }
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Escucha cuando el host inicia una nueva partida desde el popup final.
     *
     * <p>
     * Detecta el cambio del campo {@code estado} de "finalizada" a "iniciada".
     * Cuando ocurre, lee también el campo {@code modo} para que el cliente sepa
     * qué controlador de juego instanciar. El callback recibe directamente el
     * modo (p.ej. "Pescaito" o "Yusa").</p>
     *
     * @param codigoSala código de la sala
     * @param idToken token de autenticación Firebase
     * @param callback función que recibe el modo de juego ("Pescaito" o "Yusa")
     * @return hilo daemon del polling
     */
    public Thread escucharNuevaPartida(String codigoSala, String idToken, Consumer<String> callback) {
        return crearHiloPolling(() -> {
            String ultimoEstado = null;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String estado = db.leerNodo("salas/" + codigoSala + "/partida/estado", idToken);
                    if (estado != null && !estado.equals(ultimoEstado)) {
                        ultimoEstado = estado;
                        String limpio = estado.replace("\"", "").trim();
                        if ("iniciada".equals(limpio)) {
                            // Nueva partida detectada: leemos el modo para saber qué iniciar
                            String modoRaw = db.leerNodo("salas/" + codigoSala + "/partida/modo", idToken);
                            String modo = modoRaw != null ? modoRaw.replace("\"", "").trim() : null;
                            if (modo != null) {
                                callback.accept(modo);
                            }
                        }
                    }
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    // =========================================================================
    // VIDAS (modo Yusa)
    // =========================================================================
    /**
     * Actualiza las vidas de un jugador específico en Firebase.
     *
     * @param codigoSala código de la sala
     * @param uid UID del jugador cuyas vidas se actualizan
     * @param vidas nuevo número de vidas (0 = eliminado)
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void actualizarVidaJugador(String codigoSala, String uid, int vidas, String idToken) throws IOException {
        db.actualizarNodo("salas/" + codigoSala + "/partida/vidas/" + uid, vidas, idToken);
    }

    /**
     * Actualiza el mapa completo de vidas de todos los jugadores en una
     * operación.
     *
     * <p>
     * Se usa al inicio de la partida para publicar las vidas iniciales de una
     * vez, en lugar de llamar a {@link #actualizarVidaJugador} jugador por
     * jugador.
     * </p>
     *
     * @param codigoSala código de la sala
     * @param vidas mapa UID - número de vidas
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void actualizarVidas(String codigoSala, Map<String, Integer> vidas, String idToken) throws IOException {
        db.actualizarNodo("salas/" + codigoSala + "/partida/vidas", vidas, idToken);
    }

    /**
     * Lee el mapa de vidas de todos los jugadores desde Firebase.
     *
     * <p>
     * Gson deserializa los números JSON como {@code Double} cuando el tipo de
     * destino es {@code Object}. La conversión
     * {@code ((Number) e.getValue()).intValue()} es necesaria para obtener un
     * {@code int} real. Si no, causaría {@code ClassCastException} .
     * </p>
     *
     * @param codigoSala código de la sala
     * @param idToken token de autenticación Firebase
     * @return mapa UID - vidas, o {@code null} si el nodo no existe
     * @throws IOException si la petición HTTP falla
     */
    public Map<String, Integer> leerVidas(String codigoSala, String idToken) throws IOException {
        String json = db.leerNodo("salas/" + codigoSala + "/partida/vidas", idToken);
        if (json == null || "null".equals(json)) {
            return null;
        }
        Map<String, Object> raw = gson.fromJson(json, Map.class);
        Map<String, Integer> resultado = new HashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            // ((Number) e.getValue()).intValue(): conversión segura de Double/Long a int
            resultado.put(e.getKey(), ((Number) e.getValue()).intValue());
        }
        return resultado;
    }

    /**
     * Crea un hilo de polling que escucha cambios en las vidas de un jugador
     * específico.
     *
     * <p>
     * Este listener solo observa el nodo de un único jugador
     * ({@code partida/vidas/{uid}}), lo que es más eficiente que escuchar todo
     * el nodo de vidas. Se usa en {@link ui.PartidaControllerYusa} para
     * detectar cuándo el jugador local llega a 0 vidas y mostrar el overlay de
     * eliminado.
     * </p>
     *
     * <p>
     * El {@code try/catch} de {@code NumberFormatException} cubre el caso de
     * que el valor en Firebase no sea un número entero válido.
     * </p>
     *
     * @param codigoSala código de la sala
     * @param uidJugador UID del jugador cuyas vidas se observan
     * @param idToken token de autenticación Firebase
     * @param callback función que recibe el nuevo número de vidas
     * @return hilo daemon del polling
     */
    public Thread escucharVidaJugador(String codigoSala, String uidJugador,
            String idToken, Consumer<Integer> callback) {
        return crearHiloPolling(() -> {
            String ultimo = null;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String val = db.leerNodo(
                            "salas/" + codigoSala + "/partida/vidas/" + uidJugador, idToken);
                    if (val != null && !val.equals(ultimo)) {
                        ultimo = val;
                        try {
                            int vidas = Integer.parseInt(val.trim()); // convertir String a int
                            callback.accept(vidas);
                        } catch (NumberFormatException ignored) { // Valor no numérico en Firebase: ignorar
                        }
                    }
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    // =========================================================================
    // YUSA - OBJETIVOS Y PALOS
    // =========================================================================
    /**
     * Guarda el objetivo elegido por un poseedor de yusa en Firebase.
     *
     * <p>
     * Escribe en {@code partida/yusa/objetivos/{uidPoseedor}} el UID del
     * jugador objetivo. El director lo lee en
     * {@link BDPartidaService#leerObjetivosYusa} para construir el mapa
     * {@code poseedor - objetivo}.
     * </p>
     *
     * @param codigoSala código de la sala
     * @param uidPoseedor UID del poseedor de yusa que eligió al objetivo
     * @param uidObjetivo UID del jugador elegido como objetivo
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void actualizarObjetivoYusa(String codigoSala, String uidPoseedor, String uidObjetivo, String idToken) throws IOException {
        db.actualizarNodo("salas/" + codigoSala + "/partida/yusa/objetivos/" + uidPoseedor, uidObjetivo, idToken);
    }

    /**
     * Lee el mapa de objetivos de yusa desde Firebase.
     *
     * <p>
     * Devuelve el mapa {@code poseedor - objetivo} guardado por los poseedores
     * en {@link #actualizarObjetivoYusa}. Se usa en
     * {@link ui.PartidaControllerYusa#procesarPaloRecibido} para identificar a
     * qué poseedor corresponde cada respuesta de palo cuando hay múltiples
     * yusas.
     * </p>
     *
     * @param codigoSala código de la sala
     * @param idToken token de autenticación Firebase
     * @return mapa poseedor - objetivo, o mapa vacío si el nodo no existe
     * @throws IOException si la petición HTTP falla
     */
    public Map<String, String> leerObjetivosYusa(String codigoSala, String idToken) throws IOException {
        String json = db.leerNodo("salas/" + codigoSala + "/partida/yusa/objetivos", idToken);
        if (json == null || json.equals("null")) {
            return new HashMap<>();
        }
        return gson.fromJson(json, Map.class);
    }

    /**
     * Borra el nodo de objetivos de yusa al finalizar la ronda. También borra
     * el nodo de marcados (sistema legado).
     *
     * @param codigoSala código de la sala
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void limpiarObjetivosYusa(String codigoSala, String idToken) throws IOException {
        db.borrarNodo("salas/" + codigoSala + "/partida/yusa/marcados", idToken);
    }

    /**
     * Borra el nodo de palos elegidos de yusa al finalizar la ronda.
     *
     * @param codigoSala código de la sala
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void limpiarPalosYusa(String codigoSala, String idToken) throws IOException {
        db.borrarNodo("salas/" + codigoSala + "/partida/yusa/palosElegidos", idToken);
    }

    /**
     * Borra el nodo {@code doceJugado} al finalizar la ronda. Se llama desde
     * {@link ui.PartidaControllerYusa#finalizarCicloRonda()}.
     *
     * @param codigoSala código de la sala
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void limpiarDoceJugado(String codigoSala, String idToken) throws IOException {
        db.borrarNodo("salas/" + codigoSala + "/partida/doceJugado", idToken);
    }

    // =========================================================================
    // ESTADO DE RONDA - YUSA
    // =========================================================================
    /**
     * Publica en Firebase el estado de la ronda de Yusa con todos sus campos.
     *
     * <p>
     * Este es el método central del sistema de comunicación de Yusa. El
     * director lo llama para ordenar a todos los clientes que realicen una
     * acción. El nodo publicado tiene esta estructura:
     * </p>
     *
     * <pre>
     * {
     *   "accion":   "ESPERANDO_DECISION",
     *   "fase":     "NORMAL",
     *   "turnoUid": "uid123",
     *   "ts":       1700000000000
     * }
     * </pre>
     *
     * <p>
     * {@code turnoUid == null ? "null" : turnoUid}: Firebase serializa
     * {@code null} Java como ausencia del campo. Para distinguir entre "sin
     * turnoUid" y "nadie tiene el turno" (p.ej. en REVELAR_CARTAS), se usa la
     * cadena {@code "null"} explícita.
     * </p>
     *
     * <p>
     * {@code System.currentTimeMillis()} como timestamp garantiza que cada
     * publicación tiene un valor diferente, permitiendo a los clientes detectar
     * cambios incluso cuando el contenido de los demás campos sea igual al
     * anterior.
     * </p>
     *
     * @param codigoSala código de la sala
     * @param accion nombre de la acción (p.ej. "ESPERANDO_DECISION",
     * "REVELAR_CARTAS")
     * @param fase nombre de la fase actual ("NORMAL", "DOCE", "YUSA")
     * @param turnoUid UID del jugador destinatario, o {@code null} para acción
     * global
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void publicarEstadoRonda(String codigoSala, String accion, String fase, String turnoUid, String idToken) throws IOException {
        Map<String, Object> estado = new HashMap<>();
        estado.put("accion", accion);
        estado.put("fase", fase);
        // Serializar null como cadena "null" para que Firebase guarde el campo
        estado.put("turnoUid", turnoUid == null ? "null" : turnoUid);
        // Timestamp como discriminador de cambios: garantiza que cada publicación es detectable
        estado.put("ts", System.currentTimeMillis());
        db.actualizarNodo("salas/" + codigoSala + "/partida/estadoRonda", estado, idToken);
    }

    /**
     * Borra el nodo {@code estadoRonda} al finalizar la ronda.
     *
     * <p>
     * Es importante borrar este nodo para que los clientes que reconecten no
     * procesen un estado obsoleto de la ronda anterior.
     * </p>
     *
     * @param codigoSala código de la sala
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void limpiarEstadoRonda(String codigoSala, String idToken) throws IOException {
        db.borrarNodo("salas/" + codigoSala + "/partida/estadoRonda", idToken);
    }

    /**
     * Crea un hilo de polling que escucha cambios en el estado de la ronda.
     *
     * <p>
     * Es el listener más crítico de Yusa: todos los clientes lo tienen activo
     * durante toda la partida. El intervalo de polling es de 200ms (en lugar de
     * los 500ms habituales) para minimizar la latencia de respuesta a las
     * acciones del director, que debe sentirse lo más reactiva posible.
     * </p>
     *
     * <p>
     * Cuando llega un nuevo estado, lo deserializa a
     * {@code Map<String, Object>} e invoca el callback. El controlador filtra
     * los duplicados comparando el timestamp ({@code ts}) para no procesar el
     * mismo estado dos veces.
     * </p>
     *
     * @param codigoSala código de la sala
     * @param idToken token de autenticación Firebase
     * @param callback función que recibe el mapa del estado (accion, fase,
     * turnoUid, ts)
     * @return hilo daemon del polling (200ms de intervalo)
     */
    public Thread escucharEstadoRonda(String codigoSala, String idToken, Consumer<Map<String, Object>> callback) {
        return crearHiloPolling(() -> {
            String ultimo = null;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String json = db.leerNodo("salas/" + codigoSala + "/partida/estadoRonda", idToken);
                    if (json != null && !json.equals(ultimo)) {
                        ultimo = json;
                        if (!"null".equals(json)) {
                            Map<String, Object> estado = gson.fromJson(json, Map.class);
                            if (estado != null) {
                                callback.accept(estado);
                            }
                        }
                    }
                    Thread.sleep(200); // 200ms: más frecuente que otros listeners por importancia
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    // =========================================================================
    // DECISIÓN JUGADOR (canal no-director - director)
    // =========================================================================
    /**
     * Publica la decisión de un jugador no-director en el canal de decisiones.
     *
     * <p>
     * Estructura del nodo publicado:
     * </p>
     *
     * <pre>
     * {
     *   "uid":      "uid_del_jugador",
     *   "decision": "QUEDAR" | "CAMBIAR" | "QUEDAR_ULTIMO" | "ROBAR_ULTIMO" | "PALO_CORONAS" | ...,
     *   "ts":       1700000000000
     * }
     * </pre>
     *
     * <p>
     * Solo hay un único nodo {@code decisionJugador} (no uno por jugador). Cada
     * jugador sobreescribe el mismo nodo cuando es su turno de decidir. El
     * director lo lee con {@link #escucharDecisionJugador}, lo procesa y luego
     * lo borra con {@link #limpiarDecisionJugador} para prepararlo para la
     * siguiente decisión.
     * </p>
     *
     * @param codigoSala código de la sala
     * @param uidJugador UID del jugador que toma la decisión
     * @param decision cadena de la decisión (p.ej. "QUEDAR", "PALO_CORONAS")
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void publicarDecisionJugador(String codigoSala, String uidJugador, String decision, String idToken) throws IOException {
        Map<String, Object> datos = new HashMap<>();
        datos.put("uid", uidJugador);
        datos.put("decision", decision);
        datos.put("ts", System.currentTimeMillis());
        db.actualizarNodo("salas/" + codigoSala + "/partida/decisionJugador", datos, idToken);
    }

    /**
     * Borra el nodo {@code decisionJugador} tras procesarla.
     *
     * <p>
     * El director llama a este método en
     * {@link ui.PartidaControllerYusa#procesarDecisionRecibida} justo al
     * recibir cada decisión, para evitar re-procesarla si el polling vuelve a
     * leer el mismo nodo antes de que el siguiente jugador publique la suya.
     * </p>
     *
     * @param codigoSala código de la sala
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void limpiarDecisionJugador(String codigoSala, String idToken) throws IOException {
        db.borrarNodo("salas/" + codigoSala + "/partida/decisionJugador", idToken);
    }

    /**
     * Crea un hilo de polling que escucha las decisiones de los jugadores
     * no-directores.
     *
     * <p>
     * Solo el director procesa este canal (lo comprueba en el controlador con
     * {@code uidLocal.equals(uidTurnoActual)}). El intervalo de polling es de
     * 200ms para minimizar la latencia de la respuesta del director a cada
     * decisión.
     * </p>
     *
     * @param codigoSala código de la sala
     * @param idToken token de autenticación Firebase
     * @param callback función que recibe el mapa de la decisión (uid, decision,
     * ts)
     * @return hilo daemon del polling (200ms de intervalo)
     */
    public Thread escucharDecisionJugador(String codigoSala, String idToken, Consumer<Map<String, Object>> callback) {
        return crearHiloPolling(() -> {
            String ultimo = null;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String json = db.leerNodo("salas/" + codigoSala + "/partida/decisionJugador", idToken);
                    if (json != null && !json.equals(ultimo)) {
                        ultimo = json;
                        if (!"null".equals(json)) {
                            Map<String, Object> datos = gson.fromJson(json, Map.class);
                            if (datos != null) {
                                callback.accept(datos);
                            }
                        }
                    }
                    Thread.sleep(200); // 200ms: respuesta rápida del director
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    // ---------------------------------------------------------
    // OTROS
    // ---------------------------------------------------------
    /**
     * Publica el número de ronda actual en Firebase para trazabilidad. Se llama
     * al final de cada ronda en
     * {@link ui.PartidaControllerYusa#incrementarNumeroRonda()}.
     *
     * @param codigoSala código de la sala
     * @param nuevaRonda número de la nueva ronda
     * @param idToken token de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void incrementarRonda(String codigoSala, int nuevaRonda, String idToken) throws IOException {
        db.actualizarNodo("salas/" + codigoSala + "/partida/ronda", nuevaRonda, idToken);
    }

}
