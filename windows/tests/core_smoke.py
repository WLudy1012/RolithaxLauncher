import base64
import ctypes
import hashlib
import json
import struct
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


KEY = "smoke-fixture-key"
LOG = "控制台连接成功"
PAPER_LOG = "There are 2 of a max of 20 players online: Steve, Alex"
FILE = "motd=测试服\n".encode()
HUB = "/api/hubs/instanceControlHub"
COMMAND = threading.Event()
RAW_COMMAND = threading.Event()
ACTION = threading.Event()
REDIRECTED = threading.Event()


class Daemon(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def reply(self, code, body, content_type="application/json", headers=()):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for key, value in headers:
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def envelope(self, data, code=200, message="OK"):
        self.reply(code, json.dumps(dict(code=code, message=message, data=data)).encode())

    def authorized(self):
        if self.headers.get("x-api-key") == KEY:
            return True
        self.envelope(None, 401, "缺少测试凭据")
        return False

    def do_GET(self):
        url = urlsplit(self.path)
        if url.path == "/redirect-target":
            REDIRECTED.set()
            self.envelope({})
            return
        if not self.authorized():
            return
        if url.path == "/api/status":
            self.envelope(dict(version="fixture"))
        elif url.path == "/redirect/api/status":
            self.reply(302, b"", headers=(("Location", "/redirect-target"),))
        elif url.path == "/api/instance/list":
            self.envelope([dict(id=7, name="测试服", status=2)])
        elif url.path == "/api/files/instance/7/download" and parse_qs(url.query).get("path") == ["服务器.properties"]:
            self.reply(200, FILE, "application/octet-stream")
        elif url.path == HUB and parse_qs(url.query).get("id") == ["fixture-hub"] and self.headers.get("Upgrade", "").lower() == "websocket":
            self.console()
        else:
            self.envelope(None, 404)

    def do_POST(self):
        if not self.authorized():
            return
        path = urlsplit(self.path).path
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if path == "/api/instance/action":
            if json.loads(body) == dict(id=7, action="stop"):
                ACTION.set()
                self.envelope(None)
            else:
                self.envelope(None, 400)
        elif path == f"{HUB}/negotiate":
            self.reply(200, json.dumps(dict(
                connectionToken="fixture-hub",
                availableTransports=[dict(transport="WebSockets", transferFormats=["Text"])],
            )).encode())
        else:
            self.envelope(None, 404)

    def console(self):
        key = self.headers["Sec-WebSocket-Key"]
        accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.close_connection = True
        self.connection.settimeout(10)
        if self.read_message().get("protocol") != "json":
            return
        self.send_message({})
        join = self.read_message()
        if join.get("target") == "ResumeConsole":
            self.send_message(dict(type=3, invocationId=join["invocationId"], error="Unknown hub method 'ResumeConsole'"))
            join = self.read_message()
        if join.get("target") != "JoinGroup" or join.get("arguments") != [7] or not join.get("invocationId"):
            return
        self.send_message(dict(type=3, invocationId=join["invocationId"]))
        self.send_message(dict(type=1, target="ReceiveLog", arguments=[LOG]))
        for command, event in (("say 测试", RAW_COMMAND), ("list", COMMAND)):
            message = self.read_message()
            if message.get("target") != "SendCommand" or message.get("arguments") != [7, command]:
                return
            event.set()
        self.send_message(dict(type=1, target="ReceiveLog", arguments=[PAPER_LOG]))
        try:
            self.rfile.read(2)
        except OSError:
            pass

    def read_message(self):
        first, second = self.rfile.read(2)
        size = second & 127
        if size == 126:
            size = struct.unpack("!H", self.rfile.read(2))[0]
        if first & 15 != 1 or not second & 128 or size > 1024:
            return {}
        mask = self.rfile.read(4)
        body = bytes(value ^ mask[i % 4] for i, value in enumerate(self.rfile.read(size)))
        return json.loads(body.rstrip(b"\x1e"))

    def send_message(self, value):
        frame = json.dumps(value, ensure_ascii=False).encode() + b"\x1e"
        header = bytes((0x81, len(frame))) if len(frame) < 126 else bytes((0x81, 126)) + struct.pack("!H", len(frame))
        self.wfile.write(header + frame)


def verify(library):
    lib = ctypes.CDLL(str(Path(library).resolve()))
    lib.rolithax_abi_version.restype = ctypes.c_int
    lib.rolithax_session_open.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_void_p)]
    lib.rolithax_session_open.restype = ctypes.c_void_p
    lib.rolithax_session_call.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p]
    lib.rolithax_session_call.restype = ctypes.c_void_p
    lib.rolithax_session_close.argtypes = [ctypes.c_void_p]
    lib.rolithax_session_close.restype = None
    lib.rolithax_string_free.argtypes = [ctypes.c_void_p]
    lib.rolithax_string_free.restype = None

    def read(pointer):
        assert pointer, "missing bridge response"
        try:
            return json.loads(ctypes.string_at(pointer))
        finally:
            lib.rolithax_string_free(pointer)

    def encoded(value):
        return json.dumps(value, ensure_ascii=False).encode()

    assert lib.rolithax_abi_version() == 1
    error = ctypes.c_void_p()
    assert not lib.rolithax_session_open(b'{"baseUrl":"http://127.0.0.1"}', ctypes.byref(error))
    assert not read(error)["ok"]
    with ThreadingHTTPServer(("127.0.0.1", 0), Daemon) as server:
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        address = f"http://127.0.0.1:{server.server_port}"

        def open_session(path=""):
            cfg = dict(baseUrl=address + path, apiKey=KEY, allowHttp=True)
            pointer = lib.rolithax_session_open(encoded(cfg), ctypes.byref(error))
            assert pointer, read(error)
            return pointer

        session = open_session()

        def request(name, **args):
            return read(lib.rolithax_session_call(session, name.encode(), encoded(args)))

        def call(name, **args):
            reply = request(name, **args)
            assert reply["ok"], reply
            return reply["data"]

        try:
            assert call("status")["version"] == "fixture"
            assert call("instances")[0]["name"] == "测试服"
            call("action", id=7, action="stop")
            assert ACTION.is_set()
            with tempfile.TemporaryDirectory() as directory:
                destination = Path(directory) / "服务器.properties"
                destination.write_bytes(b"previous")
                call("downloadFile", id=7, path="服务器.properties", localPath=str(destination))
                assert destination.read_bytes() == FILE
                assert list(Path(directory).iterdir()) == [destination]
            epoch = call("consoleStart", id=7)["streamEpoch"]
            console = dict(id=7, streamEpoch=epoch)
            for _ in range(100):
                snapshot = call("consolePoll", **console, afterSequence=0, afterPanelRevision=-1)
                if snapshot["connected"] and snapshot["lines"]:
                    break
                time.sleep(0.1)
            assert snapshot["lines"] == [dict(sequence=1, line=LOG)], snapshot
            panel = snapshot["panel"]
            assert panel["pluginId"] == "rolithax.paper"
            unchanged = call("consolePoll", **console, afterSequence=1, afterPanelRevision=panel["revision"])
            assert not unchanged["lines"] and "panel" not in unchanged
            call("consoleSend", **console, command="say 测试")
            assert RAW_COMMAND.wait(5), "raw command did not reach the selected instance"
            action = dict(**console, packageRevision=panel.get("packageRevision", 0), actionId="refresh-players")
            command = call("consolePanelPrepareAction", **action)
            assert command == "list"
            mismatch = request("consolePanelAction", **action, confirmedCommand="stop")
            assert not mismatch["ok"] and not COMMAND.is_set(), mismatch
            call("consolePanelAction", **action, confirmedCommand=command)
            assert COMMAND.wait(5), "command did not reach the selected instance"
            for _ in range(100):
                update = call("consolePoll", **console, afterSequence=1, afterPanelRevision=panel["revision"])
                if update.get("panel"):
                    break
                time.sleep(0.1)
            assert update["lines"] == [dict(sequence=2, line=PAPER_LOG)], update
            assert update["panel"]["revision"] > panel["revision"], update
            assert "Steve" in json.dumps(update["panel"]["values"]), update
            source = dict(
                manifest='{"id":"fixture.paper","version":"1.0.0","sdkVersion":1,"server":"paper"}',
                ui='<panel title="测试"><button id="query" label="查询" action="query"/></panel>',
                rules='commands:\n  query:\n    text: "list"\n',
            )
            assert call("consolePanelInspect", source=source) == {"query": "list"}
            imported = call("consolePanelImport", **console, source=source, allowedCommands=[])
            assert imported["pluginId"] == "fixture.paper"
            assert not request("consolePanelPrepareAction", **console, packageRevision=imported["packageRevision"], actionId="query")["ok"]
            granted = call("consolePanelImport", **console, source=source, allowedCommands=["query"])
            assert call("consolePanelPrepareAction", **console, packageRevision=granted["packageRevision"], actionId="query") == "list"
            assert not request("consolePanelPrepareAction", **console, packageRevision=imported["packageRevision"], actionId="query")["ok"]
            assert not request("consolePanelImport", **console, source=dict(source, ui="<!DOCTYPE panel>"), allowedCommands=[])["ok"]
            unchanged = call("consolePoll", **console, afterSequence=2, afterPanelRevision=granted["revision"])
            assert "panel" not in unchanged
            restored = call("consolePanelRestore", **console)
            assert restored["pluginId"] == "rolithax.paper" and restored["revision"] > granted["revision"]
            assert "Steve" in json.dumps(restored["values"])
            call("consoleStop")
            assert not request("consolePanelPrepareAction", **action)["ok"]
            redirected = open_session("/redirect")
            try:
                reply = read(lib.rolithax_session_call(redirected, b"status", b"{}"))
                assert not reply["ok"] and not REDIRECTED.is_set(), reply
            finally:
                lib.rolithax_session_close(redirected)
        finally:
            lib.rolithax_session_close(session)
            server.shutdown()
    print("Windows C ABI smoke passed (UTF-8, actions, download, SignalR, panel permissions and revisions, redirect rejection)")


if __name__ == "__main__":
    verify(sys.argv[1])
