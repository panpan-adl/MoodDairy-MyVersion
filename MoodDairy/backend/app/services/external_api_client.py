"""
外部 API 客户端

统一管理外部 API 调用，包括：
- 百度 ASR 语音识别 API（异步转写）
- 百度 NLP 文本纠错 API
- 百度 NLP 对话情绪识别 API（基于文本分析情绪）

需求: 2.1, 2.3, 3.1
"""
import os
import logging
import asyncio
import time
import json
import random
from pathlib import Path
from typing import Optional, Tuple
from urllib.parse import quote
import httpx

logger = logging.getLogger(__name__)


class ASRAPIError(Exception):
    """ASR API 调用异常"""
    pass


class LLMAPIError(Exception):
    """LLM API 调用异常"""
    pass


class VoiceEmotionAPIError(Exception):
    """语音情感分析 API 调用异常"""
    pass


class BaiduASRClient:
    """
    百度语音识别客户端
    
    实现百度音频文件转写 API（异步转写）
    - 创建转写任务
    - 轮询查询结果
    
    API 文档: https://ai.baidu.com/ai-doc/SPEECH/Vk38lxily
    """
    
    # API 端点
    TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
    CREATE_TASK_URL = "https://aip.baidubce.com/rpc/2.0/aasr/v1/create"
    QUERY_TASK_URL = "https://aip.baidubce.com/rpc/2.0/aasr/v1/query"
    
    # 支持的音频格式
    SUPPORTED_FORMATS = {"mp3", "wav", "pcm", "m4a", "amr"}
    
    # 轮询配置（旧版，保留兼容）
    MAX_POLL_ATTEMPTS = 120
    POLL_INTERVAL = 3
    
    # 新版轮询配置（指数退避）
    DEFAULT_MAX_WAIT_SECONDS = 90
    DEFAULT_INITIAL_INTERVAL = 1.5
    DEFAULT_MAX_INTERVAL = 10.0
    
    def __init__(self, api_key: str, secret_key: str):
        self.api_key = api_key
        self.secret_key = secret_key
        self._access_token: Optional[str] = None
        self._token_expires_at: float = 0
    
    async def _get_access_token(self) -> str:
        if self._access_token and time.time() < self._token_expires_at - 3600:
            return self._access_token
        
        logger.info("Fetching new Baidu ASR access token...")
        
        async with httpx.AsyncClient(timeout=30.0) as client:
            try:
                response = await client.post(
                    self.TOKEN_URL,
                    data={
                        "grant_type": "client_credentials",
                        "client_id": self.api_key,
                        "client_secret": self.secret_key
                    }
                )
                response.raise_for_status()
                data = response.json()
                
                if "access_token" not in data:
                    error_msg = data.get("error_description", "Unknown error")
                    raise ASRAPIError(f"获取百度 access_token 失败: {error_msg}")
                
                self._access_token = data["access_token"]
                expires_in = data.get("expires_in", 2592000)
                self._token_expires_at = time.time() + expires_in
                
                logger.info("Baidu ASR access token obtained successfully")
                return self._access_token
                
            except httpx.HTTPError as e:
                raise ASRAPIError(f"获取百度 access_token 网络错误: {str(e)}")

    async def create_transcription_task(
        self,
        speech_url: str,
        audio_format: str,
        enable_smooth_text: bool = True
    ) -> str:
        fmt = audio_format.lower().lstrip(".")
        if fmt not in self.SUPPORTED_FORMATS:
            raise ASRAPIError(
                f"不支持的音频格式: {audio_format}。"
                f"支持的格式: {', '.join(self.SUPPORTED_FORMATS)}"
            )
        
        access_token = await self._get_access_token()
        
        request_data = {
            "speech_url": speech_url,
            "format": fmt,
            "pid": 80001,
            "rate": 16000,
        }
        
        if enable_smooth_text:
            request_data["smooth_text"] = 1
        
        logger.info(f"Creating Baidu ASR task for: {speech_url}")
        
        async with httpx.AsyncClient(timeout=60.0) as client:
            try:
                response = await client.post(
                    f"{self.CREATE_TASK_URL}?access_token={access_token}",
                    json=request_data
                )
                response.raise_for_status()
                data = response.json()
                
                if "error_code" in data:
                    error_code = data.get("error_code")
                    error_msg = data.get("error_msg", "Unknown error")
                    raise ASRAPIError(f"创建转写任务失败 [{error_code}]: {error_msg}")
                
                task_id = data.get("task_id")
                if not task_id:
                    raise ASRAPIError("创建转写任务失败: 未返回 task_id")
                
                task_status = data.get("task_status", "Unknown")
                logger.info(f"ASR task created: {task_id}, status: {task_status}")
                
                return task_id
                
            except httpx.HTTPError as e:
                raise ASRAPIError(f"创建转写任务网络错误: {str(e)}")
    
    async def query_transcription_result(self, task_id: str) -> dict:
        access_token = await self._get_access_token()
        
        async with httpx.AsyncClient(timeout=30.0) as client:
            try:
                response = await client.post(
                    f"{self.QUERY_TASK_URL}?access_token={access_token}",
                    json={"task_ids": [task_id]}
                )
                response.raise_for_status()
                data = response.json()
                
                if "error_code" in data:
                    error_code = data.get("error_code")
                    error_msg = data.get("error_msg", "Unknown error")
                    raise ASRAPIError(f"查询转写结果失败 [{error_code}]: {error_msg}")
                
                tasks_info = data.get("tasks_info", [])
                if not tasks_info:
                    raise ASRAPIError(f"未找到任务: {task_id}")
                
                task_info = tasks_info[0]
                task_status = task_info.get("task_status", "Unknown")
                
                result = {"status": task_status}
                
                if task_status == "Success":
                    task_result = task_info.get("task_result", {})
                    result_texts = task_result.get("result", [])
                    result["result"] = "".join(result_texts) if result_texts else ""
                    result["audio_duration"] = task_result.get("audio_duration", 0)
                    result["detailed_result"] = task_result.get("detailed_result", [])
                    
                elif task_status == "Failure":
                    task_result = task_info.get("task_result", {})
                    result["error"] = task_result.get("err_msg", "转写失败")
                    result["error_code"] = task_result.get("err_no", -1)
                
                return result
                
            except httpx.HTTPError as e:
                raise ASRAPIError(f"查询转写结果网络错误: {str(e)}")

    async def wait_transcription_result(
        self,
        task_id: str,
        max_wait_seconds: float = 90,
        initial_interval: float = 1.5,
        max_interval: float = 10.0
    ) -> Tuple[Optional[str], dict]:
        """
        等待转写结果（使用 deadline + 指数退避 + 抖动）
        
        行为：
        - 以 deadline = time.monotonic() + max_wait_seconds 控制总等待时长
        - 轮询间隔采用指数退避：interval = min(max_interval, interval * 1.5)
        - sleep 加抖动：await asyncio.sleep(sleep_s + random.uniform(0, sleep_s*0.2))
        - 超时返回 (None, last_result) 不抛异常
        
        Args:
            task_id: 百度 ASR 任务ID
            max_wait_seconds: 最大等待时间（秒），默认90秒
            initial_interval: 初始轮询间隔（秒），默认1.5秒
            max_interval: 最大轮询间隔（秒），默认10秒
            
        Returns:
            Tuple[Optional[str], dict]: (转写文本, 最后一次查询结果)
                - Success 时：(text, result)
                - Timeout 时：(None, last_result)
                
        Raises:
            ASRAPIError: 仅在百度明确返回 Failure 时抛出
        """
        deadline = time.monotonic() + max_wait_seconds
        interval = initial_interval
        attempt = 0
        last_result = {}
        
        logger.info(
            f"Starting ASR polling for task {task_id}, "
            f"max_wait={max_wait_seconds}s, initial_interval={initial_interval}s"
        )
        
        while time.monotonic() < deadline:
            attempt += 1
            
            # 指数退避 + 抖动
            sleep_s = min(max_interval, interval)
            jitter = random.uniform(0, sleep_s * 0.2)
            actual_sleep = sleep_s + jitter
            
            # 确保不会超过 deadline
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            actual_sleep = min(actual_sleep, remaining)
            
            await asyncio.sleep(actual_sleep)
            
            try:
                result = await self.query_transcription_result(task_id)
                last_result = result
                status = result.get("status")
                
                if status == "Success":
                    text = result.get("result", "")
                    duration_ms = result.get("audio_duration", 0)
                    logger.info(
                        f"ASR task {task_id} completed after {attempt} attempts. "
                        f"Duration: {duration_ms}ms, Text length: {len(text)}"
                    )
                    return (text, result)
                    
                elif status == "Failure":
                    error_msg = result.get("error", "Unknown error")
                    error_code = result.get("error_code", -1)
                    logger.error(f"ASR task {task_id} failed: [{error_code}] {error_msg}")
                    raise ASRAPIError(f"转写失败 [{error_code}]: {error_msg}")
                    
                else:
                    # Created/Running/Processing/None 等状态，继续等待
                    # 每 5 次轮询打一次 info 日志
                    if attempt % 5 == 0:
                        next_interval = min(max_interval, interval * 1.5)
                        logger.info(
                            f"ASR task {task_id} polling: status={status}, "
                            f"attempt={attempt}, next_wait≈{next_interval:.1f}s"
                        )
                    else:
                        logger.debug(f"ASR task {task_id} status={status}, attempt={attempt}")
                        
            except ASRAPIError:
                # Failure 抛出的异常，直接传递
                raise
            except Exception as e:
                # 网络错误等，记录但继续轮询
                logger.warning(f"ASR query error (attempt {attempt}): {str(e)}")
                last_result = {"status": "error", "error": str(e)}
            
            # 更新间隔（指数退避）
            interval = min(max_interval, interval * 1.5)
        
        # 超时，返回 (None, last_result) 不抛异常
        elapsed = max_wait_seconds
        logger.warning(
            f"ASR task {task_id} timeout after {elapsed:.1f}s ({attempt} attempts). "
            f"Last status: {last_result.get('status', 'unknown')}"
        )
        return (None, last_result)

    async def transcribe_audio(
        self,
        speech_url: str,
        audio_format: str,
        enable_smooth_text: bool = True,
        max_wait_seconds: int = 300
    ) -> str:
        task_id = await self.create_transcription_task(
            speech_url=speech_url,
            audio_format=audio_format,
            enable_smooth_text=enable_smooth_text
        )
        
        max_attempts = max(1, max_wait_seconds // self.POLL_INTERVAL)
        
        logger.info(f"Polling ASR task {task_id}, max attempts: {max_attempts}")
        
        for attempt in range(max_attempts):
            await asyncio.sleep(self.POLL_INTERVAL)
            
            result = await self.query_transcription_result(task_id)
            status = result.get("status")
            
            if status == "Success":
                text = result.get("result", "")
                duration_ms = result.get("audio_duration", 0)
                logger.info(
                    f"ASR task {task_id} completed. "
                    f"Duration: {duration_ms}ms, Text length: {len(text)}"
                )
                return text
                
            elif status == "Failure":
                error_msg = result.get("error", "Unknown error")
                error_code = result.get("error_code", -1)
                raise ASRAPIError(f"转写失败 [{error_code}]: {error_msg}")
                
            elif status == "Running":
                logger.debug(f"ASR task {task_id} still running... (attempt {attempt + 1}/{max_attempts})")
                continue
                
            else:
                # 任务5: 日志降噪 - 未知状态降级为 debug
                logger.debug(f"ASR task {task_id} unknown status: {status}, treating as processing")
        
        raise ASRAPIError(
            f"转写任务超时（等待 {max_wait_seconds} 秒）。"
            f"任务 ID: {task_id}，请稍后重试。"
        )


class BaiduNLPClient:
    """
    百度自然语言处理客户端
    
    实现百度 NLP API：
    - 对话情绪识别 (emotion): 识别出当前会话者所表现出的情绪类别及其置信度
    
    API 文档: https://ai.baidu.com/ai-doc/NLP/
    """
    
    # API 端点
    TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
    EMOTION_URL = "https://aip.baidubce.com/rpc/2.0/nlp/v1/emotion"
    
    def __init__(self, api_key: str, secret_key: str):
        self.api_key = api_key
        self.secret_key = secret_key
        self._access_token: Optional[str] = None
        self._token_expires_at: float = 0
    
    async def _get_access_token(self) -> str:
        """获取百度 NLP API 的 access_token"""
        if self._access_token and time.time() < self._token_expires_at - 3600:
            return self._access_token
        
        logger.info("Fetching new Baidu NLP access token...")
        
        async with httpx.AsyncClient(timeout=30.0) as client:
            try:
                response = await client.post(
                    self.TOKEN_URL,
                    data={
                        "grant_type": "client_credentials",
                        "client_id": self.api_key,
                        "client_secret": self.secret_key
                    }
                )
                response.raise_for_status()
                data = response.json()
                
                if "access_token" not in data:
                    error_msg = data.get("error_description", "Unknown error")
                    raise VoiceEmotionAPIError(f"获取百度 NLP access_token 失败: {error_msg}")
                
                self._access_token = data["access_token"]
                expires_in = data.get("expires_in", 2592000)
                self._token_expires_at = time.time() + expires_in
                
                logger.info("Baidu NLP access token obtained successfully")
                return self._access_token
                
            except httpx.HTTPError as e:
                raise VoiceEmotionAPIError(f"获取百度 NLP access_token 网络错误: {str(e)}")
    
    async def emotion_recognition(self, text: str) -> dict:
        """
        对话情绪识别
        
        使用百度 NLP 对话情绪识别 API 分析文本中的情绪
        映射到五类情绪：开心、中性、伤心、嫉妒、生气
        
        Args:
            text: 待分析的文本
            
        Returns:
            dict: 情绪分析结果
                - emotion_type: 情绪类型（开心/中性/伤心/嫉妒/生气）
                - score: 情绪评分（1-100，50为中性）
                - confidence: 置信度（0-1）
                - label: 原始标签（happy/neutral/sad/jealous/angry）
            
        Raises:
            VoiceEmotionAPIError: 如果 API 调用失败
        """
        if not text or not text.strip():
            return {
                "emotion_type": "中性",
                "score": 50,
                "confidence": 0.0,
                "label": "neutral"
            }
        
        access_token = await self._get_access_token()
        
        # 百度 NLP API 使用 UTF-8 编码
        url = f"{self.EMOTION_URL}?charset=UTF-8&access_token={access_token}"
        
        logger.info(f"Calling Baidu NLP emotion recognition API, text length: {len(text)}")
        
        async with httpx.AsyncClient(timeout=30.0) as client:
            try:
                response = await client.post(
                    url,
                    json={"text": text},
                    headers={"Content-Type": "application/json"}
                )
                response.raise_for_status()
                data = response.json()
                
                # 检查错误
                if "error_code" in data:
                    error_code = data.get("error_code")
                    error_msg = data.get("error_msg", "Unknown error")
                    raise VoiceEmotionAPIError(f"情绪识别失败 [{error_code}]: {error_msg}")
                
                # 解析结果
                # 返回格式: {"log_id": xxx, "text": "...", "items": [{"label": "optimistic", "prob": 0.95}]}
                items = data.get("items", [])
                
                if not items:
                    logger.warning("Emotion recognition returned empty items, using neutral")
                    return {
                        "emotion_type": "中性",
                        "score": 50,
                        "confidence": 0.0,
                        "label": "neutral"
                    }
                
                # 取第一个结果（置信度最高的）
                item = items[0]
                label = item.get("label", "neutral")
                prob = float(item.get("prob", 0.0))
                
                # 映射百度API的三类情绪到五类情绪
                # optimistic -> 开心 (happy)
                # neutral -> 中性 (neutral)
                # pessimistic -> 根据文本内容进一步分析（伤心/嫉妒/生气）
                if label == "optimistic":
                    emotion_type = "开心"
                    mapped_label = "happy"
                    score = int(50 + prob * 50)
                elif label == "neutral":
                    emotion_type = "中性"
                    mapped_label = "neutral"
                    score = 50
                else:  # pessimistic
                    # 对消极情绪进行细分：通过关键词分析
                    text_lower = text.lower()
                    
                    # 生气相关关键词
                    angry_keywords = ["生气", "愤怒", "气死", "烦死", "讨厌", "恨", "火大", "暴怒", "怒", "气愤", "恼火", "发火"]
                    # 嫉妒相关关键词
                    jealous_keywords = ["嫉妒", "羡慕", "眼红", "不公平", "凭什么", "为什么他", "为什么她", "比不上", "不如"]
                    # 伤心相关关键词
                    sad_keywords = ["伤心", "难过", "悲伤", "哭", "泪", "痛苦", "失落", "沮丧", "绝望", "心痛", "难受", "郁闷", "低落"]
                    
                    # 检查关键词
                    has_angry = any(kw in text for kw in angry_keywords)
                    has_jealous = any(kw in text for kw in jealous_keywords)
                    has_sad = any(kw in text for kw in sad_keywords)
                    
                    if has_angry and not has_jealous and not has_sad:
                        emotion_type = "生气"
                        mapped_label = "angry"
                    elif has_jealous and not has_angry:
                        emotion_type = "嫉妒"
                        mapped_label = "jealous"
                    elif has_sad or (not has_angry and not has_jealous):
                        emotion_type = "伤心"
                        mapped_label = "sad"
                    else:
                        # 默认为伤心
                        emotion_type = "伤心"
                        mapped_label = "sad"
                    
                    score = int(50 - prob * 49)
                
                score = max(1, min(100, score))
                
                logger.info(f"Emotion recognition completed: {emotion_type} ({mapped_label}), score: {score}, prob: {prob:.2f}")
                
                return {
                    "emotion_type": emotion_type,
                    "score": score,
                    "confidence": prob,
                    "label": mapped_label
                }
                
            except httpx.HTTPError as e:
                raise VoiceEmotionAPIError(f"情绪识别网络错误: {str(e)}")


class QianfanClient:
    """
    百度千帆大模型客户端
    
    实现百度千帆 API：
    - 文本生成/对话: 用于文本优化，使口语化文本更自然流畅
    - 日记结构化提取: 提取日记关键信息、情绪分析、高光时刻识别
    
    API 文档: https://cloud.baidu.com/doc/WENXINWORKSHOP/
    """
    
    # API 端点
    CHAT_URL = "https://qianfan.baidubce.com/v2/chat/completions"
    
    # 主提取 Prompt 模板
    EXTRACTION_PROMPT_TEMPLATE = """你是一个专业的日记分析助手。请分析以下日记内容，提取关键信息。

日记内容：
{diary_content}

日记元数据：
- 日期：{diary_date}
- 天气：{weather}
- 地点：{location}
- 心情评分：{mood_score}

请以 JSON 格式返回以下信息：
{{
  "summary": "日记摘要（100-200字，简洁准确，突出重点）",
  "keywords": ["关键词1", "关键词2", "关键词3"],
  "main_topics": ["主要话题1", "主要话题2"],
  "people_mentioned": [
    {{"name": "人名", "relation": "关系（如：朋友、同事、家人）"}}
  ],
  "places_mentioned": [
    {{"name": "地点名", "type": "地点类型（如：餐饮、户外、工作场所）"}}
  ],
  "emotion_analysis": {{
    "primary_emotion": "主要情绪（开心/平静/焦虑/愤怒/低落/兴奋/复杂/中性）",
    "emotion_score": 75,
    "emotion_distribution": {{
      "开心": 0.6,
      "中性": 0.3,
      "低落": 0.1,
      "焦虑": 0.0,
      "愤怒": 0.0,
      "平静": 0.0,
      "兴奋": 0.0,
      "复杂": 0.0
    }},
    "key_sentences": ["体现情绪的关键句子1", "体现情绪的关键句子2"]
  }},
  "has_small_happiness": 0,
  "small_happiness_content": "小确幸内容（如果有）",
  "has_highlight": 0,
  "highlight_summary": "高光时刻摘要（如果有）"
}}

注意事项：
1. 摘要要简洁准确，突出重点，控制在100-200字
2. 关键词要有代表性，提取3-10个最重要的词
3. 情绪分析要结合上下文，emotion_score范围1-100（50为中性，>50为积极，<50为消极）
4. emotion_distribution 各项之和应为1.0
5. 小确幸是指生活中微小但温暖的瞬间（如：喝到好喝的咖啡、看到美丽的日落）
6. 高光时刻是指值得骄傲和纪念的重要时刻（如：完成重要项目、获得认可、达成目标）
7. has_small_happiness 和 has_highlight 为 0 或 1
8. 只返回 JSON，不要添加任何其他文字说明
"""

    # 情绪分析专用 Prompt 模板
    EMOTION_ANALYSIS_PROMPT_TEMPLATE = """你是一个专业的情绪分析专家。请深入分析以下日记内容中的情绪。

日记内容：
{diary_content}

请以 JSON 格式返回情绪分析结果：
{{
  "primary_emotion": "主要情绪（开心/平静/焦虑/愤怒/低落/兴奋/复杂/中性）",
  "emotion_score": 75,
  "emotion_distribution": {{
    "开心": 0.6,
    "中性": 0.3,
    "低落": 0.1,
    "焦虑": 0.0,
    "愤怒": 0.0,
    "平静": 0.0,
    "兴奋": 0.0,
    "复杂": 0.0
  }},
  "key_sentences": ["体现情绪的关键句子1", "体现情绪的关键句子2"],
  "emotion_intensity": "情绪强度（轻微/中等/强烈）",
  "emotion_changes": ["情绪变化描述（如果有明显变化）"]
}}

分析要求：
1. 准确识别主要情绪类型（开心/平静/焦虑/愤怒/低落/兴奋/复杂/中性）
2. emotion_score 范围1-100：
   - 1-30: 强烈消极情绪（低落、焦虑、愤怒）
   - 31-45: 轻微消极情绪
   - 46-55: 中性或复杂情绪
   - 56-70: 轻微积极情绪（平静）
   - 71-100: 强烈积极情绪（开心、兴奋）
3. emotion_distribution 要准确反映各种情绪的占比（包括开心、平静、焦虑、愤怒、低落、兴奋、复杂、中性），总和为1.0
4. 提取2-5个最能体现情绪的关键句子
5. 如果日记中情绪有明显变化，在 emotion_changes 中描述
6. 只返回 JSON，不要添加任何其他文字说明
"""

    # 高光时刻和小确幸识别 Prompt 模板
    HIGHLIGHT_PROMPT_TEMPLATE = """你是一个专业的生活记录分析专家。请分析以下日记内容，识别其中的高光时刻和小确幸。

日记内容：
{diary_content}

情绪信息：
- 主要情绪：{primary_emotion}
- 情绪评分：{emotion_score}

请以 JSON 格式返回识别结果：
{{
  "has_highlight": 0,
  "highlight_summary": "高光时刻摘要（如果有）",
  "highlight_reason": "为什么这是高光时刻",
  "has_small_happiness": 0,
  "small_happiness_content": "小确幸内容（如果有）",
  "small_happiness_items": ["具体的小确幸1", "具体的小确幸2"]
}}

识别标准：

高光时刻（Highlight）：
- 重要的成就或突破（完成重要项目、通过考试、获得晋升）
- 获得认可或赞赏（获奖、被表扬、得到感谢）
- 达成重要目标（实现梦想、完成挑战）
- 特别有意义的经历（重要的聚会、难忘的旅行）
- 克服困难后的成功
- 通常伴随强烈的积极情绪（emotion_score > 70）

小确幸（Small Happiness）：
- 生活中微小但温暖的瞬间
- 简单的快乐（喝到好喝的咖啡、看到美丽的日落、听到喜欢的歌）
- 意外的惊喜（收到礼物、遇到老朋友、发现新店）
- 日常的美好（天气很好、睡了个好觉、吃到美食）
- 小小的成就感（整理好房间、完成小任务）
- 通常伴随轻微到中等的积极情绪（emotion_score 56-85）

注意事项：
1. has_highlight 和 has_small_happiness 为 0 或 1
2. 一篇日记可以同时包含高光时刻和小确幸
3. 如果没有识别到，对应的内容字段返回空字符串
4. highlight_summary 应简洁有力，突出重点（50字以内）
5. small_happiness_items 列出具体的小确幸事项（如果有多个）
6. 只返回 JSON，不要添加任何其他文字说明
"""
    
    def __init__(self, api_key: str):
        """
        初始化千帆客户端
        
        Args:
            api_key: 完整的 API Key，格式为 bce-v3/ALTAK-xxx/xxx
        """
        self.api_key = api_key
    
    async def _call_llm(
        self,
        messages: list,
        temperature: float = 0.3,
        max_tokens: int = 2000,
        max_retries: int = 3
    ) -> dict:
        """
        调用千帆大模型 API（内部方法）
        
        Args:
            messages: 消息列表
            temperature: 温度参数（0-1，越低越确定）
            max_tokens: 最大生成 token 数
            max_retries: 最大重试次数
            
        Returns:
            dict: API 响应数据
            
        Raises:
            LLMAPIError: 如果 API 调用失败
        """
        request_data = {
            "model": os.getenv("QIANYI_MODEL_NAME", "deepseek-v3.1-250821"),
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens
        }
        
        last_error = None
        
        for attempt in range(max_retries):
            try:
                async with httpx.AsyncClient(timeout=60.0) as client:
                    response = await client.post(
                        self.CHAT_URL,
                        json=request_data,
                        headers={
                            "Content-Type": "application/json",
                            "Authorization": f"Bearer {self.api_key}"
                        }
                    )
                    response.raise_for_status()
                    data = response.json()
                    
                    # 检查错误
                    if "error" in data or "code" in data:
                        error_code = data.get("code", data.get("error", "Unknown"))
                        error_msg = data.get("message", data.get("error_description", "Unknown error"))
                        raise LLMAPIError(f"千帆 API 调用失败 [{error_code}]: {error_msg}")
                    
                    return data
                    
            except httpx.HTTPStatusError as e:
                error_detail = ""
                try:
                    error_data = e.response.json()
                    error_detail = f" - {error_data.get('message', error_data.get('error', ''))}"
                except:
                    pass
                last_error = LLMAPIError(f"千帆 API 错误 [{e.response.status_code}]{error_detail}")
                
                # 如果是客户端错误（4xx），不重试
                if 400 <= e.response.status_code < 500:
                    raise last_error
                
                # 服务器错误（5xx）或网络错误，等待后重试
                if attempt < max_retries - 1:
                    wait_time = 2 ** attempt  # 指数退避：1s, 2s, 4s
                    logger.warning(f"千帆 API 调用失败，{wait_time}秒后重试... (尝试 {attempt + 1}/{max_retries})")
                    await asyncio.sleep(wait_time)
                    
            except httpx.HTTPError as e:
                last_error = LLMAPIError(f"千帆 API 网络错误: {str(e)}")
                
                if attempt < max_retries - 1:
                    wait_time = 2 ** attempt
                    logger.warning(f"千帆 API 网络错误，{wait_time}秒后重试... (尝试 {attempt + 1}/{max_retries})")
                    await asyncio.sleep(wait_time)
        
        # 所有重试都失败
        raise last_error or LLMAPIError("千帆 API 调用失败")
    
    def _parse_json_response(self, response_data: dict) -> dict:
        """
        解析 LLM 返回的 JSON 响应
        
        Args:
            response_data: API 响应数据
            
        Returns:
            dict: 解析后的 JSON 对象
            
        Raises:
            LLMAPIError: 如果解析失败
        """
        choices = response_data.get("choices", [])
        if not choices:
            raise LLMAPIError("千帆 API 返回空响应")
        
        message = choices[0].get("message", {})
        content = message.get("content", "").strip()
        
        if not content:
            raise LLMAPIError("千帆 API 返回空内容")
        
        # 尝试解析 JSON
        try:
            # 有时 LLM 会在 JSON 前后添加说明文字，需要提取 JSON 部分
            # 查找第一个 { 和最后一个 }
            start_idx = content.find("{")
            end_idx = content.rfind("}")
            
            if start_idx == -1 or end_idx == -1:
                raise ValueError("响应中未找到 JSON 对象")
            
            json_str = content[start_idx:end_idx + 1]
            result = json.loads(json_str)
            
            return result
            
        except (json.JSONDecodeError, ValueError) as e:
            logger.error(f"JSON 解析失败: {str(e)}\n响应内容: {content}")
            raise LLMAPIError(f"无法解析 LLM 返回的 JSON: {str(e)}")
    
    async def extract_diary_info(
        self,
        diary_content: str,
        diary_date: str = "",
        weather: str = "",
        location: str = "",
        mood_score: int = 50
    ) -> dict:
        """
        提取日记结构化信息
        
        使用千帆大模型提取日记的关键信息，包括摘要、关键词、情绪分析等
        
        Args:
            diary_content: 日记内容
            diary_date: 日记日期
            weather: 天气
            location: 地点
            mood_score: 心情评分（1-100）
            
        Returns:
            dict: 提取结果，包含以下字段：
                - summary: 日记摘要
                - keywords: 关键词列表
                - main_topics: 主要话题列表
                - people_mentioned: 提及的人物列表
                - places_mentioned: 提及的地点列表
                - emotion_analysis: 情绪分析结果
                - has_small_happiness: 是否包含小确幸
                - small_happiness_content: 小确幸内容
                - has_highlight: 是否包含高光时刻
                - highlight_summary: 高光时刻摘要
                
        Raises:
            LLMAPIError: 如果提取失败
        """
        if not diary_content or not diary_content.strip():
            raise LLMAPIError("日记内容不能为空")
        
        logger.info(f"开始提取日记信息，内容长度: {len(diary_content)}")
        
        # 构建 prompt
        prompt = self.EXTRACTION_PROMPT_TEMPLATE.format(
            diary_content=diary_content,
            diary_date=diary_date or "未指定",
            weather=weather or "未指定",
            location=location or "未指定",
            mood_score=mood_score
        )
        
        messages = [
            {
                "role": "system",
                "content": "你是一个专业的日记分析助手。你的任务是分析日记内容并提取结构化信息。只返回 JSON 格式的结果，不要添加任何其他说明。"
            },
            {
                "role": "user",
                "content": prompt
            }
        ]
        
        # 调用 LLM
        response_data = await self._call_llm(messages, temperature=0.3, max_tokens=2000)
        
        # 解析 JSON 响应
        result = self._parse_json_response(response_data)
        
        logger.info(f"日记信息提取完成，摘要长度: {len(result.get('summary', ''))}")
        
        return result
    
    async def analyze_emotion_detailed(self, diary_content: str) -> dict:
        """
        详细情绪分析
        
        使用千帆大模型进行深入的情绪分析
        
        Args:
            diary_content: 日记内容
            
        Returns:
            dict: 情绪分析结果，包含以下字段：
                - primary_emotion: 主要情绪
                - emotion_score: 情绪评分
                - emotion_distribution: 情绪分布
                - key_sentences: 关键句子
                - emotion_intensity: 情绪强度
                - emotion_changes: 情绪变化
                
        Raises:
            LLMAPIError: 如果分析失败
        """
        if not diary_content or not diary_content.strip():
            raise LLMAPIError("日记内容不能为空")
        
        logger.info(f"开始详细情绪分析，内容长度: {len(diary_content)}")
        
        # 构建 prompt
        prompt = self.EMOTION_ANALYSIS_PROMPT_TEMPLATE.format(
            diary_content=diary_content
        )
        
        messages = [
            {
                "role": "system",
                "content": "你是一个专业的情绪分析专家。你的任务是深入分析文本中的情绪。只返回 JSON 格式的结果，不要添加任何其他说明。"
            },
            {
                "role": "user",
                "content": prompt
            }
        ]
        
        # 调用 LLM
        response_data = await self._call_llm(messages, temperature=0.2, max_tokens=1500)
        
        # 解析 JSON 响应
        result = self._parse_json_response(response_data)
        
        logger.info(f"情绪分析完成，主要情绪: {result.get('primary_emotion', 'Unknown')}")
        
        return result
    
    async def identify_highlights_and_joys(
        self,
        diary_content: str,
        primary_emotion: str = "中性",
        emotion_score: int = 50
    ) -> dict:
        """
        识别高光时刻和小确幸
        
        使用千帆大模型识别日记中的高光时刻和小确幸
        
        Args:
            diary_content: 日记内容
            primary_emotion: 主要情绪
            emotion_score: 情绪评分
            
        Returns:
            dict: 识别结果，包含以下字段：
                - has_highlight: 是否包含高光时刻
                - highlight_summary: 高光时刻摘要
                - highlight_reason: 为什么这是高光时刻
                - has_small_happiness: 是否包含小确幸
                - small_happiness_content: 小确幸内容
                - small_happiness_items: 具体的小确幸列表
                
        Raises:
            LLMAPIError: 如果识别失败
        """
        if not diary_content or not diary_content.strip():
            raise LLMAPIError("日记内容不能为空")
        
        logger.info(f"开始识别高光时刻和小确幸，内容长度: {len(diary_content)}")
        
        # 构建 prompt
        prompt = self.HIGHLIGHT_PROMPT_TEMPLATE.format(
            diary_content=diary_content,
            primary_emotion=primary_emotion,
            emotion_score=emotion_score
        )
        
        messages = [
            {
                "role": "system",
                "content": "你是一个专业的生活记录分析专家。你的任务是识别日记中的高光时刻和小确幸。只返回 JSON 格式的结果，不要添加任何其他说明。"
            },
            {
                "role": "user",
                "content": prompt
            }
        ]
        
        # 调用 LLM
        response_data = await self._call_llm(messages, temperature=0.3, max_tokens=1500)
        
        # 解析 JSON 响应
        result = self._parse_json_response(response_data)
        
        has_highlight = result.get("has_highlight", 0)
        has_joy = result.get("has_small_happiness", 0)
        logger.info(f"识别完成，高光时刻: {has_highlight}, 小确幸: {has_joy}")
        
        return result
    
    async def text_optimization(self, text: str) -> str:
        """
        文本优化
        
        使用百度千帆大模型 API 优化文本，使其更自然流畅
        
        Args:
            text: 待优化的文本
            
        Returns:
            str: 优化后的文本
            
        Raises:
            LLMAPIError: 如果 API 调用失败
        """
        if not text or not text.strip():
            return text
        
        logger.info(f"Calling Baidu Qianfan API for text optimization, text length: {len(text)}")
        
        # 构建请求
        request_data = {
            "model": os.getenv("QIANYI_MODEL_NAME", "deepseek-v3.1-250821"),
            "messages": [
                {
                    "role": "system",
                    "content": "你是一个专业的日记文本编辑助手。你的任务是将用户通过语音输入的口语化文本优化为适合书面记录的日记内容。要求：1) 保持原意和情感不变；2) 修正语法错误和口语化表达；3) 使文本更加流畅自然；4) 只返回优化后的文本，不要添加任何解释。"
                },
                {
                    "role": "user",
                    "content": f"请将以下语音转文字的内容优化为日记文本：\n\n{text}"
                }
            ],
            "temperature": 0.3,
            "max_tokens": 2000
        }
        
        async with httpx.AsyncClient(timeout=60.0) as client:
            try:
                response = await client.post(
                    self.CHAT_URL,
                    json=request_data,
                    headers={
                        "Content-Type": "application/json",
                        "Authorization": f"Bearer {self.api_key}"
                    }
                )
                response.raise_for_status()
                data = response.json()
                
                # 检查错误
                if "error" in data or "code" in data:
                    error_code = data.get("code", data.get("error", "Unknown"))
                    error_msg = data.get("message", data.get("error_description", "Unknown error"))
                    raise LLMAPIError(f"千帆 API 调用失败 [{error_code}]: {error_msg}")
                
                # 解析结果
                # 返回格式: {"id": "...", "choices": [{"message": {"content": "优化后的文本"}}], ...}
                choices = data.get("choices", [])
                if not choices:
                    logger.warning("Qianfan API returned empty choices, using original text")
                    return text
                
                message = choices[0].get("message", {})
                optimized_text = message.get("content", "").strip()
                
                if not optimized_text:
                    logger.warning("Qianfan API returned empty content, using original text")
                    return text
                
                logger.info(f"Text optimization completed. Original: {len(text)} chars, Optimized: {len(optimized_text)} chars")
                return optimized_text
                
            except httpx.HTTPStatusError as e:
                # 处理 HTTP 错误状态码
                error_detail = ""
                try:
                    error_data = e.response.json()
                    error_detail = f" - {error_data.get('message', error_data.get('error', ''))}"
                except:
                    pass
                raise LLMAPIError(f"千帆 API 错误 [{e.response.status_code}]{error_detail}")
            except httpx.HTTPError as e:
                raise LLMAPIError(f"千帆 API 网络错误: {str(e)}")


class ExternalAPIClient:
    """
    外部 API 客户端统一管理类
    
    整合所有外部 API 客户端：
    - 百度 ASR 语音识别
    - 百度千帆大模型（用于 LLM 文本优化）
    - 百度 NLP 对话情绪识别（用于语音情感分析，基于转录文本）
    """
    
    def __init__(self):
        self._baidu_asr_client: Optional[BaiduASRClient] = None
        self._baidu_nlp_client: Optional[BaiduNLPClient] = None
        self._qianfan_client: Optional[QianfanClient] = None
        self._server_base_url: Optional[str] = None
        self._text_emotion_enabled: bool = False
        
        self._initialize_clients()
    
    def _initialize_clients(self):
        """初始化所有 API 客户端"""
        # 百度 ASR 客户端
        baidu_api_key = os.getenv("BAIDU_API_KEY")
        baidu_secret_key = os.getenv("BAIDU_SECRET_KEY")
        
        if baidu_api_key and baidu_secret_key:
            self._baidu_asr_client = BaiduASRClient(baidu_api_key, baidu_secret_key)
            logger.info("Baidu ASR client initialized")
        else:
            logger.warning("Baidu ASR credentials not configured")
        
        # 百度 NLP 客户端（对话情绪识别）
        baidu_nlp_api_key = os.getenv("BAIDU_NLP_API_KEY")
        baidu_nlp_secret_key = os.getenv("BAIDU_NLP_SECRET_KEY")
        
        if baidu_nlp_api_key and baidu_nlp_secret_key:
            self._baidu_nlp_client = BaiduNLPClient(baidu_nlp_api_key, baidu_nlp_secret_key)
            logger.info("Baidu NLP client initialized")
        else:
            logger.warning("Baidu NLP credentials not configured")
        
        # 百度千帆客户端（文本优化）
        qianfan_api_key = os.getenv("QIANFAN_API_KEY")
        
        if qianfan_api_key:
            self._qianfan_client = QianfanClient(qianfan_api_key)
            logger.info("Qianfan client initialized")
        else:
            logger.warning("Qianfan API key not configured")
        
        # 服务器公网地址（用于百度 ASR）
        self._server_base_url = os.getenv("SERVER_BASE_URL")
        if self._server_base_url:
            logger.info(f"Server base URL configured: {self._server_base_url}")
        
        # 文本情绪分析开关
        self._text_emotion_enabled = os.getenv("TEXT_EMOTION_ENABLED", "true").lower() == "true"
        logger.info(f"Text emotion analysis enabled: {self._text_emotion_enabled}")
    
    def is_asr_configured(self) -> bool:
        """检查 ASR API 是否已配置"""
        return self._baidu_asr_client is not None and self._server_base_url is not None
    
    def is_llm_configured(self) -> bool:
        """检查 LLM 文本优化 API 是否已配置（使用百度千帆大模型）"""
        return self._qianfan_client is not None
    
    def is_voice_emotion_configured(self) -> bool:
        """检查语音情感分析 API 是否已配置（使用百度 NLP 对话情绪识别）"""
        return self._baidu_nlp_client is not None and self._text_emotion_enabled
    
    async def create_asr_task(
        self,
        audio_file_path: str,
        oss_url: Optional[str] = None
    ) -> str:
        """
        创建 ASR 转写任务（仅创建，不等待结果）
        
        Args:
            audio_file_path: 音频文件路径（用于获取格式）
            oss_url: OSS 公共访问 URL（必须提供）
            
        Returns:
            str: 任务ID (task_id)
            
        Raises:
            ASRAPIError: 如果创建失败
        """
        if not self._baidu_asr_client:
            raise ASRAPIError("百度 ASR API 未配置")
        
        if not oss_url:
            raise ASRAPIError("必须提供 OSS URL")
        
        # 获取音频格式
        file_path = Path(audio_file_path)
        audio_format = file_path.suffix.lower().lstrip(".")
        
        logger.info(f"Creating ASR task for: {oss_url}")
        
        return await self._baidu_asr_client.create_transcription_task(
            speech_url=oss_url,
            audio_format=audio_format,
            enable_smooth_text=True
        )
    
    async def wait_asr_result(
        self,
        task_id: str,
        max_wait_seconds: float = 90,
        initial_interval: float = 1.5,
        max_interval: float = 10.0
    ) -> Tuple[Optional[str], dict]:
        """
        等待 ASR 转写结果（使用指数退避轮询）
        
        Args:
            task_id: 任务ID
            max_wait_seconds: 最大等待时间
            initial_interval: 初始轮询间隔
            max_interval: 最大轮询间隔
            
        Returns:
            Tuple[Optional[str], dict]: (转写文本, 最后查询结果)
                - Success: (text, result)
                - Timeout: (None, last_result)
                
        Raises:
            ASRAPIError: 仅在 Failure 时抛出
        """
        if not self._baidu_asr_client:
            raise ASRAPIError("百度 ASR API 未配置")
        
        return await self._baidu_asr_client.wait_transcription_result(
            task_id=task_id,
            max_wait_seconds=max_wait_seconds,
            initial_interval=initial_interval,
            max_interval=max_interval
        )
    
    async def query_asr_status(self, task_id: str) -> dict:
        """
        查询 ASR 任务状态（单次查询，不轮询）
        
        Args:
            task_id: 任务ID
            
        Returns:
            dict: 查询结果
                - status: 任务状态 (Success/Failure/Running/Created/Processing)
                - result: 转写文本（Success 时）
                - error: 错误信息（Failure 时）
                - error_code: 错误码（Failure 时）
                
        Raises:
            ASRAPIError: 如果查询失败
        """
        if not self._baidu_asr_client:
            raise ASRAPIError("百度 ASR API 未配置")
        
        return await self._baidu_asr_client.query_transcription_result(task_id)

    async def speech_to_text(self, audio_file_path: str, oss_url: Optional[str] = None) -> str:
        """
        语音转文字（旧版接口，保留兼容）
        
        使用百度 ASR API 将音频文件转换为文字
        
        Args:
            audio_file_path: 音频文件路径
            oss_url: OSS 公共访问 URL（优先使用，如果提供）
            
        Returns:
            str: 转录的文字
            
        Raises:
            ASRAPIError: 如果转录失败
        """
        if not self._baidu_asr_client:
            raise ASRAPIError("百度 ASR API 未配置")
        
        # 优先使用 OSS URL（如果提供）
        if oss_url:
            speech_url = oss_url
            logger.info(f"Using OSS URL for Baidu ASR: {speech_url}")
        else:
            # 降级使用服务器 URL
            if not self._server_base_url:
                raise ASRAPIError("服务器公网地址未配置，百度 ASR 需要音频文件的公网 URL")
            
            # 构建音频文件的公网 URL
            file_path = Path(audio_file_path)
            
            # 音频文件在 uploads 目录下，但服务器通过 /media/ 路径提供访问
            # URL 格式: {SERVER_BASE_URL}/media/{relative_path}
            relative_path = file_path.name
            if "uploads" in str(file_path):
                # 提取 uploads 之后的路径
                parts = file_path.parts
                try:
                    uploads_idx = parts.index("uploads")
                    # 获取 uploads 之后的相对路径
                    relative_path = "/".join(parts[uploads_idx + 1:])
                except ValueError:
                    relative_path = file_path.name
            
            # 使用 /media/ 路径（与 main.py 中的 StaticFiles 挂载路径一致）
            speech_url = f"{self._server_base_url}/media/{relative_path}"
            logger.info(f"Using server URL for Baidu ASR: {speech_url}")
        
        # 获取音频格式
        file_path = Path(audio_file_path)
        audio_format = file_path.suffix.lower().lstrip(".")
        
        logger.info(f"Calling Baidu ASR with URL: {speech_url}")
        
        return await self._baidu_asr_client.transcribe_audio(
            speech_url=speech_url,
            audio_format=audio_format,
            enable_smooth_text=True
        )
    
    async def optimize_text(self, text: str) -> str:
        """
        优化文本，使其更自然流畅
        
        使用百度千帆大模型 API
        
        Args:
            text: 待优化的文本
            
        Returns:
            str: 优化后的文本
            
        Raises:
            LLMAPIError: 如果优化失败
        """
        if not self._qianfan_client:
            raise LLMAPIError("百度千帆 API 未配置")
        
        return await self._qianfan_client.text_optimization(text)
    
    async def analyze_voice_emotion(self, audio_path: str) -> dict:
        """
        分析语音情感
        
        注意：此方法需要先将音频转录为文本，然后使用百度 NLP 对话情绪识别 API
        分析文本中的情绪。如果需要直接分析音频，请先调用 speech_to_text。
        
        Args:
            audio_path: 音频文件路径（此参数保留用于接口兼容，实际不使用）
            
        Returns:
            dict: 情感分析结果
            
        Raises:
            VoiceEmotionAPIError: 如果分析失败
        """
        # 由于百度没有直接的音频情感分析 API，这里返回降级结果
        # 实际的情感分析应该在转录文本后调用 analyze_text_emotion
        logger.warning("Direct audio emotion analysis not supported, use analyze_text_emotion instead")
        return {
            "emotion_type": "中性",
            "score": 50,
            "confidence": 0.0
        }
    
    async def analyze_text_emotion(self, text: str) -> dict:
        """
        分析文本情感
        
        使用百度 NLP 对话情绪识别 API 分析文本中的情绪
        
        Args:
            text: 待分析的文本（通常是转录后的文本）
            
        Returns:
            dict: 情感分析结果
                - emotion_type: 情绪类型（积极/消极/中性）
                - score: 情绪评分（1-100）
                - confidence: 置信度（0-1）
            
        Raises:
            VoiceEmotionAPIError: 如果分析失败
        """
        if not self._baidu_nlp_client:
            raise VoiceEmotionAPIError("百度 NLP API 未配置")
        
        if not self._text_emotion_enabled:
            logger.warning("Text emotion analysis is disabled")
            return {
                "emotion_type": "中性",
                "score": 50,
                "confidence": 0.0
            }
        
        return await self._baidu_nlp_client.emotion_recognition(text)
    
    async def extract_diary_structure(
        self,
        diary_content: str,
        diary_date: str = "",
        weather: str = "",
        location: str = "",
        mood_score: int = 50
    ) -> dict:
        """
        提取日记结构化信息
        
        使用千帆大模型提取日记的关键信息
        
        Args:
            diary_content: 日记内容
            diary_date: 日记日期
            weather: 天气
            location: 地点
            mood_score: 心情评分（1-100）
            
        Returns:
            dict: 提取结果
            
        Raises:
            LLMAPIError: 如果提取失败
        """
        if not self._qianfan_client:
            raise LLMAPIError("百度千帆 API 未配置")
        
        return await self._qianfan_client.extract_diary_info(
            diary_content=diary_content,
            diary_date=diary_date,
            weather=weather,
            location=location,
            mood_score=mood_score
        )
    
    async def analyze_diary_emotion(self, diary_content: str) -> dict:
        """
        详细分析日记情绪
        
        使用千帆大模型进行深入的情绪分析
        
        Args:
            diary_content: 日记内容
            
        Returns:
            dict: 情绪分析结果
            
        Raises:
            LLMAPIError: 如果分析失败
        """
        if not self._qianfan_client:
            raise LLMAPIError("百度千帆 API 未配置")
        
        return await self._qianfan_client.analyze_emotion_detailed(diary_content)
    
    async def identify_diary_highlights(
        self,
        diary_content: str,
        primary_emotion: str = "中性",
        emotion_score: int = 50
    ) -> dict:
        """
        识别日记中的高光时刻和小确幸
        
        使用千帆大模型识别高光时刻和小确幸
        
        Args:
            diary_content: 日记内容
            primary_emotion: 主要情绪
            emotion_score: 情绪评分
            
        Returns:
            dict: 识别结果
            
        Raises:
            LLMAPIError: 如果识别失败
        """
        if not self._qianfan_client:
            raise LLMAPIError("百度千帆 API 未配置")
        
        return await self._qianfan_client.identify_highlights_and_joys(
            diary_content=diary_content,
            primary_emotion=primary_emotion,
            emotion_score=emotion_score
        )


# 单例模式
_external_api_client: Optional[ExternalAPIClient] = None


def get_external_api_client() -> ExternalAPIClient:
    """获取外部 API 客户端单例"""
    global _external_api_client
    if _external_api_client is None:
        _external_api_client = ExternalAPIClient()
    return _external_api_client


def reset_external_api_client():
    """重置外部 API 客户端（用于测试）"""
    global _external_api_client
    _external_api_client = None
