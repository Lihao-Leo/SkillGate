#!/bin/sh
# skill-worker 前台启动：日志直出控制台（Ctrl+C 停止）
cd "$(dirname "$0")"
# PYTHONUNBUFFERED：print 日志实时输出（避免重定向时的块缓冲看不到日志）
export PYTHONUNBUFFERED=1
exec .venv/bin/python -m skill_worker.scheduler.main
