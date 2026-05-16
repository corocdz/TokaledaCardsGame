package firebase;

/**
 * Clase de configuración centralizada para los servicios de Firebase utilizados
 * por la aplicación.
 *
 * <p>
 * Esta clase actúa como un contenedor estático de constantes necesarias para
 * establecer la comunicación con Firebase Authentication y Firebase Realtime
 * Database. Su función principal es proporcionar un único punto de acceso a las
 * claves y URLs del proyecto, evitando la duplicación de valores y facilitando
 * el mantenimiento.
 * </p>
 *
 * <h2>Responsabilidades:</h2>
 * <ul>
 * <li>Almacenar la API Key del proyecto de Firebase.</li>
 * <li>Almacenar la URL base de la base de datos en tiempo real.</li>
 * <li>Proporcionar acceso centralizado a estos valores para todos los servicios
 * Firebase.</li>
 * </ul>
 *
 * <h2>Importancia dentro del proyecto:</h2>
 * <p>
 * Esta clase permite desacoplar la configuración del resto del código,
 * facilitando cambios futuros (por ejemplo, migrar a otro proyecto de Firebase
 * o modificar la estructura de URLs). Además, mejora la claridad del código al
 * evitar que estas constantes aparezcan repetidas en múltiples clases.
 * </p>
 *
 * <h2>Decisiones de diseño:</h2>
 * <ul>
 * <li>Se utiliza una clase con constantes estáticas para centralizar la
 * configuración de Firebase y garantizar que estos valores no se modifiquen en
 * tiempo de ejecución.</li>
 * <li>La clase no tiene constructor porque no debe instanciarse.</li>
 * </ul>
 *
 * @author Javier Coronilla Castellano
 */
public class FirebaseConfig {

    /**
     * API Key de Firebase utilizada para autenticar peticiones.
     */
    public static final String API_KEY = "AIzaSyCd9Qm9s3jz0_CBmNnyQfrvyMETaxICUcs";

    /**
     * URL base de la Firebase Realtime Database.
     */
    public static final String DATABASE_URL = "https://tokaleda-cards-game-default-rtdb.europe-west1.firebasedatabase.app/";

}
