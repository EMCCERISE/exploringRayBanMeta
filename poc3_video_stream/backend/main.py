import os
import cv2
import numpy as np
import torch
import av
import threading
import time
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from facenet_pytorch import MTCNN

app = FastAPI()

# Permissões CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Configura o dispositivo de processamento (GPU se disponível)
device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
print(f"=== [POC 3 BACKEND] Dispositivo de processamento ativo: {device} ===")

# Inicializa o detector facial MTCNN global
print("Carregando modelo de detecção facial (MTCNN)...")
mtcnn = MTCNN(keep_all=True, device=device)
print("=== [POC 3 BACKEND] Modelo MTCNN carregado com sucesso! ===")

# Estado compartilhado para processamento assíncrono (evita lag cumulativo)
latest_img = None
detected_boxes = None
detected_count = 0
state_lock = threading.Lock()
is_running = False

def inference_worker():
    """Thread dedicada para rodar o modelo de IA (MTCNN) sem bloquear a recepção do vídeo."""
    global latest_img, detected_boxes, detected_count, is_running
    print("=== [MTCNN THREAD] Iniciada com sucesso! ===")
    
    while is_running:
        img_to_process = None
        
        # Obtém a cópia do último frame disponível
        with state_lock:
            if latest_img is not None:
                img_to_process = latest_img.copy()
                latest_img = None # Limpa para indicar que este frame já foi pego
                
        if img_to_process is not None:
            try:
                # Executa detecção na GPU
                boxes, probs = mtcnn.detect(img_to_process)
                
                # Atualiza as caixas de detecção globais
                with state_lock:
                    detected_boxes = boxes
                    detected_count = len(boxes) if boxes is not None else 0
            except Exception as e:
                print(f"  [❌ Thread] Erro na detecção: {e}")
        else:
            # Aguarda um curto período para evitar consumo de 100% de CPU à toa
            time.sleep(0.005)
            
    print("=== [MTCNN THREAD] Finalizada. ===")

@app.websocket("/ws/stream")
async def websocket_stream(websocket: WebSocket):
    global latest_img, detected_boxes, detected_count, is_running
    await websocket.accept()
    print("=== [POC 3 BACKEND] Celular conectado via WebSocket! ===")
    
    # Limpa estados anteriores
    with state_lock:
        latest_img = None
        detected_boxes = None
        detected_count = 0
        
    # Inicializa o contexto do decodificador de vídeo HEVC (H.265) do PyAV
    codec = av.CodecContext.create("hevc", "r")
    
    # Inicia a thread de inferência
    is_running = True
    thread = threading.Thread(target=inference_worker, daemon=True)
    thread.start()
    
    try:
        while True:
            # Recebe a mensagem binária contendo o frame
            data = await websocket.receive_bytes()
            if not data or len(data) < 2:
                continue
                
            # Extrai o tipo do frame e a carga útil de vídeo
            frame_type = data[0]
            payload = data[1:]
            
            if frame_type == 0:
                # 0x00: CodecConfig (SPS/PPS) para inicializar o decodificador
                print("  [CodecConfig] Recebidos bytes de configuração do codec. Atualizando extradata...")
                codec.extradata = payload
                codec.parse(payload)
            elif frame_type == 1:
                # 0x01: Frame de vídeo comprimido comum
                packets = codec.parse(payload)
                for packet in packets:
                    try:
                        frames = codec.decode(packet)
                        for frame in frames:
                            # Converte o frame do PyAV em matriz OpenCV (BGR) - extremamente rápido
                            img = frame.to_ndarray(format="bgr24")
                            
                            # Envia o frame atual para a thread de inferência processar quando puder
                            with state_lock:
                                latest_img = img
                                
                            # Desenha os resultados da última detecção disponível (não-bloqueante)
                            with state_lock:
                                boxes_to_draw = detected_boxes
                                count_to_show = detected_count
                                
                            if boxes_to_draw is not None:
                                for box in boxes_to_draw:
                                    x1, y1, x2, y2 = [int(val) for val in box]
                                    cv2.rectangle(img, (x1, y1), (x2, y2), (0, 255, 0), 2)
                                    
                            # Renderiza o contador de pessoas na tela
                            text = f"Pessoas: {count_to_show}"
                            cv2.putText(img, text, (12, 32), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 0, 0), 3, cv2.LINE_AA)
                            cv2.putText(img, text, (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 0), 2, cv2.LINE_AA)
                            
                            # Exibe a imagem processada em tempo real na janela
                            cv2.imshow("Ray-Ban Meta Video Stream", img)
                            cv2.waitKey(1)
                    except Exception as e:
                        print(f"  [❌] Erro ao decodificar frame: {e}")
                        
    except WebSocketDisconnect:
        print("=== [POC 3 BACKEND] Celular desconectado do WebSocket. ===")
    except Exception as e:
        print(f"=== [POC 3 BACKEND] Erro na conexão: {e} ===")
    finally:
        # Para a thread de inferência
        is_running = False
        thread.join(timeout=1.0)
        
        # Garante que a janela do OpenCV seja fechada ao desconectar
        print("Fechando janelas do OpenCV...")
        cv2.destroyAllWindows()
