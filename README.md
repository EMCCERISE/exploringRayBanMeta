# 🕶️ Exploring Meta Ray-Ban Smart Glasses - Provas de Conceito (POCs)

Este repositório reúne quatro Provas de Conceito (POCs) desenvolvidas para explorar os recursos, limites e capacidades de integração com os óculos inteligentes **Meta Ray-Ban Smart Glasses** utilizando o **Meta Wearables Device Access Toolkit (DAT) SDK**.

---

## 📌 Visão Geral das POCs

| POC | Nome | Tipo de Inferência | Foco Principal |
| :--- | :--- | :--- | :--- |
| **[POC 1](#-poc-1-audio--image-log-poc1_audio_log)** | **Audio & Image Log** | Server-Side (FastAPI + Whisper) | Diário de campo com fotos e áudios transcritos |
| **[POC 2](#-poc-2-server-side-facial-recognition--spatial-audio-poc2_people_id)** | **Reconhecimento Facial Server-Side** | Server-Side (PyTorch + FaceNet + GPU) | Identificação de pessoas e descrição em áudio espacial |
| **[POC 3](#-poc-3-real-time-video-streaming--server-side-detection-poc3_video_stream)** | **Streaming de Vídeo HEVC** | Server-Side (PyAV + OpenCV + WebSocket) | Stream de vídeo em tempo real com overlay de IA no servidor |
| **[POC 4](#-poc-4-on-device-facial-recognition--spatial-audio-poc4_ondevice_people_id)** | **Reconhecimento Facial On-Device** | 100% Local (ML Kit + TFLite + Android TTS) | Reconhecimento privado offline e áudio local sem servidor |

---

## 🎙️ POC 1: Audio & Image Log (`poc1_audio_log`)

### 1. Descrição
A **POC 1** é uma solução de registro multimídia (foto + áudio). O aplicativo mexe com a captura simultânea de uma imagem e uma gravação de voz enviadas a um backend remoto. O servidor recebe os arquivos, armazena a imagem, transcreve o áudio em tempo real utilizando o modelo **Faster-Whisper** (`small`) e grava o registro cronológico em um banco de dados SQLite. Além disso, conta com um frontend em React (Vite) para visualização dos registros e um simulador Python para testes sem necessidade do óculos físico.

### 2. Limitações
- **Processamento Assíncrono/Pontual**: Funciona por requisições de disparo (foto + áudio), não sendo um monitoramento contínuo de áudio ou vídeo.
- **Dependência de Recursos do Servidor**: Se a GPU CUDA não estiver disponível no servidor, a inferência do Whisper em CPU pode gerar alguns segundos de latência.
- **Banco de Dados Simplificado**: Utiliza SQLite local, ideal para testes e prototipagem, necessitando migração para PostgreSQL/MySQL em cenários de produção.

### 3. Potencial de Uso
- **Diário de Campo Hands-Free**: Ideal para técnicos de manutenção, peritos, inspetores de infraestrutura e médicos em rondas.
- **Evidências Visuais e Audíveis**: Registro imediato de ocorrências acompanhado de anotações por voz transcritas automaticamente.
- **Acessibilidade e Atas de Reunião**: Registro de notas visuais com contexto falado.

### 4. Limitações Impostas pelo Kit da Meta (Wearables DAT SDK)
- **Modo de Captura Sob Demanda**: O SDK exige acionamento pontual para fotos e gravação de áudio.
- **Latência de Transferência Bluetooth/Wi-Fi**: Existe um delay natural na transferência dos arquivos de mídia do óculos para o smartphone via SDK antes de serem despachados ao servidor.
- **Consumo de Bateria**: Transferências frequentes de arquivos de áudio/imagem aumentam o consumo energético dos óculos e do smartphone.

### 5. Como Usar (Rodar)

#### A. Backend (FastAPI + Faster-Whisper)
Navegue até a pasta da POC 1 e execute o script automatizado [run.bat](file:///d:/GitHub/exploringRayBanMeta/poc1_audio_log/run.bat) ou os comandos abaixo:
```bash
cd poc1_audio_log
pip install fastapi uvicorn sqlalchemy python-multipart requests faster-whisper
uvicorn backend.main:app --host 0.0.0.0 --port 8000 --reload
```
- **URL Backend**: `http://localhost:8000` (Documentação em `http://localhost:8000/docs`).

#### B. Frontend (React + Vite)
Em outro terminal:
```bash
cd poc1_audio_log/frontend
npm install
npm run dev
```
- **URL Frontend**: `http://localhost:5173`.

#### C. Simulador Python (Sem o hardware dos óculos)
Para enviar requisições de teste sintéticas:
```bash
python poc1_audio_log/simulator.py
```

#### D. Aplicativo Android
Abra a pasta `poc1_audio_log/AndroidProject` no Android Studio, compile e instale em um dispositivo Android pareado com os óculos Meta Ray-Ban.

---

## 👥 POC 2: Server-Side Facial Recognition & Spatial Audio (`poc2_people_id`)

### 1. Descrição
A **POC 2** realiza a identificação de pessoas no ambiente enviando a foto tirada pelos óculos para um backend em Python (FastAPI). No servidor, utiliza-se a arquitetura **MTCNN** para detecção de rostos e **InceptionResnetV1 (FaceNet)** pré-treinado em VGGFace2 para extrair embeddings faciais de 512 dimensões. O sistema calcula a posição espacial dos rostos (esquerda, centro/frente, direita) com base no centro horizontal da imagem e gera uma resposta em áudio sintetizado em português (`.wav`) via **pyttsx3 / SAPI5**, retornando o áudio para reprodução nos óculos.

### 2. Limitações
- **Latência de Rede (RTT)**: Necessita de envio da imagem via rede, processamento de IA no servidor e download do arquivo de áudio sintetizado.
- **Sensibilidade do Modelo**: Ângulos muito inclinados de rosto, oclusões parciais ou iluminação precária reduzem a precisão do reconhecimento.
- **Sintetizador TTS no Servidor**: O servidor precisa ter vozes em português instaladas no sistema operacional (ex: SAPI5 no Windows) para gerar os arquivos `.wav`.

### 3. Potencial de Uso
- **Assistência a Deficientes Visuais / Cego**: Informa verbalmente e com noção de espaço quem está à sua frente ou aos lados.
- **Auxílio em Prosopagnosia e Alzheimer**: Ajuda o usuário a reconhecer familiares e amigos em tempo real.
- **Eventos e Networking**: Identificação instantânea de contatos ou membros de equipe cadastrados.

### 4. Limitações Impostas pelo Kit da Meta (Wearables DAT SDK)
- **Captura Discreta por Disparo**: Não suporta stream contínuo de alta resolução para o servidor nesta modalidade; requer que a foto seja tirada a cada requisição de identificação.
- **Retorno de Áudio**: O áudio precisa ser transmitido de volta ao celular e reproduzido pelos alto-falantes integrados das hastes do Ray-Ban via Bluetooth A2DP.

### 5. Como Usar (Rodar)

#### A. Backend (FastAPI + PyTorch + MTCNN + FaceNet)
Navegue até a pasta da POC 2 e execute o script [run.bat](file:///d:/GitHub/exploringRayBanMeta/poc2_people_id/run.bat) ou instale as dependências de [requirements.txt](file:///d:/GitHub/exploringRayBanMeta/poc2_people_id/requirements.txt):
```bash
cd poc2_people_id
pip install -r requirements.txt
uvicorn backend.main:app --host 0.0.0.0 --port 8000 --reload
```
- As fotos de referência devem ser salvas no diretório [backend/known_faces](file:///d:/GitHub/exploringRayBanMeta/poc2_people_id/backend/known_faces) com a convenção `parentesco_nome.jpg` (ex: `amigo_carlos.jpg`).
- **Endpoints**: `POST /identify`, `POST /register`, `POST /reload_faces`.

#### B. Simulador Python
Para testar a inferência facial sem os óculos:
```bash
python poc2_people_id/simulator.py
```

#### C. Aplicativo Android
Abra a pasta `poc2_people_id/AndroidProject` no Android Studio, ajuste o endereço IP do seu servidor no arquivo de configuração do app e instale no dispositivo Android.

---

## 📹 POC 3: Real-Time Video Streaming & Server-Side Detection (`poc3_video_stream`)

### 1. Descrição
A **POC 3** explora o streaming de vídeo de baixíssima latência direto da câmera dos óculos para um servidor através de **WebSockets**. O app Android recebe o vídeo do SDK da Meta, empacota os quadros em **HEVC / H.265** (incluindo parâmetros de codec SPS/PPS) e transmite em tempo real. O backend utiliza **PyAV (`av.CodecContext`)** para decodificação em hardware e executa o modelo **MTCNN em GPU (PyTorch/CUDA)** em uma thread assíncrona dedicada, exibindo o feed com caixas de detecção e contagem de pessoas em uma janela OpenCV em tempo real no servidor.

### 2. Limitações
- **Consumo Elevado de Recursos**: Processar vídeo HEVC em tempo real consome banda de rede considerável e exige uma GPU dedicada no servidor.
- **Exibição Local no Servidor**: A janela com a renderização das caixas delimitadoras e contador abre na interface gráfica do servidor onde o OpenCV está sendo executado.
- **Sensibilidade a Perda de Pacotes**: Conexões Wi-Fi/Bluetooth instáveis podem provocar descarte de frames no decodificador H.265.

### 3. Potencial de Uso
- **Monitoramento de Segurança e POV (Point of View)**: Transmissão em tempo real do ponto de vista do operador para centros de controle.
- **Suporte Técnico Remoto com IA**: Permite que especialistas vejam exatamente o que o técnico nos óculos está observando com overlays e contagem automática.
- **Auditoria Operacional em Linhas de Produção**.

### 4. Limitações Impostas pelo Kit da Meta (Wearables DAT SDK)
- **Restrição de Taxa de Quadros no SDK**: O SDK limita o streaming do vídeo a taxas mais baixas (ex: 2 a 15 fps) para evitar superaquecimento dos óculos.
- **Throttling Térmico**: O uso contínuo da câmera por muitos minutos aciona os mecanismos de proteção térmica do hardware Ray-Ban Meta, pausando a câmera temporariamente.

### 5. Como Usar (Rodar)

#### A. Backend (FastAPI + WebSockets + PyAV + OpenCV)
Execute o script [run.bat](file:///d:/GitHub/exploringRayBanMeta/poc3_video_stream/run.bat) na pasta da POC 3:
```bash
cd poc3_video_stream
pip install -r requirements.txt
uvicorn backend.main:app --host 0.0.0.0 --port 8000 --reload
```
- **Endpoint WebSocket**: `ws://0.0.0.0:8000/ws/stream`.
- Ao conectar o app Android, uma janela intitulada `"Ray-Ban Meta Video Stream"` abrirá no servidor mostrando o vídeo processado.

#### B. Aplicativo Android
Abra `poc3_video_stream/AndroidProject` no Android Studio, configure o IP da sua máquina servidor em `MainActivity.kt`, instale no celular e inicie a transmissão.

---

## 📱 POC 4: On-Device Facial Recognition & Spatial Audio (`poc4_ondevice_people_id`)

### 1. Descrição
A **POC 4** representa o estado da arte em privacidade e autonomia ao realizar o pipeline completo de reconhecimento facial e resposta audível **100% On-Device (localmente no smartphone Android)**, sem necessitar de internet ou servidor backend.
- **Detecção Facial**: Executada via **Google ML Kit Face Detection** no dispositivo.
- **Extração de Características**: Modelo **FaceNet em TensorFlow Lite (`MobileFaceNet`)** otimizado para celulares.
- **Áudio Espacial Local**: Sintetizador nativo do Android (**TextToSpeech - TTS**) que narra a localização das pessoas ("À sua frente está seu amigo Carlos", "Da esquerda para a direita aparecem...").
- **Gerenciamento Local**: Permite cadastrar novas pessoas (tirando foto pelos óculos ou galeria), visualizar fotos cadastradas e remover registros diretamente na interface do app Android.
- Inclui um gerador de imagens de teste sintéticas para permitir testes de bancada sem o hardware dos óculos.

### 2. Limitações
- **Precisão do Modelo Móvel**: O modelo `facenet.tflite` possui um vetor de características menor (128d) do que a versão de servidor (512d), apresentando um compromisso entre velocidade e acurácia.
- **Capacidade do Processador Móvel**: Processar muitos rostos em fotos de alta resolução pode aumentar o tempo de resposta em smartphones de entrada.

### 3. Potencial de Uso
- **Funcionamento 100% Offline**: Pode ser utilizado em locais sem cobertura de internet (elevadores, subsolos, áreas rurais, viagens).
- **Privacidade Absoluta (LGPD/GDPR)**: Nenhum dado biométrico ou imagem sai do dispositivo do usuário.
- **Latência Ultra Baixa**: Inferência concluída em menos de 200ms diretamente no aparelho móvel.
- **Assistência Pessoal Contínua**: Autonomia completa para pessoas cegas ou com baixa visão.

### 4. Limitações Impostas pelo Kit da Meta (Wearables DAT SDK)
- **Permissões do SDK**: Exige autorização explícita de permissão da câmera (`Permission.CAMERA`) através da API `Wearables.startRegistration`.
- **Modo de Disparo**: A foto é capturada sob demanda (`stream.capturePhoto()`). Quando a conexão dos óculos é interrompida, o app aciona o modo de fallback com imagens de teste.

### 5. Como Usar (Rodar)

#### A. Requisitos
- **Sem backend ou servidores externos!** O processamento é totalmente feito pelo app Android.

#### B. Aplicativo Android (Kotlin + TFLite + ML Kit)
1. Abra a pasta `poc4_ondevice_people_id/AndroidProject` no Android Studio.
2. Aguarde o Gradle sincronizar as dependências e o arquivo de modelo local TensorFlow Lite (`facenet.tflite`).
3. Conecte um smartphone Android (Android 10 ou superior) pareado com os óculos Meta Ray-Ban através do app **Meta View**.
4. Instale e execute o app (`Run 'app'`).
5. Conceda as permissões solicitadas (Câmera, Microfone, Bluetooth).
6. **Funcionalidades na Tela**:
   - 📸 **Capturar & Identificar**: Tira a foto via óculos, detecta e reconhece os rostos localmente e narra em voz espacial.
   - ➕ **Cadastrar Pessoa**: Registra nome, parentesco e foto (via galeria ou foto dos óculos).
   - 👥 **Ver Rostos**: Lista todos os perfis cadastrados localmente com opção de exclusão.

---

## 🛠️ Tecnologias Utilizadas

- **Dispositivo**: Meta Ray-Ban Smart Glasses
- **SDK de Dispositivo**: Meta Wearables Device Access Toolkit (DAT) SDK para Android
- **Linguagens**: Python (Backend / Simuladores), Kotlin (Android Nativo), JavaScript / React (Frontend)
- **Frameworks Backend**: FastAPI, Uvicorn, WebSockets, PyAV, OpenCV
- **Modelos de IA**:
  - **Speech-to-Text**: Faster-Whisper (`small`)
  - **Detecção Facial**: MTCNN (Server) / Google ML Kit Face Detection (On-Device)
  - **Embeddings Faciais**: InceptionResnetV1 (Server) / MobileFaceNet TensorFlow Lite (On-Device)
- **Síntese de Voz (TTS)**: pyttsx3 / SAPI5 (Server) / Android TextToSpeech Engine (On-Device)
