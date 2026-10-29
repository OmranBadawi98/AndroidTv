package sa.urtv.home;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;

/**
 * The TV's own ADB, from the TV: «إدارة التطبيقات» (AppsActivity) disables and enables apps with `pm`, which an ordinary
 * app may not do. It speaks the plain ADB protocol to 127.0.0.1:5555 (the TV's network debugging), like adb on a computer.
 * The first time the TV asks «Allow USB debugging?» for the key «UrTV@tv»; «Always allow» keeps it.
 * The private key lives in the Android Keystore and can never be read out (not by the home card's page either).
 * Blocking: call off the main thread. One command per connection: there is nothing to keep open.
 */
final class AdbSelf {
    static final int PORT = 5555;
    private static final String ALIAS = "urtv-adb";
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f, CLSE = 0x45534c43,
            WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** ASN.1 DigestInfo of SHA-1: adbd signs its 20-byte token as if it were a SHA-1 digest */
    private static final byte[] SHA1_INFO = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    /** What went wrong, in words for the owner. */
    static final class Failure extends IOException {
        Failure(String arabic) { super(arabic); }
    }

    private AdbSelf() {}

    /** One line in logcat (tag UrTVAdb) per step: no key, no command output, nothing private. */
    private static void log(String step) {
        try { android.util.Log.i("UrTVAdb", step); } catch (RuntimeException ignored) { }   // android.jar's stub on a plain JVM (tests)
    }

    /** Runs one shell command on the TV and returns what it printed. allowWaitMs: how long the «Allow» question may wait. */
    static String shell(String command, int allowWaitMs) throws IOException {
        log("start: " + command);
        Socket s = new Socket();
        try {
            try {
                s.connect(new InetSocketAddress("127.0.0.1", PORT), 3000);
                log("connected");
            } catch (IOException e) {
                log("connect failed: " + e);
                throw new Failure("تصحيح الشبكة (Network debugging) مقفل: شغّله من خيارات المطوّر، أو من الكمبيوتر: adb tcpip 5555");
            }
            s.setSoTimeout(10000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            send(out, CNXN, VERSION, MAX_DATA, "host::\0".getBytes("UTF-8"));
            connect(s, in, out, allowWaitMs);
            return run(in, out, command);
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    /** Until adbd says CNXN: our signature if it knows the key, else the key itself and the owner's «Allow» on the TV. */
    private static void connect(Socket s, DataInputStream in, OutputStream out, int allowWaitMs) throws IOException {
        boolean signed = false;
        while (true) {
            Message m = read(in);
            log("got " + Integer.toHexString(m.command) + " arg0=" + m.arg0);
            if (m.command == CNXN) return;
            if (m.command != AUTH || m.arg0 != AUTH_TOKEN) throw new Failure("ردّ غير متوقع من التلفزيون");
            if (!signed) {
                send(out, AUTH, AUTH_SIGNATURE, 0, sign(m.data));
                signed = true;
            } else {
                send(out, AUTH, AUTH_RSAPUBLICKEY, 0, (publicKey() + " UrTV@tv\0").getBytes("UTF-8"));
                s.setSoTimeout(allowWaitMs);
                try {
                    m = read(in);
                } catch (java.net.SocketTimeoutException e) {
                    throw new Failure("ما وصلت الموافقة: اضغط «سماح» (Allow) على التلفزيون واختر «السماح دائمًا»، ثم أعد المحاولة");
                } catch (IOException e) {
                    throw new Failure("رُفض الاتصال على التلفزيون");
                }
                s.setSoTimeout(10000);
                if (m.command == CNXN) return;
                throw new Failure("رُفض الاتصال على التلفزيون");
            }
        }
    }

    private static String run(DataInputStream in, OutputStream out, String command) throws IOException {
        final int local = 1;
        send(out, OPEN, local, 0, ("shell:" + command + "\0").getBytes("UTF-8"));
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Message m = read(in);
            if (m.command == OKAY) {
                remote = m.arg0;
            } else if (m.command == WRTE) {
                remote = m.arg0;
                text.write(m.data);
                send(out, OKAY, local, remote, new byte[0]);
            } else if (m.command == CLSE) {
                if (remote != 0) send(out, CLSE, local, remote, new byte[0]);
                return text.toString("UTF-8");
            }
        }
    }

    // ---------------------------------------------------------------- the wire: a 24-byte header, little-endian

    private static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    private static void send(OutputStream out, int command, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~command);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private static Message read(DataInputStream in) throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int length = h.getInt();
        h.getInt();   // checksum: TCP already checks
        if ((h.getInt() ^ m.command) != 0xffffffff || length < 0 || length > 1024 * 1024) throw new Failure("ردّ غير متوقع من التلفزيون");
        m.data = new byte[length];
        in.readFully(m.data);
        return m;
    }

    // ---------------------------------------------------------------- the key

    private static KeyStore.PrivateKeyEntry entry() throws IOException {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (!ks.containsAlias(ALIAS)) {
                log("making the key");
                KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
                g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setKeySize(2048)
                        .setDigests(KeyProperties.DIGEST_NONE)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .build());
                g.generateKeyPair();
                log("key made");
            }
            return (KeyStore.PrivateKeyEntry) ks.getEntry(ALIAS, null);
        } catch (Exception e) {
            log("key failed: " + e);
            throw new Failure("تعذّر تجهيز مفتاح الاتصال على هذا التلفزيون");
        }
    }

    /** PKCS#1 v1.5 over SHA-1's DigestInfo and the token: what adbd checks with the key it was given. */
    private static byte[] sign(byte[] token) throws IOException {
        try {
            PrivateKey key = entry().getPrivateKey();
            Signature sig = Signature.getInstance("NONEwithRSA");
            sig.initSign(key);
            sig.update(SHA1_INFO);
            sig.update(token);
            return sig.sign();
        } catch (Failure e) {
            throw e;
        } catch (Exception e) {
            log("sign failed: " + e);
            throw new Failure("تعذّر التوقيع بمفتاح الاتصال");
        }
    }

    private static String publicKey() throws IOException {
        return Base64.encodeToString(encode((RSAPublicKey) entry().getCertificate().getPublicKey()), Base64.NO_WRAP);
    }

    /**
     * adb's own form of an RSA public key (android_pubkey_encode): the modulus size in words, -1/n mod 2^32, the modulus and
     * R^2 mod n (R = 2^bits) little-endian, then the exponent.
     */
    static byte[] encode(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        int words = n.bitLength() / 32, bytes = words * 4;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + bytes + bytes + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        b.put(littleEndian(n, bytes));
        b.put(littleEndian(rr, bytes));
        b.putInt(key.getPublicExponent().intValue());
        return b.array();
    }

    private static byte[] littleEndian(BigInteger v, int length) {
        byte[] big = v.toByteArray(), out = new byte[length];
        for (int i = 0; i < length && i < big.length; i++) out[i] = big[big.length - 1 - i];
        return out;
    }
}
