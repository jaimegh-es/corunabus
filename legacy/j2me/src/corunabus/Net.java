package corunabus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;

/**
 * HTTP mínimo sobre HttpConnection. Descarga bytes y los decodifica en UTF-8;
 * si el servidor no manda UTF-8 válido cae a ISO-8859-1, que es lo que entiende
 * cualquier S60 sin sorpresas.
 */
public class Net {

    public static final int TIMEOUT = 20000;

    private Net() {
    }

    /**
     * GET con reserva: si el servidor está en https y el TLS del E71 no negocia,
     * reintenta por http. Así basta con apagar "Always Use HTTPS" en Cloudflare
     * sin tocar la dirección guardada.
     */
    public static String getPath(String baseUrl, String path) throws IOException {
        try {
            return get(baseUrl + path);
        } catch (IOException error) {
            if (baseUrl.startsWith("https://")) {
                return get("http://" + baseUrl.substring(8) + path);
            }
            throw error;
        }
    }

    public static String get(String url) throws IOException {
        HttpConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpConnection) Connector.open(url, Connector.READ, true);
            conn.setRequestMethod(HttpConnection.GET);
            conn.setRequestProperty("User-Agent", "CorunaBusMIDlet/1.0");
            conn.setRequestProperty("Accept", "text/plain");
            int status = conn.getResponseCode();
            if (status != HttpConnection.HTTP_OK) {
                throw new IOException("HTTP " + status);
            }
            in = conn.openInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return decode(buffer.toByteArray());
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (conn != null) {
                try {
                    conn.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** UTF-8 con reserva a ISO-8859-1 si aparecen caracteres de reemplazo. */
    static String decode(byte[] bytes) {
        String text;
        try {
            text = new String(bytes, "UTF-8");
        } catch (Throwable t) {
            text = new String(bytes);
        }
        if (text.indexOf('\uFFFD') != -1) {
            try {
                text = new String(bytes, "ISO-8859-1");
            } catch (Throwable ignored) {
            }
        }
        return text;
    }

    public static String urlEncode(String value) {
        StringBuffer out = new StringBuffer();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c);
            } else if (c == ' ') {
                out.append("%20");
            } else {
                byte[] bytes;
                try {
                    bytes = String.valueOf(c).getBytes("UTF-8");
                } catch (Throwable t) {
                    bytes = new byte[] { (byte) c };
                }
                for (int j = 0; j < bytes.length; j++) {
                    int b = bytes[j] & 0xff;
                    out.append('%');
                    out.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xf, 16)));
                    out.append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
                }
            }
        }
        return out.toString();
    }
}
