package com.example.baseplate.platform.application;

import com.example.baseplate.api.model.ExternalToolDescriptor;
import com.example.baseplate.platform.adapter.out.persistence.ExternalServiceCatalogRepository;
import com.example.baseplate.platform.crypto.SecretCipher;
import com.example.baseplate.platform.domain.ExternalEndpoint;
import com.example.baseplate.platform.domain.ExternalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部服务注册表：事实来源是平台目录表，构造期与 {@link #reload()} 时从库里加载活跃服务/端点。
 *
 * <p>凭证只在网关内部解密；SQL 种子里的占位符解密失败时按未配置处理，保证服务可启动。</p>
 */
public final class ExternalServiceRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExternalServiceRegistry.class);

    private final ExternalServiceCatalogRepository repository;
    private final SecretCipher cipher;
    private volatile Map<String, ExternalService> services = Map.of();

    public ExternalServiceRegistry(ExternalServiceCatalogRepository repository, SecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
        reload();
    }

    /** 管理接口写库后刷新快照；copy-on-write，读路径无锁。 */
    public void reload() {
        Map<String, ExternalService> loaded = new LinkedHashMap<>();
        for (ExternalService service : repository.listServices(ExternalServiceCatalogRepository.DEFAULT_TENANT)) {
            loaded.put(service.code(), decryptable(service));
        }
        services = Map.copyOf(loaded);
        log.info("外部服务注册完成: {} 个", services.size());
    }

    public void register(ExternalService service) {
        Map<String, ExternalService> copy = new LinkedHashMap<>(services);
        copy.put(service.code(), decryptable(service));
        services = Map.copyOf(copy);
    }

    public ExternalService find(String serviceCode) {
        return services.get(serviceCode);
    }

    public ExternalEndpoint endpoint(String serviceCode, String endpointCode) {
        ExternalService service = find(serviceCode);
        if (service == null) {
            return null;
        }
        return service.endpoints().stream()
                .filter(item -> item.code().equals(endpointCode))
                .findFirst().orElse(null);
    }

    /** 只含活跃端点：Repository 加载时已按 status 过滤。 */
    public List<ExternalToolDescriptor> descriptors() {
        List<ExternalToolDescriptor> result = new ArrayList<>();
        for (ExternalService service : services.values()) {
            for (ExternalEndpoint endpoint : service.endpoints()) {
                result.add(new ExternalToolDescriptor(
                        endpoint.toolCode(), endpoint.toolName(), endpoint.toolDescription(),
                        service.code(), endpoint.code(), endpoint.readonly(),
                        endpoint.paramsSchema(), endpoint.pages(), endpoint.permissionCode()));
            }
        }
        return result;
    }

    /** 占位符或历史上写坏的密文会让网关调用时抛异常，提前校验并置空。 */
    private ExternalService decryptable(ExternalService service) {
        String credentialEnc = service.credentialEnc();
        if (credentialEnc == null || credentialEnc.isBlank()
                || "NONE".equalsIgnoreCase(service.authType())) {
            return service;
        }
        try {
            cipher.decrypt(credentialEnc);
            return service;
        } catch (RuntimeException e) {
            log.warn("外部服务 {} 的凭证无法解密，按未配置处理", service.code());
            return new ExternalService(service.code(), service.tenantId(), service.baseUrl(),
                    service.authType(), service.credentialHeader(), "", service.endpoints(),
                    service.staticHeaders());
        }
    }
}
