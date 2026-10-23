package com.paulo.hyremote;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/** Par de chaves RSA-2048 que identifica este app perante o adbd (mesmo formato do `adb` do PC). */
final class AdbKey {
    private static final int WORDS = 64;                    // 2048 bits / 32
    /** Prefixo DigestInfo do SHA-1: o adbd assina o token de 20 bytes como se já fosse o hash. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
            0x1a, 0x05, 0x00, 0x04, 0x14};

    private final RSAPrivateCrtKey priv;
    private final RSAPublicKey pub;

    private AdbKey(RSAPrivateCrtKey priv, RSAPublicKey pub) {
        this.priv = priv;
        this.pub = pub;
    }

    static AdbKey generate() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        return new AdbKey((RSAPrivateCrtKey) kp.getPrivate(), (RSAPublicKey) kp.getPublic());
    }

    /** PKCS#8 DER, o mesmo conteúdo do ~/.android/adbkey do PC (sem o cabeçalho PEM). */
    static AdbKey fromPkcs8(byte[] der) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey p = kf.generatePrivate(new PKCS8EncodedKeySpec(der));
        RSAPrivateCrtKey c = (RSAPrivateCrtKey) p;
        RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(new RSAPublicKeySpec(c.getModulus(), c.getPublicExponent()));
        return new AdbKey(c, pub);
    }

    byte[] pkcs8() {
        return priv.getEncoded();
    }

    byte[] sign(byte[] token) throws Exception {
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(priv);
        s.update(SHA1_PREFIX);
        s.update(token);
        return s.sign();
    }

    /** Linha que vai para /data/misc/adb/adb_keys: base64 do formato "mincrypt" + comentário + NUL. */
    byte[] publicKeyLine(String comment) {
        String line = Base64.getEncoder().encodeToString(mincrypt()) + " " + comment + "\0";
        return line.getBytes(StandardCharsets.US_ASCII);
    }

    /** Mesma linha, sem o NUL, para mostrar ao usuário ou gravar em adb_keys. */
    String publicKeyText(String comment) {
        return Base64.getEncoder().encodeToString(mincrypt()) + " " + comment;
    }

    private byte[] mincrypt() {
        BigInteger n = pub.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        int n0inv = r32.subtract(n.mod(r32).modInverse(r32)).intValue();   // -1/n[0] mod 2^32
        BigInteger rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n);          // (2^2048)^2 mod n
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + WORDS * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(WORDS).putInt(n0inv);
        putWords(b, n);
        putWords(b, rr);
        b.putInt(pub.getPublicExponent().intValue());
        return b.array();
    }

    private static void putWords(ByteBuffer b, BigInteger x) {
        BigInteger mask = BigInteger.valueOf(0xffffffffL);
        for (int i = 0; i < WORDS; i++) b.putInt(x.shiftRight(32 * i).and(mask).intValue());
    }
}
