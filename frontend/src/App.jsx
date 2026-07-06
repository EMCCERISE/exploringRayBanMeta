import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { Glasses, Search, ChevronRight, Image as ImageIcon, Mic } from 'lucide-react';
import './index.css';

const API_URL = 'http://localhost:8000';

function App() {
  const [logs, setLogs] = useState([]);
  const [selectedLog, setSelectedLog] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetchLogs();
  }, []);

  const fetchLogs = async () => {
    try {
      const response = await axios.get(`${API_URL}/logs`);
      setLogs(response.data);
      setLoading(false);
    } catch (error) {
      console.error("Erro ao buscar logs", error);
      setLoading(false);
    }
  };

  return (
    <div className="app-container">
      {/* Sidebar / List */}
      <aside className="sidebar">
        <div className="sidebar-header">
          <Glasses className="icon-header" />
          <h1>RayBan Meta Log</h1>
        </div>
        
        <div className="search-bar">
          <Search size={18} />
          <input type="text" placeholder="Buscar registros..." />
        </div>

        <div className="log-list">
          {loading ? (
            <p className="loading-text">Carregando...</p>
          ) : logs.length === 0 ? (
            <p className="empty-text">Nenhum registro encontrado.</p>
          ) : (
            logs.map(log => (
              <div 
                key={log.id} 
                className={`log-item ${selectedLog?.id === log.id ? 'active' : ''}`}
                onClick={() => setSelectedLog(log)}
              >
                <div className="log-item-header">
                  <span className="log-id">#{log.id}</span>
                  <span className="log-time">{new Date(log.timestamp).toLocaleTimeString()}</span>
                </div>
                <div className="log-item-content">
                  <p className="log-transcript-preview">
                    {log.audio_transcript ? log.audio_transcript : "Sem áudio"}
                  </p>
                </div>
                <ChevronRight size={16} className="chevron" />
              </div>
            ))
          )}
        </div>
      </aside>

      {/* Main Content Area */}
      <main className="main-content">
        {selectedLog ? (
          <div className="detail-view">
            <div className="detail-header">
              <h2>Detalhes do Registro #{selectedLog.id}</h2>
              <span className="detail-date">{new Date(selectedLog.timestamp).toLocaleString()}</span>
            </div>

            <div className="cards-container">
              {/* Image Card */}
              <div className="card image-card">
                <div className="card-header">
                  <ImageIcon size={20} />
                  <h3>Imagem Capturada</h3>
                </div>
                <div className="card-body">
                  {selectedLog.image_path ? (
                    <img 
                      src={`${API_URL}${selectedLog.image_path}`} 
                      alt="Captura do óculos" 
                      className="captured-image"
                    />
                  ) : (
                    <div className="empty-image">Sem Imagem</div>
                  )}
                </div>
              </div>

              {/* Audio Card */}
              <div className="card audio-card">
                <div className="card-header">
                  <Mic size={20} />
                  <h3>Transcrição de Áudio</h3>
                </div>
                <div className="card-body">
                  <p className="transcript-text">
                    {selectedLog.audio_transcript ? selectedLog.audio_transcript : "Nenhuma transcrição disponível para este registro."}
                  </p>
                </div>
              </div>
            </div>
          </div>
        ) : (
          <div className="empty-state">
            <Glasses size={64} className="empty-icon" />
            <h2>Selecione um registro</h2>
            <p>Clique em um item na lista lateral para ver os detalhes da captura.</p>
          </div>
        )}
      </main>
    </div>
  );
}

export default App;
