#!/usr/bin/env python3
"""验收栈配置读取的负例：进程环境必须优先于工作区 .env。

真实缺陷（Batch 947）：`read_env` 在 `.env` 不存在时直接返回默认值，
跳过了进程环境。CI 是干净检出、没有 `.env`，于是 nightly soak 的
`POSTGRES_PASSWORD` 被解析为空，demo 启动时以
“SCRAM-based authentication, but no password was provided” 失败；
本地因为存在 `.env` 一直看不到这个问题。
"""

import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "rag_local_stack", Path(__file__).parents[1] / "lib" / "rag_local_stack.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReadEnvTest(unittest.TestCase):
    def read(self, key, default="", env=None, dotenv=None):
        with tempfile.TemporaryDirectory() as root:
            if dotenv is not None:
                Path(root, ".env").write_text(dotenv)
            with patch.object(MODULE, "PROJECT_DIR", Path(root)):
                with patch.dict(os.environ, env or {}, clear=True):
                    return MODULE.read_env(key, default)

    def test_process_environment_wins_over_dotenv(self):
        self.assertEqual(
            "postgres",
            self.read("POSTGRES_PASSWORD", env={"POSTGRES_PASSWORD": "postgres"},
                      dotenv="POSTGRES_PASSWORD=from-file\n"))

    def test_process_environment_applies_when_dotenv_is_absent(self):
        # 回归点：旧实现先判断 .env 是否存在，干净检出直接返回默认值。
        self.assertEqual(
            "postgres",
            self.read("POSTGRES_PASSWORD", default="",
                      env={"POSTGRES_PASSWORD": "postgres"}, dotenv=None))
        self.assertEqual(
            "db.example.test",
            self.read("POSTGRES_HOST", default="localhost",
                      env={"POSTGRES_HOST": "db.example.test"}, dotenv=None))

    def test_dotenv_is_used_when_process_environment_is_absent(self):
        self.assertEqual(
            "file-password",
            self.read("POSTGRES_PASSWORD", dotenv="POSTGRES_PASSWORD=file-password\n"))

    def test_quoted_dotenv_value_is_unwrapped(self):
        self.assertEqual(
            "quoted-password",
            self.read("POSTGRES_PASSWORD",
                      dotenv='POSTGRES_PASSWORD="quoted-password"\n'))

    def test_default_is_used_when_neither_source_declares_the_key(self):
        self.assertEqual(
            "localhost",
            self.read("POSTGRES_HOST", default="localhost", env={},
                      dotenv="POSTGRES_PASSWORD=postgres\n"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
