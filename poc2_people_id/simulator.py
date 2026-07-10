import os
import requests
import winsound

# URL do servidor local FastAPI da POC 2
URL = "http://localhost:8000/identify"

def generate_dummy_image(path):
    """
    Cria uma imagem JPEG branca válida usando PIL caso não exista nenhuma para teste.
    """
    from PIL import Image
    img = Image.new('RGB', (300, 300), color='white')
    img.save(path, 'JPEG')
    print(f"Imagem de teste gerada com PIL em: {path}")

def run_simulation():
    image_path = "test_image.jpg"
    
    if not os.path.exists(image_path):
        generate_dummy_image(image_path)
        
    print(f"Enviando '{image_path}' para o servidor local: {URL}...")
    
    try:
        # Envia a requisição multipart para o backend
        with open(image_path, "rb") as img_file:
            files = {"image": img_file}
            data = {"glasses_id": "RAYBAN_SIMULATOR_POC2"}
            
            response = requests.post(URL, files=files, data=data)
            
        if response.status_code == 200:
            result = response.json()
            description = result.get("text")
            audio_url_path = result.get("audio_url")
            
            print("\n" + "="*40)
            print("RESPOSTA DO SERVIDOR:")
            print(f"Texto: {description}")
            print(f"URL do Áudio: {audio_url_path}")
            print("="*40 + "\n")
            
            # Download do áudio correspondente
            full_audio_url = f"http://localhost:8000{audio_url_path}"
            print(f"Baixando áudio de retorno: {full_audio_url}...")
            
            audio_response = requests.get(full_audio_url)
            if audio_response.status_code == 200:
                temp_audio_file = "response_voice.wav"
                with open(temp_audio_file, "wb") as aud:
                    aud.write(audio_response.content)
                
                print("Reproduzindo descrição de voz localmente (via winsound)...")
                # Reproduz o arquivo WAV de forma síncrona
                winsound.PlaySound(temp_audio_file, winsound.SND_FILENAME)
                print("Reprodução concluída.")
            else:
                print(f"Erro ao baixar o arquivo de áudio: Status {audio_response.status_code}")
                
        else:
            print(f"Erro no servidor: Código {response.status_code}")
            print(response.text)
            
    except Exception as e:
        print("Erro de conexão. O backend está rodando no endereço correto?")
        print(e)

if __name__ == "__main__":
    run_simulation()
