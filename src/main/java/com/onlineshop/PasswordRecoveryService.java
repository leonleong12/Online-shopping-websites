package com.onlineshop;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class PasswordRecoveryService {
    public static final String REQUEST_MESSAGE = "如果该邮箱已注册，重设密码链接会发送到你的邮箱；请检查收件箱和垃圾邮件。";
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");
    private static final Pattern PASSWORD = Pattern.compile("^[\\x21-\\x7E]{6,64}$");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ShopMailService mail;
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public PasswordRecoveryService(JdbcTemplate db, org.springframework.transaction.PlatformTransactionManager manager, ShopMailService mail) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.mail = mail;
    }

    public Map<String, String> request(String email, String ip) {
        if (email == null || !EMAIL.matcher(email.trim()).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入有效的标准英文邮箱");
        String normalized = email.trim().toLowerCase(java.util.Locale.ROOT);
        claim("email:" + normalized, 60, 5);
        if (ip != null && !ip.isBlank()) claim("ip:" + ip, 0, 20);
        if (!mail.isConfigured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "邮件服务尚未配置，请联系管理员");
        String token = randomToken();
        Map<String, Object> account = tx.execute(status -> {
            var rows = db.queryForList("select top 1 id,username,email from dbo.users where lower(email)=? and status='ACTIVE'", normalized);
            if (rows.isEmpty()) return null;
            Map<String, Object> row = rows.getFirst();
            long userId = ((Number) row.get("id")).longValue();
            db.update("update dbo.password_reset_tokens set consumed_at=SYSUTCDATETIME() where user_id=? and consumed_at is null", userId);
            db.update("insert into dbo.password_reset_tokens(user_id,token_hash,expires_at) values(?,?,DATEADD(MINUTE,30,SYSUTCDATETIME()))", userId, hash(token));
            return row;
        });
        if (account != null) {
            try { mail.sendReset(String.valueOf(account.get("email")), String.valueOf(account.get("username")), token); }
            catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "邮件暂时发送失败，请稍后重试"); }
        }
        return Map.of("message", REQUEST_MESSAGE);
    }

    public Map<String, String> reset(String token, String newPassword) {
        if (token == null || token.length() < 32 || token.length() > 128) invalidToken();
        if (newPassword == null || !PASSWORD.matcher(newPassword).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码需为 6-64 位字符，只能使用英文、数字和符号");
        Boolean changed = tx.execute(status -> {
            var rows = db.queryForList("select top 1 user_id from dbo.password_reset_tokens with (updlock,rowlock) where token_hash=? and consumed_at is null and expires_at>SYSUTCDATETIME()", hash(token));
            if (rows.isEmpty()) return false;
            long userId = ((Number) rows.getFirst().get("user_id")).longValue();
            String passwordHash = encoder.encode(newPassword);
            int update = db.update("update dbo.users set password_hash=? where id=?", passwordHash, userId);
            int consume = db.update("update dbo.password_reset_tokens set consumed_at=SYSUTCDATETIME() where token_hash=? and consumed_at is null", hash(token));
            return update == 1 && consume == 1;
        });
        if (!Boolean.TRUE.equals(changed)) invalidToken();
        return Map.of("message", "密码已重设，请使用新密码登录");
    }

    private void claim(String rawKey, int cooldownSeconds, int hourlyLimit) {
        if (hourlyLimit <= 0) return;
        String key = hash(rawKey);
        tx.executeWithoutResult(status -> {
            // HOLDLOCK makes the read serializable for this key, including the
            // missing-row case, so two rapid clicks cannot both try to insert
            // the same rate-limit record and produce a primary-key error.
            var rows = db.queryForList("select window_started_at,attempts,next_allowed_at from dbo.password_reset_limits with (updlock,holdlock) where scope_key=?", key);
            if (rows.isEmpty()) {
                db.update("insert into dbo.password_reset_limits(scope_key,window_started_at,attempts,next_allowed_at) values(?,SYSUTCDATETIME(),1,DATEADD(SECOND,?,SYSUTCDATETIME()))", key, cooldownSeconds);
                return;
            }
            Map<String, Object> row = rows.getFirst();
            java.sql.Timestamp now = java.sql.Timestamp.valueOf(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
            java.sql.Timestamp window = (java.sql.Timestamp) row.get("window_started_at");
            java.sql.Timestamp next = (java.sql.Timestamp) row.get("next_allowed_at");
            int attempts = ((Number) row.get("attempts")).intValue();
            if (next != null && next.after(now)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
            if (window != null && window.toInstant().plus(Duration.ofHours(1)).isBefore(now.toInstant()))
                db.update("update dbo.password_reset_limits set window_started_at=SYSUTCDATETIME(),attempts=1,next_allowed_at=DATEADD(SECOND,?,SYSUTCDATETIME()) where scope_key=?", cooldownSeconds, key);
            else if (attempts >= hourlyLimit) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求次数过多，请稍后再试");
            else db.update("update dbo.password_reset_limits set attempts=attempts+1,next_allowed_at=DATEADD(SECOND,?,SYSUTCDATETIME()) where scope_key=?", cooldownSeconds, key);
        });
    }

    private String randomToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return HexFormat.of().formatHex(bytes); }
    static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static void invalidToken() { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "链接无效或已过期，请重新申请密码重设"); }
}
