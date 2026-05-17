package ui;

import com.google.gson.Gson;
import firebase.BDPartidaService;
import firebase.FirebaseDatabaseService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import i18n.IdiomaManager;
import java.util.ResourceBundle;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import partidaUTIL.Baraja;
import partidaUTIL.Carta;
import partidaUTIL.JuegoYusa;
import ui.audio.ButtonSound;
import ui.audio.SoundManager;

/**
 * Controlador de la pantalla de sala online.
 *
 * <p>
 * Esta clase gestiona la lógica de la sala online donde los jugadores se reúnen
 * antes de iniciar una partida. Desde aquí:
 * </p>
 * <ul>
 * <li>Se muestra el código de la sala y la lista de jugadores conectados.</li>
 * <li>Se determina si el usuario actual es el host y se ajustan los
 * permisos.</li>
 * <li>El host puede seleccionar el modo de juego e iniciar la partida.</li>
 * <li>El host puede expulsar jugadores de la sala.</li>
 * <li>Se refresca periódicamente el estado de la sala (jugadores, expulsiones,
 * inicio de partida, etc.).</li>
 * <li>Se gestiona la salida de la sala tanto para host como para jugadores
 * normales.</li>
 * </ul>
 *
 * <p>
 * La sala se sincroniza con Firebase Realtime Database, de modo que cualquier
 * cambio (jugadores que entran/salen, host que abandona, inicio de partida) se
 * refleja en todos los clientes.
 * </p>
 *
 * @author Javier Coronilla Castellano.
 */
public class SalaOnlineController {

    // -------------------------------
    // ELEMENTOS GRÁFICOS
    // -------------------------------
    /**
     * Codigo único de la sala.
     */
    @FXML
    private Label lblCodigoSala;

    /**
     * Lista visual de los jugadores conectados a la sala.
     */
    @FXML
    private ListView<String> listaJugadores;

    /**
     * ComboBox para seleccionar el modo de juego.
     * <p>
     * Solo host puede modificarlo.
     * </p>
     */
    @FXML
    private ComboBox<String> comboModoJuego;

    /**
     * Imagen del botón de "Iniciar Partida".
     */
    @FXML
    private ImageView btnIniciarImage;

    /**
     * Imagen del botón de "Salir".
     */
    @FXML
    private ImageView btnSalirImage;

    /**
     * Botón de "Iniciar Partida".
     */
    @FXML
    private Button btnIniciar;

    /**
     * Botón de opciones.
     */
    @FXML
    private Button botonOpciones;

    /**
     * Imagen de botón de opciones.
     */
    @FXML
    private ImageView botonOpcionesImage;

    /**
     * Botón de "Salir".
     */
    @FXML
    private Button btnSalir;

    /**
     * Capa raíz.
     */
    @FXML
    private StackPane rootSala;

    // -------------------------------
    // ELEMENTOS GENERALES
    // -------------------------------
    /**
     * Indica si el usuario actual es el host de la sala.
     */
    private boolean esHost = false;

    /**
     * Temporizador para el refresco periódico de la sala.
     * <p>
     * Se inicializa en {@link #iniciarRefrescoPeriodico()} y se cancela cuando:
     * </p>
     * <ul>
     * <li>El usuario sale de la sala.</li>
     * <li>La partida se inicia.</li>
     * <li>El host abandona la sala y esta se elimina.</li>
     * <li>El usuario es expulsado.</li>
     * </ul>
     */
    private Timer timer;

    /**
     * Mapa auxiliar que relaciona el nombre mostrado en la lista de jugadores
     * con el UID real del usuario.
     */
    private Map<String, String> nombreAUid = new HashMap<>();

    /**
     * Bundle de idioma cargado dinámicamente según la elección del usuario.
     */
    private final ResourceBundle bundle = IdiomaManager.getBundle();

    /**
     * Inicializa la interfaz de la sala online tras la carga del FXML.
     *
     * <p>
     * Este método:
     * </p>
     * <ul>
     * <li>Aplica una animación de entrada al contenedor raíz.</li>
     * <li>Carga las imágenes de los botones según el idioma.</li>
     * <li>Configura animaciones y sonidos de los botones.</li>
     * <li>Muestra el código de la sala actual.</li>
     * <li>Carga los datos de la sala desde Firebase.</li>
     * <li>Configura los modos de juego disponibles.</li>
     * <li>Restringe acciones si el usuario no es host.</li>
     * <li>Configura la lista de jugadores con un botón de expulsar (solo
     * host).</li>
     * <li>Inicia el refresco periódico de la sala.</li>
     * </ul>
     */
    @FXML
    public void initialize() {

        // Animación de aparición suave del contenedor raíz.
        Platform.runLater(() -> Animaciones.fadeIn(rootSala));

        // Cargamos imágenes de los botones según el idioma actual.
        btnIniciarImage.setImage(IdiomaManager.cargarImagen("btnIniciar"));
        btnSalirImage.setImage(IdiomaManager.cargarImagen("btnSalir"));

        // Animaciones de los botones.
        Animaciones.animarBoton(btnSalir);
        Animaciones.animarBoton(btnIniciar);
        Animaciones.animarBoton(botonOpciones);

        // Sonidos de los botones.
        ButtonSound.activar(btnSalir);
        ButtonSound.activar(btnIniciar);
        ButtonSound.activar(botonOpciones);

        // Mostramos código real de la sala.
        lblCodigoSala.setText(bundle.getString("salaOnline.codigo") + SalaContext.codigoSalaActual);

        // Cargamos datos reales de Firebase.
        cargarDatosSala();

        // Configuramos modos de juego. Actualmente solo están disponibles Pescaito y Yusa.
        comboModoJuego.getItems().addAll(
                "Pescaito",
                "Yusa"
        );

        // Por defecto, escogemos el primero en el comboBox.
        comboModoJuego.getSelectionModel().selectFirst();

        // Efecto visual y sonoro al cambiar el modo de juego en el ComboBox. Solo aplicable al host.
        comboModoJuego.setOnAction(e -> {
            if (esHost) {
                Animaciones.animarPress(comboModoJuego);
                SoundManager.clickButton();
            }
        });

        // Control de permisos del host. Si no es host, no tiene habilitados el botón de iniciar ni el comboBox.
        if (!esHost) {
            //btnExpulsar.setDisable(true);
            btnIniciar.setDisable(true);
            comboModoJuego.setDisable(true);
        }

        // Acciones de los botones.
        btnSalir.setOnAction(e -> salir());
        btnIniciar.setOnAction(e -> iniciarPartida());
        botonOpciones.setOnAction(e -> Animaciones.mostrarPopupSonido(rootSala));

        // Configuramos el listView de jugadores con un botón de expulsar visible solo para el host.
        listaJugadores.setCellFactory(lv -> new ListCell<String>() {

            // Contenedor para los jugadores y nombres.
            private final HBox contenedor = new HBox();
            private final Label lblNombre = new Label();

            // Imagen del botón de expulsar
            private final ImageView iconoExpulsar = new ImageView(
                    new Image(getClass().getResourceAsStream("/ui/graphicResources/imagenes/btnExpulsar.png"))
            );

            // Botón de expulsar jugador.
            private final Button btnExpulsar = new Button();

            { // bloque de inicialización para la lista de jugadores. Se ejecuta cada vez que se crea una nueva celda para un nuevo jugador.

                // Separación horizontal entre elementos de la celda.
                contenedor.setSpacing(10);

                // Alineación del contenido a la izquierda.
                contenedor.setAlignment(Pos.CENTER_LEFT);

                // Permite que el nombre del jugador ocupe todo el espacio posbile.
                HBox.setHgrow(lblNombre, Priority.ALWAYS);
                lblNombre.setMaxWidth(Double.MAX_VALUE);

                // Tamaño del icono del botón de expulsar.
                iconoExpulsar.setFitWidth(50);
                iconoExpulsar.setFitHeight(50);

                // Botón sin texto, solo icono.
                btnExpulsar.setGraphic(iconoExpulsar);
                btnExpulsar.setText(null);

                // Estilo del botón (sin fondo, solo icono)
                btnExpulsar.setStyle(
                        "-fx-background-color: transparent;"
                        + "-fx-padding: 0;"
                );

                // Animación del botón.
                Animaciones.animarBoton(btnExpulsar);

                // Acción del botón. Selecciona al jugador correspondiente a la celda.
                btnExpulsar.setOnAction(e -> {
                    listaJugadores.getSelectionModel().select(getItem());
                    expulsarJugador(); // Y lo expulsa.
                });
            }

            @Override
            protected void updateItem(String nombre, boolean empty) {
                // Llamada a método de superclase para que JavaFX haga actualización de celdas.
                super.updateItem(nombre, empty);

                // Si la celda está vacía (empty = true), no hay que mostrar nada.
                if (empty || nombre == null) {
                    setGraphic(null); // Quitamos cualquier contenido visual previo.
                    return; // salimos.
                }

                // Si la celda tiene nombre válido, lo mostramos en la etiqueta.
                lblNombre.setText(nombre);
                contenedor.getChildren().setAll(lblNombre); // dejamos solo nombre del jugador.

                // Si YO soy el HOST y los demás no, muestro el botón de expulsar junto a sus nombres en la celda.
                if (esHost && !nombre.contains("(Host)")) {
                    contenedor.getChildren().add(btnExpulsar);
                }

                // Asignamos contenedor completo como contenido visual de la celda.
                setGraphic(contenedor); // mostramos nombre del jugador y opcionalmente el botón de expulsar.
            }
        });

        // Iniciar refresco periódico de la sala.
        iniciarRefrescoPeriodico();
    }

    /**
     * Carga los datos de la sala desde Firebase.
     *
     * <p>
     * Este método:
     * </p>
     * <ul>
     * <li>Lee el nodo completo de la sala en Firebase.</li>
     * <li>Determina si el usuario actual es el host.</li>
     * <li>Carga la lista de jugadores y sus nombres.</li>
     * <li>Rellena la {@link #listaJugadores} y el mapa
     * {@link #nombreAUid}.</li>
     * </ul>
     */
    private void cargarDatosSala() {
        try {

            FirebaseDatabaseService db = new FirebaseDatabaseService();
            String token = MainApp.usuarioActualToken;
            String codigo = SalaContext.codigoSalaActual;

            // Leemos nodo completo de la sala.
            String jsonSala = db.leerNodo("salas/" + codigo, token);

            // Si es nulo o "null", mostramos error y salimos.
            if (jsonSala == null || jsonSala.equals("null")) {
                System.out.println("La sala no existe.");
                return;
            }

            // Convertimos el JSON en un Map para poder acceder a sus campos como si fuera un diccionario.
            Map<String, Object> datosSala = new com.google.gson.Gson().fromJson(jsonSala, Map.class);

            // Comprobamos si soy host.
            String hostUID = (String) datosSala.get("host");
            esHost = hostUID.equals(MainApp.usuarioActualUID);

            // Obtenemos el nodo de jugadores.
            Map<String, Object> jugadores = (Map<String, Object>) datosSala.get("jugadores");

            // Limpiamos la lista visual y el mapa auxiliar antes de rellenarlos.
            listaJugadores.getItems().clear();
            nombreAUid.clear();

            // Recorremos cada UID de jugador conectado a la sala.
            for (String uid : jugadores.keySet()) {

                // Obtenemos el nombre de cada jugador leyendo el nodo "usuarios/UID".
                String jsonUsuario = db.leerNodo("usuarios/" + uid, token);
                String nombre = uid; // Podemos utilizar el UID como nombre provisional en todo caso.

                // Si el nodo existe, intentamos extraer el campo "nombre".
                if (jsonUsuario != null && !jsonUsuario.equals("null")) {
                    Map<String, Object> datosUsuario = new Gson().fromJson(jsonUsuario, Map.class);

                    // Si el nodo tiene un campo "nombre", lo usamos como nombre visible.
                    if (datosUsuario != null && datosUsuario.get("nombre") != null) {
                        nombre = datosUsuario.get("nombre").toString();
                    }
                }

                // Añadimos los nombres a la lista visual.
                if (uid.equals(hostUID)) { // Si soy host, ponemos la etiqueta al lado de nuestro nombre.
                    String mostrado = nombre + " (Host)";
                    listaJugadores.getItems().add(mostrado); // Añadimos el nombre a la lista.
                    nombreAUid.put(mostrado, uid); // Guardamos la relación nombre mostrado - UID.
                } else {
                    // Si es un jugador normal, añadimos su nombre tal cual.
                    listaJugadores.getItems().add(nombre);

                    // Guardamos la relación nombre - UID nuevamente.
                    nombreAUid.put(nombre, uid);
                }
            }

        } catch (IOException e) { // Capturamos posible excepción.
            e.printStackTrace();
            System.out.println("Error cargando datos de la sala: " + e.getMessage());
        }
    }

    /**
     * Acción asociada al botón "Salir".
     *
     * <p>
     * Llama a {@link #salirDeSala(String)} para limpiar la información del
     * usuario en Firebase, cancela el temporizador de refresco y navega al menú
     * online.
     * </p>
     */
    private void salir() {
        salirDeSala(MainApp.usuarioActualUID); // Salimos de la sala limpiando la información del usuario en FB.
        timer.cancel(); // Cancelamos el temporizar de refresco.
        MainApp.cambiarEscena("menuOnline.fxml", 1200, 1000); // Navegamos al menú online.
    }

    // =========================================================================
    //  INICIAR PARTIDA (BOTÓN INICIAR)
    // =========================================================================
    /**
     * Acción asociada al botón "Iniciar partida".
     *
     * <p>
     * Solo el host puede ejecutar esta acción. El método:
     * </p>
     * <ul>
     * <li>Comprueba que el usuario sea host.</li>
     * <li>Verifica que se haya seleccionado un modo de juego.</li>
     * <li>Lee la sala desde Firebase y obtiene la lista de jugadores.</li>
     * <li>Comprueba que haya al menos 2 jugadores en la sala.</li>
     * <li>Crea una baraja barajada y las estructuras de manos y vidas.</li>
     * <li>Construye el mapa {@code datosPartida} con toda la información
     * inicial de la partida.</li>
     * <li>Guarda la partida en Firebase mediante {@link BDPartidaService}.</li>
     * </ul>
     */
    @FXML
    private void iniciarPartida() {

        // Solo host puede iniciar la partida.
        if (!esHost) {
            return;
        }

        // Debemos tener un modo de juego seleccionado.
        String modo = comboModoJuego.getValue();
        if (modo == null) {
            System.err.println("Debes seleccionar un modo de juego.");
            return;
        }

        try {

            String token = MainApp.usuarioActualToken;
            String codigo = SalaContext.codigoSalaActual;

            FirebaseDatabaseService rawDb = new FirebaseDatabaseService();
            BDPartidaService bd = new BDPartidaService(rawDb);

            // Leemos la sala desde FB.
            String jsonSala = rawDb.leerNodo("salas/" + codigo, token);

            // Convertimos el JSON en un mapa para acceder a sus campos.
            Map<String, Object> sala = new Gson().fromJson(jsonSala, Map.class);

            // Obtenemos el nodo "jugadores", que contiene todos los jugadores conectados.
            Map<String, Object> jugadores = (Map<String, Object>) sala.get("jugadores");

            // Validamos que haya, al menos, 2 jugadores en la sala. Si no, no permitimos que se juegue.
            if (jugadores == null || jugadores.size() < 2) {
                System.out.println("[SalaOnline] No se puede iniciar partida: solo hay "
                        + (jugadores == null ? 0 : jugadores.size()) + " jugador(es)."); // debug
                Animaciones.mostrarError(rootSala, bundle.getString("salaOnlineController.minJugadores"));
                return; // Salimos sin iniciar partida.
            }

            // Creamos una baraja nueva con todas las cartas del juego.
            Baraja baraja = new Baraja();
            baraja.barajar(); // La barajamos

            // Convertimos las cartas en rutas de imagen para que sea legible por FB.
            List<String> barajaRestante = new ArrayList<>();
            for (Carta c : baraja.getCartasRestantes()) {
                barajaRestante.add(c.getRutaImagen());
            }

            // Mapa UID - mano para cada jugador (inicialmente null).
            Map<String, List<String>> manos = new HashMap<>();
            for (String uid : jugadores.keySet()) {
                manos.put(uid, null);
            }

            // Creamos mapa UID - vidas para cada jugador 
            Map<String, Integer> vidas = new HashMap<>();
            for (String uid : jugadores.keySet()) {
                vidas.put(uid, JuegoYusa.VIDAS_INICIALES); // 3 vidas según la Yusa.
            }

            // Creamos la estructura inicial de la partida.
            Map<String, Object> datosPartida = new HashMap<>();
            datosPartida.put("estado", "iniciada");
            datosPartida.put("modo", modo);
            datosPartida.put("manos", manos);
            datosPartida.put("baraja", barajaRestante);
            datosPartida.put("descarte", new ArrayList<String>());
            datosPartida.put("turno", sala.get("host"));
            datosPartida.put("ronda", 1);
            datosPartida.put("vidas", vidas);

            // Añadimos narrador inicial.
            Map<String, Object> narradorInicial = new HashMap<>();
            narradorInicial.put("tipo", "global");
            narradorInicial.put("texto", "La partida ha comenzado");
            narradorInicial.put("uid", null);
            datosPartida.put("narrador", narradorInicial);

            // Guardamos toda la estructura en el nodo "partida" de la sala.
            bd.iniciarPartida(codigo, datosPartida, token);

            System.out.println("Partida iniciada correctamente."); // debug

        } catch (Exception e) { // Capturamos posibles excepciones.
            e.printStackTrace();
            Animaciones.mostrarError(rootSala, bundle.getString("salaOnlineController.iniciar"));
        }
    }

    /**
     * Método que expulsa al jugador seleccionado en la lista de jugadores.
     *
     * <p>
     * Este método solo puede ejecutarlo el host de la sala. Su función es
     * eliminar a un jugador de la sala online tanto a nivel visual como en
     * Firebase, garantizando que:
     * </p>
     * <ul>
     * <li>El jugador deja de aparecer en la lista de jugadores de la sala.</li>
     * <li>Se elimina su nodo dentro de "salas/{codigo}/jugadores".</li>
     * <li>Se marca en su nodo de usuario que ha sido expulsado.</li>
     * <li>Se borra su campo "salaActual" para que el cliente detecte la
     * expulsión.</li>
     * <li>El cliente expulsado reciba la señal y vuelva al menú online.</li>
     * <li>El cliente expulsado NO podrá unirse más a la sala mientras siga en
     * el nodo de "salas/{codigo}/expulsados".</li>
     * </ul>
     *
     * <p>
     * La expulsión se realiza de forma inmediata y sin necesidad de refrescar
     * toda la sala, ya que se elimina directamente de la ListView.
     * </p>
     */
    private void expulsarJugador() {

        // Solo host puede expulsar jugadores.
        if (!esHost) {
            return;
        }

        // Obtenemos el nombre del jugador seleccionado en la ListView.
        String seleccionado = listaJugadores.getSelectionModel().getSelectedItem();
        if (seleccionado == null) {
            return;
        }

        // Usamos el mapa UID - nombre para obtener el uid del jugador seleccionado.
        // Lo necesitamos ya que FB trabaja con UIDs.
        String uidExpulsado = nombreAUid.get(seleccionado);
        if (uidExpulsado == null) {
            return;
        }

        try { // Preparación de servicios y datos ncesarios para FB.
            FirebaseDatabaseService db = new FirebaseDatabaseService();
            String token = MainApp.usuarioActualToken;
            String codigo = SalaContext.codigoSalaActual;

            // Eliminamos al jugador que queremos expulsar de la sala para todos los clientes.
            db.borrarNodo("salas/" + codigo + "/jugadores/" + uidExpulsado, token);

            // Añadimos su UID al nodo de "expulsados"
            db.actualizarNodo("salas/" + codigo + "/expulsados/" + uidExpulsado, true, token);

            // Borramos de su nodo salaActual la sala de la que ha sido expulsado para liberarlo.
            db.borrarNodo("usuarios/" + uidExpulsado + "/salaActual", token);

            // Nos permite que el cliente muestre un mnesaje de expulsión antes de redirigir a menú.
            db.actualizarNodo("usuarios/" + uidExpulsado + "/expulsadoDeSala", codigo, token);

            // Lo eliminamos de la lista visual de jugadores.
            listaJugadores.getItems().remove(seleccionado);

        } catch (Exception e) { // Capturamos posible excepción.
            e.printStackTrace();
        }
    }

    /**
     * Inicia un temporizador que refresca periódicamente el estado de la sala.
     *
     * <p>
     * Este método crea un {@link java.util.Timer} que ejecuta una tarea cada
     * segundo. Esa tarea se encarga de llamar al método
     * {@link #refrescarJugadores()}, que actualiza la lista de jugadores,
     * detecta expulsiones, inicio de partida, salida del host, etc.
     * </p>
     *
     * <p>
     * Es importante destacar que el temporizador NO puede modificar la interfaz
     * gráfica directamente, ya que JavaFX exige que cualquier operación sobre
     * la UI se realice en el hilo principal de JavaFX. Por eso se utiliza
     * {@code Platform.runLater()}, que garantiza que la actualización se
     * ejecute correctamente en el hilo adecuado.
     * </p>
     */
    private void iniciarRefrescoPeriodico() {

        // Objeto que permite programar tareas que se ejecutan de forma periódica o con retraso.
        timer = new java.util.Timer();

        // scheduleAtFixedRate() ejecuta una tarea repetidamente con un intervalo fijo.
        // La tarea es: refrescarJugadores() se ejecutará cada segundo.
        timer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                javafx.application.Platform.runLater(() -> {
                    refrescarJugadores();
                });
            }
        }, 0, 1000); // refresco cada 1 segundo
    }

    /**
     * Refresca el estado de la sala online en tiempo real.
     *
     * <p>
     * Este método es el núcleo de la sincronización del multijugador. Se
     * ejecuta automáticamente cada segundo gracias al temporizador iniciado en
     * {@link #iniciarRefrescoPeriodico()} y permite que el cliente reaccione a
     * cualquier cambio en Firebase sin necesidad de recargar la pantalla.
     * </p>
     *
     * <p>
     * Las tareas principales que realiza son:
     * </p>
     * <ol>
     * <li>Detectar si el usuario ha sido expulsado de la sala.</li>
     * <li>Detectar si el host ha ordenado volver a la sala.</li>
     * <li>Detectar si la partida ha comenzado y cargar la pantalla de
     * partida.</li>
     * <li>Detectar si la sala ha sido eliminada (host salió).</li>
     * <li>Actualizar la lista de jugadores en la interfaz.</li>
     * </ol>
     *
     * <p>
     * Este método es crítico para mantener la coherencia entre todos los
     * clientes conectados a la sala.
     * </p>
     */
    private void refrescarJugadores() {

        // Detectamos si he sido expulsado
        try {
            FirebaseDatabaseService db = new FirebaseDatabaseService();
            String token = MainApp.usuarioActualToken;
            String uid = MainApp.usuarioActualUID;

            // Leemos el nodo "usuarios/{uid}/expulsadoDeSala".
            // Si existe, significa que el host ha expulsado al jugador.
            String expulsado = db.leerNodo("usuarios/" + uid + "/expulsadoDeSala", token);

            if (expulsado != null && !expulsado.equals("null")) {
                // Firebase devuelve strings con comillas, así que las limpiamos.
                String valor = expulsado.replace("\"", "").trim();

                // Si el valor no está vacío - expulsión confirmada.
                if (!valor.isEmpty()) {

                    // Eliminamos el nodo para evitar que el cliente entre en un bucle
                    // infinito detectando expulsión una y otra vez.
                    db.borrarNodo("usuarios/" + uid + "/expulsadoDeSala", token);

                    // Eliminamos salaActual por si el host no lo hizo.
                    db.borrarNodo("usuarios/" + uid + "/salaActual", token);
                    // Detenemos el refresco periódico.
                    if (timer != null) {
                        timer.cancel();
                    }
                    // Mostramos mensaje de expulsión y redirigimos al menú.
                    Platform.runLater(() -> {
                        Animaciones.mostrarError(rootSala, bundle.getString("salaOnlineController.expulsado"));

                        // Esperar 1 segundo antes de cambiar de escena
                        PauseTransition delay = new PauseTransition(Duration.seconds(3));
                        delay.setOnFinished(ev -> MainApp.cambiarEscena("menuOnline.fxml", 1200, 1000));
                        delay.play();
                    });

                    return;
                }
            }

        } catch (Exception e) { // Capturamos posible excepción.
            e.printStackTrace();
        }

        // Refrescamos datos generales de la sala.
        try {
            FirebaseDatabaseService db = new FirebaseDatabaseService();
            String token = MainApp.usuarioActualToken;
            String codigo = SalaContext.codigoSalaActual;
            String jsonSala = db.leerNodo("salas/" + codigo, token);

            // Leemos el nodo completo de la sala.
            String volverSala = db.leerNodo("salas/" + codigo + "/volverSala", token);
            if (volverSala != null && !volverSala.equals("null")) {

                // Obtenemos el UID del host.
                String hostUID2 = db.leerNodo("salas/" + codigo + "/host", token).replace("\"", "");

                // Solo los NO-host deben reaccionar.
                if (!MainApp.usuarioActualUID.equals(hostUID2)) {

                    if (timer != null) {
                        timer.cancel();
                    }

                    // Eliminamos el flag para evitar bucles.
                    db.borrarNodo("salas/" + codigo + "/volverSala", token);

                    // Volvemos a la sala online.
                    Platform.runLater(() -> MainApp.cambiarEscena("salaOnline.fxml", 1200, 1000));
                    return;
                }
            }

            // Detectar si la partida ha comenzado.
            String estado = db.leerNodo("salas/" + codigo + "/partida/estado", token);

            if (estado != null && !estado.equals("null")) {

                String estadoLimpio = estado.replace("\"", "");

                // Si la partida está iniciada - cargar pantalla de partida.
                if ("iniciada".equals(estadoLimpio)) {

                    if (timer != null) {
                        timer.cancel();
                    }

                    // Obtenemos el modo de juego.
                    String modoRaw = db.leerNodo("salas/" + codigo + "/partida/modo", token);

                    // Limpiamos modoRaw y obtenemos el modo.
                    String modoSemi = (modoRaw != null) ? modoRaw.replace("\"", "").trim() : null;
                    final String modoFinal = modoSemi;

                    // Cargamos pantalla de partida en el hilo de JavaFX.
                    Platform.runLater(() -> {
                        try {
                            // Cargamos pantalla de partida.
                            FXMLLoader loader = new FXMLLoader(getClass().getResource("/ui/partida.fxml"));

                            PartidaControllerBase controller;

                            // Seleccionamos el controlador según el modo de juego.
                            if ("Pescaito".equals(modoFinal)) {
                                controller = new PartidaControllerPescaito();
                            } else if ("Yusa".equals(modoFinal)) {
                                controller = new PartidaControllerYusa();
                            } else { //Excepción si falla bloque if-else if
                                throw new IllegalStateException("Modo desconocido: " + modoFinal);
                            }

                            loader.setController(controller);
                            Parent root = loader.load();

                            // Llamamos al init del modo de juego.
                            controller.init(codigo, MainApp.usuarioActualUID, MainApp.usuarioActualToken);

                            Stage stage = (Stage) rootSala.getScene().getWindow();
                            stage.setMaximized(false); // reset
                            stage.setScene(new Scene(root));
                            stage.setMaximized(true);  // maximizar bien
                            stage.show();

                        } catch (Exception e) { // Capturamos posible excepción.
                            e.printStackTrace();
                        }
                    });

                    return;
                }

            }

            // Detectar si la sala ha sido eliminada debido a que host ha salido de ella.
            if (jsonSala == null || jsonSala.equals("null")) {

                if (timer != null) {
                    timer.cancel();
                }

                // Si host sale de la sala, dicha sala se borrará de la FB y los jugadores dentro de ella saldrán tras unn aviso al menú Online.
                Platform.runLater(() -> {
                    Animaciones.mostrarError(rootSala, bundle.getString("salaOnlineController.hostSalio"));

                    PauseTransition delay = new PauseTransition(Duration.seconds(3));
                    delay.setOnFinished(ev -> MainApp.cambiarEscena("menuOnline.fxml", 1200, 1000));
                    delay.play();
                });

                return;

            }

            // Actualizar lista de jugadores del interfaz.
            Map<String, Object> sala = new Gson().fromJson(jsonSala, Map.class);

            String hostUID = sala.get("host").toString();

            // Obtenemos el nodo "jugadores" como LinkedHashMap para mantener el orden.
            LinkedHashMap<String, Object> jugadores
                    = new Gson().fromJson(
                            new Gson().toJson(sala.get("jugadores")),
                            LinkedHashMap.class
                    );

            // Ordenamos los jugadores por el campo "unidoEn".
            List<Map.Entry<String, Object>> lista = new ArrayList<>(jugadores.entrySet());

            lista.sort((a, b) -> {
                Map<String, Object> dataA = (Map<String, Object>) a.getValue();
                Map<String, Object> dataB = (Map<String, Object>) b.getValue();

                double ta = (double) dataA.get("unidoEn");
                double tb = (double) dataB.get("unidoEn");

                return Double.compare(ta, tb);
            });

            // Limpiamos lista visual
            listaJugadores.getItems().clear();

            // Añadir host primero
            String jsonHost = db.leerNodo("usuarios/" + hostUID, token);
            String nombreHost = hostUID;

            if (jsonHost != null && !jsonHost.equals("null")) {
                Map<String, Object> datosHost = new Gson().fromJson(jsonHost, Map.class);
                if (datosHost.get("nombre") != null) {
                    nombreHost = datosHost.get("nombre").toString();
                }
            }

            // Limpiamos la lista visual y el mapa auxiliar.
            listaJugadores.getItems().clear();
            nombreAUid.clear();

            // Host
            String mostradoHost = nombreHost + " (Host)";
            listaJugadores.getItems().add(mostradoHost);
            nombreAUid.put(mostradoHost, hostUID);

            // Resto
            for (Map.Entry<String, Object> entry : lista) {
                String uid = entry.getKey();
                // Host ya está añadido, lo saltamos.
                if (uid.equals(hostUID)) {
                    continue;
                }

                String jsonUsuario = db.leerNodo("usuarios/" + uid, token);
                String nombre = uid;

                if (jsonUsuario != null && !jsonUsuario.equals("null")) {
                    Map<String, Object> datosUsuario = new Gson().fromJson(jsonUsuario, Map.class);
                    if (datosUsuario.get("nombre") != null) {
                        nombre = datosUsuario.get("nombre").toString();
                    }
                }

                listaJugadores.getItems().add(nombre);
                nombreAUid.put(nombre, uid);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Gestiona la salida de un usuario de la sala online y actualiza Firebase
     * en consecuencia.
     *
     * <p>
     * Este método se ejecuta cuando un jugador pulsa el botón "Salir" o cuando
     * el host abandona la sala. Su comportamiento depende de si el usuario que
     * sale es el host o un jugador normal.
     * </p>
     *
     * <p>
     * CASO 1 - El usuario NO es el host:
     * </p>
     * <ul>
     * <li>Se elimina al jugador del nodo "jugadores" de la sala.</li>
     * <li>Se borra su campo "salaActual" en el nodo "usuarios".</li>
     * <li>El resto de la sala continúa funcionando normalmente.</li>
     * </ul>
     *
     * <p>
     * CASO 2 - El usuario ES el host:
     * </p>
     * <ul>
     * <li>Se borra su "salaActual".</li>
     * <li>Se borra el "salaActual" de todos los jugadores conectados.</li>
     * <li>Se elimina la sala completa de Firebase.</li>
     * <li>Todos los jugadores serán expulsados automáticamente al menú.</li>
     * </ul>
     *
     * <p>
     * Este método garantiza que la estructura de Firebase quede siempre en un
     * estado coherente, evitando salas huérfanas o usuarios atrapados en salas
     * inexistentes.
     * </p>
     *
     * @param uid UID del usuario que abandona la sala.
     */
    private void salirDeSala(String uid) {
        try {
            FirebaseDatabaseService db = new FirebaseDatabaseService();
            String token = MainApp.usuarioActualToken;
            String codigo = SalaContext.codigoSalaActual;

            // Obtenemos el nodo completo "salas/{codigo}".
            String jsonSala = db.leerNodo("salas/" + codigo, token);
            // Si la sala ya no existe, no hay nada que hacer.
            if (jsonSala == null || jsonSala.equals("null")) {
                return;
            }

            // Convertimos el JSON en un mapa para acceder a sus campos.
            Map<String, Object> sala = new Gson().fromJson(jsonSala, Map.class);

            // Obtenemos el UID del host.
            String hostUID = sala.get("host").toString();

            // --- CASO 1: El que sale NO es el host ---
            if (!uid.equals(hostUID)) {

                // Borramos al jugador del nodo "jugadores" y desaparece de la sala para todos los jugadores.
                db.borrarNodo("salas/" + codigo + "/jugadores/" + uid, token);

                // Borramos su nodo salaActual y queda libre.
                db.borrarNodo("usuarios/" + uid + "/salaActual", token);

                return;
            }

            // --- CASO 2: El que sale ES EL HOST ---
            // Borramos sala actual del host.
            db.borrarNodo("usuarios/" + uid + "/salaActual", token);

            // Expulsamos a todos los jugadores borrando su "salaActual".
            // Esto hará que todos los clientes detecten que la sala ha desaparecido.
            Map<String, Object> jugadores = (Map<String, Object>) sala.get("jugadores");

            for (String jugadorUID : jugadores.keySet()) {
                db.borrarNodo("usuarios/" + jugadorUID + "/salaActual", token);
            }

            // Eliminamos la sala al completo de la FB
            db.borrarNodo("salas/" + codigo, token);

        } catch (Exception e) { // Capturamos posible excepción.
            e.printStackTrace();
        }
    }

}
