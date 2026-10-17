"""Emotion-care recommendations backed by the existing NetEase search service."""
import logging
import httpx
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel
from app.services import music_library, netease_music

router = APIRouter(prefix="/music", tags=["Music"])
logger = logging.getLogger(__name__)
MOODS = {
    "healing": ("温柔治愈", "治愈 纯音乐"),
    "calm": ("平静舒缓", "舒缓 轻音乐"),
    "release": ("放松解压", "放松 纯音乐"),
    "happy": ("轻松愉快", "快乐 轻音乐"),
    "energetic": ("活力元气", "活力 音乐"),
}
class MusicTrackResponse(BaseModel):
    id: str
    title: str
    artist: str
    description: str = ""
    url: str
    duration_sec: int = 0
    mood_tags: list[str]
    icon: str = "music"
    source: str = "netease"
    jump_url: str
    cover_url: str | None = None

class MusicRecommendationResponse(BaseModel):
    mood: str
    mood_name: str
    tracks: list[MusicTrackResponse]
    source: str = "netease"


class MusicPlaybackResponse(BaseModel):
    url: str


@router.get("/tracks/{song_id}/playback", response_model=MusicPlaybackResponse)
async def get_music_playback(song_id: str) -> MusicPlaybackResponse:
    if not song_id.isascii() or not song_id.isdigit() or len(song_id) > 20 or int(song_id) <= 0:
        raise HTTPException(status_code=422, detail="歌曲编号无效")
    try:
        return MusicPlaybackResponse(url=await netease_music.resolve_song_audio(song_id))
    except (httpx.HTTPError, ValueError, TypeError, KeyError) as exc:
        logger.warning("Music playback unavailable: %s", type(exc).__name__)
        raise HTTPException(status_code=503, detail="这首歌暂时无法在应用内播放，请换一首或稍后重试") from exc

@router.get("/netease/recommendations", response_model=MusicRecommendationResponse)
@router.get("/recommendations", response_model=MusicRecommendationResponse)
async def get_music_recommendations(
    mood: str = Query(default="calm"),
    limit: int = Query(default=5, ge=1, le=10),
) -> MusicRecommendationResponse:
    normalized = music_library.normalize_mood(mood)
    mood_name, keyword = MOODS[normalized]
    try:
        songs = await netease_music.search_songs(keyword, limit)
    except (httpx.HTTPError, ValueError, TypeError, KeyError) as exc:
        logger.warning("NetEase recommendations unavailable: %s", type(exc).__name__)
        raise HTTPException(status_code=503, detail="网易云音乐暂时无法连接，请稍后重试") from exc
    if not songs:
        raise HTTPException(status_code=503, detail="暂未获取到网易云歌曲，请稍后重试")
    return MusicRecommendationResponse(
        mood=normalized, mood_name=mood_name,
        tracks=[MusicTrackResponse(
            id=song["note_id"], title=song["title"], artist=song.get("author_name") or "",
            description=song.get("description") or "", url=song["url"],
            jump_url=song["url"], cover_url=song.get("cover_url"), mood_tags=[normalized],
        ) for song in songs[:limit]],
    )
