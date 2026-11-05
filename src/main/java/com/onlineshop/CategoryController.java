package com.onlineshop;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CategoryController {
    private final JdbcTemplate db;
    private final SessionAuth sessions;

    public CategoryController(JdbcTemplate db, SessionAuth sessions) { this.db = db; this.sessions = sessions; }

    public record CategoryInput(String name, String description, Integer sortOrder, Boolean isVisible) {}
    public record CategoryOrder(List<Long> ids) {}

    @GetMapping("/categories")
    public List<Map<String, Object>> categories() {
        return db.queryForList("""
                select c.id,c.name,c.description from dbo.categories c
                where c.is_visible=1 and exists
                    (select 1 from dbo.products p where p.category_id=c.id and p.status='ON_SALE')
                order by c.sort_order,c.id
                """);
    }

    @GetMapping("/admin/categories")
    public List<Map<String, Object>> adminCategories(@RequestHeader(value="Authorization", required=false) String authorization) {
        sessions.admin(authorization);
        return db.queryForList("""
                select c.id,c.name,c.description,c.sort_order,c.is_visible,
                    (select count(*) from dbo.products p where p.category_id=c.id) as product_count,
                    (select count(*) from dbo.products p where p.category_id=c.id and p.status='ON_SALE') as on_sale_count
                from dbo.categories c order by c.sort_order,c.id
                """);
    }

    @PostMapping("/admin/categories")
    public Map<String, Object> create(@RequestHeader(value="Authorization", required=false) String authorization,
                                      @RequestBody CategoryInput input) {
        sessions.admin(authorization);
        CategoryInput value = validate(input);
        try {
            Long id = db.queryForObject("insert into dbo.categories(name,description,sort_order,is_visible) output inserted.id values(?,?,?,?)",
                    Long.class, value.name(), value.description(), value.sortOrder(), value.isVisible());
            return Map.of("id", id, "message", "分类已新增");
        } catch (DuplicateKeyException ex) { throw duplicate(); }
    }

    @PutMapping("/admin/categories/{id}")
    public Map<String, Object> update(@RequestHeader(value="Authorization", required=false) String authorization,
                                      @PathVariable long id, @RequestBody CategoryInput input) {
        sessions.admin(authorization);
        CategoryInput value = validate(input);
        try {
            int changed = db.update("update dbo.categories set name=?,description=?,sort_order=?,is_visible=? where id=?",
                    value.name(), value.description(), value.sortOrder(), value.isVisible(), id);
            if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分类不存在，请刷新列表");
            return Map.of("message", "分类已保存");
        } catch (DuplicateKeyException ex) { throw duplicate(); }
    }

    @PutMapping("/admin/categories/order")
    @Transactional
    public Map<String, Object> reorder(@RequestHeader(value="Authorization", required=false) String authorization,
                                       @RequestBody CategoryOrder input) {
        sessions.admin(authorization);
        List<Long> ids = input.ids();
        if (ids == null || ids.size() > 10000 || ids.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(ids).size() != ids.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分类排序格式不正确");
        // Lock the complete set so a concurrent create cannot be silently lost from the ordering.
        List<Long> existing = db.queryForList("select id from dbo.categories with (UPDLOCK,HOLDLOCK) order by id", Long.class);
        if (ids.size() != existing.size() || !new HashSet<>(existing).equals(new HashSet<>(ids)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "分类列表已变化，请刷新后重新排序");
        for (int i = 0; i < ids.size(); i++)
            db.update("update dbo.categories set sort_order=? where id=?", i * 10, ids.get(i));
        return Map.of("message", "分类顺序已保存");
    }

    private CategoryInput validate(CategoryInput input) {
        String name = input.name() == null ? "" : input.name().strip();
        String description = input.description() == null ? "" : input.description().strip();
        if (name.isEmpty() || name.length() > 80 || name.codePoints().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分类名称需为 1–80 个字符，不能包含换行或控制字符");
        if (description.length() > 255)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分类说明不能超过 255 个字符");
        int sortOrder = input.sortOrder() == null ? 0 : input.sortOrder();
        if (sortOrder < 0 || sortOrder > 1000000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "排序数字需为 0–1000000 的整数");
        return new CategoryInput(name, description.isEmpty() ? null : description, sortOrder,
                input.isVisible() == null || input.isVisible());
    }

    private ResponseStatusException duplicate() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "分类名称已存在，请换一个名称");
    }
}
