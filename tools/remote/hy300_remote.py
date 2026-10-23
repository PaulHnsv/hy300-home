#!/usr/bin/env python3
"""Controle remoto do HY300-T pelo PC, sem cabo (ADB sobre Wi-Fi).

Só usa a biblioteca padrão do Python e o `adb` no PATH.
Uso:  python hy300_remote.py [ip:porta]      (padrão: 192.168.0.15:5555)

Teclado: setas = D-pad, Enter = OK, Esc/Backspace = Voltar, Home = Início,
         PageUp/PageDown = volume, Espaço = play/pause.
Opcional: hy300_remote.json ao lado do script, com {"device": "ip:porta", "apps": [[nome, alvo, cor, letra], ...]}.
"""
import json
import os
import queue
import re
import subprocess
import sys
import threading
import time
import tkinter as tk
from pathlib import Path
from tkinter import messagebox, simpledialog

try:  # nitidez em telas com escala de DPI alta (Windows)
    import ctypes
    ctypes.windll.shcore.SetProcessDpiAwareness(1)
except Exception:
    pass

# ------------------------------------------------------------------ configuração
try:
    CFG = json.loads(Path(__file__).with_name("hy300_remote.json").read_text(encoding="utf-8"))
except Exception:
    CFG = {}

DEVICE = sys.argv[1] if len(sys.argv) > 1 else CFG.get("device", "192.168.0.15:5555")
NOWIN = getattr(subprocess, "CREATE_NO_WINDOW", 0)

KEYS = {"up": 19, "down": 20, "left": 21, "right": 22, "ok": 23, "back": 4, "home": 3,
        "menu": 82, "power": 26, "vol_up": 24, "vol_down": 25, "mute": 164, "play": 85}

# (nome, alvo, cor do ícone, letra). Alvo com "/" = atividade; sem "/" = pacote (abre pela categoria de TV).
APPS = [tuple(a) for a in CFG.get("apps", [
    ("SmartTube", "org.smarttube.stable", "#E5322D", "S"),
    ("Netflix", "com.netflix.mediaclient", "#B20710", "N"),
    ("MX Player", "com.mxtech.videoplayer.ad", "#2D6BD8", "MX"),
    ("Keystone", "com.softwinner.tcorrection/com.softwinner.tcorrection.projection.ProjectionSettingActivity",
     "#1B2029", "⌗"),
    ("TV Bro", "com.phlox.tvwebbrowser", "#2A8F6B", "W"),
    ("HY Home", "com.paulo.hyhome/.MainActivity", "#4FA3FF", "H"),
])]

# Paleta igual à do launcher HY Home.
BG, TILE, HOVER, PRESS = "#0E1116", "#1B2029", "#2A3242", "#3A4660"
ACCENT, TEXT, DIM = "#4FA3FF", "#E8ECF2", "#8A93A3"
GOOD, WARN, BAD = "#3FB950", "#E3B341", "#F85149"
FONT = "Segoe UI"
STATUS_CMD = "grep MemAvailable /proc/meminfo; echo T=$(cat /sys/class/thermal/thermal_zone0/temp)"


# ------------------------------------------------------------------ adb
def adb(*args, timeout=10, device=True):
    """Roda adb e devolve (código, saída). Nunca levanta exceção. 127 = sem adb, 124 = tempo esgotado."""
    cmd = ["adb", "-s", DEVICE, *args] if device else ["adb", *args]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace",
                           timeout=timeout, creationflags=NOWIN)
        return p.returncode, (p.stdout + p.stderr).strip()
    except FileNotFoundError:
        return 127, "adb não encontrado no PATH"
    except subprocess.TimeoutExpired:
        return 124, "tempo esgotado"
    except Exception as e:
        return 1, str(e)


def shell(cmd, timeout=10):
    return adb("shell", cmd, timeout=timeout)


def describe(code, out):
    """Traduz a falha do adb em (estado, mensagem para o usuário)."""
    low = out.lower()
    if code == 127:
        return "noadb", "adb não encontrado. Instale o platform-tools e ponha no PATH."
    if "unauthorized" in low:
        return "unauth", "Aceite a chave do PC na tela do projetor."
    if code == 124 or any(s in low for s in ("offline", "not found", "cannot connect", "no devices",
                                             "failed to connect", "refused", "unreachable")):
        return "offline", "Projetor offline. Tentando reconectar…"
    return "error", (out.splitlines()[-1] if out else "Erro desconhecido")


# ------------------------------------------------------------------ threads
class Dispatcher(threading.Thread):
    """Executa comandos em ordem, um por vez. Teclas seguidas viram uma única chamada ao adb,
    o que evita processos em paralelo (fora de ordem) quando o usuário segura uma tecla."""

    def __init__(self, events):
        super().__init__(daemon=True)
        self.q, self.events = queue.Queue(), events

    def key(self, code):
        self.q.put(("key", code))

    def sh(self, cmd):
        self.q.put(("sh", cmd))

    def run(self):
        pending = None
        while True:
            item = pending or self.q.get()
            pending = None
            if item[0] == "key":
                codes = [item[1]]
                while len(codes) < 10:
                    try:
                        nxt = self.q.get_nowait()
                    except queue.Empty:
                        break
                    if nxt[0] == "key":
                        codes.append(nxt[1])
                    else:
                        pending = nxt
                        break
                code, out = shell("input keyevent " + " ".join(map(str, codes)))
            else:
                code, out = shell(item[1], timeout=20)
            if code != 0:
                self.events.put(("toast", describe(code, out)[1]))


class Link(threading.Thread):
    """Consulta RAM e temperatura e reconecta com espera crescente quando o projetor some."""

    def __init__(self, events):
        super().__init__(daemon=True)
        self.events, self.stop, self.wait = events, threading.Event(), 2

    def run(self):
        while not self.stop.is_set():
            code, out = shell(STATUS_CMD, timeout=6)
            m = re.search(r"MemAvailable:\s+(\d+)", out)
            if code == 0 and m:
                t = re.search(r"T=(\d+)", out)
                self.events.put(("stats", int(m.group(1)) // 1024, int(t.group(1)) // 1000 if t else None))
                self.wait = 3
            else:
                state, msg = describe(code, out)
                self.events.put(("state", state, msg))
                if state in ("offline", "error"):
                    adb("connect", DEVICE, timeout=8, device=False)
                self.wait = min(self.wait * 2, 10)
            self.stop.wait(self.wait)


# ------------------------------------------------------------------ widgets
def rrect(c, x1, y1, x2, y2, r, **kw):
    pts = [x1 + r, y1, x2 - r, y1, x2, y1, x2, y1 + r, x2, y2 - r, x2, y2, x2 - r, y2,
           x1 + r, y2, x1, y2, x1, y2 - r, x1, y1 + r, x1, y1]
    return c.create_polygon(pts, smooth=True, **kw)


class Tile(tk.Canvas):
    """Botão arredondado com símbolo e rótulo; muda de cor ao passar o mouse e ao clicar."""

    def __init__(self, parent, s, w, h, glyph, label, command, hint="", repeat=None, glyph_bg=None,
                 glyph_fg=TEXT):
        super().__init__(parent, width=int(w * s), height=int(h * s), bg=BG, highlightthickness=0,
                         cursor="hand2", takefocus=0)
        self.cmd, self.hint, self.repeat, self.base, self._job = command, hint, repeat, TILE, None
        W, H = int(w * s), int(h * s)
        self.shape = rrect(self, 1, 1, W - 1, H - 1, int(9 * s), fill=TILE, outline="")
        if glyph_bg:  # ícone de app: quadrado colorido com a letra
            side = int(min(H * 0.5, W * 0.55))
            rrect(self, (W - side) / 2, H * 0.12, (W + side) / 2, H * 0.12 + side, int(7 * s),
                  fill=glyph_bg, outline="")
            self.create_text(W / 2, H * 0.12 + side / 2, text=glyph, fill=glyph_fg,
                             font=(FONT, int(11 * s * 0.75), "bold"))
            self.create_text(W / 2, H * 0.80, text=label, fill=TEXT, font=(FONT, 8))
        else:
            self.create_text(W / 2, H * 0.36, text=glyph, fill=TEXT, font=(FONT, 14))
            self.create_text(W / 2, H * 0.78, text=label, fill=DIM, font=(FONT, 8))
        self.bind("<Enter>", self._enter)
        self.bind("<Leave>", self._leave)
        self.bind("<ButtonPress-1>", self._press)
        self.bind("<ButtonRelease-1>", self._release)

    def paint(self, color):
        self.itemconfig(self.shape, fill=color)

    def _enter(self, _):
        self.paint(HOVER)
        self.winfo_toplevel().hint(self.hint)

    def _leave(self, _):
        self.paint(TILE)
        self._cancel()

    def _press(self, _):
        self.paint(PRESS)
        if self.repeat:  # segurar = repetir (setas e volume)
            self.cmd()
            self._job = self.after(400, self._again)

    def _release(self, e):
        self._cancel()
        inside = 0 <= e.x < self.winfo_width() and 0 <= e.y < self.winfo_height()
        self.paint(HOVER if inside else TILE)
        if inside and not self.repeat:
            self.cmd()

    def _again(self):
        self.cmd()
        self._job = self.after(130, self._again)

    def _cancel(self):
        if self._job:
            self.after_cancel(self._job)
            self._job = None


class DPad(tk.Canvas):
    """D-pad circular com quatro setores e o OK no centro."""

    def __init__(self, parent, s, size, send):
        n = int(size * s)
        super().__init__(parent, width=n, height=n, bg=BG, highlightthickness=0, cursor="hand2", takefocus=0)
        self.send, self.s, self._job = send, s, None
        pad, c = 2, n / 2
        box = (pad, pad, n - pad, n - pad)
        for name, start in (("right", 315), ("up", 45), ("left", 135), ("down", 225)):
            self.create_arc(*box, start=start, extent=90, style=tk.PIESLICE, fill=TILE, outline=BG, width=3,
                            tags=(name, "sec"))
        r = c * 0.78
        for name, (dx, dy, ch) in {"up": (0, -1, "▲"), "down": (0, 1, "▼"), "left": (-1, 0, "◀"),
                                   "right": (1, 0, "▶")}.items():
            self.create_text(c + dx * r, c + dy * r, text=ch, fill=DIM, font=(FONT, 12), tags=(name,))
        k = c * 0.36
        self.create_oval(c - k, c - k, c + k, c + k, fill=ACCENT, outline=BG, width=3, tags=("ok", "okc"))
        self.create_text(c, c, text="OK", fill=BG, font=(FONT, 13, "bold"), tags=("ok",))
        for name in ("up", "down", "left", "right", "ok"):
            self.tag_bind(name, "<Enter>", lambda e, n=name: self._enter(n))
            self.tag_bind(name, "<Leave>", lambda e, n=name: self._leave(n))
            self.tag_bind(name, "<ButtonPress-1>", lambda e, n=name: self._press(n))
            self.tag_bind(name, "<ButtonRelease-1>", lambda e, n=name: self._release(n))

    def _shade(self, name, color):
        self.itemconfig("okc" if name == "ok" else name + "&&sec", fill=color)

    def _enter(self, name):
        self._shade(name, "#7DBBFF" if name == "ok" else HOVER)
        self.winfo_toplevel().hint({"ok": "OK (Enter)", "up": "Cima (↑)", "down": "Baixo (↓)",
                                    "left": "Esquerda (←)", "right": "Direita (→)"}[name])

    def _leave(self, name):
        self._shade(name, ACCENT if name == "ok" else TILE)
        self._stop()

    def _press(self, name):
        self._shade(name, "#2F7FD6" if name == "ok" else PRESS)
        self.send(name)
        self._job = self.after(400, self._again, name)

    def _release(self, name):
        self._stop()
        self._shade(name, "#7DBBFF" if name == "ok" else HOVER)

    def _again(self, name):
        self.send(name)
        self._job = self.after(130, self._again, name)

    def _stop(self):
        if self._job:
            self.after_cancel(self._job)
            self._job = None


# ------------------------------------------------------------------ janela
class Remote(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("HY300-T")
        self.configure(bg=BG)
        self.resizable(False, False)
        self.s = max(1.0, self.winfo_fpixels("1i") / 96)
        self.events = queue.Queue()
        self.disp = Dispatcher(self.events)
        self.link = Link(self.events)
        self.topmost = tk.BooleanVar(value=False)
        self.default_hint = "Setas, Enter, Esc e PageUp/PageDown também funcionam."
        self._hint_job = None
        self.state_now = "connecting"
        self.build()
        self.bind_keys()
        self.protocol("WM_DELETE_WINDOW", self.close)
        self.disp.start()
        self.link.start()
        self.after(100, self.drain)

    def px(self, n):
        return int(n * self.s)

    # ---- layout
    def build(self):
        px, s = self.px, self.s
        outer = tk.Frame(self, bg=BG)
        outer.pack(padx=px(16), pady=px(14))

        head = tk.Frame(outer, bg=BG)
        head.pack(fill="x")
        self.dot = tk.Canvas(head, width=px(12), height=px(12), bg=BG, highlightthickness=0)
        self.dot_item = self.dot.create_oval(1, 1, px(11), px(11), fill=WARN, outline="")
        self.dot.pack(side="left", padx=(0, px(8)))
        tk.Label(head, text="HY300-T", bg=BG, fg=TEXT, font=(FONT, 11, "bold")).pack(side="left")
        tk.Label(head, text=DEVICE.split(":")[0], bg=BG, fg=DIM, font=(FONT, 9)).pack(side="right")

        cards = tk.Frame(outer, bg=BG)
        cards.pack(fill="x", pady=(px(12), px(14)))
        self.ram = self.card(cards, "RAM livre")
        self.temp = self.card(cards, "Temperatura")
        self.ram[0].pack(side="left", expand=True, fill="x", padx=(0, px(4)))
        self.temp[0].pack(side="left", expand=True, fill="x", padx=(px(4), 0))

        DPad(outer, s, 190, lambda n: self.key(n)).pack(pady=(0, px(14)))

        def row(items, **kw):
            f = tk.Frame(outer, bg=BG)
            f.pack(fill="x", pady=(0, px(8)))
            for i, (g, label, cmd, tip, rep) in enumerate(items):
                t = Tile(f, s, kw.get("w", 92), 56, g, label, cmd, tip, repeat=rep)
                t.grid(row=0, column=i, padx=px(3))
            return f

        row([("↩", "Voltar", lambda: self.key("back"), "Voltar (Esc)", False),
             ("⌂", "Início", lambda: self.key("home"), "Início (Home)", False),
             ("≡", "Menu", lambda: self.key("menu"), "Menu", False)])
        row([("−", "Vol −", lambda: self.key("vol_down"), "Volume − (PageDown)", True),
             ("🔇", "Mudo", lambda: self.key("mute"), "Mudo", False),
             ("+", "Vol +", lambda: self.key("vol_up"), "Volume + (PageUp)", True)])

        tk.Label(outer, text="APPS", bg=BG, fg=DIM, font=(FONT, 8)).pack(anchor="w", pady=(px(4), px(4)))
        grid = tk.Frame(outer, bg=BG)
        grid.pack(fill="x", pady=(0, px(8)))
        for i, (name, target, color, letter) in enumerate(APPS):
            t = Tile(grid, s, 68, 64, letter, name, lambda t=target: self.open_app(t), f"Abrir {name}",
                     glyph_bg=color, glyph_fg=ACCENT if color == "#1B2029" else "#FFFFFF")
            t.grid(row=i // 4, column=i % 4, padx=px(2), pady=px(3))

        row([("⌨", "Digitar", self.type_text, "Enviar texto ao projetor", False),
             ("📷", "Captura", self.screenshot, "Salvar captura da tela", False),
             ("⋯", "Mais", self.more, "Mais opções", False)])

        self.footer = tk.Label(outer, text=self.default_hint, bg=BG, fg=DIM, font=(FONT, 8),
                               wraplength=px(290), justify="left", anchor="w")
        self.footer.pack(fill="x", pady=(px(2), 0))

    def card(self, parent, title):
        px = self.px
        c = tk.Canvas(parent, height=px(50), bg=BG, highlightthickness=0)
        rrect(c, 1, 1, px(142), px(49), px(9), fill=TILE, outline="")
        c.create_text(px(10), px(14), text=title, fill=DIM, anchor="w", font=(FONT, 8))
        val = c.create_text(px(10), px(34), text="—", fill=TEXT, anchor="w", font=(FONT, 14, "bold"))
        c.configure(width=px(144))
        return c, val

    def bind_keys(self):
        m = {"<Up>": "up", "<Down>": "down", "<Left>": "left", "<Right>": "right", "<Return>": "ok",
             "<Escape>": "back", "<BackSpace>": "back", "<Home>": "home", "<Prior>": "vol_up",
             "<Next>": "vol_down", "<space>": "play"}
        for seq, k in m.items():
            self.bind(seq, lambda e, k=k: self.key(k))

    # ---- feedback
    def hint(self, text, ms=None):
        if self._hint_job:
            self.after_cancel(self._hint_job)
            self._hint_job = None
        self.footer.config(text=text or self.default_hint)
        if ms:
            self._hint_job = self.after(ms, lambda: self.footer.config(text=self.default_hint))

    def set_state(self, state, msg=""):
        self.state_now = state
        self.dot.itemconfig(self.dot_item, fill={"ok": GOOD, "unauth": WARN, "connecting": WARN}.get(state, BAD))
        if state != "ok":
            for c, v in (self.ram, self.temp):
                c.itemconfig(v, text="—", fill=TEXT)
            self.hint(msg or "Conectando…")

    def drain(self):
        try:
            while True:
                ev = self.events.get_nowait()
                if ev[0] == "stats":
                    _, mem, temp = ev
                    if self.state_now != "ok":
                        self.set_state("ok")
                        self.hint(None)
                    self.ram[0].itemconfig(self.ram[1], text=f"{mem} MB", fill=BAD if mem < 120 else TEXT)
                    if temp is not None:
                        color = BAD if temp >= 90 else WARN if temp >= 80 else TEXT
                        self.temp[0].itemconfig(self.temp[1], text=f"{temp} °C", fill=color)
                elif ev[0] == "state":
                    self.set_state(ev[1], ev[2])
                elif ev[0] == "toast":
                    self.hint(ev[1], 4000)
        except queue.Empty:
            pass
        self.after(100, self.drain)

    # ---- ações
    def key(self, name):
        self.disp.key(KEYS[name])

    def open_app(self, target):
        if "/" in target:
            self.disp.sh(f"am start -n {target}")
        else:
            self.disp.sh(f"monkey -p {target} -c android.intent.category.LEANBACK_LAUNCHER 1")
        self.hint(f"Abrindo {target.split('/')[0]}…", 2500)

    def type_text(self):
        text = simpledialog.askstring("Digitar texto", "Texto para enviar ao projetor (só ASCII):", parent=self)
        if not text:
            return
        if not text.isascii():
            messagebox.showinfo("Digitar texto", "Acentos e símbolos fora do ASCII não funcionam com o "
                                "`input text` do Android.", parent=self)
            return
        # Aspas simples escapam tudo; `input text` usa %s no lugar do espaço.
        self.disp.sh("input text '" + text.replace("'", "'\\''").replace(" ", "%s") + "'")

    def screenshot(self):
        folder = Path.home() / "Pictures" / "HY300"
        folder.mkdir(parents=True, exist_ok=True)
        dest = folder / time.strftime("hy300_%Y%m%d_%H%M%S.png")

        def run():
            c1, o1 = shell("screencap -p /data/local/tmp/hy.png")
            c2, o2 = adb("pull", "/data/local/tmp/hy.png", str(dest), timeout=20) if c1 == 0 else (c1, o1)
            shell("rm -f /data/local/tmp/hy.png")
            self.events.put(("toast", f"Captura salva em {dest}" if c2 == 0 else describe(c2, o2)[1]))
            if c2 == 0 and hasattr(os, "startfile"):
                os.startfile(str(dest))
        threading.Thread(target=run, daemon=True).start()
        self.hint("Capturando…", 3000)

    def more(self):
        m = tk.Menu(self, tearoff=0, bg=TILE, fg=TEXT, activebackground=HOVER, activeforeground=TEXT, bd=0)
        m.add_command(label="Ligar/desligar a tela", command=lambda: self.key("power"))
        m.add_command(label="Limpar RAM (processos em cache)", command=lambda: self.disp.sh("am kill-all"))
        m.add_command(label="Espelhar a tela (scrcpy)", command=self.mirror)
        m.add_checkbutton(label="Manter no topo", variable=self.topmost,
                          command=lambda: self.attributes("-topmost", self.topmost.get()))
        m.add_separator()
        m.add_command(label="Reiniciar o projetor…", command=self.reboot)
        x, y = self.winfo_pointerxy()
        m.tk_popup(x, y)

    def mirror(self):
        try:
            subprocess.Popen(["scrcpy", "-s", DEVICE, "--window-title", "HY300-T"], creationflags=NOWIN)
        except FileNotFoundError:
            messagebox.showinfo("scrcpy", "scrcpy não está instalado (opcional). Veja o README.", parent=self)

    def reboot(self):
        if messagebox.askyesno("Reiniciar", "Reiniciar o projetor agora?", parent=self):
            self.disp.sh("reboot")
            self.set_state("offline", "Reiniciando… reconecta sozinho em ~1 minuto.")

    def close(self):
        self.link.stop.set()
        self.destroy()


if __name__ == "__main__":
    Remote().mainloop()
