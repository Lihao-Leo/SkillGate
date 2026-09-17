"""数据库端口：SQL 用 ? 占位符（PyMySQL 适配层转换为 %s），时间戳一律参数化。"""

from __future__ import annotations

import pymysql.err as pymysql_err

from typing import Any, Protocol


class Database(Protocol):
    def query(self, sql: str, params: tuple = ()) -> list[dict[str, Any]]:
        ...

    def execute(self, sql: str, params: tuple = ()) -> int:
        ...


class SqliteDatabase:
    """测试/本地：sqlite3（schema 与 skill_platform 对齐的子集）。"""

    def __init__(self, connection):
        self._conn = connection
        self._conn.row_factory = lambda cursor, row: {
            desc[0]: value for desc, value in zip(cursor.description, row)
        }

    def query(self, sql: str, params: tuple = ()) -> list[dict[str, Any]]:
        cur = self._conn.execute(sql, params)
        rows = cur.fetchall()
        cur.close()
        return [dict(row) for row in rows]

    def execute(self, sql: str, params: tuple = ()) -> int:
        cur = self._conn.execute(sql, params)
        self._conn.commit()
        rowcount = cur.rowcount
        cur.close()
        return rowcount


class PyMySqlDatabase:
    """生产：pymysql（线程本地连接——MQ 消费/看门狗多线程并发，
    共享单连接会互相踩踏；多副本靠 DB 条件更新防双跑）。

    空闲连接会被 MySQL wait_timeout 断开：执行前 ping 自愈 + 断连异常重建重试一次。
    """

    def __init__(self, host: str, port: int, db: str, user: str, password: str):
        import threading

        import pymysql

        self._config = dict(
            host=host, port=port, db=db, user=user, password=password,
            charset="utf8mb4", autocommit=True,
            cursorclass=pymysql.cursors.DictCursor,
        )
        self._local = threading.local()

    def _connection(self):
        import pymysql

        conn = getattr(self._local, "conn", None)
        if conn is None:
            conn = pymysql.connect(**self._config)
            self._local.conn = conn
        return conn

    @staticmethod
    def _mysql(sql: str) -> str:
        return sql.replace("?", "%s")

    def _run(self, operation: str, sql: str, params: tuple) -> Any:
        for attempt in (1, 2):
            try:
                conn = self._connection()
                conn.ping(reconnect=True)
                with conn.cursor() as cur:
                    if operation == "query":
                        cur.execute(self._mysql(sql), params)
                        return [dict(row) for row in cur.fetchall()]
                    cur.execute(self._mysql(sql), params)
                    conn.commit()
                    return cur.rowcount
            except (pymysql_err.InterfaceError, pymysql_err.OperationalError) as error:
                # 2006/2013/0 = 连接断开：丢弃本线程连接，重建后重试一次
                if attempt == 2 or not _is_connection_error(error):
                    raise
                self._local.conn = None

    def query(self, sql: str, params: tuple = ()) -> list[dict[str, Any]]:
        return self._run("query", sql, params)

    def execute(self, sql: str, params: tuple = ()) -> int:
        return self._run("execute", sql, params)


def _is_connection_error(error: Exception) -> bool:
    code = getattr(error, "args", (None,))[0]
    return code in (0, 2006, 2013) or "Connection" in str(error) or "closed" in str(error)

