package i18n;

import java.net.URL;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;
import javafx.scene.image.Image;

public class IdiomaManager {

    private static Locale localeActual;

    static {
        // Cargar idioma guardado o español por defecto
        Preferences prefs = Preferences.userNodeForPackage(IdiomaManager.class);
        String idiomaGuardado = prefs.get("idioma", "es");
        localeActual = new Locale(idiomaGuardado);
    }

    public static void setIdioma(String codigo) {
        localeActual = new Locale(codigo);

        // Guardar preferencia
        Preferences prefs = Preferences.userNodeForPackage(IdiomaManager.class);
        prefs.put("idioma", codigo);
    }

    public static ResourceBundle getBundle() {
        return ResourceBundle.getBundle("i18n.messages", localeActual);
    }

    public static String getCodigoIdioma() {
        return localeActual.getLanguage();
    }

    public static Image cargarImagen(String nombreBase) {
        String lang = getCodigoIdioma();

        // EXCEPCIÓN: botón de idioma funciona al revés
        if (nombreBase.equals("btnIdioma")) {
            String archivo = lang.equals("es") ? "btnIdiomaEN.png" : "btnIdioma.png";
            String rutaEspecial = "/ui/graphicResources/imagenes/" + archivo;

            URL urlEspecial = IdiomaManager.class.getResource(rutaEspecial);
            if (urlEspecial == null) {
                System.err.println("❌ Imagen NO encontrada: " + rutaEspecial);
                throw new RuntimeException("Imagen no encontrada: " + rutaEspecial);
            }

            return new Image(urlEspecial.toExternalForm());
        }

        // RESTO DE BOTONES → siguen la regla normal
        String sufijo = lang.equals("en") ? "EN" : "";
        String ruta = "/ui/graphicResources/imagenes/" + nombreBase + sufijo + ".png";

        URL url = IdiomaManager.class.getResource(ruta);
        if (url == null) {
            System.err.println("❌ Imagen NO encontrada: " + ruta);
            throw new RuntimeException("Imagen no encontrada: " + ruta);
        }

        return new Image(url.toExternalForm());
    }

    public static String get(String clave, Object... params) {
        try {
            ResourceBundle bundle = getBundle();
            String texto = bundle.getString(clave);

            if (params != null && params.length > 0) {
                return java.text.MessageFormat.format(texto, params);
            }

            return texto;

        } catch (Exception e) {
            System.err.println("❌ Clave no encontrada en properties: " + clave);
            return "???" + clave + "???";
        }
    }

}
