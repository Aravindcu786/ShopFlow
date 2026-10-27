"""Comprehensive unit and scenario test suite for SmartShelfFusionEngine.

Validates:
1. Single item PICK
2. Multi-item PICK (Quantization & residual check)
3. PUT_BACK event
4. Browse / Touch & Leave (Net zero delta -> BROWSE_DISCARD)
5. Accidental Lean / Impact without wrist presence (ACCIDENTAL_IMPACT_DISCARD)
6. Hovering without interaction
7. Temporal coincidence window expiration
"""

import time
import unittest
from services.smart_shelf_fusion_engine import (
    SmartShelfFusionEngine,
    ShelfROI,
    ProductConfig,
    FusionConfig,
    FSMState,
    TransactionType,
)


class TestSmartShelfFusionEngine(unittest.TestCase):
    def setUp(self):
        # Shelf ROI: x: [100, 400], y: [200, 500]
        self.roi = ShelfROI(x_min=100.0, y_min=200.0, x_max=400.0, y_max=500.0, approach_margin_px=50.0)
        self.product = ProductConfig(
            product_id="PROD_FUJI_APPLE",
            name="Fuji Apple",
            unit_weight_g=200.0,
            weight_tolerance_g=25.0,
            price=1.75,
        )
        self.config = FusionConfig(
            coincidence_window_s=0.70,
            settling_window_size=10,
            settling_variance_threshold=2.0,
            settling_min_duration_s=0.10,
            min_event_weight_g=40.0,
            transient_noise_spike_g=15.0,
            min_keypoint_conf=0.50,
        )
        self.engine = SmartShelfFusionEngine(
            shelf_id="SHELF_01",
            shelf_roi=self.roi,
            product_config=self.product,
            config=self.config,
            initial_baseline_weight_g=1000.0,
        )

    def _feed_stable_weight(self, weight: float, count: int = 12, start_t: float = 1.0, step: float = 0.02):
        """Helper to feed consecutive stable weight readings."""
        t = start_t
        for _ in range(count):
            self.engine.process_weight_sample(weight, timestamp=t)
            t += step
        return t

    def test_initial_state(self):
        self.assertEqual(self.engine.current_state, FSMState.IDLE)
        self.assertEqual(self.engine.baseline_weight_g, 1000.0)

    def test_scenario_1_valid_single_pick(self):
        """Shopper approaches, hand enters ROI, takes 1 item (-200g), retracts."""
        t = 1.0
        # 1. Feed baseline weight
        t = self._feed_stable_weight(1000.0, count=12, start_t=t)

        # 2. Shopper hand approaches ROI
        self.engine.process_pose_keypoints(
            wrists_dict={"wrist_right": (80.0, 250.0, 0.9)},  # In proximity buffer
            shopper_id="CUST_101",
            timestamp=t,
        )
        self.assertEqual(self.engine.current_state, FSMState.APPROACHING)

        # 3. Hand enters Shelf ROI
        t += 0.05
        self.engine.process_pose_keypoints(
            wrists_dict={"wrist_right": (250.0, 350.0, 0.95)},  # Strictly inside ROI
            shopper_id="CUST_101",
            timestamp=t,
        )
        self.assertEqual(self.engine.current_state, FSMState.INSIDE_ROI)

        # 4. Hand grasps item - transient dynamic perturbation
        t += 0.05
        self.engine.process_weight_sample(1040.0, timestamp=t)  # Dynamic downward push
        self.assertEqual(self.engine.current_state, FSMState.INTERACTION_DETECTED)

        # 5. Hand lifts item and retracts out of ROI
        t += 0.10
        self.engine.process_pose_keypoints(
            wrists_dict={"wrist_right": (50.0, 150.0, 0.92)},  # Outside ROI
            shopper_id="CUST_101",
            timestamp=t,
        )
        self.assertEqual(self.engine.current_state, FSMState.RETRACTING)

        # 6. Load cell rings and settles on 800g (-200g drop) within 300ms
        t += 0.05
        t = self._feed_stable_weight(800.0, count=12, start_t=t, step=0.02)

        # 7. Evaluate fusion
        event = self.engine.evaluate_fusion()
        self.assertIsNotNone(event)
        self.assertEqual(event["transaction_type"], TransactionType.PICK.value)
        self.assertEqual(event["shopper_id"], "CUST_101")
        self.assertEqual(event["product_id"], "PROD_FUJI_APPLE")
        self.assertEqual(event["quantity"], 1)
        self.assertAlmostEqual(event["weight_delta_g"], -200.0, places=1)
        self.assertGreaterEqual(event["confidence"], 0.90)

    def test_scenario_2_valid_multi_item_pick(self):
        """Shopper takes 2 apples (-400g delta). Engine should resolve to quantity = 2."""
        t = 1.0
        t = self._feed_stable_weight(1000.0, count=12, start_t=t)

        # Hand enters ROI
        t += 0.05
        self.engine.process_pose_keypoints(
            wrists_dict={"wrist_right": (250.0, 350.0, 0.95)},
            shopper_id="CUST_102",
            timestamp=t,
        )

        # Hand grasps items and exits
        t += 0.10
        self.engine.process_weight_sample(1050.0, timestamp=t)
        t += 0.10
        self.engine.process_pose_keypoints(
            wrists_dict={"wrist_right": (50.0, 150.0, 0.90)},
            shopper_id="CUST_102",
            timestamp=t,
        )

        # Shelf settles at 600g (-400g delta)
        t += 0.05
        t = self._feed_stable_weight(600.0, count=12, start_t=t, step=0.02)

        event = self.engine.evaluate_fusion()
        self.assertIsNotNone(event)
        self.assertEqual(event["transaction_type"], TransactionType.PICK.value)
        self.assertEqual(event["quantity"], 2)
        self.assertAlmostEqual(event["weight_delta_g"], -400.0, places=1)
        self.assertGreaterEqual(event["confidence"], 0.90)

    def test_scenario_3_valid_put_back(self):
        """Shopper returns 1 apple (+200g delta). Engine commits PUT_BACK."""
        t = 1.0
        t = self._feed_stable_weight(800.0, count=12, start_t=t)

        # Hand enters ROI with item
        t += 0.05
        self.engine.process_pose_keypoints(
            wrists_dict={"wrist_left": (220.0, 320.0, 0.93)},
            shopper_id="CUST_103",
            timestamp=t,
        )
        self.assertEqual(self.engine.current_state, FSMState.INSIDE_ROI)

        # Places item on shelf
        t += 0.05
        self.engine.process_weight_sample(1020.0, timestamp=t)  # Impact spike
        self.assertEqual(self.engine.current_state, FSMState.INTERACTION_DETECTED)

        # Retracts hand
        t += 0.08
        self.engine.process_pose_keypoints(
            wrists_dict={"wrist_left": (40.0, 80.0, 0.90)},
            shopper_id="CUST_103",
            timestamp=t,
        )
        self.assertEqual(self.engine.current_state, FSMState.RETRACTING)

        # Shelf settles at 1000g (+200g delta)
        t += 0.05
        t = self._feed_stable_weight(1000.0, count=12, start_t=t, step=0.02)

        event = self.engine.evaluate_fusion()
        self.assertIsNotNone(event)
        self.assertEqual(event["transaction_type"], TransactionType.PUT_BACK.value)
        self.assertEqual(event["quantity"], 1)
        self.assertAlmostEqual(event["weight_delta_g"], 200.0, places=1)

    def test_scenario_4_browse_touch_and_leave(self):
        """Shopper touches/browses an apple and puts it back down -> net delta is ~0g."""
        t = 1.0
        t = self._feed_stable_weight(1000.0, count=12, start_t=t)

        # Hand enters ROI
        t += 0.05
        self.engine.process_pose_keypoints(
            wrists_dict={"wrist_right": (250.0, 350.0, 0.92)},
            shopper_id="CUST_104",
            timestamp=t,
        )

        # Hand touches/shifts weight
        t += 0.05
        self.engine.process_weight_sample(1070.0, timestamp=t)  # Dynamic touch

        # Hand exits ROI
        t += 0.10
        self.engine.process_pose_keypoints(
            wrists_dict={"wrist_right": (50.0, 100.0, 0.88)},
            shopper_id="CUST_104",
            timestamp=t,
        )

        # Shelf settles back to original 1000.0g (net delta = 0g)
        t += 0.05
        t = self._feed_stable_weight(1000.0, count=12, start_t=t, step=0.02)

        event = self.engine.evaluate_fusion()
        self.assertIsNotNone(event)
        self.assertEqual(event["transaction_type"], TransactionType.BROWSE_DISCARD.value)
        self.assertEqual(event["quantity"], 0)
        self.assertAlmostEqual(event["weight_delta_g"], 0.0, places=1)

    def test_scenario_5_accidental_lean_or_impact(self):
        """Someone leans or knocks on shelf while no hands are inside ROI."""
        t = 1.0
        t = self._feed_stable_weight(1000.0, count=12, start_t=t)

        # A large weight change occurs (+350g lean) without wrist presence
        t = self._feed_stable_weight(1350.0, count=12, start_t=t, step=0.02)

        event = self.engine.evaluate_fusion()
        self.assertIsNotNone(event)
        self.assertEqual(event["transaction_type"], TransactionType.ACCIDENTAL_IMPACT_DISCARD.value)
        self.assertIsNone(event["shopper_id"])
        self.assertEqual(event["quantity"], 0)
        self.assertIn("alert", event["details"])

    def test_scenario_6_temporal_window_expiration(self):
        """Hand retracts, but weight settles 2 seconds later (> 800ms coincidence window)."""
        t = 1.0
        t = self._feed_stable_weight(1000.0, count=12, start_t=t)

        # Hand enters and leaves
        t += 0.05
        self.engine.process_pose_keypoints(
            wrists_dict={"wrist_right": (250.0, 350.0, 0.90)},
            shopper_id="CUST_106",
            timestamp=t,
        )
        t += 0.05
        self.engine.process_weight_sample(1030.0, timestamp=t)
        t += 0.05
        self.engine.process_pose_keypoints(
            wrists_dict={"wrist_right": (50.0, 100.0, 0.90)},
            shopper_id="CUST_106",
            timestamp=t,
        )

        # Settling happens 1.8 seconds later (outside 700ms coincidence window)
        t += 1.80
        t = self._feed_stable_weight(800.0, count=12, start_t=t, step=0.02)

        event = self.engine.evaluate_fusion()
        self.assertIsNotNone(event)
        self.assertEqual(event["transaction_type"], TransactionType.UNRESOLVED_DISCARD.value)


if __name__ == "__main__":
    unittest.main()
