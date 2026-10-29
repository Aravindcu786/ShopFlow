package com.shopflow.app.models;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests verifying null-safety and parsing logic of all ShopFlow data models.
 */
public class ShoppingModelsTest {

    @Test
    public void testCartItemParsing() throws Exception {
        JSONObject json = new JSONObject();
        json.put("product_id", "PROD_001");
        json.put("name", "Whole Milk 1L");
        json.put("quantity", 2);
        json.put("unit_price", "65.00");
        json.put("subtotal", "130.00");
        json.put("last_sensor_id", "SENSOR_A1");

        CartItem item = CartItem.fromJson(json);
        assertNotNull(item);
        assertEquals("PROD_001", item.getProductId());
        assertEquals("Whole Milk 1L", item.getName());
        assertEquals(2, item.getQuantity());
        assertEquals(65.0, item.getUnitPrice(), 0.001);
        assertEquals(130.0, item.getSubtotal(), 0.001);
        assertEquals("SENSOR_A1", item.getLastSensorId());
    }

    @Test
    public void testCartItemNullSafety() {
        CartItem item = CartItem.fromJson(null);
        assertNotNull(item);
        assertEquals("", item.getProductId());
        assertEquals("", item.getName());
        assertEquals(0, item.getQuantity());
        assertEquals(0.0, item.getUnitPrice(), 0.001);
    }

    @Test
    public void testShoppingCartParsing() throws Exception {
        JSONObject cartJson = new JSONObject();
        cartJson.put("session_id", "SESS-12345678");
        cartJson.put("customer_id", "CUST000001");
        cartJson.put("item_count", 3);
        cartJson.put("subtotal", "240.00");
        cartJson.put("total", "259.20");
        cartJson.put("tax", "19.20");
        cartJson.put("tax_rate", "0.08");

        JSONArray itemsArr = new JSONArray();
        JSONObject item1 = new JSONObject();
        item1.put("product_id", "P1");
        item1.put("name", "Item 1");
        item1.put("quantity", 1);
        item1.put("unit_price", "100.00");
        item1.put("subtotal", "100.00");
        itemsArr.put(item1);

        JSONObject item2 = new JSONObject();
        item2.put("product_id", "P2");
        item2.put("name", "Item 2");
        item2.put("quantity", 2);
        item2.put("unit_price", "70.00");
        item2.put("subtotal", "140.00");
        itemsArr.put(item2);

        cartJson.put("items", itemsArr);

        ShoppingCart cart = ShoppingCart.fromJson(cartJson);
        assertNotNull(cart);
        assertFalse(cart.isEmpty());
        assertEquals("SESS-12345678", cart.getSessionId());
        assertEquals("CUST000001", cart.getCustomerId());
        assertEquals(3, cart.getItemCount());
        assertEquals(240.0, cart.getSubtotal(), 0.001);
        assertEquals(259.20, cart.getTotal(), 0.001);
        assertEquals(2, cart.getItems().size());
    }

    @Test
    public void testCustomerSessionActiveState() throws Exception {
        JSONObject sessionJson = new JSONObject();
        sessionJson.put("session_id", "SESS-ACTIVE-01");
        sessionJson.put("customer_id", "CUST000001");
        sessionJson.put("status", "ACTIVE");
        sessionJson.put("payment_status", "PENDING");
        sessionJson.put("exit_allowed", false);
        sessionJson.put("started_at", "2026-09-29T10:00:00");

        CustomerSession session = CustomerSession.fromJson(sessionJson);
        assertNotNull(session);
        assertTrue(session.isActive());
        assertFalse(session.isPaid());
        assertTrue(session.isPendingPayment());
        assertFalse(session.isExitAllowed());
        assertFalse(session.isCompleted());
    }

    @Test
    public void testCustomerSessionPaidState() throws Exception {
        JSONObject sessionJson = new JSONObject();
        sessionJson.put("session_id", "SESS-PAID-01");
        sessionJson.put("customer_id", "CUST000001");
        sessionJson.put("status", "PAID");
        sessionJson.put("payment_status", "PAID");
        sessionJson.put("exit_allowed", true);
        sessionJson.put("started_at", "2026-09-29T10:00:00");

        CustomerSession session = CustomerSession.fromJson(sessionJson);
        assertNotNull(session);
        assertTrue(session.isActive());
        assertTrue(session.isPaid());
        assertFalse(session.isPendingPayment());
        assertTrue(session.isExitAllowed());
    }

    @Test
    public void testCustomerSessionCompletedState() throws Exception {
        JSONObject sessionJson = new JSONObject();
        sessionJson.put("session_id", "SESS-COMPLETED-01");
        sessionJson.put("customer_id", "CUST000001");
        sessionJson.put("status", "COMPLETED");
        sessionJson.put("payment_status", "PAID");
        sessionJson.put("exit_allowed", true);

        CustomerSession session = CustomerSession.fromJson(sessionJson);
        assertNotNull(session);
        assertFalse(session.isActive());
        assertTrue(session.isCompleted());
    }

    @Test
    public void testCustomerSessionNullSafety() {
        CustomerSession session = CustomerSession.fromJson(null);
        assertNull(session);
    }

    @Test
    public void testPaymentResultParsing() throws Exception {
        JSONObject json = new JSONObject();
        json.put("success", true);
        json.put("session_id", "SESS-999");
        json.put("payment_status", "PAID");
        json.put("exit_allowed", true);
        json.put("message", "Payment authorized");

        PaymentResult result = PaymentResult.fromJson(json);
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("SESS-999", result.getSessionId());
        assertEquals("PAID", result.getPaymentStatus());
        assertTrue(result.isExitAllowed());
    }

    @Test
    public void testShoppingHistoryItemParsing() throws Exception {
        JSONObject json = new JSONObject();
        json.put("session_id", "SESS-HIST-01");
        json.put("customer_id", "CUST000001");
        json.put("status", "COMPLETED");
        json.put("payment_status", "PAID");
        json.put("item_count", 5);
        json.put("total", "850.50");
        json.put("started_at", "2026-09-20T14:30:00");
        json.put("completed_at", "2026-09-20T14:45:00");

        ShoppingHistoryItem item = ShoppingHistoryItem.fromJson(json);
        assertNotNull(item);
        assertEquals("SESS-HIST-01", item.getSessionId());
        assertEquals(5, item.getItemCount());
        assertEquals(850.50, item.getTotal(), 0.001);
        assertEquals("COMPLETED", item.getStatus());
        assertTrue(item.getSummaryText().contains("5 items purchased"));
    }
}
