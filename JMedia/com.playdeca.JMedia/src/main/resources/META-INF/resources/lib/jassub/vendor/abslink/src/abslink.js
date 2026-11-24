/* eslint-disable @typescript-eslint/no-unsafe-function-type */
import { MessageType, WireValueType } from "./types.js";
export * from "./types.js";
export const proxyMarker = Symbol('Abslink.proxy');
export const releaseProxy = Symbol('Abslink.releaseProxy');
export const finalizer = Symbol('Abslink.finalizer');
export const throwMarker = Symbol('Abslink.thrown');
export const isObject = (val) => (typeof val === 'object' && val !== null) || typeof val === 'function';
/**
 * Internal transfer handle to handle objects marked to proxy.
 */
const proxyTransferHandler = {
    canHandle: (val) => isObject(val) && proxyMarker in val,
    serialize(obj, ep) {
        const markerID = obj[proxyMarker];
        expose(obj, ep, markerID);
        return [markerID, []];
    },
    deserialize(markerID, ep) {
        return wrap(ep, markerID);
    }
};
/**
 * Internal transfer handler to handle thrown exceptions.
 */
const throwTransferHandler = {
    canHandle: (value) => isObject(value) && throwMarker in value,
    serialize({ value }) {
        let serialized;
        if (value instanceof Error) {
            serialized = {
                isError: true,
                value: {
                    message: value.message,
                    name: value.name,
                    stack: value.stack
                }
            };
        }
        else {
            serialized = { isError: false, value };
        }
        return [serialized, []];
    },
    deserialize(serialized) {
        if (serialized.isError) {
            throw Object.assign(new Error(serialized.value.message), serialized.value);
        }
        // eslint-disable-next-line no-throw-literal
        throw serialized.value;
    }
};
/**
 * Allows customizing the serialization of certain values.
 */
export const transferHandlers = new Map([
    ['proxy', proxyTransferHandler],
    ['throw', throwTransferHandler]
]);
function filterPath(path, obj) {
    let parent = obj;
    const parentPath = path.slice(0, -1);
    for (const segment of parentPath) {
        // Object.hasOwn() would work, but backwards compatibility...
        if (Object.prototype.hasOwnProperty.call(parent, segment)) {
            parent = parent[segment];
        }
    }
    const lastSegment = path[path.length - 1];
    const RawValue = lastSegment ? parent[lastSegment] : parent;
    return { parent, RawValue, lastSegment };
}
export function expose(obj, ep, rootMarkerID) {
    ep.on('message', function callback(data) {
        if (!data)
            return;
        const { id, type, path, markerID } = {
            path: [],
            ...data
        };
        if (markerID !== rootMarkerID)
            return;
        const argumentList = (data.argumentList ?? []).map((v) => fromWireValue(v, ep));
        let returnValue;
        try {
            const { parent, RawValue, lastSegment } = filterPath(path, obj);
            switch (type) {
                case MessageType.GET:
                    returnValue = RawValue;
                    break;
                case MessageType.SET:
                    parent[lastSegment] = fromWireValue(data.value, ep);
                    returnValue = true;
                    break;
                case MessageType.APPLY:
                    returnValue = RawValue.apply(parent, argumentList);
                    break;
                case MessageType.CONSTRUCT:
                    returnValue = new RawValue(...argumentList);
                    break;
                case MessageType.RELEASE:
                    returnValue = undefined;
                    break;
                default:
                    return;
            }
        }
        catch (value) {
            returnValue = { value, [throwMarker]: 0 };
        }
        Promise.resolve(returnValue)
            .catch((value) => {
            return { value, [throwMarker]: 0 };
        })
            .then((returnValue) => {
            // support async constructors
            // A thrown value must reach the caller as an error. Do not proxy it.
            if (type === MessageType.CONSTRUCT && !(isObject(returnValue) && throwMarker in returnValue)) {
                returnValue = proxy(returnValue);
            }
            const [wireValue, transfer] = toWireValue(returnValue, ep);
            ep.postMessage({ ...wireValue, id, markerID: rootMarkerID }, transfer);
            if (type === MessageType.RELEASE) {
                // detach and deactive after sending release response above.
                ep.off('message', callback);
                obj[finalizer]?.();
            }
        })
            .catch(_ => {
            // Send Serialization Error To Caller
            const [wireValue, transfer] = toWireValue({
                value: new TypeError('Unserializable return value'),
                [throwMarker]: 0
            }, ep);
            ep.postMessage({ ...wireValue, id, markerID: rootMarkerID }, transfer);
        });
    });
    return obj;
}
export function wrap(endpoint, rootMarkerID) {
    const pendingListeners = new Map();
    endpoint.on('message', (data) => {
        if (!data?.id) {
            return;
        }
        const resolver = pendingListeners.get(data.id);
        if (!resolver) {
            return;
        }
        try {
            resolver(data);
        }
        finally {
            pendingListeners.delete(data.id);
        }
    });
    return createProxy({ endpoint, pendingListeners, rootMarkerID });
}
function throwIfProxyReleased(isReleased) {
    if (isReleased) {
        throw new Error('Proxy has been released and is not useable');
    }
}
async function releaseEndpoint(epWithPendingListeners) {
    await requestResponseMessage(epWithPendingListeners, { type: MessageType.RELEASE });
}
const proxyCounter = new WeakMap();
const proxyFinalizers = 'FinalizationRegistry' in globalThis &&
    new FinalizationRegistry((epWithPendingListeners) => {
        const newCount = (proxyCounter.get(epWithPendingListeners) ?? 0) - 1;
        proxyCounter.set(epWithPendingListeners, newCount);
        if (newCount === 0) {
            releaseEndpoint(epWithPendingListeners).finally(() => {
                epWithPendingListeners.pendingListeners.clear();
            });
        }
    });
function registerProxy(proxy, epWithPendingListeners) {
    const newCount = (proxyCounter.get(epWithPendingListeners) ?? 0) + 1;
    proxyCounter.set(epWithPendingListeners, newCount);
    if (proxyFinalizers) {
        proxyFinalizers.register(proxy, epWithPendingListeners, proxy);
    }
}
function unregisterProxy(proxy) {
    if (proxyFinalizers) {
        proxyFinalizers.unregister(proxy);
    }
}
function createProxy(epWithPendingListeners, path = []) {
    let isProxyReleased = false;
    const propProxyCache = new Map();
    const proxy = new Proxy(function () { }, {
        get(_target, prop) {
            throwIfProxyReleased(isProxyReleased);
            if (prop === releaseProxy) {
                return async () => {
                    for (const subProxy of propProxyCache.values()) {
                        subProxy[releaseProxy]();
                    }
                    propProxyCache.clear();
                    unregisterProxy(proxy);
                    releaseEndpoint(epWithPendingListeners).finally(() => {
                        epWithPendingListeners.pendingListeners.clear();
                    });
                    isProxyReleased = true;
                };
            }
            if (prop === 'then') {
                if (path.length === 0) {
                    return { then: () => proxy };
                }
                const r = requestResponseMessage(epWithPendingListeners, {
                    type: MessageType.GET,
                    path: path.map((p) => p.toString())
                }).then(v => fromWireValue(v, epWithPendingListeners.endpoint));
                return r.then.bind(r);
            }
            const cachedProxy = propProxyCache.get(prop);
            if (cachedProxy) {
                return cachedProxy;
            }
            const propProxy = createProxy(epWithPendingListeners, [...path, prop]);
            propProxyCache.set(prop, propProxy);
            return propProxy;
        },
        set(_target, prop, rawValue) {
            throwIfProxyReleased(isProxyReleased);
            // FIXME: ES6 Proxy Handler `set` methods are supposed to return a
            // boolean. To show good will, we return true asynchronously ¯\_(ツ)_/¯
            const [value, transfer] = toWireValue(rawValue, epWithPendingListeners.endpoint);
            return requestResponseMessage(epWithPendingListeners, {
                type: MessageType.SET,
                path: [...path, prop].map((p) => p.toString()),
                value
            }, transfer).then(v => fromWireValue(v, epWithPendingListeners.endpoint));
        },
        apply(_target, _thisArg, rawArgumentList) {
            throwIfProxyReleased(isProxyReleased);
            const last = path[path.length - 1];
            // We just pretend that `bind()` didn’t happen.
            if (last === 'bind') {
                return createProxy(epWithPendingListeners, path.slice(0, -1));
            }
            const [argumentList, transfer] = processArguments(rawArgumentList, epWithPendingListeners);
            return requestResponseMessage(epWithPendingListeners, {
                type: MessageType.APPLY,
                path: path.map((p) => p.toString()),
                argumentList
            }, transfer).then(v => fromWireValue(v, epWithPendingListeners.endpoint));
        },
        construct(_target, rawArgumentList) {
            throwIfProxyReleased(isProxyReleased);
            const [argumentList, transfer] = processArguments(rawArgumentList, epWithPendingListeners);
            return requestResponseMessage(epWithPendingListeners, {
                type: MessageType.CONSTRUCT,
                path: path.map((p) => p.toString()),
                argumentList
            }, transfer).then(v => fromWireValue(v, epWithPendingListeners.endpoint));
        }
    });
    registerProxy(proxy, epWithPendingListeners);
    return proxy;
}
const transferCache = new WeakMap();
export function transfer(obj, transfers) {
    transferCache.set(obj, transfers);
    return obj;
}
function processArguments(argumentList, epWithPendingListeners) {
    const wireValues = [];
    const transferables = [];
    for (const argument of argumentList) {
        const [wireValue, transfer] = toWireValue(argument, epWithPendingListeners.endpoint);
        wireValues.push(wireValue);
        transferables.push(...transfer);
    }
    return [wireValues, transferables];
}
export function proxy(obj) {
    return Object.assign(obj, { [proxyMarker]: randomId() });
}
function toWireValue(value, ep) {
    for (const [name, handler] of transferHandlers) {
        if (handler.canHandle(value)) {
            const [serializedValue, transfer] = handler.serialize(value, ep);
            return [{
                    type: WireValueType.HANDLER,
                    name,
                    value: serializedValue
                }, transfer];
        }
    }
    return [{
            type: WireValueType.RAW,
            value
        }, transferCache.get(value) ?? []];
}
function fromWireValue(value, ep) {
    switch (value.type) {
        case WireValueType.HANDLER:
            return transferHandlers.get(value.name).deserialize(value.value, ep);
        case WireValueType.RAW:
            return value.value;
    }
}
function requestResponseMessage(ep, msg, transfer = []) {
    return new Promise((resolve) => {
        const id = randomId();
        ep.pendingListeners.set(id, resolve);
        ep.endpoint.postMessage({ id, ...msg, markerID: ep.rootMarkerID }, transfer);
    });
}
const hex = [];
const alphabet = '0123456789abcdef';
for (let i = 0; i < 256; i++) {
    hex[i] = alphabet[i >> 4 & 0xf] + alphabet[i & 0xf];
}
let step = 0;
let buffer = '';
function randomId() {
    let i = 0;
    if (!buffer || ((step + 16) > 256 * 2)) {
        for (buffer = '', step = 0; i < 256; ++i) {
            buffer += hex[Math.random() * 256 | 0];
        }
    }
    return buffer.substring(step, ++step + 16);
}
//# sourceMappingURL=abslink.js.map