package com.paulo.hyremote;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;

/**
 * Teste no PC do protocolo ADB do app. Usa a chave que o `adb` do PC já tem autorizada
 * (~/.android/adbkey), então não pede autorização nova no projetor.
 * Uso: ./phone/test/run.sh 192.168.0.15:5555
 */
public class AdbSmoke {
    public static void main(String[] a) throws Exception {
        String[] hp = a[0].split(":");
        String pem = new String(Files.readAllBytes(Paths.get(System.getProperty("user.home"), ".android", "adbkey")));
        AdbKey key = AdbKey.fromPkcs8(Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").trim()));
        System.out.println("chave publica (inicio): " + key.publicKeyText("teste").substring(0, 24) + "...");

        long t0 = System.nanoTime();
        AdbConnection c = AdbConnection.open(hp[0], Integer.parseInt(hp[1]), key, "hyremote@teste", null);
        System.out.println("conectado em " + (System.nanoTime() - t0) / 1000000 + " ms");

        t0 = System.nanoTime();
        System.out.println("echo: " + c.shell("echo ola; echo $((6*7))").trim().replace("\n", " | "));
        System.out.println("  " + (System.nanoTime() - t0) / 1000000 + " ms");
        System.out.println("status: " + c.shell("grep MemAvailable /proc/meminfo; echo T=$(cat /sys/class/thermal/thermal_zone0/temp)").trim().replace("\n", " | "));
        c.close();

        // Controller: teclas em lote (KEYCODE_UNKNOWN = 0 é inofensivo) e estado
        final int[] state = {-1};
        final int[] stats = {0, 0};
        final java.util.List<String> toasts = new java.util.concurrent.CopyOnWriteArrayList<>();
        Controller ctl = new Controller(key, "hyremote@teste", new Controller.Events() {
            public void state(int s, String m) { state[0] = s; System.out.println("estado=" + s + " " + m); }
            public void stats(int mem, int t) { stats[0] = mem; stats[1] = t; }
            public void toast(String m) { toasts.add(m); }
        });
        ctl.start();
        ctl.setTarget(a[0]);
        for (int i = 0; i < 8; i++) ctl.key(0);
        Thread.sleep(6000);
        System.out.println("controller: estado=" + state[0] + " RAM=" + stats[0] + " MB temp=" + stats[1] + " erros=" + toasts);
        ctl.shutdown();

        // aparelho inexistente: deve falhar rápido e sem travar
        Controller bad = new Controller(key, "x", new Controller.Events() {
            public void state(int s, String m) { System.out.println("[inexistente] estado=" + s + " " + m); }
            public void stats(int mem, int t) { }
            public void toast(String m) { }
        });
        bad.start();
        bad.setTarget("10.255.255.1:5555");
        Thread.sleep(6000);
        bad.shutdown();
    }
}
