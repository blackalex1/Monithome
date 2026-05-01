import re
import time
import json
import requests
import threading
from zeroconf import ServiceBrowser, Zeroconf
from .const import TOKENS_FILE, AUTH_FILE, CLIENT_ID, CLIENT_SECRET
from .discovery import SpeakerDiscovery, get_all_interfaces

class YandexAuth:
    def __init__(self, plugin):
        self.plugin = plugin
        self.log = plugin.log
        self.manager = plugin.manager

    def has_token(self):
        if not AUTH_FILE.exists():
            return False
        try:
            with open(AUTH_FILE, "r") as f:
                for line in f:
                    if line.startswith("YANDEX_TOKEN="):
                        val = line.split("=")[1].strip()
                        return len(val) > 10
        except: pass
        return False

    def start_qr_login(self):
        if self.plugin._qr_status in ["getting_url", "waiting"]:
            return
        self.plugin._qr_status = "getting_url"
        threading.Thread(target=self._qr_login_worker, daemon=True).start()

    def _qr_login_worker(self):
        self.log("Starting Yandex QR Login worker...")
        try:
            session = requests.Session()
            session.headers.update({
                "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1",
                "Accept": "application/json, text/javascript, */*; q=0.01",
            })

            # 1. CSRF
            r = session.get("https://passport.yandex.ru/am?app_platform=android", timeout=10)
            m = re.search(r'"csrf_token" value="([^"]+)"', r.text)
            if not m: m = re.search(r'window\.__CSRF__\s*=\s*["\']([^"\']+)["\']', r.text)
            if not m:
                self.plugin._qr_status = "error"
                self.manager.broadcast_ui()
                return
            page_csrf = m.group(1)

            bff_headers = {
                "X-CSRF-Token": page_csrf,
                "Origin": "https://passport.yandex.ru",
                "Referer": "https://passport.yandex.ru/pwl-yandex",
            }

            # 2. Multistep start
            r_start = session.post("https://passport.yandex.ru/pwl-yandex/api/passport/auth/multistep_start", headers=bff_headers, data={}, timeout=10)
            track_id = r_start.json().get("track_id")

            # 3. Request QR session
            r_qr = session.post("https://passport.yandex.ru/pwl-yandex/api/passport/auth/password/submit", headers=bff_headers, data={"track_id": track_id, "with_code": 1, "retpath": "https://passport.yandex.ru/profile"}, timeout=10)
            qr_resp = r_qr.json()
            polling_csrf = qr_resp.get("csrf_token", page_csrf)

            self.plugin._qr_url = f"https://passport.yandex.ru/auth/magic/code/?track_id={track_id}"
            self.plugin._qr_status = "getting_url"
            self.plugin._emit_qr_status()
            
            # Получаем страницу с QR-кодом и вытаскиваем из неё SVG
            try:
                import base64
                r_page = session.get(self.plugin._qr_url, timeout=10)
                # Ищем SVG в коде страницы
                svg_match = re.search(r'<svg[^>]*>.*?</svg>', r_page.text, re.DOTALL)
                
                if svg_match:
                    svg_content = svg_match.group(0)
                    # Если SVG содержит относительные пути или специфичные стили, 
                    # убеждаемся что он корректно отобразится
                    b64_data = base64.b64encode(svg_content.encode('utf-8')).decode()
                    self.plugin._qr_image_base64 = f"data:image/svg+xml;base64,{b64_data}"
                    self.log("QR SVG extracted from page and encoded to base64")
                else:
                    # Фолбек на старый метод с изображением, если SVG не найден
                    img_r = session.get(f"https://passport.yandex.ru/auth/magic/code/image?track_id={track_id}", timeout=10)
                    if img_r.status_code == 200:
                        b64_data = base64.b64encode(img_r.content).decode()
                        self.plugin._qr_image_base64 = f"data:image/png;base64,{b64_data}"
                        self.log("QR Image fetched as fallback")
            except Exception as e:
                self.log(f"Failed to extract QR: {e}", level="warning")

            self.log(f"QR Login URL: {self.plugin._qr_url}")
            self.plugin._qr_status = "waiting"
            self.plugin._emit_qr_status()
            self.manager.broadcast_ui()

            # 4. Polling
            start_time = time.time()
            while time.time() - start_time < 300:
                if self.plugin._stop_event.is_set(): return
                try:
                    status_r = session.post("https://passport.yandex.ru/auth/new/magic/status/", data={"csrf_token": polling_csrf, "track_id": track_id}, timeout=10)
                    status_resp = status_r.json()
                    if status_resp.get("status") == "ok":
                        self.log("QR Login confirmed! Getting token...")
                        cookies_str = "; ".join([f"{k}={v}" for k, v in session.cookies.get_dict().items()])
                        token_r = session.post("https://mobileproxy.passport.yandex.net/1/bundle/oauth/token_by_sessionid", 
                                             data={"client_id": CLIENT_ID, "client_secret": CLIENT_SECRET}, 
                                             headers={"Ya-Client-Host": "passport.yandex.ru", "Ya-Client-Cookie": cookies_str},
                                             timeout=10)
                        x_token = token_r.json().get("access_token")
                        if x_token:
                            with open(AUTH_FILE, "w") as f: f.write(f"YANDEX_TOKEN={x_token}\n")
                            self.log("Yandex token saved successfully.")
                            self.plugin._qr_status = "success"
                        self.plugin._emit_qr_status()
                        self.manager.broadcast_ui()
                        # Сразу уведомляем планшет о новом токене
                        self.plugin._broadcast_config_to_tablet()
                        
                        # Триггерим поиск колонок после получения токена
                        threading.Thread(target=self.refresh_tokens_sync, daemon=True).start()
                        return
                    elif status_resp.get("status") == "error":
                        self.log(f"QR Login error status: {status_resp}")
                        break
                except Exception as e:
                    self.log(f"QR Login polling error: {e}")
                time.sleep(3)
            
            self.plugin._qr_status = "error"
            self.plugin._emit_qr_status()
            self.manager.broadcast_ui()

        except Exception as e:
            self.log(f"QR Login worker exception: {e}", level="error")
            self.plugin._qr_status = "error"
            self.plugin._emit_qr_status()
            self.manager.broadcast_ui()

    def refresh_tokens_sync(self):
        now = time.time()
        if self.plugin._refreshing_tokens or (now - self.plugin._last_refresh_time < 10): return False
        self.plugin._refreshing_tokens = True
        self.plugin._last_refresh_time = now
        self.log("Starting automatic token refresh cycle...")
        try:
            x_token = None
            if AUTH_FILE.exists():
                with open(AUTH_FILE, "r") as f:
                    for line in f:
                        if line.startswith("YANDEX_TOKEN="):
                            x_token = line.split("=")[1].strip()
            if not x_token: return False
            interfaces = get_all_interfaces()
            zeroconf = Zeroconf(interfaces=interfaces)
            discovery = SpeakerDiscovery()
            browser = ServiceBrowser(zeroconf, "_yandexio._tcp.local.", discovery)
            time.sleep(7)
            browser.cancel()
            zeroconf.close()
            local_devices = discovery.found_devices
            headers = {"Authorization": f"OAuth {x_token}", "X-Yandex-Token": x_token, "User-Agent": "Mozilla/5.0"}
            r = requests.get("https://quasar.yandex.net/glagol/device_list", headers=headers, timeout=10)
            if r.status_code != 200: r = requests.get("https://quasar.yandex.ru/glagol/device_list", headers=headers, timeout=10)
            if r.status_code != 200: return False
            quasar_list = r.json().get("devices", [])
            new_results = {}
            for q_dev in quasar_list:
                d_id = q_dev.get("id", "").strip()
                if not d_id: continue
                g_token = q_dev.get("glagol_token") or q_dev.get("glagol", {}).get("token")
                if not g_token:
                    url_single = f"https://quasar.yandex.ru/glagol/token?device_id={d_id}&platform={q_dev.get('platform')}"
                    try:
                        r_s = requests.get(url_single, headers=headers, timeout=5)
                        if r_s.status_code == 200: g_token = r_s.json().get("token")
                    except: pass
                if not g_token: continue
                ip = local_devices.get(d_id, {}).get("ip") or self.plugin.devices.get(d_id, {}).get("ip")
                if ip:
                    new_results[d_id] = {"name": q_dev.get("name", self.plugin.i18n("speaker")).strip(), "glagol_token": g_token, "platform": q_dev.get("platform"), "ip": ip}
            if new_results:
                with open(TOKENS_FILE, "w", encoding="utf-8") as f: json.dump(new_results, f, ensure_ascii=False, indent=2)
                self.plugin.devices = new_results
                return True
        except Exception as e: self.log(f"Auto-refresh exception: {e}", level="error")
        finally: self.plugin._refreshing_tokens = False
        return False
