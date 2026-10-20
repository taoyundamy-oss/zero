FROM python:3.12-slim

WORKDIR /app

COPY gateway/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt -i https://mirrors.aliyun.com/pypi/simple/

# S5.1：构建上下文改为仓库根（compose context: .）——ssrf 单源经 common/ 并入
COPY gateway/ .
COPY common/ssrf.py ./ssrf.py

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
