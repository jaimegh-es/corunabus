package corunabus;

import javax.microedition.location.Criteria;
import javax.microedition.location.Location;
import javax.microedition.location.LocationProvider;
import javax.microedition.location.QualifiedCoordinates;

/**
 * GPS del E71 (JSR-179). Va en su propia clase a propósito: si el teléfono no
 * trae la API, el resto de la app sigue funcionando al capturar el error en la
 * pantalla de paradas cercanas.
 */
public class Gps {

    private Gps() {
    }

    /** Devuelve {lat*1e5, lng*1e5} o null si no hay posición. */
    public static int[] locate() {
        try {
            Criteria criteria = new Criteria();
            criteria.setCostAllowed(false);
            criteria.setPreferredPowerConsumption(Criteria.POWER_USAGE_MEDIUM);
            LocationProvider provider = LocationProvider.getInstance(criteria);
            if (provider == null) return null;
            Location location = provider.getLocation(120);
            if (location == null) return null;
            QualifiedCoordinates coords = location.getQualifiedCoordinates();
            if (coords == null) return null;
            return new int[] {
                (int) Math.round(coords.getLatitude() * 100000),
                (int) Math.round(coords.getLongitude() * 100000)
            };
        } catch (Throwable t) {
            return null;
        }
    }
}
