package corunabus;

import java.util.Vector;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * Pantalla de texto con vuelta atrás garantizada: el itinerario de una ruta.
 *
 * Se dibuja a mano en vez de usar un Form porque en el teléfono la tecla
 * blanda de un Form depende del modelo; aquí "Atrás" está siempre en la barra
 * inferior, en la tecla blanda izquierda y en 0, y la acción extra en la derecha.
 */
public class TextView extends Canvas implements CommandListener {

    public interface Listener {
        void textBack();

        void textExtra();
    }

    private final Vector lines = new Vector();
    private final String title;
    private final Listener listener;
    private Command backCommand;
    private Command extraCommand;
    private final String text;
    private boolean wrapped = false;
    private int top = 0;

    public TextView(String title, String text, String extraLabel, Listener listener) {
        this.title = title;
        this.text = text == null ? "" : text;
        this.listener = listener;
        setTitle(title);
        backCommand = new Command("Atrás", Command.BACK, 1);
        addCommand(backCommand);
        if (extraLabel != null) {
            extraCommand = new Command(extraLabel, Command.SCREEN, 2);
            addCommand(extraCommand);
        }
        setCommandListener(this);
    }

    private Font small() {
        return Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    }

    private int titleHeight() {
        return small().getHeight() + 8;
    }

    private int lineHeight() {
        return small().getHeight() + 2;
    }

    private int visibleLines() {
        int rows = (getHeight() - titleHeight()) / lineHeight();
        return rows < 1 ? 1 : rows;
    }

    /** Parte el texto en líneas que quepan en el ancho de la pantalla. */
    private void wrap(int width) {
        Font font = small();
        width -= 8;
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = text.length();
            String paragraph = text.substring(start, end);
            while (font.stringWidth(paragraph) > width && paragraph.length() > 0) {
                int cut = paragraph.length();
                while (cut > 0 && font.stringWidth(paragraph.substring(0, cut)) > width) cut--;
                int space = paragraph.lastIndexOf(' ', cut);
                if (space > 0) cut = space;
                if (cut <= 0) cut = 1;
                lines.addElement(paragraph.substring(0, cut));
                paragraph = paragraph.substring(cut).trim();
            }
            lines.addElement(paragraph);
            start = end + 1;
        }
    }

    protected void paint(Graphics g) {
        Font font = small();
        int w = getWidth();
        int h = getHeight();
        int pad = 4;
        int titleH = titleHeight();
        int lineH = lineHeight();

        if (!wrapped) {
            wrap(w);
            wrapped = true;
        }

        g.setColor(0xFFFFFF);
        g.fillRect(0, 0, w, h);

        g.setColor(0xD32F2F);
        g.fillRect(0, 0, w, titleH);
        g.setColor(0xFFFFFF);
        g.setFont(font);
        String head = title == null ? "" : title;
        while (font.stringWidth(head) > w - 2 * pad && head.length() > 0) {
            head = head.substring(0, head.length() - 1);
        }
        g.drawString(head, pad, (titleH - font.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);

        int rows = visibleLines();
        if (top > lines.size() - rows) top = lines.size() - rows;
        if (top < 0) top = 0;

        g.setColor(0x000000);
        for (int i = 0; i < rows; i++) {
            int index = top + i;
            if (index >= lines.size()) break;
            g.drawString((String) lines.elementAt(index), pad, titleH + i * lineH,
                    Graphics.TOP | Graphics.LEFT);
        }

        if (lines.size() > rows) {
            g.setColor(0xBBBBBB);
            g.fillRect(w - 3, titleH, 3, rows * lineH);
            int thumb = rows * lineH * rows / lines.size();
            g.setColor(0xD32F2F);
            g.fillRect(w - 3, titleH + (rows * lineH - thumb) * top / (lines.size() - rows), 3, thumb);
        }
    }

    protected void keyPressed(int keyCode) {
        int action = 0;
        try {
            action = getGameAction(keyCode);
        } catch (Throwable ignored) {
        }

        if (action == Canvas.UP || keyCode == KEY_NUM2) {
            scroll(-1);
        } else if (action == Canvas.DOWN || keyCode == KEY_NUM8) {
            scroll(1);
        } else if (action == Canvas.LEFT || keyCode == KEY_NUM4) {
            scroll(-visibleLines());
        } else if (action == Canvas.RIGHT || keyCode == KEY_NUM6) {
            scroll(visibleLines());
        } else if (keyCode == -7 || keyCode == KEY_NUM9) {
            if (extraCommand != null) listener.textExtra();
        } else if (keyCode == -6 || keyCode == KEY_NUM0) {
            listener.textBack();
        }
    }

    protected void keyRepeated(int keyCode) {
        keyPressed(keyCode);
    }

    private void scroll(int delta) {
        int rows = visibleLines();
        int max = lines.size() - rows;
        if (max < 0) max = 0;
        top += delta;
        if (top < 0) top = 0;
        if (top > max) top = max;
        repaint();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == extraCommand) listener.textExtra();
        else listener.textBack();
    }
}
