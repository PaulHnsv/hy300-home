package com.paulo.hyremote;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fila única de comandos para o projetor. Teclas seguidas viram uma só chamada (`input keyevent 20 20`),
 * em ordem, e a conexão é refeita sozinha. Sem dependência de Android, para poder ser testada no PC.
 */
final class Controller extends Thread {
    interface Events {
        void state(int state, String msg);
        void stats(int memMb, int tempC);
        void toast(String msg);
    }

    static final int OFFLINE = 0, CONNECTING = 1, AUTH = 2, OK = 3;
    private static final String STATUS_CMD = "grep MemAvailable /proc/meminfo; echo T=$(cat /sys/class/thermal/thermal_zone0/temp)";
    private static final Pattern MEM = Pattern.compile("MemAvailable:\\s+(\\d+)");
    private static final Pattern TEMP = Pattern.compile("T=(\\d+)");

    private final LinkedBlockingQueue<Object[]> q = new LinkedBlockingQueue<>();
    private final Events ev;
    private final AdbKey key;
    private final String comment;
    private volatile boolean stop;
    private String host;
    private int port = 5555;
    private AdbConnection conn;
    private long retryAt;
    private long backoff = 2000;
    private int lastState = -1;

    Controller(AdbKey key, String comment, Events ev) {
        super("controller");
        setDaemon(true);
        this.key = key;
        this.comment = comment;
        this.ev = ev;
    }

    void setTarget(String hostPort) {
        q.add(new Object[]{"target", hostPort});
    }

    void key(int code) {
        q.add(new Object[]{"key", code});
    }

    void shell(String cmd) {
        q.add(new Object[]{"sh", cmd});
    }

    void shutdown() {
        stop = true;
        q.add(new Object[]{"stop"});
    }

    @Override
    public void run() {
        while (!stop) {
            try {
                Object[] it = q.poll(3, TimeUnit.SECONDS);
                if (it == null) {
                    if (host != null) exec(STATUS_CMD, true);
                    continue;
                }
                switch ((String) it[0]) {
                    case "target": retarget((String) it[1]); break;
                    case "key": {
                        List<Integer> codes = new ArrayList<>();
                        codes.add((Integer) it[1]);
                        Object[] next;
                        while (codes.size() < 10 && (next = q.peek()) != null && "key".equals(next[0])) {
                            codes.add((Integer) q.poll()[1]);
                        }
                        StringBuilder sb = new StringBuilder("input keyevent");
                        for (int c : codes) sb.append(' ').append(c);
                        exec(sb.toString(), false);
                        break;
                    }
                    case "sh": exec((String) it[1], false); break;
                    default: break;
                }
            } catch (InterruptedException e) {
                return;
            }
        }
        closeConn();
    }

    private void retarget(String hostPort) {
        closeConn();
        retryAt = 0;
        backoff = 2000;
        String h = hostPort.trim();
        int p = 5555;
        int i = h.lastIndexOf(':');
        if (i > 0) {
            try { p = Integer.parseInt(h.substring(i + 1)); h = h.substring(0, i); } catch (NumberFormatException ignored) { }
        }
        host = h.isEmpty() ? null : h;
        port = p;
        if (host == null) setState(OFFLINE, "Informe o IP do projetor.");
        else exec(STATUS_CMD, true);
    }

    private void exec(String cmd, boolean status) {
        try {
            if (conn == null) {
                if (host == null) throw new IOException("Informe o IP do projetor.");
                if (System.currentTimeMillis() < retryAt) throw new IOException("Sem conexão com o projetor.");
                setState(CONNECTING, "Conectando…");
                conn = AdbConnection.open(host, port, key, comment, () -> setState(AUTH, "Aceite a chave na tela do projetor."));
                backoff = 2000;
            }
            String out = conn.shell(cmd);
            setState(OK, "");
            if (status) {
                Matcher m = MEM.matcher(out), t = TEMP.matcher(out);
                if (m.find()) ev.stats((int) (Long.parseLong(m.group(1)) / 1024), t.find() ? Integer.parseInt(t.group(1)) / 1000 : -1);
            }
        } catch (IOException e) {
            boolean wasAuth = lastState == AUTH;
            closeConn();
            retryAt = System.currentTimeMillis() + backoff;
            backoff = Math.min(backoff * 2, 10000);
            String msg = friendly(e, wasAuth);
            setState(OFFLINE, msg);
            if (!status) ev.toast(msg);
        }
    }

    private static String friendly(IOException e, boolean wasAuth) {
        if (e instanceof SocketTimeoutException) {
            return wasAuth ? "A chave não foi aceita a tempo. Tente de novo." : "O projetor não respondeu.";
        }
        if (e instanceof ConnectException || e instanceof NoRouteToHostException || e instanceof UnknownHostException) {
            return "Projetor offline ou ADB desligado.";
        }
        return e.getMessage() == null ? "Erro de conexão." : e.getMessage();
    }

    private void setState(int s, String msg) {
        if (s == lastState && s != OFFLINE) return;
        lastState = s;
        ev.state(s, msg);
    }

    private void closeConn() {
        if (conn != null) conn.close();
        conn = null;
    }
}
