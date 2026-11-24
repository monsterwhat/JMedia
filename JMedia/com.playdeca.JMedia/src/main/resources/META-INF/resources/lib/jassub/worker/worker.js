var __rewriteRelativeImportExtension = (this && this.__rewriteRelativeImportExtension) || function (path, preserveJsx) {
    if (typeof path === "string" && /^\.\.?\//.test(path)) {
        return path.replace(/\.(tsx)$|((?:\.d)?)((?:\.[^./]+?)?)\.([cm]?)ts$/i, function (m, tsx, d, ext, cm) {
            return tsx ? preserveJsx ? ".jsx" : ".js" : d && (!ext || !cm) ? m : (d + ext + "." + cm.toLowerCase() + "js");
        });
    }
    return path;
};
import { finalizer } from '../vendor/abslink/src/abslink.js';
import { expose } from '../vendor/abslink/adapters/w3c.js';
import { queryRemoteFonts } from '../vendor/lfa-ponyfill/index.js';
import { COLOR_CONVERSIONS, IDENTITY_MATRIX, LIBASS_YCBCR_MATRIX, WEB_MATRIX_COEFFICIENTS } from "./color-conversions.js";
import { Canvas2DRenderer } from "./renderers/2d-renderer.js";
import { WebGL1Renderer } from "./renderers/webgl1-renderer.js";
import { WebGL2Renderer } from "./renderers/webgl2-renderer.js";
// import { WebGPURenderer } from './renderers/webgpu-renderer.ts'
import { fetchok, fetchtext, THREAD_COUNT, WEIGHT_MAP } from "./util.js";
const constructor = Symbol.for('constructor');
function readImages(view) {
    const images = [];
    for (let i = 0; i < view.length; i += 8) {
        images.push({
            w: view[i],
            h: view[i + 1],
            dst_x: view[i + 2],
            dst_y: view[i + 3],
            // bitmap, color and cache_id are unsigned in C
            bitmap: view[i + 4] >>> 0,
            color: view[i + 5] >>> 0,
            stride: view[i + 6],
            cache_id: view[i + 7] >>> 0
        });
    }
    return images;
}
function renderError(e) {
    if (e instanceof Error)
        return e;
    if (e === 'unwind')
        return new Error('JASSUB: a render thread crashed, the WASM heap probably could not grow');
    if (!e)
        return new Error('JASSUB: rendering aborted, the WASM heap probably could not grow');
    return new Error(`JASSUB: rendering failed: ${JSON.stringify(e)}`);
}
const NO_TRACK = 'JASSUB: no track is loaded';
const WEIGHT_SUFFIX = new RegExp(`^(.+)\\s+(${WEIGHT_MAP.join('|')})$`);
export class ASSRenderer {
    _wasm;
    // undefined: no conversion
    _videoColorSpace;
    _malloc;
    _gpurender;
    debug = false;
    constructor(...args) {
        return this[constructor](...args);
    }
    async [constructor](data, getFont, ctrl) {
        // remove case sensitivity
        this._availableFonts = Object.fromEntries(Object.entries(data.availableFonts).map(([k, v]) => [k.trim().toLowerCase(), v]));
        this.debug = data.debug;
        this.queryFonts = data.queryFonts;
        this._getFont = getFont;
        this._defaultFont = data.defaultFont.trim().toLowerCase();
        // hack, we want custom WASM URLs
        const _fetch = globalThis.fetch;
        globalThis.fetch = _ => _fetch(data.wasmUrl);
        // the WebGPU device request is async, so it runs while the WASM loads
        // const webgpu = WebGPURenderer.isSupported(ctrl)
        // WebGL2 is barely faster than WebGL1 in most cases,
        // in rare cases the better texture-dedupe, drawing everything in one call, no fake stride and no page ordering
        // can help its performance, but those cases are rare
        this._gpurender = WebGL2Renderer.isSupported(ctrl) ?? WebGL1Renderer.isSupported(ctrl) ?? new Canvas2DRenderer(ctrl);
        ctrl.addEventListener('webglcontextrestored', () => {
            if (this._lastDrawTime)
                this._draw(this._lastDrawTime, true);
        });
        this._loadedInitialFonts = !data.fonts.length;
        const { default: WASM } = await import(__rewriteRelativeImportExtension(/* @vite-ignore */ /* webpackIgnore: true */ data.wasmWorkerUrl));
        // Emscripten pre-spawns pthread workers from this pool size.
        // A pool size of 0 pre-spawns no workers.
        // libass reads 0 as auto, but emscripten does not.
        // THREAD_COUNT is 1 when rendering is serial. Pass 0 in that case.
        // eslint-disable-next-line @typescript-eslint/unbound-method
        const { _malloc, JASSUB } = await WASM({ __url: data.wasmUrl, __workerUrl: data.wasmWorkerUrl, __poolSize: THREAD_COUNT > 1 ? THREAD_COUNT : 0, __out: (log) => this._log(log) });
        // lfa-ponyfill uses the global fetch.
        globalThis.fetch = _fetch;
        this._malloc = _malloc;
        this._wasm = new JASSUB(data.width, data.height, this._defaultFont);
        this._wasm.setThreads(THREAD_COUNT);
        if (!this._loadedInitialFonts)
            await this._loadInitialFonts(data.fonts);
        this._wasm.createTrackMem(data.subContent ?? await fetchtext(data.subUrl));
        if (data.libassMemoryLimit > 0 || data.libassGlyphLimit > 0) {
            this._wasm.setMemoryLimits(data.libassGlyphLimit || 0, data.libassMemoryLimit || 0);
        }
        this._checkColorSpace();
        return this;
    }
    // this passes a string of track data to libass, be it styles, events etc, which it then processes and adds to the track
    // useful for streaming subtitles
    processData(events) {
        if (!this._wasm.processData(events))
            throw new Error(NO_TRACK);
    }
    // processes a single subtitle packet with ReadOrder, timecode and duration
    // useful for streaming subtitles from Matroska-style demuxers
    processChunk(data, timecode, duration) {
        if (!this._wasm.processChunk(data, timecode, duration))
            throw new Error(NO_TRACK);
    }
    // removes all events that ended before the deadline, in milliseconds
    pruneEvents(deadline) {
        if (!this._wasm.pruneEvents(deadline))
            throw new Error(NO_TRACK);
    }
    // after every render, libass removes the events that ended more than delay milliseconds ago
    _pruneDelay = -1;
    configurePrune(delay) {
        this._pruneDelay = delay;
        this._wasm.configurePrune(delay);
    }
    // removes all events, but keeps the styles and the script info
    removeAllEvents() {
        if (!this._wasm.removeAllEvents())
            throw new Error(NO_TRACK);
    }
    createEvent(event) {
        if (!this._wasm.createEvent(event))
            throw new Error(NO_TRACK);
    }
    getEvents() {
        return this._wasm.getEvents();
    }
    setEvent(event, index) {
        if (!this._wasm.setEvent(index, event))
            throw new RangeError(`JASSUB: no event at index ${index}`);
    }
    removeEvent(index) {
        if (!this._wasm.removeEvent(index))
            throw new RangeError(`JASSUB: no event at index ${index}`);
    }
    createStyle(style) {
        if (!this._wasm.createStyle(style))
            throw new Error(NO_TRACK);
    }
    getStyles() {
        return this._wasm.getStyles();
    }
    setStyle(style, index) {
        if (!this._wasm.setStyle(index, style))
            throw new RangeError(`JASSUB: no style at index ${index}`);
    }
    removeStyle(index) {
        if (!this._wasm.removeStyle(index))
            throw new RangeError(`JASSUB: no style at index ${index}`);
    }
    styleOverride(style) {
        this._wasm.styleOverride(style);
    }
    disableStyleOverride() {
        this._wasm.disableStyleOverride();
    }
    setTrack(content) {
        this._wasm.createTrackMem(content);
        this._wasm.configurePrune(this._pruneDelay);
        this._checkColorSpace();
    }
    freeTrack() {
        this._wasm.removeTrack();
    }
    async setTrackByUrl(url) {
        this.setTrack(await fetchtext(url));
    }
    _checkColorSpace() {
        const subtitle = LIBASS_YCBCR_MATRIX[this._wasm.trackColorSpace];
        this._gpurender.setColorConversion(new Float32Array((subtitle && this._videoColorSpace && COLOR_CONVERSIONS[subtitle][this._videoColorSpace]) ?? IDENTITY_MATRIX));
        if (this._lastDrawTime)
            this._draw(this._lastDrawTime, true);
    }
    _defaultFont;
    setDefaultFont(fontName) {
        this._defaultFont = fontName.trim().toLowerCase();
        this._wasm.setDefaultFont(this._defaultFont);
    }
    async _log(log) {
        console.debug(log);
        const match = log.match(/JASSUB: fontselect:[^(]+: \(([^,]*), (\d{1,4}), \d+\)/);
        if (match && !await this._findAvailableFont(match[1].trim().toLowerCase(), WEIGHT_MAP[Math.ceil(parseInt(match[2]) / 100) - 1])) {
            await this._findAvailableFont(this._defaultFont);
        }
    }
    async addFonts(fontOrURLs) {
        if (!fontOrURLs.length)
            return false;
        const strings = [];
        const uint8s = [];
        for (const fontOrURL of fontOrURLs) {
            if (typeof fontOrURL === 'string') {
                strings.push(fontOrURL);
            }
            else {
                uint8s.push(fontOrURL);
            }
        }
        if (uint8s.length)
            this._allocFonts(uint8s);
        // this isn't batched like uint8s because software like jellyfin exists, which loads 50+ fonts over the network which takes time...
        // is connection exhaustion a concern here?
        const results = await Promise.allSettled(strings.map(url => this._asyncWrite(url)));
        for (const result of results) {
            if (result.status === 'rejected')
                console.warn('JASSUB: failed to load font', result.reason);
        }
        return true;
    }
    // we don't want to run _findAvailableFont before initial fonts are loaded
    // because it could duplicate fonts
    _loadedInitialFonts = false;
    async _loadInitialFonts(fontOrURLs) {
        await this.addFonts(fontOrURLs);
        this._loadedInitialFonts = true;
        this._wasm.reloadFonts();
    }
    _getFont;
    _availableFonts = {};
    _checkedFonts = new Set();
    async _findAvailableFont(fontName, weight) {
        if (!this._loadedInitialFonts)
            return;
        // Roboto Medium, null -> Roboto, Medium
        // Roboto Medium, Medium -> Roboto, Medium
        // Roboto Medium, Bold -> Roboto, Medium
        // Roboto, null -> Roboto, Regular
        // italic is not handled I guess
        const match = fontName.match(WEIGHT_SUFFIX);
        if (match) {
            fontName = match[1];
            weight = match[2];
        }
        weight ??= 'regular';
        const key = fontName + ' ' + weight;
        if (this._checkedFonts.has(key))
            return;
        this._checkedFonts.add(key);
        try {
            const font = this._availableFonts[key] ?? this._availableFonts[fontName] ?? await this._queryLocalFont(fontName, weight) ?? await this._queryRemoteFont([key, fontName]);
            if (font)
                return await this.addFonts([font]);
        }
        catch (e) {
            console.warn('Error querying font', fontName, weight, e);
        }
    }
    queryFonts;
    async _queryLocalFont(fontName, weight) {
        if (!this.queryFonts)
            return;
        return await this._getFont(fontName, weight);
    }
    async _queryRemoteFont(postscriptNames) {
        if (this.queryFonts !== 'localandremote')
            return;
        const fontData = await queryRemoteFonts({ postscriptNames });
        if (!fontData.length)
            return;
        const blob = await fontData[0].blob();
        return new Uint8Array(await blob.arrayBuffer());
    }
    async _asyncWrite(font) {
        const res = await fetchok(font);
        this._allocFonts([new Uint8Array(await res.arrayBuffer())]);
    }
    _fontId = 0;
    _allocFonts(uint8s) {
        // TODO: this should re-draw last frame!
        for (const uint8 of uint8s) {
            const ptr = this._malloc(uint8.byteLength) >>> 0;
            self.HEAPU8RAW.set(uint8, ptr);
            this._wasm.addFont('font-' + (this._fontId++), ptr, uint8.byteLength);
        }
        this._wasm.reloadFonts();
    }
    _resizeCanvas(width, height, videoWidth, videoHeight) {
        this._wasm.resizeCanvas(width, height, videoWidth, videoHeight);
        this._gpurender.resizeCanvas(width, height);
    }
    async [finalizer]() {
        this._wasm.quitLibrary();
        this._gpurender.destroy();
        // @ts-expect-error force GC
        this._wasm = null;
        // @ts-expect-error force GC
        this._gpurender = null;
        this._availableFonts = {};
    }
    _lastDrawTime;
    _draw(time, repaint = false) {
        this._lastDrawTime = time;
        try {
            const view = this._wasm.rawRender(time, Number(repaint));
            if (!view)
                return;
            this._gpurender.render(readImages(view), self.HEAPU8RAW);
        }
        catch (e) {
            throw renderError(e);
        }
    }
    // single-frame readback for tests
    _drawCapture(time) {
        const view = this._wasm.rawRender(time, 1);
        this._gpurender.render(view ? readImages(view) : [], self.HEAPU8RAW);
        const canvas = this._gpurender.canvas;
        if (!canvas)
            return;
        const { width, height } = canvas;
        const surface = new OffscreenCanvas(width, height);
        const ctx = surface.getContext('2d');
        if (!ctx)
            return;
        ctx.drawImage(canvas, 0, 0);
        return { data: ctx.getImageData(0, 0, width, height).data, width, height };
    }
    _setColorSpace(matrix, fullRange) {
        this._videoColorSpace = matrix && matrix !== 'rgb' ? `${WEB_MATRIX_COEFFICIENTS[matrix]} ${fullRange ? 'PC' : 'TV'}` : undefined;
        this._checkColorSpace();
    }
}
if (self.name === 'jassub-worker') {
    expose(ASSRenderer);
}
//# sourceMappingURL=worker.js.map