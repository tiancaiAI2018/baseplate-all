package com.example.baseplate.platform.application;

import com.example.baseplate.api.model.ExternalToolDescriptor;
import com.example.baseplate.platform.crypto.SecretCipher;
import com.example.baseplate.platform.domain.ExternalEndpoint;
import com.example.baseplate.platform.domain.ExternalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 外部服务注册表（最小可跑版：内存存储）。
 *
 * <p>正式版应落库并接控制台配置页；这里先把 loan-agent-mock 作为种子服务注入，
 * 用于验证「平台注册 → Agent 工具投影 → 网关调用」整条链路。</p>
 */
public final class ExternalServiceRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExternalServiceRegistry.class);
    private final Map<String, ExternalService> services = new LinkedHashMap<>();

    public ExternalServiceRegistry(SecretCipher cipher) {
        register(seedLoanMock(cipher));
        log.info("外部服务注册完成: {} 个", services.size());
    }

    public void register(ExternalService service) {
        services.put(service.code(), service);
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

    private static ExternalService seedLoanMock(SecretCipher cipher) {
        Map<String, Object> catalogSchema = Map.of(
                "type", "object",
                "properties", Map.of("category", Map.of("type", "string", "description", "车辆类别，如 NEW_ENERGY")),
                "required", List.of());
        Map<String, Object> draftSchema = Map.of(
                "type", "object",
                "properties", Map.of("draft", Map.of("type", "object", "description", "完整申请草稿 JSON")),
                "required", List.of("draft"));
        Map<String, Object> submitSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "draftId", Map.of("type", "string", "description", "草稿 id"),
                        "idempotencyKey", Map.of("type", "string", "description", "幂等键")),
                "required", List.of("draftId"));
        Map<String, Object> emptySchema = Map.of(
                "type", "object", "properties", Map.of(), "required", List.of());
        Map<String, Object> materialGetSchema = Map.of(
                "type", "object",
                "properties", Map.of("id", Map.of("type", "string", "description", "待分析资料 id")),
                "required", List.of("id"));
        Map<String, Object> schemaQuery = Map.of(
                "type", "object",
                "properties", Map.of("productType", Map.of("type", "string", "description", "产品编码")),
                "required", List.of("productType"));
        Map<String, Object> prepareSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "materialIds", Map.of("type", "array", "items", Map.of("type", "string"),
                                "description", "待分析资料 id 列表，如 MAT-XXXX"),
                        "productType", Map.of("type", "string", "description", "产品编码，可不传由服务端推断")),
                "required", List.of("materialIds"));
        Set<String> pages = Set.of("*");

        List<ExternalEndpoint> endpoints = List.of(
                new ExternalEndpoint("draft.prepare", "/api/draft/prepare", "POST", true,
                        "loan.draft.prepare", "抽取申请草稿骨架",
                        "按资料 id 确定性抽取申请草稿骨架：字典编码已填好，返回 pendingFields 与 conflicts；先调它再补字段",
                        prepareSchema, pages, "agent:chat"),
                new ExternalEndpoint("products", "/api/meta/products", "GET", true,
                        "loan.products", "贷款产品目录", "列出合法的产品类型编码与规则；必须先查它再选 productType",
                        emptySchema, pages, "agent:chat"),
                new ExternalEndpoint("schema", "/api/meta/form-schema", "GET", true,
                        "loan.schema", "表单字段 Schema", "按产品编码取字段清单、字典与联动规则",
                        schemaQuery, pages, "agent:chat"),
                new ExternalEndpoint("materials.list", "/api/materials", "GET", true,
                        "loan.materials.list", "待分析资料列表", "列出用户从微信/相册上传的待分析资料",
                        emptySchema, pages, "agent:chat"),
                new ExternalEndpoint("materials.get", "/api/materials/{id}", "GET", true,
                        "loan.materials.get", "待分析资料详情", "按 id 读取一份待分析资料的文本与图片信息",
                        materialGetSchema, pages, "agent:chat"),
                new ExternalEndpoint("catalog", "/api/meta/vehicle-tree", "GET", true,
                        "loan.catalog", "车贷车型目录", "按车辆类别查询品牌/车系/车型/年款树",
                        catalogSchema, pages, "agent:chat"),
                new ExternalEndpoint("validate", "/api/validate", "POST", true,
                        "loan.validate", "车贷草稿预检", "校验车贷草稿，返回字段级错误；不写数据",
                        draftSchema, pages, "agent:chat"),
                new ExternalEndpoint("draft.save", "/api/drafts", "POST", false,
                        "loan.draft.save", "保存车贷草稿", "把车贷申请草稿保存到第三方服务（需用户确认）",
                        draftSchema, pages, "agent:chat"),
                new ExternalEndpoint("submit", "/api/drafts/{draftId}/submit", "POST", false,
                        "loan.submit", "提交车贷申请", "最终提交车贷申请，必须带幂等键（需用户确认）",
                        submitSchema, pages, "agent:chat"));

        return new ExternalService(
                "loan-mock", "default", "http://localhost:8099",
                "API_KEY", "X-Api-Key", cipher.encrypt("loan-mock-demo-key"),
                endpoints, Map.of());
    }
}
