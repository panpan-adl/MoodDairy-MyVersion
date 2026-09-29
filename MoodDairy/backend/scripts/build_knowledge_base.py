#!/usr/bin/env python3
"""
知识库构建脚本
从 knowledge_base 目录加载文档，生成向量并保存到 FAISS
"""
import os
import sys
import asyncio
import logging

# 添加项目根目录到路径
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.rag.document_loader import DocumentLoader
from app.rag.embedding_service import get_embedding_service
from app.rag.faiss_store import FAISSStore

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# 路径配置
KNOWLEDGE_BASE_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "knowledge_base")
FAISS_INDEX_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)), "app", "data", "faiss_index")


async def build_knowledge_base():
    """构建知识库"""
    logger.info("=" * 50)
    logger.info("开始构建知识库")
    logger.info("=" * 50)
    
    # 1. 加载文档
    logger.info(f"知识库目录: {KNOWLEDGE_BASE_DIR}")
    
    if not os.path.exists(KNOWLEDGE_BASE_DIR):
        logger.error(f"知识库目录不存在: {KNOWLEDGE_BASE_DIR}")
        return False
    
    loader = DocumentLoader(chunk_size=500, chunk_overlap=50)
    documents = loader.load_directory(KNOWLEDGE_BASE_DIR)
    
    if not documents:
        logger.warning("没有加载到任何文档")
        return False
    
    logger.info(f"加载文档块数: {len(documents)}")
    
    # 2. 生成向量
    logger.info("开始生成向量...")
    
    embedding_service = get_embedding_service()
    
    # 测试 embedding 服务
    try:
        test_dim = await embedding_service.get_dimension()
        logger.info(f"Embedding 维度: {test_dim}")
    except Exception as e:
        logger.error(f"Embedding 服务测试失败: {e}")
        logger.error("请确保 Ollama 服务已启动并已下载 embeddinggemma 模型")
        logger.error("运行: ollama pull embeddinggemma")
        return False
    
    # 提取文本内容
    texts = [doc['content'] for doc in documents]
    
    # 批量生成向量
    embeddings = await embedding_service.embed_texts(texts, batch_size=5)
    
    # 检查结果
    valid_pairs = []
    for doc, emb in zip(documents, embeddings):
        if emb and not all(v == 0.0 for v in emb):
            valid_pairs.append((doc, emb))
    
    logger.info(f"有效向量数: {len(valid_pairs)}/{len(documents)}")
    
    if not valid_pairs:
        logger.error("没有生成有效的向量")
        return False
    
    # 3. 存储到 FAISS
    logger.info(f"保存到 FAISS: {FAISS_INDEX_PATH}")
    
    dimension = len(valid_pairs[0][1])
    faiss_store = FAISSStore(dimension=dimension, index_path=FAISS_INDEX_PATH)
    
    docs = [pair[0] for pair in valid_pairs]
    embs = [pair[1] for pair in valid_pairs]
    
    faiss_store.add_documents(embs, docs)
    faiss_store.save()
    
    logger.info("=" * 50)
    logger.info(f"知识库构建完成！共 {len(valid_pairs)} 个文档块")
    logger.info("=" * 50)
    
    return True


async def test_search():
    """测试检索"""
    logger.info("\n测试检索...")
    
    from app.rag.faiss_store import get_faiss_store
    from app.rag.embedding_service import get_embedding_service
    
    faiss_store = get_faiss_store()
    embedding_service = get_embedding_service()
    
    if faiss_store.count == 0:
        logger.warning("索引为空，请先运行 build 命令")
        return
    
    # 测试查询
    test_queries = [
        "如何管理情绪？",
        "写日记有什么好处？",
        "怎么减轻压力？"
    ]
    
    for query in test_queries:
        logger.info(f"\n查询: {query}")
        embedding = await embedding_service.embed_text(query)
        results = faiss_store.search(embedding, top_k=3)
        
        for i, (doc, score) in enumerate(results, 1):
            logger.info(f"  [{i}] 分数: {score:.4f}, 来源: {doc.get('filename', '未知')}")
            logger.info(f"      内容: {doc.get('content', '')[:100]}...")


def main():
    """主函数"""
    import argparse
    
    parser = argparse.ArgumentParser(description="知识库构建工具")
    parser.add_argument(
        "command",
        choices=["build", "test"],
        default="build",
        nargs="?",
        help="命令: build=构建知识库, test=测试检索"
    )
    
    args = parser.parse_args()
    
    if args.command == "build":
        success = asyncio.run(build_knowledge_base())
        sys.exit(0 if success else 1)
    elif args.command == "test":
        asyncio.run(test_search())


if __name__ == "__main__":
    main()
