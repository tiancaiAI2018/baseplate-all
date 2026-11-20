package com.example.baseplate.cend.adapter.in.web;

import com.example.baseplate.metadata.application.MetadataService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/c")
public class CendPublicController {

    private final MetadataService metadata;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CendPublicController(MetadataService metadata, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.metadata = metadata;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/pages/{code}")
    public Map<String, Object> page(@PathVariable String code,
                                    @RequestParam(defaultValue = "default") String tenant) {
        MetadataService.SchemaView view = published(tenant, code);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", view.code());
        result.put("name", view.name());
        result.put("version", view.version());
        result.put("schema", parse(view.schemaJson()));
        result.put("uiSchema", parse(view.uiSchemaJson()));
        return result;
    }

    @PostMapping("/pages/{code}/submit")
    public Map<String, Object> submit(@PathVariable String code,
                                      @RequestParam(defaultValue = "default") String tenant,
                                      @RequestBody SubmitRequest request) {
        published(tenant, code);
        if (request == null || request.payload() == null || request.payload().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提交内容不能为空");
        }
        jdbc.update("INSERT INTO cend_submission(tenant_id, page_code, form_code, payload)"
                        + " VALUES (?, ?, ?, ?::jsonb)",
                tenant, code, request.formCode(), mapper.writeValueAsString(request.payload()));
        return Map.of("ok", true, "pageCode", code);
    }

    private MetadataService.SchemaView published(String tenant, String code) {
        MetadataService.SchemaView view;
        try {
            view = metadata.latest(tenant, "CEND_PAGE", code);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在");
        }
        if (!"PUBLISHED".equals(view.status()) || view.suspended()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "页面未发布");
        }
        return view;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        return mapper.readValue(json, Map.class);
    }

    public record SubmitRequest(String formCode, Map<String, Object> payload) {
    }
}
