package com.tripforward.localarcadeadmin;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

public final class CryptoStore {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "local_arcade_admin_v1";

    private CryptoStore() {}

    public static synchronized KeyPair getOrCreate() throws Exception {
        KeyStore ks = KeyStore.getInstance(STORE);
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build();
            kpg.initialize(spec);
            kpg.generateKeyPair();
        }
        PrivateKey priv = (PrivateKey) ks.getKey(ALIAS, null);
        PublicKey pub = ks.getCertificate(ALIAS).getPublicKey();
        return new KeyPair(pub, priv);
    }

    public static byte[] sign(byte[] data) throws Exception {
        KeyPair kp = getOrCreate();
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(kp.getPrivate());
        s.update(data);
        return s.sign();
    }

    public static byte[] publicKeyUncompressed() throws Exception {
        ECPublicKey pub = (ECPublicKey) getOrCreate().getPublic();
        byte[] x = unsigned32(pub.getW().getAffineX());
        byte[] y = unsigned32(pub.getW().getAffineY());
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(x, 0, out, 1, 32);
        System.arraycopy(y, 0, out, 33, 32);
        return out;
    }

    private static byte[] unsigned32(BigInteger n) {
        byte[] b = n.toByteArray();
        if (b.length == 32) return b;
        if (b.length == 33 && b[0] == 0) return Arrays.copyOfRange(b, 1, 33);
        byte[] out = new byte[32];
        int src = Math.max(0, b.length - 32);
        int len = Math.min(32, b.length);
        System.arraycopy(b, src, out, 32 - len, len);
        return out;
    }
}
