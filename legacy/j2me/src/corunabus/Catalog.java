package corunabus;

import java.util.Vector;

/**
 * Líneas y paradas en memoria. Se construye una vez a partir del texto plano
 * que sirve /j2me/catalog y se guarda tal cual en RMS: el móvil nunca vuelve a
 * pedirlo salvo que el usuario pulse "Actualizar datos".
 */
public class Catalog {

    public static class Line {
        public int id;
        public String code;
        public String from;
        public String to;

        public String toString() {
            return code + " " + from + " - " + to;
        }
    }

    public static class Stop {
        public int id;
        public String name;
        public int lat;   // grados * 1e5
        public int lng;   // grados * 1e5
    }

    public Vector lines = new Vector();
    public Vector stops = new Vector();
    public String version = "";
    public boolean loaded = false;

    public Line line(int id) {
        for (int i = 0; i < lines.size(); i++) {
            Line line = (Line) lines.elementAt(i);
            if (line.id == id) return line;
        }
        return null;
    }

    public Stop stop(int id) {
        for (int i = 0; i < stops.size(); i++) {
            Stop stop = (Stop) stops.elementAt(i);
            if (stop.id == id) return stop;
        }
        return null;
    }

    /** Parsea el formato de /j2me/catalog. Devuelve false si viene mal. */
    public boolean parse(String text) {
        Vector newLines = new Vector();
        Vector newStops = new Vector();
        String newVersion = "";

        int pos = 0;
        while (pos < text.length()) {
            int end = text.indexOf('\n', pos);
            if (end == -1) end = text.length();
            String line = text.substring(pos, end).trim();
            pos = end + 1;
            if (line.length() == 0) continue;

            if (line.startsWith("VER ")) {
                newVersion = line.substring(4).trim();
            } else if (line.startsWith("L ")) {
                String[] f = split(line.substring(2));
                if (f.length < 4) continue;
                Line l = new Line();
                l.id = parseInt(f[0], 0);
                l.code = f[1];
                l.from = f[2];
                l.to = f[3];
                newLines.addElement(l);
            } else if (line.startsWith("P ")) {
                String[] f = split(line.substring(2));
                if (f.length < 4) continue;
                Stop s = new Stop();
                s.id = parseInt(f[0], 0);
                s.name = f[1];
                s.lat = (int) (parseDouble(f[2]) * 100000);
                s.lng = (int) (parseDouble(f[3]) * 100000);
                newStops.addElement(s);
            } else if (line.startsWith("ERR ")) {
                return false;
            }
        }

        if (newStops.size() == 0) return false;
        lines = newLines;
        stops = newStops;
        version = newVersion;
        loaded = true;
        return true;
    }

    /**
     * Misma tolerancia que /m/: sin acentos, sin mayúsculas, sin puntuación y
     * sin importar el orden de las palabras.
     */
    public Vector search(String query, int limit) {
        Vector result = new Vector();
        String q = normalize(query);
        if (q.length() == 0) return result;
        String[] tokens = splitWords(q);
        int exactId = parseInt(q, -1);
        int worst = Integer.MAX_VALUE;
        int[] scores = new int[stops.size()];

        for (int i = 0; i < stops.size(); i++) {
            Stop stop = (Stop) stops.elementAt(i);
            int score;
            if (stop.id == exactId) {
                score = 0;
            } else {
                String name = normalize(stop.name);
                if (name.equals(q)) score = 1;
                else if (name.startsWith(q)) score = 2;
                else if (name.indexOf(q) != -1) score = 3;
                else if (containsAll(name, tokens)) score = 4;
                else score = -1;
            }
            scores[i] = score;
            if (score >= 0 && score < worst) worst = score;
        }

        for (int score = 0; score <= 4 && result.size() < limit; score++) {
            for (int i = 0; i < stops.size() && result.size() < limit; i++) {
                if (scores[i] == score) result.addElement(stops.elementAt(i));
            }
        }
        return result;
    }

    private static boolean containsAll(String name, String[] tokens) {
        for (int i = 0; i < tokens.length; i++) {
            if (name.indexOf(tokens[i]) == -1) return false;
        }
        return true;
    }

    /** Distancia en metros (haversine, entera). */
    public static int distance(int lat1, int lng1, int lat2, int lng2) {
        double a1 = (lat1 / 100000.0) * Math.PI / 180.0;
        double a2 = (lat2 / 100000.0) * Math.PI / 180.0;
        double dLat = a2 - a1;
        double dLng = ((lng2 - lng1) / 100000.0) * Math.PI / 180.0;
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(a1) * Math.cos(a2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return (int) Math.round(2 * 6371000 * Math.asin(Math.min(1, Math.sqrt(h))));
    }

    public static String normalize(String value) {
        String out = value.toLowerCase();
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (c == 'á' || c == 'à' || c == 'ä' || c == 'â') c = 'a';
            else if (c == 'é' || c == 'è' || c == 'ë' || c == 'ê') c = 'e';
            else if (c == 'í' || c == 'ì' || c == 'ï' || c == 'î') c = 'i';
            else if (c == 'ó' || c == 'ò' || c == 'ö' || c == 'ô') c = 'o';
            else if (c == 'ú' || c == 'ù' || c == 'ü' || c == 'û') c = 'u';
            else if (c == 'ñ') c = 'n';
            else if (c == 'ç') c = 'c';
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
            else sb.append(' ');
        }
        // Colapsa espacios.
        String spaced = sb.toString().trim();
        StringBuffer clean = new StringBuffer();
        boolean lastSpace = false;
        for (int i = 0; i < spaced.length(); i++) {
            char c = spaced.charAt(i);
            if (c == ' ') {
                if (!lastSpace) clean.append(' ');
                lastSpace = true;
            } else {
                clean.append(c);
                lastSpace = false;
            }
        }
        return clean.toString();
    }

    public static String[] split(String value) {
        Vector parts = new Vector();
        int start = 0;
        for (int i = 0; i <= value.length(); i++) {
            if (i == value.length() || value.charAt(i) == '|') {
                parts.addElement(value.substring(start, i));
                start = i + 1;
            }
        }
        String[] out = new String[parts.size()];
        for (int i = 0; i < out.length; i++) out[i] = (String) parts.elementAt(i);
        return out;
    }

    /** Divide por espacios (CLDC no tiene String.split). */
    public static String[] splitWords(String value) {
        Vector parts = new Vector();
        int start = -1;
        for (int i = 0; i <= value.length(); i++) {
            boolean space = i == value.length() || value.charAt(i) == ' ';
            if (space) {
                if (start >= 0) {
                    parts.addElement(value.substring(start, i));
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }
        String[] out = new String[parts.size()];
        for (int i = 0; i < out.length; i++) out[i] = (String) parts.elementAt(i);
        return out;
    }

    public static int parseInt(String value, int def) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    public static double parseDouble(String value) {
        try {
            return Double.parseDouble(value.trim().replace(',', '.'));
        } catch (Throwable t) {
            return 0;
        }
    }
}
