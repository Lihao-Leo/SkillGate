"""billing 结算 RPC 适配测试：真实 HTTP 往返（本地 http.server）+ 错误映射。"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from skill_worker.adapters.billing import BillingError, HttpBillingClient


class _Handler(BaseHTTPRequestHandler):
    mode = "ok"

    def do_POST(self):  # noqa: N802 - http.server 约定
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if _Handler.mode == "ok":
            payload = {"code": 0, "message": "成功",
                       "data": {"holdId": "hold_1", "taskId": body["taskId"],
                                "pointsCharged": body["actualPoints"], "status": "SETTLED",
                                "alreadySettled": False}}
            status = 200
        else:
            payload = {"code": 40401, "message": "冻结单不存在", "data": None}
            status = 404
        response = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)

    def log_message(self, *args):  # 静默测试服务器日志
        pass


@pytest.fixture(scope="module")
def server():
    httpd = HTTPServer(("127.0.0.1", 0), _Handler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()


def test_settle_success_parses_envelope(server):
    client = HttpBillingClient(server, "dev-internal-token", timeout_seconds=2)
    outcome = client.settle("task_http", 70)
    assert outcome.points_charged == 70
    assert outcome.status == "SETTLED"
    assert outcome.already_settled is False


def test_settle_http_error_raises_billing_error(server):
    _Handler.mode = "error"
    try:
        client = HttpBillingClient(server, "dev-internal-token", timeout_seconds=2)
        with pytest.raises(BillingError, match="40401"):
            client.settle("task_missing", 10)
    finally:
        _Handler.mode = "ok"


def test_settle_transport_error_raises_billing_error():
    client = HttpBillingClient("http://127.0.0.1:1", "token", timeout_seconds=0.5)
    with pytest.raises(BillingError, match="settle rpc failed"):
        client.settle("task_x", 10)
