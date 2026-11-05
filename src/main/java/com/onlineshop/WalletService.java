package com.onlineshop;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.*;

@Service
public class WalletService {
    private static final BigDecimal MAX = new BigDecimal("99999999.99");
    private final JdbcTemplate db;
    private final SecureRandom random = new SecureRandom();
    public WalletService(JdbcTemplate db) { this.db = db; }

    public static BigDecimal money(String raw, boolean signed) {
        try {
            if (raw == null || !raw.matches(signed ? "-?[0-9]{1,8}(\\.[0-9]{1,2})?" : "[0-9]{1,8}(\\.[0-9]{1,2})?")) throw new IllegalArgumentException();
            BigDecimal value = new BigDecimal(raw).setScale(2, RoundingMode.UNNECESSARY);
            if (value.signum()==0 || (!signed && value.signum()<0) || value.abs().compareTo(MAX)>0) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException | ArithmeticException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请输入有效金额，最多两位小数且不能为零"); }
    }
    private String key(String prefix, long actor, String raw) {
        if (raw == null || !raw.matches("[a-fA-F0-9-]{36}")) fail(HttpStatus.BAD_REQUEST,"请求标识无效，请刷新后重试");
        return prefix+":"+actor+":"+raw;
    }
    private String remark(String raw) {
        if (raw==null || raw.isBlank() || raw.trim().length()>500) fail(HttpStatus.BAD_REQUEST,"请填写调账原因，最多 500 个字符");
        return raw.trim();
    }
    private Map<String,Object> lockUser(long id) {
        var users = db.queryForList("select id,balance,status from dbo.users with (updlock,rowlock) where id=?",id);
        if (users.isEmpty()) fail(HttpStatus.NOT_FOUND,"用户不存在");
        if (!"ACTIVE".equals(users.getFirst().get("status"))) fail(HttpStatus.CONFLICT,"该用户已被禁用");
        return users.getFirst();
    }
    private BigDecimal balance(long id) { return db.queryForObject("select balance from dbo.users where id=?",BigDecimal.class,id); }
    private void record(long userId, String type, BigDecimal amount, String order, String note, Long actor, String request) {
        db.update("insert into dbo.wallet_transactions(user_id,type,amount,balance_after,related_order_no,remark,actor_id,request_key) values(?,?,?,?,?,?,?,?)",
                userId,type,amount,balance(userId),order,note,actor,request);
    }
    public Map<String,Object> summary(long userId) { return Map.of("balance",balance(userId)); }
    public List<Map<String,Object>> transactions(long userId, long before) {
        return db.queryForList("select top (30) id,type,amount,balance_after,related_order_no,remark,CONVERT(varchar(23),created_at,126)+'Z' as created_at from dbo.wallet_transactions where user_id=? and id<? order by id desc", userId,before);
    }

    /** Caller converts a failed result into HTTP error AFTER this transaction commits the attempt counter. */
    @Transactional
    public Map<String,Object> redeem(long userId, String raw) {
        lockUser(userId); // Serializes attempts and all balance mutations for this user, including across JVMs.
        var limit = db.queryForList("select failures,blocked_until,case when blocked_until>SYSUTCDATETIME() then 1 else 0 end as blocked from dbo.wallet_redeem_limits where user_id=?",userId);
        if (limit.isEmpty()) db.update("insert into dbo.wallet_redeem_limits(user_id) values(?)",userId);
        else if (((Number)limit.getFirst().get("blocked")).intValue()==1)
            return Map.of("error","连续输错 5 次，请等待 1 分钟后重试","status",429);
        else if (limit.getFirst().get("blocked_until")!=null)
            db.update("update dbo.wallet_redeem_limits set failures=0,blocked_until=null where user_id=?",userId);
        String code = raw==null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        var codes = code.matches("NV(?:-[A-F0-9]{8}){4}") ? db.queryForList(
                "select id,amount,status,used_by from dbo.recharge_codes with (updlock,holdlock,rowlock) where code=?",code) : List.<Map<String,Object>>of();
        if (codes.isEmpty() || ((Number)codes.getFirst().get("status")).intValue()!=0) {
            db.update("update dbo.wallet_redeem_limits set failures=failures+1,blocked_until=case when failures>=4 then DATEADD(second,60,SYSUTCDATETIME()) else null end where user_id=?",userId);
            int failures = db.queryForObject("select failures from dbo.wallet_redeem_limits where user_id=?",Integer.class,userId);
            return Map.of("error",failures>=5 ? "连续输错 5 次，请等待 1 分钟后重试" : "充值码无效或已使用，请检查后重试（还可尝试 "+(5-failures)+" 次）","status",failures>=5?429:400);
        }
        var entry=codes.getFirst();
        BigDecimal amount=(BigDecimal)entry.get("amount");
        if (db.update("update dbo.users set balance=balance+?,updated_at=SYSUTCDATETIME() where id=? and balance<=?",amount,userId,MAX.subtract(amount))!=1)
            fail(HttpStatus.CONFLICT,"余额达到上限，充值码尚未使用，请联系主理人");
        if (db.update("update dbo.recharge_codes set status=1,used_by=?,used_at=SYSUTCDATETIME() where id=? and status=0",userId,entry.get("id"))!=1)
            fail(HttpStatus.CONFLICT,"充值码已被使用");
        record(userId,"RECHARGE",amount,null,"储值码充值",userId,"REDEEM:"+entry.get("id"));
        db.update("update dbo.wallet_redeem_limits set failures=0,blocked_until=null where user_id=?",userId);
        return Map.of("message","兑换成功，余额已到账","balance",balance(userId),"amount",amount);
    }

    @Transactional
    public Map<String,Object> pay(long orderId, long userId) {
        var rows=db.queryForList("select id,total_amount,status,payment_status from dbo.orders with (updlock,rowlock) where id=? and user_id=?",orderId,userId);
        if (rows.isEmpty()) fail(HttpStatus.NOT_FOUND,"订单不存在");
        var order=rows.getFirst();
        // Same order retry is harmless, including if the first response was lost.
        if ("PAID".equals(order.get("payment_status")) && db.queryForObject("select count(*) from dbo.payments where order_id=? and payment_method='WALLET' and payment_status='SUCCESS'",Integer.class,orderId)==1)
            return Map.of("message","该订单已使用钱包支付，请勿重复付款","balance",balance(userId));
        if (!"PENDING_PAYMENT".equals(order.get("status")) || !"UNPAID".equals(order.get("payment_status"))) fail(HttpStatus.CONFLICT,"该订单当前状态不能付款");
        // Saved unit prices are server-created order snapshots; never use client-submitted totals.
        BigDecimal amount=db.queryForObject("select COALESCE(SUM(unit_price*quantity),0) from dbo.order_items where order_id=?",BigDecimal.class,orderId);
        if (amount.compareTo((BigDecimal)order.get("total_amount"))!=0 || amount.signum()<0 || amount.compareTo(MAX)>0)
            fail(HttpStatus.CONFLICT,"订单金额校验失败，请联系主理人");

        var items=db.queryForList("select oi.product_id,oi.product_name,oi.quantity,p.delivery_type from dbo.order_items oi join dbo.products p on p.id=oi.product_id where oi.order_id=? order by oi.product_id",orderId);
        if(items.isEmpty()) fail(HttpStatus.CONFLICT,"订单没有商品，无法付款");
        boolean hasAuto=false, allAuto=true;
        for(var item:items) {
            boolean auto="AUTO_CARD".equals(item.get("delivery_type"));
            hasAuto|=auto;
            allAuto&=auto;
        }
        Map<Long,List<Map<String,Object>>> allocated=new LinkedHashMap<>();
        for(var item:items) {
            if(!"AUTO_CARD".equals(item.get("delivery_type"))) continue;
            long productId=((Number)item.get("product_id")).longValue();
            db.queryForList("select id from dbo.products with (updlock,rowlock) where id=?",productId);
            int pendingReserved=db.queryForObject("select coalesce(sum(oi.quantity),0) from dbo.order_items oi join dbo.orders o on o.id=oi.order_id where oi.product_id=? and o.status='PENDING_PAYMENT'",Integer.class,productId);
            int available=db.queryForObject("select count(*) from dbo.card_keys with (updlock,holdlock,rowlock) where product_id=? and status=0",Integer.class,productId);
            int needed=((Number)item.get("quantity")).intValue();
            if(available<pendingReserved || available<needed)
                fail(HttpStatus.CONFLICT,"手慢无！该商品的卡密库存不足，请减少购买数量或联系店长补货");
            var keys=db.queryForList("select top (?) id,card_content from dbo.card_keys with (updlock,holdlock,rowlock) where product_id=? and status=0 order by id asc",needed,productId);
            if(keys.size()!=needed) fail(HttpStatus.CONFLICT,"手慢无！该商品的卡密库存不足，请减少购买数量或联系店长补货");
            allocated.put(productId,keys);
        }
        String nextStatus=allAuto?"SHIPPED":"PROCESSING";
        if (db.update("update dbo.orders set status=?,payment_status='PAID',delivery_type=?,shipped_at=case when ?='SHIPPED' then SYSUTCDATETIME() else shipped_at end,updated_at=SYSUTCDATETIME() where id=? and status='PENDING_PAYMENT' and payment_deadline>SYSUTCDATETIME()",nextStatus,hasAuto?"VIRTUAL":null,nextStatus,orderId)!=1)
            fail(HttpStatus.CONFLICT,"付款时间已过，请刷新订单");
        if (db.update("update dbo.users set balance=balance-?,updated_at=SYSUTCDATETIME() where id=? and status='ACTIVE' and balance>=?",amount,userId,amount)!=1)
            fail(HttpStatus.CONFLICT,"钱包余额不足，请先充值");
        record(userId,"CONSUME",amount.negate(),String.valueOf(orderId),"订单 #"+orderId+" 钱包支付",userId,null);
        db.update("insert into dbo.payments(order_id,payment_method,payment_amount,paid_at) values(?,'WALLET',?,SYSUTCDATETIME())",orderId,amount);
        StringBuilder delivered=new StringBuilder();
        for(var item:items) {
            long productId=((Number)item.get("product_id")).longValue();
            List<Map<String,Object>> keys=allocated.get(productId);
            if(keys==null) continue;
            delivered.append("【").append(item.get("product_name")).append("】\n");
            for(var key:keys) {
                long keyId=((Number)key.get("id")).longValue();
                if(db.update("update dbo.card_keys set status=1,order_no=?,buyer_id=?,sold_at=SYSUTCDATETIME() where id=? and status=0",orderId,userId,keyId)!=1)
                    fail(HttpStatus.CONFLICT,"卡密已被分配，请刷新订单后重试");
                delivered.append(String.valueOf(key.get("card_content"))).append("\n");
            }
            delivered.append("\n");
        }
        if(delivered.length()>0) db.update("update dbo.orders set delivered_cards=? where id=?",delivered.toString().trim(),orderId);
        for(Long productId:allocated.keySet()) {
            db.update("update dbo.products set stock=case when (select count(*) from dbo.card_keys where product_id=? and status=0)-"+
                    "(select coalesce(sum(oi.quantity),0) from dbo.order_items oi join dbo.orders o on o.id=oi.order_id where oi.product_id=? and o.status='PENDING_PAYMENT') < 0 then 0 else "+
                    "(select count(*) from dbo.card_keys where product_id=? and status=0)-"+
                    "(select coalesce(sum(oi.quantity),0) from dbo.order_items oi join dbo.orders o on o.id=oi.order_id where oi.product_id=? and o.status='PENDING_PAYMENT') end,updated_at=SYSUTCDATETIME() where id=?",
                    productId,productId,productId,productId,productId);
        }
        return Map.of("message",allAuto?"付款成功，卡密已自动发货":hasAuto?"付款成功，卡密已到账，其他商品等待商家发货":"付款成功，等待商家发货","balance",balance(userId));
    }

    @Transactional
    public Map<String,Object> adjust(long actor, String username, String rawAmount, String note, String requestId) {
        BigDecimal amount=money(rawAmount,true);
        String reason=remark(note), request=key("ADJUST",actor,requestId);
        var users=db.queryForList("select id from dbo.users where username=?",username==null?"":username.trim());
        if (users.isEmpty()) fail(HttpStatus.NOT_FOUND,"用户不存在");
        long id=((Number)users.getFirst().get("id")).longValue();
        lockUser(id);
        var previous=db.queryForList("select user_id,amount,remark from dbo.wallet_transactions where request_key=?",request);
        if (!previous.isEmpty()) {
            var p=previous.getFirst();
            if (((Number)p.get("user_id")).longValue()!=id || amount.compareTo((BigDecimal)p.get("amount"))!=0 || !reason.equals(p.get("remark"))) fail(HttpStatus.CONFLICT,"请为新的调账操作使用新的请求标识");
            return Map.of("message","该调账已完成，未重复入账","balance",balance(id));
        }
        if (db.update("update dbo.users set balance=balance+?,updated_at=SYSUTCDATETIME() where id=? and balance+? between 0 and ?",amount,id,amount,MAX)!=1)
            fail(HttpStatus.CONFLICT,"用户余额不足以扣减，或调整后超过余额上限");
        record(id,"ADMIN_ADJUST",amount,null,reason,actor,request);
        return Map.of("message","调账成功，已记录操作人和资金流水","balance",balance(id));
    }

    @Transactional
    public Map<String,Object> generate(long actor, String rawAmount, int count, String requestId) {
        BigDecimal amount=money(rawAmount,false);
        if (count<1 || count>100) fail(HttpStatus.BAD_REQUEST,"每批只能生成 1-100 张充值码");
        String request=key("BATCH",actor,requestId);
        // Admin row lock also serializes duplicate batch requests before checking the unique key.
        lockUser(actor);
        var batches=db.queryForList("select amount,quantity from dbo.recharge_batches where request_key=?",request);
        if (!batches.isEmpty()) {
            if (amount.compareTo((BigDecimal)batches.getFirst().get("amount"))!=0 || ((Number)batches.getFirst().get("quantity")).intValue()!=count)
                fail(HttpStatus.CONFLICT,"该请求标识已经生成过其他面额或数量的充值码");
        } else {
            db.update("insert into dbo.recharge_batches(request_key,created_by,amount,quantity) values(?,?,?,?)",request,actor,amount,count);
            for(int i=0;i<count;i++) {
                byte[] bytes=new byte[16]; random.nextBytes(bytes);
                String hex=HexFormat.of().withUpperCase().formatHex(bytes);
                String code="NV-"+hex.substring(0,8)+"-"+hex.substring(8,16)+"-"+hex.substring(16,24)+"-"+hex.substring(24);
                db.update("insert into dbo.recharge_codes(code,amount,batch_key) values(?,?,?)",code,amount,request);
            }
        }
        return Map.of("message","充值码已生成，请妥善保管","codes",db.queryForList("select id,code,amount,status from dbo.recharge_codes where batch_key=? order by id",request));
    }

    @Transactional
    public Map<String,Object> refund(long actor, long orderId, String note) {
        String reason=remark(note);
        var rows=db.queryForList("select user_id,status,payment_status,delivered_cards from dbo.orders with (updlock,rowlock) where id=?",orderId);
        if(rows.isEmpty()) fail(HttpStatus.NOT_FOUND,"订单不存在");
        var order=rows.getFirst(); long userId=((Number)order.get("user_id")).longValue();
        if("REFUNDED".equals(order.get("payment_status"))) return Map.of("message","该订单已退款，未重复入账");
        if(!"PROCESSING".equals(order.get("status")) || !"PAID".equals(order.get("payment_status"))) fail(HttpStatus.CONFLICT,"仅支持未发货的钱包订单全额退款");
        if(order.get("delivered_cards")!=null) fail(HttpStatus.CONFLICT,"订单已自动发出卡密，不能直接退款，请先核实卡密使用情况");
        var payments=db.queryForList("select payment_amount from dbo.payments where order_id=? and payment_method='WALLET' and payment_status='SUCCESS'",orderId);
        if(payments.isEmpty()) fail(HttpStatus.CONFLICT,"该订单不是钱包付款，无法退回钱包");
        BigDecimal amount=(BigDecimal)payments.getFirst().get("payment_amount");
        if(amount.signum()<=0) fail(HttpStatus.CONFLICT,"零金额订单无需退款");
        if(db.update("update dbo.users set balance=balance+?,updated_at=SYSUTCDATETIME() where id=? and balance<=?",amount,userId,MAX.subtract(amount))!=1) fail(HttpStatus.CONFLICT,"余额达到上限，暂时无法退款");
        record(userId,"REFUND",amount,String.valueOf(orderId),reason,actor,null);
        db.update("update dbo.orders set status='CANCELLED',payment_status='REFUNDED',updated_at=SYSUTCDATETIME() where id=?",orderId);
        db.update("update dbo.payments set payment_status='REFUNDED' where order_id=?",orderId);
        db.update("update p set stock=p.stock+oi.quantity from dbo.products p join (select product_id,sum(quantity) quantity from dbo.order_items where order_id=? group by product_id) oi on oi.product_id=p.id",orderId);
        return Map.of("message","已全额退回用户钱包，订单已取消");
    }
    private static void fail(HttpStatus status,String message) { throw new ResponseStatusException(status,message); }
}
