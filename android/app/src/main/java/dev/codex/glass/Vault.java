package dev.codex.glass;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import org.json.*;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;

final class Vault {
    private static final String ALIAS="codex_glass_session_v1";
    private final SharedPreferences prefs;
    private final Object lock=new Object();
    Vault(Context c){prefs=c.getSharedPreferences("encrypted_session",Context.MODE_PRIVATE);}
    private SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias(ALIAS))return (SecretKey)ks.getKey(ALIAS,null);
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return gen.generateKey();
    }
    JSONObject read() throws Exception {synchronized(lock){
        String raw=prefs.getString("session",null);if(raw==null)return null;
        JSONObject envelope=new JSONObject(raw);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(envelope.getString("iv"),Base64.NO_WRAP)));
        byte[] clear=cipher.doFinal(Base64.decode(envelope.getString("data"),Base64.NO_WRAP));
        return new JSONObject(new String(clear,StandardCharsets.UTF_8));
    }}
    void write(JSONObject session) throws Exception {synchronized(lock){
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] encrypted=cipher.doFinal(session.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject envelope=new JSONObject().put("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).put("data",Base64.encodeToString(encrypted,Base64.NO_WRAP));
        if(!prefs.edit().putString("session",envelope.toString()).commit())throw new Exception("无法保存登录，请重试");
    }}
    void clear(){synchronized(lock){prefs.edit().clear().commit();}}
}
