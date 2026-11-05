package com.onlineshop;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

@Service
public class OrderFulfillmentService {
    private final JdbcTemplate db;
    private final ShopMailService mail;

    @Autowired
    public OrderFulfillmentService(JdbcTemplate db, ShopMailService mail) { this.db = db; this.mail = mail; }
    /** Kept for focused unit tests that exercise state transitions without mail. */
    public OrderFulfillmentService(JdbcTemplate db) { this(db, null); }

    @Transactional
    public void ship(long orderId, Map<String, String> body) {
        String type = value(body, "deliveryType");
        String tracking = value(body, "trackingNumber");
        String code = value(body, "virtualCode");
        if (!"PHYSICAL".equals(type) && !"VIRTUAL".equals(type)) fail(HttpStatus.BAD_REQUEST, "请选择快递发货或虚拟卡密");
        if ("PHYSICAL".equals(type) && (tracking.isEmpty() || tracking.length() > 100 || !tracking.matches("[A-Za-z0-9-]+"))) {
            fail(HttpStatus.BAD_REQUEST, "请输入快递单号，支持英文字母、数字和短横线，最多 100 位");
        }
        if ("VIRTUAL".equals(type) && (code.isEmpty() || code.length() > 2000)) {
            fail(HttpStatus.BAD_REQUEST, "请输入虚拟卡密，最多 2000 个字符");
        }
        var rows = db.queryForList("select status,payment_status from dbo.orders with (updlock,rowlock) where id=?", orderId);
        if (rows.isEmpty()) fail(HttpStatus.NOT_FOUND, "订单不存在");
        var order = rows.get(0);
        if (!"PROCESSING".equals(order.get("status")) || !"PAID".equals(order.get("payment_status"))) {
            fail(HttpStatus.CONFLICT, "只有已付款且处理中的订单可以发货，请刷新订单状态");
        }
        int changed = db.update("update dbo.orders set status='SHIPPED',delivery_type=?,tracking_number=?,virtual_code=?," +
                        "shipped_at=SYSUTCDATETIME(),updated_at=SYSUTCDATETIME() where id=? and status='PROCESSING' and payment_status='PAID'",
                type, "PHYSICAL".equals(type) ? tracking : null, "VIRTUAL".equals(type) ? code : null, orderId);
        if (changed != 1) fail(HttpStatus.CONFLICT, "订单状态已变化，请刷新后重试");
        Runnable notify = () -> {
            if (mail == null) return;
            try {
                var orderRows = db.queryForList("select o.id,o.total_amount,o.delivery_type,o.tracking_number,o.virtual_code,u.username,u.email " +
                        "from dbo.orders o join dbo.users u on u.id=o.user_id where o.id=?", orderId);
                if (!orderRows.isEmpty()) {
                    var items = db.queryForList("select product_name,quantity,subtotal from dbo.order_items where order_id=? order by id", orderId);
                    mail.sendShipment(orderRows.getFirst(), items);
                }
            } catch (RuntimeException ex) {
                // Delivery is already committed; a mail outage must not undo it.
                org.slf4j.LoggerFactory.getLogger(OrderFulfillmentService.class).error("Unable to prepare shipment mail for order {}", orderId, ex);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { notify.run(); }
            });
        } else notify.run();
    }

    @Transactional
    public void confirmReceipt(long orderId, long userId) {
        var rows = db.queryForList("select status from dbo.orders with (updlock,rowlock) where id=? and user_id=?", orderId, userId);
        if (rows.isEmpty()) fail(HttpStatus.NOT_FOUND, "订单不存在");
        if (!"SHIPPED".equals(rows.get(0).get("status"))) fail(HttpStatus.CONFLICT, "只有已发货的订单可以确认收货");
        int changed = db.update("update dbo.orders set status='COMPLETED',completed_at=SYSUTCDATETIME(),updated_at=SYSUTCDATETIME() " +
                "where id=? and user_id=? and status='SHIPPED'", orderId, userId);
        if (changed != 1) fail(HttpStatus.CONFLICT, "订单状态已变化，请刷新后重试");
    }

    private String value(Map<String, String> body, String key) {
        String value = body.get(key);
        return value == null ? "" : value.trim();
    }

    private void fail(HttpStatus status, String message) { throw new ResponseStatusException(status, message); }
}
