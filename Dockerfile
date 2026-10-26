FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 DATA_DIR=/data
WORKDIR /app
COPY server/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY server/ .
COPY webos/ /app/webos_player/
VOLUME /data
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8000/healthz')" || exit 1
CMD ["gunicorn","-w","1","--threads","16","-b","0.0.0.0:8000","--timeout","900","--access-logfile","-","app:app"]
