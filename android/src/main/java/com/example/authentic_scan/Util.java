package com.example.authentic_scan;

public class Util {

    public static byte[] boolean2byte(boolean[] data) {

        byte[] result = new byte[10];

        int ai = 0;
        int bi = 0;

        for(int i=0; i<data.length; i++) {
            result[ai] |= data[i] ? 0x01 : 0x00;
            if(bi++ < 8 - 1) {
                result[ai] = (byte) (result[ai] << 1);
            } else {
                ai++;
                bi = 0;
            }
        }

        return result;
    }

    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
    public static String byte2hex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
}
