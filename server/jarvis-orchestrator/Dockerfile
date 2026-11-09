FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py .
RUN useradd -r -u 10001 jarvis
USER jarvis
EXPOSE 8765
HEALTHCHECK --interval=60s --timeout=5s CMD python -c "import socket;socket.create_connection(('127.0.0.1',8765),3)"
CMD ["python", "app.py"]
