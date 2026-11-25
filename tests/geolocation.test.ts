import { afterEach, describe, expect, it, vi } from 'vitest';
import { getPosition } from '../src/utils/geolocation';

type MockStep = { success: boolean; code?: number };
type MockGeolocation = {
  calls: PositionOptions[];
  getCurrentPosition: ReturnType<typeof vi.fn>;
};

function makeMockGeolocation(script: MockStep[]): MockGeolocation {
  const calls: PositionOptions[] = [];
  const getCurrentPosition = vi.fn((_success: PositionCallback, error: PositionErrorCallback, options?: PositionOptions) => {
    calls.push(options ?? {});
    const step = script.shift();
    if (step?.success) {
      _success({
        coords: {
          latitude: 43.3623,
          longitude: -8.4115,
          accuracy: 10,
          altitude: null,
          altitudeAccuracy: null,
          heading: null,
          speed: null,
        },
        timestamp: Date.now(),
      } as GeolocationPosition);
    } else {
      const err = Object.assign(new Error('mock geolocation error'), { code: step?.code ?? 2 }) as unknown as GeolocationPositionError;
      error(err);
    }
  });
  return { calls, getCurrentPosition };
}

function installGeolocation(script: MockStep[]) {
  const mock = makeMockGeolocation(script);
  Object.defineProperty(globalThis.navigator, 'geolocation', {
    value: mock,
    configurable: true,
    writable: true,
  });
  return mock;
}

function removeGeolocation() {
  Object.defineProperty(globalThis.navigator, 'geolocation', {
    value: undefined,
    configurable: true,
    writable: true,
  });
}

/** Firefox/Chrome answer query({ name: 'geolocation' }) with this shape. */
function installPermission(state: 'granted' | 'denied' | 'prompt') {
  Object.defineProperty(globalThis.navigator, 'permissions', {
    value: { query: vi.fn(async () => ({ state })) },
    configurable: true,
    writable: true,
  });
}

function removePermission() {
  Object.defineProperty(globalThis.navigator, 'permissions', {
    value: undefined,
    configurable: true,
    writable: true,
  });
}

afterEach(() => {
  removeGeolocation();
  removePermission();
  vi.restoreAllMocks();
});

describe('getPosition()', () => {
  it('returns the position when the first (low-accuracy) call succeeds', async () => {
    const mock = installGeolocation([{ success: true }]);

    const result = await getPosition();

    expect(result).toEqual({ lat: 43.3623, lon: -8.4115 });
    expect(mock.calls).toHaveLength(1);
    expect(mock.calls[0].enableHighAccuracy).toBe(false);
  });

  it('retries once with high accuracy when the first call fails and permission is granted', async () => {
    installPermission('granted');
    const mock = installGeolocation([
      { success: false, code: 3 }, // TIMEOUT
      { success: true },
    ]);

    const result = await getPosition();

    expect(result).toEqual({ lat: 43.3623, lon: -8.4115 });
    expect(mock.calls).toHaveLength(2);
    expect(mock.calls[0].enableHighAccuracy).toBe(false);
    expect(mock.calls[1].enableHighAccuracy).toBe(true);
  });

  it('returns null when both attempts fail', async () => {
    installPermission('granted');
    const mock = installGeolocation([
      { success: false, code: 3 },
      { success: false, code: 2 }, // POSITION_UNAVAILABLE
    ]);

    const result = await getPosition();

    expect(result).toBeNull();
    expect(mock.calls).toHaveLength(2);
  });

  it('does not retry when the user denied permission (single call, null)', async () => {
    const mock = installGeolocation([{ success: false, code: 1 }]); // PERMISSION_DENIED

    const result = await getPosition();

    expect(result).toBeNull();
    expect(mock.calls).toHaveLength(1);
  });

  it('does not even call geolocation when the permission is denied', async () => {
    installPermission('denied');
    const mock = installGeolocation([{ success: true }]);

    const result = await getPosition();

    expect(result).toBeNull();
    expect(mock.calls).toHaveLength(0);
  });

  it('does not retry while the permission is still pending a prompt', async () => {
    installPermission('prompt');
    const mock = installGeolocation([
      { success: false, code: 3 }, // TIMEOUT: the dialog was not answered
      { success: true },
    ]);

    const result = await getPosition();

    expect(result).toBeNull();
    expect(mock.calls).toHaveLength(1);
  });

  it('never prompts for a background read when the permission is pending', async () => {
    installPermission('prompt');
    const mock = installGeolocation([{ success: true }]);

    const result = await getPosition({ background: true });

    expect(result).toBeNull();
    expect(mock.calls).toHaveLength(0);
  });

  it('never prompts for a background read when the permissions API is unavailable', async () => {
    const mock = installGeolocation([{ success: true }]);

    const result = await getPosition({ background: true });

    expect(result).toBeNull();
    expect(mock.calls).toHaveLength(0);
  });

  it('reads a background position silently when the permission is granted', async () => {
    installPermission('granted');
    const mock = installGeolocation([{ success: true }]);

    const result = await getPosition({ background: true });

    expect(result).toEqual({ lat: 43.3623, lon: -8.4115 });
    expect(mock.calls).toHaveLength(1);
  });

  it('asks once on an explicit request even when the permission is pending', async () => {
    installPermission('prompt');
    const mock = installGeolocation([{ success: true }]);

    const result = await getPosition();

    expect(result).toEqual({ lat: 43.3623, lon: -8.4115 });
    expect(mock.calls).toHaveLength(1);
  });

  it('returns null without calling anything when geolocation is unavailable', async () => {
    removeGeolocation();

    const result = await getPosition();

    expect(result).toBeNull();
    expect(globalThis.navigator.geolocation).toBeUndefined();
  });

  it('deduplicates concurrent in-flight calls to getPosition()', async () => {
    let resolvePos: any;
    const calls: PositionOptions[] = [];
    const mock = {
      calls,
      getCurrentPosition: vi.fn((success) => {
        calls.push({});
        resolvePos = () => success({
          coords: { latitude: 43.3623, longitude: -8.4115, accuracy: 10 },
          timestamp: Date.now(),
        } as any);
      }),
    };
    Object.defineProperty(globalThis.navigator, 'geolocation', {
      value: mock,
      configurable: true,
      writable: true,
    });

    const p1 = getPosition();
    const p2 = getPosition();

    // The permission check resolves before getCurrentPosition is reached.
    await new Promise((resolve) => setTimeout(resolve, 0));
    resolvePos();
    const [res1, res2] = await Promise.all([p1, p2]);

    expect(res1).toEqual({ lat: 43.3623, lon: -8.4115 });
    expect(res2).toEqual({ lat: 43.3623, lon: -8.4115 });
    expect(mock.getCurrentPosition).toHaveBeenCalledTimes(1);
  });
});