from pydantic import BaseModel
from datetime import datetime
from typing import Optional

class LogEntryBase(BaseModel):
    glasses_id: str
    audio_transcript: Optional[str] = None
    image_path: Optional[str] = None

class LogEntryCreate(LogEntryBase):
    pass

class LogEntry(LogEntryBase):
    id: int
    timestamp: datetime

    class Config:
        orm_mode = True
