#include <ESP8266WiFi.h>
#include <ESP8266HTTPClient.h>
#include <U8g2lib.h>

#define WIFI_SSID "iPhone"
#define WIFI_PSWD "ZalmanZ11+"

const char* serverHost = "172.20.10.4";
const int serverPort = 8080;

const int ledPin = LED_BUILTIN;

int counter = 0;
int sendValue = 100;

U8G2_SSD1306_128X64_NONAME_F_SW_I2C display(
  U8G2_R0,
  /* clock=*/ 14,
  /* data=*/ 12,
  /* reset=*/ U8X8_PIN_NONE
);

void blinkLed() {
  digitalWrite(ledPin, LOW);
  delay(200);
  digitalWrite(ledPin, HIGH);
}

String partText(String text, int startPos, int maxLen) {
  if (startPos >= text.length()) return "";
  return text.substring(startPos, startPos + maxLen);
}

void showDisplay(
  String line1 = "",
  String line2 = "",
  String line3 = "",
  String line4 = "",
  String line5 = ""
) {
  display.clearBuffer();
  display.setFont(u8g2_font_6x12_tf);

  if (line1.length()) display.drawStr(0, 12, line1.c_str());
  if (line2.length()) display.drawStr(0, 24, line2.c_str());
  if (line3.length()) display.drawStr(0, 36, line3.c_str());
  if (line4.length()) display.drawStr(0, 48, line4.c_str());
  if (line5.length()) display.drawStr(0, 60, line5.c_str());

  display.sendBuffer();
}

void connectWiFi() {
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PSWD);

  Serial.print("Verbinde mit: ");
  Serial.println(WIFI_SSID);

  showDisplay("Verbinde WLAN...", WIFI_SSID);

  unsigned long start = millis();

  while (WiFi.status() != WL_CONNECTED && millis() - start < 20000) {
    delay(500);
    Serial.print(".");
  }

  Serial.println();

  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("WLAN verbunden");
    Serial.print("ESP IP: ");
    Serial.println(WiFi.localIP());

    showDisplay("WLAN verbunden", WiFi.localIP().toString());
    blinkLed();
  } else {
    Serial.println("WLAN NICHT verbunden");
    showDisplay("WLAN Fehler", "Status: " + String(WiFi.status()));
  }
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  pinMode(ledPin, OUTPUT);
  digitalWrite(ledPin, HIGH);

  display.begin();
  display.setI2CAddress(0x78);

  showDisplay("OLED gestartet");
  delay(1000);

  connectWiFi();
}

void loop() {
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("WLAN weg - neuer Verbindungsversuch");
    showDisplay("WLAN weg", "Neuer Versuch...");
    connectWiFi();
    delay(3000);
    return;
  }

  WiFiClient client;
  HTTPClient http;

  String url = "http://" + String(serverHost) + ":" + String(serverPort) + "/data?wert=" + String(sendValue);

  showDisplay(
    "Sende Daten...",
    "Nr: " + String(counter),
    "Wert: " + String(sendValue),
    "an Server",
    url
  );

  blinkLed();

  http.begin(client, url);
  int httpCode = http.GET();

  String response = "";
  if (httpCode > 0) {
    response = http.getString();
  }

  Serial.println("------");
  Serial.print("Sende Nr: ");
  Serial.println(counter);
  Serial.print("Wert: ");
  Serial.println(sendValue);
  Serial.print("URL: ");
  Serial.println(url);
  Serial.print("HTTP Code: ");
  Serial.println(httpCode);
  Serial.print("Antwort: ");
  Serial.println(response);

  blinkLed();

  showDisplay(
    "Gesendet Nr: " + String(counter),
    "Wert: " + String(sendValue),
    "HTTP: " + String(httpCode),
    "Antwort:",
    partText(response, 0, 20)
  );

  http.end();
  counter++;

  // Nächsten Wert vorbereiten: 100 -> 95 -> ... -> 0 -> 100
  if (sendValue <= 0) {
    sendValue = 100;
  } else {
    sendValue -= 5;
  }

  delay(5000);
}