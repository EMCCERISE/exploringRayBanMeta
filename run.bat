@echo off
echo Iniciando o Servidor Backend (FastAPI)...
start cmd /k "D:\anaconda3\Scripts\activate.bat && pip install fastapi uvicorn sqlalchemy python-multipart requests && uvicorn backend.main:app --reload --port 8000"

echo Iniciando o Frontend (React/Vite)...
start cmd /k "cd frontend && npm run dev"

echo.
echo Tudo rodando! 
echo Frontend disponivel em http://localhost:5173
echo Backend disponivel em http://localhost:8000
