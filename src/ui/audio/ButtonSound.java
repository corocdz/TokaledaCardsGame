package ui.audio;

import javafx.scene.control.Button;
import javafx.scene.input.MouseEvent;

/**
 * Clase utilitaria encargada de añadir efectos de sonido a botones JavaFX.
 *
 * <p>
 * Esta clase centraliza la lógica relacionada con la reproducción de sonidos al
 * interactuar con botones, evitando duplicación de código en los controladores.
 * </p>
 *
 * <h2>Ofrece tres métodos:</h2>
 * <ul>
 * <li>{@link #activar(Button)} - activa hover + click.</li>
 * <li>{@link #activarHover(Button)} - activa solo sonido al pasar el
 * ratón.</li>
 * <li>{@link #activarClick(Button)} - activa solo sonido al pulsar.</li>
 * </ul>
 *
 * <p>
 * Todos los métodos son estáticos porque esta clase no necesita instancias:
 * actúa como un "helper" reutilizable.
 * </p>
 *
 * @author Javier Coronilla Castellano.
 */
public class ButtonSound {

    /**
     * Activa sonido de hover y click en un botón.
     *
     * <p>
     * Este método añade dos listeners al botón:
     * </p>
     * <ul>
     * <li><b>Hover:</b> reproduce un sonido cuando el ratón entra en el
     * botón.</li>
     * <li><b>Click:</b> reproduce un sonido cuando el usuario hace click.</li>
     * </ul>
     *
     * <p>
     * Se utiliza {@code MOUSE_ENTERED_TARGET} en lugar de {@code MOUSE_ENTERED}
     * para evitar que el sonido se dispare múltiples veces cuando el ratón pasa
     * por elementos hijos del botón.
     * </p>
     *
     * @param b Botón al que se le activarán los sonidos. Si es {@code null}, el
     * método no hace nada.
     */
    public static void activar(Button b) {

        // Si el botón es null, no podemos añadir listeners.
        if (b == null) {
            return;
        }

        // El botón detecta eventos incluso si el ratón entra por zonas transparentes del mismo.
        b.setPickOnBounds(true);

        // Hover (ratón por encima del botón)
        b.addEventHandler(MouseEvent.MOUSE_ENTERED_TARGET, e -> {
            SoundManager.hover(); // reproduce sonido de hover definido por SoundManager.
        });

        // Click (click en ratón)
        b.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            SoundManager.clickButton(); // reproduce sonido de click definido por SoundManager.
        });
    }

    /**
     * Activa únicamente el sonido de hover en un botón.
     *
     * <p>
     * Este método es útil cuando se desea un comportamiento más sutil, por
     * ejemplo en botones que no representan acciones críticas.
     * </p>
     *
     * @param b Botón al que se le activará el sonido de hover.
     */
    public static void activarHover(Button b) {
        if (b == null) {
            return;
        }

        b.setPickOnBounds(true);

        b.addEventHandler(MouseEvent.MOUSE_ENTERED_TARGET, e -> {
            SoundManager.hover();
        });
    }

    /**
     * Activa únicamente el sonido de click en un botón.
     *
     * <p>
     * Este método se usa cuando se quiere un feedback sonoro solo al pulsar,
     * sin necesidad de sonido al pasar el ratón.
     * </p>
     *
     * @param b Botón al que se le activará el sonido de click.
     */
    public static void activarClick(Button b) {
        if (b == null) {
            return;
        }

        b.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            SoundManager.clickButton();
        });
    }

}
