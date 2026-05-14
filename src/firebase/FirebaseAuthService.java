package firebase;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

public class FirebaseAuthService {

    private static final String SIGN_UP_URL
            = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=" + FirebaseConfig.API_KEY;

    private static final String SIGN_IN_URL
            = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=" + FirebaseConfig.API_KEY;

    private final Gson gson = new Gson();

    private String idToken; // token de sesión
    private String localId; // UID del usuario

    public String getIdToken() {
        return idToken;
    }

    public String getLocalId() {
        return localId;
    }

    // ---------------- REGISTRO ----------------
    public String register(String email, String password) throws IOException {
        AuthRequest request = new AuthRequest(email, password, true);
        String jsonRequest = gson.toJson(request);

        String jsonResponse = postJson(SIGN_UP_URL, jsonRequest);
        if (jsonResponse == null) {
            return "NETWORK_ERROR";
        }

        // Si contiene idToken → registro correcto
        if (jsonResponse.contains("idToken")) {
            AuthResponse response = gson.fromJson(jsonResponse, AuthResponse.class);
            this.idToken = response.idToken;
            this.localId = response.localId;
            return "OK";
        }

        // Interpretar errores de Firebase
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

    // ---------------- LOGIN ----------------
    public String login(String email, String password) throws IOException {
        AuthRequest request = new AuthRequest(email, password, true);
        String jsonRequest = gson.toJson(request);

        String jsonResponse = postJson(SIGN_IN_URL, jsonRequest);
        if (jsonResponse == null) {
            return "NETWORK_ERROR";
        }

        // Login correcto
        if (jsonResponse.contains("idToken")) {
            AuthResponse response = gson.fromJson(jsonResponse, AuthResponse.class);
            this.idToken = response.idToken;
            this.localId = response.localId;
            return "OK";
        }

        // Errores reales de Firebase
        if (jsonResponse.contains("INVALID_LOGIN_CREDENTIALS")) {
            return "CREDENCIALES_INCORRECTAS";
        }

        if (jsonResponse.contains("USER_DISABLED")) {
            return "USER_DISABLED";
        }
        if (jsonResponse.contains("INVALID_EMAIL")) {
            return "INVALID_EMAIL";
        }

        // Error moderno de Firebase
        if (jsonResponse.contains("INVALID_LOGIN_CREDENTIALS")) {
            return "INVALID_PASSWORD";
        }

        return "UNKNOWN_ERROR";
    }

    // ---------------- HTTP POST JSON ----------------
    private String postJson(String urlString, String jsonBody) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonBody.getBytes("utf-8");
            os.write(input, 0, input.length);
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "utf-8"))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line.trim());
            }
            return response.toString();
        }
    }

    // ---------------- CLASES AUXILIARES ----------------
    private static class AuthRequest {

        String email;
        String password;
        @SerializedName("returnSecureToken")
        boolean returnSecureToken;

        AuthRequest(String email, String password, boolean returnSecureToken) {
            this.email = email;
            this.password = password;
            this.returnSecureToken = returnSecureToken;
        }
    }

    private static class AuthResponse {

        @SerializedName("idToken")
        String idToken;

        @SerializedName("localId")
        String localId;

        @SerializedName("email")
        String email;
    }
}
