#!/usr/bin/env python3
"""Controle remoto do HY300-T pelo PC, sem cabo (ADB sobre Wi-Fi).

Só usa a biblioteca padrão do Python e o `adb` no PATH.
Uso:  python hy300_remote.py [ip:porta]      (padrão: 192.168.0.15:5555)

Teclado: setas = D-pad, Enter = OK, Esc/Backspace = Voltar, Home = Início,
         PageUp/PageDown = volume, Espaço = play/pause.
"""
import queue
import re
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import messagebox, simpledialog

DEVICE = sys.argv[1] if len(sys.argv) > 1 else "192.168.0.15:5555"
NOWIN = getattr(subprocess, "CREATE_NO_WINDOW", 0)

KEYS = {"up": 19, "down": 20, "left": 21, "right": 22, "ok": 23, "back": 4, "home": 3,
        "menu": 82, "power": 26, "vol_up": 24, "vol_down": 25, "mute": 164, "play": 85}

APPS = [
    ("HY Home", "com.paulo.hyhome/.MainActivity"),
    ("SmartTube", "org.smarttube.stable"),
    ("Netflix", "com.netflix.mediaclient"),
    ("MX Player", "com.mxtech.videoplayer.ad"),
    ("Keystone", "com.softwinner.tcorrection/com.softwinner.tcorrection.projection.ProjectionSettingActivity"),
]


def adb(*args, timeout=10):
    """Roda `adb -s DEVICE ...` e devolve (código, saída). Nunca levanta exceção."""
    try:
        p = subprocess.run(["adb", "-s", DEVICE, *args], capture_output=True, text=True,
                           timeout=timeout, creationflags=NOWIN)
        return p.returncode, (p.stdout + p.stderr).strip()
    except Exception as e:  # adb ausente, timeout etc.
        return 1, str(e)


def shell(cmd, timeout=10):
    return adb("shell", cmd, timeout=timeout)


class Remote(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(f"HY300-T  {DEVICE}")
        self.resizable(False, False)
        self.status = tk.StringVar(value="Conectando...")
        self.msgs = queue.Queue()
        self.build()
        self.bind_keys()
        threading.Thread(target=self.connect, daemon=True).start()
        self.after(100, self.drain)
        self.after(1000, self.poll)

    # ---------------------------------------------------------------- UI
    def btn(self, parent, text, cmd, w=7, **grid):
        b = tk.Button(parent, text=text, width=w, height=2, command=cmd)
        b.grid(**grid)
        return b

    def build(self):
        tk.Label(self, textvariable=self.status, anchor="w", font=("Segoe UI", 9)).pack(fill="x", padx=8, pady=4)

        pad = tk.Frame(self)
        pad.pack(padx=8, pady=4)
        self.btn(pad, "▲", lambda: self.key("up"), row=0, column=1)
        self.btn(pad, "◄", lambda: self.key("left"), row=1, column=0)
        self.btn(pad, "OK", lambda: self.key("ok"), row=1, column=1)
        self.btn(pad, "►", lambda: self.key("right"), row=1, column=2)
        self.btn(pad, "▼", lambda: self.key("down"), row=2, column=1)

        nav = tk.Frame(self)
        nav.pack(padx=8, pady=2)
        for i, (t, k) in enumerate([("Voltar", "back"), ("Início", "home"), ("Menu", "menu")]):
            self.btn(nav, t, lambda k=k: self.key(k), row=0, column=i)

        media = tk.Frame(self)
        media.pack(padx=8, pady=2)
        for i, (t, k) in enumerate([("Vol −", "vol_down"), ("Mudo", "mute"), ("Vol +", "vol_up"),
                                    ("▶/❚❚", "play")]):
            self.btn(media, t, lambda k=k: self.key(k), w=6, row=0, column=i)

        apps = tk.LabelFrame(self, text="Apps")
        apps.pack(fill="x", padx=8, pady=4)
        for i, (name, target) in enumerate(APPS):
            self.btn(apps, name, lambda t=target: self.open_app(t), w=10, row=i // 3, column=i % 3)

        tools = tk.LabelFrame(self, text="Ferramentas")
        tools.pack(fill="x", padx=8, pady=4)
        self.btn(tools, "Digitar texto", self.type_text, w=12, row=0, column=0)
        self.btn(tools, "Ligar/Desligar tela", lambda: self.key("power"), w=12, row=0, column=1)
        self.btn(tools, "Captura de tela", self.screenshot, w=12, row=1, column=0)
        self.btn(tools, "Espelhar (scrcpy)", self.mirror, w=12, row=1, column=1)
        self.btn(tools, "Reiniciar", self.reboot, w=12, row=2, column=0)

    def bind_keys(self):
        m = {"<Up>": "up", "<Down>": "down", "<Left>": "left", "<Right>": "right", "<Return>": "ok",
             "<Escape>": "back", "<BackSpace>": "back", "<Home>": "home", "<Prior>": "vol_up",
             "<Next>": "vol_down", "<space>": "play"}
        for seq, k in m.items():
            self.bind(seq, lambda e, k=k: self.key(k))

    # ---------------------------------------------------------------- ações
    def bg(self, fn, *a):
        threading.Thread(target=fn, args=a, daemon=True).start()

    def say(self, text):
        """Atualiza a barra de status a partir de qualquer thread (tkinter só aceita a principal)."""
        self.msgs.put(text)

    def drain(self):
        try:
            while True:
                self.status.set(self.msgs.get_nowait())
        except queue.Empty:
            pass
        self.after(100, self.drain)

    def key(self, name):
        self.bg(shell, f"input keyevent {KEYS[name]}")

    def open_app(self, target):
        if "/" in target:
            self.bg(shell, f"am start -n {target}")
        else:  # só o pacote: abre pela categoria de TV
            self.bg(shell, f"monkey -p {target} -c android.intent.category.LEANBACK_LAUNCHER 1")

    def type_text(self):
        text = simpledialog.askstring("Digitar texto", "Texto para enviar ao projetor:", parent=self)
        if not text:
            return
        if "'" in text or not text.isascii():
            messagebox.showinfo("Digitar texto", "Só ASCII sem aspas simples (limite do `input text`).")
            return
        # Entre aspas simples o shell não interpreta nada; o `input text` usa %s para espaço.
        self.bg(shell, "input text '" + text.replace(" ", "%s") + "'")

    def screenshot(self):
        def run():
            shell("screencap -p /data/local/tmp/hy.png")
            code, out = adb("pull", "/data/local/tmp/hy.png", "hy300_captura.png", timeout=20)
            shell("rm /data/local/tmp/hy.png")
            self.say("Captura salva em hy300_captura.png" if code == 0 else f"Falha: {out}")
        self.bg(run)

    def mirror(self):
        try:
            subprocess.Popen(["scrcpy", "-s", DEVICE, "--window-title", "HY300-T"], creationflags=NOWIN)
        except FileNotFoundError:
            messagebox.showinfo("scrcpy", "scrcpy não está instalado (opcional). Veja o README.")

    def reboot(self):
        if messagebox.askyesno("Reiniciar", "Reiniciar o projetor agora?"):
            self.bg(adb, "reboot")
            self.status.set("Reiniciando... reconecte em ~1 minuto")

    # ---------------------------------------------------------------- conexão e status
    def connect(self):
        subprocess.run(["adb", "connect", DEVICE], capture_output=True, creationflags=NOWIN)

    def poll(self):
        def run():
            code, out = shell("grep MemAvailable /proc/meminfo; cat /sys/class/thermal/thermal_zone0/temp",
                              timeout=6)
            if code != 0 or "MemAvailable" not in out:
                self.say("Sem conexão (tentando reconectar...)")
                self.connect()
                return
            mem = int(re.search(r"MemAvailable:\s+(\d+)", out).group(1)) // 1024
            t = re.findall(r"^\d{4,6}$", out, re.M)
            temp = f"  ·  {int(t[-1]) // 1000} °C" if t else ""
            self.say(f"Conectado  ·  RAM livre {mem} MB{temp}")
        self.bg(run)
        self.after(5000, self.poll)


if __name__ == "__main__":
    Remote().mainloop()
