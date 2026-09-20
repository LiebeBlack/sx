package com.example.telemetry;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Huellas y sal para lo único que el proyecto no guarda en claro: el código de seguridad de la
 * interfaz y la huella del fichero de configuración.
 *
 * <p>Se apoya solo en la biblioteca estándar —{@code MessageDigest} para SHA-256 y
 * {@code SecureRandom} para la sal—, sin añadir dependencias al APK.</p>
 */
public final class Hashing {

    private Hashing() {
    }

    /**
     * SHA-256 en hexadecimal.
     *
     * @return la huella, o {@code null} si el proveedor no está disponible (no debería ocurrir en
     *     ningún Android real: quien llama trata el {@code null} como fallo controlado)
     */
    public static String sha256Hex(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(value.getBytes("UTF-8")));
        } catch (Exception e) {
            return null;
        }
    }

    /** Sal aleatoria criptográficamente segura, en hexadecimal. */
    public static String randomHex(int bytes) {
        byte[] salt = new byte[bytes <= 0 ? 16 : bytes];
        new SecureRandom().nextBytes(salt);
        return toHex(salt);
    }

    /** Comparación en tiempo constante: no filtra información por el momento en que falla. */
    public static boolean equalsConstantTime(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return MessageDigest.isEqual(a.getBytes("UTF-8"), b.getBytes("UTF-8"));
        } catch (Exception e) {
            return a.equals(b);
        }
    }

    private static String toHex(byte[] data) {
        StringBuilder text = new StringBuilder(data.length * 2);
        for (byte value : data) {
            text.append(String.format(Locale.US, "%02x", value));
        }
        return text.toString();
    }
}
