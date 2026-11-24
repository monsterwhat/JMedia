import { wrap as _wrap, expose as _expose } from "../src/abslink.js";
function createWrapper(channel, messageable) {
    const listeners = new WeakMap();
    channel.start?.();
    messageable.start?.();
    return {
        on(event, listener) {
            const unwrapped = (event) => listener(event.data);
            if ('addEventListener' in channel) {
                channel.addEventListener(event, unwrapped);
            }
            else if ('addListener' in channel) {
                channel.addListener(event, unwrapped);
            }
            else {
                channel.on(event, unwrapped);
            }
            listeners.set(listener, unwrapped);
        },
        off(event, listener) {
            const unwrapped = listeners.get(listener);
            if ('removeEventListener' in channel) {
                channel.removeEventListener(event, unwrapped);
            }
            else if ('removeListener' in channel) {
                channel.removeListener(event, unwrapped);
            }
            else {
                channel.off(event, unwrapped);
            }
            listeners.delete(listener);
        },
        postMessage(message, transfer = []) {
            messageable.postMessage(message, transfer);
        }
    };
}
export function wrap(channel, messageable = channel) {
    return _wrap(createWrapper(channel, messageable));
}
export function expose(obj, channel = globalThis, messageable = channel) {
    return _expose(obj, createWrapper(channel, messageable));
}
//# sourceMappingURL=w3c.js.map