package com.paulo.hyremote;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Cliente mínimo do protocolo ADB sobre TCP (porta 5555): conecta, autentica com RSA e roda `shell:`.
 * Não abre nada no projetor: usa o adbd que já existe, com a mesma autenticação do `adb` do PC.
 */
final class AdbConnection implements Closeable {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;

    interface AuthListener { void onAuthPending(); }

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private final Socket sock;
    private final InputStream in;
    private final OutputStream out;
    private int nextId = 1;

    private AdbConnection(Socket s) throws IOException {
        sock = s;
        in = s.getInputStream();
        out = s.getOutputStream();
    }

    /** Conecta e autentica. Com chave ainda desconhecida, o projetor mostra um pedido na tela (até 30 s). */
    static AdbConnection open(String host, int port, AdbKey key, String comment, AuthListener l) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setTcpNoDelay(true);
            AdbConnection c = new AdbConnection(s);
            c.handshake(key, comment, l);
            return c;
        } catch (IOException | RuntimeException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    private void handshake(AdbKey key, String comment, AuthListener l) throws IOException {
        sock.setSoTimeout(5000);
        send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
        boolean signed = false;
        while (true) {
            Msg m = read();
            if (m.cmd == CNXN) {
                sock.setSoTimeout(8000);
                return;
            }
            if (m.cmd == AUTH && m.arg0 == AUTH_TOKEN) {
                try {
                    if (!signed) {
                        signed = true;
                        send(AUTH, AUTH_SIGNATURE, 0, key.sign(m.data));
                    } else {
                        if (l != null) l.onAuthPending();
                        sock.setSoTimeout(30000);
                        send(AUTH, AUTH_RSAPUBLICKEY, 0, key.publicKeyLine(comment));
                    }
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException("falha ao assinar: " + e.getMessage(), e);
                }
            } else {
                throw new IOException("resposta inesperada do adbd: 0x" + Integer.toHexString(m.cmd));
            }
        }
    }

    /** Executa `shell:<cmd>` e devolve a saída. Uma conexão serve a vários comandos, um por vez. */
    synchronized String shell(String cmd) throws IOException {
        int local = nextId++;
        send(OPEN, local, 0, ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int remote = -1;
        while (true) {
            Msg m = read();
            if (m.arg1 != local) continue;                // mensagem de outro stream
            if (m.cmd == OKAY) {
                remote = m.arg0;
            } else if (m.cmd == WRTE) {
                result.write(m.data, 0, m.data.length);
                send(OKAY, local, m.arg0, null);
            } else if (m.cmd == CLSE) {
                if (remote >= 0) send(CLSE, local, m.arg0, null);
                break;
            }
        }
        return new String(result.toByteArray(), StandardCharsets.UTF_8);
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(cmd ^ 0xffffffff);
        if (len > 0) h.put(data);
        out.write(h.array());
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] hb = new byte[24];
        readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        h.getInt();                                       // crc: não verificado
        int magic = h.getInt();
        if (magic != (m.cmd ^ 0xffffffff) || len < 0 || len > MAX_DATA) throw new IOException("pacote ADB inválido");
        m.data = new byte[len];
        readFully(m.data);
        return m;
    }

    private void readFully(byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("conexão encerrada pelo projetor");
            off += n;
        }
    }

    @Override
    public void close() {
        try { sock.close(); } catch (IOException ignored) { }
    }
}
