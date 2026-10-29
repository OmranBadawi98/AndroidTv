package sa.urtv.home;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;

/**
 * For tests/adb_self_test.py: AdbSelf's public key in adb's own form, field by field, and the signature adbd checks (PKCS#1
 * type 1 around SHA-1's DigestInfo and the token, which NONEwithRSA gives when handed both). Prints the key as
 * "n e hex" for the Python side, which encodes it again the way adb-shell does.
 */
public final class AdbSelfRun {
    static int failed;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
        if (!ok) failed++;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("shell")) {   // against the test's stand-in adbd on 127.0.0.1:5555
            System.out.print("OUT " + AdbSelf.shell(args[1], 2000));
            return;
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        byte[] enc = AdbSelf.encode(pub);
        check("524 bytes, like struct RSAPublicKey", enc.length == 524, String.valueOf(enc.length));

        byte[] token = new byte[20];
        new java.util.Random(7).nextBytes(token);
        byte[] info = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(kp.getPrivate());
        s.update(info);
        s.update(token);
        byte[] sig = s.sign();
        byte[] block = new BigInteger(1, sig).modPow(pub.getPublicExponent(), pub.getModulus()).toByteArray();
        // 00 01 FF..FF 00 DigestInfo token (BigInteger drops the leading 00)
        boolean padded = block.length == 255 && block[0] == 1 && block[255 - 36] == 0;
        for (int i = 1; padded && i < 255 - 36; i++) padded = block[i] == (byte) 0xff;
        boolean tail = Arrays.equals(Arrays.copyOfRange(block, 255 - 35, 255 - 20), info)
            && Arrays.equals(Arrays.copyOfRange(block, 255 - 20, 255), token);
        check("the signature is PKCS#1 type 1 over DigestInfo(SHA-1) and the token", padded && tail, "");

        StringBuilder hex = new StringBuilder();
        for (byte b : enc) hex.append(String.format("%02x", b & 0xff));
        System.out.println("KEY " + pub.getModulus().toString(16) + " " + pub.getPublicExponent() + " " + hex);
        System.exit(failed == 0 ? 0 : 1);
    }
}
