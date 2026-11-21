#!/usr/bin/env python3
"""本地假 SMTP 服务器：收一封信存到文件，用来验证 APP 里的 SMTP 客户端。

只实现验证需要的那几条：EHLO / AUTH LOGIN / MAIL / RCPT / DATA / QUIT。
用法: python smtp_sink.py [port] [out.eml]
"""
import base64
import socket
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 2525
OUT = sys.argv[2] if len(sys.argv) > 2 else "received.eml"


def handle(conn):
    f = conn.makefile("rwb")

    def send(line):
        f.write((line + "\r\n").encode())
        f.flush()

    send("220 sink.local ESMTP")
    data_mode = False
    msg = []
    authed_user = None
    while True:
        line = f.readline()
        if not line:
            break
        text = line.decode("utf-8", "replace").rstrip("\r\n")
        if data_mode:
            if text == ".":
                data_mode = False
                with open(OUT, "w", encoding="utf-8") as out:
                    out.write("\n".join(msg))
                print("[sink] 收到邮件 %d 行 -> %s" % (len(msg), OUT), flush=True)
                print("[sink] 认证用户: %s" % authed_user, flush=True)
                send("250 2.0.0 Ok: queued")
                continue
            msg.append(text[1:] if text.startswith("..") else text)
            continue
        up = text.upper()
        if up.startswith("EHLO") or up.startswith("HELO"):
            send("250-sink.local")
            send("250-8BITMIME")
            send("250 AUTH LOGIN PLAIN")
        elif up.startswith("AUTH LOGIN"):
            send("334 VXNlcm5hbWU6")
            u = f.readline().decode().strip()
            send("334 UGFzc3dvcmQ6")
            p = f.readline().decode().strip()
            authed_user = base64.b64decode(u).decode() if u else ""
            _ = base64.b64decode(p).decode() if p else ""
            send("235 2.7.0 Authentication successful")
        elif up.startswith("MAIL FROM"):
            send("250 2.1.0 Ok")
        elif up.startswith("RCPT TO"):
            print("[sink] RCPT: " + text, flush=True)
            send("250 2.1.5 Ok")
        elif up.startswith("DATA"):
            send("354 End data with <CR><LF>.<CR><LF>")
            data_mode = True
        elif up.startswith("QUIT"):
            send("221 2.0.0 Bye")
            break
        else:
            send("250 Ok")
    conn.close()


def main():
    import threading
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", PORT))
    srv.listen(8)
    print("[sink] 监听 127.0.0.1:%d" % PORT, flush=True)
    n = 0
    srv.settimeout(60)
    while True:
        try:
            conn, _ = srv.accept()
        except socket.timeout:
            break
        n += 1
        t = threading.Thread(target=handle, args=(conn,), daemon=True)
        t.start()
    srv.close()
    print("[sink] 结束，处理了 %d 个连接" % n, flush=True)


if __name__ == "__main__":
    main()
