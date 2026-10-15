"""Additive tables for private time letters and the personal memory room."""
from datetime import datetime
from uuid import uuid4
from sqlalchemy import Column, String, Integer, Text, DateTime, BigInteger, ForeignKey, UniqueConstraint
from app.models.database import Base

def uid(): return str(uuid4())
def now(): return datetime.utcnow()

class FutureLetter(Base):
    __tablename__ = 'future_letters'
    id = Column(String(36), primary_key=True, default=uid)
    user_id = Column(BigInteger, ForeignKey('users.id', ondelete='CASCADE'), nullable=False, index=True)
    parent_id = Column(String(36), nullable=True, unique=True)
    request_id = Column(String(36), nullable=False)
    payload = Column(Text, nullable=False)
    opens_at = Column(DateTime, nullable=False)
    timezone = Column(String(50), default='Asia/Shanghai', nullable=False)
    state = Column(String(20), default='draft', nullable=False)
    version = Column(Integer, default=1, nullable=False)
    opened_at = Column(DateTime)
    created_at = Column(DateTime, default=now, nullable=False)
    __table_args__ = (UniqueConstraint('user_id', 'request_id'),)

class LetterAsset(Base):
    __tablename__ = 'future_letter_assets'
    id = Column(String(36), primary_key=True, default=uid)
    letter_id = Column(String(36), ForeignKey('future_letters.id', ondelete='CASCADE'), nullable=False, index=True)
    kind = Column(String(10), nullable=False)
    mime = Column(String(50), nullable=False)
    created_at = Column(DateTime, default=now, nullable=False)

class LetterJob(Base):
    __tablename__ = 'future_letter_jobs'
    id = Column(String(36), primary_key=True, default=uid)
    letter_id = Column(String(36), ForeignKey('future_letters.id', ondelete='CASCADE'), nullable=False, index=True)
    version = Column(Integer, nullable=False)
    state = Column(String(20), default='queued', nullable=False)
    attempts = Column(Integer, default=0, nullable=False)
    error = Column(Text)
    updated_at = Column(DateTime, default=now, nullable=False)

class EmotionRoom(Base):
    __tablename__ = 'emotion_rooms'
    user_id = Column(BigInteger, ForeignKey('users.id', ondelete='CASCADE'), primary_key=True)
    theme = Column(String(20), default='study', nullable=False)
    version = Column(Integer, default=1, nullable=False)

class RoomItem(Base):
    __tablename__ = 'emotion_room_items'
    id = Column(String(36), primary_key=True, default=uid)
    user_id = Column(BigInteger, ForeignKey('users.id', ondelete='CASCADE'), nullable=False, index=True)
    slot = Column(Integer, nullable=False)
    kind = Column(String(20), nullable=False)
    source_type = Column(String(10), nullable=False)
    source_id = Column(String(36), nullable=False)
    payload = Column(Text, nullable=False)
    version = Column(Integer, default=1, nullable=False)
    __table_args__ = (UniqueConstraint('user_id', 'slot'),)

TABLES = [FutureLetter.__table__, LetterAsset.__table__, LetterJob.__table__, EmotionRoom.__table__, RoomItem.__table__]
