FROM python:3.13-slim
WORKDIR /app
COPY server/requirements.lock.txt /app/requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY server /app/server
COPY docs/samples /app/docs/samples
RUN useradd --create-home guard
USER guard
CMD ["sh", "-c", "uvicorn server.app:app --host 0.0.0.0 --port ${PORT:-8080} --no-access-log"]
