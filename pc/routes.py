import time
import io
import logging
from flask import render_template, Response
from PIL import Image, ImageDraw, ImageFont
from manager import plugins 

logger = logging.getLogger("CORE")

def register_routes(app, p_manager):
    @app.route('/')
    def index():
        return render_template('index.html')

    @app.route('/api/karaoke/<device_id>')
    def karaoke_stream(device_id):
        def generate():
            font_path = "C:\\Windows\\Fonts\\arial.ttf"
            font_path_bold = "C:\\Windows\\Fonts\\arialbd.ttf"
            try:
                font_main = ImageFont.truetype(font_path_bold, 50)
                font_sub = ImageFont.truetype(font_path, 35)
                font_meta = ImageFont.truetype(font_path_bold, 24)
                font_artist = ImageFont.truetype(font_path, 20)
            except:
                font_main = font_sub = font_meta = font_artist = ImageFont.load_default()

            while True:
                # 1. Сбор данных
                lyric_plugin = p_manager.get_plugin('yandex_lyrics')
                station_plugin = p_manager.get_plugin('yandex_station')
                
                device_data = {}
                if lyric_plugin:
                    device_data = lyric_plugin.get_stats().get('devices', {}).get(device_id, {})
                
                track_info = {"title": "Unknown", "artist": "Unknown", "progress": 0, "playing": False, "cover": None}
                if station_plugin:
                    s_stats = station_plugin.get_stats()
                    for d in s_stats.get('devices', []):
                        if d['id'] == device_id:
                            track_info.update({
                                "title": d.get('title', 'Unknown'),
                                "artist": d.get('subtitle', 'Unknown'),
                                "progress": d.get('progress', 0),
                                "playing": d.get('playing', False),
                                "cover": d.get('cover'),
                                "last_upd": d.get('last_update', time.time())
                            })
                            break
                
                # 2. Создание базы (фон)
                img = Image.new('RGB', (1280, 720), color=(10, 10, 15))
                if track_info['cover']:
                    try:
                        import requests
                        from PIL import ImageFilter
                        resp = requests.get(track_info['cover'], timeout=2)
                        cover_img = Image.open(io.BytesIO(resp.content)).convert('RGB')
                        cover_img = cover_img.resize((1280, 720))
                        img = cover_img.filter(ImageFilter.GaussianBlur(radius=40))
                        # Затемнение фона
                        overlay = Image.new('RGBA', (1280, 720), (0, 0, 0, 160))
                        img.paste(overlay, (0, 0), overlay)
                    except: pass
                
                draw = ImageDraw.Draw(img)
                
                # 3. Отрисовка метаданных (сверху)
                draw.text((60, 40), track_info['title'].upper(), font=font_meta, fill=(255, 255, 255, 255))
                draw.text((60, 75), track_info['artist'], font=font_artist, fill=(56, 189, 248, 255))

                # 4. Отрисовка текста (Центр)
                timings = device_data.get('timings', [])
                full_lyrics = device_data.get('lyrics', "")
                
                display_text = ""
                secondary_text = ""
                
                if timings:
                    # Режим караоке
                    current_time_ms = (track_info['progress'] + (time.time() - track_info.get('last_upd', time.time()) if track_info['playing'] else 0)) * 1000
                    for i, t_line in enumerate(timings):
                        if t_line['time'] <= current_time_ms:
                            display_text = t_line['text']
                            if i + 1 < len(timings):
                                secondary_text = timings[i+1]['text']
                        else: break
                elif full_lyrics:
                    # Режим обычного текста (берем первые 4 строки для красоты)
                    lines = [l for l in full_lyrics.split('\n') if l.strip()][:3]
                    display_text = "\n".join(lines)
                else:
                    display_text = "Wait for music..." if not track_info['playing'] else "No lyrics found"

                # Рисуем основной текст с тенью для читаемости
                def draw_text_centered(text, y, font, color, shadow=True):
                    if not text: return
                    lines = text.split('\n')
                    for i, line in enumerate(lines):
                        w, h = draw.textbbox((0, 0), line, font=font)[2:]
                        x = (1280 - w) / 2
                        cur_y = y + (i * h * 1.2)
                        if shadow:
                            draw.text((x+2, cur_y+2), line, font=font, fill=(0, 0, 0, 180))
                        draw.text((x, cur_y), line, font=font, fill=color)

                draw_text_centered(display_text, 280, font_main, (255, 255, 255))
                draw_text_centered(secondary_text, 480, font_sub, (255, 255, 255, 120))

                # 5. Индикатор прогресса (снизу)
                draw.rectangle([0, 715, 1280, 720], fill=(255, 255, 255, 30))
                if track_info.get('playing'):
                    draw.text((1220, 680), "LIVE", font=font_artist, fill=(239, 68, 68))

                output = io.BytesIO()
                img.save(output, format='JPEG', quality=85)
                yield (b'--frame\r\nContent-Type: image/jpeg\r\n\r\n' + output.getvalue() + b'\r\n')
                time.sleep(0.2)

        return Response(generate(), mimetype='multipart/x-mixed-replace; boundary=frame')
