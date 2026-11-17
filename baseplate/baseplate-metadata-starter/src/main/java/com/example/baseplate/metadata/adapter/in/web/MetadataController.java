package com.example.baseplate.metadata.adapter.in.web;

import com.example.baseplate.api.annotation.AuditLog;
import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.metadata.application.MetadataService;
import org.springframework.web.bind.annotation.GetMapping;import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** 低代码配置入口：表单 / 列表 / 流程三类的保存、发布、渲染。 */
@RestController
@RequestMapping("/api/metadata")
public class MetadataController {

    private final MetadataService service;
    private final ObjectMapper objectMapper;

    public MetadataController(MetadataService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{kind}")
    @RequiresPermission("meta:view")
    public List<MetadataService.SchemaView> list(@PathVariable String kind) {
        return service.list(kind);
    }

    @GetMapping("/{kind}/{code}")
    @RequiresPermission("meta:view")
    public MetadataService.SchemaView latest(@PathVariable String kind, @PathVariable String code) {
        return service.latest(kind, code);
    }

    @GetMapping("/{kind}/{code}/render")
    @RequiresPermission("meta:view")
    public Map<String, Object> render(@PathVariable String kind, @PathVariable String code) {
        return service.render(kind, code);
    }

    @PostMapping("/{kind}/{code}")
    @RequiresPermission("meta:edit")
    @AuditLog(action = "meta:save-draft", resource = "meta_schema")
    public MetadataService.SchemaView save(@PathVariable String kind, @PathVariable String code,
                                           @RequestBody SaveRequest request) {
        return service.saveDraft(kind, code, request.name(),
                json(request.schema()), json(request.uiSchema()));
    }

    @PostMapping("/{kind}/{code}/publish")
    @RequiresPermission("meta:publish")
    @AuditLog(action = "meta:publish", resource = "meta_schema")
    public MetadataService.SchemaView publish(@PathVariable String kind, @PathVariable String code,
                                              @RequestParam int version) {
        return service.publish(kind, code, version);
    }

    /**
     * schema / uiSchema 既接受真正的 JSON 结构，也接受已经序列化过的字符串。
     * 前端把设计器结果直接当对象发过来最自然；要求调用方再 JSON.stringify 一遍是坑，
     * 一旦发成对象就会 500，且报错指向"解析失败"而不是"字段类型不对"。
     */
    private String json(Object value) {
        if (value == null) return null;
        if (value instanceof String text) return text;
        return objectMapper.writeValueAsString(value);
    }

    public record SaveRequest(String name, Object schema, Object uiSchema) {
    }
}
