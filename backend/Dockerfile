FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /srv

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

EXPOSE 8000

# Durable auth/sync state (SQLite). Mount a volume at /srv/data and point
# FOX_DB_PATH there to persist accounts/tokens/sync items across restarts:
#   docker run -v foxtrader-data:/srv/data -e FOX_DB_PATH=/srv/data/foxtrader.db ...
ENV FOX_DB_PATH=/srv/data/foxtrader.db
RUN mkdir -p /srv/data

# Serve the ASGI app. Override FOX_PROVIDER / FOX_CORS_ORIGINS via env.
CMD ["uvicorn", "app.api:app", "--host", "0.0.0.0", "--port", "8000"]
