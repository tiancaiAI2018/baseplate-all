package com.example.baseplate.workflow.adapter.in.web;

import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.workflow.application.WorkflowDraftService;
import com.example.baseplate.workflow.application.WorkflowDraftService.CreateRequest;
import com.example.baseplate.workflow.application.WorkflowDraftService.DraftView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 流程草稿：供 AI 工具创建、供设计器读取并加载到画布。 */
@RestController
@RequestMapping("/api/workflow/drafts")
public class WorkflowDraftController {

    private final WorkflowDraftService draftService;
    private final CurrentUserProvider currentUser;

    public WorkflowDraftController(WorkflowDraftService draftService,
                                   CurrentUserProvider currentUser) {
        this.draftService = draftService;
        this.currentUser = currentUser;
    }

    @PostMapping
    @RequiresPermission("wf:deploy")
    public DraftView create(@RequestBody CreateRequest request) {
        CurrentUser user = currentUser.current();
        return draftService.create(tenant(user), user == null ? null : user.userId(), request);
    }

    @GetMapping
    @RequiresPermission("wf:view")
    public List<DraftView> list(@RequestParam(defaultValue = "20") int limit) {
        return draftService.list(tenant(currentUser.current()), limit);
    }

    @GetMapping("/{id}")
    @RequiresPermission("wf:view")
    public DraftView get(@PathVariable long id) {
        return draftService.get(tenant(currentUser.current()), id);
    }

    private String tenant(CurrentUser user) {
        return user == null || user.tenantId() == null || user.tenantId().isBlank()
                ? "default" : user.tenantId();
    }
}
