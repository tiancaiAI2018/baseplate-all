package com.example.baseplate.agent.adapter.in.web;

import com.example.baseplate.agent.application.SkillIndexService;
import com.example.baseplate.agent.runtime.AgentRuntimeFactory;
import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能管理：索引、在线编辑、zip / 文件夹导入。
 *
 * <p>技能正文始终落在本地 skills 目录，数据库只维护索引；
 * 因此这里提供的是「文件编辑」而不是「数据库 CRUD」。</p>
 */
@RestController
@RequestMapping("/api/agent/skills")
public class AgentSkillController {

    private final SkillIndexService skillIndexService;
    private final AgentRuntimeFactory runtimeFactory;
    private final CurrentUserProvider currentUser;

    public AgentSkillController(SkillIndexService skillIndexService,
                                AgentRuntimeFactory runtimeFactory,
                                CurrentUserProvider currentUser) {
        this.skillIndexService = skillIndexService;
        this.runtimeFactory = runtimeFactory;
        this.currentUser = currentUser;
    }

    public record SkillCreateRequest(String skillName, String description, String content) {
    }

    public record SkillUpdateRequest(String content) {
    }

    @GetMapping
    @RequiresPermission("agent:config:view")
    public List<SkillIndexService.SkillView> list() {
        return skillIndexService.list(tenant());
    }

    @PostMapping("/rescan")
    @RequiresPermission("agent:config:edit")
    public Map<String, Object> rescan() {
        int count = skillIndexService.scan();
        // 目录内容变了，旧 Agent 实例里缓存的技能快照必须失效
        runtimeFactory.invalidateAll();
        return Map.of("scanned", count);
    }

    @GetMapping("/{skillName}")
    @RequiresPermission("agent:config:view")
    public SkillIndexService.SkillDetail detail(@PathVariable String skillName) {
        return skillIndexService.detail(tenant(), skillName);
    }

    @PostMapping
    @RequiresPermission("agent:config:edit")
    public SkillIndexService.SkillDetail create(@RequestBody SkillCreateRequest request) {
        SkillIndexService.SkillDetail detail = skillIndexService.create(tenant(),
                request.skillName(), request.description(), request.content());
        runtimeFactory.invalidateAll();
        return detail;
    }

    @PutMapping("/{skillName}")
    @RequiresPermission("agent:config:edit")
    public SkillIndexService.SkillDetail update(@PathVariable String skillName,
                                                @RequestBody SkillUpdateRequest request) {
        SkillIndexService.SkillDetail detail = skillIndexService.update(tenant(), skillName,
                request.content());
        runtimeFactory.invalidateAll();
        return detail;
    }

    @DeleteMapping("/{skillName}")
    @RequiresPermission("agent:config:edit")
    public Map<String, Object> delete(@PathVariable String skillName) {
        skillIndexService.delete(tenant(), skillName);
        runtimeFactory.invalidateAll();
        return Map.of("deleted", true);
    }

    /** zip 导入：一个压缩包里可以放一个或多个技能目录。 */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission("agent:config:edit")
    public Map<String, Object> uploadZip(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择 zip 文件");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("只支持 .zip 压缩包");
        }
        int count = skillIndexService.importZip(name, readBytes(file));
        runtimeFactory.invalidateAll();
        return Map.of("imported", count);
    }

    /** 文件夹导入：前端传相对路径，服务端按「技能目录/文件」原样落盘。 */
    @PostMapping(value = "/upload-folder", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission("agent:config:edit")
    public Map<String, Object> uploadFolder(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "paths", required = false) List<String> paths) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("没有收到文件");
        }
        Map<String, byte[]> contents = new LinkedHashMap<>();
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            String relative = paths != null && index < paths.size()
                    ? paths.get(index) : file.getOriginalFilename();
            if (relative == null || relative.isBlank()) {
                continue;
            }
            contents.put(relative, readBytes(file));
        }
        int count = skillIndexService.importFiles(contents);
        runtimeFactory.invalidateAll();
        return Map.of("imported", count);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件失败：" + e.getMessage(), e);
        }
    }

    private String tenant() {
        CurrentUser user = currentUser.current();
        return user == null || user.tenantId() == null || user.tenantId().isBlank()
                ? "default" : user.tenantId();
    }
}
