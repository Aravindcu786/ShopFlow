"""Interactive CLI calibration utility for physical retail shelf load cells.

Performs a 2-point linear calibration (Zero Offset + Scale Factor) and persists
the parameters to sensor_calibration.json.
"""

import sys
import time
from services.hardware_hx711_service import (
    SerialHX711Provider,
    load_calibration_params,
    save_calibration_params,
)


def run_calibration(sensor_id: str = "SHELF_01", port: str = "AUTO"):
    print("=" * 65)
    print(f"   Smart Shelf Load Cell Calibration Utility: {sensor_id}")
    print("=" * 65)
    print(f"Connecting to hardware on port={port}...")

    driver = SerialHX711Provider(port=port, default_sensor_id=sensor_id)

    # Wait for initial connection
    time.sleep(2.0)
    if not driver.is_available(sensor_id):
        print(f"\n[!] Error: No streaming sensor found on port {port}.")
        print("    Ensure the ESP32 / Arduino is plugged in via USB and baudrate is 115200.")
        driver.close()
        sys.exit(1)

    print("\n[+] Sensor connected successfully!")

    # Step 1: Tare (Zero Offset)
    print("\n--- STEP 1: ZERO TARE ---")
    print("Please ENSURE THE SHELF IS COMPLETELY EMPTY (no items, no hands).")
    input("Press ENTER when shelf is empty to capture zero baseline...")

    print("Sampling zero tare (10 samples)...")
    samples = []
    for _ in range(15):
        samples.append(driver._raw_adc)
        time.sleep(0.1)

    zero_offset = sum(samples[-10:]) / 10.0
    print(f"-> Captured Zero Offset = {zero_offset:.1f} ADC counts")

    # Step 2: Reference Weight
    print("\n--- STEP 2: KNOWN WEIGHT CALIBRATION ---")
    while True:
        try:
            wt_input = input("Enter the exact weight of your calibration object in grams (e.g. 500): ").strip()
            known_weight_g = float(wt_input)
            if known_weight_g > 0:
                break
            print("Weight must be greater than 0.")
        except ValueError:
            print("Please enter a valid numeric value.")

    print(f"\nPlace the {known_weight_g}g reference weight in the center of the shelf.")
    input("Press ENTER once placed and vibrations have settled...")

    print("Sampling reference weight (15 samples)...")
    loaded_samples = []
    for _ in range(20):
        loaded_samples.append(driver._raw_adc)
        time.sleep(0.1)

    loaded_raw = sum(loaded_samples[-10:]) / 10.0
    raw_delta = loaded_raw - zero_offset

    if abs(raw_delta) < 100:
        print(f"\n[!] Warning: Raw delta is very small ({raw_delta:.1f}). Check wiring or load cell polarity.")
        driver.close()
        sys.exit(1)

    scale_factor = raw_delta / known_weight_g
    print(f"-> Loaded Raw Count: {loaded_raw:.1f}")
    print(f"-> Raw Delta:        {raw_delta:.1f}")
    print(f"-> Scale Factor:     {scale_factor:.4f} counts/gram")

    # Step 3: Verification
    print("\n--- STEP 3: LIVE VERIFICATION ---")
    driver.zero_offset = zero_offset
    driver.scale_factor = scale_factor

    print("Live readings for 5 seconds (Target: ~{:.1f}g):".format(known_weight_g))
    for _ in range(10):
        time.sleep(0.5)
        measured_g = driver.get_weight(sensor_id)
        if measured_g is not None:
            err = measured_g - known_weight_g
            print(f"   Measured: {measured_g:7.2f}g | Error: {err:+6.2f}g")

    # Step 4: Save
    save_calibration_params(sensor_id, zero_offset, scale_factor)
    print("\n[+] Calibration successfully saved to sensor_calibration.json!")
    driver.close()


if __name__ == "__main__":
    sensor_name = sys.argv[1] if len(sys.argv) > 1 else "SHELF_01"
    port_arg = sys.argv[2] if len(sys.argv) > 2 else "AUTO"
    run_calibration(sensor_name, port_arg)
