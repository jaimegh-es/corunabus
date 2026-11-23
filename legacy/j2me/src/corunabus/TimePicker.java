package corunabus;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * Selector de hora propio: dos ruedas (HH y MM) en vez de un campo de texto
 * libre. Arriba/abajo cambia el número, izquierda/derecha cambia de rueda, OK
 * acepta y la tecla derecha ("Ahora") planifica sin hora límite.
 */
public class TimePicker extends Canvas {

    public interface Listener {
        /** hhmm "HH:MM", o null cuando se elige "Ahora". */
        void timePicked(String hhmm);

        void timeCancelled();
    }

    private int hour;
    private int minute;
    private int field = 0; // 0 = horas, 1 = minutos
    private final Listener listener;

    public TimePicker(int hour, int minute, Listener listener) {
        this.hour = hour;
        this.minute = minute;
        this.listener = listener;
        setTitle("Hora de llegada");
    }

    private Font small() {
        return Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    }

    private Font big() {
        return Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_LARGE);
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        Font small = small();
        Font big = big();
        int titleH = small.getHeight() + 8;
        int barH = small.getHeight() + 6;

        g.setColor(0xFFFFFF);
        g.fillRect(0, 0, w, h);

        g.setColor(0x1F3B5B);
        g.fillRect(0, 0, w, titleH);
        g.setColor(0xFFFFFF);
        g.setFont(small);
        g.drawString("Llegar antes de las", 4, (titleH - small.getHeight()) / 2,
                Graphics.TOP | Graphics.LEFT);

        String hh = pad2(hour);
        String mm = pad2(minute);
        int colonW = big.stringWidth(":");
        int hhW = big.stringWidth(hh);
        int mmW = big.stringWidth(mm);
        int total = hhW + colonW + mmW;
        int x = (w - total) / 2;
        int y = titleH + (h - titleH - barH - big.getHeight()) / 2;

        // Rueda activa resaltada.
        g.setColor(0x1F3B5B);
        if (field == 0) g.fillRect(x - 4, y - 4, hhW + 8, big.getHeight() + 8);
        else g.fillRect(x + hhW + colonW - 4, y - 4, mmW + 8, big.getHeight() + 8);

        g.setColor(field == 0 ? 0xFFFFFF : 0x000000);
        g.setFont(big);
        g.drawString(hh, x, y, Graphics.TOP | Graphics.LEFT);
        g.setColor(0x000000);
        g.drawString(":", x + hhW, y, Graphics.TOP | Graphics.LEFT);
        g.setColor(field == 1 ? 0xFFFFFF : 0x000000);
        g.drawString(mm, x + hhW + colonW, y, Graphics.TOP | Graphics.LEFT);

        g.setColor(0x555555);
        g.setFont(small);
        g.drawString("Arriba/abajo cambia", 4, y + big.getHeight() + 14,
                Graphics.TOP | Graphics.LEFT);
        g.drawString("Izq./dcha. horas-minutos", 4, y + big.getHeight() + 14 + small.getHeight(),
                Graphics.TOP | Graphics.LEFT);

        int by = h - barH;
        g.setColor(0xE6E6E6);
        g.fillRect(0, by, w, barH);
        g.setColor(0x444444);
        g.drawString("Atrás", 4, by + 3, Graphics.TOP | Graphics.LEFT);
        g.drawString("Ahora", w - 4, by + 3, Graphics.TOP | Graphics.RIGHT);
    }

    private static String pad2(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    protected void keyPressed(int keyCode) {
        int action = 0;
        try {
            action = getGameAction(keyCode);
        } catch (Throwable ignored) {
        }

        if (action == Canvas.UP || keyCode == KEY_NUM2) {
            change(1);
        } else if (action == Canvas.DOWN || keyCode == KEY_NUM8) {
            change(-1);
        } else if (action == Canvas.LEFT || keyCode == KEY_NUM4) {
            field = 0;
            repaint();
        } else if (action == Canvas.RIGHT || keyCode == KEY_NUM6) {
            field = 1;
            repaint();
        } else if (action == Canvas.FIRE || keyCode == KEY_NUM5) {
            listener.timePicked(pad2(hour) + ":" + pad2(minute));
        } else if (keyCode == -7 || keyCode == KEY_NUM9) {
            listener.timePicked(null);
        } else if (keyCode == -6 || keyCode == KEY_NUM0) {
            listener.timeCancelled();
        }
    }

    private void change(int delta) {
        if (field == 0) {
            hour += delta;
            if (hour > 23) hour = 0;
            if (hour < 0) hour = 23;
        } else {
            minute += delta;
            if (minute > 59) minute = 0;
            if (minute < 0) minute = 59;
        }
        repaint();
    }
}
