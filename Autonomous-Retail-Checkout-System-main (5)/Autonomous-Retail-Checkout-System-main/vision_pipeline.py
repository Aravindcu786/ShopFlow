"""Dual-camera motion-gated detection and tracking pipeline."""

import os
import cv2
import time
import threading
import numpy as np
from ultralytics import YOLO
from urllib.parse import urlparse, urlunparse
from reid_engine import PersonReIDEngine
from ai.entry_face_manager import EntryFaceManager, get_customer_color
from services.shelf_roi_service import is_person_in_shelf_roi, is_person_in_exit_roi, get_shelf_roi, get_exit_roi
from services.pose_interaction_service import PoseInteractionManager
import config as app_config


MODEL_WEIGHTS = "yolo26n_openvino_model"
if not os.path.exists(MODEL_WEIGHTS) and not os.path.isdir(MODEL_WEIGHTS):
    MODEL_WEIGHTS = "yolo26n.pt"

YOLO_IMGSZ = 256
YOLO_CONF = 0.25
MOTION_THRESH = 0.015
JPEG_QUALITY = 80
CAM2_TRACK_OFFSET = 1000

_PALETTE = [
    (255,  56,  56),
    ( 56, 255,  56),
    ( 56,  56, 255),
    (255, 200,  56),
    (200,  56, 255),
]


def _load_tracker_model(camera_name: str):
    """Load an independent YOLO detector and tracker instance per camera."""
    print(f"[VISION] Loading {camera_name} detector/tracker: {MODEL_WEIGHTS} ...")
    return YOLO(MODEL_WEIGHTS, task="detect")


_cam1_model = _load_tracker_model("CAM1")
_cam2_model = None
_cam2_model_lock = threading.Lock()


def _get_cam2_model():
    """Lazily initialize the Camera 2 tracker on first frame."""
    global _cam2_model
    with _cam2_model_lock:
        if _cam2_model is None:
            _cam2_model = _load_tracker_model("CAM2")
        return _cam2_model


def _normalize_source(source) -> str:
    """Normalize input camera index, video file path, or stream URL."""
    if source is None:
        return ""
    s = str(source).strip().strip('"').strip("'")
    if not s or "YOUR_PHONE_IP" in s:
        return ""
    if s.isdigit():
        return s
    if s.lower().endswith(('.mp4', '.avi', '.mkv', '.mov', '.webm')):
        return s
    if not (s.startswith("http://") or s.startswith("https://") or s.startswith("rtsp://")):
        s = "http://" + s
    parsed = urlparse(s)
    if parsed.scheme in {"http", "https"}:
        path = parsed.path or ""
        if path in {"", "/"}:
            parsed = parsed._replace(path="/video")
            return urlunparse(parsed)
    return s


def _open_cap(source: str) -> "cv2.VideoCapture | None":
    """Open and configure VideoCapture for IP stream, video file, or webcam."""
    s = _normalize_source(source)
    if not s:
        return None

    is_video_file = s.lower().endswith(('.mp4', '.avi', '.mkv', '.mov', '.webm'))
    is_ip = not s.isdigit() and not is_video_file

    if is_video_file:
        try:
            cap = cv2.VideoCapture(s)
        except Exception:
            return None
    elif is_ip:
        os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = (
            "fflags;nobuffer"
            "|flags;low_delay"
            "|analyzeduration;0"
            "|probesize;32"
            "|rtsp_transport;tcp"
        )
        try:
            cap = cv2.VideoCapture(s, cv2.CAP_FFMPEG)
            if not cap.isOpened():
                cap = cv2.VideoCapture(s)
        except Exception:
            try:
                cap = cv2.VideoCapture(s)
            except Exception:
                return None
    else:
        idx = int(s)
        cap = None
        for backend in [cv2.CAP_DSHOW, cv2.CAP_MSMF, None]:
            try:
                if backend is not None:
                    c = cv2.VideoCapture(idx, backend)
                else:
                    c = cv2.VideoCapture(idx)
                if c and c.isOpened():
                    ret, test_frame = c.read()
                    if ret and test_frame is not None:
                        cap = c
                        break
                    else:
                        c.release()
            except Exception:
                continue

    if cap and cap.isOpened():
        try:
            cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
            cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
            if not is_video_file:
                cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        except Exception:
            pass

        if is_ip:
            print(f"[CAM] Draining initial buffer for {s} ...")
            try:
                for _ in range(30):
                    cap.grab()
            except Exception:
                pass
            print("[CAM] Buffer drained. Stream is now live.")

    return cap


def _draw_boxes(frame: np.ndarray, boxes: list, cam_title: str = "CAMERA",
                state_mgr=None, entry_face_mgr=None) -> np.ndarray:
    """Draw bounding boxes, shelf zones, pose skeletons, and item-taken indicators."""
    annotated = frame.copy()

    # Draw shelf ROI overlay and product zones on Shelf Camera
    if cam_title == "SHELF CAM" and getattr(app_config, "SHELF_ROI_SHOW_OVERLAY", True):
        roi = get_shelf_roi()
        # Outer Shelf ROI rectangle and neat header tag
        cv2.rectangle(annotated, (roi[0], roi[1]), (roi[2], roi[3]), (0, 215, 255), 2)
        cv2.rectangle(annotated, (roi[0], max(0, roi[1] - 22)), (roi[0] + 96, roi[1]), (25, 30, 45), -1)
        cv2.putText(annotated, "SHELF ROI", (roi[0] + 8, roi[1] - 6),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.44, (0, 215, 255), 1, cv2.LINE_AA)

        # Draw shelf matrix zone (Single Zone)
        from services.shelf_roi_service import get_shelf_zones
        zones = get_shelf_zones()
        for z in zones:
            zx1, zy1, zx2, zy2 = z["bbox"]
            z_idx = z["zone_idx"]
            z_label = z.get("zone_id", f"Zone {chr(65 + z_idx)}").upper()

            # Highlight zone if any person's hand is currently in this zone
            is_active_zone = False
            for b in boxes:
                p_info = b.get("pose_info") or {}
                act_prod = p_info.get("active_product") or {}
                if act_prod.get("zone_idx") == z_idx and p_info.get("active_wrist"):
                    is_active_zone = True
                    break

            if is_active_zone:
                z_color = (0, 255, 120)
                z_thick = 2
            else:
                z_color = (180, 180, 50)
                z_thick = 1

            # Outer boundary of zone
            cv2.rectangle(annotated, (zx1, zy1), (zx2, zy2), z_color, z_thick)

            # Compact zone header at the top
            cv2.rectangle(annotated, (zx1, zy1), (zx2, zy1 + 22), (20, 25, 38), -1)
            cv2.rectangle(annotated, (zx1, zy1), (zx2, zy1 + 22), z_color, 1)

            # Centered zone label without text collisions
            tw = cv2.getTextSize(z_label, cv2.FONT_HERSHEY_SIMPLEX, 0.42, 1)[0][0]
            tx = zx1 + max(4, (zx2 - zx1 - tw) // 2)
            cv2.putText(annotated, z_label, (tx, zy1 + 16),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.42, z_color, 1, cv2.LINE_AA)

            if is_active_zone:
                cv2.rectangle(annotated, (zx1 + 6, zy2 - 20), (zx2 - 6, zy2 - 4), (0, 180, 80), -1)
                cv2.putText(annotated, "ACTIVE", (zx1 + 14, zy2 - 8),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.36, (255, 255, 255), 1, cv2.LINE_AA)

    # Draw exit ROI overlay on Entry Camera
    if cam_title == "ENTRY CAM" and getattr(app_config, "SHELF_ROI_SHOW_OVERLAY", True):
        roi = get_exit_roi()
        cv2.rectangle(annotated, (roi[0], roi[1]), (roi[2], roi[3]), (0, 0, 255), 2)
        cv2.putText(annotated, "EXIT ROI", (roi[0] + 5, roi[1] + 20),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 255), 1, cv2.LINE_AA)

    # Top banner for Shelf Camera when an item has been taken
    if cam_title == "SHELF CAM":
        recent_taken = None
        for b in boxes:
            p_info = b.get("pose_info") or {}
            if p_info.get("is_taken_recent") and p_info.get("last_taken"):
                recent_taken = p_info["last_taken"]
                break
        if not recent_taken and state_mgr and hasattr(state_mgr, "get_last_confirmed_taken"):
            confirmed = state_mgr.get_last_confirmed_taken()
            if confirmed:
                recent_taken = confirmed.get("product")
        if recent_taken:
            t_name = recent_taken.get("name", "Product")
            t_price = recent_taken.get("price", "0.00")
            cv2.rectangle(annotated, (15, 10), (625, 48), (0, 160, 0), -1)
            cv2.rectangle(annotated, (15, 10), (625, 48), (255, 255, 255), 2)
            cv2.putText(annotated, f"ITEM TAKEN: {t_name} (${t_price}) - CART UPDATED (WEIGHT CONFIRMED)",
                        (20, 35), cv2.FONT_HERSHEY_SIMPLEX, 0.48, (255, 255, 255), 2, cv2.LINE_AA)

    for b in boxes:
        tid = b["track_id"]
        x1, y1, x2, y2 = b["bbox"]

        # Render standalone detected product objects
        if b.get("is_object"):
            cv2.rectangle(annotated, (x1, y1), (x2, y2), (255, 200, 0), 2)
            cv2.putText(annotated, b.get("badge", "OBJECT"), (x1 + 4, max(16, y1 - 6)),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.42, (255, 200, 0), 1, cv2.LINE_AA)
            continue

        cid = b.get("cust_id")
        if not cid:
            if entry_face_mgr and cam_title != "SHELF CAM":
                cid, _, _ = entry_face_mgr.get_display_info(tid)
            elif state_mgr and tid in state_mgr.track_to_customer:
                cid = state_mgr.track_to_customer[tid]
            else:
                cid = None

        customer_id = str(cid or "")
        is_verified = bool(customer_id) and not customer_id.startswith(("TRACK_", "CAM2-", "YOLO", "UNREGISTERED"))
        badge_text = b.get("badge", "")

        # Always show bounding boxes for all active tracks on both cameras
        show_box = is_verified or (tid > 0)
        if not show_box:
            continue

        if not is_verified:
            if cam_title == "SHELF CAM":
                raw_id = tid - CAM2_TRACK_OFFSET if tid >= CAM2_TRACK_OFFSET else tid
                if badge_text == "UNREGISTERED" or not customer_id:
                    customer_id = "UNREGISTERED"
                else:
                    customer_id = f"CAM2-T{raw_id}"
            else:
                # ENTRY CAM: unverified persons do NOT show a customer ID
                if badge_text == "UNREGISTERED":
                    customer_id = "UNREGISTERED"
                    badge_text = ""
                elif not customer_id or customer_id.startswith("TRACK_"):
                    customer_id = f"TRACK_{tid}"

        colour = b.get("color") or get_customer_color(customer_id)
        cv2.rectangle(annotated, (x1, y1), (x2, y2), colour, 2)

        # Draw pose skeleton and wrist interaction if available on shelf cam
        p_info = b.get("pose_info") or {}
        if p_info:
            ls, rs = p_info.get("left_shoulder"), p_info.get("right_shoulder")
            le, re = p_info.get("left_elbow"), p_info.get("right_elbow")
            lw, rw = p_info.get("left_wrist"), p_info.get("right_wrist")

            # Arm bones
            if ls and le:
                cv2.line(annotated, (int(ls[0]), int(ls[1])), (int(le[0]), int(le[1])), (0, 240, 255), 2)
            if le and lw:
                cv2.line(annotated, (int(le[0]), int(le[1])), (int(lw[0]), int(lw[1])), (0, 240, 255), 2)
            if rs and re:
                cv2.line(annotated, (int(rs[0]), int(rs[1])), (int(re[0]), int(re[1])), (0, 240, 255), 2)
            if re and rw:
                cv2.line(annotated, (int(re[0]), int(re[1])), (int(rw[0]), int(rw[1])), (0, 240, 255), 2)

            # Wrist interaction indicators
            roi = get_shelf_roi()
            for w in [lw, rw]:
                if w:
                    wx, wy = int(w[0]), int(w[1])
                    in_roi = (roi[0] <= wx <= roi[2]) and (roi[1] <= wy <= roi[3])
                    if in_roi:
                        cv2.circle(annotated, (wx, wy), 9, (0, 255, 0), -1)
                        cv2.circle(annotated, (wx, wy), 13, (0, 255, 120), 2)
                        cv2.putText(annotated, "HAND IN SHELF", (wx + 10, wy),
                                    cv2.FONT_HERSHEY_SIMPLEX, 0.38, (0, 255, 0), 1, cv2.LINE_AA)
                    else:
                        cv2.circle(annotated, (wx, wy), 5, (255, 200, 0), -1)

        # Build display lines
        display_lines = [customer_id]
        if badge_text:
            for part in badge_text.split(" | "):
                part_s = part.strip()
                if part_s and part_s != customer_id:
                    display_lines.append(part_s)

        line_height = 16
        total_h = len(display_lines) * line_height + 8
        banner_top = max(0, y1 - total_h)
        banner_bottom = y1

        max_tw = 0
        for line in display_lines:
            (tw, _), _ = cv2.getTextSize(line, cv2.FONT_HERSHEY_SIMPLEX, 0.42, 1)
            max_tw = max(max_tw, tw)
        banner_right = min(annotated.shape[1], x1 + max_tw + 16)

        cv2.rectangle(annotated, (x1, banner_top), (banner_right, banner_bottom), colour, -1)

        for i, line in enumerate(display_lines):
            text_y = banner_top + (i + 1) * line_height
            cv2.putText(annotated, line, (x1 + 4, text_y),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.42, (255, 255, 255), 1, cv2.LINE_AA)

    return annotated


class CameraGrabber:
    """Background frame capture loop for a single camera source."""

    _SOURCE_TTL: float = 5.0

    def __init__(self, name: str, config_key: str, peer_grabber=None):
        self.name = name
        self.config_key = config_key
        self.peer_grabber = peer_grabber

        self._raw_lock = threading.Lock()
        self._raw_frame = None
        self._new_frame_evt = threading.Event()

        self._src_cache = ""
        self._src_cache_time = 0.0

        self._thread = threading.Thread(
            target=self._grab_loop, daemon=True, name=f"grab-{name}"
        )

    def start(self):
        """Start the background frame capture thread."""
        self._thread.start()
        print(f"[{self.name}] Grab thread started.")

    def get_frame(self):
        """Return the latest raw frame or shared peer frame."""
        with self._raw_lock:
            if self._raw_frame is not None:
                return self._raw_frame
        if self.peer_grabber is not None and self._get_source() == self.peer_grabber._get_source():
            return self.peer_grabber.get_frame()
        return None

    def _get_source(self) -> str:
        """Fetch camera source setting with TTL cache."""
        now = time.monotonic()
        if now - self._src_cache_time > self._SOURCE_TTL:
            try:
                import config
                self._src_cache = config.reload().get(self.config_key, "")
                self._src_cache_time = now
            except Exception:
                pass
        return self._src_cache

    def _grab_loop(self):
        """Continuously grab frames from camera source."""
        cap = None
        current_src = ""
        last_check = 0.0
        fail_count = 0

        while True:
            now = time.monotonic()

            if now - last_check > self._SOURCE_TTL:
                new_src = self._get_source()
                last_check = now
            else:
                new_src = current_src

            if self.peer_grabber is not None:
                peer_src = self.peer_grabber._get_source()
                if new_src and new_src == peer_src:
                    if cap is not None:
                        cap.release()
                        cap = None
                        current_src = ""
                    peer_frame = self.peer_grabber.get_frame()
                    if peer_frame is not None:
                        with self._raw_lock:
                            self._raw_frame = peer_frame.copy()
                        self._new_frame_evt.set()
                    time.sleep(0.02)
                    continue

            if new_src != current_src or cap is None or not cap.isOpened():
                if cap is not None:
                    cap.release()
                cap = _open_cap(new_src)
                current_src = new_src
                fail_count = 0
                if cap is None or not cap.isOpened():
                    time.sleep(2.0)
                    continue
                print(f"[{self.name}] Camera opened: {new_src or '(none)'}")

            ret, frame = cap.read()
            if ret and frame is not None:
                fail_count = 0
                with self._raw_lock:
                    self._raw_frame = frame
                self._new_frame_evt.set()

                if current_src.lower().endswith(('.mp4', '.avi', '.mkv', '.mov', '.webm')):
                    fps = cap.get(cv2.CAP_PROP_FPS)
                    delay = 1.0 / fps if fps > 0 else 0.033
                    time.sleep(delay)
            else:
                fail_count += 1
                if cap and current_src.lower().endswith(('.mp4', '.avi', '.mkv', '.mov', '.webm')):
                    cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                    time.sleep(0.033)
                else:
                    if fail_count > 10:
                        if cap is not None:
                            cap.release()
                            cap = None
                        fail_count = 0
                        time.sleep(2.0)
                    else:
                        time.sleep(0.033)


class BatchedDetector:
    """Dual-camera detector running YOLO tracking and re-identification."""

    def __init__(self, grabber1: CameraGrabber, grabber2: CameraGrabber,
                 is_entry1: bool = True, state_mgr=None):
        self.grabber1 = grabber1
        self.grabber2 = grabber2
        self.is_entry1 = is_entry1
        self.state_mgr = state_mgr

        self.bg_sub1 = cv2.createBackgroundSubtractorMOG2(
            history=300, varThreshold=24, detectShadows=False
        )
        self.bg_sub2 = cv2.createBackgroundSubtractorMOG2(
            history=300, varThreshold=24, detectShadows=False
        )

        self._det_lock = threading.Lock()
        self._state = {
            "cam1": {"boxes": [], "tracks": {}, "badge": "STATIC | YOLO SKIPPED", "bcolor": (130, 130, 130)},
            "cam2": {"boxes": [], "tracks": {}, "badge": "STATIC | YOLO SKIPPED", "bcolor": (130, 130, 130)},
        }

        self.reid_engine = PersonReIDEngine(sim_threshold=0.75)
        self.entry_face_mgr = EntryFaceManager(state_mgr=self.state_mgr, reid_engine=self.reid_engine)
        self.pose_mgr = PoseInteractionManager()

        self._frame_lock = threading.Lock()
        self._ann_frame1 = self._make_standby_frame("Entry Camera", "Initializing...")
        self._ann_frame2 = self._make_standby_frame("Shelf Camera", "Initializing...")

        self._thread = threading.Thread(
            target=self._detect_loop, daemon=True, name="batched-detector"
        )

    def _make_standby_frame(self, cam_name: str, message: str) -> np.ndarray:
        """Create a stylized standby frame when a camera is offline or initializing."""
        img = np.zeros((480, 640, 3), dtype=np.uint8)
        img[:] = (18, 24, 38)
        cv2.rectangle(img, (20, 20), (620, 460), (38, 50, 75), 2)
        cv2.circle(img, (45, 230), 6, (255, 120, 56), -1, cv2.LINE_AA)
        title = f"{cam_name}: {message}"
        cv2.putText(img, title, (62, 236), cv2.FONT_HERSHEY_SIMPLEX, 0.62, (241, 245, 249), 2, cv2.LINE_AA)
        cv2.putText(img, "Autonomous Retail Surveillance | Configure in Admin Panel (/admin)", (62, 270),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.44, (148, 163, 184), 1, cv2.LINE_AA)
        return img

    def start(self):
        """Start background face manager and detection loop."""
        self.entry_face_mgr.start()
        self._thread.start()
        print("[BATCHED] Detector thread started — single model, motion-gated batch inference.")

    def get_jpeg1(self):
        return self._encode_jpeg(1)

    def get_jpeg2(self):
        return self._encode_jpeg(2)

    def get_tracks1(self) -> dict:
        with self._det_lock:
            return dict(self._state["cam1"]["tracks"])

    def get_tracks2(self) -> dict:
        with self._det_lock:
            return dict(self._state["cam2"]["tracks"])

    def get_shelf_matrix_status(self) -> dict:
        """Compute structured real-time shelf zone matrix state from active detections and pose."""
        from services.shelf_roi_service import get_shelf_zones, get_shelf_roi

        roi = get_shelf_roi()
        zones = get_shelf_zones()

        with self._det_lock:
            cam2_boxes = list(self._state["cam2"].get("boxes", []))
            detected_objs = list(self._state["cam2"].get("detected_objects", []))

        # Check interacting customers on cam2
        interacting_zones = {}
        recent_taken_zone = None

        for b in cam2_boxes:
            p_info = b.get("pose_info") or {}
            cid = b.get("cust_id", "")

            # Check active wrist location
            act_wrist = p_info.get("active_wrist")
            phase = p_info.get("phase", "IDLE")
            if act_wrist:
                wx, wy = act_wrist
                for z in zones:
                    zx1, zy1, zx2, zy2 = z["bbox"]
                    if zx1 <= wx <= zx2 and zy1 <= wy <= zy2:
                        interacting_zones[z["zone_idx"]] = {
                            "customer_id": cid,
                            "phase": phase,
                            "status": "GRABBING" if phase == "GRABBING" else "REACHING",
                            "wrist": [int(wx), int(wy)],
                        }

            # Check recently taken
            if p_info.get("is_taken_recent") and p_info.get("last_taken"):
                lt = p_info["last_taken"]
                recent_taken_zone = {
                    "zone_idx": lt.get("zone_idx", 0),
                    "product_name": lt.get("name"),
                    "price": lt.get("price"),
                    "customer_id": cid,
                }

        if not recent_taken_zone and self.state_mgr and hasattr(self.state_mgr, "get_last_confirmed_taken"):
            confirmed = self.state_mgr.get_last_confirmed_taken()
            if confirmed:
                prod = confirmed.get("product", {})
                recent_taken_zone = {
                    "zone_idx": prod.get("zone_idx", 0),
                    "product_name": prod.get("name"),
                    "price": prod.get("price"),
                    "customer_id": confirmed.get("customer_id"),
                }

        # Build zone status list
        zone_list = []
        for z in zones:
            z_idx = z["zone_idx"]
            zx1, zy1, zx2, zy2 = z["bbox"]

            # Match detected physical objects in this zone
            zone_detections = []
            for obj in detected_objs:
                bx1, by1, bx2, by2 = obj["bbox"]
                ocx = (bx1 + bx2) / 2.0
                ocy = (by1 + by2) / 2.0
                if (zx1 - 15 <= ocx <= zx2 + 15) and (zy1 - 15 <= ocy <= zy2 + 15):
                    zone_detections.append({
                        "label": obj["label"],
                        "confidence": round(float(obj["conf"]), 2),
                        "bbox": [bx1, by1, bx2, by2],
                    })

            # Determine occupancy & state
            inter_info = interacting_zones.get(z_idx)
            is_taken = (recent_taken_zone and recent_taken_zone.get("zone_idx") == z_idx)

            if is_taken:
                state = "TAKEN"
                state_badge = "ITEM TAKEN"
                status_detail = f"Picked by {recent_taken_zone.get('customer_id', 'Customer')}"
            elif inter_info:
                state = inter_info["status"]
                state_badge = inter_info["status"]
                status_detail = f"Customer {inter_info['customer_id']} ({inter_info['phase'].lower()})"
            elif zone_detections:
                state = "DETECTED"
                state_badge = "DETECTED"
                top_conf = max(d["confidence"] for d in zone_detections)
                status_detail = f"Vision match: {zone_detections[0]['label']} ({int(top_conf * 100)}%)"
            else:
                state = "AVAILABLE"
                state_badge = "IN STOCK"
                status_detail = "In Stock & Monitored"

            zone_list.append({
                "zone_id": z["zone_id"],
                "zone_letter": z.get("zone_letter", chr(65 + z_idx)),
                "zone_idx": z_idx,
                "name": z["name"],
                "product_id": z["product_id"],
                "price": z["price"],
                "bbox": [zx1, zy1, zx2, zy2],
                "state": state,
                "state_badge": state_badge,
                "status_detail": status_detail,
                "detected_count": len(zone_detections),
                "detected_items": zone_detections,
                "customer": inter_info["customer_id"] if inter_info else None,
            })

        return {
            "timestamp": time.time(),
            "shelf_roi": list(roi),
            "frame_size": [640, 480],
            "zones": zone_list,
            "recent_taken": recent_taken_zone,
        }

    def _encode_jpeg(self, cam_id: int):
        """Encode current annotated camera frame to JPEG bytes."""
        with self._frame_lock:
            frame = self._ann_frame1 if cam_id == 1 else self._ann_frame2

        if frame is None:
            cam_title = "ENTRY CAM" if cam_id == 1 else "SHELF CAM"
            frame = self._make_standby_frame(cam_title, "Loading...")

        ok, buf = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY])
        if not ok or buf is None:
            cam_title = "ENTRY CAM" if cam_id == 1 else "SHELF CAM"
            fallback = self._make_standby_frame(cam_title, "Loading...")
            _, buf = cv2.imencode(".jpg", fallback, [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY])
        return buf.tobytes() if buf is not None else b""

    def _motion_ratio(self, bg_sub, frame: np.ndarray) -> float:
        """Apply MOG2 on a downscaled gray frame and return changed-pixel ratio."""
        small = cv2.resize(frame, (320, 240), interpolation=cv2.INTER_NEAREST)
        gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
        mask = bg_sub.apply(gray)
        return cv2.countNonZero(mask) / (320.0 * 240.0)

    def _detect_loop(self):
        """Main detection loop processing both camera feeds continuously."""
        cycle = 0
        while True:
            cycle += 1
            try:
                frame1 = self.grabber1.get_frame()
                frame2 = self.grabber2.get_frame()

                s1 = self.grabber1._get_source()
                s2 = self.grabber2._get_source()

                if frame2 is None and frame1 is not None:
                    if s1 == s2 or not s2:
                        frame2 = frame1.copy()
                elif frame1 is None and frame2 is not None:
                    if s1 == s2 or not s1:
                        frame1 = frame2.copy()

                ph1_used = (frame1 is None)
                ph2_used = (frame2 is None)

                if ph1_used:
                    src1 = s1 or "None"
                    frame1 = self._make_standby_frame("Entry Camera", f"Offline ({src1})")
                if ph2_used:
                    src2 = s2 or "None"
                    frame2 = self._make_standby_frame("Shelf Camera", f"Offline ({src2})")

                ratio1 = 0.0 if ph1_used else self._motion_ratio(self.bg_sub1, frame1)
                ratio2 = 0.0 if ph2_used else self._motion_ratio(self.bg_sub2, frame2)
                moving1 = ratio1 >= MOTION_THRESH
                moving2 = ratio2 >= MOTION_THRESH

                res1 = _cam1_model.track(frame1, persist=True, tracker="bytetrack.yaml", classes=[0],
                                         imgsz=YOLO_IMGSZ, conf=YOLO_CONF, verbose=False) if not ph1_used else None
                res2 = _get_cam2_model().track(frame2, persist=True, tracker="bytetrack.yaml", classes=[0, 39, 41, 46, 47, 48],
                                         imgsz=YOLO_IMGSZ, conf=YOLO_CONF, verbose=False) if not ph2_used else None
                r1 = res1[0] if res1 else None
                r2 = res2[0] if res2 else None

                with self._det_lock:
                    self._state["cam1"].update({
                        "badge": "MOTION | DETECTING" if moving1 else "DETECTING",
                        "bcolor": (56, 255, 56) if moving1 else (130, 130, 130),
                    })
                    self._state["cam2"].update({
                        "badge": "MOTION | DETECTING" if moving2 else "DETECTING",
                        "bcolor": (56, 255, 56) if moving2 else (130, 130, 130),
                    })

                ann1 = self._parse_and_draw(r1, frame1, "ENTRY CAM",
                                             cam_key="cam1", is_entry=self.is_entry1) if not ph1_used else frame1
                ann2 = self._parse_and_draw(r2, frame2, "SHELF CAM",
                                             cam_key="cam2", is_entry=False) if not ph2_used else frame2

                with self._frame_lock:
                    self._ann_frame1 = ann1
                    self._ann_frame2 = ann2
                self.entry_face_mgr.clean_stale_tracks(max_age_seconds=5.0)
                self.reid_engine.clean_stale_tracks()
                self.pose_mgr.clean_stale()

            except Exception as e:
                print(f"[BATCHED] detect_loop error (thread kept alive): {e}")
                time.sleep(0.1)

    def _parse_and_draw(self, result, frame: np.ndarray, cam_title: str,
                        cam_key: str, is_entry: bool) -> np.ndarray:
        """Parse tracker output, perform identification/re-ID, and render bounding boxes."""
        boxes_out = []
        tracks_out = {}
        detected_objects = []
        person_boxes = []

        if result is not None and result.boxes is not None:
            for box in result.boxes:
                cls_id = int(box.cls.item()) if hasattr(box, "cls") and box.cls is not None else 0
                if not is_entry and cls_id != 0:
                    ox1, oy1, ox2, oy2 = map(int, box.xyxy[0].tolist())
                    label = _get_cam2_model().names.get(cls_id, f"obj_{cls_id}")
                    conf = float(box.conf.item()) if hasattr(box, "conf") and box.conf is not None else 0.0
                    detected_objects.append({
                        "bbox": (ox1, oy1, ox2, oy2),
                        "label": label,
                        "conf": conf,
                    })
                    boxes_out.append({
                        "track_id": -2,
                        "bbox": (ox1, oy1, ox2, oy2),
                        "pos": ((ox1 + ox2) / 2.0, (oy1 + oy2) / 2.0),
                        "cust_id": label.upper(),
                        "color": (255, 200, 0),
                        "badge": f"{label} ({conf:.2f})",
                        "is_object": True,
                    })
                else:
                    person_boxes.append(box)

            for box in person_boxes:
                if box.id is None:
                    x1, y1, x2, y2 = map(int, box.xyxy[0].tolist())
                    boxes_out.append({
                        "track_id": -1,
                        "bbox": (x1, y1, x2, y2),
                        "pos": ((x1 + x2) / 2.0, (y1 + y2) / 2.0),
                        "cust_id": "YOLO",
                        "color": (0, 215, 255),
                        "badge": "NO TRACK ID",
                    })
                    continue
                raw_tid = int(box.id.item())
                tid = raw_tid if is_entry else raw_tid + CAM2_TRACK_OFFSET
                x1, y1, x2, y2 = map(int, box.xyxy[0].tolist())
                cx, cy = (x1 + x2) / 2.0, (y1 + y2) / 2.0
                pose_info = None

                if is_entry:
                    self.entry_face_mgr.process_track_frame(frame, (x1, y1, x2, y2), tid)
                    disp_cid, color, badge = self.entry_face_mgr.get_display_info(tid)

                    # Exit ROI detection
                    if disp_cid and not disp_cid.startswith(("TRACK_", "YOLO")):
                        if is_person_in_exit_roi((x1, y1, x2, y2)):
                            try:
                                from services.session_service import handle_exit_detection
                                from database.connection import app_context_guard
                                with app_context_guard():
                                    exit_res = handle_exit_detection(disp_cid, self.state_mgr)
                                    if exit_res.get("exit_allowed"):
                                        badge = "EXIT ALLOWED"
                                    else:
                                        badge = "EXIT BLOCKED"
                            except Exception:
                                pass
                else:
                    disp_cid, badge = self.reid_engine.associate_cam2_track(
                        frame, (x1, y1, x2, y2), tid, f"CAM2-T{raw_tid}", self.state_mgr
                    )

                    # Link cam2 track to customer and session ONLY if Re-ID successfully matched a verified customer
                    is_verified_cust = bool(disp_cid) and not disp_cid.startswith(("CAM2-", "TRACK_", "YOLO", "UNREGISTERED"))
                    if is_verified_cust:
                        cust_id = disp_cid
                        sess_id = self.state_mgr.get_session_for_customer(cust_id) if self.state_mgr else None
                        if self.state_mgr and sess_id:
                            self.state_mgr.link_cam2_track(tid, cust_id, sess_id)
                    else:
                        cust_id = None
                        sess_id = None

                    color = get_customer_color(disp_cid)

                    # --- Shelf ROI and proximity check ---
                    from services.shelf_roi_service import is_person_near_shelf
                    in_shelf = is_person_in_shelf_roi((x1, y1, x2, y2), threshold=0.05) or is_person_near_shelf((x1, y1, x2, y2))

                    # --- Pose inference & Shelf interaction detection ---
                    pose_info = self.pose_mgr.get_display_info(tid, self.state_mgr)
                    if in_shelf:
                        self.pose_mgr.update_roi_status(tid, True, cust_id, sess_id)
                        if self.pose_mgr.should_run_pose(tid, True):
                            state_res, _ = self.pose_mgr.run_pose_inference(
                                frame, (x1, y1, x2, y2), tid,
                                customer_id=cust_id, session_id=sess_id,
                                detected_objects=detected_objects,
                            )
                            # NOTE: Pose interaction tracks REACHING / GRABBING status and active product.
                            # Actual item pickup and cart addition strictly require weight change confirmation
                            # via process_weight_event().
                            if self.state_mgr:
                                self.pose_mgr.sync_to_state_manager(tid, self.state_mgr)
                        pose_info = self.pose_mgr.get_display_info(tid, self.state_mgr)
                    else:
                        self.pose_mgr.update_roi_status(tid, False, cust_id, sess_id)
                        if self.state_mgr:
                            self.pose_mgr.sync_to_state_manager(tid, self.state_mgr)
                        pose_info = self.pose_mgr.get_display_info(tid, self.state_mgr)

                    # Build enhanced badge
                    badge_parts = []
                    if is_verified_cust:
                        badge_parts.append(badge)
                    elif badge:
                        badge_parts.append(badge)

                    if pose_info.get("interaction_status"):
                        badge_parts.append(pose_info["interaction_status"])
                    elif in_shelf:
                        badge_parts.append("NEAR SHELF")

                    badge = " | ".join(badge_parts) if badge_parts else badge

                tracks_out[tid] = (cx, cy)
                boxes_out.append({
                    "track_id": tid,
                    "bbox": (x1, y1, x2, y2),
                    "pos": (cx, cy),
                    "cust_id": disp_cid,
                    "color": color,
                    "badge": badge,
                    "pose_info": pose_info if not is_entry else None,
                })

        with self._det_lock:
            self._state[cam_key].update({
                "boxes": boxes_out,
                "tracks": tracks_out,
                "detected_objects": detected_objects if not is_entry else [],
            })

        return _draw_boxes(frame, boxes_out, cam_title, self.state_mgr, self.entry_face_mgr)


class VisionSystem:
    """Facade managing camera grabbers and batched detection pipeline."""

    def __init__(self, state_manager=None):
        self.state_mgr = state_manager

        self.grabber1 = CameraGrabber("Entry Camera", config_key="entry_camera")
        self.grabber2 = CameraGrabber("Shelf Camera", config_key="shelf_camera", peer_grabber=self.grabber1)

        self.detector = BatchedDetector(
            self.grabber1, self.grabber2,
            is_entry1=True,
            state_mgr=state_manager,
        )

        self.entry_stream = self
        self.shelf_stream = self

        self.grabber1.start()
        self.grabber2.start()
        self.detector.start()

        print("[VISION] Pipeline LIVE — single batched YOLO model, motion-gated per-cam.")

    def entry_get_jpeg(self):
        return self.detector.get_jpeg1()

    def shelf_get_jpeg(self):
        return self.detector.get_jpeg2()

    def get_jpeg(self, cam_id: int = 1):
        return self.detector.get_jpeg1() if cam_id == 1 else self.detector.get_jpeg2()

    def process_entry_camera(self, frame: np.ndarray) -> np.ndarray:
        """Synchronously process a single entry camera frame."""
        results = _cam1_model.track(
            frame, persist=True, tracker="bytetrack.yaml", classes=[0],
            imgsz=YOLO_IMGSZ, conf=YOLO_CONF, verbose=False,
        )
        return self.detector._parse_and_draw(
            results[0] if results else None, frame, "ENTRY CAM", cam_key="cam1", is_entry=True
        )

    def process_shelf_camera(self, frame: np.ndarray) -> np.ndarray:
        """Synchronously process a single shelf camera frame."""
        results = _get_cam2_model().track(
            frame, persist=True, tracker="bytetrack.yaml", classes=[0, 39, 41, 46, 47, 48],
            imgsz=YOLO_IMGSZ, conf=YOLO_CONF, verbose=False,
        )
        return self.detector._parse_and_draw(
            results[0] if results else None, frame, "SHELF CAM", cam_key="cam2", is_entry=False
        )

    def get_latest_shelf_tracks(self) -> dict:
        """Return the latest tracked customer positions on Camera 2."""
        return self.detector.get_tracks2()

    def get_shelf_matrix_status(self) -> dict:
        """Return structured real-time shelf zone matrix state."""
        return self.detector.get_shelf_matrix_status()

