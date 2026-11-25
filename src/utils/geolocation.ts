import { Capacitor } from '@capacitor/core';
import { Geolocation } from '@capacitor/geolocation';

export interface GeolocationResult {
  lat: number;
  lon: number;
}

export interface GetPositionOptions {
  forceRefresh?: boolean;
  /**
   * Automatic, non user-initiated read (page load, polling, sorting). Such a
   * read must never open the browser permission prompt: Firefox and Firefox
   * Focus grant geolocation for a single visit by default, so an automatic
   * call on every page load or timer asks the user again and again.
   * Una lectura automática (arranque, sondeo, ordenar por cercanía) nunca debe
   * abrir el aviso de permiso: Firefox y Focus conceden la ubicación para una
   * sola visita, así que llamar automáticamente vuelve a preguntar sin parar.
   */
  background?: boolean;
}

const PERMISSION_DENIED = 1;

let inFlightPromise: Promise<GeolocationResult | null> | null = null;
let lastKnownPosition: { result: GeolocationResult; time: number } | null = null;

export function clearGeolocationCache(): void {
  inFlightPromise = null;
  lastKnownPosition = null;
}

export const isNativePlatform = (): boolean => {
  try {
    return Capacitor.isNativePlatform();
  } catch {
    return false;
  }
};

function requestWebPosition(options: PositionOptions): Promise<GeolocationResult> {
  return new Promise((resolve, reject) => {
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      reject(new Error('Geolocation API unavailable'));
      return;
    }
    const timeoutMs = (options.timeout ?? 10000) + 1500;
    const timer = setTimeout(() => {
      reject(new Error('Geolocation timeout'));
    }, timeoutMs);

    navigator.geolocation.getCurrentPosition(
      (pos) => {
        clearTimeout(timer);
        if (pos?.coords && typeof pos.coords.latitude === 'number' && typeof pos.coords.longitude === 'number') {
          resolve({ lat: pos.coords.latitude, lon: pos.coords.longitude });
        } else {
          reject(new Error('Invalid position data'));
        }
      },
      (err) => {
        clearTimeout(timer);
        reject(err);
      },
      options
    );
  });
}

function isPermissionGranted(status: any): boolean {
  if (!status) return false;
  return status.location === 'granted' || status.coarseLocation === 'granted';
}

type WebPermissionState = 'granted' | 'denied' | 'prompt' | 'unknown';

/**
 * Current web permission state, without ever triggering the prompt. Browsers
 * without the Permissions API (older Safari) answer `unknown`.
 */
async function webPermissionState(): Promise<WebPermissionState> {
  try {
    const perms = typeof navigator !== 'undefined' ? (navigator as any).permissions : null;
    if (!perms?.query) return 'unknown';
    const status = await perms.query({ name: 'geolocation' });
    const state = status?.state;
    return state === 'granted' || state === 'denied' || state === 'prompt' ? state : 'unknown';
  } catch {
    return 'unknown';
  }
}

async function getNativePosition(): Promise<GeolocationResult | null> {
  // In the native Android/iOS app the WebView's geolocation usually cannot
  // access the device sensors, so we use the Capacitor Geolocation plugin to
  // read the real device GPS position.
  try {
    const status = await Geolocation.checkPermissions();
    if (!isPermissionGranted(status)) {
      const granted = await Geolocation.requestPermissions();
      if (!isPermissionGranted(granted)) return null;
    }

    // Fast, cached/network fix first: the fused provider can return a recent
    // position almost instantly even indoors, where a full GPS lock times out.
    const low = await nativeGetOnce({ enableHighAccuracy: false, timeout: 3000, maximumAge: 600000 });
    if (low) return low;

    const high = await nativeGetOnce({ enableHighAccuracy: true, timeout: 8000, maximumAge: 0 });
    if (high) return high;

    // Last resort: some devices only deliver fixes while "watching". Start a
    // short watch session and resolve on the first available fix.
    return await nativeWatchOnce(8000);
  } catch {
    return null;
  }
}

function toResult(pos: { coords?: { latitude?: number; longitude?: number } } | null): GeolocationResult | null {
  if (pos?.coords && typeof pos.coords.latitude === 'number' && typeof pos.coords.longitude === 'number') {
    return { lat: pos.coords.latitude, lon: pos.coords.longitude };
  }
  return null;
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('timeout')), ms);
    promise.then(
      (v) => { clearTimeout(timer); resolve(v); },
      (e) => { clearTimeout(timer); reject(e); }
    );
  });
}

async function nativeGetOnce(options: { enableHighAccuracy: boolean; timeout: number; maximumAge: number }): Promise<GeolocationResult | null> {
  try {
    const pos = await withTimeout(Geolocation.getCurrentPosition(options), options.timeout + 2000);
    return toResult(pos as never);
  } catch {
    return null;
  }
}

function nativeWatchOnce(durationMs: number): Promise<GeolocationResult | null> {
  return new Promise((resolve) => {
    let watchId: string | undefined;
    let done = false;
    const finish = (value: GeolocationResult | null) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      if (watchId) Geolocation.clearWatch({ id: watchId }).catch(() => {});
      resolve(value);
    };
    const timer = setTimeout(() => finish(null), durationMs);
    Geolocation.watchPosition({ enableHighAccuracy: false }, (pos) => {
      const result = toResult(pos as never);
      if (result) finish(result);
    }).then((id) => {
      watchId = id;
    }).catch(() => finish(null));
  });
}

async function getWebPosition(background: boolean): Promise<GeolocationResult | null> {
  if (typeof navigator === 'undefined' || !navigator.geolocation) return null;

  const state = await webPermissionState();
  if (state === 'denied') return null;

  // Background reads are silent: with permission in 'prompt' (or unknown, as
  // Firefox reports after a one-off grant expires) calling anyway would show
  // the dialog. On the web they simply return no fix.
  if (background && state !== 'granted') return null;

  try {
    return await requestWebPosition({ enableHighAccuracy: false, timeout: 6000, maximumAge: 60000 });
  } catch (err) {
    const code = (err as GeolocationPositionError | undefined)?.code;
    if (code === PERMISSION_DENIED) return null;
  }

  // Retry at high accuracy only when the permission is already granted. When
  // it is still 'prompt', our timeout usually means the dialog is unanswered,
  // and a second call would open another prompt.
  if (state !== 'granted') return null;

  try {
    return await requestWebPosition({ enableHighAccuracy: true, timeout: 10000, maximumAge: 60000 });
  } catch {
    return null;
  }
}

/**
 * Obtains the user's position as reliably as possible.
 *
 * Native app: reads the device GPS through the Capacitor Geolocation plugin.
 * Supports both precise (fine) and approximate (coarse) location permissions on Android 12+.
 *
 * Web/PWA: fast low-accuracy fix first (network / cached fix up to 60s old)
 * and, on failure (except a denied permission prompt), retries with high accuracy
 * with a cached fix allowance so mobile browsers do not stall on cold satellite lock.
 *
 * Concurrent calls are deduplicated into a single shared promise, and recent fixes
 * are cached in memory for 15s (bypassable with `forceRefresh: true`).
 *
 * Returns `null` when the position cannot be obtained.
 */
export async function getPosition(options?: GetPositionOptions): Promise<GeolocationResult | null> {
  const isTest = typeof process !== 'undefined' && process.env?.NODE_ENV === 'test';
  const ttl = isTest ? 0 : 15000;

  const now = Date.now();
  if (!options?.forceRefresh && ttl > 0 && lastKnownPosition && (now - lastKnownPosition.time < ttl)) {
    return lastKnownPosition.result;
  }

  if (inFlightPromise && !options?.forceRefresh) {
    return inFlightPromise;
  }

  const run = (async () => {
    try {
      let res: GeolocationResult | null = null;
      if (Capacitor.isNativePlatform()) {
        res = await getNativePosition();
      } else {
        res = await getWebPosition(options?.background ?? false);
      }
      if (res) {
        lastKnownPosition = { result: res, time: Date.now() };
      }
      return res;
    } finally {
      inFlightPromise = null;
    }
  })();

  inFlightPromise = run;
  return run;
}