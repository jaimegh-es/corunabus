package corunabus;

import java.util.Vector;
import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;

/**
 * Coruña Bus para Nokia E71 (MIDP 2.0 / CLDC 1.1).
 *
 * El teléfono solo guarda líneas y paradas; las rutas y las llegadas las
 * calcula el servidor y llegan como texto plano. El catálogo se descarga una
 * vez y solo se vuelve a pedir con "Actualizar datos".
 */
public class BusMIDlet extends MIDlet implements CommandListener, ScrollList.Listener,
        TextView.Listener {

    private static final String DEFAULT_URL = "https://buscoruna.inled.es";
    private static final String SETTINGS_KEY = "settings";
    private static final String CATALOG_KEY = "catalog";
    private static final String FAVS_KEY = "favs";
    private static final String PLANS_KEY = "plans";

    private Display display;
    private Catalog catalog = new Catalog();
    private String baseUrl = DEFAULT_URL;

    private List menu;
    private ScrollList favoritesList;
    private ScrollList stopPicker;
    private ScrollList resultsList;
    private ScrollList planList;
    private Vector planOptions = new Vector();
    private Vector planBuses = new Vector();
    private int planChoice = 0;
    private int planBoardStop = 0;
    private boolean stopFromPlan = false;
    private Form screen;
    private TextView planDetail;
    private TextBox searchBox;
    private Form settingsScreen;

    private int currentStop = 0;
    private StringItem stopBody;

    // 0 = navegando, 1 = eligiendo origen, 2 = eligiendo destino
    private int planMode = 0;
    private int planOrigin = 0;
    private int pendingOrigin = 0;
    private int pendingDest = 0;
    private String planTime = "";
    private int shownOrigin = 0;
    private int shownDest = 0;
    private String shownLabel = "";

    private final Command back = new Command("Atrás", Command.BACK, 2);
    private final Command exit = new Command("Salir", Command.EXIT, 3);
    private final Command refresh = new Command("Actualizar", Command.SCREEN, 2);
    private final Command save = new Command("Favorito", Command.SCREEN, 3);
    private final Command route = new Command("Ruta", Command.SCREEN, 4);
    private final Command ok = new Command("OK", Command.OK, 1);

    protected void startApp() {
        try {
            display = Display.getDisplay(this);
            baseUrl = Store.read(SETTINGS_KEY);
            if (baseUrl == null || baseUrl.length() == 0) baseUrl = DEFAULT_URL;

            buildMenu();

            // El menú aparece al instante y lo demás va detrás: primero se lee el
            // catálogo guardado y, si no hay, se descarga sin bloquear el arranque.
            display.setCurrent(menu);
            String cached = Store.read(CATALOG_KEY);
            if (cached != null) catalog.parse(cached);
            if (catalog.loaded) setMenuTitle("Coruña Bus");
            else downloadCatalog(false);
        } catch (Throwable t) {
            crashed(t);
        }
    }

    /** Cambios de pantalla desde un hilo de fondo: pasan por el hilo de UI. */
    private void ui(final Runnable action) {
        final Runnable safe = new Runnable() {
            public void run() {
                try {
                    action.run();
                } catch (Throwable t) {
                    crashed(t);
                }
            }
        };
        try {
            display.callSerially(safe);
        } catch (Throwable t) {
            safe.run();
        }
    }

    private void setMenuTitle(String title) {
        try {
            menu.setTitle(title);
        } catch (Throwable ignored) {
        }
    }

    /** Último recurso: evita el "excepción imprevista" del sistema. */
    private void crashed(Throwable t) {
        try {
            Alert alert = new Alert("Error");
            alert.setString("Fallo interno: " + t.toString());
            alert.setType(AlertType.ERROR);
            alert.setTimeout(Alert.FOREVER);
            display.setCurrent(alert, menu);
        } catch (Throwable ignored) {
        }
    }

    protected void pauseApp() {
    }

    protected void destroyApp(boolean unconditional) {
    }

    // ---------------------------------------------------------------- menú

    private void buildMenu() {
        menu = new List("Coruña Bus", List.IMPLICIT);
        menu.append("Paradas cercanas", null);
        menu.append("Buscar parada", null);
        menu.append("Favoritos", null);
        menu.append("Planificar ruta", null);
        menu.append("Ajustes", null);
        menu.addCommand(ok);
        menu.addCommand(exit);
        menu.setSelectCommand(ok);
        menu.setCommandListener(this);

    }

    private void showMenu() {
        stopFromPlan = false;
        display.setCurrent(menu);
    }

    private void showError(String message) {
        Alert alert = new Alert("Error");
        alert.setString(message);
        alert.setType(AlertType.ERROR);
        alert.setTimeout(5000);
        display.setCurrent(alert, menu);
    }

    private void showBusy(String message) {
        display.setCurrent(new BusyCanvas("Espera", message));
    }

    // ------------------------------------------------------------- catálogo

    private void downloadCatalog(final boolean manual) {
        final boolean refresh = manual;
        if (manual) showBusy("Actualizando datos...");
        else setMenuTitle("Coruña Bus - cargando datos...");
        new Thread(new Runnable() {
            public void run() {
                String text = null;
                String detail = "";
                String path = "/j2me/catalog" + (refresh ? "?refresh=1" : "");
                try {
                    // La primera conexión tras arrancar puede fallar mientras el
                    // teléfono negocia el punto de acceso: se reintenta una vez.
                    for (int attempt = 0; attempt < 2 && text == null; attempt++) {
                        try {
                            text = Net.getPath(baseUrl, path);
                        } catch (Throwable t) {
                            detail = t.toString();
                            if (attempt == 0) {
                                try {
                                    Thread.sleep(1500);
                                } catch (InterruptedException ignored) {
                                }
                            }
                        }
                    }
                    final boolean parsed = text != null && catalog.parse(text);
                    if (parsed) Store.write(CATALOG_KEY, text);
                    if (text != null && !parsed && detail.length() == 0) {
                        detail = "respuesta no válida (" + text.length() + " bytes)";
                    }
                    final String failure = detail;
                    ui(new Runnable() {
                        public void run() {
                            setMenuTitle("Coruña Bus");
                            display.setCurrent(menu);
                            if (parsed) {
                                if (manual) {
                                    Alert alert = new Alert("Datos");
                                    alert.setString(catalog.stops.size() + " paradas y "
                                            + catalog.lines.size() + " líneas actualizadas.");
                                    alert.setTimeout(3000);
                                    display.setCurrent(alert, menu);
                                }
                            } else if (manual || !catalog.loaded) {
                                Alert alert = new Alert("Sin conexión");
                                alert.setString("No se pudo descargar la lista de paradas.\n" + baseUrl
                                        + "\n" + failure);
                                alert.setType(AlertType.ERROR);
                                alert.setTimeout(Alert.FOREVER);
                                display.setCurrent(alert, menu);
                            }
                        }
                    });
                } catch (Throwable t) {
                    final String message = t.toString();
                    ui(new Runnable() {
                        public void run() {
                            setMenuTitle("Coruña Bus");
                            showError("Fallo cargando datos: " + message);
                        }
                    });
                }
            }
        }).start();
    }

    // --------------------------------------------------------------- paradas

    private void openStop(final int stopId) {
        currentStop = stopId;
        showBusy("Consultando parada...");
        new Thread(new Runnable() {
            public void run() {
                String text;
                try {
                    text = Net.getPath(baseUrl, "/j2me/stop?id=" + stopId);
                } catch (Throwable t) {
                    text = "ERR " + t.toString();
                }
                final String body = text;
                ui(new Runnable() {
                    public void run() {
                        renderStop(body);
                    }
                });
            }
        }).start();
    }

    private void renderStop(String text) {
        Catalog.Stop stop = catalog.stop(currentStop);
        String title = stop != null ? stop.name : "Parada " + currentStop;

        StringBuffer body = new StringBuffer();
        String[] lines = splitLines(text);

        String updated = "";
        boolean any = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("L ")) {
                String[] f = Catalog.split(line.substring(2));
                if (f.length < 2) continue;
                body.append(pad(f[0], 4));
                String minutes = f[1];
                if (minutes.length() == 0) {
                    body.append("sin datos");
                } else {
                    int comma = minutes.indexOf(',');
                    body.append(comma > 0 ? minutes.substring(0, comma) : minutes);
                    body.append(" min");
                }
                body.append("\n");
                any = true;
            } else if (line.startsWith("T ")) {
                updated = line.substring(2);
            } else if (line.startsWith("ERR ")) {
                body.append(line.substring(4)).append('\n');
                any = true;
            }
        }

        if (!any) body.append("No hay líneas para esta parada.");
        if (updated.length() > 0) {
            body.append("\nDatos de las ").append(updated);
        }

        screen = new Form(title);
        stopBody = new StringItem("", body.toString());
        screen.append(stopBody);
        screen.addCommand(refresh);
        screen.addCommand(save);
        screen.addCommand(route);
        screen.addCommand(back);
        screen.setCommandListener(this);
        display.setCurrent(screen);
    }

    private String pad(String value, int width) {
        StringBuffer out = new StringBuffer(value);
        while (out.length() < width) out.append(' ');
        return out.toString();
    }

    // ------------------------------------------------------------ búsqueda

    private void openSearch() {
        searchBox = new TextBox("Buscar parada", "", 40, TextField.ANY);
        searchBox.addCommand(ok);
        searchBox.addCommand(back);
        searchBox.setCommandListener(this);
        display.setCurrent(searchBox);
    }

    private void runSearch() {
        String query = searchBox.getString();
        Vector found = catalog.search(query, 25);
        if (found.size() == 0) {
            Alert alert = new Alert("Sin resultados");
            alert.setString("Prueba con una palabra del nombre o el número de parada.");
            alert.setTimeout(3000);
            display.setCurrent(alert, searchBox);
            return;
        }
        resultsList = new ScrollList("Resultados");
        resultsList.setListener(this);
        for (int i = 0; i < found.size(); i++) {
            Catalog.Stop stop = (Catalog.Stop) found.elementAt(i);
            resultsList.add(stop.name + " #" + stop.id);
        }
        display.setCurrent(resultsList);
    }

    private int resultId(int index) {
        Vector found = catalog.search(searchBox.getString(), 25);
        if (index < 0 || index >= found.size()) return 0;
        return ((Catalog.Stop) found.elementAt(index)).id;
    }

    // ---------------------------------------------------------- favoritos

    private String readFavorites() {
        String raw = Store.read(FAVS_KEY);
        return raw == null ? "" : raw;
    }

    private Vector favoriteEntries() {
        Vector entries = new Vector(); // String[] {id, name}
        String raw = readFavorites();
        String[] lines = splitLines(raw);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() == 0) continue;
            String[] f = Catalog.split(line);
            if (f.length < 2) continue;
            int id = Catalog.parseInt(f[0], 0);
            if (id > 0) entries.addElement(new String[] { f[0], f[1] });
        }
        return entries;
    }

    private boolean isFavorite(int stopId) {
        Vector entries = favoriteEntries();
        for (int i = 0; i < entries.size(); i++) {
            String[] entry = (String[]) entries.elementAt(i);
            if (Catalog.parseInt(entry[0], 0) == stopId) return true;
        }
        return false;
    }

    private void saveFavorite() {
        if (currentStop == 0) return;
        if (isFavorite(currentStop)) {
            showError("Ya está en favoritos.");
            return;
        }
        Catalog.Stop stop = catalog.stop(currentStop);
        String name = stop != null ? stop.name : String.valueOf(currentStop);
        String raw = readFavorites();
        Store.write(FAVS_KEY, raw + currentStop + "|" + clean(name) + "\n");
        Alert alert = new Alert("Favoritos");
        alert.setString(name + " guardada.");
        alert.setTimeout(2500);
        display.setCurrent(alert, screen);
    }

    private void openFavorites() {
        Vector entries = favoriteEntries();
        Vector plans = planEntries();
        favoritesList = new ScrollList("Favoritos");
        favoritesList.setListener(this);
        for (int i = 0; i < entries.size(); i++) {
            favoritesList.add(((String[]) entries.elementAt(i))[1]);
        }
        for (int i = 0; i < plans.size(); i++) {
            String[] plan = (String[]) plans.elementAt(i);
            String suffix = plan[2].length() > 0 ? " (" + plan[2] + ")" : "";
            favoritesList.add("Ruta: " + planTitle(plan) + suffix);
        }
        if (entries.size() + plans.size() == 0) {
            favoritesList.add("Aún no hay favoritos.");
        } else {
            favoritesList.setExtraLabel("Quitar");
        }
        display.setCurrent(favoritesList);
    }

    private void openFavorite(int index) {
        Vector entries = favoriteEntries();
        Vector plans = planEntries();
        if (index >= 0 && index < entries.size()) {
            selectStop(Catalog.parseInt(((String[]) entries.elementAt(index))[0], 0));
        } else if (index - entries.size() >= 0 && index - entries.size() < plans.size()) {
            String[] plan = (String[]) plans.elementAt(index - entries.size());
            loadPlan(Catalog.parseInt(plan[0], 0), Catalog.parseInt(plan[1], 0), plan[2]);
        }
    }

    private void removeFavoriteAt(int index) {
        int stops = favoriteEntries().size();
        if (index < stops) removeFavorite(index);
        else removePlanFavorite(index - stops);
    }

    /** Rutas guardadas: String[] {origen, destino, hora, etiqueta}. */
    private Vector planEntries() {
        Vector out = new Vector();
        String raw = Store.read(PLANS_KEY);
        String[] lines = splitLines(raw == null ? "" : raw);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].length() == 0) continue;
            String[] f = Catalog.split(lines[i]);
            if (f.length < 4) continue;
            if (Catalog.parseInt(f[0], 0) > 0 && Catalog.parseInt(f[1], 0) > 0) out.addElement(f);
        }
        return out;
    }

    private String planTitle(String[] entry) {
        String label = entry.length > 3 ? entry[3] : "";
        if (label.length() > 0) return label;
        return nameOf(entry[0]) + " -> " + nameOf(entry[1]);
    }

    private void savePlanFavorite() {
        if (shownOrigin <= 0 || shownDest <= 0) return;
        String label = shownLabel.length() > 0
                ? shownLabel
                : nameOf(String.valueOf(shownOrigin)) + " -> " + nameOf(String.valueOf(shownDest));
        Vector entries = planEntries();
        for (int i = 0; i < entries.size(); i++) {
            String[] entry = (String[]) entries.elementAt(i);
            if (Catalog.parseInt(entry[0], 0) == shownOrigin
                    && Catalog.parseInt(entry[1], 0) == shownDest
                    && entry[2].equals(planTime)) {
                showError("Esa ruta ya está en favoritos.");
                return;
            }
        }
        String raw = Store.read(PLANS_KEY);
        Store.write(PLANS_KEY, (raw == null ? "" : raw) + shownOrigin + "|" + shownDest + "|"
                + planTime + "|" + clean(label) + "\n");
        Alert alert = new Alert("Favoritos");
        alert.setString(clean(label) + " guardada.");
        alert.setTimeout(2500);
        display.setCurrent(alert, screen);
    }

    private void removePlanFavorite(int index) {
        Vector entries = planEntries();
        if (index < 0 || index >= entries.size()) return;
        StringBuffer out = new StringBuffer();
        for (int i = 0; i < entries.size(); i++) {
            if (i == index) continue;
            String[] entry = (String[]) entries.elementAt(i);
            out.append(entry[0]).append('|').append(entry[1]).append('|')
                    .append(entry[2]).append('|').append(entry[3]).append('\n');
        }
        Store.write(PLANS_KEY, out.toString());
        openFavorites();
    }

    private void removeFavorite(int index) {
        Vector entries = favoriteEntries();
        if (index < 0 || index >= entries.size()) return;
        String[] target = (String[]) entries.elementAt(index);
        StringBuffer out = new StringBuffer();
        for (int i = 0; i < entries.size(); i++) {
            String[] entry = (String[]) entries.elementAt(i);
            if (i == index) continue;
            out.append(entry[0]).append('|').append(entry[1]).append('\n');
        }
        Store.write(FAVS_KEY, out.toString());
        openFavorites();
    }

    // ---------------------------------------------------------- cercanas

    private void openNearby() {
        showBusy("Buscando GPS...");
        new Thread(new Runnable() {
            public void run() {
                int[] position;
                try {
                    position = Gps.locate();
                } catch (Throwable t) {
                    position = null;
                }
                final int[] found = position;
                ui(new Runnable() {
                    public void run() {
                        if (found == null) {
                            Alert alert = new Alert("GPS");
                            alert.setString("No hay posición. Usa \"Buscar parada\" o sal al exterior y reintenta.");
                            alert.setTimeout(5000);
                            display.setCurrent(alert, menu);
                            return;
                        }
                        showNearbyList(found[0], found[1]);
                    }
                });
            }
        }).start();
    }

    private void showNearbyList(int lat, int lng) {
        Vector best = new Vector();
        for (int i = 0; i < catalog.stops.size(); i++) {
            Catalog.Stop stop = (Catalog.Stop) catalog.stops.elementAt(i);
            if (stop.lat == 0 && stop.lng == 0) continue;
            int meters = Catalog.distance(lat, lng, stop.lat, stop.lng);
            best.addElement(new Object[] { stop, new Integer(meters) });
        }
        // Orden simple por distancia (N=537, no hace falta más).
        for (int i = 0; i < best.size(); i++) {
            for (int j = i + 1; j < best.size(); j++) {
                Object[] a = (Object[]) best.elementAt(i);
                Object[] b = (Object[]) best.elementAt(j);
                if (((Integer) a[1]).intValue() > ((Integer) b[1]).intValue()) {
                    best.setElementAt(b, i);
                    best.setElementAt(a, j);
                }
            }
        }

        stopPicker = new ScrollList("Paradas cercanas");
        stopPicker.setListener(this);
        int shown = 0;
        for (int i = 0; i < best.size() && shown < 8; i++) {
            Object[] entry = (Object[]) best.elementAt(i);
            Catalog.Stop stop = (Catalog.Stop) entry[0];
            int meters = ((Integer) entry[1]).intValue();
            if (meters > 1500) break;
            stopPicker.add(stop.name + " (" + meters + " m)");
            shown++;
        }
        if (shown == 0) stopPicker.add("No hay paradas cerca.");
        nearbyIds = new int[shown];
        for (int i = 0; i < shown; i++) {
            Object[] entry = (Object[]) best.elementAt(i);
            nearbyIds[i] = ((Catalog.Stop) entry[0]).id;
        }
        display.setCurrent(stopPicker);
    }

    private int[] nearbyIds = new int[0];
    /** Pantalla de aviso que se ve mientras llega el catálogo. */
    private List loadingScreen;

    // --------------------------------------------------------- planificar

    private void startPlan() {
        if (!catalog.loaded) {
            showError("Faltan los datos de paradas.");
            return;
        }
        planMode = 1;
        chooseStop();
    }

    private void chooseStop() {
        Vector entries = favoriteEntries();
        stopPicker = new ScrollList(planMode == 2 ? "Destino" : "Origen");
        stopPicker.setListener(this);
        for (int i = 0; i < entries.size(); i++) {
            stopPicker.add(((String[]) entries.elementAt(i))[1]);
        }
        stopPicker.add("Buscar parada...");
        pickerIds = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            pickerIds[i] = Catalog.parseInt(((String[]) entries.elementAt(i))[0], 0);
        }
        display.setCurrent(stopPicker);
    }

    private int[] pickerIds = new int[0];

    private void selectStop(int stopId) {
        if (planMode == 1) {
            planOrigin = stopId;
            planMode = 2;
            chooseStop();
        } else if (planMode == 2) {
            if (stopId == planOrigin) {
                showError("Origen y destino son la misma parada.");
                return;
            }
            int destination = stopId;
            planMode = 0;
            askTime(planOrigin, destination);
        } else {
            openStop(stopId);
        }
    }

    /** Selector de hora límite de llegada; "Ahora" planifica sin hora. */
    private void askTime(final int origin, final int destination) {
        pendingOrigin = origin;
        pendingDest = destination;
        java.util.Calendar now = java.util.Calendar.getInstance();
        TimePicker picker = new TimePicker(now.get(java.util.Calendar.HOUR_OF_DAY),
                now.get(java.util.Calendar.MINUTE), new TimePicker.Listener() {
            public void timePicked(String hhmm) {
                loadPlan(origin, destination, hhmm == null ? "" : hhmm);
            }

            public void timeCancelled() {
                planMode = 0;
                showMenu();
            }
        });
        display.setCurrent(picker);
    }

    private void loadPlan(final int origin, final int destination, final String time) {
        shownOrigin = origin;
        shownDest = destination;
        planTime = time == null ? "" : time;
        shownLabel = "";
        showBusy("Calculando ruta...");
        new Thread(new Runnable() {
            public void run() {
                String text;
                String suffix = time != null && time.length() > 0 ? "&t=" + Net.urlEncode(time) : "";
                try {
                    text = Net.getPath(baseUrl, "/j2me/plan?o=" + origin + "&d=" + destination + suffix);
                } catch (Throwable t) {
                    text = "ERR " + t.toString();
                }
                final String body = text;
                ui(new Runnable() {
                    public void run() {
                        renderPlan(body);
                    }
                });
            }
        }).start();
    }

    private void renderPlan(String text) {
        String[] lines = splitLines(text);
        String originName = "";
        String destName = "";
        String error = "";
        planOptions.removeAllElements();
        planBuses.removeAllElements();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("O ")) {
                String[] f = Catalog.split(line.substring(2));
                originName = f.length > 1 ? f[1] : "";
            } else if (line.startsWith("D ")) {
                String[] f = Catalog.split(line.substring(2));
                destName = f.length > 1 ? f[1] : "";
            } else if (line.startsWith("R ")) {
                String[] f = Catalog.split(line.substring(2));
                if (f.length < 8) continue;
                planOptions.addElement(f);
                planBuses.addElement(new Vector());
            } else if (line.startsWith("A ") && planBuses.size() > 0) {
                String[] f = Catalog.split(line.substring(2));
                if (f.length < 5) continue;
                int n = Catalog.parseInt(f[0], 0) - 1;
                if (n < 0 || n >= planBuses.size()) continue;
                ((Vector) planBuses.elementAt(n)).addElement(
                        new String[] { f[1], f[2], f[3], f[4] });
            } else if (line.startsWith("ERR")) {
                error = line.length() > 4 ? line.substring(4).trim() : "";
            }
        }

        shownLabel = originName.length() > 0 ? originName + " -> " + destName : "Ruta";

        if (planOptions.size() == 0) {
            StringBuffer body = new StringBuffer();
            if (error.length() > 0) body.append("No se pudo calcular la ruta.\n").append(error);
            else body.append("No hay ruta directa ni con un transbordo.");
            planDetail = new TextView("Ruta", body.toString(), null, this);
            display.setCurrent(planDetail);
            return;
        }

        planList = new ScrollList(shownLabel);
        planList.setListener(this);
        for (int i = 0; i < planOptions.size(); i++) {
            planList.add(optionLabel((String[]) planOptions.elementAt(i), firstBus(i)));
        }
        display.setCurrent(planList);
    }

    /** Primer bus (el recomendado) de una opción, o null si no hay datos en vivo. */
    private String[] firstBus(int index) {
        if (index < 0 || index >= planBuses.size()) return null;
        Vector buses = (Vector) planBuses.elementAt(index);
        return buses.size() > 0 ? (String[]) buses.elementAt(0) : null;
    }

    /** "Línea 14 directo · pasa 22:31 (en 3 min) · 8 par · llega 22:52". */
    private String optionLabel(String[] f, String[] bus) {
        int base = "D".equals(f[1]) ? 8 : 13;
        StringBuffer out = new StringBuffer();
        if ("D".equals(f[1])) out.append("Línea ").append(f[2]).append(" directo");
        else out.append("Línea ").append(f[2]).append(" + ").append(f[8]);
        String depart = bus != null ? bus[1] : "";
        String wait = bus != null ? bus[0] : field(f, base);
        String arrive = bus != null && bus[2].length() > 0 ? bus[2] : field(f, base + 1);
        String late = bus != null ? bus[3] : field(f, base + 2);
        if (depart.length() > 0) {
            out.append(" · pasa ").append(depart);
            if (wait.length() > 0) out.append(" (en ").append(wait).append(" min)");
        } else if (wait.length() > 0) {
            out.append(" · en ").append(wait).append(" min");
        }
        out.append(" · ").append(f[5]).append(" par · ").append(f[6]).append(" min");
        if (arrive.length() > 0) out.append(" · llega ").append(arrive);
        if ("1".equals(late)) out.append(" (tarde)");
        return out.toString();
    }

    private String field(String[] f, int index) {
        return index >= 0 && index < f.length ? f[index] : "";
    }

    /** Datos completos de la opción: paradas, hora de paso y demás buses. */
    private void openPlanDetail(int index) {
        if (index < 0 || index >= planOptions.size()) return;
        planChoice = index;
        String[] f = (String[]) planOptions.elementAt(index);
        planBoardStop = Catalog.parseInt(f[3], 0);
        Vector buses = (Vector) planBuses.elementAt(index);
        int base = "D".equals(f[1]) ? 8 : 13;

        StringBuffer body = new StringBuffer();
        if ("D".equals(f[1])) {
            body.append("Línea ").append(f[2]).append(" directo\n");
            body.append(f[5]).append(" paradas, ~").append(f[6]).append(" min\n");
            body.append("Sube en ").append(nameOf(f[3])).append('\n');
            body.append("Baja en ").append(nameOf(f[4])).append('\n');
        } else {
            body.append("Línea ").append(f[2]).append(" + línea ").append(f[8]).append('\n');
            body.append(f[5]).append(" paradas, ~").append(f[6]).append(" min\n");
            body.append("Sube en ").append(nameOf(f[3])).append('\n');
            body.append("Cambia en ").append(nameOf(f[4]));
            int walk = Catalog.parseInt(f[7], 0);
            if (walk > 0) body.append(" (").append(walk).append(" m andando)");
            body.append('\n');
            body.append("Baja en ").append(nameOf(f[10])).append('\n');
        }

        String[] first = firstBus(index);
        String depart = first != null ? first[1] : "";
        String wait = first != null ? first[0] : field(f, base);
        String arrive = first != null && first[2].length() > 0 ? first[2] : field(f, base + 1);
        String late = first != null ? first[3] : field(f, base + 2);

        body.append('\n');
        if (depart.length() > 0) {
            body.append("Pasa por tu parada a las ").append(depart);
            if (wait.length() > 0) body.append(" (en ").append(wait).append(" min)");
        } else if (wait.length() > 0) {
            body.append("Bus en ").append(wait).append(" min");
        } else {
            body.append("Sin datos en vivo");
        }
        body.append('\n');
        if (arrive.length() > 0) body.append("Llega a las ").append(arrive).append('\n');
        if ("1".equals(late)) body.append("No llega a la hora pedida.\n");

        if (buses.size() > 1) {
            body.append('\n').append("Otros buses de la línea ").append(f[2]);
            if (!"D".equals(f[1])) body.append(" en tu parada");
            body.append(":\n");
            for (int i = 1; i < buses.size(); i++) {
                String[] b = (String[]) buses.elementAt(i);
                body.append(b[1]);
                if (b[0].length() > 0) body.append(" (en ").append(b[0]).append(" min)");
                if (b[2].length() > 0) body.append(" · llega ").append(b[2]);
                if ("1".equals(b[3])) body.append(" tarde");
                body.append('\n');
            }
        }

        planDetail = new TextView("Ruta", body.toString(), "Parada", "Favorito", this);
        display.setCurrent(planDetail);
    }

    private String nameOf(String stopId) {
        Catalog.Stop stop = catalog.stop(Catalog.parseInt(stopId, 0));
        return stop != null ? stop.name : "parada " + stopId;
    }

    // ----------------------------------------------------------- ajustes

    private void openSettings() {
        settingsScreen = new Form("Ajustes");
        settingsScreen.append("Servidor (sin barra final). Si usas HTTP, escribe http:// y el puerto.\n");
        final TextField url = new TextField("Dirección", baseUrl, 120, TextField.URL);
        settingsScreen.append(url);
        settingsScreen.addCommand(new Command("Guardar", Command.OK, 1));
        settingsScreen.addCommand(new Command("Datos", Command.SCREEN, 2));
        settingsScreen.addCommand(back);
        settingsScreen.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c.getCommandType() == Command.BACK) {
                    showMenu();
                    return;
                }
                if ("Datos".equals(c.getLabel())) {
                    baseUrl = url.getString().trim();
                    if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                    Store.write(SETTINGS_KEY, baseUrl);
                    downloadCatalog(true);
                    return;
                }
                baseUrl = url.getString().trim();
                if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                if (baseUrl.length() == 0) baseUrl = DEFAULT_URL;
                Store.write(SETTINGS_KEY, baseUrl);
                Alert alert = new Alert("Ajustes");
                alert.setString("Servidor guardado.");
                alert.setTimeout(2500);
                display.setCurrent(alert, menu);
            }
        });
        display.setCurrent(settingsScreen);
    }

    // ------------------------------------------------------------- eventos

    public void commandAction(Command c, Displayable d) {
        try {
            handleCommand(c, d);
        } catch (Throwable t) {
            crashed(t);
        }
    }

    private void handleCommand(Command c, Displayable d) {
        if (d == menu) {
            if (c == exit) {
                notifyDestroyed();
                return;
            }
            int index = menu.getSelectedIndex();
            if (index <= 3 && !catalog.loaded) {
                showError("Descargando la lista de paradas. Espera unos segundos.");
                return;
            }
            if (index == 0) openNearby();
            else if (index == 1) {
                planMode = 0;
                openSearch();
            } else if (index == 2) openFavorites();
            else if (index == 3) startPlan();
            else if (index == 4) openSettings();
            return;
        }

        if (d == searchBox) {
            if (c == back) {
                planMode = 0;
                showMenu();
            } else {
                runSearch();
            }
            return;
        }

        if (d == screen) {
            if (c == refresh) {
                openStop(currentStop);
            } else if (c == save) {
                saveFavorite();
            } else if (c == route) {
                planOrigin = currentStop;
                planMode = 2;
                chooseStop();
            } else if (stopFromPlan && planDetail != null) {
                stopFromPlan = false;
                display.setCurrent(planDetail);
            } else {
                showMenu();
            }
        }
    }

    // ------------------------------------------------------ detalle de ruta

    /** Atrás desde el itinerario: siempre vuelve a la lista de rutas. */
    public void textBack() {
        try {
            if (planList != null) display.setCurrent(planList);
            else showMenu();
        } catch (Throwable t) {
            crashed(t);
        }
    }

    /** Abre la parada de salida para ver cuánto le queda al bus. */
    public void textExtra() {
        try {
            if (planBoardStop > 0) {
                stopFromPlan = true;
                openStop(planBoardStop);
            }
        } catch (Throwable t) {
            crashed(t);
        }
    }

    public void textExtra2() {
        try {
            savePlanFavorite();
        } catch (Throwable t) {
            crashed(t);
        }
    }

    // --------------------------------------------------------------- listas

    public void listSelected(ScrollList list, int index) {
        try {
            if (list == planList) {
                openPlanDetail(index);
            } else if (list == favoritesList) {
                openFavorite(index);
            } else if (list == resultsList) {
                int id = resultId(index);
                if (id > 0) selectStop(id);
            } else if (list == stopPicker) {
                pickStop(index);
            }
        } catch (Throwable t) {
            crashed(t);
        }
    }

    public void listBack(ScrollList list) {
        try {
            if (list == resultsList && planMode == 0) {
                display.setCurrent(searchBox);
            } else if (list == resultsList) {
                chooseStop();
            } else if (list == stopPicker) {
                planMode = 0;
                showMenu();
            } else {
                showMenu();
            }
        } catch (Throwable t) {
            crashed(t);
        }
    }

    public void listExtra(ScrollList list, int index) {
        try {
            if (list == favoritesList) removeFavoriteAt(index);
        } catch (Throwable t) {
            crashed(t);
        }
    }

    private void pickStop(int index) {
        if (planMode != 0) {
            if (index < pickerIds.length) selectStop(pickerIds[index]);
            else openSearch();
            return;
        }
        if (index >= 0 && index < nearbyIds.length) openStop(nearbyIds[index]);
    }

    // ------------------------------------------------------------ utilidades

    private static String[] splitLines(String text) {
        Vector parts = new Vector();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                String line = text.substring(start, i).trim();
                parts.addElement(line);
                start = i + 1;
            }
        }
        String[] out = new String[parts.size()];
        for (int i = 0; i < out.length; i++) out[i] = (String) parts.elementAt(i);
        return out;
    }

    private static String clean(String value) {
        return value.replace('|', '/').replace('\n', ' ');
    }
}
