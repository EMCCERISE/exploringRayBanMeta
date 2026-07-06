import os
import shutil
import requests
from fastapi import FastAPI, UploadFile, File, Form, Depends, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.orm import Session
from fastapi.staticfiles import StaticFiles

from . import models, schemas
from .database import SessionLocal, engine

# Cria as tabelas no banco de dados
models.Base.metadata.create_all(bind=engine)

app = FastAPI()

# Permite CORS para o frontend local
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Pasta para salvar imagens
UPLOAD_DIR = "uploads"
os.makedirs(UPLOAD_DIR, exist_ok=True)
app.mount("/uploads", StaticFiles(directory=UPLOAD_DIR), name="uploads")

def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()

LMSTUDIO_API_URL = "http://localhost:1234/v1"

def transcribe_audio_lmstudio(audio_path: str) -> str:
    """
    Tenta usar o endpoint do LMStudio (padrão OpenAI) para transcrição.
    Caso o LMStudio não suporte STT nativamente, esta função pode precisar ser 
    adaptada para usar Whisper localmente (ex: biblioteca 'whisper').
    """
    url = f"{LMSTUDIO_API_URL}/audio/transcriptions"
    
    try:
        with open(audio_path, "rb") as f:
            files = {"file": (os.path.basename(audio_path), f, "audio/wav")}
            data = {"model": "local-model"} # Modelo não importa muito para LMStudio local
            response = requests.post(url, files=files, data=data, timeout=30)
            
        if response.status_code == 200:
            return response.json().get("text", "")
        else:
            print(f"Erro no LMStudio: {response.text}")
            return f"Erro na transcrição: {response.status_code}"
    except Exception as e:
        print(f"Exceção ao conectar no LMStudio: {e}")
        return "Erro: não foi possível conectar ao LMStudio."

@app.post("/upload", response_model=schemas.LogEntry)
async def upload_data(
    glasses_id: str = Form(...),
    image: UploadFile = File(...),
    audio: UploadFile = File(...),
    db: Session = Depends(get_db)
):
    # 1. Salvar imagem
    image_filename = f"{glasses_id}_{image.filename}"
    image_path = os.path.join(UPLOAD_DIR, image_filename)
    with open(image_path, "wb") as buffer:
        shutil.copyfileobj(image.file, buffer)

    # 2. Salvar áudio temporariamente
    audio_filename = f"{glasses_id}_{audio.filename}"
    audio_path = os.path.join(UPLOAD_DIR, audio_filename)
    with open(audio_path, "wb") as buffer:
        shutil.copyfileobj(audio.file, buffer)

    # 3. Transcrever áudio
    transcript = transcribe_audio_lmstudio(audio_path)
    
    # (Opcional) apagar o áudio depois se não quiser guardar
    # os.remove(audio_path)

    # 4. Salvar no banco
    db_log = models.LogEntry(
        glasses_id=glasses_id,
        audio_transcript=transcript,
        image_path=f"/uploads/{image_filename}"
    )
    db.add(db_log)
    db.commit()
    db.refresh(db_log)

    return db_log

@app.get("/logs", response_model=list[schemas.LogEntry])
def read_logs(skip: int = 0, limit: int = 100, db: Session = Depends(get_db)):
    logs = db.query(models.LogEntry).order_by(models.LogEntry.timestamp.desc()).offset(skip).limit(limit).all()
    return logs
