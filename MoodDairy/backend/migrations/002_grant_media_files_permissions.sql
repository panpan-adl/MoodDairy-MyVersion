-- 授予 app_user 用户对 media_files 表的所有权限
-- Grant permissions for media_files table to app_user

-- 授予表权限
GRANT ALL PRIVILEGES ON TABLE public.media_files TO "app_user";

-- 授予序列权限（用于自增 ID）
GRANT ALL PRIVILEGES ON SEQUENCE public.media_files_id_seq TO "app_user";

-- 验证权限
SELECT 
    grantee,
    table_schema,
    table_name,
    privilege_type
FROM information_schema.table_privileges
WHERE table_name = 'media_files'
AND grantee = 'app_user';
