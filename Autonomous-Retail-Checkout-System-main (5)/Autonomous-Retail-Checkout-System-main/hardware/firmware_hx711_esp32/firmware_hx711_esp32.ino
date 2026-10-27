/*
  Autonomous Retail Smart Shelf - HX711 Firmware
  Target: ESP32 / Arduino / RP2040 / STM32
  
  Functions:
  1. Reads 24-bit ADC counts from HX711 load cell amplifier.
  2. Runs a 5-point median filter to reject electromagnetic transient spikes.
  3. Formats calibrated weight / raw telemetry as JSON lines over USB-Serial (115200 baud).
  4. Accepts remote serial commands from host:
     - "TARE"         -> Recalibrates zero baseline
     - "CALIB:<g>"    -> Computes calibration factor with placed reference weight
*/

#include <Arduino.h>

// Pin Definitions (Configure to match physical board wiring)
#define HX711_DOUT_PIN  16  // Data Out pin (DT)
#define HX711_SCK_PIN   17  // Power Down and Serial Clock pin (SCK)

// Calibration and Filtering Defaults
const char SENSOR_ID[] = "SHELF_01";
long zero_offset = 0;
float scale_factor = 420.0f; // Counts per gram (calibrated value)
const int MEDIAN_WINDOW = 5;

// Read 24-bit two's complement value from HX711
long readHX711Raw() {
  // Wait until DOUT goes LOW
  unsigned long start = millis();
  while (digitalRead(HX711_DOUT_PIN) == HIGH) {
    if (millis() - start > 100) return 0; // Timeout
  }

  unsigned long count = 0;
  for (int i = 0; i < 24; i++) {
    digitalWrite(HX711_SCK_PIN, HIGH);
    delayMicroseconds(1);
    count = (count << 1) | digitalRead(HX711_DOUT_PIN);
    digitalWrite(HX711_SCK_PIN, LOW);
    delayMicroseconds(1);
  }

  // 25th pulse for Channel A, 128 gain
  digitalWrite(HX711_SCK_PIN, HIGH);
  delayMicroseconds(1);
  digitalWrite(HX711_SCK_PIN, LOW);
  delayMicroseconds(1);

  // Sign extension for 24-bit two's complement
  if (count & 0x800000) {
    count |= 0xFF000000;
  }
  return (long)count;
}

// Simple 5-sample median filter
long getFilteredReading() {
  long samples[MEDIAN_WINDOW];
  for (int i = 0; i < MEDIAN_WINDOW; i++) {
    samples[i] = readHX711Raw();
    delay(2);
  }
  // Bubble sort to find median
  for (int i = 0; i < MEDIAN_WINDOW - 1; i++) {
    for (int j = i + 1; j < MEDIAN_WINDOW; j++) {
      if (samples[i] > samples[j]) {
        long tmp = samples[i];
        samples[i] = samples[j];
        samples[j] = tmp;
      }
    }
  }
  return samples[MEDIAN_WINDOW / 2];
}

void tare() {
  long sum = 0;
  for (int i = 0; i < 10; i++) {
    sum += getFilteredReading();
    delay(5);
  }
  zero_offset = sum / 10;
  Serial.print("{\"event\":\"TARE_OK\",\"sensor_id\":\"");
  Serial.print(SENSOR_ID);
  Serial.print("\",\"zero_offset\":");
  Serial.print(zero_offset);
  Serial.println("}");
}

void setup() {
  Serial.begin(115200);
  pinMode(HX711_DOUT_PIN, INPUT);
  pinMode(HX711_SCK_PIN, OUTPUT);
  digitalWrite(HX711_SCK_PIN, LOW);

  delay(500);
  tare(); // Automatic initial tare on boot
}

void loop() {
  // 1. Process host commands from Python
  if (Serial.available()) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();
    if (cmd == "TARE") {
      tare();
    } else if (cmd.startsWith("CALIB:")) {
      float known_g = cmd.substring(6).toFloat();
      if (known_g > 0) {
        long current_raw = getFilteredReading();
        scale_factor = (float)(current_raw - zero_offset) / known_g;
        Serial.print("{\"event\":\"CALIB_OK\",\"scale_factor\":");
        Serial.print(scale_factor);
        Serial.println("}");
      }
    }
  }

  // 2. Sample and compute weight
  long raw_val = getFilteredReading();
  float weight_g = (float)(raw_val - zero_offset) / scale_factor;

  // 3. Emit structured JSON telemetry packet
  Serial.print("{\"sensor_id\":\"");
  Serial.print(SENSOR_ID);
  Serial.print("\",\"raw\":");
  Serial.print(raw_val);
  Serial.print(",\"weight_g\":");
  Serial.print(weight_g, 2);
  Serial.print(",\"ts_ms\":");
  Serial.print(millis());
  Serial.println("}");

  // Regulate sample rate (~30-50 Hz)
  delay(25);
}
