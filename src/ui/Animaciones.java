/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package ui;

/**
 *
 * @author coro
 */
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

public class Animaciones {

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

    public static void animarLabelGeneral(Label label) {

        // Pulse (latido)
        ScaleTransition pulse = new ScaleTransition(Duration.seconds(2), label);
        pulse.setFromX(1.0);
        pulse.setFromY(1.0);
        pulse.setToX(1.05);
        pulse.setToY(1.05);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(Animation.INDEFINITE);

        // Float (flotar)
        TranslateTransition floatAnim = new TranslateTransition(Duration.seconds(3), label);
        floatAnim.setFromY(0);
        floatAnim.setToY(-6);
        floatAnim.setAutoReverse(true);
        floatAnim.setCycleCount(Animation.INDEFINITE);

        new ParallelTransition(pulse, floatAnim).play();
    }

    public static void animarLabelSecundario(Label label) {

        // Posición base (la que tú le des en FXML o con setTranslateX antes)
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

    public static void fadeIn(Node root) {
        root.setOpacity(0);

        FadeTransition ft = new FadeTransition(Duration.millis(450), root);
        ft.setFromValue(0);
        ft.setToValue(1);
        ft.play();
    }

    public static void fadeInPro(Node root) {

        // Estado inicial
        root.setOpacity(0);
        root.setScaleX(0.97);
        root.setScaleY(0.97);

        // Fade suave
        FadeTransition fade = new FadeTransition(Duration.millis(420), root);
        fade.setFromValue(0);
        fade.setToValue(1);

        // Micro-zoom elegante
        ScaleTransition zoom = new ScaleTransition(Duration.millis(420), root);
        zoom.setFromX(0.97);
        zoom.setFromY(0.97);
        zoom.setToX(1.0);
        zoom.setToY(1.0);

        // Glow retro suave (desaparece)
        DropShadow glow = new DropShadow();
        glow.setColor(Color.rgb(255, 105, 180, 0.7)); // rosa gamer
        glow.setRadius(25);
        glow.setSpread(0.2);
        root.setEffect(glow);

        Timeline glowFade = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(glow.radiusProperty(), 25)),
                new KeyFrame(Duration.millis(420), new KeyValue(glow.radiusProperty(), 0))
        );

        // Curva de animación más profesional
        fade.setInterpolator(Interpolator.EASE_OUT);
        zoom.setInterpolator(Interpolator.EASE_OUT);

        // Ejecutar todo junto
        new ParallelTransition(fade, zoom, glowFade).play();
    }

}
