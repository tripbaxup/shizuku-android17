package com.tripforward.localarcadeadmin;

import android.content.SharedPreferences;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class AdminHceService extends HostApduService {
    private static final byte[] AID = hex("F041524341444D494E01");
    private static final byte[] OK = hex("9000");
    private static final byte[] NOT_FOUND = hex("6A82");
    private static final byte[] WRONG_DATA = hex("6A80");
    private static final byte[] CONDITIONS = hex("6985");
    private static final byte PROTOCOL_VERSION = 1;

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        try {
            if (isSelectAid(apdu)) {
                return concat(new byte[]{'A','R','C','A','D','M','I','N', PROTOCOL_VERSION}, OK);
            }
            if (apdu == null || apdu.length < 4 || (apdu[0] & 0xFF) != 0x80) return NOT_FOUND;
            int ins = apdu[1] & 0xFF;
            if (ins == 0x10) {
                int credits = getCreditsPerTap();
                byte[] info = new byte[]{PROTOCOL_VERSION, 0x01, (byte)((credits >>> 8) & 0xFF), (byte)(credits & 0xFF)};
                return concat(info, OK);
            }
            if (ins == 0x11) {
                return concat(CryptoStore.publicKeyUncompressed(), OK);
            }
            if (ins == 0x20) {
                byte[] challenge = getData(apdu);
                if (challenge.length < 16 || challenge.length > 64) return WRONG_DATA;

                SharedPreferences p = getSharedPreferences("admin", MODE_PRIVATE);
                long counter = p.getLong("counter", 0L) + 1L;
                int credits = getCreditsPerTap();
                boolean enabled = p.getBoolean("enabled", true);
                if (!enabled) return CONDITIONS;

                ByteBuffer b = ByteBuffer.allocate(1 + 1 + 2 + 8 + challenge.length);
                b.put(PROTOCOL_VERSION);
                b.put((byte)0x01);
                b.putShort((short)credits);
                b.putLong(counter);
                b.put(challenge);
                byte[] payload = b.array();
                byte[] sig = CryptoStore.sign(payload);

                p.edit().putLong("counter", counter).apply();

                ByteBuffer out = ByteBuffer.allocate(1 + 1 + 2 + 8 + 2 + sig.length);
                out.put(PROTOCOL_VERSION);
                out.put((byte)0x01);
                out.putShort((short)credits);
                out.putLong(counter);
                out.putShort((short)sig.length);
                out.put(sig);
                return concat(out.array(), OK);
            }
            return NOT_FOUND;
        } catch (Exception e) {
            return hex("6F00");
        }
    }

    private int getCreditsPerTap() {
        return Math.max(1, Math.min(99, getSharedPreferences("admin", MODE_PRIVATE).getInt("credits", 1)));
    }

    private static byte[] getData(byte[] apdu) {
        if (apdu.length < 5) return new byte[0];
        int lc = apdu[4] & 0xFF;
        if (apdu.length < 5 + lc) return new byte[0];
        return Arrays.copyOfRange(apdu, 5, 5 + lc);
    }

    private static boolean isSelectAid(byte[] apdu) {
        if (apdu == null || apdu.length < 5) return false;
        if ((apdu[0] & 0xFF) != 0x00 || (apdu[1] & 0xFF) != 0xA4 || (apdu[2] & 0xFF) != 0x04) return false;
        int lc = apdu[4] & 0xFF;
        if (lc != AID.length || apdu.length < 5 + lc) return false;
        return Arrays.equals(AID, Arrays.copyOfRange(apdu, 5, 5 + lc));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] hex(String s) {
        int n = s.length();
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2) out[i / 2] = (byte) Integer.parseInt(s.substring(i, i + 2), 16);
        return out;
    }

    @Override
    public void onDeactivated(int reason) { }
}
