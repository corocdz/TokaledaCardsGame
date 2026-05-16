package ui;

/**
 * Clase de contexto utilizada para almacenar información global relacionada con
 * la sala online en la que se encuentra el usuario.
 *
 * <p>
 * Esta clase actúa como un contenedor estático y centralizado que permite
 * compartir el código de la sala actual entre distintos controladores sin
 * necesidad de pasar parámetros explícitamente entre ellos.
 * </p>
 *
 * <h2>Responsabilidades principales:</h2>
 * <ul>
 * <li>Almacenar el código de la sala en la que el usuario está
 * participando.</li>
 * <li>Permitir que cualquier parte de la aplicación acceda a dicho código sin
 * necesidad de duplicar información.</li>
 * <li>Servir como punto de referencia para operaciones relacionadas con
 * partidas online, como refrescos de estado, navegación o desconexión.</li>
 * </ul>
 *
 * <h2>Importancia dentro del proyecto:</h2>
 * <p>
 * El código de sala es un dato esencial para:
 * </p>
 * <ul>
 * <li>Inicializar controladores de partida.</li>
 * <li>Leer y actualizar datos en Firebase dentro del nodo de la sala.</li>
 * <li>Gestionar la desconexión del usuario desde {@link MainApp}.</li>
 * <li>Controlar la navegación entre pantallas relacionadas con la sala.</li>
 * </ul>
 *
 * <p>
 * Esta clase se menciona y utiliza en {@link MainApp}, especialmente en los
 * métodos de cambio de escena y en la lógica de desconexión, donde podemos
 * apreciar su importancia al ser necesario el saber si el usuario estaba dentro
 * de una sala para limpiar correctamente su estado en Firebase.
 * </p>
 *
 * <h2>Decisión de diseño:</h2>
 * <p>
 * Se utiliza una variable estática porque:
 * </p>
 * <ul>
 * <li>El código de sala debe ser accesible desde cualquier controlador.</li>
 * <li>No depende de instancias concretas de clases.</li>
 * <li>Representa un estado global temporal de la aplicación.</li>
 * </ul>
 *
 * <p>
 * Esta clase es deliberadamente simple para evitar acoplamiento innecesario y
 * mantener la arquitectura limpia.
 * </p>
 * 
 * 
 * @author Javier Coronilla Castellano
 */
public class SalaContext {

    
    /**
     * Código de la sala online en la que el usuario está actualmente.
     * <p>
     * De forma natural, su valor es {@code null} cuando el usuario no está dentro de ninguna sala.
     * </p>
     */
    public static String codigoSalaActual = null;
}
