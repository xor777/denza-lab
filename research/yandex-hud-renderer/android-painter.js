/* Owned Android buffers. Native map access is separate from evidence PNG encoding. */
'use strict';
function createHudAndroidPainter(width, height) {
    const Bitmap = Java.use('android.graphics.Bitmap');
    const config = Java.use('android.graphics.Bitmap$Config').ARGB_8888.value;
    const Canvas = Java.use('android.graphics.Canvas');
    const Paint = Java.use('android.graphics.Paint');
    const Style = Java.use('android.graphics.Paint$Style');
    const Path = Java.use('android.graphics.Path');
    const Filter = Java.use('android.graphics.ColorMatrixColorFilter');
    const Base64 = Java.use('android.util.Base64');
    const pngFormat = Java.use('android.graphics.Bitmap$CompressFormat').PNG.value;
    const context = Java.use('android.app.ActivityThread').currentApplication();
    const resources = context.getResources();
    const owned = [];
    const own = object => { owned.push(object); return object; };
    const drawables = new Map();
    let closed = false;
    let overlayKey = null, staticFrame = null;
    let output, overlay, canvas, overlayCanvas, paint, stream;
    const stats = {overlayBuilds: 0, drawableLoads: 0, encodes: 0, staticReuses: 0};

    function dispose() {
        if (closed) return;
        closed = true;
        const errors = [];
        for (const value of [output, overlay]) {
            try { if (value) value.recycle(); } catch (error) { errors.push(String(error)); }
        }
        for (const value of owned.reverse()) {
            try { value.$dispose(); } catch (error) { errors.push(String(error)); }
        }
        drawables.clear();
        staticFrame = null;
        if (errors.length) throw new Error(errors.join('; '));
    }

    try {
        output = own(Bitmap.createBitmap(width, height, config));
        overlay = own(Bitmap.createBitmap(width, height, config));
        canvas = own(Canvas.$new(output));
        overlayCanvas = own(Canvas.$new(overlay));
        paint = own(Paint.$new(3));
        paint.setStrokeCap(Java.use('android.graphics.Paint$Cap').ROUND.value);
        paint.setStrokeJoin(Java.use('android.graphics.Paint$Join').ROUND.value);
        paint.setTypeface(own(Java.use('android.graphics.Typeface').create('sans-serif-medium', 0)));
        stream = own(Java.use('java.io.ByteArrayOutputStream').$new(32768));
    } catch (error) {
        dispose();
        throw error;
    }

    function drawable(command) {
        // Transform properties belong to the command, not to the resource identity.
        let value = drawables.get(command.name);
        if (!value) {
            const id = resources.getIdentifier(command.name, 'drawable', 'ru.yandex.yandexnavi');
            if (!id) throw new Error('Missing stock drawable: ' + command.name);
            value = own(context.getDrawable(id).mutate());
            drawables.set(command.name, value);
            stats.drawableLoads++;
        }
        value.setBounds(0, 0, Math.round(command.w), Math.round(command.h));
        value.setAlpha(Math.round(command.alpha * 255));
        if (command.brightness === 1) value.clearColorFilter();
        else {
            const b = command.brightness;
            const filter = Filter.$new(Java.array('float', [b,0,0,0,0, 0,b,0,0,0, 0,0,b,0,0, 0,0,0,1,0]));
            try { value.setColorFilter(filter); } finally { filter.$dispose(); }
        }
        overlayCanvas.save();
        try {
            overlayCanvas.translate(command.x + command.w / 2, command.y + command.h / 2);
            overlayCanvas.rotate(command.rotation);
            overlayCanvas.translate(-command.w / 2, -command.h / 2);
            value.draw(overlayCanvas);
        } finally { overlayCanvas.restore(); }
    }

    function prepare(commands) {
        if (closed) throw new Error('Painter disposed');
        const key = JSON.stringify(commands);
        if (key === overlayKey) return;
        // Commit the key only after successful paint: errors must not cache partial overlays.
        overlay.eraseColor(0);
        for (const command of commands) {
            paint.setColor((0xff000000 | parseInt((command.color || '#000000').slice(1), 16)) | 0);
            paint.setStyle(Style.FILL.value);
            switch (command.op) {
                case 'roundRect':
                    overlayCanvas.drawRoundRect(command.x, command.y, command.x + command.w,
                        command.y + command.h, command.r, command.r, paint);
                    break;
                case 'text':
                    paint.setTextSize(command.size);
                    overlayCanvas.drawText(command.value, command.x, command.y, paint);
                    break;
                case 'path': {
                    const path = Path.$new();
                    try {
                        command.points.forEach((point, i) => i ? path.lineTo(...point) : path.moveTo(...point));
                        if (command.fill) path.close();
                        else { paint.setStyle(Style.STROKE.value); paint.setStrokeWidth(command.width); }
                        overlayCanvas.drawPath(path, paint);
                    } finally { path.$dispose(); }
                    break;
                }
                case 'drawable': drawable(command); break;
                default: throw new Error('Unknown drawing command: ' + command.op);
            }
        }
        overlayKey = key;
        staticFrame = null;
        stats.overlayBuilds++;
    }

    // Main thread: copy a screenshot we own, then release it immediately. Two fixed
    // ARGB buffers (432 KB total) serve every frame, including immutable native captures.
    function compose(screenshot) {
        output.eraseColor(-16777216);
        if (screenshot) canvas.drawBitmap(screenshot, 0, 0, null);
        canvas.drawBitmap(overlay, 0, 0, null);
    }

    // Frida worker thread, never Android UI thread. Single-flight renderer owns output.
    function encode(isStatic) {
        if (isStatic && staticFrame) { stats.staticReuses++; return staticFrame; }
        stream.reset();
        if (!output.compress(pngFormat, 100, stream)) throw new Error('PNG encoding failed');
        const raw = stream.toByteArray();
        const frame = {base64: String(Base64.encodeToString(raw, 2)), bytes: raw.length};
        stats.encodes++;
        if (isStatic) staticFrame = frame;
        return frame;
    }

    return {prepare, compose, encode, dispose, stats};
}
