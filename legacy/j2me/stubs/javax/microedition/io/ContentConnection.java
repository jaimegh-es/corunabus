package javax.microedition.io;

public interface ContentConnection extends StreamConnection {
    String getType();
    long getLength();
    String getEncoding();
}
