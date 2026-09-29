"""
文档加载器
支持 PDF、DOCX、TXT、CSV 等常见格式
"""
import os
import logging
from typing import List, Dict, Any, Optional
from pathlib import Path

logger = logging.getLogger(__name__)


class DocumentLoader:
    """
    文档加载器
    支持多种文档格式的加载和文本提取
    """
    
    # 支持的文件扩展名
    SUPPORTED_EXTENSIONS = {'.pdf', '.docx', '.doc', '.txt', '.md', '.csv', '.json'}
    
    def __init__(self, chunk_size: int = 500, chunk_overlap: int = 50):
        """
        初始化文档加载器
        
        Args:
            chunk_size: 文本分块大小（字符数）
            chunk_overlap: 分块重叠大小
        """
        self.chunk_size = chunk_size
        self.chunk_overlap = chunk_overlap
        logger.info(f"DocumentLoader 初始化: chunk_size={chunk_size}, overlap={chunk_overlap}")
    
    def load_file(self, file_path: str) -> List[Dict[str, Any]]:
        """
        加载单个文件
        
        Args:
            file_path: 文件路径
            
        Returns:
            分块后的文档列表
        """
        path = Path(file_path)
        if not path.exists():
            logger.error(f"文件不存在: {file_path}")
            return []
        
        ext = path.suffix.lower()
        
        try:
            if ext == '.pdf':
                text = self._load_pdf(file_path)
            elif ext in ('.docx', '.doc'):
                text = self._load_docx(file_path)
            elif ext in ('.txt', '.md'):
                text = self._load_text(file_path)
            elif ext == '.csv':
                text = self._load_csv(file_path)
            elif ext == '.json':
                text = self._load_json(file_path)
            else:
                logger.warning(f"不支持的文件格式: {ext}")
                return []
            
            # 分块
            chunks = self._split_text(text)
            
            # 构建文档对象
            documents = []
            for i, chunk in enumerate(chunks):
                documents.append({
                    'content': chunk,
                    'source': file_path,
                    'filename': path.name,
                    'chunk_index': i,
                    'total_chunks': len(chunks)
                })
            
            logger.info(f"加载文件完成: {path.name}, 分块数: {len(chunks)}")
            return documents
            
        except Exception as e:
            logger.error(f"加载文件失败 {file_path}: {e}")
            return []
    
    def load_directory(self, dir_path: str, recursive: bool = True) -> List[Dict[str, Any]]:
        """
        加载目录下的所有文档
        
        Args:
            dir_path: 目录路径
            recursive: 是否递归加载子目录
            
        Returns:
            所有文档的分块列表
        """
        path = Path(dir_path)
        if not path.exists():
            logger.error(f"目录不存在: {dir_path}")
            return []
        
        all_documents = []
        
        # 获取所有文件
        if recursive:
            files = list(path.rglob('*'))
        else:
            files = list(path.glob('*'))
        
        for file_path in files:
            if file_path.is_file() and file_path.suffix.lower() in self.SUPPORTED_EXTENSIONS:
                documents = self.load_file(str(file_path))
                all_documents.extend(documents)
        
        logger.info(f"目录加载完成: {dir_path}, 总文档块数: {len(all_documents)}")
        return all_documents
    
    def _load_pdf(self, file_path: str) -> str:
        """加载 PDF 文件"""
        try:
            import fitz  # PyMuPDF
            
            doc = fitz.open(file_path)
            text_parts = []
            
            for page in doc:
                text = page.get_text()
                if text.strip():
                    text_parts.append(text)
            
            doc.close()
            return '\n\n'.join(text_parts)
            
        except ImportError:
            logger.error("请安装 PyMuPDF: pip install pymupdf")
            raise
    
    def _load_docx(self, file_path: str) -> str:
        """加载 DOCX 文件"""
        try:
            from docx import Document
            
            doc = Document(file_path)
            text_parts = []
            
            for para in doc.paragraphs:
                if para.text.strip():
                    text_parts.append(para.text)
            
            return '\n\n'.join(text_parts)
            
        except ImportError:
            logger.error("请安装 python-docx: pip install python-docx")
            raise
    
    def _load_text(self, file_path: str) -> str:
        """加载文本文件"""
        with open(file_path, 'r', encoding='utf-8') as f:
            return f.read()
    
    def _load_csv(self, file_path: str) -> str:
        """加载 CSV 文件"""
        try:
            import pandas as pd
            
            df = pd.read_csv(file_path)
            # 将每行转换为文本
            rows = []
            for _, row in df.iterrows():
                row_text = ' | '.join([f"{col}: {val}" for col, val in row.items()])
                rows.append(row_text)
            
            return '\n'.join(rows)
            
        except ImportError:
            logger.error("请安装 pandas: pip install pandas")
            raise
    
    def _load_json(self, file_path: str) -> str:
        """加载 JSON 文件"""
        import json
        
        with open(file_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        # 递归提取所有文本内容
        def extract_text(obj, prefix=''):
            texts = []
            if isinstance(obj, dict):
                for key, value in obj.items():
                    texts.extend(extract_text(value, f"{prefix}{key}: "))
            elif isinstance(obj, list):
                for i, item in enumerate(obj):
                    texts.extend(extract_text(item, f"{prefix}[{i}] "))
            else:
                texts.append(f"{prefix}{obj}")
            return texts
        
        return '\n'.join(extract_text(data))
    
    def _split_text(self, text: str) -> List[str]:
        """
        将文本分割成块
        使用重叠以保持上下文连贯性
        """
        if not text:
            return []
        
        # 按段落分割
        paragraphs = text.split('\n\n')
        
        chunks = []
        current_chunk = ""
        
        for para in paragraphs:
            para = para.strip()
            if not para:
                continue
            
            # 如果当前块加上新段落不超过限制，则添加
            if len(current_chunk) + len(para) + 2 <= self.chunk_size:
                if current_chunk:
                    current_chunk += "\n\n" + para
                else:
                    current_chunk = para
            else:
                # 保存当前块
                if current_chunk:
                    chunks.append(current_chunk)
                
                # 如果段落本身超过限制，需要进一步分割
                if len(para) > self.chunk_size:
                    # 按句子分割
                    sentences = para.replace('。', '。\n').replace('！', '！\n').replace('？', '？\n').split('\n')
                    current_chunk = ""
                    
                    for sentence in sentences:
                        sentence = sentence.strip()
                        if not sentence:
                            continue
                        
                        if len(current_chunk) + len(sentence) + 1 <= self.chunk_size:
                            if current_chunk:
                                current_chunk += " " + sentence
                            else:
                                current_chunk = sentence
                        else:
                            if current_chunk:
                                chunks.append(current_chunk)
                            current_chunk = sentence
                else:
                    current_chunk = para
        
        # 添加最后一块
        if current_chunk:
            chunks.append(current_chunk)
        
        return chunks
