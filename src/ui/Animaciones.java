package ui;

import i18n.IdiomaManager;
import javafx.animation.*;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/**
 * Clase encargada de gestionar animaciones visuales en la interfaz.
 *
 * <p>
 * Proporciona efectos simples y reutilizables para botones, etiquetas, logos y
 * transiciones de entrada. Su objetivo es mejorar la experiencia visual del
 * usuario mediante animaciones suaves y no intrusivas.
 * </p>
 *
 * <p>
 * Todas las animaciones están implementadas con las clases de transición de
 * JavaFX, como {@link FadeTransition}, {@link ScaleTransition},
 * {@link RotateTransition}, {@link TranslateTransition} y
 * {@link ParallelTransition}.
 * </p>
 *
 * <p>
 * La clase es completamente estática, lo que permite invocar sus métodos desde
 * cualquier controlador sin necesidad de instanciarla.
 * </p>
 *
 * @author Javier Coronilla Castellano.
 */
public class Animaciones {

    /**
     * Aplica al logo un efecto continuo de balanceo y respiración.
     *
     * <p>
     * Animación en loop que da la sensación de que el logo "respira" con
     * movimientos de inclinación, movimiento y zoom.
     * </p>
     *
     * @param logo Imagen del logo a animar.
     */
    public static void animarLogo(ImageView logo) {

        RotateTransition rot = new RotateTransition(Duration.seconds(2), logo);
        rot.setFromAngle(0);
        rot.setToAngle(8);
        rot.setAutoReverse(true);
        rot.setCycleCount(Animation.INDEFINITE);

        ScaleTransition scale = new ScaleTransition(Duration.seconds(2), logo);
        scale.setFromX(1.0);
        scale.setFromY(1.0);
        scale.setToX(1.08);
        scale.setToY(1.08);
        scale.setAutoReverse(true);
        scale.setCycleCount(Animation.INDEFINITE);

        new ParallelTransition(rot, scale).play();
    }

    /**
     * Añade animaciones de hover a un botón.
     *
     * <p>
     * Al pasar el ratón por encima, el botón aumenta ligeramente de tamaño y
     * reduce su opacidad. Al salir, vuelve a su estado original.
     * </p>
     *
     * @param boton Botón al que aplicar el efecto.
     */
    public static void animarBoton(Button boton) {

        boton.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_ENTERED, e -> {
            ScaleTransition st = new ScaleTransition(Duration.millis(150), boton);
            st.setToX(1.08);
            st.setToY(1.08);

            FadeTransition ft = new FadeTransition(Duration.millis(150), boton);
            ft.setToValue(0.85);

            new ParallelTransition(st, ft).play();
        });

        boton.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_EXITED, e -> {
            ScaleTransition st = new ScaleTransition(Duration.millis(150), boton);
            st.setToX(1.0);
            st.setToY(1.0);

            FadeTransition ft = new FadeTransition(Duration.millis(150), boton);
            ft.setToValue(1.0);

            new ParallelTransition(st, ft).play();
        });
    }

    /**
     * Simula una pulsación rápida sobre un nodo.
     *
     * <p>
     * Reduce brevemente el tamaño del elemento y lo devuelve a su escala
     * original, imitando el efecto de "click" físico.
     * </p>
     *
     * @param nodo Elemento visual que recibirá la animación.
     */
    public static void animarPress(Node nodo) {
        ScaleTransition stDown = new ScaleTransition(Duration.millis(80), nodo);
        stDown.setToX(0.95);
        stDown.setToY(0.95);

        ScaleTransition stUp = new ScaleTransition(Duration.millis(80), nodo);
        stUp.setToX(1.0);
        stUp.setToY(1.0);

        SequentialTransition seq = new SequentialTransition(stDown, stUp);
        seq.play();
    }

    /**
     * Aplica a una etiqueta un efecto de latido y flotación.
     *
     * <p>
     * La etiqueta aumenta y disminuye ligeramente su tamaño mientras sube y
     * baja unos píxeles en un movimiento suave y constante.
     * </p>
     *
     * @param label Etiqueta a animar.
     */
    public static void animarLabelGeneral(Label label) {

        ScaleTransition pulse = new ScaleTransition(Duration.seconds(2), label);
        pulse.setFromX(1.0);
        pulse.setFromY(1.0);
        pulse.setToX(1.05);
        pulse.setToY(1.05);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(Animation.INDEFINITE);

        TranslateTransition floatAnim = new TranslateTransition(Duration.seconds(3), label);
        floatAnim.setFromY(0);
        floatAnim.setToY(-6);
        floatAnim.setAutoReverse(true);
        floatAnim.setCycleCount(Animation.INDEFINITE);

        new ParallelTransition(pulse, floatAnim).play();
    }

    /**
     * Aplica una animación más dinámica a etiquetas secundarias.
     *
     * <p>
     * Combina un movimiento orbital, un pequeño pulso y una rotación suave.
     * Animación pensada para textos que deben llamar más la atención sin ser
     * excesivos.
     * </p>
     *
     * @param label Etiqueta a animar.
     */
    public static void animarLabelSecundario(Label label) {

        double baseX = label.getTranslateX();
        double baseY = label.getTranslateY();

        Timeline orbita = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(label.translateXProperty(), baseX),
                        new KeyValue(label.translateYProperty(), baseY)
                ),
                new KeyFrame(Duration.seconds(0.4),
                        new KeyValue(label.translateXProperty(), baseX - 6),
                        new KeyValue(label.translateYProperty(), baseY - 3)
                ),
                new KeyFrame(Duration.seconds(0.8),
                        new KeyValue(label.translateXProperty(), baseX),
                        new KeyValue(label.translateYProperty(), baseY - 6)
                ),
                new KeyFrame(Duration.seconds(1.2),
                        new KeyValue(label.translateXProperty(), baseX + 6),
                        new KeyValue(label.translateYProperty(), baseY - 3)
                ),
                new KeyFrame(Duration.seconds(1.6),
                        new KeyValue(label.translateXProperty(), baseX),
                        new KeyValue(label.translateYProperty(), baseY)
                )
        );
        orbita.setCycleCount(Animation.INDEFINITE);
        orbita.setAutoReverse(false);

        ScaleTransition pulse = new ScaleTransition(Duration.seconds(1.6), label);
        pulse.setFromX(1.0);
        pulse.setFromY(1.0);
        pulse.setToX(1.10);
        pulse.setToY(1.10);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(Animation.INDEFINITE);

        RotateTransition rot = new RotateTransition(Duration.seconds(1.6), label);
        rot.setFromAngle(-3);
        rot.setToAngle(3);
        rot.setAutoReverse(true);
        rot.setCycleCount(Animation.INDEFINITE);

        new ParallelTransition(orbita, pulse, rot).play();
    }

    /**
     * Muestra un popUp de error dentro del contenedor indicado.
     *
     * <p>
     * Carga el FXML correspondiente, aplica la traducción y añade el popUp
     * sobre el StackPane principal. Animación posible por controller del popUp
     * error {link PopUpErrorController}
     * </p>
     *
     * @param root Contenedor donde se insertará el popup.
     * @param mensaje Texto del error a mostrar.
     */
    public static void mostrarError(StackPane root, String mensaje) {
        try {
            FXMLLoader loader = new FXMLLoader(Animaciones.class.getResource("/ui/popUpError.fxml"));
            loader.setResources(IdiomaManager.getBundle()); // ← NECESARIO PARA TRADUCCIÓN

            StackPane popup = loader.load();

            PopUpErrorController controller = loader.getController();
            controller.setMensaje(mensaje);

            root.getChildren().add(popup);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Muestra el popUp de opciones de sonido.
     *
     * <p>
     * Carga el FXML del popUp y lo añade al contenedor principal sin bloquear
     * la interfaz.
     * </p>
     *
     * @param root Contenedor donde se insertará el popup.
     */
    public static void mostrarPopupSonido(StackPane root) {
        try {
            FXMLLoader loader = new FXMLLoader(Animaciones.class.getResource("/ui/popUpSonido.fxml"));
            loader.setResources(IdiomaManager.getBundle());

            StackPane popup = loader.load();
            root.getChildren().add(popup);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Muestra un popUp de ayuda dentro del contenedor indicado.
     *
     * <p>
     * Carga el FXML correspondiente, aplica la traducción y añade el popUp
     * sobre el StackPane principal. Animación posible por controller del popUp
     * Ayuda {link PopUpErrorController}
     * </p>
     *
     * @param root Contenedor donde se insertará el popup.
     * @param mensaje Texto de ayuda a mostrar.
     */
    public static void mostrarAyuda(StackPane root, String mensaje) {
        try {
            FXMLLoader loader = new FXMLLoader(Animaciones.class.getResource("/ui/popUpAyuda.fxml"));
            loader.setResources(IdiomaManager.getBundle());

            StackPane popup = loader.load();

            PopUpAyudaController controller = loader.getController();
            controller.setMensaje(mensaje);

            root.getChildren().add(popup);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Transición de entrada avanzada con fade, zoom y brillo suave.
     *
     * <p>
     * Combina opacidad, escalado y un efecto de resplandor que desaparece. Se
     * utiliza para pantallas principales o elementos destacados.
     * </p>
     *
     * @param root Nodo al que aplicar la animación.
     */
    public static void fadeIn(Node root) {

        root.setOpacity(0);
        root.setScaleX(0.97);
        root.setScaleY(0.97);

        FadeTransition fade = new FadeTransition(Duration.millis(420), root);
        fade.setFromValue(0);
        fade.setToValue(1);

        ScaleTransition zoom = new ScaleTransition(Duration.millis(420), root);
        zoom.setFromX(0.97);
        zoom.setFromY(0.97);
        zoom.setToX(1.0);
        zoom.setToY(1.0);

        DropShadow glow = new DropShadow();
        glow.setColor(Color.rgb(255, 105, 180, 0.7)); // rosa gamer
        glow.setRadius(25);
        glow.setSpread(0.2);
        root.setEffect(glow);

        Timeline glowFade = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(glow.radiusProperty(), 25)),
                new KeyFrame(Duration.millis(420), new KeyValue(glow.radiusProperty(), 0))
        );

        fade.setInterpolator(Interpolator.EASE_OUT);
        zoom.setInterpolator(Interpolator.EASE_OUT);

        new ParallelTransition(fade, zoom, glowFade).play();
    }

}
