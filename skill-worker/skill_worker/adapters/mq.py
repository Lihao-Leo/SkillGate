"""MQ 端口：skill-execute 消费 / skill-settle 与回调重试生产（RocketMQ 延迟做退避）。

生产实现：RocketMQ 5 官方 gRPC 客户端（rocketmq-python-client），经 Proxy（默认 8081）
收发——与 gateway 的 remoting(9876) 共存同一 broker。延迟消息用 delivery_timestamp
（延迟等级 5/9/14 = 1/5/15 分钟，与回退策略一致）。
"""

from __future__ import annotations

import json
import logging
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Protocol

logger = logging.getLogger(__name__)

DELAY_LEVEL_SECONDS = {5: 60, 9: 300, 14: 900}


class MqPort(Protocol):
    def send(self, topic: str, payload: dict[str, Any], delay_level: int = 0) -> None: ...


@dataclass
class InMemoryMq:
    """进程内实现（测试）：记录全部生产消息，供断言；consume 消费指定 topic 队列。"""

    sent: list["SentMessage"] = field(default_factory=list)
    queues: dict[str, list[str]] = field(default_factory=dict)

    @dataclass
    class SentMessage:
        topic: str
        payload: dict
        delay_level: int

    def send(self, topic: str, payload: dict[str, Any], delay_level: int = 0) -> None:
        self.sent.append(self.SentMessage(topic, payload, delay_level))
        self.queues.setdefault(topic, []).append(json.dumps(payload))

    def consume(self, topic: str) -> str | None:
        queue = self.queues.get(topic, [])
        return queue.pop(0) if queue else None

    def messages_of(self, topic: str) -> list[dict]:
        return [m.payload for m in self.sent if m.topic == topic]


class RocketMqAdapter:
    """RocketMQ 5 gRPC：send（含延迟）+ subscribe（后台轮询线程，at-least-once）。

    handler 抛异常 = 不 ack（SimpleConsumer 不可见超时后自动重投），
    幂等由条件更新 / hold 状态 / taskId 消化。
    """

    def __init__(self, proxy_endpoint: str, producer_group: str = "skill_worker_producer"):
        from rocketmq import ClientConfiguration, Credentials, Producer  # type: ignore

        config = ClientConfiguration(proxy_endpoint, Credentials())
        self._producer = Producer(config)
        self._producer.startup()
        self._config = config
        self._threads: list[threading.Thread] = []
        logger.info("rocketmq adapter ready: endpoint=%s", proxy_endpoint)

    def send(self, topic: str, payload: dict[str, Any], delay_level: int = 0) -> None:
        from rocketmq import Message  # type: ignore

        message = Message()
        message.topic = topic
        message.body = json.dumps(payload).encode()
        if delay_level:
            seconds = DELAY_LEVEL_SECONDS.get(delay_level, 60)
            message.delivery_timestamp = int((time.time() + seconds) * 1000)
        send_receipt = self._producer.send(message)
        logger.info("mq sent: topic=%s message_id=%s", topic,
                    getattr(send_receipt, "message_id", "?"))

    def subscribe(self, topic: str, group: str, handler,
                  invisible_seconds: int = 120, renew_interval: int = 30) -> None:
        """订阅 topic：receive → handler(body_bytes) → ack。

        长任务模式：不可见期默认 120s，处理期间每 renew_interval 秒续期一次
        （change_invisible_duration），防止处理超过窗口后 receipt 失效（40013）
        与消息重复投递。handler 异常：不 ack 并把窗口缩到 5s 快速重投
        （重投的幂等由条件更新 / hold 状态 / taskId 消化）。
        """
        from rocketmq import SimpleConsumer  # type: ignore

        consumer = SimpleConsumer(self._config, group)
        consumer.startup()
        consumer.subscribe(topic)

        def _loop() -> None:
            logger.info("consuming: topic=%s group=%s", topic, group)
            while True:
                try:
                    received = consumer.receive(1, invisible_seconds)
                except Exception:  # noqa: BLE001 - 超时/网络抖动继续轮询
                    time.sleep(1)
                    continue
                messages = received if isinstance(received, list) else [received]
                for message in messages:
                    if message is None:
                        continue
                    self._process(consumer, message, handler,
                                  invisible_seconds, renew_interval)

        thread = threading.Thread(target=_loop, name=f"mq-{group}-{topic}", daemon=True)
        thread.start()
        self._threads.append(thread)

    def _process(self, consumer, message, handler,
                 invisible_seconds: int, renew_interval: int) -> None:
        stop = threading.Event()

        def renew() -> None:
            """处理期间持续续期不可见窗口（长任务防 40013/重复投递）。"""
            while not stop.wait(renew_interval):
                try:
                    consumer.change_invisible_duration(message, invisible_seconds)
                except Exception:  # noqa: BLE001 - 续期失败不致命（receipt 未过期前仍可 ack）
                    pass

        renewer = threading.Thread(target=renew, daemon=True)
        renewer.start()
        try:
            handler(message.body)
            try:
                consumer.ack(message)
            except Exception as error:  # noqa: BLE001 - 处理成功但 ack 失败：仅记录
                logger.warning("ack failed（消息将重投，由 taskId 幂等消化）: %s", error)
        except Exception:  # noqa: BLE001 - 业务失败：缩窗快速重投
            logger.exception("consume failed, will redeliver: %s", message.body[:200])
            try:
                consumer.change_invisible_duration(message, 5)
            except Exception:  # noqa: BLE001
                pass
        finally:
            stop.set()
            renewer.join(timeout=1)
