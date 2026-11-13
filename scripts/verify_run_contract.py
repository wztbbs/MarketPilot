#!/usr/bin/env python3
"""本地业务运行恢复验证；自动保留过程证据，不调用线上、真实模型或交易所。"""
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
import uuid
from datetime import datetime, timezone
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
REPORT_NAMES = ("BusinessRunAdapterTest", "BusinessRunMySqlIT")


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def docker(*arguments, env=None):
    return subprocess.run(["docker", *arguments], cwd=ROOT, env=env, check=True,
                          text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.strip()


def capture_sources(root, directory):
    paths = [root / "pom.xml", root / "src/main/resources/db/schema.sql", root / "scripts/verify_run_contract.py"]
    paths += sorted((root / "src/main/java/com/cexpilot/platform").rglob("*.java"))
    paths += sorted((root / "src/test/java/com/cexpilot/platform/run").rglob("*.java"))
    fingerprints = {}
    with zipfile.ZipFile(directory / "source-before-run.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for path in paths:
            relative = path.relative_to(root).as_posix()
            data = path.read_bytes()
            archive.writestr(relative, data)
            fingerprints[relative] = hashlib.sha256(data).hexdigest()
    return fingerprints


def archive_reports(root, directory, earliest_time_ns, sensitive_values=()):
    """去掉系统属性，保留用例、诊断与耗时；拒绝拿旧报告冒充本次结果。"""
    destination = directory / "reports"
    destination.mkdir(exist_ok=True)
    reports = {}
    for name in REPORT_NAMES:
        source = root / "target/surefire-reports" / ("TEST-com.cexpilot.platform.run." + name + ".xml")
        if not source.exists() or source.stat().st_mtime_ns < earliest_time_ns:
            raise RuntimeError("缺少本轮新生成的测试报告：" + name)
        suite = ET.parse(source).getroot()
        for properties in list(suite.findall("properties")):
            suite.remove(properties)
        body = ET.tostring(suite, encoding="unicode")
        for value in sensitive_values:
            body = body.replace(value, "[REDACTED]")
        (destination / (name + ".xml")).write_text(body + "\n")
        reports[name] = {key: suite.attrib.get(key) for key in ("name", "tests", "failures", "errors", "skipped", "time")}
    return reports


def validate_evidence(directory, reports):
    for name in REPORT_NAMES:
        report = reports[name]
        if int(report["tests"]) < 3 or any(int(report[key]) for key in ("failures", "errors", "skipped")):
            raise RuntimeError("测试未完整通过：" + name)
    summary = json.loads((directory / "summary.json").read_text())
    if len(summary["cases"]) != 3 or any(result != "PASS" for result in summary["cases"].values()):
        raise RuntimeError("三个故障场景的过程结果不完整")
    events = [json.loads(line) for line in (directory / "events.jsonl").read_text().splitlines()]
    for scenario in summary["cases"]:
        kills = [e for e in events if e["scenario"] == scenario and e["event"] == "force_kill_finished" and e["details"].get("faultInjection")]
        finishes = [e for e in events if e["scenario"] == scenario and e["event"] == "recovery_process_finished"]
        snapshots = [e for e in events if e["scenario"] == scenario and e["event"] == "state_snapshot"]
        starts = [e for e in events if e["scenario"] == scenario and e["event"] == "process_started"]
        if len(kills) != 1 or kills[0]["details"]["exitCode"] == 0:
            raise RuntimeError("缺少真实强杀退出记录：" + scenario)
        if len(finishes) != 1 or finishes[0]["details"].get("exitCode") != 0:
            raise RuntimeError("缺少恢复 JVM 正常退出记录：" + scenario)
        killed_pid = kills[0]["details"].get("pid")
        recovered_pid = finishes[0]["details"].get("pid")
        if not isinstance(killed_pid, int) or killed_pid <= 0 or not isinstance(recovered_pid, int) or recovered_pid <= 0:
            raise RuntimeError("缺少进程 PID：" + scenario)
        if not any(e["details"].get("mode") == "start" and e["details"].get("pid") == killed_pid for e in starts):
            raise RuntimeError("kill 记录无法关联原 JVM：" + scenario)
        if not any(e["details"].get("mode") == "resume" and e["details"].get("pid") == recovered_pid for e in starts):
            raise RuntimeError("恢复结果无法关联新 JVM：" + scenario)
        if {e["details"]["stage"] for e in snapshots} != {"before_kill", "after_kill", "after_recovery"}:
            raise RuntimeError("缺少故障点状态快照：" + scenario)
        for snapshot in snapshots:
            if not (directory / snapshot["details"]["file"]).is_file():
                raise RuntimeError("状态快照文件缺失：" + scenario)


def recorded_command(command, directory, manifest, stage):
    entry = {"name": stage, "startedAtUtc": utc_now()}
    manifest.setdefault("stages", []).append(entry)
    write_json(directory / "manifest.json", manifest)
    started = time.monotonic()
    with (directory / "runner.log").open("a") as output:
        result = subprocess.run(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
    entry.update(finishedAtUtc=utc_now(), elapsedSeconds=round(time.monotonic() - started, 3), exitCode=result.returncode)
    write_json(directory / "manifest.json", manifest)
    if result.returncode:
        raise subprocess.CalledProcessError(result.returncode, command)


def main():
    record_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:10]
    directory = ROOT / "importants/回归测试/运行恢复" / record_id
    directory.mkdir(parents=True, exist_ok=False)
    manifest = {"format": "run-contract-archive-v1", "recordId": record_id, "startedAtUtc": utc_now(), "status": "RUNNING"}
    created = False
    configuration = None
    environment = None
    name = "cexpilot-run-contract-" + uuid.uuid4().hex[:10]
    start = time.monotonic()
    print("本轮过程记录：" + str(directory), flush=True)
    try:
        manifest["sourceSha256"] = capture_sources(ROOT, directory)
        manifest["gitHead"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        manifest["workingTreeDirty"] = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())
        # 编译先于具体测试；不会默认运行全量单元测试。
        recorded_command(["mvn", "test-compile", "-q"], directory, manifest, "test-compile")
        endpoint = docker("context", "inspect", "--format", "{{.Endpoints.docker.Host}}")
        if not endpoint.startswith("unix://") or os.environ.get("DOCKER_HOST", "").startswith(("tcp:", "ssh:")):
            raise RuntimeError("此脚本只允许本机 Unix socket Docker")
        manifest["dockerVersion"] = docker("version", "--format", "{{.Server.Version}}")
        manifest["mysqlImageId"] = docker("image", "inspect", "--format", "{{.Id}}", "mysql:8.0")
        environment = os.environ.copy()
        environment.update(MYSQL_ROOT_PASSWORD=secrets.token_urlsafe(24), MYSQL_DATABASE="cexpilot_run_contract",
                           MYSQL_USER="run_contract", MYSQL_PASSWORD=secrets.token_urlsafe(24))
        database_started = time.monotonic()
        docker("run", "--rm", "--pull=never", "-d", "--name", name, "--label", "cexpilot.test=run-contract",
               "-p", "127.0.0.1::3306", "-e", "MYSQL_ROOT_PASSWORD", "-e", "MYSQL_DATABASE",
               "-e", "MYSQL_USER", "-e", "MYSQL_PASSWORD", "mysql:8.0", "--skip-log-bin", env=environment)
        created = True
        port = docker("port", name, "3306/tcp")
        if not port.startswith("127.0.0.1:"):
            raise RuntimeError("MySQL 必须只绑定本机地址")
        command = ['docker', 'exec', name, 'sh', '-c',
                   'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h 127.0.0.1 -u "$MYSQL_USER" "$MYSQL_DATABASE" -Nse "SELECT 1"']
        deadline = time.monotonic() + 90
        while True:
            ready = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
            if ready.returncode == 0 and ready.stdout.strip() == "1":
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("独立本地 MySQL 未在限时内准备好")
            time.sleep(1)
        manifest["mysqlStartupSeconds"] = round(time.monotonic() - database_started, 3)
        artifacts = ROOT / "target/run-contract"
        artifacts.mkdir(parents=True, exist_ok=True)
        descriptor, filename = tempfile.mkstemp(prefix="mysql-", suffix=".properties", dir=artifacts)
        configuration = Path(filename)
        with os.fdopen(descriptor, "w") as output:
            output.write("url=jdbc:mysql://127.0.0.1:" + port.rsplit(":", 1)[1]
                         + "/cexpilot_run_contract?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true\n"
                         + "username=run_contract\npassword=" + environment["MYSQL_PASSWORD"] + "\n")
        print("本地 MySQL 就绪，开始故障恢复验证。", flush=True)
        test_start_ns = time.time_ns()
        try:
            recorded_command(["mvn", "test", "-q", "-Dtest=BusinessRunAdapterTest,BusinessRunMySqlIT",
                              "-Drun.mysql.config=" + str(configuration), "-Drun.contract.evidence=" + str(directory)],
                             directory, manifest, "fault-recovery-tests")
        finally:
            manifest["reports"] = archive_reports(ROOT, directory, test_start_ns,
                                                   [environment["MYSQL_PASSWORD"], environment["MYSQL_ROOT_PASSWORD"]])
        validate_evidence(directory, manifest["reports"])
        changed = [path for path, digest in manifest["sourceSha256"].items()
                   if not (ROOT / path).exists() or hashlib.sha256((ROOT / path).read_bytes()).hexdigest() != digest]
        manifest["sourcesChangedDuringRun"] = changed
        if changed:
            raise RuntimeError("执行期间源码变化，不能把该记录视为确定版本的验证")
        manifest["status"] = "PASS"
        print("本地验证通过，完整过程已归档。", flush=True)
    except BaseException as failure:
        manifest["status"] = "FAIL"
        manifest["failureType"] = type(failure).__name__
        raise
    finally:
        if configuration is not None:
            configuration.unlink(missing_ok=True)
        removed = True
        if created:
            removed = subprocess.run(["docker", "rm", "-f", name], cwd=ROOT, stdout=subprocess.DEVNULL,
                                     stderr=subprocess.DEVNULL, check=False).returncode == 0
        manifest["cleanup"] = {"credentialFileDeleted": configuration is None or not configuration.exists(), "containerRemoved": removed}
        manifest["finishedAtUtc"] = utc_now()
        manifest["totalSeconds"] = round(time.monotonic() - start, 3)
        # 仅针对本轮生成的秘密做兜底脱敏，不将任何环境变量表写入记录。
        if environment is not None and (directory / "runner.log").exists():
            log = (directory / "runner.log").read_text()
            for key in ("MYSQL_PASSWORD", "MYSQL_ROOT_PASSWORD"):
                log = log.replace(environment[key], "[REDACTED]")
            (directory / "runner.log").write_text(log)
        write_json(directory / "manifest.json", manifest)


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        print("本地运行契约验证失败：" + str(error), flush=True)
        raise SystemExit(1)
    except subprocess.CalledProcessError as error:
        print("本地运行契约验证失败：" + str(error.cmd[0]) + " 退出码 " + str(error.returncode), flush=True)
        raise SystemExit(1)
