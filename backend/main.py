import os
import shutil
import requests
import time
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

from faster_whisper import WhisperModel

# Inicializa o modelo de STT globalmente (fica na memória)
try:
    print("Carregando WhisperModel (small)...")
    whisper_model = WhisperModel("small", device="cuda", compute_type="float16")
    print("WhisperModel carregado com sucesso na GPU!")
except Exception as e:
    print(f"Aviso: Falha ao carregar Whisper na GPU. Tentando CPU... Erro: {e}")
    whisper_model = WhisperModel("small", device="cpu", compute_type="int8")
    print("WhisperModel carregado na CPU!")

def transcribe_audio_whisper(audio_path: str) -> str:
    """
    Usa o faster-whisper carregado na memória para transcrição super rápida.
    """
    global whisper_model
    try:
        # language="pt" força reconhecimento em português (remova se quiser auto-detect)
        segments, info = whisper_model.transcribe(audio_path, beam_size=5, language="pt")
        return " ".join([segment.text for segment in segments]).strip()
    except Exception as e:
        print(f"Erro na transcrição via Whisper: {e}")
        if "cublas" in str(e).lower() or "cudnn" in str(e).lower() or "cuda" in str(e).lower():
            print("⚠️ DLLs da GPU (CUDA) não encontradas no Windows! Trocando para CPU (modo de segurança)...")
            try:
                whisper_model = WhisperModel("small", device="cpu", compute_type="int8")
                segments, info = whisper_model.transcribe(audio_path, beam_size=5, language="pt")
                return " ".join([segment.text for segment in segments]).strip()
            except Exception as cpu_e:
                print(f"Erro na transcrição via CPU: {cpu_e}")
        
        return "Erro: não foi possível transcrever o áudio."

@app.post("/upload", response_model=schemas.LogEntry)
async def upload_data(
    glasses_id: str = Form(...),
    image: UploadFile = File(...),
    audio: UploadFile = File(...),
    db: Session = Depends(get_db)
):
    timestamp = int(time.time() * 1000)
    
    # 1. Salvar imagem
    image_filename = f"{glasses_id}_{timestamp}_{image.filename}"
    image_path = os.path.join(UPLOAD_DIR, image_filename)
    with open(image_path, "wb") as buffer:
        shutil.copyfileobj(image.file, buffer)

    # 2. Salvar áudio temporariamente
    audio_filename = f"{glasses_id}_{timestamp}_{audio.filename}"
    audio_path = os.path.join(UPLOAD_DIR, audio_filename)
    with open(audio_path, "wb") as buffer:
        shutil.copyfileobj(audio.file, buffer)

    # 3. Transcrever áudio via Whisper
    transcript = transcribe_audio_whisper(audio_path)
    
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
