---
name: 表单设计
description: 用户要为流程设计表单、增加字段或调整字段类型时使用。产出字段清单并保存为草稿
version: 1
---

# 表单设计

## 什么时候用

- 「设计一个请假申请表单」「报销单要有哪些字段」
- 「给这个表单加一个附件上传」

## 怎么做

1. 要改已有表单时先调用 `form_get_draft`，拿到现有字段和规则
2. 把需求整理成字段清单：
   - 字段名用小写英文＋下划线，例如 `leave_days`
   - 类型只用 input / textarea / number / slider / date / datetime / switch / select / radio / checkbox / upload / editor
   - select / radio / checkbox 必须给 options
3. 调用 `form_create_draft`，前端会打开表单设计器
4. 只说到「草稿已打开」为止，发布由用户点「保存并发布」

## 边界

- 字段名不能使用 script / eval / expression / class 这类保留词
- 工具返回失败时，按失败原因修正参数后重试，不要直接结束回复
