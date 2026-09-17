"""沙箱端口：K8s Job 生命周期（创建 / 观测 / 删除 / 产物采集）与沙箱规格。"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from typing import Protocol

from ..domain import ArtifactFile, Execution

JOB_SUCCEEDED = "SUCCEEDED"
JOB_FAILED = "FAILED"
JOB_RUNNING = "RUNNING"
JOB_MISSING = "MISSING"


@dataclass
class SandboxSpec:
    """§4.6 沙箱规格：加固容器 + 超时 + 资源档位 + 环境注入。"""

    task_id: str
    image: str
    timeout_seconds: int
    cpu: str
    memory: str
    env: dict[str, str] = field(default_factory=dict)
    workdir: str = ""


def sandbox_spec(execution: Execution, image: str, workspace_root: str,
                 litellm_base_url: str, litellm_api_key: str, models: str) -> SandboxSpec:
    """按 required_abilities 选镜像与资源档位（含 video → 4C8G +ffmpeg），env 注入 §4.5。"""
    workdir = os.path.join(workspace_root, execution.task_id)
    return SandboxSpec(
        task_id=execution.task_id,
        image=image,
        timeout_seconds=execution.timeout_seconds,
        cpu="4", memory="8Gi" if image.endswith("ffmpeg:latest") else "2",  # 简化档位判定由调用方传 image
        env={
            "SKILL_TASK_ID": execution.task_id,
            "SKILL_INPUT": os.path.join(workdir, "input.json"),
            "OPENAI_BASE_URL": litellm_base_url,
            "OPENAI_API_KEY": f"{litellm_api_key}-task-{execution.task_id}",
            "SKILL_MODELS": models,
        },
        workdir=workdir,
    )


class SandboxPort(Protocol):
    def create_job(self, spec: SandboxSpec) -> str:
        """创建 Job（activeDeadlineSeconds=timeoutSeconds），返回 job 名称"""
        ...

    def job_status(self, job_name: str, spec_timeout_seconds: int) -> str:
        """SUCCEEDED / FAILED / RUNNING / MISSING（超时由调用方按时间判定）"""
        ...

    def delete_job(self, job_name: str) -> None: ...

    def stage_input(self, task_id: str, input_json: bytes) -> None:
        """受理侧落库的完整入参下载到工作区 input.json（§4.7 输入注入）"""
        ...

    def stage_package(self, task_id: str, package_bytes: bytes, sha256: str) -> None:
        """Skill 包解压到工作区（sha256 校验失败抛异常）"""
        ...

    def collect_artifacts(self, task_id: str) -> list[ArtifactFile]:
        """采集工作区 artifacts/（manifest 优先由 artifacts 模块解析）"""
        ...


class FakeSandbox:
    """测试用沙箱：可编程结果 + 工作区文件暂存（成功时测试预先写入产物）。"""

    def __init__(self) -> None:
        self.jobs: dict[str, SandboxSpec] = {}
        self.deleted: list[str] = []
        self.workspace: dict[str, dict[str, bytes]] = {}
        self.programmed: dict[str, str] = {}  # task_id → job 终态
        self.fail_sha256: set[str] = set()

    def program(self, task_id: str, status: str) -> None:
        self.programmed[task_id] = status

    def write_artifact(self, task_id: str, filename: str, content: bytes) -> None:
        self.workspace.setdefault(task_id, {})[f"artifacts/{filename}"] = content

    def create_job(self, spec: SandboxSpec) -> str:
        name = f"skill-{spec.task_id}"
        self.jobs[name] = spec
        return name

    def job_status(self, job_name: str, spec_timeout_seconds: int) -> str:
        task_id = job_name.removeprefix("skill-")
        return self.programmed.get(task_id, JOB_RUNNING)

    def delete_job(self, job_name: str) -> None:
        self.deleted.append(job_name)
        self.jobs.pop(job_name, None)

    def stage_input(self, task_id: str, input_json: bytes) -> None:
        self.workspace.setdefault(task_id, {})["input.json"] = input_json

    def stage_package(self, task_id: str, package_bytes: bytes, sha256: str) -> None:
        import hashlib

        actual = hashlib.sha256(package_bytes).hexdigest()
        if actual != sha256:
            raise ValueError(f"package sha256 mismatch: expect {sha256} got {actual}")
        self.workspace.setdefault(task_id, {})["package.zip"] = package_bytes

    def collect_artifacts(self, task_id: str) -> list[ArtifactFile]:
        from ..scheduler.artifacts import collect_artifacts

        # 仅采集 artifacts/ 前缀（工作区还含 input.json / package.zip，K8s 实现同理只扫 artifacts/）
        files = {name.removeprefix("artifacts/"): content
                 for name, content in self.workspace.get(task_id, {}).items()
                 if name.startswith("artifacts/")}
        return collect_artifacts(files)


class K8sSandbox:
    """生产：kubernetes 客户端（Job + 共享 PVC 工作区，scheduler 与 Job 同 PVC）。"""

    def __init__(self, namespace: str, workspace_root: str, pvc_name: str):
        from kubernetes import client as k8s_client  # type: ignore
        from kubernetes import config as k8s_config  # type: ignore

        try:
            k8s_config.load_incluster_config()
        except Exception:
            k8s_config.load_kube_config()
        self._api = k8s_client.BatchV1Api()
        self._core = k8s_client.CoreV1Api()
        self._namespace = namespace
        self._workspace_root = workspace_root
        self._pvc_name = pvc_name

    def create_job(self, spec: SandboxSpec) -> str:
        from kubernetes import client as k8s_client  # type: ignore

        name = f"skill-{spec.task_id}".replace("_", "-").lower()
        job = k8s_client.V1Job(
            metadata=k8s_client.V1ObjectMeta(name=name, namespace=self._namespace),
            spec=k8s_client.V1JobSpec(
                active_deadline_seconds=spec.timeout_seconds,  # §4.6 超时联动
                backoff_limit=0,
                ttl_seconds_after_finished=3600,
                template=k8s_client.V1PodTemplateSpec(
                    spec=k8s_client.V1PodSpec(
                        restart_policy="Never",
                        termination_grace_period_seconds=10,  # grace 10s：Skill 可捕获清理
                        security_context=k8s_client.V1PodSecurityContext(run_as_non_root=True),
                        containers=[k8s_client.V1Container(
                            name="skill",
                            image=spec.image,
                            command=["/usr/local/bin/skill-entrypoint"],
                            working_dir=spec.workdir,
                            env=[k8s_client.V1EnvVar(name=k, value=v)
                                 for k, v in spec.env.items()],
                            resources=k8s_client.V1ResourceRequirements(
                                limits={"cpu": spec.cpu, "memory": spec.memory},
                                requests={"cpu": spec.cpu, "memory": spec.memory},
                            ),
                            security_context=k8s_client.V1SecurityContext(
                                run_as_non_root=True,
                                read_only_root_filesystem=True,   # §4.6 runc 加固
                                allow_privilege_escalation=False,
                                capabilities=k8s_client.V1Capabilities(drop=["ALL"]),
                                seccomp_profile=k8s_client.V1SeccompProfile(
                                    type="RuntimeDefault"),
                            ),
                            volume_mounts=[
                                k8s_client.V1VolumeMount(
                                    name="workspace", mount_path=spec.workdir),
                            ],
                        )],
                        volumes=[k8s_client.V1Volume(
                            name="workspace",
                            persistent_volume_claim=k8s_client.V1PersistentVolumeClaimVolumeSource(
                                claim_name=self._pvc_name),
                        )],
                    ),
                ),
            ),
        )
        self._api.create_namespaced_job(self._namespace, job)
        return name

    def job_status(self, job_name: str, spec_timeout_seconds: int) -> str:
        try:
            job = self._api.read_namespaced_job(job_name, self._namespace)
        except Exception:
            return JOB_MISSING
        if job.status.succeeded:
            return JOB_SUCCEEDED
        if job.status.failed:
            return JOB_FAILED
        return JOB_RUNNING

    def delete_job(self, job_name: str) -> None:
        try:
            self._api.delete_namespaced_job(
                job_name, self._namespace, propagation_policy="Foreground")
        except Exception:
            pass  # 幂等：missing 视同已删

    def stage_input(self, task_id: str, input_json: bytes) -> None:
        workdir = os.path.join(self._workspace_root, task_id)
        os.makedirs(workdir, exist_ok=True)
        with open(os.path.join(workdir, "input.json"), "wb") as handle:
            handle.write(input_json)

    def stage_package(self, task_id: str, package_bytes: bytes, sha256: str) -> None:
        import hashlib

        actual = hashlib.sha256(package_bytes).hexdigest()
        if actual != sha256:
            raise ValueError(f"package sha256 mismatch: expect {sha256} got {actual}")
        workdir = os.path.join(self._workspace_root, task_id)
        os.makedirs(workdir, exist_ok=True)
        with open(os.path.join(workdir, "package.zip"), "wb") as handle:
            handle.write(package_bytes)
        # 解压（路径穿越已在 gateway 上传时校验；此处 zip 内条目再做一次防御）
        import zipfile

        with zipfile.ZipFile(os.path.join(workdir, "package.zip")) as bundle:
            for entry in bundle.namelist():
                if entry.startswith("/") or ".." in entry:
                    raise ValueError(f"illegal zip entry: {entry}")
            bundle.extractall(workdir)

    def collect_artifacts(self, task_id: str) -> list[ArtifactFile]:
        artifacts_dir = os.path.join(self._workspace_root, task_id, "artifacts")
        if not os.path.isdir(artifacts_dir):
            return []
        files: dict[str, bytes] = {}
        for root, _dirs, names in os.walk(artifacts_dir):
            for name in names:
                path = os.path.join(root, name)
                with open(path, "rb") as handle:
                    files[os.path.relpath(path, artifacts_dir)] = handle.read()
        from ..scheduler.artifacts import collect_artifacts

        return collect_artifacts(files)


class LocalSubprocessSandbox:
    """本地开发沙箱：subprocess 直跑，无 K8s 依赖（入口分流与镜像 entrypoint 同构）。

    工作区 {workspace_root}/{task_id}/：input.json + 解压后的包 + artifacts/。
    main.py → python main.py；SKILL.md → 平台 agent_runner（AGENT 技能包零改造）。
    """

    def __init__(self, workspace_root: str, agent_runner_path: str = ""):
        self._root = workspace_root
        self._runner = agent_runner_path
        self._procs: dict[str, tuple] = {}  # job_name -> (Popen, started_at, timeout)

    def _workdir(self, task_id: str):
        from pathlib import Path
        path = Path(self._root) / task_id
        path.mkdir(parents=True, exist_ok=True)
        return path

    def stage_input(self, task_id: str, input_json: bytes) -> None:
        (self._workdir(task_id) / "input.json").write_bytes(input_json)

    def stage_package(self, task_id: str, package_bytes: bytes, sha256: str) -> None:
        import hashlib
        import io
        import zipfile
        from pathlib import Path

        actual = hashlib.sha256(package_bytes).hexdigest()
        if actual != sha256:
            raise ValueError(f"package sha256 mismatch: expect {sha256} got {actual}")
        workdir = self._workdir(task_id)
        with zipfile.ZipFile(io.BytesIO(package_bytes)) as archive:
            for entry in archive.infolist():
                name = entry.filename
                if name.endswith("/") or name.startswith("__MACOSX/"):
                    continue
                target = (workdir / name).resolve()
                if not str(target).startswith(str(workdir.resolve())):
                    raise ValueError(f"包内路径非法（穿越）: {name}")
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(entry))

    def create_job(self, spec: SandboxSpec) -> str:
        """os.posix_spawn 启动（不走 fork——worker 进程内 gRPC 线程会使
        subprocess.Popen 的 fork 死锁，详见日志 fork_posix 警告）。"""
        import os
        import sys
        from pathlib import Path

        workdir = Path(spec.workdir)
        if (workdir / "main.py").exists():
            entry = "main.py"
        elif (workdir / "SKILL.md").exists() and self._runner:
            entry = f'"{self._runner}"'
        else:
            raise ValueError("包缺少入口：需 main.py 或 SKILL.md")

        log_path = workdir / "job.log"
        env = {**os.environ, **spec.env}
        cmd = (f'cd "{workdir}" && exec "{sys.executable}" {entry}')
        log_fd = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
        try:
            pid = os.posix_spawn(
                "/bin/sh", ["/bin/sh", "-c", cmd], env,
                file_actions=[
                    (os.POSIX_SPAWN_DUP2, log_fd, 1),
                    (os.POSIX_SPAWN_DUP2, log_fd, 2),
                ])
        finally:
            os.close(log_fd)
        job_name = f"skill-{spec.task_id}"
        import time
        self._procs[job_name] = (pid, time.time(), spec.timeout_seconds)
        return job_name

    def job_status(self, job_name: str, spec_timeout_seconds: int) -> str:
        import time

        handle = self._procs.get(job_name)
        if handle is None:
            return JOB_MISSING
        pid, started_at, timeout = handle
        try:
            done, status = os.waitpid(pid, os.WNOHANG)
        except ChildProcessError:  # 已被收割（重启等）
            return JOB_MISSING
        if done == 0:
            if time.time() - started_at > timeout:
                import signal
                try:
                    os.kill(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                return JOB_FAILED
            return JOB_RUNNING
        return JOB_SUCCEEDED if os.waitstatus_to_exitcode(status) == 0 else JOB_FAILED

    def delete_job(self, job_name: str) -> None:
        import signal

        handle = self._procs.pop(job_name, None)
        if handle:
            try:
                os.kill(handle[0], signal.SIGKILL)
            except ProcessLookupError:
                pass

    def failure_log(self, task_id: str) -> str | None:
        """失败原因（工作区 job.log 的最后非空行，通常是最内层异常），可读化 error_message。"""
        log_file = self._workdir(task_id) / "job.log"
        if not log_file.exists():
            return None
        lines = [line for line in
                 log_file.read_text(encoding="utf-8", errors="replace").splitlines()
                 if line.strip()]
        return lines[-1][:400] if lines else None

    def collect_artifacts(self, task_id: str) -> list[ArtifactFile]:
        from pathlib import Path

        from ..scheduler.artifacts import collect_artifacts

        artifacts_dir = self._workdir(task_id) / "artifacts"
        files: dict[str, bytes] = {}
        if artifacts_dir.exists():
            for path in sorted(artifacts_dir.rglob("*")):
                if path.is_file():
                    files[str(path.relative_to(artifacts_dir))] = path.read_bytes()
        return collect_artifacts(files)
