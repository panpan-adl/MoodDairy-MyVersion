-- 授予 app_user 在 public schema 下的核心读写权限
-- 适用场景：表由其他用户创建，app_user 无法访问导致 500/权限错误

BEGIN;

-- 1) schema 访问权限
GRANT USAGE ON SCHEMA public TO "app_user";

-- 2) 现有表权限
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO "app_user";

-- 3) 现有序列权限（自增 ID）
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO "app_user";

-- 4) 未来新建对象默认权限（需由对象所有者执行）
ALTER DEFAULT PRIVILEGES IN SCHEMA public
GRANT ALL PRIVILEGES ON TABLES TO "app_user";

ALTER DEFAULT PRIVILEGES IN SCHEMA public
GRANT ALL PRIVILEGES ON SEQUENCES TO "app_user";

COMMIT;

-- 可选验证：
-- SELECT grantee, table_name, privilege_type
-- FROM information_schema.role_table_grants
-- WHERE table_schema = 'public' AND grantee = 'app_user'
-- ORDER BY table_name, privilege_type;
