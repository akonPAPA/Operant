FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml README.md ./
COPY orderpilot_ai_worker ./orderpilot_ai_worker
RUN pip install --no-cache-dir .
CMD ["python", "-m", "orderpilot_ai_worker.main"]