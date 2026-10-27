"""Production hardware drivers for HX711 load cell weight sensors.

Supports two physical deployment topologies:
1. SerialHX711Provider: Communicates via USB/UART with a microcontroller (ESP32/RP2040/Arduino)
   running the HX711 firmware. Ideal for PC, Industrial Edge Gateways, and NVIDIA Jetson.
2. DirectGPIOHX711Provider: Direct bit-banging of DOUT/SCK pins for Single Board Computers
   (Raspberry Pi, Jetson GPIO).
"""

from __future__ import annotations

import json
import logging
import os
import threading
import time
from typing import Any, Callable, Dict, Optional, Tuple

from services.weight_sensor_service import WeightSensorProvider

logger = logging.getLogger("HX711Hardware")
if not logger.handlers:
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter("[%(asctime)s] [%(levelname)s] [%(name)s] %(message)s"))
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)

CALIBRATION_FILE = os.path.join(os.path.dirname(os.path.dirname(__file__)), "sensor_calibration.json")


# ============================================================================
# Calibration Storage Helpers
# ============================================================================

def load_calibration_params(sensor_id: str) -> Dict[str, float]:
    """Load calibration offset and scale factor from disk."""
    if os.path.exists(CALIBRATION_FILE):
        try:
            with open(CALIBRATION_FILE, "r") as f:
                data = json.load(f)
                return data.get(sensor_id, {"zero_offset": 0.0, "scale_factor": 1.0})
        except Exception as e:
            logger.warning(f"[CALIB] Failed to read {CALIBRATION_FILE}: {e}")
    return {"zero_offset": 0.0, "scale_factor": 1.0}


def save_calibration_params(sensor_id: str, zero_offset: float, scale_factor: float) -> bool:
    """Persist calibration parameters to disk."""
    data = {}
    if os.path.exists(CALIBRATION_FILE):
        try:
            with open(CALIBRATION_FILE, "r") as f:
                data = json.load(f)
        except Exception:
            data = {}
    data[sensor_id] = {
        "zero_offset": round(zero_offset, 4),
        "scale_factor": round(scale_factor, 6),
        "updated_at": time.time(),
    }
    try:
        with open(CALIBRATION_FILE, "w") as f:
            json.dump(data, f, indent=2)
        logger.info(f"[CALIB] Saved calibration for {sensor_id}: offset={zero_offset}, scale={scale_factor}")
        return True
    except Exception as e:
        logger.error(f"[CALIB] Failed to write {CALIBRATION_FILE}: {e}")
        return False


# ============================================================================
# Topology 1: Serial / USB Microcontroller Bridge
# ============================================================================

class SerialHX711Provider(WeightSensorProvider):
    """High-performance non-blocking USB/Serial driver for HX711 microcontrollers."""

    def __init__(
        self,
        port: str = "AUTO",
        baudrate: int = 115200,
        default_sensor_id: str = "SHELF_01",
        timeout: float = 1.0,
    ):
        self.port = port
        self.baudrate = baudrate
        self.sensor_id = default_sensor_id
        self.timeout = timeout

        # Calibration parameters
        calib = load_calibration_params(self.sensor_id)
        self.zero_offset: float = calib.get("zero_offset", 0.0)
        self.scale_factor: float = calib.get("scale_factor", 1.0)

        # In-memory latest state
        self._current_weight_g: float = 0.0
        self._raw_adc: int = 0
        self._last_sample_time: float = 0.0
        self._is_connected: bool = False
        self._lock = threading.Lock()

        # Serial worker thread
        self._stop_event = threading.Event()
        self._serial_handle = None
        self._worker_thread: Optional[threading.Thread] = None

        self._start_worker()

    def _find_serial_port(self) -> Optional[str]:
        """Auto-detect connected USB serial devices (e.g. ESP32, CP2102, CH340)."""
        if self.port != "AUTO":
            return self.port
        try:
            import serial.tools.list_ports  # type: ignore

            ports = list(serial.tools.list_ports.comports())
            for p in ports:
                # Look for common USB-UART descriptors
                desc = (p.description or "").lower()
                hwid = (p.hwid or "").lower()
                if any(x in desc or x in hwid for x in ("ch340", "cp210", "ftdi", "usb serial", "esp32", "arduino")):
                    logger.info(f"[SERIAL] Auto-detected load cell microcontroller on {p.device} ({p.description})")
                    return p.device
            if ports:
                logger.info(f"[SERIAL] Using first available port: {ports[0].device}")
                return ports[0].device
        except Exception as e:
            logger.warning(f"[SERIAL] Port discovery error: {e}")
        return None

    def _start_worker(self) -> None:
        """Launch background reader thread."""
        self._stop_event.clear()
        self._worker_thread = threading.Thread(target=self._serial_reader_loop, daemon=True)
        self._worker_thread.start()

    def _serial_reader_loop(self) -> None:
        """Continuously read, parse, and filter weight packets from serial port."""
        while not self._stop_event.is_set():
            target_port = self._find_serial_port()
            if not target_port:
                time.sleep(1.0)
                continue

            try:
                import serial  # type: ignore

                logger.info(f"[SERIAL] Opening {target_port} at {self.baudrate} baud...")
                with serial.Serial(target_port, self.baudrate, timeout=self.timeout) as ser:
                    self._serial_handle = ser
                    self._is_connected = True
                    # Flush startup boot messages
                    ser.reset_input_buffer()

                    while not self._stop_event.is_set():
                        raw_line = ser.readline().decode("utf-8", errors="ignore").strip()
                        if not raw_line:
                            continue

                        # Parse incoming line (Format: JSON or CSV)
                        sample_weight_g = None
                        raw_val = 0

                        if raw_line.startswith("{") and raw_line.endswith("}"):
                            try:
                                packet = json.loads(raw_line)
                                raw_val = int(packet.get("raw", 0))
                                if "weight_g" in packet:
                                    sample_weight_g = float(packet["weight_g"])
                            except Exception:
                                pass
                        elif "," in raw_line or raw_line.lstrip("-").isdigit():
                            try:
                                parts = raw_line.split(",")
                                raw_val = int(parts[0].strip())
                                if len(parts) > 1:
                                    sample_weight_g = float(parts[1].strip())
                            except Exception:
                                pass

                        # Compute weight if not pre-computed by microcontroller firmware
                        if sample_weight_g is None and self.scale_factor != 0:
                            sample_weight_g = (raw_val - self.zero_offset) / self.scale_factor

                        if sample_weight_g is not None:
                            with self._lock:
                                self._raw_adc = raw_val
                                self._current_weight_g = sample_weight_g
                                self._last_sample_time = time.monotonic()

            except Exception as e:
                logger.warning(f"[SERIAL] Connection lost on {target_port}: {e}. Retrying in 2s...")
                with self._lock:
                    self._is_connected = False
                time.sleep(2.0)

    # --- WeightSensorProvider Interface ---

    def get_weight(self, sensor_id: str) -> Optional[float]:
        """Return the latest calibrated weight in grams."""
        with self._lock:
            # If no fresh sample received in last 2.0 seconds, report None
            if time.monotonic() - self._last_sample_time > 2.0:
                return None
            return self._current_weight_g

    def is_available(self, sensor_id: str) -> bool:
        """Return whether physical serial port is connected and streaming."""
        with self._lock:
            return self._is_connected and (time.monotonic() - self._last_sample_time <= 2.0)

    def tare(self, sensor_id: Optional[str] = None) -> bool:
        """Tare the load cell: set current raw reading as zero offset."""
        with self._lock:
            if not self._is_connected:
                logger.warning("[SERIAL TARE] Cannot tare: sensor not connected.")
                return False
            self.zero_offset = float(self._raw_adc)
            save_calibration_params(sensor_id or self.sensor_id, self.zero_offset, self.scale_factor)
            logger.info(f"[SERIAL TARE] Tared {sensor_id or self.sensor_id}: zero_offset={self.zero_offset}")
            return True

    def calibrate(self, sensor_id: str, known_weight_g: float) -> bool:
        """Two-point calibration using a known reference weight."""
        if known_weight_g <= 0:
            logger.error("[CALIB] Known reference weight must be greater than 0g.")
            return False

        with self._lock:
            if not self._is_connected:
                logger.error("[CALIB] Sensor not connected.")
                return False

            raw_diff = self._raw_adc - self.zero_offset
            if abs(raw_diff) < 50:
                logger.error(f"[CALIB] Raw delta too small ({raw_diff}). Ensure weight is placed on shelf.")
                return False

            self.scale_factor = raw_diff / known_weight_g
            save_calibration_params(sensor_id, self.zero_offset, self.scale_factor)
            logger.info(
                f"[CALIB] Calibrated {sensor_id}: scale_factor={self.scale_factor:.4f} "
                f"for known_weight={known_weight_g}g"
            )
            return True

    def close(self) -> None:
        """Stop worker thread and close serial handle."""
        self._stop_event.set()
        if self._worker_thread:
            self._worker_thread.join(timeout=1.0)
        logger.info("[SERIAL] Driver closed.")


# ============================================================================
# Topology 2: Direct SBC GPIO Bit-Banging Driver (Raspberry Pi / Jetson)
# ============================================================================

class DirectGPIOHX711Provider(WeightSensorProvider):
    """Direct GPIO bit-banging driver for Raspberry Pi / Jetson GPIO headers.

    Reads 24-bit two's complement data from HX711 DOUT pin clocked by PD_SCK.
    """

    def __init__(
        self,
        dout_pin: int = 5,
        sck_pin: int = 6,
        sensor_id: str = "SHELF_01",
        gain: int = 128,
    ):
        self.dout_pin = dout_pin
        self.sck_pin = sck_pin
        self.sensor_id = sensor_id
        self.gain = gain  # 128 (Channel A, 25 pulses), 64 (Channel A, 27 pulses), 32 (Channel B, 26 pulses)
        self.gain_pulses = 1 if gain == 128 else (2 if gain == 32 else 3)

        calib = load_calibration_params(self.sensor_id)
        self.zero_offset: float = calib.get("zero_offset", 0.0)
        self.scale_factor: float = calib.get("scale_factor", 1.0)

        self._gpio = None
        self._init_gpio()

    def _init_gpio(self) -> None:
        """Import and configure platform GPIO library."""
        try:
            import RPi.GPIO as GPIO  # type: ignore

            self._gpio = GPIO
            GPIO.setmode(GPIO.BCM)
            GPIO.setup(self.dout_pin, GPIO.IN)
            GPIO.setup(self.sck_pin, GPIO.OUT)
            GPIO.output(self.sck_pin, False)
            logger.info(f"[GPIO] Initialized HX711 on DOUT={self.dout_pin}, SCK={self.sck_pin}")
        except ImportError:
            logger.warning("[GPIO] RPi.GPIO not installed. DirectGPIOHX711Provider operating in mock mode.")

    def _read_raw(self) -> Optional[int]:
        """Execute 24-clock cycle read sequence according to HX711 datasheet."""
        if not self._gpio:
            return None

        GPIO = self._gpio
        # Wait until DOUT goes LOW (data ready)
        start_t = time.monotonic()
        while GPIO.input(self.dout_pin) == 1:
            if time.monotonic() - start_t > 0.15:  # 150ms timeout
                return None
            time.sleep(0.0001)

        raw_count = 0
        # Clock in 24 bits (MSB first)
        for _ in range(24):
            GPIO.output(self.sck_pin, True)
            raw_count = (raw_count << 1) | GPIO.input(self.dout_pin)
            GPIO.output(self.sck_pin, False)

        # Additional pulses to set gain for next reading
        for _ in range(self.gain_pulses):
            GPIO.output(self.sck_pin, True)
            GPIO.output(self.sck_pin, False)

        # Convert 24-bit 2's complement to signed 32-bit integer
        if raw_count & 0x800000:
            raw_count -= 0x1000000

        return raw_count

    def get_weight(self, sensor_id: str) -> Optional[float]:
        raw_val = self._read_raw()
        if raw_val is None:
            return None
        return (raw_val - self.zero_offset) / self.scale_factor

    def is_available(self, sensor_id: str) -> bool:
        return self._gpio is not None

    def tare(self, sensor_id: Optional[str] = None) -> bool:
        readings = [self._read_raw() for _ in range(10)]
        valid = [r for r in readings if r is not None]
        if not valid:
            return False
        self.zero_offset = sum(valid) / len(valid)
        save_calibration_params(sensor_id or self.sensor_id, self.zero_offset, self.scale_factor)
        return True

    def calibrate(self, sensor_id: str, known_weight_g: float) -> bool:
        readings = [self._read_raw() for _ in range(10)]
        valid = [r for r in readings if r is not None]
        if not valid:
            return False
        avg_raw = sum(valid) / len(valid)
        raw_diff = avg_raw - self.zero_offset
        self.scale_factor = raw_diff / known_weight_g
        save_calibration_params(sensor_id, self.zero_offset, self.scale_factor)
        return True
