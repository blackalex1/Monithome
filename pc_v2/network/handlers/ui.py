import os
import json
import logging
from .base import check_auth
from core.config import config_manager
from plugin_engine.manager import plugin_manager

logger = logging.getLogger("SocketHandlers.UI")

async def get_ui_config_data():
    cfg = config_manager.get()
    lang = cfg.language or "ru"
    
    translations = {}
    # Путь к языкам относительно корня проекта
    lang_path = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(__file__))), "web", "languages", f"{lang}.json")
    if os.path.exists(lang_path):
        try:
            with open(lang_path, "r", encoding="utf-8") as f:
                translations = json.load(f)
        except Exception as e:
            logger.error(f"Error loading translation for {lang}: {e}")

    plugins_configs = []
    plugins_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(__file__))), "plugins")
    if os.path.exists(plugins_dir):
        for d in os.listdir(plugins_dir):
            if os.path.isdir(os.path.join(plugins_dir, d)):
                p = plugin_manager.active_plugins.get(d)
                p_cfg = {}
                if p:
                    p_cfg = p.get_config()
                else:
                    p_cfg_path = os.path.join(plugins_dir, d, "config.json")
                    if os.path.exists(p_cfg_path):
                        with open(p_cfg_path, "r", encoding="utf-8") as f:
                            p_cfg = json.load(f)
                
                if p_cfg:
                    p_cfg["active"] = d in plugin_manager.active_plugins
                    p_id = p_cfg.get("id", d)
                    p_cfg["name"] = translations.get(f"plugin_name_{p_id}", p_cfg.get("name", p_id))
                    p_cfg["description"] = translations.get(f"plugin_desc_{p_id}", p_cfg.get("description", ""))
                    plugins_configs.append(p_cfg)
    
    color = cfg.theme_color if hasattr(cfg, 'theme_color') else "0xFF22C55E"
    return {
        "plugins": plugins_configs,
        "theme_color": color,
        "translations": translations,
        "language": lang
    }

def register_ui_handlers(sio):
    @sio.on("get_ui_config")
    async def handle_get_ui_config(sid):
        if sid is not None and not await check_auth(sio, sid): return
        data = await get_ui_config_data()
        await sio.emit("ui_config", data, room=sid or 'authorized')

    @sio.on("get_manager_data")
    async def handle_get_manager_data(sid):
        if sid is not None and not await check_auth(sio, sid): return
        plugins = []
        cfg = config_manager.get()
        plugins_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(__file__))), "plugins")
        if os.path.exists(plugins_dir):
            for d in os.listdir(plugins_dir):
                if os.path.isdir(os.path.join(plugins_dir, d)):
                    is_active = d in cfg.active_plugins
                    plugins.append({"id": d, "active": is_active})
        await sio.emit("manager_data", plugins, room=sid)
