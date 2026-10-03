// Per-version changelog.
// Add an entry (highest first) whenever you ship a new version; users will
// see it once after updating (tracked in localStorage).
//
// Changelog por versión.
// Añade una entrada (la más nueva arriba) cada vez que sacas versión; los
// usuarios la verán una sola vez tras actualizar (se registra en localStorage).

export interface ChangelogEntry {
    version: string;
    title: string;
    items: string[];
}

export const CHANGELOG: ChangelogEntry[] = [
    {
        version: '0.0.12',
        title: '👆 Ahora sí: los favoritos no se abren solos',
        items: [
            'El arreglo anterior no llegaba a funcionar en Android. Al terminar un deslizamiento, el navegador emite unos eventos de ratón "de compatibilidad" después de cancelar el toque, justo en el punto donde levantaste el dedo, y eso reintentaba registrar el toque justo antes del clic. Por eso la lista se abría igualmente.',
            'Ahora se escucha un único tipo de evento de puntero, nunca varios a la vez, así que el clic se contrasta con el gesto que de verdad lo produjo y se descarta si el dedo se había movido.',
        ],
    },
    {
        version: '0.0.11',
        title: '👆 Los favoritos ya no se pulsan solos',
        items: [
            'En la lista de favoritos, un item se abría solo sin haberlo tocado. Pasaba al terminar un scroll o al repintarse la lista mientras tenías el dedo encima: el toque apuntaba a un item y el navegador abría otro.',
            'Ahora un toque solo cuenta si el dedo apenas se movió y quedó sobre el mismo item. El teclado y los lectores de pantalla siguen funcionando igual.',
            'La lista de favoritos ya no se vuelve a pintar si no ha cambiado nada, que era lo que provocaba el salto de items.',
        ],
    },
    {
        version: '0.0.10',
        title: '🚌 El seguimiento ya no se corta antes de tiempo',
        items: [
            'El seguimiento se acababa solo un minuto después del aviso "pulsa el botón de parada", con el autobús todavía a varios minutos. Ahora solo termina cuando el bus está realmente en la parada de destino, o cuando llegas tú, y siempre con confirmación.',
            'El bus ya no se pierde de un salto: un fallo puntual de datos o un aviso de la operadora ya no matan el viaje. Antes un solo sondeo fallido lo cortaba.',
            'Si pasan minutos sin poder leer datos, o el viaje se alarga demasiado, el seguimiento se cierra solo en lugar de quedarse sonando y gastando batería.',
            'Al buscar un bus por número se muestra la línea entera, en los dos sentidos, marcando con un punto verde las paradas que te quedan por delante. Las filas se pueden pulsar sin tener que afinar el dedo.',
            'En la app de Android ya se ven los horarios: salía "Error de conexión" porque las peticiones al servidor de datos no llegaban desde el móvil.',
        ],
    },
    {
        version: '0.0.9',
        title: '🔧 Aviso de bloqueo corregido',
        items: [
            'Corregido el aviso falso de "página bloqueada por tu operadora": al movernos entre pantallas aparecía sin estar bloqueada nada.',
            'Ahora la app reintenta la carga automáticamente y solo muestra el aviso si de verdad el servidor no responde.',
        ],
    },
    {
        version: '0.0.8',
        title: '🎨 Pantalla de carga renovada',
        items: [
            'Nueva pantalla de carga con el logo de Coruña Bus centrado y barra de progreso mientras la app carga (fondo claro y logo con bordes redondeados).',
            'Si la página no carga por un bloqueo de tu operadora, se muestra el aviso de bloqueo con las opciones para desbloquearla.',
            'El seguimiento termina solo: 1 minuto después del aviso "pulsa el botón de parada" se cierra la notificación y se acaba el viaje, sin necesidad de abrir la app.',
        ],
    },
    {
        version: '0.0.6',
        title: '🚏 Seguimiento más inteligente',
        items: [
            'Avisos con antelación real desde tu ubicación de salida: si guardas el tiempo que tardas andando a la parada (casa, trabajo...), la app te avisa con ese margen para que salgas a tiempo.',
            'El seguimiento finaliza solo al llegar al destino: ya no se queda buscando el bus sin fin al llegar a la parada.',
            'Modo "solo aviso" (sin destino obligatorio): el seguimiento acaba cuando el bus llega a tu parada de salida.',
            'Más ahorro de batería: el seguimiento en segundo plano se detiene automáticamente al llegar.',
            'Animaciones y transiciones renovadas en toda la interfaz: entradas suaves, skeletons de carga y modales más fluidos.',
        ],
    },
    {
        version: '0.0.5',
        title: '🔧 Corrección de arranque',
        items: [
            'Solucionado un fallo que hacía que la app cargara desde "localhost" dentro del móvil: ahora apunta al servidor real de producción y arranca mostrando los datos correctamente.',
        ],
    },
    {
        version: '0.0.4',
        title: '🎉 Nueva página de bienvenida',
        items: [
            'Landing renovada y separada de la app: entra desde ella o navega directo con /welcome.',
            'Vista previa en directo de la app desde la propia landing.',
            'Tres formas de usarla claramente explicadas: Web, PWA y APK (la recomendada).',
            'Mejoras de geolocalización: timeout en web, permiso de ubicación aproximada en Android 12+ y detección más rápida del bus.',
        ],
    },
    {
        version: '0.0.3',
        title: '🛡️ Detección de bloqueos y mejoras',
        items: [
            'Detección inteligente de bloqueos de operadoras a Cloudflare durante partidos.',
            'Información clara y transparente con enlace a comprobador en tiempo real (hayahora.futbol).',
            'Acceso rápido a Proton VPN gratuita para saltar bloqueos de operadoras.',
            'Enlace directo a GitHub para auditar el código abierto.',
        ],
    },
    {
        version: '0.0.2',
        title: '🚀 Novedades de esta versión',
        items: [
            'App Android con avisos de llegada en segundo plano (elige los minutos de antelación).',
            'Notificaciones nativas en la bandeja del sistema.',
            'Nombres personalizados para tus paradas favoritas.',
            'Actualización de la app desde Ajustes, con changelog como este.',
            'Búsqueda de bus por número mucho más rápida.',
            'Avisos no bloqueantes: el aviso sonora al instante.',
        ],
    },
];

export function getChangelogFor(version: string): ChangelogEntry | null {
    return CHANGELOG.find(e => e.version === version) ?? null;
}

// Semantic version helpers, tolerant with prefixes/suffixes (v1.2, 1.2.3,
// "1.0.28", ...). The changelog must work even when the native build numbers
// the APK differently from the changelog entries (e.g. APK "1.0.28" vs
// entries "0.0.3"), so exact string equality is not enough.
// Comparación semántica tolerante: el changelog funciona aunque el APK lleve
// una numeración distinta a la de las entradas (p. ej. APK "1.0.28" frente a
// entradas "0.0.3"), así que la igualdad exacta no basta.
export function parseVersion(v: string): number[] {
    return String(v)
        .trim()
        .replace(/^v/i, '')
        .split('.')
        .map(s => parseInt(s, 10))
        .filter(n => !isNaN(n));
}

export function compareVersions(a: string, b: string): number {
    const av = parseVersion(a);
    const bv = parseVersion(b);
    const len = Math.max(av.length, bv.length);
    for (let i = 0; i < len; i++) {
        const x = av[i] || 0;
        const y = bv[i] || 0;
        if (x !== y) return x - y;
    }
    return 0;
}

// Show-once bookkeeping: remembers the last version whose changelog the user saw
// Control de "mostrar una vez": recuerda la última versión cuyo changelog vio el usuario
const SEEN_KEY = 'buscoruna_changelog_seen';

export function hasSeenChangelog(version: string): boolean {
    return localStorage.getItem(SEEN_KEY) === version;
}

export function markChangelogSeen(version: string) {
    localStorage.setItem(SEEN_KEY, version);
}

// Entry to show: the newest changelog entry that is <= the running app version
// and newer than the last one the user saw. With nothing seen yet (fresh
// install, or APK version that never matched before) it shows the newest
// available entry.
// Entrada a mostrar: la más reciente que sea <= a la versión actual de la app y
// más nueva que la última vista. Sin registro previo, muestra la más reciente.
export function getPendingChangelog(currentVersion: string): ChangelogEntry | null {
    const sorted = [...CHANGELOG].sort((a, b) => compareVersions(b.version, a.version));
    if (sorted.length === 0) return null;
    const lastSeen = localStorage.getItem(SEEN_KEY);
    if (!lastSeen) return sorted[0];
    for (const e of sorted) {
        if (compareVersions(e.version, currentVersion) <= 0 && compareVersions(e.version, lastSeen) > 0) {
            return e;
        }
    }
    return null;
}
