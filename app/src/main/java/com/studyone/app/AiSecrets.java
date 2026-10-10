package com.studyone.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Android Keystore-backed private API credential. Never exported to a backup or log. */
public final class AiSecrets {
    private static final String ALIAS = "studyone_ai_v3_aes_key";
    private static final String STORE = "studyone_ai_secret";
    private AiSecrets() {}

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }

    public static void save(Context context, String keyText) throws Exception {
        String value = keyText == null ? "" : keyText.trim();
        if (!value.startsWith("sk-") || value.length() < 16 || value.length() > 512)
            throw new IllegalArgumentException("OpenAI API 키 형식이 올바르지 않습니다.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] data = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        SharedPreferences p = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        boolean ok = p.edit().putString("data", Base64.encodeToString(data, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).commit();
        if (!ok) throw new IllegalStateException("키를 저장하지 못했습니다.");
    }

    public static String read(Context context) throws Exception {
        SharedPreferences p = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        String data = p.getString("data", "");
        String iv = p.getString("iv", "");
        if (data.isEmpty() || iv.isEmpty()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(),
                new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)),
                StandardCharsets.UTF_8);
    }

    public static boolean configured(Context context) {
        return context.getSharedPreferences(STORE, Context.MODE_PRIVATE).contains("data");
    }

    public static void clear(Context context) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                .remove("data").remove("iv").commit();
    }
}
