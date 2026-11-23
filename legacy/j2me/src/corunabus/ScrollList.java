package corunabus;

import java.util.Vector;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * Lista propia para lo que el List de MIDP no hace: mover el texto de la fila
 * seleccionada cuando no cabe (rótulo), con una pausa antes y después.
 *
 * El List nativo recorta los nombres largos de parada y no avisa al cambiar de
 * fila, así que aquí se dibuja todo a mano. Las teclas son las del teléfono:
 * arriba/abajo (o 2/8), OK (o 5), tecla blanda izquierda (o 0) para volver y
 * derecha (o -7) para la acción extra.
 *
 * Además de las teclas se registran comandos de verdad, para que la tecla
 * blanda del teléfono (y la de la derecha con la acción extra) siempre
 * funcionen aunque el modelo mapee otras teclas.
 */
public class ScrollList extends Canvas implements Runnable, CommandListener {

    public interface Listener {
        void listSelected(ScrollList list, int index);

        void listBack(ScrollList list);

        void listExtra(ScrollList list, int index);
    }

    private static final int SPEED = 40;   // píxeles por segundo
    private static final int HOLD = 900;   // pausa antes de empezar a moverse
    private static final int TAIL = 1300;  // pausa al llegar al final

    private final Vector items = new Vector();
    private String title;
    private int selected = 0;
    private int top = 0;
    private long cycleStart = 0;
    private Listener listener;
    private String backLabel = "Atrás";
    private String extraLabel = null;
    private boolean visible = false;
    private Thread worker;
    private Command backCommand = new Command("Atrás", Command.BACK, 1);
    private Command extraCommand;

    public ScrollList(String title) {
        this.title = title;
        setTitle(title);
        addCommand(backCommand);
        setCommandListener(this);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setBackLabel(String label) {
        this.backLabel = label;
        try {
            removeCommand(backCommand);
        } catch (Throwable ignored) {
        }
        backCommand = new Command(label, Command.BACK, 1);
        addCommand(backCommand);
    }

    public void setExtraLabel(String label) {
        if (extraCommand != null) {
            removeCommand(extraCommand);
            extraCommand = null;
        }
        this.extraLabel = label;
        if (label != null) {
            extraCommand = new Command(label, Command.SCREEN, 2);
            addCommand(extraCommand);
        }
    }

    public void commandAction(Command c, Displayable d) {
        if (c == extraCommand) extra();
        else back();
    }

    public void add(String text) {
        items.addElement(text);
    }

    public void clear() {
        items.removeAllElements();
        selected = 0;
        top = 0;
        cycleStart = System.currentTimeMillis();
    }

    public int size() {
        return items.size();
    }

    public int getSelectedIndex() {
        return selected;
    }

    public void selectFirst() {
        selected = 0;
        top = 0;
        cycleStart = System.currentTimeMillis();
    }

    // ------------------------------------------------------------- pintado

    private Font small() {
        return Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    }

    private Font bold() {
        return Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
    }

    private int rowHeight() {
        return small().getHeight() + 8;
    }

    private int titleHeight() {
        return small().getHeight() + 8;
    }

    private int visibleRows() {
        int rows = (getHeight() - titleHeight()) / rowHeight();
        return rows < 1 ? 1 : rows;
    }

    protected void paint(Graphics g) {
        Font small = small();
        int w = getWidth();
        int h = getHeight();
        int pad = 4;
        int titleH = titleHeight();
        int rowH = rowHeight();
        int rows = visibleRows();

        g.setColor(0xFFFFFF);
        g.fillRect(0, 0, w, h);

        // Barra de título con la posición dentro de la lista.
        g.setColor(0xD32F2F);
        g.fillRect(0, 0, w, titleH);
        g.setColor(0xFFFFFF);
        g.setFont(small);
        String count = items.size() > 0 ? (selected + 1) + "/" + items.size() : "";
        int countW = count.length() > 0 ? small.stringWidth(count) : 0;
        String head = title == null ? "" : title;
        g.drawString(clip(head, w - 2 * pad - countW - 4), pad,
                (titleH - small.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
        if (countW > 0) {
            g.drawString(count, w - pad, (titleH - small.getHeight()) / 2,
                    Graphics.TOP | Graphics.RIGHT);
        }

        if (selected < top) top = selected;
        if (selected >= top + rows) top = selected - rows + 1;
        if (top > items.size() - rows) top = items.size() - rows;
        if (top < 0) top = 0;

        long now = System.currentTimeMillis();
        int avail = w - 2 * pad;
        for (int i = 0; i < rows; i++) {
            int index = top + i;
            if (index >= items.size()) break;
            String text = (String) items.elementAt(index);
            int y = titleH + i * rowH;
            boolean sel = index == selected;
            if (sel) {
                g.setColor(0xD32F2F);
                g.fillRect(0, y, w, rowH);
            }
            Font font = sel ? bold() : small;
            g.setColor(sel ? 0xFFFFFF : 0x000000);
            g.setFont(font);
            int offset = 0;
            if (sel) {
                int width = font.stringWidth(text);
                if (width > avail) offset = marqueeOffset(width - avail, now);
            }
            g.setClip(pad, y + 2, avail, rowH - 4);
            g.drawString(text, pad - offset, y + (rowH - font.getHeight()) / 2,
                    Graphics.TOP | Graphics.LEFT);
            g.setClip(0, 0, w, h);
        }

        // Barra de indicador de desplazamiento a la derecha.
        if (items.size() > rows) {
            int trackH = rows * rowH;
            int thumbH = trackH * rows / items.size();
            int thumbY = titleH + (trackH - thumbH) * top / (items.size() - rows);
            g.setColor(0xBBBBBB);
            g.fillRect(w - 3, titleH, 3, trackH);
            g.setColor(0xD32F2F);
            g.fillRect(w - 3, thumbY, 3, thumbH);
        }
    }

    /** Desplazamiento del rótulo: pausa, recorrido, pausa y vuelta a empezar. */
    private int marqueeOffset(int overflow, long now) {
        long elapsed = now - cycleStart;
        int travel = overflow * 1000 / SPEED;
        if (travel < 400) travel = 400;
        if (elapsed < HOLD) return 0;
        if (elapsed < HOLD + travel) return (int) ((elapsed - HOLD) * SPEED / 1000);
        if (elapsed < HOLD + travel + TAIL) return overflow;
        cycleStart = now;
        return 0;
    }

    private String clip(String text, int width) {
        Font font = small();
        if (font.stringWidth(text) <= width) return text;
        int end = text.length();
        while (end > 0 && font.stringWidth(text.substring(0, end) + "...") > width) end--;
        return text.substring(0, end) + "...";
    }

    // -------------------------------------------------------------- teclas

    protected void keyPressed(int keyCode) {
        handleKey(keyCode);
    }

    protected void keyRepeated(int keyCode) {
        handleKey(keyCode);
    }

    private void handleKey(int keyCode) {
        int action = 0;
        try {
            action = getGameAction(keyCode);
        } catch (Throwable ignored) {
        }

        if (action == Canvas.UP || keyCode == KEY_NUM2) {
            move(-1);
        } else if (action == Canvas.DOWN || keyCode == KEY_NUM8) {
            move(1);
        } else if (action == Canvas.LEFT || keyCode == KEY_NUM4) {
            move(-visibleRows());
        } else if (action == Canvas.RIGHT || keyCode == KEY_NUM6) {
            move(visibleRows());
        } else if (action == Canvas.FIRE || keyCode == KEY_NUM5) {
            choose();
        } else if (keyCode == -7) {
            if (extraLabel != null) extra();
            else back();
        } else if (keyCode == -6 || keyCode == KEY_NUM0) {
            back();
        }
    }

    private void move(int delta) {
        if (items.size() == 0) return;
        int next = selected + delta;
        if (next < 0) next = 0;
        if (next >= items.size()) next = items.size() - 1;
        if (next != selected) {
            selected = next;
            cycleStart = System.currentTimeMillis();
            repaint();
        }
    }

    private void choose() {
        if (listener != null && selected >= 0 && selected < items.size()) {
            listener.listSelected(this, selected);
        }
    }

    private void back() {
        if (listener != null) listener.listBack(this);
    }

    private void extra() {
        if (listener != null) listener.listExtra(this, selected);
    }

    // -------------------------------------------------------------- rótulo

    protected void showNotify() {
        visible = true;
        cycleStart = System.currentTimeMillis();
        if (worker == null) {
            worker = new Thread(this);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
    }

    protected void hideNotify() {
        visible = false;
    }

    private boolean needsMarquee() {
        if (selected < 0 || selected >= items.size()) return false;
        Font font = bold();
        return font.stringWidth((String) items.elementAt(selected)) > getWidth() - 8;
    }

    public void run() {
        while (true) {
            try {
                if (visible && needsMarquee()) {
                    repaint();
                    Thread.sleep(70);
                } else {
                    Thread.sleep(400);
                }
            } catch (Throwable t) {
                return;
            }
        }
    }
}
