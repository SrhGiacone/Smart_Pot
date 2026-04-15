package de.manuel.smartpot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class Main {

    private static volatile Integer aktuellerFeuchtigkeitswert = null;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8080), 0);

        server.createContext("/", Main::handleIndex);
        server.createContext("/style.css", exchange ->
                serveStaticFile(exchange, "/web/style.css", "text/css; charset=utf-8"));
        server.createContext("/app.js", exchange ->
                serveStaticFile(exchange, "/web/app.js", "application/javascript; charset=utf-8"));
        server.createContext("/api/status", Main::handleStatus);
        server.createContext("/data", Main::handleData);

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("Server läuft auf:");
        System.out.println("http://localhost:8080");
        System.out.println("Im Netzwerk erreichbar über die IP deines PCs, z. B.:");
        System.out.println("http://DEINE-PC-IP:8080");

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

        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, response.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
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

        sendText(exchange, 200, "OK empfangen: " + prozent + " %");
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