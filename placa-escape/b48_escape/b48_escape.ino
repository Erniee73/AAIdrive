/*
 * B48 Escape: placa ESP32-C3 SuperMini que "pulsa" los botones ON/OFF
 * del mando de repuesto del módulo de la válvula de escape.
 *
 * - Se anuncia por Bluetooth (BLE) como "B48-Escape".
 * - Solo acepta órdenes de un móvil emparejado (escritura cifrada).
 * - Órdenes: "O" = abrir (pulsa ON), "C" = cerrar (pulsa OFF).
 * - Al arrancar, cierra la válvula.
 * - Si el móvil se desconecta más de 5 segundos, cierra la válvula.
 * - El LED azul de la placa se enciende mientras hay un móvil conectado.
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <BLESecurity.h>

// ---- Pines (ESP32-C3 SuperMini) ----
#define PIN_ON   3      // al optoacoplador del botón ON
#define PIN_OFF  4      // al optoacoplador del botón OFF
#define PIN_LED  8      // LED azul de la placa (se enciende con LOW)

// ---- Ajustes ----
#define PULSE_MS            400    // cuánto tiempo se "pulsa" el botón
#define CLOSE_AFTER_LOST_MS 5000   // cerrar si se pierde la conexión más de esto
#define BOOT_DELAY_MS       1500   // espera al arrancar antes de cerrar

#define DEVICE_NAME  "B48-Escape"
#define SERVICE_UUID "6b7a0001-b48a-4c1e-9d3e-0e5c0a1f4800"
#define CMD_UUID     "6b7a0002-b48a-4c1e-9d3e-0e5c0a1f4800"   // escribir "O" o "C"
#define STATE_UUID   "6b7a0003-b48a-4c1e-9d3e-0e5c0a1f4800"   // leer/avisos: "open" o "closed"

BLECharacteristic *stateChar = nullptr;

volatile char pendingCommand = 0;      // 'O', 'C' o 0
volatile bool connected = false;
volatile unsigned long lostAt = 0;
volatile bool closeAfterLost = false;
bool valveOpen = false;

void press(int pin) {
  digitalWrite(pin, HIGH);
  delay(PULSE_MS);
  digitalWrite(pin, LOW);
}

void setValve(bool open) {
  press(open ? PIN_ON : PIN_OFF);
  valveOpen = open;
  if (stateChar != nullptr) {
    stateChar->setValue(open ? "open" : "closed");
    if (connected) stateChar->notify();
  }
  Serial.println(open ? "Valvula: ABIERTA" : "Valvula: CERRADA");
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    connected = true;
    closeAfterLost = false;
    digitalWrite(PIN_LED, LOW);
    Serial.println("Movil conectado");
  }
  void onDisconnect(BLEServer *server) override {
    connected = false;
    lostAt = millis();
    closeAfterLost = true;
    digitalWrite(PIN_LED, HIGH);
    Serial.println("Movil desconectado");
    BLEDevice::startAdvertising();
  }
};

class CommandCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    auto value = characteristic->getValue();
    if (value.length() == 0) return;
    char command = value[0];
    if (command == 'O' || command == 'C') {
      pendingCommand = command;
    }
  }
};

void setup() {
  Serial.begin(115200);
  pinMode(PIN_ON, OUTPUT);
  pinMode(PIN_OFF, OUTPUT);
  pinMode(PIN_LED, OUTPUT);
  digitalWrite(PIN_ON, LOW);
  digitalWrite(PIN_OFF, LOW);
  digitalWrite(PIN_LED, HIGH);

  // al arrancar, válvula cerrada
  delay(BOOT_DELAY_MS);
  setValve(false);

  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setEncryptionLevel(ESP_BLE_SEC_ENCRYPT);

  // emparejamiento sin PIN (el móvil se empareja una vez desde Ajustes > Bluetooth)
  BLESecurity *security = new BLESecurity();
  security->setAuthenticationMode(ESP_LE_AUTH_REQ_SC_BOND);
  security->setCapability(ESP_IO_CAP_NONE);
  security->setInitEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);

  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  BLEService *service = server->createService(SERVICE_UUID);

  BLECharacteristic *cmdChar = service->createCharacteristic(CMD_UUID,
      BLECharacteristic::PROPERTY_WRITE);
  cmdChar->setAccessPermissions(ESP_GATT_PERM_WRITE_ENCRYPTED);   // solo móviles emparejados
  cmdChar->setCallbacks(new CommandCallbacks());

  stateChar = service->createCharacteristic(STATE_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  stateChar->setAccessPermissions(ESP_GATT_PERM_READ_ENCRYPTED);
  stateChar->addDescriptor(new BLE2902());
  stateChar->setValue("closed");

  service->start();
  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("B48-Escape listo");
}

void loop() {
  char command = pendingCommand;
  if (command != 0) {
    pendingCommand = 0;
    setValve(command == 'O');
  }

  if (!connected && closeAfterLost && millis() - lostAt > CLOSE_AFTER_LOST_MS) {
    closeAfterLost = false;
    // se pulsa OFF siempre, por si se abrió con el mando de mano
    Serial.println("Conexion perdida: cerrando");
    setValve(false);
  }
  delay(20);
}
