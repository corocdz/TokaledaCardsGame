package firebase;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Servicio encargado de gestionar la autenticación de usuarios mediante
 * Firebase Authentication.
 *
 * <p>
 * Esta clase actúa como una capa intermedia entre la aplicación y la API REST
 * de Firebase. Permite realizar operaciones de registro e inicio de sesión
 * enviando peticiones HTTP POST y procesando las respuestas JSON devueltas por
 * Firebase.
 * </p>
 *
 * <h2>Responsabilidades principales:</h2>
 * <ul>
 * <li>Registrar nuevos usuarios en Firebase Authentication.</li>
 * <li>Iniciar sesión con email y contraseña.</li>
 * <li>Interpretar los códigos de error devueltos por Firebase.</li>
 * <li>Almacenar el token de sesión y el UID del usuario autenticado.</li>
 * </ul>
 *
 * <h2>Importancia dentro del proyecto:</h2>
 * <p>
 * Este servicio es fundamental para el funcionamiento del modo online del
 * juego. Sin él, no sería posible autenticar usuarios, gestionar sesiones ni
 * acceder a la base de datos en tiempo real de Firebase.
 * </p>
 *
 * <h2>Decisiones de diseño:</h2>
 * <ul>
 * <li>Se utiliza la API REST de Firebase en lugar del SDK oficial para mantener
 * el proyecto ligero y sin dependencias externas.</li>
 * <li>Se emplea Gson para serializar y deserializar JSON de forma
 * sencilla.</li>
 * <li>Los métodos devuelven códigos de error personalizados para facilitar su
 * interpretación en los controladores de la interfaz.</li>
 * </ul>
 * 
 * @author Javier Coronilla Castellano
 */
public class FirebaseAuthService {

    /**
     * URL del endpoint de registro de Firebase Authentication.
     */
    private static final String SIGN_UP_URL
            = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=" + FirebaseConfig.API_KEY;

    /**
     * URL del endpoint de inicio de sesión de Firebase Authentication.
     */
    private static final String SIGN_IN_URL
            = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=" + FirebaseConfig.API_KEY;

    /**
     * Instancia de Gson utilizada para convertir objetos Java a JSON y
     * viceversa.
     */
    private final Gson gson = new Gson();

    /**
     * Token de sesión devuelto por Firebase tras un inicio de sesión o registro
     * exitoso.
     */
    private String idToken; // token de sesión

    /**
     * UID único del usuario autenticado.
     */
    private String localId; // UID del usuario

    /**
     * Método getter token.
     *
     * @return token de sesión actual
     */
    public String getIdToken() {
        return idToken;
    }

    /**
     * Método getter UID local.
     *
     * @return UID del usuario autenticado
     */
    public String getLocalId() {
        return localId;
    }

    // ------------------------------------------
    // ---------------- REGISTRO ----------------
    // ------------------------------------------
    /**
     * Registra un nuevo usuario en Firebase Authentication utilizando email y
     * contraseña.
     *
     * @param email Correo electrónico del usuario
     * @param password Contraseña del usuario
     * @return Código indicando el resultado del registro
     * @throws IOException Si ocurre un error de conexión al enviar la petición
     *
     * <h3>Códigos devueltos:</h3>
     * <ul>
     * <li>"OK" → registro correcto</li>
     * <li>"EMAIL_EXISTS" → el email ya está registrado</li>
     * <li>"INVALID_EMAIL" → formato de email incorrecto</li>
     * <li>"WEAK_PASSWORD" → contraseña demasiado débil</li>
     * <li>"MISSING_PASSWORD" → no se envió contraseña</li>
     * <li>"NETWORK_ERROR" → no hay conexión o Firebase no responde</li>
     * <li>"UNKNOWN_ERROR" → error inesperado</li>
     * </ul>
     */
    public String register(String email, String password) throws IOException {

        // Se crea una petición con los datos del registro y se corvierte a JSON
        AuthRequest request = new AuthRequest(email, password, true);
        String jsonRequest = gson.toJson(request);

        // Se envía la petición HTTP POST al endpoint de registro.
        String jsonResponse = postJson(SIGN_UP_URL, jsonRequest);

        // Si no hay respuesta, se asume un error de red.
        if (jsonResponse == null) {
            return "NETWORK_ERROR";
        }

        // Si la respuesta contiene un idToken, el registro fue exitoso.
        if (jsonResponse.contains("idToken")) {
            AuthResponse response = gson.fromJson(jsonResponse, AuthResponse.class);
            this.idToken = response.idToken;
            this.localId = response.localId;
            return "OK";
        }

        // Interpretación de errores de Firebase. Algunos de ellos nunca se ejecutarán ya que desde los controllers los manejamos de manera local. Las ponemos por si acaso.
        if (jsonResponse.contains("EMAIL_EXISTS")) {
            return "EMAIL_EXISTS";
        }
        if (jsonResponse.contains("INVALID_EMAIL")) {
            return "INVALID_EMAIL";
        }
        if (jsonResponse.contains("WEAK_PASSWORD")) {
            return "WEAK_PASSWORD";
        }
        if (jsonResponse.contains("MISSING_PASSWORD")) {
            return "MISSING_PASSWORD";
        }

        return "UNKNOWN_ERROR";
    }

    // ------------------------------------------
    // ---------------- LOGIN ----------------
    // ------------------------------------------
    /**
     * Inicia sesión en Firebase Authentication utilizando email y contraseña.
     *
     * @param email Correo electrónico del usuario
     * @param password Contraseña del usuario
     * @return Código indicando el resultado del login
     * @throws IOException Si ocurre un error de conexión al enviar la petición
     *
     * <h3>Códigos devueltos:</h3>
     * <ul>
     * <li>"OK" → login correcto</li>
     * <li>"CREDENCIALES_INCORRECTAS" → email inexistente o contraseña
     * incorrecta</li>
     * <li>"USER_DISABLED" → cuenta deshabilitada</li>
     * <li>"INVALID_EMAIL" → email con formato inválido</li>
     * <li>"NETWORK_ERROR" → error de conexión</li>
     * <li>"UNKNOWN_ERROR" → error inesperado</li>
     * </ul>
     */
    public String login(String email, String password) throws IOException {

        // Cuerpo de la petición con las credenciales del usuario.
        AuthRequest request = new AuthRequest(email, password, true);
        String jsonRequest = gson.toJson(request);

        // Se envía la petición al endpoint de login.
        String jsonResponse = postJson(SIGN_IN_URL, jsonRequest);

        // Si no hay respuesta, se asume un error de red.
        if (jsonResponse == null) {
            return "NETWORK_ERROR";
        }

        // Si la respuesta contiene un idToken, el login fue exitoso.
        if (jsonResponse.contains("idToken")) {
            AuthResponse response = gson.fromJson(jsonResponse, AuthResponse.class);
            this.idToken = response.idToken;
            this.localId = response.localId;
            return "OK";
        }

        // Interpretación de errores de Firebase. Algunos de ellos nunca se ejecutarán ya que desde los controllers los manejamos de manera local. Las ponemos por si acaso.
        if (jsonResponse.contains("INVALID_LOGIN_CREDENTIALS")) {
            return "CREDENCIALES_INCORRECTAS";
        }

        if (jsonResponse.contains("USER_DISABLED")) {
            return "USER_DISABLED";
        }
        if (jsonResponse.contains("INVALID_EMAIL")) {
            return "INVALID_EMAIL";
        }

        if (jsonResponse.contains("INVALID_LOGIN_CREDENTIALS")) {
            return "INVALID_PASSWORD";
        }

        return "UNKNOWN_ERROR";
    }

    // ------------------------------------------------
    // ---------------- HTTP POST JSON ----------------
    // ------------------------------------------------
    /**
     * Envía una petición HTTP POST con un cuerpo JSON a la URL indicada.
     *
     * @param urlString URL del endpoint de Firebase
     * @param jsonBody Cuerpo JSON que se enviará en la petición
     * @return Respuesta JSON devuelta por Firebase, o {@code null} si ocurre un
     * error de red
     * @throws IOException Si falla la conexión o la escritura del cuerpo
     *
     * <p>
     * Este método encapsula toda la lógica de comunicación HTTP, librando a la
     * lógica de login y registro de usuarios de ello.
     * </p>
     */
    private String postJson(String urlString, String jsonBody) throws IOException {

        // Construimos un objeto URL a partir de la cadena recibida.
        URL url = new URL(urlString);

        // Abrimos conexión HTTP con la que enviaremos la petición.
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        // Queremos enviar datos, por lo que la petición será POST.
        conn.setRequestMethod("POST");

        // Cabecera necesaria para que Firebase interprete correctamente la petición.
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true); // Enviamos datos en el cuerpo.

        // Enviamos el cuerpo JSON al servidor. Uso de try-with-resources para cerrar automáticamente el OutputStream.
        try (OutputStream os = conn.getOutputStream()) { // OutputStream con la conexión HTTP que hicimos antes.
            byte[] input = jsonBody.getBytes("utf-8");
            os.write(input, 0, input.length); // Escribimos los bytes en el cuerpo de la petición.
        }

        // Obtenemos el código HTTP devuelto por Firebase.
        int code = conn.getResponseCode();

        // Es necesario que sea del formato 2xx, si no, es un error.
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream(); // si code es 200-299 leemos la respuesta normal. Si no, leemor el flujo del error.

        // Leemos la respuesta con BufferedReader.
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "utf-8"))) {

            // Utilizamos StringBuilder para construir la respuesta desde BufferedReader
            StringBuilder response = new StringBuilder();
            String line;

            // Concatenamos cada línea en un único String. Devuelto en formato JSON por Firebase.
            while ((line = br.readLine()) != null) {
                response.append(line.trim()); // JSON limpio sin espacios innecesarios.
            }

            // Devolvemos JSON como String.
            return response.toString();
        }
    }

    // ---------------------------------------------------
    // ---------------- CLASES AUXILIARES ----------------
    // ---------------------------------------------------
    /**
     *
     * Clase estática que representa el cuerpo JSON que se envia a Firebase
     * Authentication en las peticiones de registro e inicio de sesión.
     *
     * <h2>Importancia dentro del flujo de autenticación:</h2>
     * <ul>
     * <li>Permite construir de forma segura y estructurada el cuerpo de la
     * petición.</li>
     * <li>Evita errores al escribir manualmente el JSON.</li>
     * <li>Garantiza compatibilidad con los requisitos de la API REST de
     * Firebase.</li>
     * </ul>
     *
     * <h2>Decisiones de diseño:</h2>
     * <ul>
     * <li>La clase es <code>static</code> y privada porque solo se utiliza
     * dentro de <code>FirebaseAuthService</code>.</li>
     * <li>No tiene getters ni setters porque su único propósito es ser
     * serializada.</li>
     * <li>Se usa la anotación <code>@SerializedName</code> para mapear nombres
     * exactos requeridos por Firebase.</li>
     * </ul>
     */
    private static class AuthRequest {

        /**
         * Email del usuario que se enviará a Firebase.
         */
        String email;

        /**
         * Contraseña del usuario que se enviará a Firebase.
         */
        String password;

        /**
         * Indicamos a Firebase que debe devolver un token de sesión completo.
         *
         * Firebase requiere de este campo. Sin él o siendo false, Firebase no
         * devolverá credenciales válidas para mantener sesión.
         */
        @SerializedName("returnSecureToken")
        boolean returnSecureToken;

        /**
         * Constructor que inicializa los datos necesarios para la petición.
         *
         * @param email Email del usuario
         * @param password Contraseña del usuario
         * @param returnSecureToken Indica si Firebase debe devolver un token de
         * sesión
         */
        AuthRequest(String email, String password, boolean returnSecureToken) {
            this.email = email;
            this.password = password;
            this.returnSecureToken = returnSecureToken;
        }
    }

    /**
     * Representa la respuesta JSON devuelta por Firebase Authentication cuando
     * un registro o inicio de sesión se realiza correctamente.
     *
     * <h2>Importancia dentro del flujo de autenticación:</h2>
     * <ul>
     * <li>Permite extraer de forma segura el token de sesión devuelto por
     * Firebase.</li>
     * <li>Proporciona el UID necesario para acceder a la base de datos del
     * usuario.</li>
     * <li>Evita tener que manipular manualmente cadenas JSON.</li>
     * </ul>
     *
     * <h2>Decisiones de diseño:</h2>
     * <ul>
     * <li>La clase es privada y estática porque solo se usa dentro del
     * servicio.</li>
     * <li>Los campos coinciden exactamente con los nombres JSON de Firebase,
     * gracias a <code>@SerializedName</code>.</li>
     * <li>No se incluyen setters porque los valores solo se leen, nunca se
     * modifican.</li>
     * </ul>
     */
    private static class AuthResponse {

        /**
         * Token de sesión devuelto por Firebase.
         */
        @SerializedName("idToken")
        String idToken; // Permite autenticar futuras peticiones a la base de datos. Tiene una validez temporal.

        /**
         * UID único del usuario dentro de Firebase Authentication.
         */
        @SerializedName("localId")
        String localId; // Este identificador se utiliza para acceder al nodo correspondiente en Firebase.

        /**
         * Email asociado al cuenta autenticada.
         */
        @SerializedName("email")
        String email; // Firebase lo devuelve como confirmación de que la autenticación se realizó correctamente.
    }
}
