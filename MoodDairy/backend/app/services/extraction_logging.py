"""
日记提取服务日志配置

提供结构化日志记录功能，包括：
- 关键操作日志
- 错误日志
- 性能指标日志

验证需求: 6.3
"""
import logging
import time
from typing import Optional, Dict, Any
from datetime import datetime
from functools import wraps
import json


# 配置日志格式
LOG_FORMAT = '%(asctime)s - %(name)s - %(levelname)s - [%(filename)s:%(lineno)d] - %(message)s'
DATE_FORMAT = '%Y-%m-%d %H:%M:%S'


def setup_extraction_logger(name: str = "extraction_service", level: int = logging.INFO) -> logging.Logger:
    """
    设置提取服务日志记录器
    
    Args:
        name: 日志记录器名称
        level: 日志级别
        
    Returns:
        logging.Logger: 配置好的日志记录器
    """
    logger = logging.getLogger(name)
    logger.setLevel(level)
    
    # 避免重复添加处理器
    if logger.handlers:
        return logger
    
    # 控制台处理器
    console_handler = logging.StreamHandler()
    console_handler.setLevel(level)
    console_formatter = logging.Formatter(LOG_FORMAT, DATE_FORMAT)
    console_handler.setFormatter(console_formatter)
    logger.addHandler(console_handler)
    
    # 文件处理器
    file_handler = logging.FileHandler('extraction_service.log', encoding='utf-8')
    file_handler.setLevel(level)
    file_formatter = logging.Formatter(LOG_FORMAT, DATE_FORMAT)
    file_handler.setFormatter(file_formatter)
    logger.addHandler(file_handler)
    
    return logger


class ExtractionLogger:
    """提取服务结构化日志记录器"""
    
    def __init__(self, logger: Optional[logging.Logger] = None):
        """
        初始化日志记录器
        
        Args:
            logger: 日志记录器实例，如果为None则创建新的
        """
        self.logger = logger or setup_extraction_logger()
    
    def log_operation(
        self,
        operation: str,
        diary_id: Optional[int] = None,
        user_id: Optional[int] = None,
        status: str = "started",
        details: Optional[Dict[str, Any]] = None
    ) -> None:
        """
        记录关键操作日志
        
        Args:
            operation: 操作名称
            diary_id: 日记ID
            user_id: 用户ID
            status: 操作状态（started/completed/failed）
            details: 额外详情
        """
        log_data = {
            "operation": operation,
            "status": status,
            "timestamp": datetime.now().isoformat()
        }
        
        if diary_id:
            log_data["diary_id"] = diary_id
        if user_id:
            log_data["user_id"] = user_id
        if details:
            log_data["details"] = details
        
        log_message = f"[{operation}] {status}"
        if diary_id:
            log_message += f" - diary_id={diary_id}"
        if user_id:
            log_message += f" - user_id={user_id}"
        
        if status == "failed":
            self.logger.error(f"{log_message} - {json.dumps(log_data, ensure_ascii=False)}")
        else:
            self.logger.info(f"{log_message} - {json.dumps(log_data, ensure_ascii=False)}")
    
    def log_performance(
        self,
        operation: str,
        duration_ms: int,
        diary_id: Optional[int] = None,
        metrics: Optional[Dict[str, Any]] = None
    ) -> None:
        """
        记录性能指标日志
        
        Args:
            operation: 操作名称
            duration_ms: 执行时间（毫秒）
            diary_id: 日记ID
            metrics: 额外性能指标
        """
        log_data = {
            "operation": operation,
            "duration_ms": duration_ms,
            "timestamp": datetime.now().isoformat()
        }
        
        if diary_id:
            log_data["diary_id"] = diary_id
        if metrics:
            log_data["metrics"] = metrics
        
        # 性能警告阈值
        if duration_ms > 30000:  # 30秒
            level = "WARNING"
            self.logger.warning(f"[PERFORMANCE] {operation} took {duration_ms}ms - {json.dumps(log_data, ensure_ascii=False)}")
        elif duration_ms > 10000:  # 10秒
            level = "INFO"
            self.logger.info(f"[PERFORMANCE] {operation} took {duration_ms}ms - {json.dumps(log_data, ensure_ascii=False)}")
        else:
            level = "DEBUG"
            self.logger.debug(f"[PERFORMANCE] {operation} took {duration_ms}ms - {json.dumps(log_data, ensure_ascii=False)}")
    
    def log_error(
        self,
        error: Exception,
        operation: str,
        diary_id: Optional[int] = None,
        context: Optional[Dict[str, Any]] = None
    ) -> None:
        """
        记录错误日志
        
        Args:
            error: 异常对象
            operation: 操作名称
            diary_id: 日记ID
            context: 错误上下文
        """
        log_data = {
            "operation": operation,
            "error_type": type(error).__name__,
            "error_message": str(error),
            "timestamp": datetime.now().isoformat()
        }
        
        if diary_id:
            log_data["diary_id"] = diary_id
        if context:
            log_data["context"] = context
        
        self.logger.error(
            f"[ERROR] {operation} failed - {type(error).__name__}: {str(error)} - "
            f"{json.dumps(log_data, ensure_ascii=False)}",
            exc_info=True
        )
    
    def log_llm_call(
        self,
        diary_id: int,
        status: str,
        duration_ms: Optional[int] = None,
        tokens_used: Optional[int] = None,
        error: Optional[str] = None
    ) -> None:
        """
        记录 LLM 调用日志
        
        Args:
            diary_id: 日记ID
            status: 调用状态（started/completed/failed）
            duration_ms: 执行时间（毫秒）
            tokens_used: 使用的token数
            error: 错误信息
        """
        log_data = {
            "operation": "llm_call",
            "diary_id": diary_id,
            "status": status,
            "timestamp": datetime.now().isoformat()
        }
        
        if duration_ms:
            log_data["duration_ms"] = duration_ms
        if tokens_used:
            log_data["tokens_used"] = tokens_used
        if error:
            log_data["error"] = error
        
        if status == "failed":
            self.logger.error(f"[LLM_CALL] Failed for diary_id={diary_id} - {json.dumps(log_data, ensure_ascii=False)}")
        else:
            self.logger.info(f"[LLM_CALL] {status} for diary_id={diary_id} - {json.dumps(log_data, ensure_ascii=False)}")
    
    def log_schema_validation(
        self,
        diary_id: int,
        status: str,
        errors: Optional[list] = None,
        repaired: bool = False
    ) -> None:
        """
        记录 Schema 验证日志
        
        Args:
            diary_id: 日记ID
            status: 验证状态（passed/failed/repaired）
            errors: 验证错误列表
            repaired: 是否已修复
        """
        log_data = {
            "operation": "schema_validation",
            "diary_id": diary_id,
            "status": status,
            "repaired": repaired,
            "timestamp": datetime.now().isoformat()
        }
        
        if errors:
            log_data["error_count"] = len(errors)
            log_data["errors"] = errors
        
        if status == "failed":
            self.logger.warning(f"[SCHEMA_VALIDATION] Failed for diary_id={diary_id} - {json.dumps(log_data, ensure_ascii=False)}")
        else:
            self.logger.info(f"[SCHEMA_VALIDATION] {status} for diary_id={diary_id} - {json.dumps(log_data, ensure_ascii=False)}")
    
    def log_database_operation(
        self,
        operation: str,
        table: str,
        diary_id: Optional[int] = None,
        status: str = "completed",
        duration_ms: Optional[int] = None,
        error: Optional[str] = None
    ) -> None:
        """
        记录数据库操作日志
        
        Args:
            operation: 操作类型（insert/update/delete/select）
            table: 表名
            diary_id: 日记ID
            status: 操作状态
            duration_ms: 执行时间（毫秒）
            error: 错误信息
        """
        log_data = {
            "operation": f"db_{operation}",
            "table": table,
            "status": status,
            "timestamp": datetime.now().isoformat()
        }
        
        if diary_id:
            log_data["diary_id"] = diary_id
        if duration_ms:
            log_data["duration_ms"] = duration_ms
        if error:
            log_data["error"] = error
        
        if status == "failed":
            self.logger.error(f"[DB_OPERATION] {operation} on {table} failed - {json.dumps(log_data, ensure_ascii=False)}")
        else:
            self.logger.debug(f"[DB_OPERATION] {operation} on {table} {status} - {json.dumps(log_data, ensure_ascii=False)}")
    
    def log_extraction_summary(
        self,
        diary_id: int,
        total_duration_ms: int,
        llm_duration_ms: int,
        status: str,
        error: Optional[str] = None
    ) -> None:
        """
        记录提取任务摘要日志
        
        Args:
            diary_id: 日记ID
            total_duration_ms: 总执行时间（毫秒）
            llm_duration_ms: LLM 调用时间（毫秒）
            status: 最终状态
            error: 错误信息
        """
        log_data = {
            "operation": "extraction_summary",
            "diary_id": diary_id,
            "total_duration_ms": total_duration_ms,
            "llm_duration_ms": llm_duration_ms,
            "llm_percentage": round((llm_duration_ms / total_duration_ms * 100), 2) if total_duration_ms > 0 else 0,
            "status": status,
            "timestamp": datetime.now().isoformat()
        }
        
        if error:
            log_data["error"] = error
        
        if status == "succeeded":
            self.logger.info(f"[EXTRACTION_SUMMARY] Completed for diary_id={diary_id} in {total_duration_ms}ms - {json.dumps(log_data, ensure_ascii=False)}")
        else:
            self.logger.error(f"[EXTRACTION_SUMMARY] Failed for diary_id={diary_id} - {json.dumps(log_data, ensure_ascii=False)}")


def log_execution_time(operation_name: str):
    """
    装饰器：记录函数执行时间
    
    Args:
        operation_name: 操作名称
    """
    def decorator(func):
        @wraps(func)
        async def async_wrapper(*args, **kwargs):
            start_time = time.time()
            logger = logging.getLogger("extraction_service")
            
            try:
                result = await func(*args, **kwargs)
                duration_ms = int((time.time() - start_time) * 1000)
                logger.info(f"[TIMING] {operation_name} completed in {duration_ms}ms")
                return result
            except Exception as e:
                duration_ms = int((time.time() - start_time) * 1000)
                logger.error(f"[TIMING] {operation_name} failed after {duration_ms}ms: {str(e)}")
                raise
        
        @wraps(func)
        def sync_wrapper(*args, **kwargs):
            start_time = time.time()
            logger = logging.getLogger("extraction_service")
            
            try:
                result = func(*args, **kwargs)
                duration_ms = int((time.time() - start_time) * 1000)
                logger.info(f"[TIMING] {operation_name} completed in {duration_ms}ms")
                return result
            except Exception as e:
                duration_ms = int((time.time() - start_time) * 1000)
                logger.error(f"[TIMING] {operation_name} failed after {duration_ms}ms: {str(e)}")
                raise
        
        # 判断是否为异步函数
        import asyncio
        if asyncio.iscoroutinefunction(func):
            return async_wrapper
        else:
            return sync_wrapper
    
    return decorator


# 创建全局日志记录器实例
extraction_logger = ExtractionLogger()
