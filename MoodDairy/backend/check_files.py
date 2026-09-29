"""
检查 uploads 目录中的文件
"""
import os
from pathlib import Path

uploads_dir = Path("./uploads")

print(f"检查目录: {uploads_dir.absolute()}")
print(f"目录是否存在: {uploads_dir.exists()}")
print()

if uploads_dir.exists():
    print("目录结构:")
    for root, dirs, files in os.walk(uploads_dir):
        level = root.replace(str(uploads_dir), '').count(os.sep)
        indent = ' ' * 2 * level
        print(f'{indent}{os.path.basename(root)}/')
        subindent = ' ' * 2 * (level + 1)
        for file in files:
            file_path = Path(root) / file
            file_size = file_path.stat().st_size
            print(f'{subindent}{file} ({file_size} bytes)')
    
    print("\n查找今天的音频文件:")
    audio_dir = uploads_dir / "2026" / "01" / "22" / "audio"
    if audio_dir.exists():
        print(f"音频目录存在: {audio_dir}")
        files = list(audio_dir.glob("*.m4a"))
        print(f"找到 {len(files)} 个 .m4a 文件:")
        for f in files:
            print(f"  - {f.name} ({f.stat().st_size} bytes)")
    else:
        print(f"音频目录不存在: {audio_dir}")
else:
    print("uploads 目录不存在！")
