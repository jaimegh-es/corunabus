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
public class BusMIDlet extends MIDlet implements CommandListener {

    private static final String DEFAULT_URL = "https://buscoruna.inled.es";
    private static final String SETTINGS_KEY = "settings";
    private static final String CATALOG_KEY = "catalog";
    private static final String FAVS_KEY = "favs";

    private Display display;
    private Catalog catalog = new Catalog();
    private String baseUrl = DEFAULT_URL;

    private List menu;
    private List favoritesList;
    private List stopPicker;
    private List resultsList;
    private Form screen;
    private TextBox searchBox;
    private Form settingsScreen;

    private int currentStop = 0;
    private StringItem stopBody;
    private StringItem planBody;

    // 0 = navegando, 1 = eligiendo origen, 2 = eligiendo destino
    private int planMode = 0;
    private int planOrigin = 0;
    private int pendingOrigin = 0;
    private int pendingDest = 0;
    private TextBox timeBox;

    private final Command back = new Command("Atrás", Command.BACK, 2);
    private final Command exit = new Command("Salir", Command.EXIT, 3);
    private final Command refresh = new Command("Actualizar", Command.SCREEN, 2);
    private final Command save = new Command("Favorito", Command.SCREEN, 3);
    private final Command route = new Command("Ruta", Command.SCREEN, 4);
    private final Command remove = new Command("Quitar", Command.SCREEN, 5);
    private final Command ok = new Command("OK", Command.OK, 1);

    protected void startApp() {
        try {
            display = Display.getDisplay(this);
            baseUrl = Store.read(SETTINGS_KEY);
            if (baseUrl == null || baseUrl.length() == 0) baseUrl = DEFAULT_URL;

            buildMenu();

            String cached = Store.read(CATALOG_KEY);
            if (cached != null) catalog.parse(cached);

            // La interfaz aparece al instante; si no hay datos guardados se ve
            // un aviso claro mientras se descargan en segundo plano.
            if (catalog.loaded) {
                setMenuTitle("Coruña Bus");
                display.setCurrent(menu);
            } else {
                display.setCurrent(loadingScreen);
                downloadCatalog(false);
            }
        } catch (Throwable t) {
            crashed(t);
        }
    }

    /** Cambios de pantalla desde un hilo de fondo: pasan por el hilo de UI. */
    private void ui(Runnable action) {
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

        loadingScreen = new List("Coruña Bus - cargando datos...", List.IMPLICIT);
        loadingScreen.append("Descargando paradas y líneas...", null);
        loadingScreen.append("Espera unos segundos.", null);
        loadingScreen.addCommand(exit);
        loadingScreen.setCommandListener(this);
    }

    private void showMenu() {
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
        screen = new Form("Espera");
        screen.append(message);
        display.setCurrent(screen);
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
                    body.append(minutes.replace(',', ' '));
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
        resultsList = new List("Resultados", List.IMPLICIT);
        for (int i = 0; i < found.size(); i++) {
            Catalog.Stop stop = (Catalog.Stop) found.elementAt(i);
            resultsList.append(stop.name + " #" + stop.id, null);
        }
        resultsList.addCommand(ok);
        resultsList.addCommand(back);
        resultsList.setSelectCommand(ok);
        resultsList.setCommandListener(this);
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
        favoritesList = new List("Favoritos", List.IMPLICIT);
        for (int i = 0; i < entries.size(); i++) {
            String[] entry = (String[]) entries.elementAt(i);
            favoritesList.append(entry[1], null);
        }
        favoritesList.addCommand(ok);
        favoritesList.addCommand(remove);
        favoritesList.addCommand(back);
        favoritesList.setSelectCommand(ok);
        favoritesList.setCommandListener(this);
        display.setCurrent(favoritesList);
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

        stopPicker = new List("Paradas cercanas", List.IMPLICIT);
        int shown = 0;
        for (int i = 0; i < best.size() && shown < 8; i++) {
            Object[] entry = (Object[]) best.elementAt(i);
            Catalog.Stop stop = (Catalog.Stop) entry[0];
            int meters = ((Integer) entry[1]).intValue();
            if (meters > 1500) break;
            stopPicker.append(stop.name + " (" + meters + " m)", null);
            shown++;
        }
        stopPicker.addCommand(ok);
        stopPicker.addCommand(back);
        stopPicker.setSelectCommand(ok);
        stopPicker.setCommandListener(this);
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
        stopPicker = new List(planMode == 2 ? "Destino" : "Origen", List.IMPLICIT);
        for (int i = 0; i < entries.size(); i++) {
            stopPicker.append(((String[]) entries.elementAt(i))[1], null);
        }
        stopPicker.append("Buscar parada...", null);
        stopPicker.addCommand(ok);
        stopPicker.addCommand(back);
        stopPicker.setSelectCommand(ok);
        stopPicker.setCommandListener(this);
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

    /** Pregunta la hora límite de llegada; vacío = salir ahora. */
    private void askTime(final int origin, final int destination) {
        pendingOrigin = origin;
        pendingDest = destination;
        timeBox = new TextBox("Llegar antes de las (HH:MM)", "", 5, TextField.ANY);
        timeBox.addCommand(ok);
        timeBox.addCommand(back);
        timeBox.setCommandListener(this);
        display.setCurrent(timeBox);
    }

    private void loadPlan(final int origin, final int destination, final String time) {
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
        StringBuffer body = new StringBuffer();
        String originName = "";
        String destName = "";
        boolean any = false;

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
                if (f.length < 2) continue;
                if ("D".equals(f[1]) && f.length >= 8) {
                    body.append(f[0]).append(") Línea ").append(f[2]).append(" directo\n");
                    body.append("   ").append(f[5]).append(" paradas, ~").append(f[6]).append(" min\n");
                    body.append("   Sube en ").append(nameOf(f[3])).append('\n');
                    body.append("   Baja en ").append(nameOf(f[4])).append('\n');
                    appendTiming(body, f, 8);
                    body.append('\n');
                    any = true;
                } else if ("T".equals(f[1]) && f.length >= 12) {
                    body.append(f[0]).append(") Línea ").append(f[2]).append(" + línea ").append(f[8]).append('\n');
                    body.append("   ").append(f[5]).append(" paradas, ~").append(f[6]).append(" min\n");
                    body.append("   Sube en ").append(nameOf(f[3])).append('\n');
                    body.append("   Cambia en ").append(nameOf(f[4]));
                    int walk = Catalog.parseInt(f[7], 0);
                    if (walk > 0) body.append(" (").append(walk).append(" m andando)");
                    body.append('\n');
                    body.append("   Baja en ").append(nameOf(f[10])).append('\n');
                    appendTiming(body, f, 13);
                    body.append('\n');
                    any = true;
                }
            } else if (line.startsWith("ERR")) {
                body.append("No se pudo calcular la ruta.\n");
                if (line.length() > 4) body.append(line.substring(4)).append('\n');
                any = true;
            }
        }

        if (!any) body.append("No hay ruta directa ni con un transbordo.");

        screen = new Form(originName.length() > 0 ? originName + " -> " + destName : "Ruta");
        planBody = new StringItem("", body.toString());
        screen.append(planBody);
        screen.addCommand(back);
        screen.setCommandListener(this);
        display.setCurrent(screen);
    }

    /** Espera real en origen y hora estimada de llegada (campos del servidor). */
    private void appendTiming(StringBuffer body, String[] f, int index) {
        String wait = index < f.length ? f[index] : "";
        String arrive = index + 1 < f.length ? f[index + 1] : "";
        String late = index + 2 < f.length ? f[index + 2] : "";
        if (wait.length() > 0) {
            body.append("   Bus en ").append(wait).append(" min\n");
        }
        if (arrive.length() > 0) {
            body.append("   Llega a las ").append(arrive);
            if ("1".equals(late)) body.append(" (no llega a tiempo)");
            body.append('\n');
        }
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
        if (d == timeBox) {
            if (c == back) {
                showMenu();
                return;
            }
            String time = timeBox.getString().trim();
            if (time.length() > 0 && !validTime(time)) {
                Alert alert = new Alert("Hora");
                alert.setString("Escribe HH:MM, por ejemplo 14:30. Vacío = salir ahora.");
                alert.setTimeout(4000);
                display.setCurrent(alert, timeBox);
                return;
            }
            loadPlan(pendingOrigin, pendingDest, time);
            return;
        }

        if (d == loadingScreen) {
            if (c == exit) notifyDestroyed();
            return;
        }

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

        if (d == resultsList) {
            if (c == back) {
                if (planMode != 0) chooseStop();
                else display.setCurrent(searchBox);
                return;
            }
            int id = resultId(resultsList.getSelectedIndex());
            if (id > 0) selectStop(id);
            return;
        }

        if (d == favoritesList) {
            if (c == back) {
                showMenu();
                return;
            }
            Vector entries = favoriteEntries();
            int index = favoritesList.getSelectedIndex();
            if (c == remove) {
                removeFavorite(index);
                return;
            }
            if (index >= 0 && index < entries.size()) {
                selectStop(Catalog.parseInt(((String[]) entries.elementAt(index))[0], 0));
            }
            return;
        }

        if (d == stopPicker) {
            if (c == back) {
                planMode = 0;
                showMenu();
                return;
            }
            int index = stopPicker.getSelectedIndex();
            if (planMode != 0) {
                if (index < pickerIds.length) {
                    selectStop(pickerIds[index]);
                } else {
                    openSearch();
                }
                return;
            }
            if (index >= 0 && index < nearbyIds.length) openStop(nearbyIds[index]);
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
            } else {
                showMenu();
            }
        }
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

    private static boolean validTime(String value) {
        int colon = value.indexOf(':');
        if (colon < 1 || colon >= value.length() - 1 || value.length() - colon > 3) return false;
        int hours = Catalog.parseInt(value.substring(0, colon), -1);
        int minutes = Catalog.parseInt(value.substring(colon + 1), -1);
        return hours >= 0 && hours <= 23 && minutes >= 0 && minutes <= 59;
    }

    private static String clean(String value) {
        return value.replace('|', '/').replace('\n', ' ');
    }
}
