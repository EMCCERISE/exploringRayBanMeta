from sqlalchemy import Column, Integer, String, DateTime
from sqlalchemy.sql import func
from .database import Base

class LogEntry(Base):
    __tablename__ = "logs"

    id = Column(Integer, primary_key=True, index=True)
    glasses_id = Column(String, index=True)
    audio_transcript = Column(String, nullable=True)
    image_path = Column(String, nullable=True)
    timestamp = Column(DateTime(timezone=True), server_default=func.now())
