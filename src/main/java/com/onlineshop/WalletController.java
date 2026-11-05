package com.onlineshop;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api")
public class WalletController {
    private final SessionAuth auth;
    private final WalletService wallet;
    private final JdbcTemplate db;
    public WalletController(SessionAuth auth, WalletService wallet, JdbcTemplate db) { this.auth=auth;this.wallet=wallet;this.db=db; }
    @ModelAttribute
    public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); }
    public record Redeem(String code) {}
    public record Adjust(String username, String amount, String remark, String requestId) {}
    public record Generate(String amount, int quantity, String requestId) {}
    public record Refund(String remark) {}

    @GetMapping("/wallet")
    public Map<String,Object> summary(@RequestHeader(value="Authorization",required=false) String header) { return wallet.summary(auth.user(header)); }
    @GetMapping("/wallet/transactions")
    public List<Map<String,Object>> transactions(@RequestHeader(value="Authorization",required=false) String header,@RequestParam(defaultValue="9223372036854775807") long before) { return wallet.transactions(auth.user(header),before); }
    @PostMapping("/wallet/redeem")
    public Map<String,Object> redeem(@RequestHeader(value="Authorization",required=false) String header, @RequestBody Redeem body, HttpServletResponse response) {
        var result=wallet.redeem(auth.user(header),body.code());
        if(result.containsKey("error")) {
            int status=((Number)result.get("status")).intValue();
            if(status==429) response.setHeader("Retry-After","60");
            throw new ResponseStatusException(HttpStatus.valueOf(status),String.valueOf(result.get("error")));
        }
        return result;
    }
    @GetMapping("/admin/wallet/users")
    public List<Map<String,Object>> users(@RequestHeader(value="Authorization",required=false) String header,@RequestParam(defaultValue="") String q) {
        auth.admin(header);
        if(q.length()>50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"用户名过长");
        return db.queryForList("select top (50) id,username,balance,status from dbo.users where username like ? order by username", "%"+q+"%");
    }
    @GetMapping("/admin/wallet/transactions")
    public List<Map<String,Object>> adminTransactions(@RequestHeader(value="Authorization",required=false) String header,@RequestParam(defaultValue="9223372036854775807") long before,@RequestParam(defaultValue="") String username) {
        auth.admin(header);
        return db.queryForList("select top (30) t.id,u.username,t.type,t.amount,t.balance_after,t.related_order_no,t.remark,a.username as operator,CONVERT(varchar(23),t.created_at,126)+'Z' as created_at from dbo.wallet_transactions t join dbo.users u on u.id=t.user_id left join dbo.users a on a.id=t.actor_id where t.id<? and (?='' or u.username=?) order by t.id desc",before,username,username);
    }
    @PostMapping("/admin/wallet/adjust")
    public Map<String,Object> adjust(@RequestHeader(value="Authorization",required=false) String header,@RequestBody Adjust body) { return wallet.adjust(auth.admin(header),body.username(),body.amount(),body.remark(),body.requestId()); }
    @PostMapping("/admin/wallet/recharge-codes")
    public Map<String,Object> generate(@RequestHeader(value="Authorization",required=false) String header,@RequestBody Generate body) { return wallet.generate(auth.admin(header),body.amount(),body.quantity(),body.requestId()); }
    @GetMapping("/admin/wallet/recharge-codes")
    public List<Map<String,Object>> codes(@RequestHeader(value="Authorization",required=false) String header,@RequestParam(defaultValue="9223372036854775807") long before) {
        auth.admin(header);
        // Resolve the cursor in SQL so pagination retains the full precision of used_at.
        return db.queryForList("""
                select top (100) c.id,case when c.status=0 then c.code else null end as code,
                    c.amount,c.status,u.username as used_by,
                    CONVERT(varchar(23),c.used_at,126)+'Z' as used_at,
                    CONVERT(varchar(23),c.created_at,126)+'Z' as created_at
                from dbo.recharge_codes c
                left join dbo.users u on u.id=c.used_by
                left join dbo.recharge_codes anchor on anchor.id=?
                where ?=9223372036854775807 or c.status>anchor.status
                    or (c.status=anchor.status and (
                        (c.status=0 and c.id<anchor.id)
                        or (c.status=1 and (
                            c.used_at<anchor.used_at
                            or (c.used_at=anchor.used_at and c.id<anchor.id)
                            or (c.used_at is null and anchor.used_at is not null)
                            or (c.used_at is null and anchor.used_at is null and c.id<anchor.id)
                        ))
                    ))
                order by c.status,case when c.status=1 then c.used_at end desc,c.id desc
                """,before,before);
    }
    @PostMapping("/admin/wallet/orders/{id}/refund")
    public Map<String,Object> refund(@RequestHeader(value="Authorization",required=false) String header,@PathVariable long id,@RequestBody Refund body) { return wallet.refund(auth.admin(header),id,body.remark()); }
}
