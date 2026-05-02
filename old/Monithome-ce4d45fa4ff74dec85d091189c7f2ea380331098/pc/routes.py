import time
import io
import logging
from flask import render_template, Response
from PIL import Image, ImageDraw, ImageFont
from manager import plugins 

logger = logging.getLogger("CORE")

def generate_karaoke_frame(p_manager, device_id):
    """Генерация одного кадра караоке для устройства"""
    # 1. Сбор данных
    lyric_plugin = p_manager.plugins.get('yandex_lyrics')
    station_plugin = p_manager.plugins.get('yandex_station')
    
    device_data = {}
    if lyric_plugin:
        # Пытаемся взять данные из кэша (devices в стейте плагина)
        device_data = lyric_plugin._lyrics_cache.get(device_id, {})
    
    track_info = {"title": "Unknown", "artist": "Unknown", "progress": 0, "playing": False, "cover": None}
    if station_plugin:
        # Берем актуальное состояние из плагина
        for d_id, state in station_plugin.states.items():
            if d_id == device_id:
                track_info.update({
                    "title": state.get('title', 'Unknown'),
                    "artist": state.get('subtitle', 'Unknown') or state.get('artist', 'Unknown'),
                    "progress": state.get('progress', 0),
                    "playing": state.get('playing', False),
                    "cover": state.get('cover'),
                    "last_upd": state.get('last_update', time.time())
                })
                break

    # 2. Создание изображения
    img = Image.new('RGB', (1280, 720), color=(10, 10, 15))
    try:
        # Шрифты (стараемся найти подходящие в системе)
        font_candidates = ["arial", "roboto", "segoeui", "ubuntu"]
        def get_font(name, size, bold=False):
            suffix = "bd" if bold else ""
            paths = [
                f"C:\\Windows\\Fonts\\{name}{suffix}.ttf",
                f"/usr/share/fonts/truetype/{name}/{name}{suffix}.ttf",
                f"~/.fonts/{name}{suffix}.ttf"
            ]
            for p in paths:
                p = os.path.expanduser(p)
                if os.path.exists(p): return ImageFont.truetype(p, size)
            return ImageFont.load_default()

        f_main = get_font("arial", 50, bold=True)
        f_sub = get_font("arial", 35)
        f_meta = get_font("arial", 24, bold=True)
        f_artist = get_font("arial", 20)
    except:
        f_main = f_sub = f_meta = f_artist = ImageFont.load_default()

    if track_info['cover']:
        try:
            import requests
            from PIL import ImageFilter
            resp = requests.get(track_info['cover'], timeout=1)
            c_img = Image.open(io.BytesIO(resp.content)).convert('RGB').resize((1280, 720))
            img = c_img.filter(ImageFilter.GaussianBlur(radius=40))
            overlay = Image.new('RGBA', (1280, 720), (0, 0, 0, 160))
            img.paste(overlay, (0, 0), overlay)
        except: pass

    draw = ImageDraw.Draw(img)
    draw.text((60, 40), track_info['title'].upper(), font=f_meta, fill=(255, 255, 255))
    draw.text((60, 75), track_info['artist'], font=f_artist, fill=(56, 189, 248))

    # 3. Текст песни
    timings = device_data.get('timings', [])
    display_text, secondary_text = "", ""
    if timings:
        now = time.time()
        cur_ms = (track_info['progress'] + (now - track_info.get('last_upd', now) if track_info['playing'] else 0)) * 1000
        for i, t_line in enumerate(timings):
            if t_line['time'] <= cur_ms:
                display_text = t_line['text']
                if i + 1 < len(timings): secondary_text = timings[i+1]['text']
            else:
                if i == 0: secondary_text = t_line['text']
                break
    elif device_data.get('lyrics'):
        display_text = "\n".join([l for l in device_data['lyrics'].split('\n') if l.strip()][:3])
    else:
        display_text = "Wait for music..." if not track_info['playing'] else "No lyrics found"

    def draw_centered(t, y, f, c, shadow=True):
        if not t: return
        for i, line in enumerate(t.split('\n')):
            w, h = draw.textbbox((0, 0), line, font=f)[2:]
            x = (1280 - w) / 2
            cy = y + (i * h * 1.2)
            if shadow: draw.text((x+2, cy+2), line, font=f, fill=(0, 0, 0, 180))
            draw.text((x, cy), line, font=f, fill=c)

    draw_centered(display_text, 300, f_main, (56, 189, 248))
    draw_centered(secondary_text, 420, f_sub, (200, 200, 200, 150))
    
    # Индикатор прогресса
    draw.rectangle([0, 715, 1280, 720], fill=(255, 255, 255, 30))
    if track_info.get('playing'):
        draw.text((1220, 680), "LIVE", font=f_artist, fill=(239, 68, 68))
        
    return img

def register_routes(app, p_manager):
    @app.route('/')
    def index():
        return render_template('index.html')

    @app.route('/locales/<path:filename>')
    def serve_locales(filename):
        from flask import send_from_directory
        import os
        dist_locales = os.path.abspath(os.path.join(app.root_path, '../pc_gui/dist/locales'))
        if os.path.exists(dist_locales):
            return send_from_directory(dist_locales, filename)
        public_locales = os.path.abspath(os.path.join(app.root_path, '../pc_gui/public/locales'))
        return send_from_directory(public_locales, filename)

    @app.route('/api/karaoke/static/<device_id>')
    def karaoke_static(device_id):
        img = generate_karaoke_frame(p_manager, device_id)
        img_io = io.BytesIO()
        img.save(img_io, 'JPEG', quality=80)
        img_io.seek(0)
        return Response(img_io, mimetype='image/jpeg')

    @app.route('/api/karaoke/<device_id>')
    def karaoke_stream(device_id):
        def generate():
            while True:
                img = generate_karaoke_frame(p_manager, device_id)
                output = io.BytesIO()
                img.save(output, format='JPEG', quality=85)
                yield (b'--frame\r\nContent-Type: image/jpeg\r\n\r\n' + output.getvalue() + b'\r\n')
                time.sleep(0.2) # ~5 FPS для экономии ресурсов
        return Response(generate(), mimetype='multipart/x-mixed-replace; boundary=frame')

        return Response(generate(), mimetype='multipart/x-mixed-replace; boundary=frame')
