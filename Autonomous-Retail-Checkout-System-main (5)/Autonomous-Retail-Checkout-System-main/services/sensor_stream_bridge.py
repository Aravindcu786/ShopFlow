"""Continuous hardware weight sensor polling and ingestion daemon.

Bridges raw physical readings from WeightSensorProvider (SerialHX711 or DirectGPIO)
into the SmartShelfFusionEngine at a regulated sampling rate (20-80 Hz).
"""

from __future__ import annotations

import logging
import threading
import time
from typing import Optional

from services.smart_shelf_fusion_engine import SmartShelfFusionEngine
from services.weight_sensor_service import WeightSensorProvider, get_sensor_provider

logger = logging.getLogger("SensorStreamBridge")


class SensorStreamBridge:
    """Polls physical weight hardware and streams time-series samples into Fusion Engine."""

    def __init__(
        self,
        engine: SmartShelfFusionEngine,
        provider: Optional[WeightSensorProvider] = None,
        sensor_id: str = "SHELF_01",
        poll_rate_hz: float = 40.0,
    ):
        self.engine = engine
        self.provider = provider or get_sensor_provider()
        self.sensor_id = sensor_id
        self.poll_interval_s: float = 1.0 / max(1.0, poll_rate_hz)

        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._sample_count: int = 0
        self._last_log_time: float = time.monotonic()

    def start(self) -> None:
        """Start background polling thread."""
        if self._thread and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run_loop, name=f"SensorBridge-{self.sensor_id}", daemon=True)
        self._thread.start()
        logger.info(f"[BRIDGE] Started sensor stream bridge for {self.sensor_id} at {1.0/self.poll_interval_s:.0f} Hz")

    def _run_loop(self) -> None:
        """Continuous polling loop."""
        while not self._stop_event.is_set():
            loop_start = time.monotonic()

            try:
                weight_g = self.provider.get_weight(self.sensor_id)
                if weight_g is not None:
                    now_ts = time.monotonic()
                    # Feed sample directly into the multi-modal fusion engine
                    self.engine.process_weight_sample(weight_g, timestamp=now_ts)
                    self._sample_count += 1

                # Periodically log throughput stats every 10 seconds
                if (loop_start - self._last_log_time) >= 10.0:
                    rate = self._sample_count / (loop_start - self._last_log_time)
                    logger.debug(f"[BRIDGE] Ingestion rate: {rate:.1f} samples/sec (latest={weight_g}g)")
                    self._sample_count = 0
                    self._last_log_time = loop_start

            except Exception as e:
                logger.error(f"[BRIDGE] Polling error on {self.sensor_id}: {e}")

            # Sleep remaining interval to maintain steady sample frequency
            elapsed = time.monotonic() - loop_start
            sleep_time = self.poll_interval_s - elapsed
            if sleep_time > 0:
                time.sleep(sleep_time)

    def stop(self) -> None:
        """Stop polling thread gracefully."""
        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=1.0)
        logger.info(f"[BRIDGE] Stopped sensor stream bridge for {self.sensor_id}")
