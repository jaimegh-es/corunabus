package javax.microedition.io;

import java.io.IOException;

public class Connector {
    public static final int READ = 1;
    public static final int WRITE = 2;
    public static final int READ_WRITE = 3;

    public static Connection open(String name) throws IOException { return null; }
    public static Connection open(String name, int mode) throws IOException { return null; }
    public static Connection open(String name, int mode, boolean timeouts) throws IOException { return null; }
}
