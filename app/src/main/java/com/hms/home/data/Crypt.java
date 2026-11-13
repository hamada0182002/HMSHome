package com.hms.home.data;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts saved login with an AES key kept in the Android Keystore. Falls back to simple obfuscation if the box has a broken keystore. */
final class Crypt {
    private static final String ALIAS = "hms_home_key";

    private static String b64(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }
    private static byte[] unb64(String s) { return Base64.decode(s, Base64.NO_WRAP); }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            kg.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }

    static String enc(String s) {
        try {
            if (android.os.Build.VERSION.SDK_INT < 23) throw new IllegalStateException("no keystore");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] ct = c.doFinal(s.getBytes("UTF-8"));
            return "k:" + b64(c.getIV()) + ":" + b64(ct);
        } catch (Throwable e) {
            try { return "o:" + b64(s.getBytes("UTF-8")); } catch (Exception x) { return ""; }
        }
    }

    static String dec(String s) {
        if (s == null || s.length() < 3) return "";
        try {
            if (s.startsWith("o:")) return new String(unb64(s.substring(2)), "UTF-8");
            String[] p = s.split(":");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, unb64(p[1])));
            return new String(c.doFinal(unb64(p[2])), "UTF-8");
        } catch (Throwable e) { return ""; }
    }
}
