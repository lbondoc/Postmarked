package io.github.lbondoc.postmarked.web

import kotlin.js.Promise

// The browser calls the website needs, each a small piece of JavaScript. Files, pictures and blobs stay on the
// JavaScript side as handles (JsAny); only small results (hashes, thumbnails, pixels) are copied into Kotlin.

/** Opens the file picker. Resolves with the chosen files, or none when cancelled. */
internal fun pickFiles(): Promise<JsArray<JsAny>> = js(
    """new Promise((resolve) => {
        const input = document.createElement('input');
        input.type = 'file';
        input.multiple = true;
        input.accept = 'image/*,video/*';
        input.onchange = () => resolve(Array.from(input.files));
        input.oncancel = () => resolve([]);
        input.click();
    })""",
)

internal fun fileName(file: JsAny): String = js("file.name")
internal fun fileType(file: JsAny): String = js("file.type || ''")
internal fun blobSize(blob: JsAny): Double = js("blob.size")
internal fun fileModified(file: JsAny): Double = js("file.lastModified")

/** The first [bytes] of [file], for reading EXIF without loading the whole picture. */
internal fun fileStart(file: JsAny, bytes: Int): Promise<JsAny> = js("file.slice(0, bytes).arrayBuffer()")

/** The SHA-256 of a whole file as hex, the same fingerprint the phone uses to skip files already added. */
internal fun sha256Hex(file: JsAny): Promise<JsString> = js(
    """file.arrayBuffer()
        .then((b) => crypto.subtle.digest('SHA-256', b))
        .then((h) => Array.from(new Uint8Array(h), (x) => x.toString(16).padStart(2, '0')).join(''))""",
)

/**
 * A drawable picture from a photo (turned upright from its EXIF) or a video's frame about a second in,
 * or halfway for shorter clips, as on the phone.
 */
internal fun picture(file: JsAny, isVideo: Boolean): Promise<JsAny> = js(
    """isVideo ? new Promise((resolve, reject) => {
        const video = document.createElement('video');
        const url = URL.createObjectURL(file);
        const done = (f) => (x) => { URL.revokeObjectURL(url); f(x); };
        video.muted = true;
        video.preload = 'auto';
        video.onloadedmetadata = () => { video.currentTime = Math.min(1, (video.duration || 0) / 2); };
        video.onseeked = () => createImageBitmap(video).then(done(resolve), done(reject));
        video.onerror = done(() => reject(new Error('This video has no picture to show.')));
        video.src = url;
    }) : createImageBitmap(file, { imageOrientation: 'from-image' })""",
)

/** [source] drawn at most [maxSide] px on its long side (never enlarged), or at exactly [width] × [height]. */
internal fun scaled(source: JsAny, maxSide: Int, width: Int, height: Int): JsAny = js(
    """(() => {
        const f = maxSide > 0 ? Math.min(1, maxSide / Math.max(source.width, source.height)) : 0;
        const w = maxSide > 0 ? Math.max(1, Math.round(source.width * f)) : width;
        const h = maxSide > 0 ? Math.max(1, Math.round(source.height * f)) : height;
        const canvas = new OffscreenCanvas(w, h);
        const g = canvas.getContext('2d');
        g.imageSmoothingQuality = 'high';
        g.drawImage(source, 0, 0, w, h);
        return canvas;
    })()""",
)

internal fun canvasWidth(canvas: JsAny): Int = js("canvas.width")
internal fun canvasHeight(canvas: JsAny): Int = js("canvas.height")

/** A JPEG of [canvas]. Pictures drawn on a canvas carry no metadata, so display copies never include GPS. */
internal fun jpeg(canvas: JsAny, quality: Double): Promise<JsAny> =
    js("canvas.convertToBlob({ type: 'image/jpeg', quality: quality })")

/** The canvas's pixels as ARGB ints, the layout the palette and fingerprint code expects. */
internal fun argbPixels(canvas: JsAny): JsAny = js(
    """(() => {
        const d = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
        const out = new Int32Array(d.length / 4);
        for (let i = 0; i < out.length; i++) out[i] = (255 << 24) | (d[4*i] << 16) | (d[4*i+1] << 8) | d[4*i+2];
        return out;
    })()""",
)

internal fun blobBytes(blob: JsAny): Promise<JsAny> = js("blob.arrayBuffer()")

private fun int8View(buffer: JsAny): JsAny = js("new Int8Array(buffer)")
private fun lengthOf(array: JsAny): Int = js("array.length")
private fun byteAt(array: JsAny, i: Int): Byte = js("array[i]")
private fun intAt(array: JsAny, i: Int): Int = js("array[i]")
private fun newBytes(size: Int): JsAny = js("new Uint8Array(size)")
private fun setByte(array: JsAny, i: Int, value: Byte): Unit = js("array[i] = value")

internal fun bytesOf(buffer: JsAny): ByteArray {
    val view = int8View(buffer)
    return ByteArray(lengthOf(view)) { byteAt(view, it) }
}

internal fun intsOf(int32Array: JsAny): IntArray = IntArray(lengthOf(int32Array)) { intAt(int32Array, it) }

internal fun ByteArray.toUint8Array(): JsAny = newBytes(size).also { array -> forEachIndexed { i, b -> setByte(array, i, b) } }

/**
 * Sends [body] (a File or Blob) to Storage at [url], reporting the share sent so far. XMLHttpRequest rather than
 * fetch, because only it reports upload progress. Resolves with the HTTP status (0 when the network failed).
 */
internal fun upload(
    url: String,
    body: JsAny,
    contentType: String,
    token: String,
    apiKey: String,
    onProgress: (Double) -> Unit,
    abort: JsAny,
): Promise<JsNumber> = js(
    """new Promise((resolve) => {
        const xhr = new XMLHttpRequest();
        abort.signal.addEventListener('abort', () => xhr.abort());
        xhr.onabort = () => resolve(0);
        xhr.open('POST', url);
        xhr.setRequestHeader('authorization', 'Bearer ' + token);
        xhr.setRequestHeader('apikey', apiKey);
        xhr.setRequestHeader('x-upsert', 'true');
        xhr.setRequestHeader('content-type', contentType);
        xhr.upload.onprogress = (e) => { if (e.lengthComputable && e.total > 0) onProgress(e.loaded / e.total); };
        xhr.onload = () => resolve(xhr.status);
        xhr.onerror = () => resolve(0);
        xhr.send(body);
    })""",
)

/** Stops an [upload] in flight when its coroutine is cancelled (a cancelled upload). */
internal fun abortController(): JsAny = js("new AbortController()")
internal fun abortNow(controller: JsAny): Unit = js("controller.abort()")

/** Asks before closing the tab while [busy]: uploads on the web only run while the page is open. */
internal fun warnBeforeLeaving(busy: Boolean): Unit = js(
    "window.onbeforeunload = busy ? ((e) => { e.preventDefault(); e.returnValue = ''; }) : null",
)

/** Saves [bytes] as a download named [name]. */
internal fun download(bytes: JsAny, name: String, type: String): Unit = js(
    """(() => {
        const url = URL.createObjectURL(new Blob([bytes], { type: type }));
        const a = document.createElement('a');
        a.href = url;
        a.download = name;
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 60000);
    })()""",
)

/** Web Share with the pictures where the browser can (phones). False when it can't share files. */
internal fun shareFiles(files: JsArray<JsAny>, text: String): Boolean = js(
    """(() => {
        const shared = files.map((f) => new File([f.bytes], f.name, { type: 'image/jpeg' }));
        if (!navigator.canShare || !navigator.canShare({ files: shared })) return false;
        navigator.share({ files: shared, text: text }).catch(() => {});
        return true;
    })()""",
)

internal fun namedBytes(name: String, bytes: JsAny): JsAny = js("({ name: name, bytes: bytes })")

/** Downloads [url] as a Blob, failing on an HTTP error. */
internal fun fetchBlob(url: String): Promise<JsAny> =
    js("fetch(url).then((r) => r.ok ? r.blob() : Promise.reject(new Error('Download failed (' + r.status + ')')))")

internal fun blobType(blob: JsAny): String = js("blob.type || ''")

/** Saves [blob] as a download named [name]. */
internal fun downloadBlob(blob: JsAny, name: String): Unit = js(
    """(() => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = name;
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 60000);
    })()""",
)

internal fun namedBlob(name: String, blob: JsAny): JsAny = js("({ name: name, blob: blob })")

/** Web Share with the files where the browser can share files (phones). False when it can't. */
internal fun shareBlobs(named: JsArray<JsAny>): Boolean = js(
    """(() => {
        const files = named.map((n) => new File([n.blob], n.name, { type: n.blob.type }));
        if (!navigator.canShare || !navigator.canShare({ files: files })) return false;
        navigator.share({ files: files }).catch(() => {});
        return true;
    })()""",
)
