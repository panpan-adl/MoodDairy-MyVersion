"""
日记提取服务专用错误类

定义提取过程中可能出现的各种错误类型
验证需求: 6.1, 6.2
"""
from typing import Optional, Dict, Any
from fastapi import status


class ExtractionError(Exception):
    """提取错误基类"""
    
    def __init__(
        self,
        message: str,
        error_code: str,
        diary_id: Optional[int] = None,
        details: Optional[Dict[str, Any]] = None
    ):
        self.message = message
        self.error_code = error_code
        self.diary_id = diary_id
        self.details = details or {}
        super().__init__(self.message)
    
    def to_dict(self) -> Dict[str, Any]:
        """转换为字典格式"""
        result = {
            "error": self.message,
            "error_code": self.error_code,
        }
        if self.diary_id:
            result["diary_id"] = self.diary_id
        if self.details:
            result["details"] = self.details
        return result


class LLMAPIError(ExtractionError):
    """LLM API 调用错误"""
    
    def __init__(
        self,
        message: str,
        diary_id: Optional[int] = None,
        api_response: Optional[Dict[str, Any]] = None,
        retry_count: int = 0
    ):
        details = {
            "retry_count": retry_count
        }
        if api_response:
            details["api_response"] = api_response
        
        super().__init__(
            message=message,
            error_code="LLM_API_ERROR",
            diary_id=diary_id,
            details=details
        )


class LLMTimeoutError(LLMAPIError):
    """LLM API 超时错误"""
    
    def __init__(
        self,
        diary_id: Optional[int] = None,
        timeout_seconds: int = 30
    ):
        super().__init__(
            message=f"LLM API 调用超时（{timeout_seconds}秒）",
            diary_id=diary_id
        )
        self.error_code = "LLM_TIMEOUT_ERROR"
        self.details["timeout_seconds"] = timeout_seconds


class LLMRateLimitError(LLMAPIError):
    """LLM API 速率限制错误"""
    
    def __init__(
        self,
        diary_id: Optional[int] = None,
        retry_after: Optional[int] = None
    ):
        message = "LLM API 速率限制"
        if retry_after:
            message += f"，请在 {retry_after} 秒后重试"
        
        super().__init__(
            message=message,
            diary_id=diary_id
        )
        self.error_code = "LLM_RATE_LIMIT_ERROR"
        if retry_after:
            self.details["retry_after"] = retry_after


class SchemaValidationError(ExtractionError):
    """Schema 验证错误"""
    
    def __init__(
        self,
        message: str,
        diary_id: Optional[int] = None,
        validation_errors: Optional[list] = None,
        invalid_data: Optional[Dict[str, Any]] = None
    ):
        details = {}
        if validation_errors:
            details["validation_errors"] = validation_errors
        if invalid_data:
            details["invalid_data"] = invalid_data
        
        super().__init__(
            message=message,
            error_code="SCHEMA_VALIDATION_ERROR",
            diary_id=diary_id,
            details=details
        )


class SchemaRepairError(ExtractionError):
    """Schema 修复失败错误"""
    
    def __init__(
        self,
        message: str,
        diary_id: Optional[int] = None,
        original_errors: Optional[list] = None,
        repair_attempts: int = 0
    ):
        details = {
            "repair_attempts": repair_attempts
        }
        if original_errors:
            details["original_errors"] = original_errors
        
        super().__init__(
            message=message,
            error_code="SCHEMA_REPAIR_ERROR",
            diary_id=diary_id,
            details=details
        )


class DatabaseOperationError(ExtractionError):
    """数据库操作错误"""
    
    def __init__(
        self,
        message: str,
        diary_id: Optional[int] = None,
        operation: Optional[str] = None,
        original_error: Optional[Exception] = None
    ):
        details = {}
        if operation:
            details["operation"] = operation
        if original_error:
            details["original_error"] = str(original_error)
            details["error_type"] = type(original_error).__name__
        
        super().__init__(
            message=message,
            error_code="DATABASE_OPERATION_ERROR",
            diary_id=diary_id,
            details=details
        )


class DatabaseConnectionError(DatabaseOperationError):
    """数据库连接错误"""
    
    def __init__(
        self,
        diary_id: Optional[int] = None,
        original_error: Optional[Exception] = None
    ):
        super().__init__(
            message="数据库连接失败",
            diary_id=diary_id,
            operation="connect",
            original_error=original_error
        )
        self.error_code = "DATABASE_CONNECTION_ERROR"


class DatabaseIntegrityError(DatabaseOperationError):
    """数据库完整性错误"""
    
    def __init__(
        self,
        message: str,
        diary_id: Optional[int] = None,
        constraint: Optional[str] = None,
        original_error: Optional[Exception] = None
    ):
        super().__init__(
            message=message,
            diary_id=diary_id,
            operation="integrity_check",
            original_error=original_error
        )
        self.error_code = "DATABASE_INTEGRITY_ERROR"
        if constraint:
            self.details["constraint"] = constraint


class ConcurrencyConflictError(ExtractionError):
    """并发冲突错误"""
    
    def __init__(
        self,
        diary_id: int,
        current_status: str,
        attempted_operation: str
    ):
        super().__init__(
            message=f"并发冲突：日记 {diary_id} 当前状态为 {current_status}，无法执行 {attempted_operation}",
            error_code="CONCURRENCY_CONFLICT_ERROR",
            diary_id=diary_id,
            details={
                "current_status": current_status,
                "attempted_operation": attempted_operation
            }
        )


class DiaryNotFoundError(ExtractionError):
    """日记不存在错误"""
    
    def __init__(self, diary_id: int):
        super().__init__(
            message=f"日记不存在: {diary_id}",
            error_code="DIARY_NOT_FOUND",
            diary_id=diary_id
        )


class ContentHashError(ExtractionError):
    """内容哈希计算错误"""
    
    def __init__(
        self,
        diary_id: int,
        original_error: Optional[Exception] = None
    ):
        details = {}
        if original_error:
            details["original_error"] = str(original_error)
        
        super().__init__(
            message=f"计算内容哈希失败: diary_id={diary_id}",
            error_code="CONTENT_HASH_ERROR",
            diary_id=diary_id,
            details=details
        )


class ExtractionJobError(ExtractionError):
    """提取任务错误"""
    
    def __init__(
        self,
        message: str,
        diary_id: Optional[int] = None,
        job_id: Optional[int] = None,
        operation: Optional[str] = None
    ):
        details = {}
        if job_id:
            details["job_id"] = job_id
        if operation:
            details["operation"] = operation
        
        super().__init__(
            message=message,
            error_code="EXTRACTION_JOB_ERROR",
            diary_id=diary_id,
            details=details
        )


class MaxRetriesExceededError(ExtractionError):
    """超过最大重试次数错误"""
    
    def __init__(
        self,
        diary_id: int,
        max_retries: int,
        last_error: Optional[str] = None
    ):
        details = {
            "max_retries": max_retries
        }
        if last_error:
            details["last_error"] = last_error
        
        super().__init__(
            message=f"超过最大重试次数 ({max_retries})，提取失败",
            error_code="MAX_RETRIES_EXCEEDED",
            diary_id=diary_id,
            details=details
        )


class ContentCollectionError(ExtractionError):
    """内容收集错误"""
    
    def __init__(
        self,
        diary_id: int,
        missing_relations: Optional[list] = None,
        original_error: Optional[Exception] = None
    ):
        details = {}
        if missing_relations:
            details["missing_relations"] = missing_relations
        if original_error:
            details["original_error"] = str(original_error)
        
        super().__init__(
            message=f"收集日记内容失败: diary_id={diary_id}",
            error_code="CONTENT_COLLECTION_ERROR",
            diary_id=diary_id,
            details=details
        )


class ExportError(ExtractionError):
    """导出错误"""
    
    def __init__(
        self,
        message: str,
        user_id: Optional[int] = None,
        export_format: Optional[str] = None,
        period: Optional[str] = None,
        original_error: Optional[Exception] = None
    ):
        details = {}
        if user_id:
            details["user_id"] = user_id
        if export_format:
            details["export_format"] = export_format
        if period:
            details["period"] = period
        if original_error:
            details["original_error"] = str(original_error)
        
        super().__init__(
            message=message,
            error_code="EXPORT_ERROR",
            details=details
        )


class QueryError(ExtractionError):
    """查询错误"""
    
    def __init__(
        self,
        message: str,
        user_id: Optional[int] = None,
        query_params: Optional[Dict[str, Any]] = None,
        original_error: Optional[Exception] = None
    ):
        details = {}
        if user_id:
            details["user_id"] = user_id
        if query_params:
            details["query_params"] = query_params
        if original_error:
            details["original_error"] = str(original_error)
        
        super().__init__(
            message=message,
            error_code="QUERY_ERROR",
            details=details
        )
