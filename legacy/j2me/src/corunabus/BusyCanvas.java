package corunabus;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * Pantalla de espera animada: una rueda girando con el mensaje debajo.
 *
 * Sustituye al Form estático de antes, para que se vea que la app está
 * trabajando mientras el servidor responde.
 */
public class BusyCanvas extends Canvas implements Runnable {

    private final String message;
    private boolean visible = false;
    private Thread worker;
    private int angle = 0;

    public BusyCanvas(String title, String message) {
        setTitle(title);
        this.message = message;
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();

        g.setColor(0xFFFFFF);
        g.fillRect(0, 0, w, h);

        int cx = w / 2;
        int cy = h / 2 - 8;
        int r = 15;

        // Pista gris y sector rojo girando: una rueda sencilla y barata de pintar.
        g.setColor(0xEEEEEE);
        g.fillArc(cx - r, cy - r, 2 * r, 2 * r, 0, 360);
        g.setColor(0xD32F2F);
        g.fillArc(cx - r, cy - r, 2 * r, 2 * r, angle, 100);
        g.setColor(0xFFFFFF);
        g.fillArc(cx - r / 2, cy - r / 2, r, r, 0, 360);

        Font font = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
        g.setFont(font);
        g.setColor(0x333333);
        g.drawString(message, cx, cy + r + 14, Graphics.TOP | Graphics.HCENTER);
    }

    protected void showNotify() {
        visible = true;
        if (worker == null) {
            worker = new Thread(this);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
    }

    protected void hideNotify() {
        visible = false;
    }

    public void run() {
        while (true) {
            try {
                if (visible) {
                    angle = (angle + 30) % 360;
                    repaint();
                    Thread.sleep(90);
                } else {
                    Thread.sleep(250);
                }
            } catch (Throwable t) {
                return;
            }
        }
    }
}
