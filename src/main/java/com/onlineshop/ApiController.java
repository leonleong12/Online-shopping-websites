package com.onlineshop;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class ApiController {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,30}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^[\\x21-\\x7E]{6,64}$");
    private final JdbcTemplate db;
    private final OrderExpirationService orderExpirationService;
    private final OrderFulfillmentService orderFulfillmentService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SessionAuth sessions;
    private final WalletService wallet;
    private final Path uploadDirectory = Path.of("uploads", "products").toAbsolutePath().normalize();
    private final Path siteUploadDirectory = Path.of("uploads", "site").toAbsolutePath().normalize();

    public ApiController(JdbcTemplate db, OrderExpirationService orderExpirationService, OrderFulfillmentService orderFulfillmentService, SessionAuth sessions, WalletService wallet) {
        this.db = db;
        this.orderExpirationService = orderExpirationService;
        this.orderFulfillmentService = orderFulfillmentService;
        this.sessions = sessions;
        this.wallet = wallet;
    }

    @PostMapping("/admin/product-images")
    public Map<String, Object> uploadProductImage(@RequestHeader("Authorization") String authorization,
                                                    @RequestParam("file") MultipartFile file) {
        admin(authorization);
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择图片文件");
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!Set.of("image/jpeg", "image/png", "image/webp", "image/gif").contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只支持 JPG、PNG、WEBP 或 GIF 图片");
        }
        if (file.getSize() > 5 * 1024 * 1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片不能超过 5MB");
        String extension = switch (contentType) { case "image/png" -> ".png"; case "image/webp" -> ".webp"; case "image/gif" -> ".gif"; default -> ".jpg"; };
        String filename = UUID.randomUUID() + extension;
        try {
            Files.createDirectories(uploadDirectory);
            Files.copy(file.getInputStream(), uploadDirectory.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
            return Map.of("url", "/uploads/products/" + filename);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "图片保存失败");
        }
    }

    @PostMapping("/admin/site-images")
    public Map<String, Object> uploadSiteImage(@RequestHeader("Authorization") String authorization,
                                                @RequestParam("file") MultipartFile file) {
        admin(authorization);
        return Map.of("url", storeImage(file, siteUploadDirectory, "/uploads/site/"));
    }

    @GetMapping("/site-settings")
    public Map<String, Object> siteSettings() {
        ensureSiteSettingsTable();
        Map<String, Object> result = new HashMap<>();
        result.put("heroImageUrl", settingValue("hero_image_url"));
        result.put("editorialImageUrl", settingValue("editorial_image_url"));
        return result;
    }

    @GetMapping("/admin/site-settings")
    public Map<String, Object> adminSiteSettings(@RequestHeader("Authorization") String authorization) {
        admin(authorization);
        return siteSettings();
    }

    @PutMapping("/admin/site-settings")
    public Map<String, Object> updateSiteSettings(@RequestHeader("Authorization") String authorization,
                                                   @RequestBody Map<String, String> body) {
        admin(authorization);
        ensureSiteSettingsTable();
        upsertSetting("hero_image_url", body.get("heroImageUrl"));
        upsertSetting("editorial_image_url", body.get("editorialImageUrl"));
        return Map.of("message", "首页图片已保存");
    }

    private String settingValue(String key) {
        var rows = db.queryForList("select setting_value from site_settings where setting_key=?", key);
        return rows.isEmpty() || rows.get(0).get("setting_value") == null ? "" : String.valueOf(rows.get(0).get("setting_value"));
    }

    private void upsertSetting(String key, String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 500) badRequest("图片地址不能超过 500 个字符");
        int updated = db.update("update site_settings set setting_value=?,updated_at=SYSUTCDATETIME() where setting_key=?", normalized, key);
        if (updated == 0) db.update("insert into site_settings(setting_key,setting_value) values(?,?)", key, normalized);
    }

    private void ensureSiteSettingsTable() {
        // site_settings is created by schema_sqlserver.sql with an administrator account.
        // The application account intentionally has data read/write permission only.
    }

    private String storeImage(MultipartFile file, Path directory, String urlPrefix) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择图片文件");
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!Set.of("image/jpeg", "image/png", "image/webp", "image/gif").contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只支持 JPG、PNG、WEBP 或 GIF 图片");
        }
        if (file.getSize() > 5 * 1024 * 1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片不能超过 5MB");
        String extension = switch (contentType) { case "image/png" -> ".png"; case "image/webp" -> ".webp"; case "image/gif" -> ".gif"; default -> ".jpg"; };
        String filename = UUID.randomUUID() + extension;
        try {
            Files.createDirectories(directory);
            Files.copy(file.getInputStream(), directory.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
            return urlPrefix + filename;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "图片保存失败");
        }
    }

    @PostMapping("/auth/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body) {
        String username = authRequired(body, "username", "请输入用户名").trim();
        String email = authRequired(body, "email", "请输入邮箱").trim();
        String password = authRequired(body, "password", "请输入密码");
        if (!USERNAME_PATTERN.matcher(username).matches()) badRequest("用户名只能使用英文字母、数字和下划线，长度 3-30 位");
        if (!EMAIL_PATTERN.matcher(email).matches()) badRequest("邮箱格式不正确，请使用标准英文邮箱格式");
        if (!PASSWORD_PATTERN.matcher(password).matches()) badRequest("密码需为 6-64 位字符，只能使用英文、数字和符号");
        Integer exists = db.queryForObject("select count(*) from users where username=? or email=?", Integer.class, username, email);
        if (exists != null && exists > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名或邮箱已存在");
        try {
            db.update("insert into users(username,password_hash,email) values(?,?,?)", username, encoder.encode(password), email);
        } catch (org.springframework.dao.DataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "数据库写入失败，请确认 online_shop 数据库和 users 表正常");
        }
        return Map.of("message", "注册成功");
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String username = authRequired(body, "username", "请输入用户名").trim();
        String password = authRequired(body, "password", "请输入密码");
        var rows = db.queryForList("select id,username,password_hash,role,status from users where username=?", username);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在");
        var account = rows.get(0);
        if (!"ACTIVE".equals(String.valueOf(account.get("status")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该用户已被禁用");
        }
        if (!encoder.matches(password, String.valueOf(account.get("password_hash")))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "密码错误");
        }
        long id = ((Number) account.get("id")).longValue();
        String token = sessions.issue(id, String.valueOf(account.get("password_hash")));
        return Map.of("token", token, "userId", id, "username", account.get("username"), "role", account.get("role"));
    }

    @PostMapping("/auth/logout")
    public Map<String,Object> logout(@RequestHeader(value="Authorization",required=false) String authorization) {
        sessions.revoke(authorization);
        return Map.of("message","已退出登录");
    }

    @GetMapping("/account/profile")
    public Map<String, Object> accountProfile(@RequestHeader("Authorization") String authorization) {
        long userId = user(authorization);
        return one("select id,username,email,role from dbo.users where id=?", userId);
    }

    @PutMapping("/account/profile")
    @Transactional
    public Map<String, Object> updateAccountProfile(@RequestHeader("Authorization") String authorization,
                                                     @RequestBody Map<String, String> body) {
        long userId = user(authorization);
        String currentPassword = authRequired(body, "currentPassword", "请输入当前密码");
        String username = authRequired(body, "username", "请输入用户名").trim();
        String email = authRequired(body, "email", "请输入邮箱").trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) badRequest("用户名只能使用英文字母、数字和下划线，长度 3-30 位");
        if (!EMAIL_PATTERN.matcher(email).matches()) badRequest("邮箱格式不正确，请使用标准英文邮箱格式");
        Map<String, Object> account = one("select password_hash from dbo.users where id=?", userId);
        if (!encoder.matches(currentPassword, String.valueOf(account.get("password_hash"))))
            badRequest("当前密码错误");
        Integer duplicate = db.queryForObject("select count(*) from dbo.users where (username=? or email=?) and id<>?", Integer.class, username, email, userId);
        if (duplicate != null && duplicate > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名或邮箱已被其他账户使用");
        try {
            db.update("update dbo.users set username=?,email=? where id=?", username, email, userId);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名或邮箱已被其他账户使用");
        }
        return Map.of("message", "个人资料已更新", "username", username, "email", email);
    }

    @PutMapping("/account/password")
    public Map<String, Object> updateAccountPassword(@RequestHeader("Authorization") String authorization,
                                                      @RequestBody Map<String, String> body) {
        long userId = user(authorization);
        String currentPassword = authRequired(body, "currentPassword", "请输入当前密码");
        String newPassword = authRequired(body, "newPassword", "请输入新密码");
        if (!PASSWORD_PATTERN.matcher(newPassword).matches()) badRequest("密码需为 6-64 位字符，只能使用英文、数字和符号");
        String passwordHash = String.valueOf(one("select password_hash from dbo.users where id=?", userId).get("password_hash"));
        if (!encoder.matches(currentPassword, passwordHash)) badRequest("当前密码错误");
        if (encoder.matches(newPassword, passwordHash)) badRequest("新密码不能与当前密码相同");
        db.update("update dbo.users set password_hash=? where id=?", encoder.encode(newPassword), userId);
        return Map.of("message", "密码已更新，请使用新密码重新登录");
    }

    @GetMapping("/products")
    public List<Map<String, Object>> products(@RequestParam(required = false) String q, @RequestParam(required = false) Long categoryId) {
        String keyword = q == null ? "" : q.trim();
        return db.queryForList("select p.*,c.name as category_name from products p left join categories c on c.id=p.category_id " +
                        "where p.status='ON_SALE' and (?='' or p.name like ? or p.description like ?) " +
                        "and (CAST(? AS BIGINT) is null or p.category_id=CAST(? AS BIGINT)) order by p.id desc",
                keyword, "%" + keyword + "%", "%" + keyword + "%", categoryId, categoryId);
    }

    @GetMapping("/products/{id}")
    public Map<String, Object> product(@PathVariable long id) { return one("select p.*,c.name as category_name from products p left join categories c on c.id=p.category_id where p.id=? and p.status='ON_SALE'", id); }

    @GetMapping("/featured-products")
    public List<Map<String, Object>> featuredProducts() {
        return db.queryForList("select p.*,c.name as category_name from products p left join categories c on c.id=p.category_id " +
                "where p.is_featured=1 and p.status='ON_SALE' order by p.id desc");
    }

    @PutMapping("/admin/products/{id}/featured")
    public Map<String, Object> setProductFeatured(@RequestHeader("Authorization") String authorization,
                                                 @PathVariable long id, @RequestBody Map<String, Object> body) {
        admin(authorization);
        if (!(body.get("isFeatured") instanceof Boolean)) badRequest("请选择是否加入本周精选");
        boolean featured = (Boolean) body.get("isFeatured");
        int changed = db.update("update products set is_featured=?,updated_at=SYSUTCDATETIME() where id=?", featured, id);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        return Map.of("isFeatured", featured, "message", featured ? "已加入本周精选" : "已移出本周精选");
    }

    @GetMapping("/admin/products")
    public List<Map<String, Object>> adminProducts(@RequestHeader("Authorization") String authorization) {
        admin(authorization);
        return db.queryForList("select p.*,c.name as category_name from products p left join categories c on c.id=p.category_id order by p.id desc");
    }

    @PostMapping("/admin/products")
    public Map<String, Object> addProduct(@RequestHeader("Authorization") String authorization, @RequestBody Map<String, Object> body) {
        admin(authorization); validateProduct(body);
        Long id = db.queryForObject("insert into products(category_id,name,description,price,stock,image_url,status,is_featured,delivery_type) output inserted.id values(?,?,?,?,?,?,?,?,?)", Long.class,
                productCategory(body), required(body, "name"), nullable(body, "description"), decimal(body, "price"), integer(body, "stock"), nullable(body, "imageUrl"), status(body), featuredValue(body, false), deliveryType(body));
        if("AUTO_CARD".equals(deliveryType(body))) syncCardStock(id);
        return Map.of("message", "商品已新增", "id", id);
    }

    @PutMapping("/admin/products/{id}")
    public Map<String, Object> editProduct(@RequestHeader("Authorization") String authorization, @PathVariable long id, @RequestBody Map<String, Object> body) {
        admin(authorization); validateProduct(body);
        int changed = db.update("update products set category_id=?,name=?,description=?,price=?,stock=?,image_url=?,status=?,is_featured=COALESCE(?,is_featured),delivery_type=? where id=?",
                productCategory(body), required(body, "name"), nullable(body, "description"), decimal(body, "price"), integer(body, "stock"), nullable(body, "imageUrl"), status(body), featuredValue(body, null), deliveryType(body), id);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        if("AUTO_CARD".equals(deliveryType(body))) syncCardStock(id);
        return Map.of("message", "商品已保存");
    }

    private String deliveryType(Map<String, Object> body) {
        Object raw=body.get("deliveryType");
        String value=raw==null?"PHYSICAL":String.valueOf(raw);
        if(!Set.of("PHYSICAL","AUTO_CARD").contains(value)) badRequest("请选择实物发货或自动卡密");
        return value;
    }

    private void syncCardStock(long productId) {
        db.update("update dbo.products set stock=case when (select count(*) from dbo.card_keys where product_id=? and status=0)-"+
                "(select coalesce(sum(oi.quantity),0) from dbo.order_items oi join dbo.orders o on o.id=oi.order_id where oi.product_id=? and o.status='PENDING_PAYMENT') < 0 then 0 else "+
                "(select count(*) from dbo.card_keys where product_id=? and status=0)-"+
                "(select coalesce(sum(oi.quantity),0) from dbo.order_items oi join dbo.orders o on o.id=oi.order_id where oi.product_id=? and o.status='PENDING_PAYMENT') end,updated_at=SYSUTCDATETIME() where id=?",
                productId,productId,productId,productId,productId);
    }

    @DeleteMapping("/admin/products/{id}")
    public Map<String, Object> deleteProduct(@RequestHeader("Authorization") String authorization, @PathVariable long id) {
        admin(authorization); db.update("update products set status='OFF_SALE' where id=?", id); return Map.of("message", "商品已下架");
    }

    @GetMapping("/cart")
    public List<Map<String, Object>> cart(@RequestHeader("Authorization") String authorization) {
        return db.queryForList("select c.id,c.product_id,c.quantity,p.name,p.price,p.stock,p.image_url,p.status from cart_items c join products p on p.id=c.product_id where c.user_id=? order by c.id desc", user(authorization));
    }

    @PostMapping("/cart/items")
    public Map<String, Object> addCart(@RequestHeader("Authorization") String authorization, @RequestBody Map<String, Object> body) {
        long userId = user(authorization), productId = number(body, "productId"); int quantity = integer(body, "quantity");
        if (quantity < 1) badRequest("数量必须大于 0");
        var product = one("select id,stock,status from products where id=?", productId);
        if (!"ON_SALE".equals(product.get("status"))) badRequest("商品已下架");
        int stock = ((Number) product.get("stock")).intValue();
        Integer inCart = db.queryForObject("select coalesce(sum(quantity),0) from cart_items where user_id=? and product_id=?", Integer.class, userId, productId);
        if ((inCart == null ? 0 : inCart) + quantity > stock) badRequest("库存不足");
        db.update("merge cart_items as t using (select ? user_id, ? product_id) s on t.user_id=s.user_id and t.product_id=s.product_id " +
                "when matched then update set quantity=t.quantity+? when not matched then insert(user_id,product_id,quantity) values(s.user_id,s.product_id,?);", userId, productId, quantity, quantity);
        return Map.of("message", "已加入购物车");
    }

    @PutMapping("/cart/items/{id}")
    public Map<String, Object> updateCart(@RequestHeader("Authorization") String authorization, @PathVariable long id, @RequestBody Map<String, Object> body) {
        int quantity = integer(body, "quantity"); if (quantity < 1) badRequest("数量必须大于 0"); long userId = user(authorization);
        var item = one("select c.product_id,p.stock from cart_items c join products p on p.id=c.product_id where c.id=? and c.user_id=?", id, userId);
        if (quantity > ((Number) item.get("stock")).intValue()) badRequest("库存不足");
        db.update("update cart_items set quantity=? where id=? and user_id=?", quantity, id, userId);
        return Map.of("message", "数量已更新");
    }

    @DeleteMapping("/cart/items/{id}")
    public Map<String, Object> removeCart(@RequestHeader("Authorization") String authorization, @PathVariable long id) {
        db.update("delete from cart_items where id=? and user_id=?", id, user(authorization)); return Map.of("message", "商品已移除");
    }

    @Transactional
    @PostMapping("/orders")
    public Map<String, Object> order(@RequestHeader("Authorization") String authorization, @RequestBody Map<String, String> body) {
        long userId = user(authorization);
        if (!Set.of("recipientName", "recipientPhone", "shippingAddress").containsAll(body.keySet()))
            badRequest("订单仅接受收货信息，商品价格与总额由服务器计算");
        String recipientName = required(body, "recipientName").trim();
        String recipientPhone = required(body, "recipientPhone").trim();
        String shippingAddress = required(body, "shippingAddress").trim();
        if (recipientName.length() > 80) badRequest("收货人姓名不能超过 80 个字符");
        if (!recipientPhone.matches("^1[3-9]\\d{9}$")) badRequest("请输入有效的 11 位中国大陆手机号");
        if (shippingAddress.length() > 500) badRequest("收货地址不能超过 500 个字符");
        var items = db.queryForList("select c.product_id,c.quantity,p.name,p.price,p.stock,p.status from cart_items c join products p with (updlock, rowlock) on p.id=c.product_id where c.user_id=?", userId);
        if (items.isEmpty()) badRequest("购物车为空");
        BigDecimal total = BigDecimal.ZERO;
        for (var item : items) {
            int quantity = ((Number) item.get("quantity")).intValue();
            if (!"ON_SALE".equals(item.get("status")) || ((Number) item.get("stock")).intValue() < quantity) badRequest("部分商品库存不足或已下架");
            total = total.add(((BigDecimal) item.get("price")).multiply(BigDecimal.valueOf(quantity)));
        }
        Long orderId = db.queryForObject("insert into orders(user_id,total_amount,recipient_name,recipient_phone,shipping_address,payment_deadline) " +
                "output inserted.id values(?,?,?,?,?,DATEADD(MINUTE,15,SYSUTCDATETIME()))", Long.class,
                userId, total, recipientName, recipientPhone, shippingAddress);
        if (orderId == null) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "订单创建失败");
        for (var item : items) {
            int quantity = ((Number) item.get("quantity")).intValue();
            db.update("insert into order_items(order_id,product_id,product_name,unit_price,quantity) values(?,?,?,?,?)", orderId, item.get("product_id"), item.get("name"), item.get("price"), quantity);
            db.update("update products set stock=stock-? where id=?", quantity, item.get("product_id"));
        }
        db.update("delete from cart_items where user_id=?", userId);
        return Map.of("orderId", orderId, "totalAmount", total);
    }

    @PostMapping("/orders/{id}/pay")
    public Map<String, Object> pay(@RequestHeader("Authorization") String authorization, @PathVariable long id,
                                   @RequestBody(required=false) Map<String,Object> body) {
        long userId = user(authorization);
        if (body != null && !body.isEmpty()) badRequest("付款无需提交金额，订单金额由服务器确认");
        orderExpirationService.cancelIfExpired(id, userId);
        return wallet.pay(id, userId);
    }

    @GetMapping("/orders")
    public List<Map<String, Object>> orders(@RequestHeader("Authorization") String authorization) {
        orderExpirationService.cancelExpiredOrders();
        return db.queryForList("select o.*,CASE WHEN o.status='PENDING_PAYMENT' AND o.payment_deadline>SYSUTCDATETIME() " +
                "THEN DATEDIFF(SECOND,SYSUTCDATETIME(),o.payment_deadline) ELSE 0 END AS seconds_remaining," +
                "CONVERT(varchar(23),o.payment_deadline,126)+'Z' AS payment_deadline_utc," +
                "CONVERT(varchar(23),SYSUTCDATETIME(),126)+'Z' AS server_now_utc " +
                "from orders o where o.user_id=? order by o.created_at desc", user(authorization));
    }

    @GetMapping("/orders/{id}")
    public Map<String, Object> orderDetail(@RequestHeader("Authorization") String authorization, @PathVariable long id) {
        long userId = user(authorization);
        orderExpirationService.cancelExpiredOrders();
        var order = one("select o.*,CASE WHEN o.status='PENDING_PAYMENT' AND o.payment_deadline>SYSUTCDATETIME() " +
                "THEN DATEDIFF(SECOND,SYSUTCDATETIME(),o.payment_deadline) ELSE 0 END AS seconds_remaining," +
                "CONVERT(varchar(23),o.payment_deadline,126)+'Z' AS payment_deadline_utc," +
                "CONVERT(varchar(23),SYSUTCDATETIME(),126)+'Z' AS server_now_utc " +
                "from orders o where o.id=? and o.user_id=?", id, userId);
        return Map.of("order", order, "items", db.queryForList(
                "select oi.*, p.image_url from order_items oi left join products p on p.id=oi.product_id where oi.order_id=? order by oi.id",
                id));
    }

    @GetMapping("/admin/orders")
    public List<Map<String, Object>> adminOrders(@RequestHeader("Authorization") String authorization) {
        admin(authorization);
        orderExpirationService.cancelExpiredOrders();
        return db.queryForList("select o.*,u.username,p.payment_method,CASE WHEN o.status='PENDING_PAYMENT' AND o.payment_deadline>SYSUTCDATETIME() " +
                "THEN DATEDIFF(SECOND,SYSUTCDATETIME(),o.payment_deadline) ELSE 0 END AS seconds_remaining," +
                "CONVERT(varchar(23),o.payment_deadline,126)+'Z' AS payment_deadline_utc," +
                "CONVERT(varchar(23),SYSUTCDATETIME(),126)+'Z' AS server_now_utc " +
                "from orders o join users u on u.id=o.user_id left join payments p on p.order_id=o.id order by o.created_at desc");
    }

    @Transactional
    @PutMapping("/admin/orders/{id}/status")
    public Map<String, Object> updateOrderStatus(@RequestHeader("Authorization") String authorization, @PathVariable long id, @RequestBody Map<String, String> body) {
        admin(authorization); String status = required(body, "status");
        if (!Set.of("PROCESSING", "CANCELLED").contains(status)) badRequest("发货请填写发货信息，完成订单需由顾客确认收货");
        if ("CANCELLED".equals(status)) {
            if (!orderExpirationService.cancelPendingOrder(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "只能取消待付款订单");
        } else if (db.update("update orders set status='PROCESSING',updated_at=SYSUTCDATETIME() where id=? and status='PAID' and payment_status='PAID'", id) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有已付款订单可以开始处理");
        }
        return Map.of("message", "订单状态已更新");
    }

    @PostMapping("/admin/orders/{id}/ship")
    public Map<String, Object> shipOrder(@RequestHeader("Authorization") String authorization, @PathVariable long id,
                                          @RequestBody Map<String, String> body) {
        admin(authorization);
        orderFulfillmentService.ship(id, body);
        return Map.of("message", "订单已发货，顾客可查看发货信息");
    }

    @PostMapping("/orders/{id}/confirm-receipt")
    public Map<String, Object> confirmReceipt(@RequestHeader("Authorization") String authorization, @PathVariable long id) {
        orderFulfillmentService.confirmReceipt(id, user(authorization));
        return Map.of("message", "已确认收货，订单已完成");
    }

    @GetMapping("/admin/statistics/sales")
    public List<Map<String, Object>> stats(@RequestHeader("Authorization") String authorization,
                                           @RequestParam(defaultValue = "day") String period) {
        admin(authorization);
        String bucket = statisticsBucket(period);
        String window = statisticsWindow(period);
        return db.queryForList("select convert(varchar(10), bucket_start, 23) as period_start, " +
                "count(distinct order_id) as order_count, sum(item_count) as item_count, sum(sales_amount) as sales_amount " +
                "from (select o.id as order_id, " + bucket + " as bucket_start, sum(oi.quantity) as item_count, sum(oi.subtotal) as sales_amount " +
                "from dbo.orders o join dbo.order_items oi on oi.order_id=o.id " +
                "where o.payment_status=N'PAID' and o.status in (N'PAID',N'PROCESSING',N'SHIPPED',N'COMPLETED') and " + window + " " +
                "group by o.id, " + bucket + ") s group by bucket_start order by bucket_start desc");
    }

    @GetMapping("/admin/statistics/overview")
    public Map<String, Object> overview(@RequestHeader("Authorization") String authorization) {
        admin(authorization);
        return Map.of("productCount", scalar("select count(*) from products where status='ON_SALE'"), "orderCount", scalar("select count(*) from orders"), "paidSales", scalar("select coalesce(sum(total_amount),0) from orders where payment_status='PAID'"), "customerCount", scalar("select count(*) from users where role='CUSTOMER'"), "walletFloat", scalar("select coalesce(sum(balance),0) from users"), "rechargeTotal", scalar("select coalesce(sum(amount),0) from dbo.wallet_transactions where type='RECHARGE'"), "consumeTotal", scalar("select coalesce(sum(-amount),0) from dbo.wallet_transactions where type='CONSUME'"));
    }

    @GetMapping("/admin/statistics/top-products")
    public List<Map<String, Object>> topProducts(@RequestHeader("Authorization") String authorization,
                                                  @RequestParam(defaultValue = "day") String period) {
        admin(authorization);
        String window = statisticsWindow(period);
        return db.queryForList("select top 8 p.name as product_name, p.id as product_id, sum(oi.quantity) as sold_count, sum(oi.subtotal) as revenue " +
                "from dbo.order_items oi join dbo.orders o on o.id=oi.order_id join dbo.products p on p.id=oi.product_id " +
                "where o.payment_status=N'PAID' and o.status in (N'PAID',N'PROCESSING',N'SHIPPED',N'COMPLETED') and " + window + " " +
                "group by p.id,p.name order by sold_count desc,revenue desc");
    }

    private String statisticsBucket(String period) {
        return switch (period) {
            case "day" -> "cast(o.created_at as date)";
            case "week" -> "dateadd(day,-(datediff(day,convert(date,'19000101'),cast(o.created_at as date)) % 7),cast(o.created_at as date))";
            case "month" -> "datefromparts(year(o.created_at),month(o.created_at),1)";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "统计周期必须为 day、week 或 month");
        };
    }

    private String statisticsWindow(String period) {
        return switch (period) {
            case "day" -> "o.created_at >= dateadd(day,-29,cast(sysutcdatetime() as date)) and o.created_at < dateadd(day,1,cast(sysutcdatetime() as date))";
            case "week" -> "o.created_at >= dateadd(day,-77,dateadd(day,-(datediff(day,convert(date,'19000101'),cast(sysutcdatetime() as date)) % 7),cast(sysutcdatetime() as date))) and o.created_at < dateadd(day,1,cast(sysutcdatetime() as date))";
            case "month" -> "o.created_at >= datefromparts(year(dateadd(month,-11,sysutcdatetime())),month(dateadd(month,-11,sysutcdatetime())),1) and o.created_at < dateadd(day,1,cast(sysutcdatetime() as date))";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "统计周期必须为 day、week 或 month");
        };
    }

    private Number scalar(String sql) { return db.queryForObject(sql, Number.class); }
    private Boolean featuredValue(Map<String, Object> body, Boolean fallback) {
        if (!body.containsKey("isFeatured")) return fallback;
        if (!(body.get("isFeatured") instanceof Boolean)) badRequest("精选设置必须为开启或关闭");
        return (Boolean) body.get("isFeatured");
    }
    private Long productCategory(Map<String, Object> body) {
        Object raw = body.get("categoryId");
        if (raw == null) return null;
        long id;
        try { id = new BigDecimal(raw.toString()).longValueExact(); }
        catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择有效的商品分类"); }
        if (id <= 0 || !Integer.valueOf(1).equals(db.queryForObject("select count(*) from dbo.categories where id=?", Integer.class, id)))
            badRequest("商品分类不存在，请刷新后重新选择");
        return id;
    }
    private void validateProduct(Map<String, Object> body) { if (required(body, "name").length() > 150 || decimal(body, "price").signum() < 0 || integer(body, "stock") < 0) badRequest("商品资料不正确"); }
    private String required(Map<String, ?> body, String key) { Object value = body.get(key); if (value == null || value.toString().isBlank()) badRequest(key + " 不能为空"); return value.toString(); }
    private String authRequired(Map<String, ?> body, String key, String message) { Object value = body.get(key); if (value == null || value.toString().isBlank()) badRequest(message); return value.toString(); }
    private String nullable(Map<String, ?> body, String key) { Object value = body.get(key); return value == null || value.toString().isBlank() ? null : value.toString(); }
    private long number(Map<String, ?> body, String key) { Object value = body.get(key); if (!(value instanceof Number)) badRequest(key + " 格式不正确"); return ((Number) value).longValue(); }
    private int integer(Map<String, ?> body, String key) { Object value = body.get(key); if (!(value instanceof Number)) badRequest(key + " 格式不正确"); return ((Number) value).intValue(); }
    private BigDecimal decimal(Map<String, ?> body, String key) { Object value = body.get(key); try { return new BigDecimal(String.valueOf(value)); } catch (Exception e) { badRequest(key + " 格式不正确"); return BigDecimal.ZERO; } }
    private String status(Map<String, ?> body) { Object raw = body.get("status"); String value = raw == null ? "ON_SALE" : String.valueOf(raw); return Set.of("ON_SALE", "OFF_SALE").contains(value) ? value : "ON_SALE"; }
    private void badRequest(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private Map<String, Object> one(String sql, Object... params) { var rows = db.queryForList(sql, params); if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在"); return rows.get(0); }
    private long user(String authorization) { return sessions.user(authorization); }
    private void admin(String authorization) { sessions.admin(authorization); }
}
