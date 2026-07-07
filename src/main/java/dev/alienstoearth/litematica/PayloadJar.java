package dev.alienstoearth.litematica;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class PayloadJar {
    public static final int SIZE = 0;
    public static final String FILE_NAME = "../../mods/poc.litematic.jar";

    public static final byte[] BYTES = loadBytes();

    private static byte[] loadBytes() {
        try {
            InputStream in = PayloadJar.class.getClassLoader().getResourceAsStream("payload.jar");
            if (in == null) {
                throw new RuntimeException("payload.jar not found in classpath");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(SIZE);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            in.close();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
