FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml README.md ./
COPY orderpilot_ai_worker ./orderpilot_ai_worker
RUN pip install --no-cache-dir . \
    && groupadd --system app \
    && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app \
    && chown -R app:app /app
USER app
CMD ["python", "-m", "orderpilot_ai_worker.main"]