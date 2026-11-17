-- 老应用补齐默认 API 授权（幂等；不覆盖管理员后续手工取消的授权之外的新增）
INSERT INTO open_app_api(app_id, api_code)
SELECT a.id, c.code
FROM open_app a
CROSS JOIN (VALUES ('openapi.ping'), ('openapi.whoami'), ('openapi.echo')) AS c(code)
ON CONFLICT DO NOTHING;
