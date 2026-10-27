"""Shelf interaction ROI geometry helpers."""

import logging
from typing import Tuple, Optional

import config

logger = logging.getLogger(__name__)


def get_shelf_roi() -> Tuple[int, int, int, int]:
    """Return the configured shelf ROI as (x1, y1, x2, y2)."""
    return config.SHELF_ROI


def get_exit_roi() -> Tuple[int, int, int, int]:
    """Return the configured exit ROI as (x1, y1, x2, y2)."""
    return config.EXIT_ROI


def calculate_bbox_iou(bbox_a: Tuple[int, int, int, int],
                       bbox_b: Tuple[int, int, int, int]) -> float:
    """Calculate Intersection over Union between two bounding boxes.

    Each bbox is (x1, y1, x2, y2).
    """
    ax1, ay1, ax2, ay2 = bbox_a
    bx1, by1, bx2, by2 = bbox_b

    ix1 = max(ax1, bx1)
    iy1 = max(ay1, by1)
    ix2 = min(ax2, bx2)
    iy2 = min(ay2, by2)

    if ix2 <= ix1 or iy2 <= iy1:
        return 0.0

    intersection = (ix2 - ix1) * (iy2 - iy1)
    area_a = max(1, (ax2 - ax1) * (ay2 - ay1))
    area_b = max(1, (bx2 - bx1) * (by2 - by1))
    union = area_a + area_b - intersection

    return intersection / max(1, union)


def calculate_person_roi_overlap(person_bbox: Tuple[int, int, int, int],
                                  roi_bbox: Tuple[int, int, int, int]) -> float:
    """Calculate intersection-over-person-area overlap.

    Better than IoU for shelf ROI because the ROI is typically much larger
    than a person bounding box, so IoU would always be small.

    Returns:
        Fraction of person_bbox area that overlaps with roi_bbox. Value in [0.0, 1.0].
    """
    px1, py1, px2, py2 = person_bbox
    rx1, ry1, rx2, ry2 = roi_bbox

    ix1 = max(px1, rx1)
    iy1 = max(py1, ry1)
    ix2 = min(px2, rx2)
    iy2 = min(py2, ry2)

    if ix2 <= ix1 or iy2 <= iy1:
        return 0.0

    intersection = (ix2 - ix1) * (iy2 - iy1)
    person_area = max(1, (px2 - px1) * (py2 - py1))

    return intersection / person_area


def is_person_in_shelf_roi(bbox: Tuple[int, int, int, int],
                            threshold: Optional[float] = None) -> bool:
    """Check if a person's bounding box overlaps sufficiently with shelf ROI."""
    if threshold is None:
        threshold = config.SHELF_ROI_OVERLAP_THRESHOLD
    roi = get_shelf_roi()
    overlap = calculate_person_roi_overlap(bbox, roi)
    return overlap >= threshold


def is_person_near_shelf(bbox: Tuple[int, int, int, int], margin: int = 80) -> bool:
    """Check if a person's bounding box is within proximity margin of shelf ROI."""
    px1, py1, px2, py2 = bbox
    rx1, ry1, rx2, ry2 = get_shelf_roi()

    expanded_roi = (rx1 - margin, ry1 - margin, rx2 + margin, ry2 + margin)
    ix1 = max(px1, expanded_roi[0])
    iy1 = max(py1, expanded_roi[1])
    ix2 = min(px2, expanded_roi[2])
    iy2 = min(py2, expanded_roi[3])

    return (ix2 > ix1) and (iy2 > iy1)


def get_shelf_zones() -> list:
    """Return single shelf zone matrix with zone identifier and product metadata."""
    rx1, ry1, rx2, ry2 = get_shelf_roi()

    meta = {
        "product_id": "PRODUCT_001",
        "name": "Apple (Fuji)",
        "price": "$2.50",
        "price_val": 2.50,
        "coco": ["apple", "banana", "bottle", "cup", "sandwich", "bread"],
    }

    try:
        from database.models import Product
        from database.connection import app_context_guard
        with app_context_guard():
            p = Product.query.filter_by(product_id=meta["product_id"], active=True).first()
            if not p:
                p = Product.query.filter_by(active=True).first()
            if p:
                meta["name"] = p.name
                meta["price"] = f"${float(p.price):.2f}"
                meta["price_val"] = float(p.price)
                meta["product_id"] = p.product_id
    except Exception:
        pass

    return [{
        "zone_idx": 0,
        "zone_letter": "A",
        "zone_id": "Zone A",
        "bbox": (rx1, ry1, rx2, ry2),
        "product_id": meta["product_id"],
        "name": meta["name"],
        "price": meta["price"],
        "price_val": meta["price_val"],
        "coco": meta["coco"],
    }]


def is_person_in_exit_roi(bbox: Tuple[int, int, int, int],
                           threshold: float = 0.30) -> bool:
    """Check if a person's bounding box overlaps sufficiently with exit ROI."""
    roi = get_exit_roi()
    overlap = calculate_person_roi_overlap(bbox, roi)
    return overlap >= threshold

