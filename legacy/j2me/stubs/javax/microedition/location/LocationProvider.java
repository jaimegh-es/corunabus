package javax.microedition.location;

public abstract class LocationProvider {
    public static LocationProvider getInstance(Criteria criteria) throws LocationException { return null; }
    public abstract Location getLocation(int timeout) throws LocationException, InterruptedException;
}
