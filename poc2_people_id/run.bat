@echo off
echo =============================================================
echo   INICIANDO SERVIDOR BACKEND LOCAL (FASTAPI + GPU CUDA)
echo =============================================================
echo Ativando ambiente Conda base1...

:: Tenta ativar o conda base1
call d:\miniforge3\condabin\activate.bat base1
if %errorlevel% neq 0 (
    echo Falha ao ativar ambiente base1 via condabin, tentando via Scripts...
    call d:\miniforge3\Scripts\activate.bat base1
)

echo.
echo Verificando e instalando dependencias (fastapi, uvicorn, facenet-pytorch, pyttsx3)...
python -m pip install -r requirements.txt

echo.
echo Iniciando servidor FastAPI local...
echo O backend estara escutando em http://localhost:8000 e na sua rede local.
uvicorn backend.main:app --host 0.0.0.0 --reload --port 8000
