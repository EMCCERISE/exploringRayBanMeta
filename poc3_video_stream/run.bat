@echo off
echo =============================================================
echo   INICIANDO SERVIDOR DE STREAMING DE VÍDEO (FASTAPI + GPU)
echo =============================================================
echo Ativando ambiente Conda base1...

call d:\miniforge3\condabin\activate.bat base1
if %errorlevel% neq 0 (
    echo Falha ao ativar ambiente base1 via condabin, tentando via Scripts...
    call d:\miniforge3\Scripts\activate.bat base1
)

echo.
echo Verificando e instalando dependencias (fastapi, uvicorn, websockets, facenet-pytorch, av)...
python -m pip install -r requirements.txt

echo.
echo Iniciando servidor FastAPI...
echo O backend estara escutando conexoes do celular em http://localhost:8000
uvicorn backend.main:app --host 0.0.0.0 --reload --port 8000
