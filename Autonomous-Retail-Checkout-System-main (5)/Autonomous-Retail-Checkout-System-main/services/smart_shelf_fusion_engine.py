"""Production-grade Multi-Modal Sensor Fusion Engine for Autonomous Retail Checkout.

Fuses high-frequency load cell (HX711) weight measurements with spatial
YOLOv8/11-Pose keypoints to execute robust, low-latency virtual cart transactions
(PICK, PUT_BACK, BROWSE_DISCARD, ACCIDENTAL_IMPACT_DISCARD).
"""

from __future__ import annotations

import logging
import math
import time
from collections import deque
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger("SmartShelfFusionEngine")
if not logger.handlers:
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter(
        "[%(asctime)s] [%(levelname)s] [%(name)s] %(message)s"
    ))
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)


# ============================================================================
# Enums and Data Models
# ============================================================================

class FSMState(str, Enum):
    """Formal states for the shopper shelf interaction lifecycle."""
    IDLE = "IDLE"
    APPROACHING = "APPROACHING"
    INSIDE_ROI = "INSIDE_ROI"
    INTERACTION_DETECTED = "INTERACTION_DETECTED"
    RETRACTING = "RETRACTING"
    SETTLED_FUSION = "SETTLED_FUSION"
    TRANSACTION_COMMITTED = "TRANSACTION_COMMITTED"
    DISCARDED = "DISCARDED"


class TransactionType(str, Enum):
    """Classification of the fused retail transaction event."""
    PICK = "PICK"
    PUT_BACK = "PUT_BACK"
    BROWSE_DISCARD = "BROWSE_DISCARD"
    ACCIDENTAL_IMPACT_DISCARD = "ACCIDENTAL_IMPACT_DISCARD"
    HOVER_DISCARD = "HOVER_DISCARD"
    UNRESOLVED_DISCARD = "UNRESOLVED_DISCARD"


@dataclass(frozen=True)
class ShelfROI:
    """2D Bounding Region of Interest for the shelf plane in camera coordinates."""
    x_min: float
    y_min: float
    x_max: float
    y_max: float
    approach_margin_px: float = 60.0  # Buffer zone around ROI to capture approach velocity

    def contains_point(self, x: float, y: float) -> bool:
        """Check if point is strictly inside the physical shelf bounding box."""
        return self.x_min <= x <= self.x_max and self.y_min <= y <= self.y_max

    def is_in_proximity(self, x: float, y: float) -> bool:
        """Check if point is within the approach boundary margin."""
        return (
            (self.x_min - self.approach_margin_px) <= x <= (self.x_max + self.approach_margin_px)
            and (self.y_min - self.approach_margin_px) <= y <= (self.y_max + self.approach_margin_px)
        )


@dataclass(frozen=True)
class ProductConfig:
    """Metadata and physical weight specs for shelf inventory."""
    product_id: str
    name: str
    unit_weight_g: float
    weight_tolerance_g: float = 25.0
    price: float = 0.0


@dataclass(frozen=True)
class FusionConfig:
    """Engine configuration thresholds and operational windows."""
    # Temporal coincidence window between hand retraction and weight settling (ms -> seconds)
    coincidence_window_s: float = 0.70  # 600-800ms window
    # Settling detection parameters
    settling_window_size: int = 10      # Number of consecutive weight samples for variance check
    settling_variance_threshold: float = 2.5  # Max standard deviation (g) for steady-state
    settling_min_duration_s: float = 0.20     # Minimum time load cell must remain steady
    # Weight activation thresholds
    min_event_weight_g: float = 40.0   # Minimum abs(delta) to trigger a physical item event
    transient_noise_spike_g: float = 15.0  # Min deviation to trigger INTERACTION_DETECTED state
    # Vision thresholds
    min_keypoint_conf: float = 0.45    # Minimum pose keypoint confidence
    # Interaction timeouts
    max_dwell_timeout_s: float = 8.0   # Max duration an interaction can stay open before timeout
    retraction_timeout_s: float = 1.5  # Max duration waiting for weight settling after retraction
    # Baseline slow drift tracking
    baseline_ema_alpha: float = 0.02   # Exponential moving average factor for ambient drift tare


@dataclass
class WristKeypoint:
    """Normalized or pixel coordinates for a tracked wrist keypoint."""
    x: float
    y: float
    confidence: float
    timestamp: float


@dataclass
class WeightSample:
    """Single time-stamped reading from the load cell / HX711."""
    timestamp: float
    raw_weight_g: float


@dataclass
class ShopperPoseTrajectory:
    """Historical tracking of wrist trajectories for a specific shopper."""
    shopper_id: str
    wrist_history: deque[Tuple[float, float, float, str]] = field(
        default_factory=lambda: deque(maxlen=60)
    )  # (timestamp, x, y, wrist_side)
    last_seen_time: float = 0.0
    is_currently_in_roi: bool = False
    is_currently_in_proximity: bool = False
    entered_roi_time: Optional[float] = None
    retracted_from_roi_time: Optional[float] = None
    max_interaction_depth: float = 0.0


@dataclass
class TransactionEvent:
    """Structured transaction payload emitted upon fusion confirmation."""
    event_id: str
    transaction_type: TransactionType
    shopper_id: Optional[str]
    product_id: Optional[str]
    product_name: Optional[str]
    quantity: int
    weight_delta_g: float
    confidence: float
    timestamp: float
    details: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        """Convert transaction event to JSON-serializable dictionary."""
        return {
            "event_id": self.event_id,
            "transaction_type": self.transaction_type.value,
            "shopper_id": self.shopper_id,
            "product_id": self.product_id,
            "product_name": self.product_name,
            "quantity": self.quantity,
            "weight_delta_g": round(self.weight_delta_g, 2),
            "confidence": round(self.confidence, 4),
            "timestamp": round(self.timestamp, 4),
            "details": self.details,
        }


# ============================================================================
# Multi-Modal Sensor Fusion Engine
# ============================================================================

class SmartShelfFusionEngine:
    """Multi-Modal Sensor Fusion Engine for Smart Shelf Item Tracking.

    Fuses spatial YOLO-Pose wrist keypoints and load-cell weight time series
    to accurately detect item pickups, put-backs, and reject hover/leaning noise.
    """

    def __init__(
        self,
        shelf_id: str,
        shelf_roi: ShelfROI,
        product_config: ProductConfig,
        config: Optional[FusionConfig] = None,
        initial_baseline_weight_g: float = 0.0,
    ):
        self.shelf_id = shelf_id
        self.shelf_roi = shelf_roi
        self.product = product_config
        self.config = config or FusionConfig()

        # Weight time-series buffers
        self.weight_buffer: deque[WeightSample] = deque(maxlen=120)
        self.baseline_weight_g: float = initial_baseline_weight_g
        self.locked_pre_interaction_weight_g: Optional[float] = None
        self._last_settled_weight_g: Optional[float] = initial_baseline_weight_g
        self._last_settled_timestamp: float = 0.0

        # State Machine Tracking
        self.current_state: FSMState = FSMState.IDLE
        self.state_entry_time: float = time.monotonic()
        self.active_shopper_id: Optional[str] = None

        # Shopper pose tracking table: shopper_id -> ShopperPoseTrajectory
        self.shopper_trajectories: Dict[str, ShopperPoseTrajectory] = {}

        # Retraction and settling alignment markers
        self.last_retraction_timestamp: Optional[float] = None
        self.last_retraction_shopper_id: Optional[str] = None

        # Event ID counter
        self._event_counter: int = 0

    # ------------------------------------------------------------------------
    # Public Ingestion Interface
    # ------------------------------------------------------------------------

    def process_pose_keypoints(
        self,
        wrists_dict: Dict[str, Any],
        shopper_id: str,
        timestamp: Optional[float] = None,
    ) -> None:
        """Ingest YOLO-Pose wrist keypoints for an active shopper.

        Args:
            wrists_dict: Dictionary containing wrist coordinates and confidences.
                Supported formats:
                - {"left": (x, y, conf), "right": (x, y, conf)}
                - {"wrist_left": [x, y, conf], "wrist_right": [x, y, conf]}
                - Keypoint objects with .x, .y, .confidence attributes.
            shopper_id: Tracked identifier of the customer (ByteTrack / ReID ID).
            timestamp: Monotonic or epoch timestamp in seconds.
        """
        ts = timestamp if timestamp is not None else time.monotonic()

        # Parse keypoints
        parsed_wrists: List[Tuple[float, float, float, str]] = []
        for side, raw_val in wrists_dict.items():
            if raw_val is None:
                continue
            if isinstance(raw_val, (list, tuple)) and len(raw_val) >= 3:
                x, y, conf = float(raw_val[0]), float(raw_val[1]), float(raw_val[2])
            elif hasattr(raw_val, "x") and hasattr(raw_val, "y"):
                x = float(raw_val.x)
                y = float(raw_val.y)
                conf = float(getattr(raw_val, "confidence", 1.0))
            else:
                continue

            if conf >= self.config.min_keypoint_conf:
                side_str = "left" if "left" in side.lower() else "right"
                parsed_wrists.append((x, y, conf, side_str))

        # Retrieve or initialize trajectory record
        traj = self.shopper_trajectories.get(shopper_id)
        if traj is None:
            traj = ShopperPoseTrajectory(shopper_id=shopper_id)
            self.shopper_trajectories[shopper_id] = traj

        traj.last_seen_time = ts

        # Determine spatial status relative to Shelf ROI
        any_in_roi = False
        any_in_proximity = False

        for x, y, conf, side in parsed_wrists:
            traj.wrist_history.append((ts, x, y, side))
            if self.shelf_roi.contains_point(x, y):
                any_in_roi = True
                any_in_proximity = True
            elif self.shelf_roi.is_in_proximity(x, y):
                any_in_proximity = True

        was_in_roi = traj.is_currently_in_roi
        traj.is_currently_in_roi = any_in_roi
        traj.is_currently_in_proximity = any_in_proximity

        # Hand entered ROI transition
        if any_in_roi and not was_in_roi:
            traj.entered_roi_time = ts
            traj.retracted_from_roi_time = None
            logger.debug(f"[POSE] Shopper '{shopper_id}' entered shelf ROI at t={ts:.3f}")

        # Hand retracted from ROI transition (Outbound exit)
        if was_in_roi and not any_in_roi:
            traj.retracted_from_roi_time = ts
            self.last_retraction_timestamp = ts
            self.last_retraction_shopper_id = shopper_id
            logger.info(
                f"[POSE] Hand RETRACTED: Shopper '{shopper_id}' exited shelf ROI at t={ts:.3f}"
            )

        # Update FSM based on visual cues
        self._update_fsm_with_pose(shopper_id, traj, ts)

    def process_weight_sample(
        self,
        raw_weight: float,
        timestamp: Optional[float] = None,
    ) -> None:
        """Ingest continuous load-cell weight reading (grams) from HX711.

        Args:
            raw_weight: Current physical weight in grams.
            timestamp: Monotonic or epoch timestamp in seconds.
        """
        ts = timestamp if timestamp is not None else time.monotonic()
        sample = WeightSample(timestamp=ts, raw_weight_g=raw_weight)
        self.weight_buffer.append(sample)

        # Ambient baseline slow-drift tracking when system is strictly IDLE
        if self.current_state == FSMState.IDLE:
            std_dev, mean_val = self._calculate_rolling_stats()
            if std_dev is not None and std_dev < self.config.settling_variance_threshold:
                # Slowly adapt to temperature or moisture drift
                alpha = self.config.baseline_ema_alpha
                self.baseline_weight_g = (1.0 - alpha) * self.baseline_weight_g + alpha * mean_val

        # Update FSM based on weight changes
        self._update_fsm_with_weight(raw_weight, ts)

    def evaluate_fusion(self) -> Optional[Dict[str, Any]]:
        """Evaluate multi-modal sensor state and execute fusion logic.

        Must be invoked at high frequency (e.g., 20-50 Hz) in the main pipeline loop.
        Returns a structured dictionary representing the transaction event, or None
        if no transaction is ready to be committed.
        """
        now = time.monotonic()

        # Step 1: Check for interaction timeouts
        if self.current_state in (
            FSMState.APPROACHING,
            FSMState.INSIDE_ROI,
            FSMState.INTERACTION_DETECTED,
        ):
            if (now - self.state_entry_time) > self.config.max_dwell_timeout_s:
                logger.warning(
                    f"[FUSION] Interaction dwell timeout ({self.config.max_dwell_timeout_s}s) "
                    f"exceeded in state {self.current_state}. Resetting to IDLE."
                )
                self._transition_to(FSMState.IDLE, now)
                return None

        # Step 2: Handle RETRACTING waiting for weight settling
        if self.current_state == FSMState.RETRACTING:
            retract_time = self.last_retraction_timestamp or self.state_entry_time
            if (now - retract_time) > self.config.retraction_timeout_s:
                logger.info(
                    f"[FUSION] Settling window timed out after retraction. "
                    f"Evaluating best available steady-state."
                )
                self._transition_to(FSMState.SETTLED_FUSION, now)

        # Step 3: Check for Accidental Impact / Leaning when IDLE
        impact_event = self._check_accidental_impact(now)
        if impact_event is not None:
            return impact_event.to_dict()

        # Step 4: Resolve SETTLED_FUSION state
        if self.current_state == FSMState.SETTLED_FUSION:
            event = self._resolve_transaction(now)
            if event is not None:
                self._transition_to(FSMState.TRANSACTION_COMMITTED, now)
                # Reset state machine back to IDLE after commit
                self._transition_to(FSMState.IDLE, now)
                return event.to_dict()

        return None

    # ------------------------------------------------------------------------
    # State Machine Logic
    # ------------------------------------------------------------------------

    def _update_fsm_with_pose(
        self,
        shopper_id: str,
        traj: ShopperPoseTrajectory,
        ts: float,
    ) -> None:
        """Advance or transition FSM according to vision events."""
        if self.current_state == FSMState.IDLE:
            if traj.is_currently_in_roi:
                self.active_shopper_id = shopper_id
                self._lock_baseline_weight()
                self._transition_to(FSMState.INSIDE_ROI, ts)
            elif traj.is_currently_in_proximity:
                self.active_shopper_id = shopper_id
                self._lock_baseline_weight()
                self._transition_to(FSMState.APPROACHING, ts)

        elif self.current_state == FSMState.APPROACHING:
            if traj.is_currently_in_roi:
                self._transition_to(FSMState.INSIDE_ROI, ts)
            elif not traj.is_currently_in_proximity:
                # Shopper walked past without reaching into shelf
                self._transition_to(FSMState.IDLE, ts)

        elif self.current_state in (FSMState.INSIDE_ROI, FSMState.INTERACTION_DETECTED):
            # Check if active shopper has retracted from ROI
            if not traj.is_currently_in_roi and traj.retracted_from_roi_time is not None:
                self._transition_to(FSMState.RETRACTING, ts)

    def _update_fsm_with_weight(self, raw_weight: float, ts: float) -> None:
        """Advance FSM when weight perturbations or steady-state settling occur."""
        locked_base = (
            self.locked_pre_interaction_weight_g
            if self.locked_pre_interaction_weight_g is not None
            else self.baseline_weight_g
        )
        instant_delta = raw_weight - locked_base

        # Detect physical interaction while hand is inside ROI
        if self.current_state == FSMState.INSIDE_ROI:
            if abs(instant_delta) >= self.config.transient_noise_spike_g:
                self._transition_to(FSMState.INTERACTION_DETECTED, ts)

        # Check for weight stabilization while in RETRACTING or INTERACTION_DETECTED
        if self.current_state in (FSMState.RETRACTING, FSMState.INTERACTION_DETECTED):
            settled_delta = self._detect_weight_settling(ts)
            if settled_delta is not None:
                # Load cell reached a steady state
                if self.current_state == FSMState.RETRACTING:
                    self._transition_to(FSMState.SETTLED_FUSION, ts)
                elif self.current_state == FSMState.INTERACTION_DETECTED:
                    # Shopper hand may have left ROI slightly before or after settling
                    if self.last_retraction_timestamp is not None:
                        dt = abs(ts - self.last_retraction_timestamp)
                        if dt <= self.config.coincidence_window_s:
                            self._transition_to(FSMState.SETTLED_FUSION, ts)

    def _transition_to(self, new_state: FSMState, ts: float) -> None:
        """Perform formal state transition with diagnostics logging."""
        if self.current_state != new_state:
            logger.info(
                f"[FSM] Transition: {self.current_state.value} -> {new_state.value} "
                f"(shopper={self.active_shopper_id}, t={ts:.3f})"
            )
            self.current_state = new_state
            self.state_entry_time = ts

            if new_state == FSMState.IDLE:
                self.active_shopper_id = None
                self.locked_pre_interaction_weight_g = None
                self.last_retraction_timestamp = None
                self.last_retraction_shopper_id = None

    # ------------------------------------------------------------------------
    # Variance-Based Settling & Debounce Filter
    # ------------------------------------------------------------------------

    def _calculate_rolling_stats(self) -> Tuple[Optional[float], Optional[float]]:
        """Compute rolling mean and standard deviation over the settling window."""
        window_size = self.config.settling_window_size
        if len(self.weight_buffer) < window_size:
            return None, None

        recent_samples = [
            self.weight_buffer[-i].raw_weight_g
            for i in range(1, window_size + 1)
        ]
        mean_val = sum(recent_samples) / window_size
        variance = sum((x - mean_val) ** 2 for x in recent_samples) / (window_size - 1)
        std_dev = math.sqrt(max(0.0, variance))
        return std_dev, mean_val

    def _detect_weight_settling(self, ts: float) -> Optional[float]:
        """Verify that the load cell has damped out and achieved steady state.

        Returns:
            The settled delta in grams relative to the pre-interaction baseline,
            or None if the signal is still vibrating/noisy.
        """
        std_dev, mean_val = self._calculate_rolling_stats()
        if std_dev is None or mean_val is None:
            return None

        # Check if variance is beneath the mechanical noise threshold
        if std_dev <= self.config.settling_variance_threshold:
            # Check duration of this steady state
            window_duration = ts - self.weight_buffer[-self.config.settling_window_size].timestamp
            if window_duration >= self.config.settling_min_duration_s:
                self._last_settled_weight_g = mean_val
                self._last_settled_timestamp = ts

                locked_base = (
                    self.locked_pre_interaction_weight_g
                    if self.locked_pre_interaction_weight_g is not None
                    else self.baseline_weight_g
                )
                delta_g = mean_val - locked_base
                return delta_g

        return None

    def _lock_baseline_weight(self) -> None:
        """Lock in steady pre-interaction tare weight as shopper enters."""
        if self.locked_pre_interaction_weight_g is None:
            _, mean_val = self._calculate_rolling_stats()
            if mean_val is not None:
                self.locked_pre_interaction_weight_g = mean_val
            else:
                self.locked_pre_interaction_weight_g = (
                    self.weight_buffer[-1].raw_weight_g
                    if self.weight_buffer
                    else self.baseline_weight_g
                )
            logger.debug(
                f"[FUSION] Locked pre-interaction baseline weight = "
                f"{self.locked_pre_interaction_weight_g:.1f}g"
            )

    # ------------------------------------------------------------------------
    # Spatial & Temporal Alignment & Transaction Resolution
    # ------------------------------------------------------------------------

    def _resolve_transaction(self, now: float) -> Optional[TransactionEvent]:
        """Fuse spatial pose retraction with steady-state weight delta."""
        self._event_counter += 1
        event_id = f"TXN-{self.shelf_id}-{int(now * 1000)}-{self._event_counter:04d}"

        # 1. Obtain settled weight delta
        locked_base = (
            self.locked_pre_interaction_weight_g
            if self.locked_pre_interaction_weight_g is not None
            else self.baseline_weight_g
        )
        current_settled_g = (
            self._last_settled_weight_g
            if self._last_settled_weight_g is not None
            else (self.weight_buffer[-1].raw_weight_g if self.weight_buffer else locked_base)
        )
        weight_delta_g = current_settled_g - locked_base

        # 2. Check temporal coincidence with hand retraction
        shopper_id = self.active_shopper_id or self.last_retraction_shopper_id
        retract_ts = self.last_retraction_timestamp

        temporal_coincidence_ok = False
        time_diff_ms = -1.0
        if retract_ts is not None:
            time_diff_ms = abs(self._last_settled_timestamp - retract_ts) * 1000.0
            if time_diff_ms <= (self.config.coincidence_window_s * 1000.0):
                temporal_coincidence_ok = True

        logger.info(
            f"[FUSION EVAL] delta={weight_delta_g:+.1f}g, shopper={shopper_id}, "
            f"retract_dt={time_diff_ms:.1f}ms (window={self.config.coincidence_window_s*1000:.0f}ms, "
            f"coincidence={temporal_coincidence_ok})"
        )

        # 3. Categorize Outcome

        # Case A: Browse / Touch & Leave (Net weight returns to zero)
        if abs(weight_delta_g) < self.config.min_event_weight_g:
            logger.info(
                f"[FUSION] BROWSE_DISCARD: Hand entered & left, net weight returned to baseline "
                f"({weight_delta_g:+.1f}g). No cart modification."
            )
            return TransactionEvent(
                event_id=event_id,
                transaction_type=TransactionType.BROWSE_DISCARD,
                shopper_id=shopper_id,
                product_id=None,
                product_name=None,
                quantity=0,
                weight_delta_g=weight_delta_g,
                confidence=0.98,
                timestamp=now,
                details={
                    "reason": "Weight returned to baseline after touch/browse",
                    "delta_g": weight_delta_g,
                    "retraction_dt_ms": time_diff_ms,
                },
            )

        # Case B: Hand Retraction + Temporal Alignment Verified
        if temporal_coincidence_ok and shopper_id:
            # Resolve product quantity
            unit_wt = self.product.unit_weight_g
            tolerance = self.product.weight_tolerance_g
            abs_delta = abs(weight_delta_g)

            raw_qty = abs_delta / unit_wt if unit_wt > 0 else 1.0
            quantity = max(1, round(raw_qty))

            # Quantization residual check
            expected_total_wt = quantity * unit_wt
            residual_g = abs(abs_delta - expected_total_wt)
            max_allowed_residual = quantity * tolerance

            # Confidence is high if residual error is low
            confidence = max(0.1, 1.0 - (residual_g / max(1.0, expected_total_wt)))

            if residual_g > max_allowed_residual:
                logger.warning(
                    f"[FUSION] Weight delta {abs_delta:.1f}g does not match unit weight "
                    f"{unit_wt}g (qty={quantity}, residual={residual_g:.1f}g > {max_allowed_residual:.1f}g)"
                )
                confidence *= 0.5

            if weight_delta_g < -self.config.min_event_weight_g:
                # Negative delta = item removed from shelf = PICK
                logger.info(
                    f"[FUSION COMMIT] VALID PICK: Shopper '{shopper_id}' picked {quantity}x "
                    f"'{self.product.name}' (delta={weight_delta_g:.1f}g, conf={confidence:.2f})"
                )
                # Update shelf baseline to new stable weight
                self.baseline_weight_g = current_settled_g
                return TransactionEvent(
                    event_id=event_id,
                    transaction_type=TransactionType.PICK,
                    shopper_id=shopper_id,
                    product_id=self.product.product_id,
                    product_name=self.product.name,
                    quantity=quantity,
                    weight_delta_g=weight_delta_g,
                    confidence=confidence,
                    timestamp=now,
                    details={
                        "unit_weight_g": unit_wt,
                        "residual_error_g": round(residual_g, 2),
                        "retraction_dt_ms": time_diff_ms,
                        "price": self.product.price,
                    },
                )

            elif weight_delta_g > self.config.min_event_weight_g:
                # Positive delta = item returned to shelf = PUT_BACK
                logger.info(
                    f"[FUSION COMMIT] VALID PUT_BACK: Shopper '{shopper_id}' returned {quantity}x "
                    f"'{self.product.name}' (delta={weight_delta_g:.1f}g, conf={confidence:.2f})"
                )
                self.baseline_weight_g = current_settled_g
                return TransactionEvent(
                    event_id=event_id,
                    transaction_type=TransactionType.PUT_BACK,
                    shopper_id=shopper_id,
                    product_id=self.product.product_id,
                    product_name=self.product.name,
                    quantity=quantity,
                    weight_delta_g=weight_delta_g,
                    confidence=confidence,
                    timestamp=now,
                    details={
                        "unit_weight_g": unit_wt,
                        "residual_error_g": round(residual_g, 2),
                        "retraction_dt_ms": time_diff_ms,
                        "price": self.product.price,
                    },
                )

        # Case C: Temporal mismatch or missing shopper identity
        logger.warning(
            f"[FUSION] Unresolved fusion event: delta={weight_delta_g:.1f}g, "
            f"coincidence={temporal_coincidence_ok}, shopper={shopper_id}"
        )
        return TransactionEvent(
            event_id=event_id,
            transaction_type=TransactionType.UNRESOLVED_DISCARD,
            shopper_id=shopper_id,
            product_id=self.product.product_id,
            product_name=self.product.name,
            quantity=0,
            weight_delta_g=weight_delta_g,
            confidence=0.2,
            timestamp=now,
            details={
                "reason": "Temporal coincidence window expired or shopper unlinked",
                "delta_g": weight_delta_g,
                "retraction_dt_ms": time_diff_ms,
            },
        )

    def _check_accidental_impact(self, now: float) -> Optional[TransactionEvent]:
        """Detect and discard accidental leaning, bumps, or vibration without wrist presence."""
        if self.current_state != FSMState.IDLE:
            return None

        # Check if load cell measured a settled or significant delta while IDLE
        std_dev, mean_val = self._calculate_rolling_stats()
        if std_dev is None or mean_val is None:
            return None

        delta_g = mean_val - self.baseline_weight_g
        if abs(delta_g) >= self.config.min_event_weight_g and std_dev <= self.config.settling_variance_threshold:
            # Check if any shopper had wrists inside the shelf ROI recently
            recent_wrist_in_roi = False
            for traj in self.shopper_trajectories.values():
                if traj.is_currently_in_roi:
                    recent_wrist_in_roi = True
                    break
                if (
                    traj.retracted_from_roi_time is not None
                    and (now - traj.retracted_from_roi_time) <= self.config.coincidence_window_s
                ):
                    recent_wrist_in_roi = True
                    break

            if not recent_wrist_in_roi:
                self._event_counter += 1
                event_id = f"IMPACT-{self.shelf_id}-{int(now * 1000)}-{self._event_counter:04d}"
                logger.warning(
                    f"[FUSION ALERT] ACCIDENTAL LEAN / IMPACT DETECTED! Delta={delta_g:+.1f}g, "
                    f"std_dev={std_dev:.2f}g, but NO wrist in Shelf ROI. Triggering discard & alert."
                )
                # Recalibrate baseline to settle after bump
                self.baseline_weight_g = mean_val
                return TransactionEvent(
                    event_id=event_id,
                    transaction_type=TransactionType.ACCIDENTAL_IMPACT_DISCARD,
                    shopper_id=None,
                    product_id=None,
                    product_name=None,
                    quantity=0,
                    weight_delta_g=delta_g,
                    confidence=0.99,
                    timestamp=now,
                    details={
                        "alert": "MECHANICAL_NOISE_OR_CUSTOMER_LEAN",
                        "weight_delta_g": delta_g,
                        "std_dev_g": std_dev,
                        "action": "Virtual cart unmodified; baseline weight recalibrated.",
                    },
                )

        return None

    # ------------------------------------------------------------------------
    # Maintenance and Inspection Utilities
    # ------------------------------------------------------------------------

    def tare(self, current_weight_g: Optional[float] = None) -> None:
        """Manually tare/zero the shelf load cell reference."""
        if current_weight_g is not None:
            self.baseline_weight_g = current_weight_g
        elif self.weight_buffer:
            self.baseline_weight_g = self.weight_buffer[-1].raw_weight_g
        self.locked_pre_interaction_weight_g = None
        self.current_state = FSMState.IDLE
        logger.info(f"[FUSION] Shelf '{self.shelf_id}' tared to {self.baseline_weight_g:.1f}g")

    def get_diagnostics(self) -> Dict[str, Any]:
        """Return engine internal diagnostics snapshot for telemetry / dashboard."""
        std_dev, mean_val = self._calculate_rolling_stats()
        return {
            "shelf_id": self.shelf_id,
            "fsm_state": self.current_state.value,
            "active_shopper_id": self.active_shopper_id,
            "baseline_weight_g": round(self.baseline_weight_g, 2),
            "locked_baseline_g": (
                round(self.locked_pre_interaction_weight_g, 2)
                if self.locked_pre_interaction_weight_g is not None
                else None
            ),
            "current_weight_mean_g": round(mean_val, 2) if mean_val is not None else None,
            "current_weight_std_dev_g": round(std_dev, 2) if std_dev is not None else None,
            "buffer_depth": len(self.weight_buffer),
            "tracked_shoppers": len(self.shopper_trajectories),
        }
