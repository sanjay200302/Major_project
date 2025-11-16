#include "BluetoothSerial.h"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error Bluetooth is not enabled! Please run `make menuconfig` to enable it
#endif

BluetoothSerial SerialBT;

// Pins (adjust if your board differs)
const int blueLedPin    = 2;    // Built-in LED (GPIO2 on many ESP32 dev boards)
const int bootButtonPin = 0;    // BOOT button (GPIO0), active LOW

// Triple-press detection (aligns with the app's 2s window)
const unsigned long MULTI_PRESS_WINDOW_MS = 2000;
const int REQUIRED_PRESSES = 3;

// Connection management
volatile bool btConnected = false;
bool wasConnected = false;
unsigned long lastReconnectAttempt = 0;
const unsigned long RECONNECT_INTERVAL = 3000; // Try to reconnect every 3 seconds

// Button press variables
int pressCount = 0;
unsigned long windowStartMs = 0;
unsigned long lastDebounceMs = 0;
const unsigned long debounceMs = 40;

// Enhanced callback to handle connection states
static void btCallback(esp_spp_cb_event_t event, esp_spp_cb_param_t *param) {
  switch (event) {
    case ESP_SPP_SRV_OPEN_EVT: // Client connected
      btConnected = true;
      wasConnected = true;
      Serial.println("Android device connected");
      break;

    case ESP_SPP_CLOSE_EVT: // Client disconnected
      btConnected = false;
      Serial.println("Android device disconnected - will attempt to reconnect");
      lastReconnectAttempt = millis(); // Start reconnect timer
      break;

    case ESP_SPP_INIT_EVT:
      Serial.println("Bluetooth initialized");
      break;

    case ESP_SPP_DISCOVERY_COMP_EVT:
      Serial.println("Service discovery completed");
      break;

    default:
      break;
  }
}

void setup() {
  pinMode(blueLedPin, OUTPUT);
  pinMode(bootButtonPin, INPUT_PULLUP);

  digitalWrite(blueLedPin, HIGH); // ON while waiting for BT
  Serial.begin(115200);

  // Initialize Bluetooth with better configuration
  SerialBT.begin("ESP32_SheGuard"); // Device name
  SerialBT.register_callback(btCallback);

  // Optional PIN for pairing
  const char* pin = "1234";
  SerialBT.setPin(pin, 4);

  SerialBT.setTimeout(100); // Read timeout

  Serial.println("Bluetooth SPP started. Device name: ESP32_SheGuard");
  Serial.println("Pair and connect from your Android app");
  Serial.println("Auto-reconnect is enabled");
}

void loop() {
  handleConnection();    // Manage Bluetooth connection
  updateLedStatus();     // Update LED based on connection state
  handleButton();        // Check for button presses

  delay(10);
}

void handleConnection() {
  unsigned long currentTime = millis();

  // If not connected and it's time to attempt reconnect
  if (!btConnected && wasConnected && (currentTime - lastReconnectAttempt >= RECONNECT_INTERVAL)) {
    attemptReconnect();
    lastReconnectAttempt = currentTime;
  }

  // If never connected or lost initial connection, keep advertising
  if (!btConnected && !wasConnected) {
    if (!SerialBT.hasClient()) {
      static unsigned long lastAdvertiseCheck = 0;
      if (currentTime - lastAdvertiseCheck > 5000) {
        Serial.println("Waiting for connection...");
        lastAdvertiseCheck = currentTime;
      }
    }
  }
}

void attemptReconnect() {
  Serial.println("Attempting to reconnect...");
  if (!SerialBT.connected()) {
    Serial.println("Advertising for connections...");
    // Blink to indicate reconnect attempt
    digitalWrite(blueLedPin, HIGH);
    delay(100);
    digitalWrite(blueLedPin, LOW);
    delay(100);
    digitalWrite(blueLedPin, HIGH);
  }
}

void updateLedStatus() {
  static unsigned long lastBlink = 0;
  static bool ledState = HIGH;
  unsigned long currentTime = millis();

  if (btConnected) {
    // When connected - LED is OFF
    digitalWrite(blueLedPin, LOW);
  } else {
    // Disconnected: blink pattern
    if (wasConnected) {
      // Fast blink if previously connected
      if (currentTime - lastBlink >= 500) {
        lastBlink = currentTime;
        ledState = !ledState;
        digitalWrite(blueLedPin, ledState);
      }
    } else {
      // Slow blink if never connected
      if (currentTime - lastBlink >= 1000) {
        lastBlink = currentTime;
        ledState = !ledState;
        digitalWrite(blueLedPin, ledState);
      }
    }
  }
}

void handleButton() {
  // Active LOW button with debounce
  bool pressed = (digitalRead(bootButtonPin) == LOW);
  unsigned long now = millis();

  static bool prevPressed = false;
  static bool inHold = false;

  // Debounce
  if (pressed != prevPressed) {
    lastDebounceMs = now;
    prevPressed = pressed;
  }
  if ((now - lastDebounceMs) < debounceMs) {
    return;
  }

  // Rising-edge on release of active-LOW button
  if (!pressed && inHold) {
    inHold = false;
    onButtonClick();
  } else if (pressed && !inHold) {
    inHold = true; // begin hold until release
  }
}

void onButtonClick() {
  unsigned long now = millis();

  // Send a short newline-terminated message per press to avoid coalescing in Android
  if (btConnected) {
    SerialBT.println("P");  // distinct frame for each press
    SerialBT.flush();
    Serial.println("Sent button press to app");
  } else {
    Serial.println("Button pressed but no connection");
    // Visual feedback for button press without connection
    digitalWrite(blueLedPin, HIGH);
    delay(50);
    digitalWrite(blueLedPin, LOW);
  }

  if (now - windowStartMs > MULTI_PRESS_WINDOW_MS) {
    windowStartMs = now;
    pressCount = 0;
  }
  pressCount++;

  if (pressCount >= REQUIRED_PRESSES) {
    pressCount = 0;
    windowStartMs = 0;

    // Send exact trigger the Android app listens for
    if (btConnected) {
      SerialBT.println("TRIPLE_PRESS_ACTION"); // exact payload expected by app
      SerialBT.flush();
      Serial.println("Sent TRIPLE_PRESS_ACTION - SOS Triggered!");

      // Visual confirmation
      for (int i = 0; i < 3; i++) {
        digitalWrite(blueLedPin, HIGH);
        delay(100);
        digitalWrite(blueLedPin, LOW);
        delay(100);
      }
    } else {
      Serial.println("Triple press detected, but no Bluetooth connection.");
      // Error pattern
      for (int i = 0; i < 5; i++) {
        digitalWrite(blueLedPin, HIGH);
        delay(50);
        digitalWrite(blueLedPin, LOW);
        delay(50);
      }
    }
  }
}

void blinkLed(int times, int delayMs) {
  for (int i = 0; i < times; i++) {
    digitalWrite(blueLedPin, HIGH);
    delay(delayMs);
    digitalWrite(blueLedPin, LOW);
    delay(delayMs);
  }
}