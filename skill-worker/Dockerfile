# scheduler 镜像（Deployment 常驻）
FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
# 核心依赖；rocketmq-client-python 依赖本地 librocketmq，按部署环境安装后取消注释
RUN pip install --no-cache-dir pymysql redis boto3 kubernetes

COPY skill_worker/ ./skill_worker/

# 沙箱 PVC 工作区（与 Job 共享：input.json / package / artifacts/）
VOLUME ["/workspace"]

CMD ["python", "-m", "skill_worker.scheduler.main"]
