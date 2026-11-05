package com.onlineshop;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api")
public class CardKeyController {
    private final JdbcTemplate db;
    private final SessionAuth auth;

    public CardKeyController(JdbcTemplate db, SessionAuth auth) { this.db = db; this.auth = auth; }

    @GetMapping("/admin/products/{productId}/card-keys")
    public Map<String, Object> list(@RequestHeader(value="Authorization", required=false) String header,
                                    @PathVariable long productId, @RequestParam(defaultValue="0") int status) {
        auth.admin(header);
        if (status != 0 && status != 1) throw bad("卡密状态不正确");
        product(productId);
        var rows = db.queryForList("select top (200) k.id,k.card_content,k.status,k.order_no,u.username as buyer_username,CONVERT(varchar(23),k.created_at,126)+'Z' as created_at,CONVERT(varchar(23),k.sold_at,126)+'Z' as sold_at from dbo.card_keys k left join dbo.users u on u.id=k.buyer_id where k.product_id=? and k.status=? order by k.id desc", productId, status);
        return Map.of("items", rows, "summary", summary(productId));
    }

    @PostMapping("/admin/products/{productId}/card-keys")
    @Transactional
    public Map<String, Object> importKeys(@RequestHeader(value="Authorization", required=false) String header,
                                         @PathVariable long productId, @RequestBody Map<String, Object> body) {
        auth.admin(header);
        var product = lockProduct(productId);
        Object raw = body.get("contents");
        if (!(raw instanceof List<?> contents) || contents.isEmpty() || contents.size() > 500)
            throw bad("每次最多导入 500 条卡密");
        boolean filterDuplicates = Boolean.TRUE.equals(body.get("filterDuplicates"));
        Set<String> batch = new LinkedHashSet<>();
        for (Object item : contents) {
            if (item == null) continue;
            String value = item.toString().trim();
            if (value.isEmpty()) continue;
            if (value.length() > 4000) throw bad("单条卡密不能超过 4000 个字符");
            if (!batch.add(value) && !filterDuplicates) throw bad("本批次存在重复卡密，请勾选自动过滤重复项");
        }
        if (batch.isEmpty()) throw bad("请输入至少一条非空卡密");
        int inserted = 0, skipped = 0;
        for (String value : batch) {
            Integer exists = db.queryForObject("select count(*) from dbo.card_keys with (updlock,holdlock) where product_id=? and card_content=?", Integer.class, productId, value);
            if (exists != null && exists > 0) {
                if (!filterDuplicates) throw bad("库存或已售记录中已存在相同卡密：" + abbreviate(value));
                skipped++;
                continue;
            }
            db.update("insert into dbo.card_keys(product_id,card_content) values(?,?)", productId, value);
            inserted++;
        }
        syncStock(productId);
        return Map.of("message", "已导入 " + inserted + " 条卡密" + (skipped > 0 ? "，过滤重复 " + skipped + " 条" : ""), "inserted", inserted, "skipped", skipped, "stock", stock(productId));
    }

    @DeleteMapping("/admin/products/{productId}/card-keys/{id}")
    @Transactional
    public Map<String, Object> delete(@RequestHeader(value="Authorization", required=false) String header,
                                      @PathVariable long productId, @PathVariable long id) {
        auth.admin(header);
        lockProduct(productId);
        int count = db.update("delete from dbo.card_keys where id=? and product_id=? and status=0", id, productId);
        if (count != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "卡密不存在或已售出，不能删除");
        syncStock(productId);
        return Map.of("message", "未售卡密已删除", "stock", stock(productId));
    }

    private Map<String, Object> product(long id) {
        var rows = db.queryForList("select id,name,delivery_type from dbo.products where id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        return rows.getFirst();
    }
    private Map<String, Object> lockProduct(long id) {
        var rows = db.queryForList("select id,name,delivery_type from dbo.products with (updlock,rowlock) where id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        return rows.getFirst();
    }
    private Map<String, Object> summary(long id) {
        return db.queryForMap("select sum(case when status=0 then 1 else 0 end) as available,sum(case when status=1 then 1 else 0 end) as sold from dbo.card_keys where product_id=?", id);
    }
    private int stock(long id) { return db.queryForObject("select stock from dbo.products where id=?", Integer.class, id); }
    private void syncStock(long id) {
        db.update("update dbo.products set stock=case when delivery_type='AUTO_CARD' then case when " +
                "(select count(*) from dbo.card_keys where product_id=? and status=0)-" +
                "(select coalesce(sum(oi.quantity),0) from dbo.order_items oi join dbo.orders o on o.id=oi.order_id where oi.product_id=? and o.status='PENDING_PAYMENT') < 0 then 0 else " +
                "(select count(*) from dbo.card_keys where product_id=? and status=0)-" +
                "(select coalesce(sum(oi.quantity),0) from dbo.order_items oi join dbo.orders o on o.id=oi.order_id where oi.product_id=? and o.status='PENDING_PAYMENT') end " +
                "else stock end,updated_at=SYSUTCDATETIME() where id=?", id, id, id, id, id);
    }
    private String abbreviate(String value) { return value.length() <= 32 ? value : value.substring(0, 32) + "…"; }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
