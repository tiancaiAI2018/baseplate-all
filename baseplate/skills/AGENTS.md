# AGENT SKILLS

## OVERVIEW
智能体技能目录。每个子目录一个技能，入口固定为 `SKILL.md`，由 `SkillIndexService` 扫描建索引，可在控制台在线编辑 / zip 导入 / 绑定 Agent。

## FORMAT
```
skills/<技能名>/SKILL.md   # 必需，含名称、适用场景、步骤/约束
skills/<技能名>/...         # 可选脚本与素材
```

## CONVENTIONS
- `SKILL.md` 写清「何时用、怎么做、禁止什么」，面向模型可执行。
- 技能名用中文或稳定短名，目录重命名会改变索引键。
- 引用的脚本/文件放在同目录，使用相对路径。
- 新增/改名后同步 `docs/04-api.md`（如涉及接口）。

## ANTI-PATTERNS
- 在技能里写密钥、令牌、内网地址。
- 依赖仓库外绝对路径。
- 技能描述与 `SKILL.md` 内容不一致（索引读的是文件）。
