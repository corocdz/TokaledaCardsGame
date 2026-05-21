package firebase;

import com.google.gson.Gson;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * Servicio HTTP de bajo nivel para comunicarse con Firebase Realtime Database.
 *
 * <p>
 * Esta clase implementa directamente las cuatro operaciones de la
 * <strong>API REST de Firebase Realtime Database</strong>: GET, PUT, PATCH y
 * DELETE. Es la capa más baja del sistema de acceso a datos: no conoce el
 * dominio del juego ni la estructura de los nodos, solo sabe construir
 * peticiones HTTP y leer respuestas.</p>
 *
 * <h2>Por qué HTTP en lugar del SDK nativo de Firebase</h2>
 * <p>
 * El SDK oficial de Firebase para Java está pensado para aplicaciones Android o
 * servidores, no para aplicaciones de escritorio JavaFX. En lugar de añadir una
 * dependencia compleja y difícil de configurar, esta clase usa la API REST que
 * Firebase expone públicamente a través de HTTP, lo que permite:</p>
 * <ul>
 * <li>Control total sobre las peticiones sin dependencias externas
 * adicionales.</li>
 * <li>Funcionamiento en cualquier JVM sin configuración especial.</li>
 * <li>Depuración sencilla: cada petición es una URL estándar que se puede
 * probar incluso desde el navegador.</li>
 * </ul>
 *
 * <h2>Cómo funciona la API REST de Firebase</h2>
 * <p>
 * Cada nodo del árbol JSON de Firebase tiene una URL única con el formato:</p>
 * <pre>
 * https://{proyecto}.firebaseio.com/{ruta/al/nodo}.json?auth={idToken}
 * </pre>
 * <p>
 * Las operaciones son:</p>
 * <ul>
 * <li><strong>GET</strong>: leer el nodo y todo su subárbol como JSON.</li>
 * <li><strong>PUT</strong>: escribir (reemplazar) el nodo completo con el JSON
 * enviado.</li>
 * <li><strong>PATCH</strong>: actualizar solo los campos enviados sin borrar el
 * resto.</li>
 * <li><strong>DELETE</strong>: borrar el nodo y todo su subárbol.</li>
 * </ul>
 * <p>
 * El parámetro {@code ?auth=idToken} autentica la petición usando el token JWT
 * del usuario activo, obtenido por {@link ui.MainApp} tras el login con
 * Firebase Auth.</p>
 *
 * <h2>Relación con BDPartidaService</h2>
 * <p>
 * Esta clase no la usan directamente los controladores de partida: ellos
 * acceden a través de {@link BDPartidaService}, que añade una capa semántica de
 * alto nivel (sabe qué rutas corresponden a manos, baraja, turno, etc.). Esta
 * clase solo conoce URLs y cuerpos JSON.</p>
 *
 * <h2>Métodos sin uso aparente</h2>
 * <ul>
 * <li>{@link #patchJson(String, String)}: el método PATCH privado fue
 * reemplazado por PUT en {@link #actualizarNodo}. Puede eliminarse.</li>
 * <li>{@link #actualizarUsuario(String, Map, String)}: duplica funcionalidad
 * con {@link #actualizarCamposUsuario(String, Map, String)}. Puede
 * eliminarse.</li>
 * <li>{@link #guardarSala(String, Map, String)}: no se llama desde ningún
 * controlador en los archivos del proyecto. Verificar si sigue siendo
 * necesario.</li>
 * </ul>
 *
 * @author Javier Coronilla Castellano
 */
public class FirebaseDatabaseService {

    /**
     * Instancia de Gson para serializar objetos Java a JSON y viceversa. Gson
     * es thread-safe en operaciones de serialización, por lo que una sola
     * instancia compartida es suficiente para todos los métodos de la clase.
     */
    private final Gson gson = new Gson();

    // =========================================================================
    //  OPERACIONES SOBRE USUARIOS
    // =========================================================================
    /**
     * Crea o reemplaza el nodo completo de un usuario en Firebase.
     *
     * <p>
     * Construye la URL {@code usuarios/{uid}.json?auth={token}} y hace una
     * petición PUT con el JSON de {@code datosUsuario}. El PUT reemplaza
     * completamente el nodo: si el usuario ya tenía datos, se
     * sobreescriben.</p>
     *
     * <p>
     * Se usa internamente desde
     * {@link #actualizarCamposUsuario(String, Map, String)} tras fusionar los
     * datos nuevos con los existentes. Así se simula un PATCH seguro: leer -
     * fusionar - escribir todo con PUT.</p>
     *
     * @param uid UID del usuario cuyo nodo se crea o reemplaza
     * @param datosUsuario mapa con los campos a guardar (p.ej. nombre,
     * conectado, salaActual)
     * @param idToken token JWT de autenticación Firebase del usuario actual
     * @throws IOException si la petición HTTP falla
     */
    public void guardarUsuario(String uid, Map<String, Object> datosUsuario, String idToken) throws IOException {
        String url = FirebaseConfig.DATABASE_URL + "usuarios/" + uid + ".json?auth=" + idToken;
        String jsonBody = gson.toJson(datosUsuario);
        putJson(url, jsonBody);
    }

    /**
     * Actualiza campos específicos de un usuario leyendo primero el estado
     * actual, fusionando los cambios y reescribiendo el nodo completo con PUT.
     *
     * <p>
     * Firebase Realtime Database REST soporta PATCH para actualizaciones
     * parciales, pero en la práctica usar PUT tras leer-y-fusionar es más
     * predecible y evita condiciones de carrera con claves anidadas.</p>
     *
     * <p>
     * Proceso en 3 pasos:</p>
     * <ol>
     * <li>Leer el nodo {@code usuarios/{uid}} con {@link #leerNodo}.</li>
     * <li>Deserializar el JSON a un {@code Map} con Gson y fusionar los nuevos
     * datos encima ({@code base.putAll(nuevosDatos)}): las claves nuevas se
     * añaden y las existentes se sobreescriben, pero las no mencionadas se
     * conservan.</li>
     * <li>Guardar el mapa fusionado completo con {@link #guardarUsuario}.</li>
     * </ol>
     *
     * <p>
     * La comprobación {@code jsonActual != null && !jsonActual.equals("null")}
     * cubre el caso en que el nodo no existe en Firebase: la API REST devuelve
     * la cadena literal {@code "null"} (no {@code null} Java) cuando un nodo no
     * existe. En ese caso, {@code base} empieza como mapa vacío y solo contiene
     * {@code nuevosDatos}.</p>
     *
     * @param uid UID del usuario a actualizar
     * @param nuevosDatos mapa con los campos que se quieren cambiar o añadir
     * @param idToken token JWT de autenticación Firebase
     * @throws IOException si alguna petición HTTP falla
     */
    public void actualizarCamposUsuario(String uid, Map<String, Object> nuevosDatos, String idToken) throws IOException {
        // Leer el estado actual del nodo para no perder datos existentes
        String jsonActual = leerNodo("usuarios/" + uid, idToken);

        Map<String, Object> base = new java.util.HashMap<>();
        // Deserializar el JSON actual a un Map genérico
        // Gson usará Map.class como tipo de destino - valores pueden ser String, Double, Map, List
        if (jsonActual != null && !jsonActual.equals("null")) {
            // Deserializar el JSON actual a un Map genérico
            // Gson usará Map.class como tipo de destino - valores pueden ser String, Double, Map, List
            base = gson.fromJson(jsonActual, Map.class);
            if (base == null) {
                base = new java.util.HashMap<>();
            }
        }

        //  Fusionar: putAll sobreescribe claves existentes y añade las nuevas
        //  Las claves no mencionadas en nuevosDatos se conservan del base
        base.putAll(nuevosDatos);

        // Guardar el mapa completo con PUT (reemplaza el nodo entero)
        guardarUsuario(uid, base, idToken);
    }

    // =========================================================================
    //  OPERACIONES SOBRE SALAS
    // =========================================================================
    /**
     * Crea o reemplaza el nodo completo de una sala en Firebase.
     *
     * <p>
     * Construye la URL {@code salas/{codigo}.json?auth={token}} y hace una
     * petición PUT. Reutiliza {@link #putJson(String, String)} exactamente
     * igual que {@link #guardarUsuario(String, Map, String)}.</p>
     *
     * <p>
     * <strong>Nota:</strong> no aparece llamado directamente desde los
     * controladores actuales. La sala se crea desde la pantalla de unirse/crear
     * sala. Verificar si sigue siendo necesario o puede eliminarse.</p>
     *
     * @param codigo código único de la sala (p.ej. "ABCD12")
     * @param datosSala mapa con los campos de la sala (host, jugadores, etc.)
     * @param idToken token JWT de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void guardarSala(String codigo, Map<String, Object> datosSala, String idToken) throws IOException {
        String url = FirebaseConfig.DATABASE_URL + "salas/" + codigo + ".json?auth=" + idToken;
        String jsonBody = gson.toJson(datosSala);
        putJson(url, jsonBody);   // reutiliza el mismo método que para usuarios
    }

    // =========================================================================
    //  OPERACIONES GENÉRICAS (usadas por BDPartidaService)
    // =========================================================================
    /**
     * Lee el contenido JSON de cualquier nodo del árbol de Firebase.
     *
     * <p>
     * Construye la URL {@code {DATABASE_URL}{ruta}.json?auth={token}} y hace
     * una petición GET. Devuelve el JSON tal cual lo devuelve Firebase,
     * incluidas las comillas dobles alrededor de los strings primitivos (p.ej.
     * {@code "\"valor\""}). El llamante es responsable de deserializarlo o
     * limpiarlo.</p>
     *
     * <p>
     * Si el nodo no existe, Firebase devuelve la cadena literal {@code "null"}
     * (no {@code null} Java). Los llamantes deben comprobar
     * {@code json != null && !json.equals("null")} antes de procesar.</p>
     *
     * <p>
     * Lo usan {@link BDPartidaService} para los listeners de polling,
     * {@link ui.SalaOnlineController} para refrescar la sala cada segundo, y
     * múltiples controladores para leer el estado inicial.</p>
     *
     * @param ruta ruta del nodo en Firebase, sin la URL base ni el
     * {@code .json} (p.ej. {@code "salas/ABCD12/partida/turno"})
     * @param idToken token JWT de autenticación Firebase
     * @return cadena JSON con el contenido del nodo, o {@code "null"} si no
     * existe
     * @throws IOException si la petición HTTP falla
     */
    public String leerNodo(String ruta, String idToken) throws IOException {
        String url = FirebaseConfig.DATABASE_URL + ruta + ".json?auth=" + idToken;
        return getJson(url);
    }

    /**
     * Escribe o reemplaza cualquier nodo del árbol de Firebase con el valor
     * indicado.
     *
     * <p>
     * Serializa {@code valor} a JSON con Gson y hace una petición PUT a la URL
     * {@code {DATABASE_URL}{ruta}.json?auth={token}}. El PUT reemplaza
     * completamente el nodo: si tenía hijos, se borran y se reemplazan por el
     * nuevo valor.</p>
     *
     * <p>
     * El parámetro {@code valor} puede ser cualquier tipo serializable por
     * Gson:</p>
     * <ul>
     * <li>{@code String}, {@code Integer}, {@code Boolean}: se serializan como
     * primitivos JSON.</li>
     * <li>{@code Map<String, Object>}: se serializa como objeto JSON.</li>
     * <li>{@code List<String>}: se serializa como array JSON.</li>
     * </ul>
     *
     * @param ruta ruta del nodo en Firebase (p.ej.
     * {@code "salas/ABCD12/partida/turno"})
     * @param valor valor a escribir; puede ser String, int, boolean, Map o List
     * @param idToken token JWT de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void actualizarNodo(String ruta, Object valor, String idToken) throws IOException {
        String url = FirebaseConfig.DATABASE_URL + ruta + ".json?auth=" + idToken;
        String jsonBody = gson.toJson(valor);
        putJson(url, jsonBody);
    }

    /**
     * Borra un nodo y todo su subárbol del árbol de Firebase.
     *
     * <p>
     * Hace una petición DELETE a la URL
     * {@code {DATABASE_URL}{ruta}.json?auth={token}}. Firebase borra el nodo
     * indicado y todos sus hijos recursivamente. Es equivalente a escribir
     * {@code null} con PUT.</p>
     *
     * <p>
     * Se usa extensivamente en el proyecto para limpiar el estado al finalizar
     * rondas: borrar {@code estadoRonda}, {@code decisionJugador},
     * {@code yusa/objetivos}, etc. También para expulsar jugadores, disolver
     * salas y gestionar desconexiones.</p>
     *
     * <p>
     * El bloque try-with-resources lee la respuesta del servidor para que la
     * conexión se cierre correctamente. Si no se leyera el stream, la JVM
     * podría dejar conexiones HTTP abiertas y agotar el pool de conexiones.</p>
     *
     * @param ruta ruta del nodo a borrar (p.ej.
     * {@code "salas/ABCD12/partida/estadoRonda"})
     * @param idToken token JWT de autenticación Firebase
     * @throws IOException si la petición HTTP falla
     */
    public void borrarNodo(String ruta, String idToken) throws IOException {
        String urlString = FirebaseConfig.DATABASE_URL + ruta + ".json?auth=" + idToken;
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod("DELETE");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "utf-8"))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line.trim());
            }
            System.out.println("DELETE respuesta: " + response);
        }
    }

    // =========================================================================
    //  MÉTODOS HTTP PRIVADOS
    // =========================================================================
    /**
     * Ejecuta una petición HTTP PUT enviando un cuerpo JSON y lee la respuesta.
     *
     * <p>
     * Este método es el núcleo de todas las escrituras en Firebase. Lo usan
     * {@link #guardarUsuario}, {@link #guardarSala} y
     * {@link #actualizarNodo}.</p>
     *
     * <p>
     * Pasos de la petición:</p>
     * <ol>
     * <li>{@code new URL(urlString)}: crea el objeto URL a partir de la cadena.
     * Lanza {@link java.net.MalformedURLException} si la URL no es válida.</li>
     * <li>{@code url.openConnection()}: crea la conexión HTTP sin enviarla aún.
     * Se castea a {@link HttpURLConnection} para acceder a métodos HTTP.</li>
     * <li>{@code conn.setRequestMethod("PUT")}: establece el verbo HTTP.</li>
     * <li>{@code conn.setDoOutput(true)}: indica que se va a enviar un cuerpo
     * en la petición (necesario para PUT/POST; por defecto es false).</li>
     * <li>{@code conn.getOutputStream()}: abre el canal de escritura y envía la
     * petición. Hasta esta línea, la conexión no se ha abierto realmente.</li>
     * <li>{@code jsonBody.getBytes("utf-8")}: convierte el String JSON a bytes
     * UTF-8 para enviarlo por el canal de bytes del OutputStream.</li>
     * <li>{@code conn.getResponseCode()}: bloquea hasta recibir la respuesta
     * HTTP del servidor y devuelve el código (200 OK, 401 Unauthorized,
     * etc.).</li>
     * <li>La selección de stream: {@code getInputStream()} para respuestas 2xx
     * (éxito), {@code getErrorStream()} para errores 4xx/5xx. Si se usa
     * {@code getInputStream()} en un error, la JVM lanza IOException.</li>
     * <li>try-with-resources con {@link BufferedReader}: lee la respuesta línea
     * a línea y la muestra por consola. El try-with-resources garantiza que el
     * stream se cierra aunque ocurra una excepción.</li>
     * </ol>
     *
     * @param urlString URL completa con ruta, {@code .json} y
     * {@code ?auth=token}
     * @param jsonBody cuerpo JSON a enviar como cadena
     * @throws IOException si la conexión falla, la URL es inválida o hay error
     * HTTP
     */
    private void putJson(String urlString, String jsonBody) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true); // necesario para PUT: indica que se envía cuerpo

        // Abrir el OutputStream y escribir el cuerpo JSON como bytes UTF-8
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonBody.getBytes("utf-8");
            os.write(input, 0, input.length);
        }
        // getOutputStream() envía la petición implícitamente; getResponseCode() espera la respuesta

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "utf-8"))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line.trim());
            }
            System.out.println("Respuesta DB: " + response);
        }
    }

    /**
     * Ejecuta una petición HTTP GET y devuelve la respuesta como cadena JSON.
     *
     * <p>
     * A diferencia de {@link #putJson(String, String)}, una petición GET no
     * tiene cuerpo, por lo que no se llama a {@code setDoOutput(true)} ni se
     * abre el OutputStream. La petición se envía implícitamente al llamar a
     * {@code conn.getResponseCode()}.</p>
     *
     * <p>
     * El {@link StringBuilder} acumula las líneas de la respuesta. Se usa en
     * lugar de un simple {@code String} porque la concatenación de strings en
     * un bucle ({@code response = response + line}) crea un objeto String nuevo
     * en cada iteración, mientras que {@code StringBuilder.append} modifica el
     * mismo objeto en memoria.</p>
     *
     * <p>
     * Firebase devuelve el JSON en una sola línea normalmente, pero el bucle
     * {@code readLine()} cubre el caso de respuestas multilínea por si el
     * servidor las formatea con saltos de línea (p.ej. en modo debug).</p>
     *
     * @param urlString URL completa con ruta, {@code .json} y
     * {@code ?auth=token}
     * @return cadena JSON con la respuesta de Firebase, o {@code "null"} si el
     * nodo no existe
     * @throws IOException si la conexión falla o hay error HTTP
     */
    private String getJson(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod("GET");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "utf-8"))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line.trim());
            }
            return response.toString(); // devolver el JSON completo como String
        }
    }

}
