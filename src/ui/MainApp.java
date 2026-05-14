package ui;

import i18n.IdiomaManager;
import firebase.BDPartidaService;
import firebase.FirebaseDatabaseService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.stage.Stage;
import javafx.util.Duration;
import ui.audio.MusicManager;

public class MainApp extends Application {

    private static Stage primaryStage;
    public static String usuarioActualUID = null;
    public static String usuarioActualToken = null;
    public static SalaOnlineController controladorSalaActual = null;

    @Override
    public void start(Stage stage) throws Exception {

        primaryStage = stage;
        primaryStage.setTitle("Tokaleda Cards Game");
        primaryStage.getIcons().add(new Image(
                getClass().getResourceAsStream("/ui/graphicResources/imagenes/logoApp.png")
        ));

        // 2. Cargar el FXML con ResourceBundle
        FXMLLoader loader = new FXMLLoader(getClass().getResource("login.fxml"));
        loader.setResources(IdiomaManager.getBundle());
        Parent root = loader.load();

        primaryStage.setScene(new Scene(root, 1100, 900));
        primaryStage.centerOnScreen();
        primaryStage.show();

        MusicManager.playMenuMusic();

        Platform.setImplicitExit(true);

    }

    public static Object cambiarEscena(String fxml, int width, int height) {
        try {
            FXMLLoader loader = new FXMLLoader(MainApp.class.getResource(fxml));
            loader.setResources(IdiomaManager.getBundle());

            Parent root = loader.load();
            Object controller = loader.getController();

            // Cambiar escena
            Scene scene = new Scene(root, width, height);
            primaryStage.setScene(scene);
            primaryStage.centerOnScreen(); // ⭐ AÑADIR AQUÍ
            primaryStage.show();

            // Si es sala, guardamos el controlador
            if (controller instanceof SalaOnlineController) {
                controladorSalaActual = (SalaOnlineController) controller;
            } else {
                controladorSalaActual = null;
            }

            System.out.println(">>> Controlador cargado: " + controller.getClass().getName());

            // ⭐ SI ES PARTIDA (cualquier modo) ⭐
            if (controller instanceof PartidaControllerBase) {
                System.out.println(">>> ENTRANDO EN PARTIDA: " + controller.getClass().getName());
                // SOLO cambiar música si no está ya sonando la de partida
                if (!MusicManager.isPlaying("partida")) {
                    System.out.println(">>> CAMBIANDO A MÚSICA DE PARTIDA");
                    MusicManager.playPartidaMusic();
                }

                // FULLSCREEN
                primaryStage.setFullScreenExitHint("");
                primaryStage.setFullScreenExitKeyCombination(
                        new KeyCodeCombination(KeyCode.ESCAPE)
                );
                primaryStage.setFullScreen(true);

                PartidaControllerBase pc = (PartidaControllerBase) controller;

                pc.init(SalaContext.codigoSalaActual, MainApp.usuarioActualUID, MainApp.usuarioActualToken);
                pc.configurarLayoutEscena(primaryStage);

                // REFRESCO DE PARTIDA
                BDPartidaService bdPartida = new BDPartidaService(new FirebaseDatabaseService());

                Timeline timeline = new Timeline(
                        new KeyFrame(Duration.millis(700), e -> {
                            try {
                                Map<String, Object> partida = bdPartida.leerPartida(
                                        SalaContext.codigoSalaActual,
                                        MainApp.usuarioActualToken
                                );

                                if (partida != null) {
                                    Map<String, List<String>> manos = (Map<String, List<String>>) partida.get("manos");
                                    List<String> baraja = (List<String>) partida.get("baraja");
                                    List<String> descarte = (List<String>) partida.get("descarte");

                                    pc.actualizarDesdeModelo(manos, baraja, descarte);
                                }

                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        })
                );

                timeline.setCycleCount(Animation.INDEFINITE);
                timeline.play();

            } else {

                // ⭐ SI NO ES PARTIDA → MÚSICA GENERAL ⭐
                if (!MusicManager.isPlaying("menu")) {
                    MusicManager.playMenuMusic();
                }
            }

            return controller;

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Error cargando escena: " + fxml);
            return null;
        }
    }

    @Override
    public void stop() {
        System.out.println(">>> STOP() EJECUTADO <<<");
        MainApp.desconectarUsuario();
    }

    public static void desconectarUsuario() {
        try {
            if (usuarioActualUID != null && usuarioActualToken != null) {
                FirebaseDatabaseService db = new FirebaseDatabaseService();

                // 1) Marcar desconectado = false
                Map<String, Object> datos = new HashMap<>();
                datos.put("conectado", false);
                db.actualizarCamposUsuario(usuarioActualUID, datos, usuarioActualToken);

                // 2) Si estaba en una sala, salir también de la sala
                if (SalaContext.codigoSalaActual != null) {
                    String codigo = SalaContext.codigoSalaActual;

                    String jsonSala = db.leerNodo("salas/" + codigo, usuarioActualToken);
                    if (jsonSala != null && !jsonSala.equals("null")) {

                        Map<String, Object> sala = new com.google.gson.Gson().fromJson(jsonSala, Map.class);
                        String hostUID = sala.get("host").toString();

                        if (!usuarioActualUID.equals(hostUID)) {
                            db.borrarNodo("salas/" + codigo + "/jugadores/" + usuarioActualUID, usuarioActualToken);
                            db.borrarNodo("usuarios/" + usuarioActualUID + "/salaActual", usuarioActualToken);

                        } else {
                            Map<String, Object> jugadores = (Map<String, Object>) sala.get("jugadores");

                            for (String jugadorUID : jugadores.keySet()) {
                                db.borrarNodo("usuarios/" + jugadorUID + "/salaActual", usuarioActualToken);
                            }

                            db.borrarNodo("salas/" + codigo, usuarioActualToken);
                        }
                    }
                }

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
