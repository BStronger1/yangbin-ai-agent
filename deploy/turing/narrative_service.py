"""无 sudo 的 Linux 部署：独立 PostgreSQL / Redis / API / Worker。

Agent 专用实例：来源为 ai-ppt-generator/scripts/server_service.py。
目录固定为 ~/apps/yangbin-agent-ppt，数据库、存储、账号及桥接密钥独立。
仅复用现有运行时二进制；其他应用的数据和服务不变。
先安装 backend 锁定依赖，再使用 runtime/bin/python app/scripts/server_service.py setup。
"""

import argparse
import fcntl
import json
import os
import secrets
import shlex
import subprocess
import time
import urllib.request
from pathlib import Path

ROOT = Path.home() / "apps/yangbin-agent-ppt"
APP = ROOT / "app"
BIN = ROOT / "runtime/bin"
BACKEND = APP / "backend"
CONF = ROOT / "supervisord.conf"


def run(args, **kwargs):
    return subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def private_write(path, content):
    path.write_text(content, encoding="utf-8")
    path.chmod(0o600)


def ctl(*args, check=True):
    return subprocess.run(
        [str(BIN / "supervisorctl"), "-c", str(CONF), *args],
        check=check,
        capture_output=True,
        text=True,
    )


def configure(host, port):
    for folder in ("data/postgres", "data/redis", "data/uploads", "logs", "run"):
        (ROOT / folder).mkdir(parents=True, exist_ok=True, mode=0o700)
    secret_path = ROOT / "secrets.json"
    if not secret_path.exists():
        private_write(
            secret_path,
            json.dumps(
                {name: secrets.token_hex(32) for name in ("postgres", "redis", "jwt")}
            ),
        )
    credentials = json.loads(secret_path.read_text())
    if "model_config" not in credentials:
        credentials["model_config"] = secrets.token_hex(32)
        private_write(secret_path, json.dumps(credentials))
    if "bridge" not in credentials:
        credentials["bridge"] = secrets.token_hex(32)
        private_write(secret_path, json.dumps(credentials))
    model = json.loads((ROOT / "model-config.json").read_text(encoding="utf-8"))
    if not model.get("LLM_API_KEY"):
        raise RuntimeError("Missing LLM configuration")
    environment = {
        **model,
        "APP_ENV": "production",
        "DEMO_MODE": "false",
        "AGENT_EMBED_ENABLED": "true",
        "AGENT_BRIDGE_SECRET": credentials["bridge"],
        "DATABASE_URL": "postgresql+asyncpg://aippt:"
        + credentials["postgres"]
        + "@127.0.0.1:39442/aippt",
        "REDIS_URL": "redis://:" + credentials["redis"] + "@127.0.0.1:39389/0",
        "JWT_SECRET": credentials["jwt"],
        "MODEL_CONFIG_SECRET": credentials["model_config"],
        "CORS_ORIGINS": json.dumps([f"http://{host}:{port}"]),
        "STORAGE_DRIVER": "local",
        "STORAGE_LOCAL_DIR": str(ROOT / "data/uploads"),
    }
    # dotenv 单引号保留 $ 等模型密钥字符；密钥不进入日志或命令行参数。
    private_write(
        BACKEND / ".env",
        "".join(
            f"{key}='" + str(value).replace("\\", "\\\\").replace("'", "\\'") + "'\n"
            for key, value in environment.items()
        ),
    )
    private_write(
        ROOT / "redis.conf",
        f"bind 127.0.0.1\nport 39389\nprotected-mode yes\n"
        f"requirepass {credentials['redis']}\ndir {ROOT / 'data/redis'}\n"
        "appendonly yes\nappendfsync everysec\ndaemonize no\n",
    )
    pgdata = ROOT / "data/postgres"
    if not (pgdata / "PG_VERSION").exists():
        pwfile = ROOT / "run/pg-password"
        private_write(pwfile, credentials["postgres"] + "\n")
        try:
            run(
                [
                    BIN / "initdb",
                    "-D",
                    pgdata,
                    "-U",
                    "aippt",
                    "--pwfile",
                    pwfile,
                    "--encoding=UTF8",
                    "--locale=C.UTF-8",
                    "--auth-local=scram-sha-256",
                    "--auth-host=scram-sha-256",
                ],
                stdout=subprocess.DEVNULL,
            )
        finally:
            pwfile.unlink(missing_ok=True)
    socket_path = ROOT / "run/supervisor.sock"
    common = (
        f"[unix_http_server]\nfile={socket_path}\nchmod=0600\n"
        f"[supervisord]\nlogfile={ROOT / 'logs/supervisord.log'}\n"
        f"pidfile={ROOT / 'run/supervisord.pid'}\nchildlogdir={ROOT / 'logs'}\n"
        "umask=0077\nlogfile_maxbytes=5MB\nlogfile_backups=3\n"
        "[rpcinterface:supervisor]\nsupervisor.rpcinterface_factory="
        "supervisor.rpcinterface:make_main_rpcinterface\n"
        f"[supervisorctl]\nserverurl=unix://{socket_path}\n"
    )
    commands = {
        "postgres": f"{BIN}/postgres -D {pgdata} -h 127.0.0.1 -p 39442 "
        f"-k {ROOT / 'run'} -c max_connections=40 -c shared_buffers=128MB",
        "redis": f"{BIN}/redis-server {ROOT / 'redis.conf'}",
        "api": f"{BACKEND}/.venv/bin/python -m uvicorn app.main:app --host {host} --port {port}",
        "worker": f"{BACKEND}/.venv/bin/python scripts/run_worker.py",
    }
    for index, (name, command) in enumerate(commands.items(), 1):
        common += (
            f"\n[program:{name}]\ncommand={command}\ndirectory={BACKEND}\n"
            f"autostart={'true' if name in ('postgres', 'redis') else 'false'}\n"
            f"autorestart=true\nstartsecs=3\nstartretries=10\npriority={index * 10}\n"
            f"stopwaitsecs={950 if name == 'worker' else 30}\n"
            "stopasgroup=true\nkillasgroup=true\nredirect_stderr=true\n"
            f"stdout_logfile={ROOT / 'logs' / (name + '.log')}\n"
            "stdout_logfile_maxbytes=10MB\nstdout_logfile_backups=3\n"
            'environment=PYTHONUNBUFFERED="1"\n'
        )
    private_write(CONF, common)
    private_write(ROOT / "address.json", json.dumps({"host": host, "port": port}))


def start(migrate=False):
    if ctl("pid", check=False).returncode != 0:
        run([BIN / "supervisord", "-c", CONF])
    else:
        ctl("reread")
        ctl("update")
        ctl("start", "postgres", "redis", check=False)
    for _ in range(60):
        result = subprocess.run(
            [str(BIN / "pg_isready"), "-h", "127.0.0.1", "-p", "39442", "-U", "aippt"],
            capture_output=True,
            check=False,
        )
        if result.returncode == 0:
            break
        time.sleep(1)
    else:
        raise RuntimeError("PostgreSQL did not start; see logs/postgres.log")
    if migrate:
        credentials = json.loads((ROOT / "secrets.json").read_text())
        pg_env = {**os.environ, "PGPASSWORD": credentials["postgres"]}
        base = [
            BIN / "psql",
            "-h",
            "127.0.0.1",
            "-p",
            "39442",
            "-U",
            "aippt",
            "-d",
            "postgres",
        ]
        exists = run(
            base + ["-tAc", "SELECT 1 FROM pg_database WHERE datname='aippt'"],
            env=pg_env,
            capture_output=True,
            text=True,
        ).stdout.strip()
        if exists != "1":
            run(base + ["-c", "CREATE DATABASE aippt"], env=pg_env)
        run([BACKEND / ".venv/bin/alembic", "upgrade", "head"], cwd=BACKEND)
    for name in ("api", "worker"):
        status = ctl("status", name, check=False).stdout
        if "RUNNING" not in status:
            ctl("start", name)
        elif migrate:
            ctl("restart", name)
    address = json.loads((ROOT / "address.json").read_text())
    url = f"http://{address['host']}:{address['port']}"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    # 网络 home 的首次冷启动需要加载大量 Python 模块，允许更长的就绪窗口。
    for _ in range(240):
        try:
            with opener.open(urllib.request.Request(url + "/api/v1/health", headers={"X-Agent-Owner": "admin", "X-Agent-Bridge-Token": json.loads((ROOT / "secrets.json").read_text())["bridge"]}), timeout=5) as response:
                health = json.load(response)
            if health.get("database") == "ok" and health.get("redis") == "ok":
                print(json.dumps(health))
                print(f"AI PPT: {url}")
                print(ctl("status").stdout)
                return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError("Application not healthy; inspect logs")


def install_autostart():
    launcher = ROOT / "start.sh"
    private_write(
        launcher,
        "#!/bin/sh\numask 077\nexec "
        + shlex.join(
            [str(BIN / "python"), str(ROOT / "narrative_service.py"), "start"]
        )
        + " >> "
        + shlex.quote(str(ROOT / "logs/boot.log"))
        + " 2>&1\n",
    )
    launcher.chmod(0o700)
    result = subprocess.run(
        ["crontab", "-l"], capture_output=True, text=True, check=False
    )
    if result.returncode and "no crontab" not in result.stderr.lower():
        raise RuntimeError("Cannot read crontab; autostart was not installed")
    marker = "# yangbin-agent-ppt-user-service"
    existing = result.stdout
    lines = [line for line in existing.splitlines() if not line.endswith(marker)]
    lines.append(f"@reboot /bin/sh {shlex.quote(str(launcher))} {marker}")
    updated = "\n".join(lines) + "\n"
    if updated != existing:
        private_write(ROOT / "crontab.before-aippt", existing)
        run(["crontab", "-"], input=updated, text=True)
    print("Installed user @reboot entry; existing entries preserved")


def stop():
    pid = int(ctl("pid").stdout.strip())
    print(ctl("shutdown").stdout)
    # shutdown 只确认接收请求；等进程释放 socket 后才允许立即重新启动。
    for _ in range(1000):
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return
        time.sleep(1)
    raise RuntimeError("Supervisor shutdown did not complete; inspect logs before restarting")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["setup", "start", "status", "stop"])
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=39811)
    args = parser.parse_args()
    os.umask(0o077)
    (ROOT / "run").mkdir(parents=True, exist_ok=True)
    with (ROOT / "run/deploy.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.action == "setup":
            configure(args.host, args.port)
            start(migrate=True)
            install_autostart()
        elif args.action == "start":
            start()
        elif args.action == "status":
            print(ctl("status", check=False).stdout)
        else:
            stop()


if __name__ == "__main__":
    main()
