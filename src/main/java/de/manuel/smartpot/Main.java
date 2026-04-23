package de.manuel.smartpot;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;

public class Main {

    private static volatile Integer aktuellerFeuchtigkeitswert = null;

    private static final String NTFY_TOPIC = "Smart-Pot";
    private static final String NTFY_URL = "https://ntfy.sh/" + NTFY_TOPIC;

    private static volatile boolean initialisiert = false;
    private static volatile boolean warUeber75 = false;
    private static volatile Integer letzteTrockenSchwelle = null;

    // Datei, in der gespeichert wird, wann zuletzt gegossen und gedüngt wurde
    private static final String SPEICHER_DATEI = "smartpot-speicher.properties";

    // Format für Datum + Uhrzeit
    private static final DateTimeFormatter ZEIT_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    // Gespeicherte Zeitpunkte
    private static volatile String letzterGiessZeitpunkt = "";
    private static volatile String letzterDuengeZeitpunkt = "";

    public static void main(String[] args) throws IOException {
        // Beim Start gespeicherte Daten laden
        ladeGespeicherteDaten();

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8080), 0);

        server.createContext("/", Main::handleIndex);
        server.createContext("/style.css", exchange ->
                serveStaticFile(exchange, "/web/style.css", "text/css; charset=utf-8"));
        server.createContext("/app.js", exchange ->
                serveStaticFile(exchange, "/web/app.js", "application/javascript; charset=utf-8"));

        server.createContext("/api/status", Main::handleStatus);

        // Neue API-Endpunkte für gespeicherte Aktionen
        server.createContext("/api/actions", Main::handleActions);
        server.createContext("/api/giessen", Main::handleGiessen);
        server.createContext("/api/duengen", Main::handleDuengen);

        // Messwert vom ESP
        server.createContext("/data", Main::handleData);

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("Server läuft auf:");
        System.out.println("http://localhost:8080");
        System.out.println("Im Netzwerk erreichbar über die IP deines PCs, z. B.:");
        System.out.println("http://DEINE-PC-IP:8080");
        System.out.println("ntfy Topic: " + NTFY_TOPIC);
        System.out.println("Speicherdatei: " + SPEICHER_DATEI);

        openBrowser("http://localhost:8080");
    }

    private static void handleIndex(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if (!"/".equals(path)) {
            sendText(exchange, 404, "Seite nicht gefunden");
            return;
        }

        serveStaticFile(exchange, "/web/index.html", "text/html; charset=utf-8");
    }

    private static void handleStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Methode nicht erlaubt");
            return;
        }

        String json;

        if (aktuellerFeuchtigkeitswert == null) {
            json = "{\"wert\":null,\"anzeige\":\"Noch keine Daten\",\"status\":\"waiting\"}";
        } else {
            json = "{\"wert\":" + aktuellerFeuchtigkeitswert +
                    ",\"anzeige\":\"" + aktuellerFeuchtigkeitswert + " %\",\"status\":\"ok\"}";
        }

        sendJson(exchange, 200, json);
    }

    // Gibt die gespeicherten Zeitpunkte für Gießen und Düngen zurück
    private static void handleActions(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Methode nicht erlaubt");
            return;
        }

        String json = "{"
                + "\"letzterGiessZeitpunkt\":" + jsonStringOrNull(letzterGiessZeitpunkt) + ","
                + "\"letzterDuengeZeitpunkt\":" + jsonStringOrNull(letzterDuengeZeitpunkt)
                + "}";

        sendJson(exchange, 200, json);
    }

    // Speichert "zuletzt gegossen"
    private static void handleGiessen(HttpExchange exchange) throws IOException {
        if (!istGetOderPost(exchange)) {
            sendText(exchange, 405, "Methode nicht erlaubt");
            return;
        }

        String zeitpunkt = LocalDateTime.now().format(ZEIT_FORMAT);
        letzterGiessZeitpunkt = zeitpunkt;
        speichereDaten();

        String json = "{"
                + "\"status\":\"ok\","
                + "\"aktion\":\"giessen\","
                + "\"zeitpunkt\":\"" + escapeJson(zeitpunkt) + "\""
                + "}";

        sendJson(exchange, 200, json);
    }

    // Speichert "zuletzt gedüngt"
    private static void handleDuengen(HttpExchange exchange) throws IOException {
        if (!istGetOderPost(exchange)) {
            sendText(exchange, 405, "Methode nicht erlaubt");
            return;
        }

        String zeitpunkt = LocalDateTime.now().format(ZEIT_FORMAT);
        letzterDuengeZeitpunkt = zeitpunkt;
        speichereDaten();

        String json = "{"
                + "\"status\":\"ok\","
                + "\"aktion\":\"duengen\","
                + "\"zeitpunkt\":\"" + escapeJson(zeitpunkt) + "\""
                + "}";

        sendJson(exchange, 200, json);
    }

    private static void handleData(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Methode nicht erlaubt");
            return;
        }

        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = parseQuery(query);
        String wert = params.getOrDefault("wert", "");

        Integer prozent = extrahiereProzent(wert);

        if (prozent == null || prozent < 0 || prozent > 100) {
            sendText(exchange, 400, "Ungültiger Wert. Erlaubt sind nur 0 bis 100.");
            return;
        }

        aktuellerFeuchtigkeitswert = prozent;
        System.out.println("Empfangen vom ESP8266: " + prozent + " %");

        if (!initialisiert) {
            initialisiert = true;
            warUeber75 = prozent > 75;
            letzteTrockenSchwelle = ermittleTrockenSchwelle(prozent);
            System.out.println("Startzustand gesetzt.");
            sendText(exchange, 200, "OK empfangen: " + prozent + " %");
            return;
        }

        if (prozent > 75) {
            if (!warUeber75) {
                sendeDankeBenachrichtigung(prozent);
            }
            warUeber75 = true;
        } else {
            warUeber75 = false;
        }

        if (prozent > 30) {
            letzteTrockenSchwelle = null;
        } else {
            Integer aktuelleTrockenSchwelle = ermittleTrockenSchwelle(prozent);

            if (aktuelleTrockenSchwelle != null) {
                if (letzteTrockenSchwelle == null || aktuelleTrockenSchwelle < letzteTrockenSchwelle) {
                    sendeTrockenBenachrichtigung(aktuelleTrockenSchwelle, prozent);
                    letzteTrockenSchwelle = aktuelleTrockenSchwelle;
                }
            }
        }

        sendText(exchange, 200, "OK empfangen: " + prozent + " %");
    }

    private static Integer ermittleTrockenSchwelle(int prozent) {
        if (prozent <= 0) {
            return 0;
        }
        if (prozent <= 5) {
            return 5;
        }
        if (prozent <= 10) {
            return 10;
        }
        if (prozent <= 15) {
            return 15;
        }
        if (prozent <= 20) {
            return 20;
        }
        if (prozent <= 25) {
            return 25;
        }
        if (prozent <= 30) {
            return 30;
        }
        return null;
    }

    private static void sendeTrockenBenachrichtigung(int schwelle, int prozent) {
        String title;
        String message;
        String priority;

        switch (schwelle) {
            case 30:
                title = "SmartPot Hinweis";
                message = "Ich bin jetzt bei " + prozent + "% Feuchtigkeit. Ein bisschen Wasser wäre bald schön 🌱";
                priority = "2";
                break;
            case 25:
                title = "SmartPot Hinweis";
                message = "Ich habe Durst. Die Erde ist nur noch bei " + prozent + "% Feuchtigkeit.";
                priority = "3";
                break;
            case 20:
                title = "SmartPot Warnung";
                message = "Bitte bald gießen. Ich bin auf " + prozent + "% gefallen.";
                priority = "3";
                break;
            case 15:
                title = "SmartPot Warnung";
                message = "Jetzt wird es ernst. Nur noch " + prozent + "% Feuchtigkeit übrig.";
                priority = "4";
                break;
            case 10:
                title = "SmartPot Dringend";
                message = "Ich brauche dringend Wasser. Feuchtigkeit nur noch bei " + prozent + "%!";
                priority = "4";
                break;
            case 5:
                title = "SmartPot Sehr dringend";
                message = "Bitte sofort gießen! Ich bin fast komplett trocken: " + prozent + "%!";
                priority = "5";
                break;
            case 0:
                title = "SmartPot Notfall";
                message = "Du hast mich garnichmehr lieb :( 0% Feuchtigkeit erreicht!";
                priority = "5";
                break;
            default:
                return;
        }

        sendeNtfyBenachrichtigung(title, message, priority);
    }

    private static void sendeDankeBenachrichtigung(int prozent) {
        String title = "Nachricht vom Baum";
        String message = "Danke fürs Gießen 🌳 Mir geht's wieder richtig gut! Aktuelle Feuchtigkeit: " + prozent + "%";
        sendeNtfyBenachrichtigung(title, message, "3");
    }

    private static void sendeNtfyBenachrichtigung(String title, String message, String priority) {
        HttpURLConnection connection = null;

        try {
            URL url = new URL(NTFY_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            connection.setRequestProperty("Title", title);
            connection.setRequestProperty("Priority", priority);

            byte[] data = message.getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(data);
            }

            int responseCode = connection.getResponseCode();
            System.out.println("ntfy Response: " + responseCode + " | " + title + " | " + message);
        } catch (Exception e) {
            System.out.println("Fehler beim Senden der ntfy Nachricht:");
            e.printStackTrace();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // Lädt gespeicherte Daten beim Programmstart
    private static void ladeGespeicherteDaten() {
        Path path = Paths.get(SPEICHER_DATEI);

        if (!Files.exists(path)) {
            System.out.println("Keine Speicherdatei gefunden. Starte mit leeren Werten.");
            return;
        }

        Properties properties = new Properties();

        try (InputStream is = Files.newInputStream(path)) {
            properties.load(is);

            letzterGiessZeitpunkt = properties.getProperty("letzterGiessZeitpunkt", "");
            letzterDuengeZeitpunkt = properties.getProperty("letzterDuengeZeitpunkt", "");

            System.out.println("Gespeicherte Daten geladen.");
        } catch (IOException e) {
            System.out.println("Fehler beim Laden der Speicherdatei:");
            e.printStackTrace();
        }
    }

    // Speichert aktuelle Daten in Datei
    private static void speichereDaten() {
        Properties properties = new Properties();
        properties.setProperty("letzterGiessZeitpunkt", letzterGiessZeitpunkt == null ? "" : letzterGiessZeitpunkt);
        properties.setProperty("letzterDuengeZeitpunkt", letzterDuengeZeitpunkt == null ? "" : letzterDuengeZeitpunkt);

        try (OutputStream os = Files.newOutputStream(Paths.get(SPEICHER_DATEI))) {
            properties.store(os, "Smart Pot gespeicherte Daten");
            System.out.println("Daten gespeichert.");
        } catch (IOException e) {
            System.out.println("Fehler beim Speichern der Daten:");
            e.printStackTrace();
        }
    }

    private static void serveStaticFile(HttpExchange exchange, String resourcePath, String contentType) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Methode nicht erlaubt");
            return;
        }

        try (InputStream is = Main.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                sendText(exchange, 404, "Datei nicht gefunden: " + resourcePath);
                return;
            }

            byte[] data = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, data.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }
    }

    private static void sendText(HttpExchange exchange, int statusCode, String text) throws IOException {
        byte[] response = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, response.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    private static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, response.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    private static boolean istGetOderPost(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        return "GET".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method);
    }

    private static String jsonStringOrNull(String value) {
        if (value == null || value.isBlank()) {
            return "null";
        }
        return "\"" + escapeJson(value) + "\"";
    }

    private static String escapeJson(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            } else {
                System.out.println("Browser konnte nicht automatisch geöffnet werden.");
            }
        } catch (Exception e) {
            System.out.println("Fehler beim Öffnen des Browsers:");
            e.printStackTrace();
        }
    }

    private static Integer extrahiereProzent(String wert) {
        if (wert == null) {
            return null;
        }

        String nurZahlen = wert.replaceAll("[^0-9]", "");

        if (nurZahlen.isEmpty()) {
            return null;
        }

        try {
            return Integer.parseInt(nurZahlen);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();

        if (query == null || query.isEmpty()) {
            return map;
        }

        String[] pairs = query.split("&");

        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);

            if (keyValue.length == 2) {
                String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
                map.put(key, value);
            }
        }

        return map;
    }
}