# ============================================================
#  mit-app-api —— 给上层 App 用的翻译服务（数据库/缓存/书页管理）
#  重活（检测/OCR/翻译/抹字/嵌字）仍然是 mit-engine 干，这里不含 torch
#
#  基础镜像用本地已有的 python:3.12-slim，避免拉 Docker Hub
# ============================================================
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONUTF8=1 \
    TZ=Asia/Shanghai

WORKDIR /app

COPY app_api/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -i https://pypi.tuna.tsinghua.edu.cn/simple -r /app/requirements.txt

COPY app_api /app/app_api

EXPOSE 8000

CMD ["uvicorn", "app_api.main:app", "--host", "0.0.0.0", "--port", "8000", "--log-level", "info"]
