"""面部表情识别路由（骨架实现，待补充完整API）"""

from fastapi import APIRouter

router = APIRouter(prefix="/face-emotion", tags=["Face Emotion"])

@router.post("/report")
async def report_face_emotion():
    """接收摄像头表情识别结果"""
    # TODO: 实现情绪信号写入 emotion_records 表
    return {"success": True}
