package corunabus;

import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

/**
 * Persistencia en RMS. El catálogo ocupa ~27 KB y algunos Nokia limitan el
 * tamaño de un registro, así que se parte en trozos de 2 KB: el registro 1
 * guarda cuántos trozos hay y los siguientes el texto.
 */
public class Store {

    private static final int CHUNK = 2000;

    private Store() {
    }

    public static String read(String name) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(name, false);
            if (rs.getNumRecords() == 0) return null;
            String header = new String(rs.getRecord(1), "UTF-8");
            int chunks = Catalog.parseInt(header.trim(), 0);
            StringBuffer out = new StringBuffer();
            for (int i = 0; i < chunks; i++) {
                byte[] bytes = rs.getRecord(i + 2);
                if (bytes == null) break;
                out.append(new String(bytes, "UTF-8"));
            }
            return out.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            close(rs);
        }
    }

    public static void write(String name, String text) {
        RecordStore rs = null;
        try {
            try {
                RecordStore.deleteRecordStore(name);
            } catch (RecordStoreException ignored) {
            }
            rs = RecordStore.openRecordStore(name, true);
            int chunks = (text.length() + CHUNK - 1) / CHUNK;
            byte[] header = String.valueOf(chunks).getBytes("UTF-8");
            rs.addRecord(header, 0, header.length);
            for (int i = 0; i < chunks; i++) {
                int start = i * CHUNK;
                int end = Math.min(text.length(), start + CHUNK);
                byte[] bytes = text.substring(start, end).getBytes("UTF-8");
                rs.addRecord(bytes, 0, bytes.length);
            }
        } catch (Throwable ignored) {
        } finally {
            close(rs);
        }
    }

    public static void delete(String name) {
        try {
            RecordStore.deleteRecordStore(name);
        } catch (Throwable ignored) {
        }
    }

    private static void close(RecordStore rs) {
        if (rs != null) {
            try {
                rs.closeRecordStore();
            } catch (Throwable ignored) {
            }
        }
    }
}
