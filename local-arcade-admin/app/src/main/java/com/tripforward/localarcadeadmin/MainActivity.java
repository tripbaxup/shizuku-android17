package com.tripforward.localarcadeadmin;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.security.MessageDigest;

public class MainActivity extends Activity {
    private TextView amountText;
    private TextView stateText;
    private TextView fingerprintText;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("admin", MODE_PRIVATE);
        cryptoStoreInit();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 64, 48, 48);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("ARCADE ADMIN CARD");
        title.setTextSize(28f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Offline owner credential for your retrofit arcade readers\nTap phone → next physical card receives the selected credits\nAID: F041524341444D494E01");
        sub.setTextSize(16f);
        sub.setPadding(0, 20, 0, 36);
        root.addView(sub);

        stateText = new TextView(this);
        stateText.setTextSize(18f);
        root.addView(stateText);

        amountText = new TextView(this);
        amountText.setTextSize(22f);
        amountText.setPadding(0, 36, 0, 8);
        root.addView(amountText);

        SeekBar seek = new SeekBar(this);
        seek.setMax(98);
        seek.setProgress(Math.max(0, prefs.getInt("credits", 1) - 1));
        seek.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int credits = p + 1;
                prefs.edit().putInt("credits", credits).apply();
                updateAmount();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });

        Button toggle = new Button(this);
        toggle.setOnClickListener(v -> {
            boolean enabled = !prefs.getBoolean("enabled", true);
            prefs.edit().putBoolean("enabled", enabled).apply();
            updateState(toggle);
        });
        root.addView(toggle);

        fingerprintText = new TextView(this);
        fingerprintText.setTextSize(13f);
        fingerprintText.setPadding(0, 30, 0, 0);
        root.addView(fingerprintText);

        TextView note = new TextView(this);
        note.setText("Workflow: choose an amount, arm the credential, tap the Pixel to your retrofit reader, then tap the physical MIFARE Classic card. The reader writes the local credit record and verifies it. No Wi-Fi is used.");
        note.setTextSize(14f);
        note.setPadding(0, 30, 0, 0);
        root.addView(note);

        setContentView(root);
        updateAmount();
        updateState(toggle);
        updateFingerprint();
    }

    private void cryptoStoreInit() {
        try { CryptoStore.getOrCreate(); } catch (Exception ignored) { }
    }

    private void updateAmount() {
        amountText.setText("Credits to ADD to the next card: " + prefs.getInt("credits", 1));
    }

    private void updateState(Button toggle) {
        boolean hce = getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION);
        boolean nfc = NfcAdapter.getDefaultAdapter(this) != null;
        boolean enabled = prefs.getBoolean("enabled", true);
        stateText.setText("NFC: " + (nfc ? "available" : "missing") + "   HCE: " + (hce ? "available" : "missing") + "\nAdmin credential: " + (enabled ? "ARMED" : "DISABLED"));
        toggle.setText(enabled ? "Disable admin credential" : "Enable admin credential");
    }

    private void updateFingerprint() {
        try {
            byte[] pub = CryptoStore.publicKeyUncompressed();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(pub);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0 && i % 4 == 0) sb.append(' ');
                sb.append(String.format("%02X", digest[i]));
            }
            fingerprintText.setText("Reader provisioning fingerprint (SHA-256):\n" + sb);
        } catch (Exception e) {
            fingerprintText.setText("Unable to initialize Android Keystore key.");
        }
    }
}
