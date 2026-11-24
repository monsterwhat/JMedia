// offset by 1, 0 = 1, to match CSS font-weight values, but dividing by 100 gives the correct index
export const WEIGHT_MAP = [
    'thin',
    'extralight',
    'light',
    'regular', // Normal isn't used
    'medium',
    'semibold',
    'bold',
    'extrabold',
    'black',
    'ultrablack'
];
export async function fetchok(url) {
    const res = await fetch(url);
    if (!res.ok)
        throw new Error(`JASSUB: failed to fetch ${url}: HTTP ${res.status}`);
    return res;
}
export async function fetchtext(url) {
    const res = await fetchok(url);
    return await res.text();
}
export const THREAD_COUNT = self.crossOriginIsolated ? Math.min(Math.max(1, navigator.hardwareConcurrency - 2), 8) : 1;
//# sourceMappingURL=util.js.map