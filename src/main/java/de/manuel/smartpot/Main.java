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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class Main {

    // Speichert den zuletzt empfangenen Feuchtigkeitswert
    // volatile = damit mehrere Threads immer den aktuellen Wert sehen
    private static volatile Integer aktuellerFeuchtigkeitswert = null;

    // ntfy Topic-Name
    // Diesen Topic-Namen musst du in der ntfy-App abonnieren
    private static final String NTFY_TOPIC = "Smart-Pot";

    // Fertige URL für ntfy
    private static final String NTFY_URL = "https://ntfy.sh/" + NTFY_TOPIC;

    // Merkt sich, ob schon mindestens ein erster Messwert angekommen ist
    // Damit beim allerersten Wert nicht sofort eine Benachrichtigung gesendet wird
    private static volatile boolean initialisiert = false;

    // Merkt sich, ob der Wert zuletzt über 75% war
    // Dadurch schicken wir die "Danke fürs Gießen"-Nachricht nur beim Übergang über 75%
    private static volatile boolean warUeber75 = false;

    // Merkt sich die letzte Trockenheits-Schwelle, für die schon benachrichtigt wurde
    // Beispiel: Wenn schon eine Meldung bei 25% gesendet wurde, soll sie nicht nochmal gesendet werden
    private static volatile Integer letzteTrockenSchwelle = null;

    public static void main(String[] args) throws IOException {

        // HTTP-Server auf Port 8080 starten
        // 0.0.0.0 = erreichbar auf localhost und im Netzwerk
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8080), 0);

        // Route für Startseite
        server.createContext("/", Main::handleIndex);

        // Route für CSS-Datei
        server.createContext("/style.css", exchange ->
                serveStaticFile(exchange, "/web/style.css", "text/css; charset=utf-8"));

        // Route für JavaScript-Datei
        server.createContext("/app.js", exchange ->
                serveStaticFile(exchange, "/web/app.js", "application/javascript; charset=utf-8"));

        // API-Route für aktuellen Status als JSON
        server.createContext("/api/status", Main::handleStatus);

        // Route, über die der ESP8266 neue Messwerte sendet
        server.createContext("/data", Main::handleData);

        // Thread-Pool für mehrere gleichzeitige Anfragen
        server.setExecutor(Executors.newCachedThreadPool());

        // Server starten
        server.start();

        // Infos in die Konsole schreiben
        System.out.println("Server läuft auf:");
        System.out.println("http://localhost:8080");
        System.out.println("Im Netzwerk erreichbar über die IP deines PCs, z. B.:");
        System.out.println("http://DEINE-PC-IP:8080");
        System.out.println("ntfy Topic: " + NTFY_TOPIC);

        // Browser automatisch öffnen
        openBrowser("http://localhost:8080");
    }

    // Behandelt Aufrufe auf "/"
    private static void handleIndex(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        // Nur exakt "/" erlauben
        if (!"/".equals(path)) {
            sendText(exchange, 404, "Seite nicht gefunden");
            return;
        }

        // index.html aus den Ressourcen laden und zurückgeben
        serveStaticFile(exchange, "/web/index.html", "text/html; charset=utf-8");
    }

    // Liefert den aktuellen Feuchtigkeitsstatus als JSON zurück
    private static void handleStatus(HttpExchange exchange) throws IOException {

        // Nur GET-Anfragen erlauben
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Methode nicht erlaubt");
            return;
        }

        String json;

        // Wenn noch kein Messwert da ist
        if (aktuellerFeuchtigkeitswert == null) {
            json = "{\"wert\":null,\"anzeige\":\"Noch keine Daten\",\"status\":\"waiting\"}";
        } else {
            // Sonst aktuellen Wert ins JSON schreiben
            json = "{\"wert\":" + aktuellerFeuchtigkeitswert +
                    ",\"anzeige\":\"" + aktuellerFeuchtigkeitswert + " %\",\"status\":\"ok\"}";
        }

        // JSON als UTF-8 in Bytes umwandeln
        byte[] response = json.getBytes(StandardCharsets.UTF_8);

        // Header setzen
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");

        // Antwort senden
        exchange.sendResponseHeaders(200, response.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    // Verarbeitet neue Messwerte vom ESP8266
    // Beispiel-Aufruf: /data?wert=25
    private static void handleData(HttpExchange exchange) throws IOException {

        // Nur GET-Anfragen erlauben
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Methode nicht erlaubt");
            return;
        }

        // Query-Teil der URL holen, also z. B. "wert=25"
        String query = exchange.getRequestURI().getQuery();

        // Query in eine Map umwandeln
        Map<String, String> params = parseQuery(query);

        // Wert auslesen, falls nicht vorhanden: leerer String
        String wert = params.getOrDefault("wert", "");

        // Aus String nur die Prozentzahl extrahieren
        Integer prozent = extrahiereProzent(wert);

        // Prüfen ob der Wert gültig ist
        if (prozent == null || prozent < 0 || prozent > 100) {
            sendText(exchange, 400, "Ungültiger Wert. Erlaubt sind nur 0 bis 100.");
            return;
        }

        // Aktuellen Feuchtigkeitswert speichern
        aktuellerFeuchtigkeitswert = prozent;

        System.out.println("Empfangen vom ESP8266: " + prozent + " %");

        // Beim ersten Messwert nur Startzustand setzen, noch keine Nachricht schicken
        if (!initialisiert) {
            initialisiert = true;

            // Merken, ob der Wert direkt schon über 75 ist
            warUeber75 = prozent > 75;

            // Merken, in welcher Trockenheitsstufe wir gerade sind
            letzteTrockenSchwelle = ermittleTrockenSchwelle(prozent);

            System.out.println("Startzustand gesetzt.");
            sendText(exchange, 200, "OK empfangen: " + prozent + " %");
            return;
        }

        // Wenn der Wert über 75 steigt, soll eine Dankesnachricht gesendet werden
        if (prozent > 75) {

            // Nur senden, wenn wir vorher noch NICHT über 75 waren
            if (!warUeber75) {
                sendeDankeBenachrichtigung(prozent);
            }

            // Zustand merken
            warUeber75 = true;
        } else {
            // Wenn nicht über 75, zurücksetzen
            warUeber75 = false;
        }

        // Wenn der Wert über 30 liegt, ist der Topf nicht trocken genug für Warnstufen
        if (prozent > 30) {

            // Trocken-Schwelle zurücksetzen
            // Dadurch kann beim nächsten Absinken wieder neu benachrichtigt werden
            letzteTrockenSchwelle = null;

        } else {
            // Aktuelle Trockenheits-Schwelle ermitteln
            Integer aktuelleTrockenSchwelle = ermittleTrockenSchwelle(prozent);

            if (aktuelleTrockenSchwelle != null) {

                // Nur benachrichtigen, wenn wir tiefer gefallen sind als die letzte bekannte Schwelle
                if (letzteTrockenSchwelle == null || aktuelleTrockenSchwelle < letzteTrockenSchwelle) {
                    sendeTrockenBenachrichtigung(aktuelleTrockenSchwelle, prozent);

                    // Neue Schwelle merken, damit nicht doppelt gesendet wird
                    letzteTrockenSchwelle = aktuelleTrockenSchwelle;
                }
            }
        }

        // Antwort an den ESP senden
        sendText(exchange, 200, "OK empfangen: " + prozent + " %");
    }

    // Ordnet einen Prozentwert einer Trockenheits-Schwelle zu
    // Beispiel:
    // 29 -> 30
    // 22 -> 25
    // 4  -> 5
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

        // Über 30 gibt es keine Trockenheitswarnung
        return null;
    }

    // Baut abhängig von der Schwelle die passende Warnnachricht zusammen
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
                message = "Hilfe! 0% Feuchtigkeit erreicht. Bitte sofort gießen!";
                priority = "5";
                break;
            default:
                return;
        }

        // Fertige Nachricht an ntfy senden
        sendeNtfyBenachrichtigung(title, message, priority);
    }

    // Sendet eine Dankesnachricht, wenn nach dem Gießen der Wert über 75% steigt
    private static void sendeDankeBenachrichtigung(int prozent) {
        String title = "Nachricht vom Baum";
        String message = "Danke fürs Gießen 🌳 Mir geht's wieder richtig gut! Aktuelle Feuchtigkeit: " + prozent + "%";

        sendeNtfyBenachrichtigung(title, message, "3");
    }

    // Sendet eine Push-Nachricht an ntfy
    private static void sendeNtfyBenachrichtigung(String title, String message, String priority) {
        HttpURLConnection connection = null;

        try {
            // Verbindung zu ntfy aufbauen
            URL url = new URL(NTFY_URL);
            connection = (HttpURLConnection) url.openConnection();

            // POST = Nachricht senden
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);

            // Zeitlimits setzen
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            // HTTP-Header setzen
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            connection.setRequestProperty("Title", title);
            connection.setRequestProperty("Priority", priority);

            // Nachrichtentext in Bytes umwandeln
            byte[] data = message.getBytes(StandardCharsets.UTF_8);

            // Daten an ntfy schicken
            try (OutputStream os = connection.getOutputStream()) {
                os.write(data);
            }

            // Antwortcode lesen
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

    // Liefert eine Datei aus dem resources/web Ordner aus
    // z. B. index.html, style.css oder app.js
    private static void serveStaticFile(HttpExchange exchange, String resourcePath, String contentType) throws IOException {

        // Nur GET-Anfragen erlauben
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Methode nicht erlaubt");
            return;
        }

        // Datei aus den Projekt-Ressourcen laden
        try (InputStream is = Main.class.getResourceAsStream(resourcePath)) {

            // Wenn Datei nicht gefunden wurde
            if (is == null) {
                sendText(exchange, 404, "Datei nicht gefunden: " + resourcePath);
                return;
            }

            // Ganze Datei lesen
            byte[] data = is.readAllBytes();

            // Content-Type setzen
            exchange.getResponseHeaders().set("Content-Type", contentType);

            // Datei senden
            exchange.sendResponseHeaders(200, data.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }
    }

    // Sendet einfachen Text als HTTP-Antwort zurück
    private static void sendText(HttpExchange exchange, int statusCode, String text) throws IOException {
        byte[] response = text.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, response.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    // Öffnet automatisch den Browser mit der angegebenen URL
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

    // Holt nur die Zahl aus dem übergebenen String
    // Beispiele:
    // "25" -> 25
    // "25%" -> 25
    // "Feuchte: 25%" -> 25
    private static Integer extrahiereProzent(String wert) {
        if (wert == null) {
            return null;
        }

        // Alles außer Zahlen entfernen
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

    // Zerlegt den Query-String in Schlüssel/Wert-Paare
    // Beispiel:
    // "wert=25&name=baum" -> Map mit wert=25 und name=baum
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();

        if (query == null || query.isEmpty()) {
            return map;
        }

        // Einzelne Parameter trennen
        String[] pairs = query.split("&");

        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);

            if (keyValue.length == 2) {
                // URL-kodierte Zeichen wieder lesbar machen
                String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);

                map.put(key, value);
            }
        }

        return map;
    }
}