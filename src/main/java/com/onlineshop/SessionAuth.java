package com.onlineshop;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionAuth {
    private record Session(long userId, Instant expiresAt, String passwordHash) {}
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final JdbcTemplate db;
    public SessionAuth(JdbcTemplate db) { this.db = db; }
    public String issue(long id, String passwordHash) {
        sessions.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(Instant.now()));
        String token = UUID.randomUUID() + "-" + UUID.randomUUID();
        sessions.put(token, new Session(id, Instant.now().plusSeconds(43200), passwordHash));
        return token;
    }
    public long user(String header) {
        if (header == null || !header.startsWith("Bearer ")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");
        String token = header.substring(7);
        Session session = sessions.get(token);
        if (session == null || !session.expiresAt().isAfter(Instant.now())) {
            sessions.remove(token);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"登录已失效，请重新登录");
        }
        var accounts = db.queryForList("select status,password_hash from dbo.users where id=?", session.userId());
        if (accounts.isEmpty()) {
            sessions.remove(token);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"账号不存在，请重新登录");
        }
        if (!"ACTIVE".equals(accounts.getFirst().get("status")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"账号已被禁用");
        // Invalidate sessions when the stored account password changes.
        if (!java.util.Objects.equals(session.passwordHash(), accounts.getFirst().get("password_hash"))) {
            sessions.remove(token);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"密码已更新，请重新登录");
        }
        return session.userId();
    }
    public long admin(String header) {
        long id = user(header);
        if (!"ADMIN".equals(db.queryForObject("select role from dbo.users where id=?", String.class, id)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"需要管理员权限");
        return id;
    }
    public void revoke(String header) { if (header != null && header.startsWith("Bearer ")) sessions.remove(header.substring(7)); }
}
