package i18n;

import java.net.URL;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;
import javafx.scene.image.Image;

/**
 * Gestor centralizado del idioma de la aplicación.
 *
 * <p>
 * Esta clase se encarga de:
 * </p>
 *
 * <h2>Responsabilidades principales:</h2>
 * <ul>
 * <li>Determinar el idioma actual de la aplicación mediante
 * {@link Locale}.</li>
 * <li>Cargar el {@link ResourceBundle} correspondiente a dicho idioma.</li>
 * <li>Persistir la preferencia de idioma usando {@link Preferences}.</li>
 * <li>Cargar imágenes específicas según el idioma (botones traducidos).</li>
 * <li>Resolver textos traducidos con soporte para parámetros.</li>
 * </ul>
 *
 * <p>
 * Su uso es estático, actuando como un punto único de acceso para cualquier
 * parte de la interfaz que necesite textos o recursos dependientes del idioma.
 * </p>
 * 
 * @author Javier Coronilla Castellano.
 */
public class IdiomaManager {

    /**
     * Locale actual de la aplicación, que determina el idioma activo.
     */
    private static Locale localeActual;

    // Bloque estático: se ejecuta una única vez cuando se carga la clase.
    static {
        // Obtenemos el nodo de preferencias asociado a esta clase.
        Preferences prefs = Preferences.userNodeForPackage(IdiomaManager.class);

        // Leemos el idioma guardado bajo la clave "idioma".
        // Si no existe, se usa "es" (español) como valor por defecto.
        String idiomaGuardado = prefs.get("idioma", "es");

        // Creamos el locale a partir del código del idioma recuperado.
        localeActual = new Locale(idiomaGuardado);
    }

    /**
     * Establece el idioma actual de la aplicación y lo guarda en preferencias.
     *
     * <p>
     * Este método actualiza el {@link Locale} interno y persiste el código de
     * idioma usando {@link Preferences}, de forma que al reiniciar la
     * aplicación se mantenga la elección del usuario.
     * </p>
     *
     * @param codigo Código de idioma (por ejemplo, {@code "es"} o
     * {@code "en"}).
     */
    public static void setIdioma(String codigo) {

        // Actualizamos el locale con el nuevo código de idioma.
        localeActual = new Locale(codigo);

        // Guardamos la preferencia de idioma para futuras ejecuciones.
        Preferences prefs = Preferences.userNodeForPackage(IdiomaManager.class);
        prefs.put("idioma", codigo);
    }

    /**
     * Devuelve el {@link ResourceBundle} asociado al idioma actual.
     *
     * <p>
     * El bundle se carga a partir del archivo base {@code i18n.messages},
     * utilizando el {@link Locale} almacenado en {@link #localeActual}.
     * </p>
     *
     * @return ResourceBundle con los textos traducidos para el idioma actual.
     */
    public static ResourceBundle getBundle() {
        return ResourceBundle.getBundle("i18n.messages", localeActual);
    }

    /**
     * Obtiene el código del idioma actual.
     *
     * <p>
     * Este método devuelve el código de idioma del {@link Locale} activo, por
     * ejemplo {@code "es"} o {@code "en"}. Pensado para tomar decisiones
     * condicionales en la interfaz según el idioma.
     * </p>
     *
     * @return Código de idioma actual (por ejemplo, {@code "es"} o
     * {@code "en"}).
     */
    public static String getCodigoIdioma() {
        return localeActual.getLanguage();
    }

    /**
     * Carga una imagen dependiente del idioma a partir de un nombre base.
     *
     * <p>
     * Para la mayoría de botones, se sigue la convención:
     * </p>
     * <ul>
     * <li>Español: {@code nombreBase.png}</li>
     * <li>Inglés: {@code nombreBaseEN.png}</li>
     * </ul>
     *
     * <p>
     * Existe una excepción para el botón de idioma ({@code btnIdioma}), que
     * funciona al revés: si el idioma actual es español, se muestra la imagen
     * del botón en inglés, y viceversa, para indicar al usuario el idioma al
     * que cambiará.
     * </p>
     *
     * @param nombreBase Nombre base del recurso de imagen (sin sufijo ni
     * extensión).
     * @return Imagen cargada desde el classpath.
     * @throws RuntimeException Si la imagen no se encuentra en la ruta
     * esperada.
     */
    public static Image cargarImagen(String nombreBase) {

        // Obtenemos el código de idioma actual (es, en).
        String lang = getCodigoIdioma();

        // EXCEPCIÓN: botón de idioma funciona al revés.
        if (nombreBase.equals("btnIdioma")) {
            // Si el idioma actual es el español, mostramos la imagen EN (que muestra la bandera de reino unido para simbolizar el cambio a inglés).
            // Si el idioma actual es el inglés, mostramos la imagen ES (que muestra la bandera de españa para simbolizar el cambio a español).
            String archivo = lang.equals("es") ? "btnIdiomaEN.png" : "btnIdioma.png";
            String rutaEspecial = "/ui/graphicResources/imagenes/" + archivo; // ruta completa hacia el botón que queremos.

            // Buscamos recurso en el classpath.
            URL urlEspecial = IdiomaManager.class.getResource(rutaEspecial);
            if (urlEspecial == null) { // Si no se encuentra, mostramos error.
                System.err.println("Imagen NO encontrada: " + rutaEspecial);
                throw new RuntimeException("Imagen NO encontrada: " + rutaEspecial);
            }

            // Devolvemos la imagen construida a partir de la URL
            return new Image(urlEspecial.toExternalForm());
        }

        // RESTO DE BOTONES: regla normal; botones en inglés acabados en EN y los españoles sin sufijo.
        String sufijo = lang.equals("en") ? "EN" : ""; // si idioma es inglés, usarmos el sufijo de inglés, si no, no hay sufijo.
        String ruta = "/ui/graphicResources/imagenes/" + nombreBase + sufijo + ".png"; // ruta completa al botón con el nombre base + sufijo.

        // Buscamos recurso en el classpath.
        URL url = IdiomaManager.class.getResource(ruta);
        if (url == null) { // Si no se encuentra, mostramos error.
            System.err.println("Imagen NO encontrada: " + ruta);
            throw new RuntimeException("Imagen no encontrada: " + ruta);
        }

        // devolvemos la imagen construida a partir de la URL.
        return new Image(url.toExternalForm());
    }

    /**
     * Método que obtiene un texto traducido a partir de una clave, con soporte
     * para parámetros.
     *
     * <p>
     * Este método busca la clave en el {@link ResourceBundle} actual y, si se
     * proporcionan parámetros, aplica {@link java.text.MessageFormat} para
     * formatear el texto. En caso de que la clave no exista, devuelve una
     * cadena de marcador con el formato {@code ???clave???} y escribe un
     * mensaje de error en la salida estándar de error.
     * </p>
     *
     * @param clave Clave del texto en el archivo {@code messages.properties}.
     * @param params Parámetros opcionales para formatear el texto.
     * @return Texto traducido, formateado si hay parámetros, o marcador de
     * error si la clave no existe.
     */
    public static String get(String clave, Object... params) {
        try {

            // Obtenemos el bundle correspondiente al idioma actual.
            ResourceBundle bundle = getBundle();

            // Recuperamos el texto asociado a la clave.
            String texto = bundle.getString(clave);

            // Si se han pasado parámetros, formateamos texto con ellos.
            if (params != null && params.length > 0) {
                return java.text.MessageFormat.format(texto, params);
            }

            // Si no hay parámetros, devolvemos el texto tal cual.
            return texto;

        } catch (Exception e) { // En caso de excepciones, las capturamos.
            System.err.println("Clave no encontrada en properties: " + clave);
            return "???" + clave + "???";
        }
    }

}
