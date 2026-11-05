package com.onlineshop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.sql.SQLException;
import java.util.Map;

@RestControllerAdvice(assignableTypes={WalletController.class,ApiController.class,SupportController.class,CardKeyController.class,CategoryController.class,PasswordRecoveryController.class})
public class DatabaseErrorAdvice {
    private static final Logger log=LoggerFactory.getLogger(DatabaseErrorAdvice.class);
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String,String>> database(DataAccessException error, jakarta.servlet.http.HttpServletRequest request) {
        // Do not log request bodies, recharge codes or SQL error text that may contain secrets.
        SQLException sqlError = findSqlException(error);
        if (sqlError == null) {
            log.error("Database operation rolled back/failed: type={} path={}",error.getClass().getSimpleName(),request.getRequestURI());
        } else {
            String detail = sqlError.getMessage() == null ? "" : sqlError.getMessage().replaceAll("[\\r\\n\\t]", " ");
            if (detail.length() > 400) detail = detail.substring(0, 400);
            log.error("Database operation rolled back/failed: type={} path={} sqlState={} vendorCode={} detail={}",
                    error.getClass().getSimpleName(),request.getRequestURI(),sqlError.getSQLState(),sqlError.getErrorCode(),detail);
        }
        HttpStatus status=error instanceof DataIntegrityViolationException ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE;
        String message=error instanceof TransientDataAccessException ? "操作繁忙，本次资金变动未提交，请刷新后重试" :
                error instanceof DataIntegrityViolationException ? "数据校验冲突，本次资金变动未提交，请刷新核对后重试" :
                "数据库暂时不可用，请稍后重试；若刚升级，请先执行对应数据库迁移";
        if (request.getRequestURI().startsWith("/api/auth/")) message = "账户服务暂时不可用，本次操作未完成，请稍后重试";
        if (request.getRequestURI().startsWith("/api/admin/categories") || request.getRequestURI().equals("/api/categories"))
            message = "分类操作未完成，请刷新后重试；若刚升级，请先执行分类数据库更新";
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("message",message));
    }

    private SQLException findSqlException(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sqlException) return sqlException;
        return null;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,String>> invalidBody(jakarta.servlet.http.HttpServletRequest request) {
        String message = request.getRequestURI().startsWith("/api/auth/")
                ? "请求格式不正确，请检查邮箱及密码等填写内容" : "请求格式不正确，请检查金额、数量及填写内容";
        return ResponseEntity.badRequest().header("Cache-Control", "no-store").body(Map.of("message", message));
    }
}
