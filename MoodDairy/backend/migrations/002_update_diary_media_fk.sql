-- 迁移：更新 diary_media.asset_id 外键从 media_uploads 到 media_files
-- 日期：2026-01-24
-- 原因：voice service 现在使用 media_files 表（OSS 存储），不再使用 media_uploads

-- 步骤 1：删除旧的外键约束
ALTER TABLE diary_media 
DROP CONSTRAINT IF EXISTS diary_media_asset_id_fkey;

-- 步骤 2：添加新的外键约束指向 media_files
ALTER TABLE diary_media 
ADD CONSTRAINT diary_media_asset_id_fkey 
FOREIGN KEY (asset_id) 
REFERENCES media_files(id) 
ON DELETE SET NULL;

-- 验证：检查外键约束
SELECT 
    tc.constraint_name, 
    tc.table_name, 
    kcu.column_name, 
    ccu.table_name AS foreign_table_name,
    ccu.column_name AS foreign_column_name 
FROM information_schema.table_constraints AS tc 
JOIN information_schema.key_column_usage AS kcu
  ON tc.constraint_name = kcu.constraint_name
  AND tc.table_schema = kcu.table_schema
JOIN information_schema.constraint_column_usage AS ccu
  ON ccu.constraint_name = tc.constraint_name
  AND ccu.table_schema = tc.table_schema
WHERE tc.constraint_type = 'FOREIGN KEY' 
  AND tc.table_name='diary_media'
  AND kcu.column_name='asset_id';
