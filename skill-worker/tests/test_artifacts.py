"""产物规则测试（§4.7 / TC-ART-001~002）：manifest 优先、后缀推断、上限。"""

from __future__ import annotations

from skill_worker.domain import ArtifactFile
from skill_worker.scheduler.artifacts import collect_artifacts, infer_type, over_limit

MAX_FILE = 500 * 1024 * 1024
MAX_TOTAL = 2 * 1024 * 1024 * 1024
DEFAULT_MAX_COUNT = 4


def test_manifest_declares_type_and_meta():
    import json

    files = {
        "manifest.json": json.dumps({
            "files": [
                {"filename": "拆解报告.md", "type": "document", "meta": {"pages": 3}},
                {"filename": "out.bin", "type": "data"},
            ]
        }).encode(),
        "拆解报告.md": "# 报告".encode(),
        "out.bin": b"\x00\x01",
    }
    artifacts = collect_artifacts(files)
    by_name = {a.filename: a for a in artifacts}
    assert by_name["拆解报告.md"].type == "document"
    assert by_name["拆解报告.md"].meta == {"pages": 3}
    assert by_name["out.bin"].type == "data"
    assert "manifest.json" not in by_name  # manifest 本身不是产物


def test_suffix_inference_without_manifest():
    artifacts = collect_artifacts({
        "v.mp4": b"v", "img.png": b"i", "data.json": b"{}", "unknown.xyz": b"?"
    })
    by_name = {a.filename: a for a in artifacts}
    assert by_name["v.mp4"].type == "video"
    assert by_name["img.png"].type == "image"
    assert by_name["data.json"].type == "json"
    assert by_name["unknown.xyz"].type == "other"


def test_infer_type_table():
    assert infer_type("a.MP4") == "video"  # 大小写不敏感
    assert infer_type("noext") == "other"


def test_count_limit_by_max_count():
    config = {"countable": True, "maxCount": 2}
    files = [ArtifactFile(f"f{i}.txt", b"x") for i in range(5)]  # 上限 4
    assert over_limit(files, config, MAX_FILE, MAX_TOTAL, DEFAULT_MAX_COUNT) is not None
    assert over_limit(files[:4], config, MAX_FILE, MAX_TOTAL, DEFAULT_MAX_COUNT) is None


def test_count_limit_non_countable_default():
    assert over_limit([ArtifactFile(f"f{i}.txt", b"x") for i in range(5)],
                      {"countable": False}, MAX_FILE, MAX_TOTAL, 4) is not None


def test_single_file_and_total_limits():
    big = ArtifactFile("big.bin", b"x" * (MAX_FILE + 1))
    assert "单文件" in over_limit([big], {}, MAX_FILE, MAX_TOTAL, 4)

    files = [ArtifactFile(f"f{i}.bin", b"x" * (MAX_TOTAL // 2)) for i in range(3)]
    assert "总量" in over_limit(files, {"countable": True, "maxCount": 5},
                                MAX_FILE, MAX_TOTAL, 4)
