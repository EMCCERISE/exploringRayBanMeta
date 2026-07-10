import os
import time
import glob
import shutil
import threading
from PIL import Image
import torch
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from facenet_pytorch import MTCNN, InceptionResnetV1
import pyttsx3

# Configuração de Diretórios
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
KNOWN_FACES_DIR = os.path.join(BASE_DIR, "known_faces")
UPLOAD_DIR = os.path.join(BASE_DIR, "uploads")
os.makedirs(KNOWN_FACES_DIR, exist_ok=True)
os.makedirs(UPLOAD_DIR, exist_ok=True)

app = FastAPI()

# Permissões CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Monta o diretório de uploads para servir os arquivos gerados estáticos
app.mount("/uploads", StaticFiles(directory=UPLOAD_DIR), name="uploads")

# Define dispositivo de processamento (GPU se disponível)
device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
print(f"=== [POC 2 BACKEND] Dispositivo de processamento ativo: {device} ===")

# Inicializa modelos FaceNet na GPU/CPU
print("Carregando modelo de detecção facial (MTCNN)...")
mtcnn = MTCNN(keep_all=True, device=device)

print("Carregando modelo de embeddings faciais (InceptionResnetV1)...")
resnet = InceptionResnetV1(pretrained="vggface2", device=device).eval()
print("=== [POC 2 BACKEND] Modelos carregados com sucesso! ===")

# Cache para armazenar os embeddings conhecidos como uma lista de tuplas (nome, embedding)
known_embeddings = []
tts_lock = threading.Lock()

def load_known_faces():
    """
    Varre a pasta known_faces/ e extrai embeddings das fotos conhecidas.
    Apoia múltiplas fotos por pessoa removendo sufixos numéricos (ex: carlos_1 -> Carlos).
    """
    global known_embeddings
    known_embeddings = []
    
    supported_extensions = ["*.jpg", "*.jpeg", "*.png"]
    image_paths = []
    for ext in supported_extensions:
        image_paths.extend(glob.glob(os.path.join(KNOWN_FACES_DIR, ext)))
        image_paths.extend(glob.glob(os.path.join(KNOWN_FACES_DIR, ext.upper())))
        
    print(f"=== [POC 2 BACKEND] Carregando imagens de referência de: {KNOWN_FACES_DIR} ===")
    
    # Criamos um MTCNN dedicado local (keep_all=False) para processar rostos únicos nas imagens de cadastro
    local_mtcnn = MTCNN(keep_all=False, device=device)
    
    for img_path in image_paths:
        filename = os.path.basename(img_path)
        name, _ = os.path.splitext(filename)
        
        # Remove sufixos numéricos como _1, _2, -1, ou 1 no final do nome
        import re
        name_without_suffix = re.sub(r'[-_]?\d+$', '', name)
        # Formata o nome (ex: neto_joao -> Neto Joao)
        formatted_name = name_without_suffix.replace("_", " ").strip().title()
        
        try:
            img = Image.open(img_path).convert("RGB")
            # Detecta e recorta o rosto principal da imagem
            face = local_mtcnn(img)
            
            if face is not None:
                # Extrai embedding do rosto
                emb = resnet(face.unsqueeze(0).to(device)).detach().cpu()
                known_embeddings.append((formatted_name, emb))
                print(f"  [+] Rosto cadastrado com sucesso: {formatted_name} ({filename})")
            else:
                print(f"  [⚠️] Aviso: Nenhum rosto encontrado na imagem de referência: {filename}")
        except Exception as e:
            print(f"  [❌] Erro ao carregar imagem {filename}: {e}")
            
    print(f"=== [POC 2 BACKEND] Cadastro finalizado. Total de rostos ativos: {len(known_embeddings)} ===")

# Inicializa o carregamento dos rostos conhecidos
load_known_faces()

def speak_offline(text: str, filepath: str):
    """
    Utiliza o pyttsx3 com o motor de voz local SAPI5 do Windows para gerar WAV offline.
    """
    with tts_lock:
        try:
            engine = pyttsx3.init()
            engine.setProperty("rate", 155)  # Ajusta a velocidade de fala
            
            # Busca vozes em português instaladas no Windows
            voices = engine.getProperty("voices")
            pt_voice_id = None
            for voice in voices:
                if any(pt in voice.name.lower() or pt in voice.id.lower() for pt in ["portuguese", "brazil", "pt-br", "pt_br", "pt"]):
                    pt_voice_id = voice.id
                    break
            
            if pt_voice_id:
                engine.setProperty("voice", pt_voice_id)
                
            engine.save_to_file(text, filepath)
            engine.runAndWait()
        except Exception as e:
            print(f"  [❌] Erro na geração de áudio offline (pyttsx3): {e}")

@app.post("/identify")
async def identify(
    glasses_id: str = Form(...),
    image: UploadFile = File(...)
):
    timestamp = int(time.time() * 1000)
    
    # 1. Salvar a foto no disco
    image_filename = f"{glasses_id}_{timestamp}_{image.filename}"
    image_path = os.path.join(UPLOAD_DIR, image_filename)
    with open(image_path, "wb") as buffer:
        shutil.copyfileobj(image.file, buffer)
        
    print(f"=== [POC 2 BACKEND] Nova foto recebida. Salvando em {image_filename} ===")
    
    try:
        # 2. Abrir imagem e detectar rostos
        img = Image.open(image_path).convert("RGB")
        width, height = img.size
        
        # Detecta coordenadas e probabilidades de todos os rostos
        boxes, probs = mtcnn.detect(img)
        
        description_text = ""
        
        if boxes is None or len(boxes) == 0:
            description_text = "Não vejo ninguém à sua frente."
            print("  [MTCNN] Nenhum rosto detectado no frame.")
        else:
            print(f"  [MTCNN] Detectados {len(boxes)} rostos.")
            
            # Extrai os recortes faciais como tensores de imagem
            face_tensors = mtcnn(img)
            
            if face_tensors is None:
                description_text = "Não consegui processar a imagem dos rostos detectados."
            else:
                # Garante dimensão correta [N, C, H, W]
                if len(face_tensors.shape) == 3:
                    face_tensors = face_tensors.unsqueeze(0)
                
                # Gera embeddings na GPU
                embeddings = resnet(face_tensors.to(device)).detach().cpu()
                
                people = []
                for i, box in enumerate(boxes):
                    cx = (box[0] + box[2]) / 2  # Centro horizontal do rosto
                    emb = embeddings[i].unsqueeze(0)
                    
                    # Classifica a posição do rosto baseado em sua coordenada X
                    if cx < width / 3:
                        pos = "à esquerda"
                    elif cx > 2 * width / 3:
                        pos = "à direita"
                    else:
                        pos = "à sua frente"
                        
                    # Compara com os rostos conhecidos
                    min_dist = 999.0
                    matched_name = "uma pessoa desconhecida"
                    
                    for name, ref_emb in known_embeddings:
                        # Calcula a distância euclidiana L2
                        dist = torch.dist(emb, ref_emb).item()
                        if dist < min_dist:
                            min_dist = dist
                            # Threshold padrão para o InceptionResnetV1 é 0.85
                            if dist < 0.85:
                                matched_name = name
                                
                    print(f"    Rosto {i}: Pos={pos}, Nome={matched_name}, Dist={min_dist:.4f}")
                    
                    people.append({
                        "name": matched_name,
                        "position": pos,
                        "center_x": cx
                    })
                
                # Ordena as pessoas da esquerda para a direita
                people.sort(key=lambda x: x["center_x"])
                
                # Constrói o texto amigável em português
                if len(people) == 1:
                    p = people[0]
                    if p["name"] == "uma pessoa desconhecida":
                        description_text = f"Há uma pessoa desconhecida {p['position']}."
                    else:
                        description_text = f"A pessoa {p['position']} parece ser seu {p['name']}."
                else:
                    description_text = f"Tem {len(people)} pessoas na sua frente. "
                    descriptions = []
                    for p in people:
                        if p["name"] == "uma pessoa desconhecida":
                            descriptions.append(f"uma pessoa desconhecida {p['position']}")
                        else:
                            descriptions.append(f"seu {p['name']} {p['position']}")
                            
                    # Formata em: "A esquerda está X, no centro Y e à direita Z"
                    # Unindo com vírgulas e "e" na última
                    description_text += "Da esquerda para a direita: " + ", ".join(descriptions[:-1]) + " e " + descriptions[-1] + "."
        
        # 3. Gerar arquivo de áudio local offline (WAV)
        audio_filename = f"tts_{timestamp}.wav"
        audio_path = os.path.join(UPLOAD_DIR, audio_filename)
        
        print(f"  [TTS] Gerando áudio offline: \"{description_text}\"")
        speak_offline(description_text, audio_path)
        
        # 4. Monta a resposta final
        audio_url = f"/uploads/{audio_filename}"
        print(f"=== [POC 2 BACKEND] Concluído! Retornando áudio. ===\n")
        
        return {
            "text": description_text,
            "audio_url": audio_url
        }
        
    except Exception as e:
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"Erro interno de processamento facial: {str(e)}")

@app.post("/reload_faces")
def reload_faces():
    """
    Permite recarregar a pasta de rostos de referência sem precisar reiniciar o FastAPI.
    """
    load_known_faces()
    return {"status": "success", "known_faces_count": len(known_embeddings)}

@app.post("/register")
async def register(
    image: UploadFile = File(...),
    name: str = Form(...),
    relationship: str = Form(...)
):
    timestamp = int(time.time() * 1000)
    
    # Limpa e padroniza os campos para criar o nome do arquivo
    clean_name = name.strip().lower().replace(" ", "_")
    clean_rel = relationship.strip().lower().replace(" ", "_")
    
    if not clean_name or not clean_rel:
        raise HTTPException(status_code=400, detail="Nome e parentesco são obrigatórios.")
        
    filename = f"{clean_rel}_{clean_name}_{timestamp}.jpg"
    filepath = os.path.join(KNOWN_FACES_DIR, filename)
    
    # Salva o arquivo temporariamente na pasta de conhecidos
    with open(filepath, "wb") as buffer:
        shutil.copyfileobj(image.file, buffer)
        
    try:
        # Verifica se há um rosto detectável na foto
        img = Image.open(filepath).convert("RGB")
        local_mtcnn = MTCNN(keep_all=False, device=device)
        face = local_mtcnn(img)
        
        if face is None:
            # Se não encontrar rosto, remove o arquivo e rejeita o cadastro
            os.remove(filepath)
            raise HTTPException(
                status_code=400, 
                detail="Nenhum rosto detectado na foto. Tente tirar a foto novamente com boa iluminação."
            )
            
        # Rosto detectado! Recarrega a base de embeddings locais na memória
        load_known_faces()
        
        formatted_name = f"{clean_rel} {clean_name}".replace("_", " ").title()
        print(f"=== [POC 2 BACKEND] Nova pessoa cadastrada dinamicamente: {formatted_name} ===")
        
        return {
            "status": "success",
            "message": f"{formatted_name} cadastrado com sucesso!"
        }
    except HTTPException:
        raise
    except Exception as e:
        if os.path.exists(filepath):
            os.remove(filepath)
        import traceback
        traceback.print_exc()
        raise HTTPException(status_code=500, detail=f"Erro ao salvar rosto cadastrado: {str(e)}")
