#!/bin/bash

JAR_FILE="${1:?Usage: $0 <path-to-jar> [output-name]}"
OUTPUT_NAME="${2:-poc.litematic.jar}"

if [ ! -f "$JAR_FILE" ]; then
    echo "ERROR: $JAR_FILE not found"
    exit 1
fi

JAR_SIZE=$(wc -c < "$JAR_FILE" | tr -d ' ')
echo "Jar size: $JAR_SIZE bytes"

PAYLOAD_RESOURCE="src/main/resources/payload.jar"
cp "$JAR_FILE" "$PAYLOAD_RESOURCE"
echo "Copied payload jar to $PAYLOAD_RESOURCE"

cat > src/main/java/dev/alienstoearth/litematica/PayloadJar.java << JAVAEOF
package dev.alienstoearth.litematica;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class PayloadJar {
    public static final int SIZE = $JAR_SIZE;
    public static final String FILE_NAME = "../../mods/$OUTPUT_NAME";

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
JAVAEOF

echo "Written src/main/java/dev/alienstoearth/litematica/PayloadJar.java"
