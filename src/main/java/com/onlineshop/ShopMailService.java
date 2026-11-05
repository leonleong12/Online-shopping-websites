package com.onlineshop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Centralized outbound mail. SMTP credentials are read from environment-backed Spring properties. */
@Service
public class ShopMailService {
    private static final Logger log = LoggerFactory.getLogger(ShopMailService.class);
    private final JavaMailSender sender;
    private final String username;
    private final String from;
    private final String fromName;
    private final String publicUrl;

    public ShopMailService(ObjectProvider<JavaMailSender> sender,
                           @org.springframework.beans.factory.annotation.Value("${spring.mail.username:}") String username,
                           @org.springframework.beans.factory.annotation.Value("${MAIL_FROM:${spring.mail.username:}}") String from,
                           @org.springframework.beans.factory.annotation.Value("${MAIL_FROM_NAME:NovaMart 客服}") String fromName,
                           @org.springframework.beans.factory.annotation.Value("${SHOP_PUBLIC_URL:http://localhost:8081}") String publicUrl) {
        this.sender = sender.getIfAvailable();
        this.username = username == null ? "" : username.trim();
        this.from = from == null || from.isBlank() ? this.username : from.trim();
        this.fromName = fromName == null || fromName.isBlank() ? "NovaMart 客服" : fromName.trim();
        this.publicUrl = publicUrl == null ? "http://localhost:8081" : publicUrl.replaceAll("/+$", "");
    }

    public boolean isConfigured() { return sender != null && !username.isBlank(); }

    public void sendReset(String recipient, String username, String token) {
        if (!isConfigured()) throw new IllegalStateException("邮件服务尚未配置");
        String link = publicUrl + "/?resetToken=" + token;
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from, fromName);
            helper.setTo(recipient);
            helper.setSubject("NovaMart 密码重设链接");
            helper.setText("您好，" + username + "：\n\n" +
                    "我们收到了重设 NovaMart 密码的请求。请在 30 分钟内打开以下链接设置新密码：\n" + link +
                    "\n\n如果不是您本人操作，请忽略此邮件。出于安全原因，我们不会在邮件中显示原密码。\n\nNovaMart");
            sender.send(message);
        } catch (Exception ex) {
            throw new IllegalStateException("邮件发送失败", ex);
        }
    }

    /** Sends after a successful shipment. Mail errors are intentionally swallowed by the caller. */
    public void sendShipment(Map<String, Object> order, List<Map<String, Object>> items) {
        if (!isConfigured()) {
            log.warn("Shipment mail skipped: SMTP is not configured");
            return;
        }
        Object recipient = order.get("email");
        if (recipient == null || String.valueOf(recipient).isBlank()) {
            log.warn("Shipment mail skipped: order {} has no recipient email", order.get("id"));
            return;
        }
        StringBuilder body = new StringBuilder("您好，").append(order.getOrDefault("username", "顾客")).append("：\n\n")
                .append("您的 NovaMart 订单已发货。\n\n订单编号：#").append(order.get("id"))
                .append("\n支付金额：￥").append(order.getOrDefault("total_amount", BigDecimal.ZERO)).append("\n\n购买清单：\n");
        for (Map<String, Object> item : items) {
            body.append("- ").append(item.get("product_name"))
                    .append(" × ").append(item.get("quantity"))
                    .append("，￥").append(item.get("subtotal")).append("\n");
        }
        String type = String.valueOf(order.getOrDefault("delivery_type", "PHYSICAL"));
        if ("VIRTUAL".equals(type)) body.append("\n虚拟卡密：\n").append(order.getOrDefault("virtual_code", ""));
        else body.append("\n快递单号：").append(order.getOrDefault("tracking_number", "待更新"));
        body.append("\n\n您可以登录 NovaMart 查看订单进度。\n\nNovaMart");
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from, fromName);
            helper.setTo(String.valueOf(recipient));
            helper.setSubject("NovaMart 订单 #" + order.get("id") + " 已发货");
            helper.setText(body.toString());
            sender.send(message);
            log.info("Shipment mail sent for order {}", order.get("id"));
        } catch (Exception ex) {
            log.error("Shipment mail failed for order {}", order.get("id"), ex);
        }
    }

}
