"""YOLO11n-Pose based shelf interaction detection.

Pose inference is triggered when:
1. Camera 2 has a valid person track
2. Person's bbox overlaps or is near the shelf interaction ROI
3. Pose inference interval allows it

The pose stage detects:
1. Arm reaching into shelf ROI (approaching, reaching)
2. Hand dwelling near/on products (grabbing, touching)
3. Hand retracting with object (object taken)
4. Associates the taken object with customer & shopping session
"""

import logging
import time
import threading
from typing import Optional, Dict, Any, Tuple, List

import numpy as np

import config

logger = logging.getLogger(__name__)

# YOLO-Pose keypoint indices (COCO format)
KP_LEFT_SHOULDER = 5
KP_RIGHT_SHOULDER = 6
KP_LEFT_ELBOW = 7
KP_RIGHT_ELBOW = 8
KP_LEFT_WRIST = 9
KP_RIGHT_WRIST = 10

# Interaction statuses
STATUS_NOT_IN_SHELF = "NOT_IN_SHELF"
STATUS_APPROACHING = "APPROACHING"
STATUS_REACHING = "REACHING"
STATUS_GRABBING = "GRABBING"
STATUS_INTERACTING = "INTERACTING"
STATUS_TAKEN = "TAKEN"
STATUS_LEAVING = "LEAVING"

# Default retail store shelf products mapped to zones and COCO classes (Single Shelf Matrix Zone)
DEFAULT_SHELF_PRODUCTS = [
    {
        "zone_idx": 0,
        "product_id": "PRODUCT_001",
        "name": "Apple (Fuji)",
        "price": "2.50",
        "coco": ["apple", "banana", "bottle", "cup", "sandwich", "bread"],
    },
]


class PoseInteractionState:
    """Per-track pose interaction state."""

    def __init__(self, cam2_track_id: int):
        self.cam2_track_id = cam2_track_id
        self.customer_id: Optional[str] = None
        self.session_id: Optional[str] = None
        self.inside_shelf_roi: bool = False
        self.last_pose_timestamp: float = 0.0
        self.pose_confidence: float = 0.0
        self.keypoints: Optional[np.ndarray] = None
        self.left_wrist: Optional[Tuple[float, float]] = None
        self.right_wrist: Optional[Tuple[float, float]] = None
        self.left_elbow: Optional[Tuple[float, float]] = None
        self.right_elbow: Optional[Tuple[float, float]] = None
        self.left_shoulder: Optional[Tuple[float, float]] = None
        self.right_shoulder: Optional[Tuple[float, float]] = None
        self.interaction_score: float = 0.0
        self.last_interaction_time: float = 0.0
        self.interaction_status: str = STATUS_NOT_IN_SHELF
        self._prev_wrist_positions: list = []
        self._roi_enter_time: float = 0.0
        self._roi_dwell_time: float = 0.0

        # Detailed hand interaction tracking
        self.wrist_in_shelf: bool = False
        self.active_wrist: Optional[Tuple[float, float]] = None
        self.active_wrist_side: str = ""
        self.active_zone_idx: Optional[int] = None
        self.active_product: Optional[Dict[str, Any]] = None
        self.grabbed_product: Optional[Dict[str, Any]] = None
        self.wrist_enter_roi_time: float = 0.0
        self.wrist_dwell_time: float = 0.0
        self.last_taken_product: Optional[Dict[str, Any]] = None
        self.last_taken_time: float = 0.0
        self.cooldown_until: float = 0.0
        self.phase: str = "IDLE"
        self.wrist_history: list = []  # List of (timestamp, wx, wy)

    def to_dict(self) -> Dict[str, Any]:
        """Serialize to dict for state manager storage."""
        return {
            "cam2_track_id": self.cam2_track_id,
            "customer_id": self.customer_id,
            "session_id": self.session_id,
            "inside_shelf_roi": self.inside_shelf_roi,
            "wrist_in_shelf": self.wrist_in_shelf,
            "interaction_status": self.interaction_status,
            "interaction_score": self.interaction_score,
            "last_interaction_time": self.last_interaction_time,
            "pose_confidence": self.pose_confidence,
            "last_pose_timestamp": self.last_pose_timestamp,
            "phase": self.phase,
            "active_product": self.active_product,
            "last_taken_product": self.last_taken_product,
            "last_taken_time": self.last_taken_time,
        }


class PoseInteractionManager:
    """Manages YOLO11n-Pose model, per-track interaction states, and item-taking detection."""

    def __init__(self):
        self._model = None
        self._model_lock = threading.Lock()
        self._states: Dict[int, PoseInteractionState] = {}
        self._states_lock = threading.Lock()

    def _get_model(self):
        """Lazy-load YOLO11n-Pose model."""
        if self._model is None:
            with self._model_lock:
                if self._model is None:
                    try:
                        from ultralytics import YOLO
                        model_path = config.POSE_MODEL_WEIGHTS
                        logger.info(f"[POSE] Loading pose model: {model_path}")
                        self._model = YOLO(model_path, task="pose")
                        logger.info("[POSE] Pose model loaded successfully")
                    except Exception as e:
                        logger.error(f"[POSE] Failed to load pose model: {e}")
                        return None
        return self._model

    def should_run_pose(self, cam2_track_id: int, near_or_in_roi: bool = False,
                        inside_roi: Optional[bool] = None) -> bool:
        """Determine if pose inference should run for this track right now."""
        if inside_roi is not None:
            near_or_in_roi = inside_roi
        if not near_or_in_roi:
            return False

        with self._states_lock:
            state = self._states.get(cam2_track_id)
            if state is None:
                return True
            elapsed = time.monotonic() - state.last_pose_timestamp
            return elapsed >= config.POSE_INFERENCE_INTERVAL

    def get_shelf_product_for_position(self, wx: float, wy: float,
                                         detected_objects: Optional[List[Dict[str, Any]]] = None) -> Dict[str, Any]:
        """Resolve product from detected object near wrist OR single shelf zone matrix."""
        # 1. Match against any detected object within 120px of wrist
        if detected_objects:
            for obj in detected_objects:
                bx1, by1, bx2, by2 = obj.get("bbox", (0, 0, 0, 0))
                ocx, ocy = (bx1 + bx2) / 2.0, (by1 + by2) / 2.0
                dist = ((wx - ocx) ** 2 + (wy - ocy) ** 2) ** 0.5
                if dist < 120.0:
                    label = str(obj.get("label", "")).lower()
                    for prod in DEFAULT_SHELF_PRODUCTS:
                        if any(c in label for c in prod["coco"]):
                            return dict(prod)

        # 2. Single shelf ROI matrix zone
        return dict(DEFAULT_SHELF_PRODUCTS[0])

    def run_pose_inference(self, frame: np.ndarray, bbox: Tuple[int, int, int, int],
                           cam2_track_id: int, customer_id: Optional[str] = None,
                           session_id: Optional[str] = None,
                           detected_objects: Optional[List[Dict[str, Any]]] = None) -> Tuple[Optional[PoseInteractionState], Optional[Dict[str, Any]]]:
        """Run YOLO11n-Pose on crop, analyze wrist trajectory, detect item taken."""
        model = self._get_model()
        if model is None:
            return None, None

        x1, y1, x2, y2 = bbox
        h, w = frame.shape[:2]

        pad_x = int((x2 - x1) * 0.15)
        pad_y = int((y2 - y1) * 0.10)
        cx1 = max(0, x1 - pad_x)
        cy1 = max(0, y1 - pad_y)
        cx2 = min(w, x2 + pad_x)
        cy2 = min(h, y2 + pad_y)
        crop = frame[cy1:cy2, cx1:cx2]

        if crop.size == 0 or crop.shape[0] < 50 or crop.shape[1] < 30:
            return None, None

        try:
            results = model(
                crop,
                conf=config.POSE_CONF,
                imgsz=config.POSE_IMGSZ,
                verbose=False,
            )
        except Exception as e:
            logger.error(f"[POSE] Inference error for track {cam2_track_id}: {e}")
            return None, None

        if not results or results[0].keypoints is None or results[0].keypoints.data.shape[0] == 0:
            return None, None

        result = results[0]
        kps_data = result.keypoints.data
        if kps_data.shape[0] > 1 and result.boxes is not None:
            areas = []
            for box in result.boxes:
                bx = box.xyxy[0].tolist()
                areas.append((bx[2] - bx[0]) * (bx[3] - bx[1]))
            best_idx = max(range(len(areas)), key=lambda i: areas[i])
            keypoints = kps_data[best_idx].cpu().numpy()
        else:
            keypoints = kps_data[0].cpu().numpy()

        offset_x, offset_y = cx1, cy1
        now = time.monotonic()
        item_taken_event = None

        with self._states_lock:
            state = self._states.get(cam2_track_id)
            if state is None:
                state = PoseInteractionState(cam2_track_id)
                self._states[cam2_track_id] = state

            if customer_id:
                state.customer_id = customer_id
            if session_id:
                state.session_id = session_id

            state.last_pose_timestamp = now
            state.keypoints = keypoints

            def _get_kp(idx):
                if idx < keypoints.shape[0]:
                    kp = keypoints[idx]
                    if len(kp) >= 3 and kp[2] > 0.3:
                        return (float(kp[0] + offset_x), float(kp[1] + offset_y))
                return None

            state.left_shoulder = _get_kp(KP_LEFT_SHOULDER)
            state.right_shoulder = _get_kp(KP_RIGHT_SHOULDER)
            state.left_elbow = _get_kp(KP_LEFT_ELBOW)
            state.right_elbow = _get_kp(KP_RIGHT_ELBOW)
            state.left_wrist = _get_kp(KP_LEFT_WRIST)
            state.right_wrist = _get_kp(KP_RIGHT_WRIST)

            # Compute interaction score
            score = self._compute_interaction_score(state, bbox)
            state.interaction_score = score
            state.pose_confidence = float(np.mean(keypoints[:, 2])) if keypoints.shape[1] >= 3 else 0.0

            # Shelf interaction & wrist-in-shelf analysis
            rx1, ry1, rx2, ry2 = config.SHELF_ROI
            wrists_in_roi = []
            for side, wrist in [("right", state.right_wrist), ("left", state.left_wrist)]:
                if wrist:
                    wx, wy = wrist
                    if (rx1 <= wx <= rx2) and (ry1 <= wy <= ry2):
                        wrists_in_roi.append((side, wrist))

            prev_wrist_in_shelf = state.wrist_in_shelf
            prev_phase = state.phase

            if wrists_in_roi:
                side, active_w = wrists_in_roi[0]
                state.wrist_in_shelf = True
                state.active_wrist = active_w
                state.active_wrist_side = side

                # Product resolution
                prod = self.get_shelf_product_for_position(active_w[0], active_w[1], detected_objects)
                state.active_product = prod
                state.active_zone_idx = prod.get("zone_idx")

                # Trajectory update
                state.wrist_history.append((now, active_w[0], active_w[1]))
                if len(state.wrist_history) > 20:
                    state.wrist_history.pop(0)

                if state.wrist_enter_roi_time == 0:
                    state.wrist_enter_roi_time = now
                    state.phase = STATUS_REACHING
                    state.interaction_status = f"REACHING ({prod['name']})"
                    logger.info(f"[POSE] Hand entered shelf: Track {cam2_track_id} reaching {prod['name']}")

                dwell = now - state.wrist_enter_roi_time
                state.wrist_dwell_time = dwell

                if dwell >= 0.35:
                    state.phase = STATUS_GRABBING
                    state.grabbed_product = prod
                    state.interaction_status = f"GRABBING ({prod['name']})"

                # Check if item was recently confirmed taken via weight event
                if (now - state.last_taken_time) < 3.5 and state.last_taken_product:
                    state.phase = STATUS_TAKEN
                    state.interaction_status = f"TAKEN: {state.last_taken_product['name']} (${state.last_taken_product['price']})"

            else:
                # Wrist is NOT inside shelf ROI
                state.wrist_in_shelf = False
                state.active_wrist = None
                state.wrist_enter_roi_time = 0
                state.wrist_dwell_time = 0
                state.grabbed_product = None

                # Only show TAKEN status if confirmed by weight event within 3.5s
                if (now - state.last_taken_time) < 3.5 and state.last_taken_product:
                    state.phase = STATUS_TAKEN
                    state.interaction_status = f"TAKEN: {state.last_taken_product['name']} (${state.last_taken_product['price']})"
                else:
                    state.phase = STATUS_NOT_IN_SHELF
                    state.interaction_status = self._determine_status(state, score)

            if state.interaction_status in (STATUS_INTERACTING, STATUS_APPROACHING, STATUS_REACHING, STATUS_GRABBING, STATUS_TAKEN):
                state.last_interaction_time = time.time()

            # Item pickup is strictly determined by Pose Interaction + Weight Change fusion.
            # Pose alone does NOT generate an unconfirmed taken event.
            return state, None

    def _compute_interaction_score(self, state: PoseInteractionState,
                                    bbox: Tuple[int, int, int, int]) -> float:
        """Compute lightweight interaction evidence score from keypoints."""
        score = 0.0
        shelf_roi = config.SHELF_ROI
        rx1, ry1, rx2, ry2 = shelf_roi

        wrist_roi_score = 0.0
        for wrist in [state.left_wrist, state.right_wrist]:
            if wrist:
                wx, wy = wrist
                if rx1 <= wx <= rx2 and ry1 <= wy <= ry2:
                    wrist_roi_score = max(wrist_roi_score, 1.0)
                else:
                    rcx = (rx1 + rx2) / 2
                    rcy = (ry1 + ry2) / 2
                    max_dist = ((rx2 - rx1) + (ry2 - ry1)) / 2
                    dist = ((wx - rcx) ** 2 + (wy - rcy) ** 2) ** 0.5
                    proximity = max(0, 1.0 - dist / max(1, max_dist))
                    wrist_roi_score = max(wrist_roi_score, proximity * 0.5)
        score += 0.40 * wrist_roi_score

        reach_score = 0.0
        for wrist, shoulder in [(state.left_wrist, state.left_shoulder),
                                 (state.right_wrist, state.right_shoulder)]:
            if wrist and shoulder:
                wy = wrist[1]
                sy = shoulder[1]
                if wy >= sy - 30:
                    reach_score = max(reach_score, 0.8)
                arm_extension = abs(wrist[0] - shoulder[0])
                body_h = max(1, bbox[3] - bbox[1])
                if arm_extension > body_h * 0.12:
                    reach_score = max(reach_score, 0.7)
        score += 0.25 * reach_score

        now = time.monotonic()
        if state._roi_enter_time == 0:
            state._roi_enter_time = now
        state._roi_dwell_time = now - state._roi_enter_time
        dwell_score = min(1.0, state._roi_dwell_time / 2.5)
        score += 0.20 * dwell_score

        body_cx = (bbox[0] + bbox[2]) / 2
        roi_cx = (rx1 + rx2) / 2
        centering = max(0, 1.0 - abs(body_cx - roi_cx) / max(1, (rx2 - rx1) / 2))
        score += 0.15 * centering

        return min(1.0, score)

    def _determine_status(self, state: PoseInteractionState, score: float) -> str:
        """State machine for interaction status when not grabbing/taken."""
        if not state.inside_shelf_roi:
            state._roi_enter_time = 0
            state._roi_dwell_time = 0
            return STATUS_NOT_IN_SHELF

        if score >= 0.55:
            return STATUS_INTERACTING
        elif score >= 0.25:
            return STATUS_APPROACHING
        elif state.interaction_status == STATUS_INTERACTING:
            return STATUS_LEAVING
        else:
            return STATUS_APPROACHING

    def update_roi_status(self, cam2_track_id: int, inside_roi: bool,
                          customer_id: Optional[str] = None,
                          session_id: Optional[str] = None):
        """Update ROI status for tracks."""
        with self._states_lock:
            state = self._states.get(cam2_track_id)
            if state is None:
                state = PoseInteractionState(cam2_track_id)
                self._states[cam2_track_id] = state

            prev_in_roi = state.inside_shelf_roi
            state.inside_shelf_roi = inside_roi
            if customer_id:
                state.customer_id = customer_id
            if session_id:
                state.session_id = session_id

            if inside_roi and not prev_in_roi:
                state._roi_enter_time = time.monotonic()
                state.interaction_status = STATUS_APPROACHING
            elif not inside_roi and prev_in_roi:
                state.interaction_status = STATUS_LEAVING
                state._roi_enter_time = 0
                state._roi_dwell_time = 0

    def get_state(self, cam2_track_id: int) -> Optional[PoseInteractionState]:
        """Retrieve pose interaction state for a track."""
        with self._states_lock:
            return self._states.get(cam2_track_id)

    def confirm_weight_taken(self, customer_id: Optional[str] = None,
                             product: Optional[Dict[str, Any]] = None,
                             cam2_track_id: Optional[int] = None):
        """Update pose state when a weight change event confirms that a product was taken."""
        if not product:
            return
        now = time.monotonic()
        with self._states_lock:
            matched = None
            if cam2_track_id is not None and cam2_track_id in self._states:
                matched = self._states[cam2_track_id]
            elif customer_id:
                for s in self._states.values():
                    if s.customer_id == customer_id:
                        matched = s
                        break
            if matched:
                matched.last_taken_product = product
                matched.last_taken_time = now
                matched.phase = STATUS_TAKEN
                matched.interaction_status = f"TAKEN: {product.get('name', 'Product')} (${product.get('price', '0.00')})"
                logger.info(f"[POSE] Item taken confirmed by weight event for Track {matched.cam2_track_id}: {product.get('name')}")

    def get_display_info(self, cam2_track_id: int, state_mgr=None) -> Dict[str, Any]:
        """Return rich display-friendly status dictionary for camera overlay."""
        with self._states_lock:
            state = self._states.get(cam2_track_id)
            now = time.monotonic()

            # Check if global state manager has a weight-confirmed pickup for this customer/track
            if state_mgr:
                confirmed = state_mgr.get_last_confirmed_taken()
                if confirmed:
                    cid = (state.customer_id if state else None) or state_mgr.get_customer_for_cam2_track(cam2_track_id)
                    if cid and cid == confirmed.get("customer_id"):
                        if state:
                            state.last_taken_product = confirmed.get("product")
                            state.last_taken_time = now
                            state.phase = STATUS_TAKEN

            if state is None:
                last_taken = None
                is_taken_recent = False
                if state_mgr:
                    confirmed = state_mgr.get_last_confirmed_taken()
                    cid = state_mgr.get_customer_for_cam2_track(cam2_track_id)
                    if confirmed and cid and cid == confirmed.get("customer_id"):
                        last_taken = confirmed.get("product")
                        is_taken_recent = True

                return {
                    "roi_status": "",
                    "pose_status": "",
                    "interaction_status": f"TAKEN: {last_taken.get('name', 'Product')}" if is_taken_recent else "",
                    "last_taken": last_taken,
                    "is_taken_recent": is_taken_recent,
                    "active_wrist": None,
                    "active_product": None,
                    "phase": "TAKEN" if is_taken_recent else "IDLE",
                }

            is_taken_recent = (now - state.last_taken_time) < 3.5 and state.last_taken_product is not None

            if is_taken_recent:
                prod = state.last_taken_product
                interaction_str = f"TAKEN: {prod['name']} (${prod['price']})"
            elif state.phase == STATUS_GRABBING and state.active_product:
                interaction_str = f"GRABBING: {state.active_product['name']}"
            elif state.phase == STATUS_REACHING and state.active_product:
                interaction_str = f"REACHING: {state.active_product['name']}"
            elif state.interaction_status != STATUS_NOT_IN_SHELF:
                interaction_str = state.interaction_status
            else:
                interaction_str = ""

            roi_str = "SHELF" if state.inside_shelf_roi else ""
            pose_str = "POSE ACTIVE" if (now - state.last_pose_timestamp) < 1.0 else ""

            return {
                "roi_status": roi_str,
                "pose_status": pose_str,
                "interaction_status": interaction_str,
                "last_taken": state.last_taken_product if is_taken_recent else None,
                "is_taken_recent": is_taken_recent,
                "active_wrist": state.active_wrist if state.wrist_in_shelf else None,
                "left_wrist": state.left_wrist,
                "right_wrist": state.right_wrist,
                "left_elbow": state.left_elbow,
                "right_elbow": state.right_elbow,
                "left_shoulder": state.left_shoulder,
                "right_shoulder": state.right_shoulder,
                "active_product": state.active_product,
                "phase": state.phase,
                "keypoints": state.keypoints,
            }

    def sync_to_state_manager(self, cam2_track_id: int, state_mgr):
        """Push current pose state to the global state manager."""
        with self._states_lock:
            state = self._states.get(cam2_track_id)
            if state and state_mgr:
                state_mgr.set_pose_state(cam2_track_id, state.to_dict())

    def clean_stale(self, max_age: float = 30.0):
        """Remove pose states for tracks not updated recently."""
        now = time.monotonic()
        with self._states_lock:
            stale = [
                tid for tid, s in self._states.items()
                if now - s.last_pose_timestamp > max_age and s.last_pose_timestamp > 0
            ]
            for tid in stale:
                del self._states[tid]
