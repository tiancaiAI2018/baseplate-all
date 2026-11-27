package com.example.baseplate.platform.adapter.in.web;

import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.platform.adapter.out.persistence.ExternalServiceCatalogRepository;
import com.example.baseplate.platform.adapter.out.persistence.ExternalServiceCatalogRepository.EndpointInput;
import com.example.baseplate.platform.adapter.out.persistence.ExternalServiceCatalogRepository.ServiceInput;
import com.example.baseplate.platform.application.ExternalServiceRegistry;
import com.example.baseplate.platform.crypto.SecretCipher;
import com.example.baseplate.platform.domain.ExternalServiceView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 外部服务目录控制台：服务与端点的增删改查；凭证明文只进不出。 */
@RestController
@RequestMapping("/api/integration/admin")
public class ExternalServiceAdminController {

    private static final String TENANT = ExternalServiceCatalogRepository.DEFAULT_TENANT;

    private final ExternalServiceCatalogRepository repository;
    private final ExternalServiceRegistry registry;
    private final SecretCipher cipher;

    public ExternalServiceAdminController(ExternalServiceCatalogRepository repository,
                                          ExternalServiceRegistry registry,
                                          SecretCipher cipher) {
        this.repository = repository;
        this.registry = registry;
        this.cipher = cipher;
    }

    @GetMapping("/services")
    @RequiresPermission("integration:service:view")
    public List<ExternalServiceView> services() {
        return repository.listServiceRows(TENANT).stream().map(repository::toView).toList();
    }

    @PostMapping("/services")
    @RequiresPermission("integration:service:edit")
    public Map<String, Object> createService(@RequestBody ServiceRequest request) {
        String code = requireCode(request.code(), "外部服务编码");
        if (repository.findServiceRow(TENANT, code).isPresent()) {
            throw new IllegalArgumentException("外部服务编码已存在：" + code);
        }
        repository.insertService(TENANT, new ServiceInput(
                code, request.name(), request.baseUrl(), request.authType(), request.credentialHeader(),
                encrypt(request.credential()), request.staticHeaders(), request.status()));
        registry.reload();
        return Map.of("code", code, "created", true);
    }

    @PutMapping("/services/{code}")
    @RequiresPermission("integration:service:edit")
    public Map<String, Object> updateService(@PathVariable String code, @RequestBody ServiceRequest request) {
        repository.updateService(TENANT, code, new ServiceInput(
                code, request.name(), request.baseUrl(), request.authType(), request.credentialHeader(),
                encrypt(request.credential()), request.staticHeaders(), request.status()));
        registry.reload();
        return Map.of("code", code, "updated", true);
    }

    @DeleteMapping("/services/{code}")
    @RequiresPermission("integration:service:edit")
    public Map<String, Object> deleteService(@PathVariable String code) {
        repository.deleteService(TENANT, code);
        registry.reload();
        return Map.of("code", code, "deleted", true);
    }

    @PostMapping("/services/{code}/endpoints")
    @RequiresPermission("integration:service:edit")
    public Map<String, Object> createEndpoint(@PathVariable String code, @RequestBody EndpointRequest request) {
        requireService(code);
        String endpointCode = requireCode(request.code(), "端点编码");
        if (repository.findEndpointRow(TENANT, code, endpointCode).isPresent()) {
            throw new IllegalArgumentException("端点编码已存在：" + code + "/" + endpointCode);
        }
        repository.insertEndpoint(TENANT, code, toInput(endpointCode, request));
        registry.reload();
        return Map.of("code", endpointCode, "created", true);
    }

    @PutMapping("/services/{code}/endpoints/{endpointCode}")
    @RequiresPermission("integration:service:edit")
    public Map<String, Object> updateEndpoint(@PathVariable String code,
                                              @PathVariable String endpointCode,
                                              @RequestBody EndpointRequest request) {
        requireService(code);
        repository.updateEndpoint(TENANT, code, endpointCode, toInput(endpointCode, request));
        registry.reload();
        return Map.of("code", endpointCode, "updated", true);
    }

    @DeleteMapping("/services/{code}/endpoints/{endpointCode}")
    @RequiresPermission("integration:service:edit")
    public Map<String, Object> deleteEndpoint(@PathVariable String code, @PathVariable String endpointCode) {
        repository.deleteEndpoint(TENANT, code, endpointCode);
        registry.reload();
        return Map.of("code", endpointCode, "deleted", true);
    }

    private void requireService(String code) {
        if (repository.findServiceRow(TENANT, code).isEmpty()) {
            throw new IllegalArgumentException("外部服务不存在：" + code);
        }
    }

    private static String requireCode(String code, String label) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return code;
    }

    /** 明文凭证落库前加密；编辑时留空表示保持旧值（Repository 用 COALESCE 保留）。 */
    private String encrypt(String credential) {
        return credential == null || credential.isBlank() ? null : cipher.encrypt(credential);
    }

    private static EndpointInput toInput(String code, EndpointRequest request) {
        return new EndpointInput(
                code,
                request.path(),
                request.method() == null || request.method().isBlank()
                        ? "GET" : request.method().toUpperCase(Locale.ROOT),
                request.readonly() == null || request.readonly(),
                request.toolCode(), request.toolName(), request.toolDescription(),
                request.paramsSchema() == null ? Map.of() : request.paramsSchema(),
                request.pages() == null ? List.of() : request.pages(),
                request.permissionCode(), request.status(),
                request.sortNo() == null ? 0 : request.sortNo());
    }

    /**
     * 布尔与数字用包装类型：Jackson 3 在 FAIL_ON_NULL_FOR_PRIMITIVES 下，
     * 请求体缺字段或显式传 null 会直接解析失败。
     */
    public record ServiceRequest(String code, String name, String baseUrl, String authType,
                                 String credentialHeader, String credential,
                                 Map<String, String> staticHeaders, String status) {
    }

    public record EndpointRequest(String code, String path, String method, Boolean readonly,
                                  String toolCode, String toolName, String toolDescription,
                                  Map<String, Object> paramsSchema, List<String> pages,
                                  String permissionCode, String status, Integer sortNo) {
    }
}
