# TMAGIC RUNTIME

## OVERVIEW
tmagic 页面运行时独立入口，与主控制台分开打包：`page.html/page.ts/page.vue` 渲染已发布页面，`playground.*` 用于设计预览。

## STRUCTURE
```
tmagic-runtime/
├── page.html / page.ts / page.vue        # 正式渲染入口
├── playground.html / playground.ts / playground.vue
├── animations.css
└── components/PhoneInput.vue、ShareButton.vue
```

## BEHAVIOR
- 从 URL 取 `code`、`tenant`、可选 `page`，请求 `/api/c/pages/{code}?tenant=...`。
- 响应里的 `uiSchema` 就是 DSL（可能包在 `{dsl}` 中，`unwrap` 处理）。
- 通过 `TMagicApp` 注册 `text/button/img/container/page` 与自定义 `PhoneInput`/`ShareButton`。
- 设计宽度按视口宽度设置。

## CONVENTIONS
- 该目录是 Vite 独立入口（见 `vite.config.ts` 的 `input`），改动后 `npm run build` 必须同时产出 page/playground。
- 自定义组件放 `components/` 并在此注册。
- 渲染失败给出可见错误（页面不存在/未发布）。

## ANTI-PATTERNS
- 在这里写控制台业务逻辑。
- 改 DSL 结构不更新 `components/cend/cendDsl.ts` 与 `types/cend.ts`。
