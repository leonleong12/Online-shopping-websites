package com.onlineshop;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrderExpirationService {
    private final JdbcTemplate db;

    public OrderExpirationService(JdbcTemplate db) {
        this.db = db;
    }

    @Scheduled(fixedDelay = 15_000L, initialDelay = 1_000L)
    @Transactional
    public void cancelExpiredOrders() {
        List<Long> expiredIds = db.query(
                "UPDATE dbo.orders WITH (UPDLOCK, ROWLOCK) " +
                        "SET status='CANCELLED',updated_at=SYSUTCDATETIME() " +
                        "OUTPUT inserted.id " +
                        "WHERE status='PENDING_PAYMENT' AND payment_deadline <= SYSUTCDATETIME()",
                (rs, rowNum) -> rs.getLong(1));
        expiredIds.forEach(this::restoreReservedStock);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean cancelIfExpired(long orderId, long userId) {
        List<Long> cancelledIds = db.query(
                "UPDATE dbo.orders WITH (UPDLOCK, ROWLOCK) " +
                        "SET status='CANCELLED',updated_at=SYSUTCDATETIME() " +
                        "OUTPUT inserted.id " +
                        "WHERE id=? AND user_id=? AND status='PENDING_PAYMENT' " +
                        "AND payment_deadline <= SYSUTCDATETIME()",
                (rs, rowNum) -> rs.getLong(1), orderId, userId);
        cancelledIds.forEach(this::restoreReservedStock);
        return !cancelledIds.isEmpty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean cancelPendingOrder(long orderId) {
        List<Long> cancelledIds = db.query(
                "UPDATE dbo.orders WITH (UPDLOCK, ROWLOCK) " +
                        "SET status='CANCELLED',updated_at=SYSUTCDATETIME() " +
                        "OUTPUT inserted.id " +
                        "WHERE id=? AND status='PENDING_PAYMENT'",
                (rs, rowNum) -> rs.getLong(1), orderId);
        cancelledIds.forEach(this::restoreReservedStock);
        return !cancelledIds.isEmpty();
    }

    private void restoreReservedStock(long orderId) {
        db.update("UPDATE p SET p.stock=p.stock+oi.quantity, p.updated_at=SYSUTCDATETIME() " +
                        "FROM dbo.products p " +
                        "JOIN (SELECT product_id,SUM(quantity) AS quantity FROM dbo.order_items " +
                        "WHERE order_id=? GROUP BY product_id) oi ON oi.product_id=p.id",
                orderId);
    }
}
