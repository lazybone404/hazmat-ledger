-- JWT 撤销用的用户版本号。
--
-- 无状态 token 本身无法撤回；停用账号、改密、改角色时把 token_version 加一，
-- 校验过滤器发现 token 里的 tv 与库里不一致就判 401。旧 token 在过期前自动失效。
-- 默认 0 兼容已有数据。
ALTER TABLE app_user ADD COLUMN token_version INT DEFAULT 0 NOT NULL;
