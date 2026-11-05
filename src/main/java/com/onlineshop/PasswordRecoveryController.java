package com.onlineshop;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class PasswordRecoveryController {
    private final PasswordRecoveryService service;
    public PasswordRecoveryController(PasswordRecoveryService service) { this.service = service; }

    @PostMapping("/forgot-password")
    public Map<String, String> forgot(@RequestBody Map<String, String> body, HttpServletRequest request) {
        return service.request(body.get("email"), request.getRemoteAddr());
    }

    @PostMapping("/reset-password")
    public Map<String, String> reset(@RequestBody Map<String, String> body) {
        return service.reset(body.get("token"), body.get("newPassword"));
    }
}
