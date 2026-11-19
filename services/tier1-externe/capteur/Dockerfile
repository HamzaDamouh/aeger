# Simulateur des bracelets (Tier 1). Il tourne normalement directement sur le PC (python simulateur.py ...),
# cette image permet de le lancer aussi dans Docker :
#   docker build -t aeger/capteur:1.0 .
#   docker run --rm -v "$PWD/certs:/app/certs:ro" aeger/capteur:1.0 run --hr 100 --std 500
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY simulateur.py .
ENTRYPOINT ["python", "simulateur.py"]
CMD ["run"]
