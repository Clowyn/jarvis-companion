"""
Autonomous In-Game Chat AI Agent for the Jarvis Minecraft Companion.
Powered by Google Gemini 2.0 Flash with instant deterministic execution
for companion controls, ore scanning, time/weather commands, and player stats.
"""

from __future__ import annotations

import asyncio
import json
import math
import os
import random
import re
import sys
import time
from collections import Counter
from typing import Any, Dict, List, Optional


def get_cardinal_direction(dx: float, dz: float) -> str:
    """Calculates Turkish cardinal compass direction from delta X and delta Z."""
    # In Minecraft: -Z is North, +Z is South, +X is East, -X is West
    angle = math.atan2(dx, -dz)
    degrees = (math.degrees(angle) + 360) % 360
    directions = [
        ("Kuzey", 0), ("Kuzeydogu", 45), ("Dogu", 90), ("Guneydogu", 135),
        ("Guney", 180), ("Guneybati", 225), ("Bati", 270), ("Kuzeybati", 315)
    ]
    best_dir = "Kuzey"
    min_diff = 360
    for name, deg in directions:
        diff = abs(degrees - deg)
        if diff > 180:
            diff = 360 - diff
        if diff < min_diff:
            min_diff = diff
            best_dir = name
    return best_dir

# Force UTF-8 encoding for Windows terminal
try:
    if sys.stdout.encoding != "utf-8":
        sys.stdout.reconfigure(encoding="utf-8")
    if sys.stderr.encoding != "utf-8":
        sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

from jarvis_bridge.client import JarvisClient


def safe_print(msg: str):
    """Prints safely to console regardless of Windows terminal encoding."""
    try:
        print(msg)
    except Exception:
        try:
            encoding = sys.stdout.encoding or "utf-8"
            print(msg.encode(encoding, errors="replace").decode(encoding))
        except Exception:
            pass


def sanitize_for_minecraft(text: str) -> str:
    """Normalizes Turkish characters to ASCII-compatible letters to prevent Minecraft font glyph corruption."""
    char_map = {
        "İ": "I", "ı": "i",
        "ş": "s", "Ş": "S",
        "ğ": "g", "Ğ": "G",
        "ü": "u", "Ü": "U",
        "ö": "o", "Ö": "O",
        "ç": "c", "Ç": "C",
    }
    for tr, asc in char_map.items():
        text = text.replace(tr, asc)
    return text


def format_ore_name(raw_name: str) -> str:
    """Translates raw Minecraft & modded block IDs into clean Turkish ore names."""
    s = raw_name.lower()
    if ":" in s:
        s = s.split(":", 1)[1]
    s = s.replace("deepslate_", "").replace("_ore", "").replace("ore_", "")
    translations = {
        "coal": "Komur",
        "iron": "Demir",
        "copper": "Bakir",
        "gold": "Altin",
        "diamond": "Elmas",
        "emerald": "Zumrut",
        "redstone": "Kiziltas",
        "lapis": "Lapis",
        "ancient_debris": "Antik Kalinti",
        "debris": "Antik Kalinti",
        "quartz": "Kuvars",
        "sulfur": "Kukurt",
        "lead": "Kursun",
        "tin": "Kalay",
        "silver": "Gumus",
        "nickel": "Nikel",
        "zinc": "Cinko",
        "uraninite": "Uranyum",
        "uranium": "Uranyum",
        "osmium": "Osmiyum",
        "sal_ammoniac": "Sal Ammoniac",
        "dark": "Karanlik Cevher",
        "certus": "Certus Kuvars",
        "fluorite": "Florit",
        "aluminum": "Aluminyum",
    }
    for k, v in translations.items():
        if k in s:
            return v
    return s.replace("_", " ").title()


class JarvisChatAgent:
    def __init__(self, api_url: Optional[str] = None, client: Optional[JarvisClient] = None):
        # Load .env if present
        env_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), ".env")
        if os.path.exists(env_path):
            try:
                with open(env_path, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if line and not line.startswith("#") and "=" in line:
                            k, v = line.split("=", 1)
                            key = k.strip()
                            if key not in os.environ:
                                os.environ[key] = v.strip()
            except Exception as e:
                safe_print(f"[ChatAgent] Warning reading .env: {e}")

        import socket

        def is_port_open(host: str, port: int) -> bool:
            try:
                with socket.create_connection((host, port), timeout=0.3):
                    return True
            except OSError:
                return False

        self._is_port_open = is_port_open

        # If localhost:25585 is open (singleplayer active), default to localhost
        if "JARVIS_API_URL" not in os.environ and is_port_open("localhost", 25585):
            api_url = "http://localhost:25585"
            safe_print("[ChatAgent] Local singleplayer Minecraft detected on port 25585!")
        elif not api_url and "JARVIS_API_URL" not in os.environ:
            last_url_file = os.path.join(os.path.dirname(os.path.dirname(__file__)), ".last_url")
            if os.path.exists(last_url_file):
                try:
                    with open(last_url_file, "r", encoding="utf-8") as f:
                        saved = f.read().strip()
                        if saved:
                            api_url = saved
                except Exception:
                    pass

        self.api_url = api_url or (client.base_url if client else os.environ.get("JARVIS_API_URL", "http://localhost:25585"))
        self.client = client or JarvisClient(base_url=self.api_url)
        self.last_seen_id = 0
        self.running = False
        self.last_owner = "AlcyoneDX"
        self.companion_was_spawned = False
        self.death_recovery_in_progress = False
        self.eating_in_progress = False
        self.last_eat_time = 0.0
        self.poll_counter = 0

        # Memory & Waypoints
        self.memory_file = os.path.join(os.path.dirname(os.path.dirname(__file__)), "memory.json")
        self.memory = self._load_memory()
        self.marked_chest_positions: List[Dict[str, int]] = self.memory.get("marked_chests", [])
        self.tts_enabled: bool = self.memory.get("tts_enabled", False)

        # Radar & Bodyguard Reflexes
        self.guard_mode = True
        self.sentry_post: Optional[tuple[int, int, int]] = None
        self.activity_state = "Takipte"
        self.players_last_known: Dict[str, Dict[str, Any]] = {}
        self.guide_target: Optional[Dict[str, Any]] = None
        self.last_creeper_warn_time = 0.0
        self.last_attack_time = 0.0
        self.last_radar_scan_time = 0.0

        self.gemini_key = os.environ.get("GEMINI_API_KEY")
        self.gemini_chat = None

        if self.gemini_key:
            default_model = os.environ.get("GEMINI_MODEL", "gemini-2.0-flash")
            safe_print(f"[ChatAgent] [Gemini] Google Gemini API Key active! Default Model: {default_model} (15 RPM)")
            try:
                from google import genai
                from google.genai import types

                self.genai_client = genai.Client(api_key=self.gemini_key)
                self._setup_gemini_chat(types, model_name=default_model)
                safe_print("[ChatAgent] [Gemini] Autonomous Tool-Calling Chat Session initialized!")
            except Exception as e:
                safe_print(f"[ChatAgent] Error initializing Gemini: {e}")
                self.genai_client = None
        else:
            self.genai_client = None
            safe_print("[ChatAgent] [*] Fast Deterministic Turkish NLP Engine active (No API key).")

    def _setup_gemini_chat(self, types, model_name: Optional[str] = None):
        if not model_name:
            model_name = os.environ.get("GEMINI_MODEL", "gemini-2.0-flash")
        self.gemini_model = model_name
        client = self.client

        async def follow_player(player: str, distance: float = 2.5) -> str:
            """Commands the Jarvis Companion to navigate to and follow a player."""
            try:
                st = await client.get_status()
                p = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
                c_st = await client.companion_status()
                if not c_st.spawned and p:
                    await client.companion_spawn(name="Jarvis", x=p.x, y=p.y, z=p.z, dimension=p.dimension)
                elif p:
                    await client.companion_action("teleport", x=p.x + 1.0, y=p.y, z=p.z + 1.0)
                await client.companion_follow(player=player, distance=distance)
                return f"Jarvis {player} adli oyuncunun yanina geldi ve takip ediyor."
            except Exception as e:
                return f"Takip hatasi: {e}"

        async def stop_companion() -> str:
            """Commands the Jarvis Companion to stop moving and wait at its current location."""
            try:
                await client.companion_stop()
                return "Jarvis durduruldu ve oldugu yerde bekliyor."
            except Exception as e:
                return f"Durdurma hatasi: {e}"

        async def bring_companion_to_player(player: str) -> str:
            """Brings or teleports the Jarvis Companion directly to the player."""
            return await follow_player(player=player, distance=2.5)

        async def scan_surroundings(player: str, radius: int = 16) -> str:
            """Scans nearby blocks and valuable ores in a cubic radius around the target player."""
            try:
                rad = max(8, min(radius, 32))
                res = await client.get_surroundings(player=player, radius=rad)
                blocks = res.blocks or []
                ores = [
                    format_ore_name(b.name or b.block or b.id or "")
                    for b in blocks
                    if (b.category == "ore" or "ore" in (b.block or "").lower() or "debris" in (b.block or "").lower())
                ]
                if ores:
                    counts = Counter(ores)
                    summary = ", ".join([f"{count}x {name}" for name, count in counts.most_common(6)])
                    return f"{rad} blok alanda tespit edilen madenler: {summary}"
                return f"{rad} blok yakin cevrede degerli maden bulunamadi."
            except Exception as e:
                return f"Tarama hatasi: {e}"

        async def get_player_status(player: str) -> str:
            """Gets current player health, food level, world coordinates, and dimension."""
            try:
                st = await client.get_status()
                p = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
                if p:
                    return f"Oyuncu: {p.name}, Can: {int(p.health or 20)}/20, Aclik: {p.food_level}/20, Konum: ({int(p.x or 0)}, {int(p.y or 0)}, {int(p.z or 0)}), Boyut: {p.dimension}"
                return f"Oyuncu {player} su an sunucuda bulunamadi."
            except Exception as e:
                return f"Durum hatasi: {e}"

        async def execute_command(command: str) -> str:
            """Executes an in-game Minecraft console command."""
            try:
                res = await client.execute_command(command.lstrip("/"))
                return f"Komut calistirildi: {command} (Basari: {res.success})"
            except Exception as e:
                return f"Komut hatasi: {e}"

        async def organize_storage(player: str, radius: int = 32) -> str:
            """Organizes all base containers into dedicated category chests (ores, food, blocks, equipment, farming, materials) and stacks new double chests when full."""
            try:
                st = await client.get_status()
                p = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
                px, py, pz = (int(p.x or 0), int(p.y or 0), int(p.z or 0)) if p else (None, None, None)
                res = await client.container_organize(x=px, y=py, z=pz, radius=radius, player=player)
                if res.success:
                    return f"Depo basariyla organize edildi. Toplam {res.total_items_organized} adet esya ayrildi. Dolan sandiklarin uzerine {res.new_chests_placed} yeni double chest yerlestirildi Efendim."
                return f"Depo duzenleme hatasi: {res.error or 'Basarisiz'}"
            except Exception as e:
                return f"Depo organize hatasi: {e}"

        sys_instruction = (
            "Sen Tony Stark'in efsanevi yapay zekasi JARVIS'sin. Su an Minecraft dunyasindasin. "
            "Karakterin: Son derece kibar, sadik, profesyonel, zeki ve net. "
            "Ayni zamanda 'FTB Presents Direwolf20 1.21' (NeoForge 1.21.1) modpaketi konusunda en ust duzey teknik uzmansin.\n\n"
            "MODPACK ORTAMI VE TEKNIK BILGI:\n"
            "- Modpaketi: FTB Presents Direwolf20 1.21 (v1.21.0, NeoForge 21.1.251, tam olarak 363 mod icerir).\n"
            "- Temel Modlar ve Ekosistem:\n"
            "  * Mekanism 10.7 (mekanism): 3x/4x/5x cevher zenginlestirme, gazlar, fisyon/fuzyon reaktorleri, Mekasuit.\n"
            "  * Applied Energistics 2 (ae2): ME dijital depolama, otomasyon, molekuler birlestiriciler, kablo aglari.\n"
            "  * Create 6.0 (create): Kinetik enerji, donme gucu (SU/RPM), konveyorler, trenler ve mekanik otomasyon.\n"
            "  * Just Dire Things (justdirethings): Direwolf20'nin ozel modu; gelismis aletler, blok kiricilar/yerlestiriciler, kablosuz sensorler ve T2/T3/T4 enerji aletleri.\n"
            "  * Powah 6.2 (powah): Reaktörler, enerjilendirme kureleri (energizing orb), kablosuz sarj ve yuksek kapasiteli bataryalar.\n"
            "  * Ars Nouveau (ars_nouveau): Buyu glifleri, buyulu otomasyon, starbuncle ve bookwyrm yardimcilari.\n"
            "  * Ender IO 8.2 (enderio): Kompakt konduitler (conduits), makineler, gelismis kapasitorler.\n"
            "  * Sophisticated Storage & Functional Storage: Sandik/varil gelistirmeleri ve depolama cekmeceleri (drawers).\n"
            "  * Oritech (oritech): Modern 1.21 endustriyel makineleri, partikul hizlandiricilar ve lazer enerji sistemleri.\n"
            "  * Occultism (occultism): Boyutsal depolama, demon cagirma ve maden cinleri.\n"
            "  * Industrial Foregoing (industrialforegoing): Lateks/plastik uretimi, otomatik tarim ve lazer madencilik.\n\n"
            "KUBEJS MODPACK BILGISI:\n"
            "- Modpack KubeJS sunucu scriptleri ozel tarifler ve unifikasyonlar icerir (mod_specific dizini):\n"
            "  * EnderIO yogun ME kablolari (ae2_cables.js)\n"
            "  * Create rose quartz metallurgic infuser ve maden yikama unifikasyonlari (create.js)\n"
            "  * Oritech, Extreme Reactors ve Integrated Dynamics tarif overrides.\n\n"
            "KESINLIKLE YASAK / ESKI MODLAR (KRITIK KURAL):\n"
            "- Asla Minecraft 1.12 veya 1.16 doneminde kalan eski veya modpack'te olmayan modlari ONERME!\n"
            "- Kesinlikle yasakli eski modlar: IndustrialCraft 2 (IC2), BuildCraft, Thermal Expansion 1.12, Forestry, Project Red, eski RedPower.\n"
            "- Bu modlar Minecraft 1.21 NeoForge surumunde mevcut degildir. Otomasyon, enerji veya teknik cozumlerde yalnizca modpack'teki guncel 1.21 modlarini oner.\n\n"
            "KURALLAR:\n"
            "1. Yanitlarin KISA, OZ, teknik acidan kusursuz ve agirbasli olmali. Asla gereksiz edebiyat veya gevezelik yapma.\n"
            "2. Daima 'Efendim' diye hitap et.\n"
            "3. Maden taramasi veya sayimi istendiginde tespit edilen madenleri tek cumlede ozetle (Orn: '16 blokta sunlar tespit edildi: 12x Demir, 4x Elmas Efendim.'). Asla sadece 'tamamlandi' deyip kesme.\n"
            "4. Yanitlarini daima Turkce ver."
        )

        self.gemini_chat = self.genai_client.aio.chats.create(
            model=self.gemini_model,
            config=types.GenerateContentConfig(
                tools=[
                    follow_player,
                    stop_companion,
                    bring_companion_to_player,
                    scan_surroundings,
                    get_player_status,
                    execute_command,
                    organize_storage,
                ],
                system_instruction=sys_instruction,
                temperature=0.3,
            ),
        )

    async def start(self):
        self.running = True
        safe_print("================================================================================")
        safe_print("[Jarvis] Autonomous In-Game Chat Agent is RUNNING!")
        safe_print(f"[Jarvis] Connected to: {self.api_url}")
        safe_print("[Jarvis] Chat triggers: '!jarvis <mesaj>' or '@jarvis' or 'jarvis <mesaj>'")
        safe_print("================================================================================")

        try:
            recent = await self.client.get_chat(limit=10, since=0)
            if recent:
                self.last_seen_id = recent[-1].id
                safe_print(f"[ChatAgent] Initialized at chat ID: {self.last_seen_id} (skipping {len(recent)} old messages)")
        except Exception as e:
            safe_print(f"[ChatAgent] Initial chat fetch warning: {e}")

        try:
            await self._say("Iyi gunler Efendim, emrinizdeyim.")
        except Exception:
            pass

        while self.running:
            try:
                await self._poll_and_process()
            except Exception as e:
                safe_print(f"[ChatAgent] Error in poll loop: {e}")
            await asyncio.sleep(1.0)

    async def _say(self, msg: str):
        sanitized = sanitize_for_minecraft(msg)
        await self.client.say(sanitized)
        if getattr(self, "tts_enabled", False):
            try:
                from jarvis_bridge.tts import speak
                speak(msg)
            except Exception as e:
                safe_print(f"[ChatAgent] TTS warning: {e}")

    def _load_memory(self) -> Dict[str, Any]:
        """Loads persistent waypoints and base memory from disk."""
        if hasattr(self, "memory_file") and os.path.exists(self.memory_file):
            try:
                with open(self.memory_file, "r", encoding="utf-8") as f:
                    return json.load(f)
            except Exception:
                pass
        return {"waypoints": {}}

    def _save_memory(self):
        """Saves persistent memory to disk."""
        try:
            with open(self.memory_file, "w", encoding="utf-8") as f:
                json.dump(self.memory, f, indent=2, ensure_ascii=False)
        except Exception as e:
            safe_print(f"[ChatAgent] Error saving memory: {e}")

    async def _save_waypoint(self, player: str, name: str) -> str:
        """Saves current player location to persistent memory."""
        st = await self.client.get_status()
        p_obj = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
        if not p_obj:
            return "Konumunuz alinamadigi icin kayit yapilamadi Efendim."

        clean_name = name.strip().title()
        key = clean_name.lower()
        self.memory.setdefault("waypoints", {})[key] = {
            "name": clean_name,
            "x": int(p_obj.x or 0),
            "y": int(p_obj.y or 0),
            "z": int(p_obj.z or 0),
            "dimension": getattr(p_obj, "dimension", "minecraft:overworld"),
            "saved_by": player,
            "time": int(time.time()),
        }
        self._save_memory()
        return f"'{clean_name}' konumu hafizama kaydedildi Efendim (X: {int(p_obj.x)}, Y: {int(p_obj.y)}, Z: {int(p_obj.z)})."

    async def _where_is_waypoint(self, player: str, name: str) -> Optional[str]:
        """Calculates distance, direction, and coordinates to a saved waypoint."""
        waypoints = self.memory.get("waypoints", {})
        key = name.strip().lower()
        wp = waypoints.get(key)
        if not wp:
            for k, v in waypoints.items():
                if k in key or key in k:
                    wp = v
                    break
        if not wp:
            return None

        try:
            st = await self.client.get_status()
            p_obj = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
            if not p_obj:
                return f"'{wp['name']}' konumu: (X: {wp['x']}, Y: {wp['y']}, Z: {wp['z']}) Efendim."
            dx = wp["x"] - int(p_obj.x or 0)
            dz = wp["z"] - int(p_obj.z or 0)
            dist = int(math.hypot(dx, dz))
            direction = get_cardinal_direction(dx, dz)
            return f"'{wp['name']}' konumu: {dist} blok {direction} yonunde (X: {wp['x']}, Y: {wp['y']}, Z: {wp['z']}) Efendim."
        except Exception:
            return f"'{wp['name']}' konumu: (X: {wp['x']}, Y: {wp['y']}, Z: {wp['z']}) Efendim."

    async def _list_waypoints(self) -> str:
        """Lists all saved waypoints in persistent memory."""
        waypoints = self.memory.get("waypoints", {})
        if not waypoints:
            return "Hafizamda henuz kayitli bir konum bulunmuyor Efendim."
        items = [f"{v['name']} (X: {v['x']}, Y: {v['y']}, Z: {v['z']})" for v in waypoints.values()]
        return "Kayitli Konumlar: " + ", ".join(items) + " Efendim."

    async def _delete_waypoint(self, name: str) -> str:
        """Deletes a waypoint from persistent memory."""
        key = name.strip().lower()
        waypoints = self.memory.get("waypoints", {})
        if key in waypoints:
            del waypoints[key]
            self._save_memory()
            return f"'{name}' konumu hafizadan silindi Efendim."
        return f"Hafizada '{name}' adinda bir konum bulunamadi."

    async def _navigate_to_waypoint(self, player: str, name: str) -> str:
        """Moves or teleports companion to a saved waypoint."""
        waypoints = self.memory.get("waypoints", {})
        key = name.strip().lower()
        wp = waypoints.get(key)
        if not wp:
            for k, v in waypoints.items():
                if k in key or key in k:
                    wp = v
                    break
        if not wp:
            return f"Hafizamda '{name}' adinda bir konum bulunamadi Efendim."

        tx = float(wp["x"])
        ty = float(wp["y"])
        tz = float(wp["z"])
        try:
            await self.client.companion_action("teleport", x=tx, y=ty, z=tz)
            return f"'{wp['name']}' konumuna intikal ettim Efendim (X: {wp['x']}, Y: {wp['y']}, Z: {wp['z']})."
        except Exception as e:
            return f"Konuma giderken hata: {e}"

    async def _start_sentry_mode(self, player: str) -> str:
        """Stations Jarvis as a stationary sentry guard at current position."""
        st = await self.client.get_status()
        p_obj = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
        c_st = await self.client.companion_status()
        pos = c_st.position
        if pos and getattr(pos, "x", None) is not None:
            sx, sy, sz = int(pos.x), int(pos.y), int(pos.z)
        elif p_obj:
            sx, sy, sz = int(p_obj.x or 0), int(p_obj.y or 0), int(p_obj.z or 0)
        else:
            sx, sy, sz = 0, 64, 0

        self.sentry_post = (sx, sy, sz)
        self.activity_state = "Nobetci"
        self.guard_mode = True

        try:
            await self.client.companion_stop()
        except Exception:
            pass
        return f"Nobetci protokolleri aktif Efendim. ({sx}, {sy}, {sz}) noktasinda mevzilendim, 32 blokluk alani tum tehditlere karsi koruyorum."

    async def _stop_sentry_mode(self, player: str) -> str:
        """Resumes player following and disengages stationary sentry mode."""
        self.sentry_post = None
        self.activity_state = "Takipte"
        try:
            await self.client.companion_follow(player=player, distance=2.5)
        except Exception:
            pass
        return "Nobet tamamlandi Efendim. Yaniniza dondum, takipteyim."

    async def _start_guiding(self, player: str, destination_name: str) -> str:
        """Starts walking towards a destination (home or corpse) while guiding the player."""
        waypoints = self.memory.get("waypoints", {})
        key = destination_name.strip().lower()
        wp = waypoints.get(key)
        if not wp:
            for k, v in waypoints.items():
                if key in k or k in key:
                    wp = v
                    break
        if not wp:
            if "ev" in key:
                return "Hafizamda kayitli bir 'Ev' konumu bulunamadi Efendim. 'burayi ev olarak kaydet' diyerek kaydedebilirsiniz."
            if "olum" in key or "ceset" in key:
                return "Hafizamda kayitli bir olum noktasi bulunamadi Efendim."
            return f"Hafizamda '{destination_name}' adinda bir konum bulunamadi Efendim."

        st = await self.client.get_status()
        p_obj = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
        px = int(p_obj.x or 0) if p_obj else 0
        pz = int(p_obj.z or 0) if p_obj else 0

        tx = float(wp["x"])
        ty = float(wp["y"])
        tz = float(wp["z"])

        dx = tx - px
        dz = tz - pz
        dist = int(math.hypot(dx, dz))
        direction = get_cardinal_direction(dx, dz)

        self.guide_target = {
            "name": wp["name"],
            "x": tx,
            "y": ty,
            "z": tz,
            "player": player,
            "last_step_time": 0.0,
            "waiting_for_player": False,
        }
        self.activity_state = f"{wp['name']} Rehberligi"

        # Make sure companion is spawned
        c_st = await self.client.companion_status()
        if not c_st.spawned:
            if p_obj:
                await self.client.companion_spawn(name="Jarvis", x=p_obj.x, y=p_obj.y, z=p_obj.z, dimension=p_obj.dimension)
            else:
                await self.client.companion_spawn(name="Jarvis")
            await asyncio.sleep(0.3)

        if "olum" in key or "ceset" in key:
            return f"Vefat noktaniza onculuk ediyorum Efendim, lutfen beni takip edin! ({dist} blok {direction} yonunde)."
        return f"Beni takip edin Efendim, {wp['name']} noktasina gidiyoruz! ({dist} blok {direction} yonunde). Yolu gosteriyorum."

    async def _guide_step(self):
        """Advances one step of walking and guiding the player towards destination."""
        if not self.guide_target:
            return

        now = asyncio.get_event_loop().time()
        if now - self.guide_target.get("last_step_time", 0.0) < 1.4:
            return
        self.guide_target["last_step_time"] = now

        player = self.guide_target["player"]
        tx = self.guide_target["x"]
        ty = self.guide_target["y"]
        tz = self.guide_target["z"]
        tname = self.guide_target["name"]

        try:
            st = await self.client.get_status()
            p_obj = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
            c_st = await self.client.companion_status()
            if not c_st.spawned:
                return

            c_pos = c_st.position
            cx = float(getattr(c_pos, "x", 0.0) if c_pos else 0.0)
            cy = float(getattr(c_pos, "y", 0.0) if c_pos else 0.0)
            cz = float(getattr(c_pos, "z", 0.0) if c_pos else 0.0)

            # 1. Check distance to target
            dist_to_target = math.hypot(tx - cx, tz - cz)
            if dist_to_target <= 4.5:
                # Reached destination!
                if "olum" in tname.lower() or "ceset" in tname.lower():
                    await self._say("Vefat noktaniza ulastik Efendim. Esyalariniz burada olmali.")
                else:
                    await self._say(f"'{tname}' noktasina ulastik Efendim! Guvenli bir yolculuk oldu.")

                try:
                    await self.client.execute_command("execute at @e[type=jarvis:companion,limit=1] run playsound minecraft:entity.player.levelup player @a ~ ~ ~ 1.0 1.2")
                except Exception:
                    pass

                self.guide_target = None
                self.activity_state = "Takipte"
                await self.client.companion_follow(player=player, distance=2.5)
                return

            # 2. Check distance to player
            if p_obj:
                px = float(p_obj.x or 0.0)
                pz = float(p_obj.z or 0.0)
                dist_to_player = math.hypot(px - cx, pz - cz)

                if dist_to_player > 16.0:
                    # Player is falling behind
                    if not self.guide_target.get("waiting_for_player", False):
                        self.guide_target["waiting_for_player"] = True
                        await self.client.companion_stop()
                        await self._say("Efendim biraz geride kaldiniz, sizi bekliyorum...")
                    return
                elif self.guide_target.get("waiting_for_player", False) and dist_to_player <= 8.0:
                    # Player caught up
                    self.guide_target["waiting_for_player"] = False
                    await self._say("Harika, yolumuza devam ediyoruz Efendim.")

            # 3. Walk towards target (step forward by up to 16 blocks along the vector)
            step_dist = min(dist_to_target, 16.0)
            ratio = step_dist / dist_to_target if dist_to_target > 0 else 1.0
            next_x = cx + (tx - cx) * ratio
            next_z = cz + (tz - cz) * ratio

            await self.client.companion_move(float(next_x), float(cy), float(next_z), speed=1.2)

        except Exception as e:
            safe_print(f"[ChatAgent] Guide step error: {e}")

    async def _scan_chests(self, player: str) -> str:
        """Scans for nearby chests, barrels, and storage containers across 128 blocks width."""
        res = await self.client.get_surroundings(player=player, radius=128)
        blocks = res.blocks or []
        containers = [
            b for b in blocks
            if getattr(b, "category", "") == "container"
            or any(kw in (b.block or "").lower() for kw in ["chest", "barrel", "shulker", "box"])
        ]
        if not containers:
            return "128 blok genisliginde herhangi bir sandik veya fici bulunamadi Efendim."

        containers.sort(key=lambda b: getattr(b, "distance", 999.0) or 999.0)
        p_st = await self.client.get_status()
        p_obj = next((x for x in (p_st.players or []) if x.name.lower() == player.lower()), None)

        lines = []
        for c in containers[:6]:
            cx, cy, cz = int(c.x or 0), int(c.y or 0), int(c.z or 0)
            cname = c.block.split(":")[-1].replace("_", " ").title() if c.block else "Sandik"
            dist = int(getattr(c, "distance", 0.0) or 0.0)
            dir_str = ""
            if p_obj:
                dir_str = " " + get_cardinal_direction(cx - int(p_obj.x or 0), cz - int(p_obj.z or 0)) + " yonunde"
            lines.append(f"{cname} ({dist} blok{dir_str}, X:{cx}, Y:{cy}, Z:{cz})")

        return f"128 blok genisliginde tespit edilen sandiklar Efendim ({len(containers)} adet): " + ", ".join(lines) + "."

    async def _inspect_chest(self, player: str, target_name: str = "") -> str:
        """Inspects container items using NeoForge ItemHandler capability."""
        cx, cy, cz = None, None, None

        # Check saved chest in memory
        waypoints = self.memory.get("waypoints", {})
        for k, v in waypoints.items():
            if "sandik" in k or (target_name and target_name.lower() in k):
                cx, cy, cz = v["x"], v["y"], v["z"]
                break

        if cx is None:
            # Look for nearest chest in surroundings
            res = await self.client.get_surroundings(player=player, radius=16)
            containers = [
                b for b in (res.blocks or [])
                if getattr(b, "category", "") == "container"
                or any(kw in (b.block or "").lower() for kw in ["chest", "barrel", "shulker"])
            ]
            if containers:
                containers.sort(key=lambda b: getattr(b, "distance", 999.0) or 999.0)
                closest = containers[0]
                cx, cy, cz = int(closest.x or 0), int(closest.y or 0), int(closest.z or 0)

        if cx is None:
            return "Incelenecek yakin bir sandik bulunamadi Efendim. Lutfen bir sandigin yakinina gecin."

        # Move companion to chest
        try:
            await self.client.companion_move(float(cx), float(cy), float(cz), speed=1.2)
            await asyncio.sleep(0.8)
        except Exception:
            pass

        # Play chest open sound
        try:
            await self.client.execute_command(f"playsound minecraft:block.chest.open block @a {cx} {cy} {cz} 0.8 1.0")
        except Exception:
            pass

        # Call inspect_container
        try:
            insp_res = await self.client.companion_interact("inspect_container", x=cx, y=cy, z=cz)
            items = insp_res.details.get("items", []) if insp_res.details else []
            if not items:
                return f"({cx}, {cy}, {cz}) konumundaki sandik su an tamamen bos Efendim."

            counts = Counter()
            for it in items:
                iname = it.get("name") or it.get("item", "").split(":")[-1].replace("_", " ").title()
                counts[iname] += it.get("count", 1)

            summary = ", ".join([f"{cnt}x {n}" for n, cnt in counts.most_common(8)])
            return f"Sandik icerigi (X:{cx}, Y:{cy}, Z:{cz}): {summary} Efendim."
        except Exception as e:
            return f"Sandik incelenirken bir sorun olustu: {e}"

    async def _organize_storage(self, player: str, p: str = "") -> str:
        """Organizes all base containers into dedicated category double chests (Ores, Food, Blocks, Equipment, Farming, Materials).
        Automatically places a new stacked double chest on top whenever a category chest fills up!
        """
        # 1. Ensure companion is spawned
        try:
            st = await self.client.get_status()
            p_obj = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
            c_st = await self.client.companion_status()
            if not c_st.spawned and p_obj:
                await self.client.companion_spawn(name="Jarvis", x=p_obj.x, y=p_obj.y, z=p_obj.z, dimension=p_obj.dimension)
        except Exception:
            pass

        px, py, pz = None, None, None
        try:
            st = await self.client.get_status()
            p_obj = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
            if p_obj:
                px, py, pz = int(p_obj.x or 0), int(p_obj.y or 0), int(p_obj.z or 0)
        except Exception:
            pass

        try:
            res = await self.client.container_organize(x=px, y=py, z=pz, radius=36, player=player)
            if not res.success:
                # Fallback to in-place sorting
                return await self._sort_chests_in_place(player)

            # Play audio feedback
            try:
                await self.client.execute_command("execute at @e[type=jarvis:companion,limit=1] run playsound minecraft:block.chest.open block @a ~ ~ ~ 1.0 1.0")
                await asyncio.sleep(0.3)
                await self.client.execute_command("execute at @e[type=jarvis:companion,limit=1] run playsound minecraft:block.chest.close block @a ~ ~ ~ 1.0 1.0")
            except Exception:
                pass

            cat_summary = []
            if res.categories:
                for cat, count in res.categories.items():
                    if isinstance(count, dict):
                        cat_summary.append(f"{cat}: {count.get('items', 0)}")
                    else:
                        cat_summary.append(f"{cat}: {count}")
            summary_str = f" ({', '.join(cat_summary)})" if cat_summary else ""

            stack_str = f" Dolan sandiklarin uzerine {res.new_chests_placed} adet yeni cift sandik yerlestirildi." if res.new_chests_placed > 0 else ""

            return (
                f"Etraftaki {res.containers_scanned or 'tum'} adet sandik basariyla organize edildi ve duzenlendi. "
                f"Toplam {res.total_items_organized} adet esya (madenler, yemekler, bloklar) kategorilerine gore ayrildi ve istiflendi Efendim.{summary_str}{stack_str}"
            )
        except Exception as e:
            return f"Depo duzenlenirken bir hata olustu: {e}"

    def _calculate_target_block(self, px: float, py: float, pz: float, yaw: float, pitch: float) -> tuple[int, int, int]:
        """Calculates the target floor or looked-at block coordinate based on player pose."""
        if pitch > 12.0:
            rad_yaw = math.radians(yaw)
            rad_pitch = math.radians(pitch)
            dx = -math.sin(rad_yaw) * math.cos(rad_pitch)
            dy = -math.sin(rad_pitch)
            dz = math.cos(rad_yaw) * math.cos(rad_pitch)
            eye_y = py + 1.62
            if abs(dy) > 0.05:
                t = (py - eye_y) / dy
                if 0.5 <= t <= 6.0:
                    tx = int(math.floor(px + t * dx))
                    tz = int(math.floor(pz + t * dz))
                    return tx, int(round(py)), tz
        return int(math.floor(px)), int(round(py)), int(math.floor(pz))

    async def _handle_marker_command(self, player: str, p: str) -> str:
        """Handles target storage marker creation, display, and clearing."""
        if any(w in p for w in ["temizle", "sifirla", "sıfırla", "sil"]):
            self.marked_chest_positions = []
            self.memory["marked_chests"] = []
            self._save_memory()
            try:
                await self.client.container_clear_markers()
                await self.client.execute_command("playsound minecraft:block.note_block.bass block @a ~ ~ ~ 1.0 0.8")
            except Exception:
                pass
            return "Tum hedef sandik isaretleri temizlendi Efendim."

        if any(w in p for w in ["goster", "göster", "nerede", "parlat", "listele"]):
            markers = list(self.marked_chest_positions)
            try:
                server_markers = await self.client.container_get_markers()
                for sm in server_markers:
                    if sm not in markers:
                        markers.append(sm)
            except Exception:
                pass
            if not markers:
                return "Kayitli herhangi bir hedef sandik konumu bulunamadi Efendim."
            for m in markers:
                mx, my, mz = m["x"], m["y"], m["z"]
                try:
                    await self.client.execute_command(
                        f"execute at @a[name={player},limit=1] run particle minecraft:happy_villager {mx}+0.5 {my}+0.8 {mz}+0.5 0.3 0.3 0.3 0 15"
                    )
                except Exception:
                    pass
            return f"Kayitli {len(markers)} adet hedef sandik konumuna yesil isaret parcaciklari gonderildi Efendim."

        # Mark the position the player is looking at
        st = await self.client.get_status()
        p_obj = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
        if not p_obj:
            return "Konumunuzu belirleyemedim Efendim."

        px = float(p_obj.x or 0.0)
        py = float(p_obj.y or 0.0)
        pz = float(p_obj.z or 0.0)
        yaw = float(getattr(p_obj, "yaw", None) or 0.0)
        pitch = float(getattr(p_obj, "pitch", None) or 0.0)

        tx, ty, tz = self._calculate_target_block(px, py, pz, yaw, pitch)

        coord = {"x": tx, "y": ty, "z": tz}
        if coord in self.marked_chest_positions:
            return f"Bu konum ({tx}, {ty}, {tz}) zaten hedef sandik listesinde kayitli Efendim."

        self.marked_chest_positions.append(coord)
        self.memory["marked_chests"] = self.marked_chest_positions
        self._save_memory()

        idx = len(self.marked_chest_positions)
        try:
            await self.client.execute_command(
                f"particle minecraft:happy_villager {tx}+0.5 {ty}+0.8 {tz}+0.5 0.3 0.3 0.3 0 15"
            )
            await self.client.execute_command(
                f"playsound minecraft:block.note_block.chime block @a {tx} {ty} {tz} 1.0 1.4"
            )
        except Exception:
            pass

        return f"Hedef sandik #{idx} konumu ({tx}, {ty}, {tz}) olarak kaydedildi Efendim."

    async def _move_chests_to_markers(self, player: str) -> str:
        """Moves existing chests to player-designated marker locations using clone move."""
        markers = list(self.marked_chest_positions)
        try:
            srv_markers = await self.client.container_get_markers()
            for sm in srv_markers:
                if sm not in markers:
                    markers.append(sm)
        except Exception:
            pass

        if not markers:
            return "Henuz hic hedef sandik konumu isaretlemediniz Efendim."

        res = await self.client.get_surroundings(player=player, radius=32)
        blocks = res.blocks or []
        marked_coords = {(m["x"], m["y"], m["z"]) for m in markers}

        source_chests = []
        visited = set()
        for b in blocks:
            b_name = (b.name or b.block or b.id or "").lower()
            if any(m in b_name for m in ["furnace", "smoker", "blast", "brew", "generator", "crusher", "enrich", "press", "framed", "waystone", "basin", "pipe", "cable"]):
                continue
            if any(kw in b_name for kw in ["chest", "barrel", "shulker", "drawer", "crate", "cabinet"]):
                bx, by, bz = int(b.x or 0), int(b.y or 0), int(b.z or 0)
                if (bx, by, bz) not in marked_coords and (bx, by, bz) not in visited:
                    visited.add((bx, by, bz))
                    source_chests.append((bx, by, bz))

        if not source_chests:
            return "Tasinacak herhangi bir kaynak sandik bulunamadi Efendim."

        moved_count = 0
        for m in markers:
            tx, ty, tz = m["x"], m["y"], m["z"]
            if not source_chests:
                break
            sx, sy, sz = source_chests.pop(0)
            try:
                await self.client.execute_command(f"clone {sx} {sy} {sz} {sx} {sy} {sz} {tx} {ty} {tz} replace move")
                moved_count += 1
            except Exception as ex:
                safe_print(f"[ChatAgent] Move chest error: {ex}")

        self.marked_chest_positions = []
        self.memory["marked_chests"] = []
        self._save_memory()
        try:
            await self.client.container_clear_markers()
        except Exception:
            pass

        return f"Efendim, {moved_count} adet sandik isaretlediginiz yerlere basariyla tasindi."

    async def _transfer_chests_to_basement(self, player: str) -> str:
        """Instantly transfers items from upstairs chests into basement/depot chests."""
        res = await self.client.get_surroundings(player=player, radius=32)
        blocks = res.blocks or []
        containers = []
        for b in blocks:
            b_name = (b.name or b.block or b.id or "").lower()
            if any(m in b_name for m in ["furnace", "smoker", "blast", "brew", "generator", "crusher", "enrich", "press", "framed", "waystone", "basin", "pipe", "cable"]):
                continue
            if any(kw in b_name for kw in ["chest", "barrel", "shulker", "drawer", "crate", "cabinet"]):
                containers.append(b)

        if len(containers) < 2:
            return "Esyalari aktarmak icin hem kaynak hem hedef sandiklara ihtiyac var Efendim."

        wp_depo = self.memory.get("waypoints", {}).get("depo")
        target_chests = []
        source_chests = []

        if wp_depo:
            depo_x, depo_y, depo_z = wp_depo["x"], wp_depo["y"], wp_depo["z"]
            for c in containers:
                cx, cy, cz = int(c.x or 0), int(c.y or 0), int(c.z or 0)
                if math.hypot(cx - depo_x, cz - depo_z) <= 12 and abs(cy - depo_y) <= 4:
                    target_chests.append((cx, cy, cz))
                else:
                    source_chests.append((cx, cy, cz))
        else:
            min_y = min(int(c.y or 0) for c in containers)
            for c in containers:
                cx, cy, cz = int(c.x or 0), int(c.y or 0), int(c.z or 0)
                if cy <= min_y + 2:
                    target_chests.append((cx, cy, cz))
                else:
                    source_chests.append((cx, cy, cz))

        if not target_chests:
            return "Alt kattaki depoda hedef sandiklar bulunamadi Efendim."
        if not source_chests:
            return "Yukari katta tasinacak kaynak sandik bulunamadi Efendim."

        total_transferred = 0
        for sx, sy, sz in source_chests:
            for tx, ty, tz in target_chests:
                try:
                    xfer = await self.client.container_transfer(
                        source_x=sx, source_y=sy, source_z=sz,
                        target_x=tx, target_y=ty, target_z=tz,
                        filter="all"
                    )
                    if xfer.success and (xfer.transferred or 0) > 0:
                        total_transferred += (xfer.transferred or 0)
                except Exception:
                    pass

        return f"Efendim, yukaridaki sandiklardan {total_transferred} adet esya alt kattaki depoya tasindi."


    async def _handle_build_command(self, player: str, p: str) -> str:
        """Pillar 2: Autonomous Building Engine NLP Handler."""
        try:
            # Check for cancel
            if any(w in p for w in ["durdur", "iptal", "kes"]):
                res = await self.client.companion_build_cancel()
                if res.cancelled:
                    return "Insaat calismalari durduruldu ve iptal edildi Efendim."
                return "Aktif bir insaat gorevi bulunamadi Efendim."

            # Check for status
            if any(w in p for w in ["durum", "status", "ilerleme", "kac blok"]):
                st = await self.client.companion_build_status()
                if st.active and st.task:
                    pct = int(st.task.progress * 100)
                    return f"Insaat devam ediyor: {st.task.structure_type.upper()} (%{pct} tamamlandi, {st.task.placed_blocks}/{st.task.total_blocks} blok yerlestirildi)."
                return "Su anda aktif bir insaat bulunmuyor Efendim."

            # Ensure companion is spawned
            try:
                c_st = await self.client.companion_status()
                if not c_st.spawned:
                    st = await self.client.get_status()
                    p_obj = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
                    if p_obj:
                        await self.client.companion_spawn(name="Jarvis", x=p_obj.x, y=p_obj.y, z=p_obj.z, dimension=p_obj.dimension)
                    else:
                        await self.client.companion_spawn(name="Jarvis")
            except Exception:
                pass

            # Extract material if mentioned
            material = "minecraft:cobblestone"
            if "odun" in p or "tahta" in p or "ahsap" in p or "plank" in p:
                material = "minecraft:oak_planks"
            elif "tas" in p or "taş" in p or "stone" in p:
                material = "minecraft:stone"
            elif "deepslate" in p:
                material = "minecraft:cobbled_deepslate"
            elif "toprak" in p or "dirt" in p:
                material = "minecraft:dirt"

            # Structure matching
            if any(w in p for w in ["kopru", "köprü"]):
                m = re.search(r"(\d+)\s*(?:blok)?", p)
                length = int(m.group(1)) if m else 12
                res = await self.client.companion_build(
                    structure="bridge",
                    material=material,
                    length=length,
                    width=3,
                    railing=True,
                    torches=True,
                )
                if res.success and res.task:
                    return f"{length} blok uzunlugunda korkuluklu ve mesaleli kopru insaatina basliyorum Efendim ({res.task.total_blocks} blok)."
                return f"Kopru insaati baslatilamadi: {res.error or 'Hata'}"

            elif any(w in p for w in ["siginak", "sığınak", "barinak", "barınak", "bunker", "ev yap"]):
                m = re.search(r"(\d+)\s*(?:x\d+)?", p)
                size = int(m.group(1)) if m else 5
                res = await self.client.companion_build(
                    structure="shelter",
                    material=material,
                    size=size,
                    height=3,
                    door=True,
                    torches=True,
                )
                if res.success and res.task:
                    return f"Guvenli acil durum siginagi insaatina basliyorum Efendim ({size}x{size} ebatlarinda, {res.task.total_blocks} blok)."
                return f"Siginak insaati baslatilamadi: {res.error or 'Hata'}"

            elif any(w in p for w in ["duvar", "barikat"]):
                m = re.search(r"(\d+)\s*(?:blok)?", p)
                length = int(m.group(1)) if m else 9
                res = await self.client.companion_build(
                    structure="wall",
                    material=material,
                    length=length,
                    height=3,
                    crenellations=True,
                )
                if res.success and res.task:
                    return f"{length} blok uzunlugunda savunma barikati oruyorum Efendim ({res.task.total_blocks} blok)."
                return f"Barikat insaati baslatilamadi: {res.error or 'Hata'}"

            elif any(w in p for w in ["platform", "zemin", "taban"]):
                m = re.search(r"(\d+)(?:x(\d+))?", p)
                sx = int(m.group(1)) if m else 5
                sz = int(m.group(2)) if (m and m.group(2)) else sx
                res = await self.client.companion_build(
                    structure="platform",
                    material=material,
                    size_x=sx,
                    size_z=sz,
                )
                if res.success and res.task:
                    return f"{sx}x{sz} ebatinda platform insaatina basliyorum Efendim ({res.task.total_blocks} blok)."
                return f"Platform insaati baslatilamadi: {res.error or 'Hata'}"

            return "Insaat komutu anlasilamadi Efendim. (Kopru, siginak, duvar veya platform belirtebilirsiniz)."
        except Exception as e:
            return f"Insaat hatasi: {e}"

    async def _handle_recipe_query(self, player: str, p: str) -> str:
        """Pillar 4: Recipe lookup engine across vanilla and DW20 mods."""
        m = re.search(r"(.*?)\s*(?:nasil|nasıl|tarifi|yapilisi|yapılışı|craftlanir|craftlanır|yapilir|yapılır|uretilir|üretilir)", p)
        query = m.group(1).strip() if m else p
        for w in ["jarvis", "bana", "bir", "su", "şu", "bu"]:
            query = re.sub(rf"\b{w}\b", "", query).strip()

        if not query:
            return "Hangi esya veya blok icin tarif aradiginizi belirtir misiniz Efendim?"

        try:
            res = await self.client.modpack_recipes(item=query, limit=5)
            if not res.success or not res.recipes:
                if self.gemini_chat:
                    prompt = f"Minecraft FTB Direwolf20 1.21 modpaketinde '{query}' nasıl yapılır veya üretilir? İlgili modun (Mekanism, AE2, Create, Just Dire Things, Powah vb.) makinesini ve girdilerini kısaca açıkla."
                    return await self._ask_gemini_raw(prompt)
                return f"'{query}' icin kayitli bir tarif bulunamadi Efendim."

            lines = [f"'{query}' icin {len(res.recipes)} tarif bulundu:"]
            for i, r in enumerate(res.recipes[:3], 1):
                mod_name = r.mod.upper()
                out_name = r.output.name or r.output.item
                out_cnt = r.output.count
                ins = []
                for ing_group in r.inputs[:6]:
                    if ing_group:
                        first_opt = ing_group[0]
                        ins.append(first_opt.item.split(":")[-1])
                in_str = ", ".join(ins) if ins else "Girdi yok"
                lines.append(f"{i}. [{mod_name}] {out_cnt}x {out_name} <- ({in_str}) [{r.recipe_type.split(':')[-1]}]")

            return " ".join(lines) + " Efendim."
        except Exception as e:
            return f"Tarif sorgulama hatasi: {e}"

    async def _handle_machine_query(self, player: str, p: str) -> str:
        """Pillar 4: Inspects nearby modded machinery, energy generators, and fluid tanks."""
        try:
            res = await self.client.modpack_machines(radius=24)
            if not res.success or not res.machines:
                return "24 blok yakin cevrede herhangi bir modlu makine veya enerji cihazi tespit edilemedi Efendim."

            lines = [f"24 blok alanda {len(res.machines)} makine tespit edildi:"]
            for m in res.machines[:5]:
                name = m.name or m.block_id.split(":")[-1]
                mod = m.mod.upper()
                pos = m.pos
                energy_str = ""
                if m.energy:
                    energy_str = f" [{m.energy.stored:,}/{m.energy.capacity:,} FE]"
                lines.append(f"- [{mod}] {name} (X:{pos.get('x')}, Y:{pos.get('y')}, Z:{pos.get('z')}){energy_str}")

            return "\n".join(lines) + " Efendim."
        except Exception as e:
            return f"Makine tarama hatasi: {e}"

    async def _handle_farming_command(self, player: str, p: str) -> str:
        """Autonomous Farming Engine: Harvesting, Replanting, Animal Breeding & Shearing."""
        try:
            if any(w in p for w in ["kirp", "kırp", "yun", "yün"]):
                res = await self.client.companion_shear_sheep(radius=16)
                if res.success and res.details:
                    sheared = res.details.get("sheep_sheared", 0)
                    return f"Efendim, 16 blok alandaki {sheared} adet koyun kirpildi ve yunler dahili cantama toplandi."
                return f"Koyun kirpma basarisiz: {res.details.get('error', 'Hata') if res.details else 'Hata'}"

            if any(w in p for w in ["besle", "ciftlestir", "çiftleştir", "hayvan"]):
                res = await self.client.companion_breed_animals(radius=16)
                if res.success and res.details:
                    bred = res.details.get("animals_bred", 0)
                    return f"Efendim, 16 blok alandaki {bred} adet ciftlik hayvani (inek, koyun, domuz, tavuk) beslendi ve uremeleri saglandi."
                return f"Hayvan besleme basarisiz: {res.details.get('error', 'Hata') if res.details else 'Hata'}"

            if any(w in p for w in ["hasat", "ekin", "bugday", "buğday", "patates", "havuc", "havuç", "seker kamisi", "şeker kamışı", "karpuz", "balkabagi", "balkabağı"]):
                res = await self.client.companion_harvest_crops(radius=16)
                if res.success and res.details:
                    harv = res.details.get("harvested_count", 0)
                    rep = res.details.get("replanted_count", 0)
                    return f"Efendim, 16 blok alandaki {harv} adet olgun ekin hasat edildi ve topraga hemen {rep} adet yeni tohum ekildi. Urunler cantamda."
                return f"Hasat islemi basarisiz: {res.details.get('error', 'Hata') if res.details else 'Hata'}"

            # Default: Farm All
            res = await self.client.companion_farm(radius=16)
            if res.success and res.details:
                harv = res.details.get("total_crops_harvested", 0)
                rep = res.details.get("total_crops_replanted", 0)
                sheared = res.details.get("total_sheep_sheared", 0)
                bred = res.details.get("total_animals_bred", 0)
                return (
                    f"Ciftlik protokolleri tamamlandi Efendim! {harv} ekin hasat edildi, {rep} tohum yeniden ekildi, "
                    f"{sheared} koyun kirpildi ve {bred} hayvan beslendi."
                )
            return "Ciftlik islemleri yurutulurken bir sorun olustu Efendim."
        except Exception as e:
            return f"Ciftlik hatasi: {e}"

    async def _handle_lumberjack_command(self, player: str, p: str) -> str:
        """Autonomous Lumberjack & Reforestation Engine: Tree cutting and instant replanting."""
        try:
            m = re.search(r"(\d+)\s*(?:agac|ağaç|tane|adet)?", p)
            max_trees = int(m.group(1)) if m else 5

            res = await self.client.companion_chop_trees(radius=20, max_trees=max_trees)
            if res.success and res.details:
                chopped = res.details.get("trees_chopped", 0)
                logs = res.details.get("logs_harvested", 0)
                saplings = res.details.get("saplings_replanted", 0)
                if chopped == 0:
                    return "20 blok yakin cevrede topraga kok salmis uygun bir agac govdesi bulunamadi Efendim."
                return (
                    f"Efendim, {chopped} adet agac dipten tepeye guvenle kesildi. Toplam {logs} adet odun cantama toplandi "
                    f"ve topraga {saplings} adet fidan dikilerek orman yenilendi."
                )
            return f"Odunculuk islemi basarisiz: {res.details.get('error', 'Hata') if res.details else 'Hata'}"
        except Exception as e:
            return f"Odunculuk hatasi: {e}"

    async def _handle_craft_command(self, player: str, p: str) -> str:
        """Autonomous Crafting Engine: Tool replenishment and targeted recipe fabrication."""
        try:
            # Auto replenish tools if generic
            if any(w in p for w in ["alet", "aletler", "kendine", "yenile", "ikmal", "tamamla"]) and not any(w in p for w in ["ekmek", "mesale", "meşale", "kazma yap"]):
                res = await self.client.companion_auto_replenish()
                if res.success and res.details:
                    cnt = res.details.get("replenished_count", 0)
                    items = res.details.get("crafted_items", [])
                    if cnt > 0:
                        return f"Efendim, envanterdeki materyallerle su aletler uretildi: {', '.join(items)}."
                    return "Efendim, aletleriniz ve mesaleleriniz tam, uretim gerektiren eksik bir arac bulunamadi."
                return "Alet ikmali yapilamadi Efendim."

            # Targeted Crafting
            item_id = "minecraft:torch"
            count = 1
            m_cnt = re.search(r"(\d+)", p)
            if m_cnt:
                count = int(m_cnt.group(1))

            if "kazma" in p:
                if "elmas" in p: item_id = "minecraft:diamond_pickaxe"
                elif "demir" in p: item_id = "minecraft:iron_pickaxe"
                elif "tas" in p or "taş" in p: item_id = "minecraft:stone_pickaxe"
                else: item_id = "minecraft:iron_pickaxe"
            elif "balta" in p:
                if "elmas" in p: item_id = "minecraft:diamond_axe"
                else: item_id = "minecraft:iron_axe"
            elif "kilic" in p or "kılıç" in p:
                if "elmas" in p: item_id = "minecraft:diamond_sword"
                else: item_id = "minecraft:iron_sword"
            elif "mesale" in p or "meşale" in p or "torch" in p:
                item_id = "minecraft:torch"
                if not m_cnt: count = 16
            elif "ekmek" in p or "bread" in p:
                item_id = "minecraft:bread"
                if not m_cnt: count = 3
            elif "makas" in p:
                item_id = "minecraft:shears"
            elif "kova" in p:
                item_id = "minecraft:bucket"
            elif "sandik" in p or "sandık" in p:
                item_id = "minecraft:chest"

            res = await self.client.companion_craft(item=item_id, count=count)
            if res.success and res.details:
                crafted = res.details.get("crafted_count", 0)
                name = item_id.split(":")[-1].replace("_", " ").title()
                return f"Efendim, {crafted} adet {name} basariyla uretildi ve cantama alindi."
            return f"Uretim yapilamadi: {res.details.get('error', 'Yetersiz materyal') if res.details else 'Hata'}"
        except Exception as e:
            return f"Crafting hatasi: {e}"

    async def _handle_tech_command(self, player: str, p: str) -> str:
        """FTB Direwolf20 Modded Tech & Ore Multiplication Logistics."""
        try:
            if any(w in p for w in ["tara", "kontrol et", "enerji", "durum", "liste"]):
                res = await self.client.companion_tech_scan_machines(radius=32)
                if res.success and res.details:
                    machines = res.details.get("machines", [])
                    if not machines:
                        return "32 blok alanda herhangi bir modlu makine (Mekanism, Thermal, EnderIO vb.) bulunamadi Efendim."
                    lines = [f"32 blok alanda {len(machines)} modlu makine tespit edildi:"]
                    for m in machines[:6]:
                        lines.append(f"- [{m.get('mod').upper()}] {m.get('name')} (X:{m.get('x')}, Y:{m.get('y')}, Z:{m.get('z')}) [FE: {m.get('energy_fe'):,}/{m.get('max_energy_fe'):,}]")
                    return "\n".join(lines) + " Efendim."
                return "Makine taramasi basarisiz Efendim."

            # Feed ores into ore multipliers
            res = await self.client.companion_tech_process_ores(filter="all", radius=32)
            if res.success and res.details:
                ins = res.details.get("total_ores_inserted", 0)
                ext = res.details.get("total_items_extracted", 0)
                mac = res.details.get("machines_utilized", 0)
                if ins == 0 and ext == 0:
                    return "Efendim, makinelerin haznelerine aktarilacak ham maden bulunamadi veya makinelerde bos hazne/enerji yok."
                return (
                    f"Maden cogaltma basariyla calisti Efendim! {mac} makine kullanildi. "
                    f"{ins} adet ham maden islenmek uzere haznelere yerlestirildi, {ext} adet islenmis urun cantama alindi."
                )
            return f"Makineler calistirilamadi: {res.details.get('error', 'Hata') if res.details else 'Hata'}"
        except Exception as e:
            return f"Teknoloji hatasi: {e}"

    async def _handle_spelunking_command(self, player: str, p: str) -> str:
        """Spelunking, Cave Lighting, Spawner Neutralization & Dungeon Raider."""
        try:
            if any(w in p for w in ["spawner", "zindan", "dungeon"]):
                break_spawner = any(w in p for w in ["kir", "kır", "yok et", "patlat"])
                res = await self.client.companion_neutralize_spawners(radius=24, break_spawner=break_spawner)
                if res.success and res.details:
                    found = res.details.get("spawners_found", 0)
                    broken = res.details.get("spawners_broken", 0)
                    neut = res.details.get("spawners_neutralized", 0)
                    chests = res.details.get("chests_looted", 0)
                    if found == 0:
                        return "24 blok yakinlikta herhangi bir canavar spawner'i bulunamadi Efendim."
                    action_txt = f"{broken} spawner kirildi" if break_spawner else f"{neut} spawner mesalelerle aydinlatilarak etkisiz kilindi"
                    return f"Zindan operasyonu tamamlandi Efendim: {action_txt}, {chests} zindan sandigi cantama yaglandi."
                return "Spawner operasyonu basarisiz Efendim."

            # Light up dark areas
            res = await self.client.companion_light_up_area(radius=24)
            if res.success and res.details:
                torches = res.details.get("torches_placed", 0)
                if torches == 0:
                    return "24 blok alandaki zeminler zaten yeterince aydinlik veya mesale yerlestirilecek karanlik zemin kalmadi Efendim."
                return f"Efendim, 24 blok alandaki karanlik tabanlara {torches} adet mesale yerlestirildi. Canavar dogumu engellendi."
            return "Aydinlatma basarisiz Efendim."
        except Exception as e:
            return f"Kesif hatasi: {e}"

    async def _sort_chests(self, player: str) -> str:
        """Organizes all containers near player into dedicated category storage (Ores, Food, Blocks, Equipment, etc.)."""
        return await self._organize_storage(player)

    async def _sort_chests_in_place(self, player: str) -> str:
        """Instant in-place sorting and compaction of all containers near player."""
        try:
            scan_res = await self.client.container_scan(radius=32, player=player)
            containers = scan_res.containers or []
            if not containers:
                return "32 blok yakin cevrede duzenlenecek herhangi bir sandik veya fici bulunamadi Efendim."

            sorted_count = 0
            for c in containers:
                pos = c.pos if hasattr(c, "pos") else None
                if pos:
                    cx = getattr(pos, "x", None)
                    cy = getattr(pos, "y", None)
                    cz = getattr(pos, "z", None)
                else:
                    cx, cy, cz = getattr(c, "x", None), getattr(c, "y", None), getattr(c, "z", None)

                if cx is not None and cy is not None and cz is not None:
                    try:
                        res = await self.client.container_sort(x=int(cx), y=int(cy), z=int(cz))
                        if res.success:
                            sorted_count += 1
                    except Exception as ex:
                        safe_print(f"[ChatAgent] Container sort error at ({cx}, {cy}, {cz}): {ex}")

            try:
                await self.client.execute_command("playsound minecraft:block.chest.open block @a ~ ~ ~ 0.8 1.2")
            except Exception:
                pass

            return f"Efendim, {sorted_count} adet sandik basariyla duzenlendi. Esyalar (madenler, yemekler, bloklar) kategorilerine gore ayrildi ve istiflendi."
        except Exception as e:
            return f"Sandiklar duzenlenirken bir hata olustu: {e}"

    async def _retrieve_item_for_player(self, player: str, p: str) -> str:
        """Finds items in nearby chests, transfers to Jarvis, and delivers directly to player."""
        # Ensure companion is spawned
        st = await self.client.get_status()
        p_obj = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
        c_st = await self.client.companion_status()
        if not c_st.spawned and p_obj:
            try:
                await self.client.companion_spawn(name="Jarvis", x=p_obj.x, y=p_obj.y, z=p_obj.z, dimension=p_obj.dimension)
            except Exception:
                pass

        # Parse requested count
        m_cnt = re.search(r"(\d+)", p)
        count = int(m_cnt.group(1)) if m_cnt else 1

        # Item dictionary mapping Turkish names to Minecraft item IDs
        item_map = {
            "demir": "minecraft:iron_ingot",
            "demir külçesi": "minecraft:iron_ingot",
            "iron": "minecraft:iron_ingot",
            "altin": "minecraft:gold_ingot",
            "altın": "minecraft:gold_ingot",
            "gold": "minecraft:gold_ingot",
            "bakir": "minecraft:copper_ingot",
            "bakır": "minecraft:copper_ingot",
            "copper": "minecraft:copper_ingot",
            "elmas": "minecraft:diamond",
            "diamond": "minecraft:diamond",
            "komur": "minecraft:coal",
            "kömür": "minecraft:coal",
            "coal": "minecraft:coal",
            "zumrut": "minecraft:emerald",
            "zümrüt": "minecraft:emerald",
            "emerald": "minecraft:emerald",
            "kiziltas": "minecraft:redstone",
            "kızıltaş": "minecraft:redstone",
            "redstone": "minecraft:redstone",
            "lapis": "minecraft:lapis_lazuli",
            "kuvars": "minecraft:quartz",
            "quartz": "minecraft:quartz",
            "netherit": "minecraft:netherite_ingot",
            "netherite": "minecraft:netherite_ingot",
            "odun": "minecraft:oak_log",
            "tas": "minecraft:stone",
            "taş": "minecraft:stone",
            "kiriktas": "minecraft:cobblestone",
            "kırıktaş": "minecraft:cobblestone",
            "cobblestone": "minecraft:cobblestone",
            "ekmek": "minecraft:bread",
            "biftek": "minecraft:cooked_beef",
            "elma": "minecraft:apple",
            "mesale": "minecraft:torch",
            "meşale": "minecraft:torch",
            "torch": "minecraft:torch",
            "ok": "minecraft:arrow",
            "yay": "minecraft:bow",
            "kilic": "minecraft:iron_sword",
            "kılıç": "minecraft:iron_sword",
            "kazma": "minecraft:iron_pickaxe",
        }

        matched_item = None
        item_name_tr = "Esya"
        for tr_name, item_id in sorted(item_map.items(), key=lambda x: len(x[0]), reverse=True):
            if tr_name in p:
                matched_item = item_id
                item_name_tr = tr_name.title()
                break

        if not matched_item:
            # Fallback regex for "minecraft:..." or direct item word
            m_item = re.search(r"\b([a-z_]+:[a-z_]+)\b", p)
            if m_item:
                matched_item = m_item.group(1)
                item_name_tr = matched_item.split(":")[-1].replace("_", " ").title()
            else:
                m_word = re.search(r"(?:bana|sandiktan|sandıktan|kasadan|depodan)\s+(?:\d+\s+)?(?:adet\s+|tane\s+)?([a-zA-Z_]+)\s+getir", p)
                if m_word:
                    w = m_word.group(1).lower()
                    matched_item = f"minecraft:{w}"
                    item_name_tr = w.title()
                else:
                    return "Getirilmesini istediginiz esyanin adini belirtmediniz Efendim (Orn: 'bana 10 demir getir')."

        try:
            res = await self.client.companion_retrieve(item=matched_item, count=count, player=player)
            if res.success and res.delivered > 0:
                return f"Efendim, sandiklardan {res.delivered} adet {item_name_tr} alip envanterinize teslim ettim."
            elif res.retrieved > 0:
                return f"Efendim, {res.retrieved} adet {item_name_tr} sandiktan alindi ancak teslimatta sorun yasandi."
            else:
                return f"32 blok yakinindaki sandiklarda {item_name_tr} bulunamadi Efendim."
        except Exception as e:
            err_str = str(e).lower()
            if "not found" in err_str or "bulunamadi" in err_str:
                return f"32 blok yakinindaki sandiklarda {item_name_tr} bulunamadi Efendim."
    async def _give_logistics_wand(self, player: str) -> str:
        """Gives the player the Jarvis Logistics Wand with pre-configured components and enchant glow."""
        cmd = (
            f"execute at {player} run give {player} blaze_rod["
            'custom_name=\'{"text":"Jarvis Lojistik Asası","color":"gold","bold":true}\','
            'custom_data={jarvis_wand:1b,FilterItems:[]},'
            'enchantment_glint_override=true,'
            'lore=[\'{"text":"Filtredeki Eşyalar: (Boş)","color":"gray"}\','
            '\'{"text":"------------------------","color":"dark_gray","strikethrough":true}\','
            '\'{"text":"Sağ Tık (Sandığa): Filtredeki eşyaları topla & doldur","color":"green"}\','
            '\'{"text":"Shift + Sağ Tık (Sandığa): Sandıktaki eşyaları filtreye kopyala","color":"aqua"}\','
            '\'{"text":"Sağ Tık (Havaya): Filtre arayüzünü aç","color":"yellow"}\']'
            "] 1"
        )
        try:
            await self.client.execute_command(cmd)
            await self.client.execute_command(f"playsound minecraft:entity.player.levelup player {player}")
            await self.client.execute_command(f"particle minecraft:happy_villager ~ ~1 ~ 0.5 0.5 0.5 0.1 20")
            return (
                "Efendim, Jarvis Lojistik Asası envanterinize verildi!\n"
                "• Sandığa Shift + Sağ Tık: Sandıktaki eşyaları filtreye kopyalar.\n"
                "• Sandığa Normal Sağ Tık: Çevredeki tüm dağınık sandıklardan ve çantanızdan o eşyaları çeker ve bu sandığa doldurur!\n"
                "• Havaya Sağ Tık: 27 yuvalı hayalet filtre menüsünü açar (eşyalarınız tüketilmez)."
            )
        except Exception as e:
            return f"Asa verilirken bir hata olustu: {e}"

    async def _deposit_to_chest(self, player: str, p: str = "") -> str:
        """Deposits items from Jarvis companion's 27-slot inventory into nearby categorized chests using native NeoForge capabilities."""
        # 1. Ensure companion is spawned
        st = await self.client.get_status()
        p_obj = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
        c_st = await self.client.companion_status()
        if not c_st.spawned and p_obj:
            try:
                await self.client.companion_spawn(name="Jarvis", x=p_obj.x, y=p_obj.y, z=p_obj.z, dimension=p_obj.dimension)
            except Exception:
                pass

        # 3. Determine category filter
        category = "all" if any(w in p for w in ["butun", "tum", "her seyi", "herseyi", "hepsini"]) else "ores"

        # 4. Physical move towards nearest container
        try:
            scan = await self.client.container_scan(radius=32, player=player)
            if scan.containers:
                nearest = scan.containers[0]
                pos = nearest.pos if hasattr(nearest, "pos") else None
                if pos:
                    cx, cy, cz = getattr(pos, "x", None), getattr(pos, "y", None), getattr(pos, "z", None)
                    if cx is not None and cy is not None and cz is not None:
                        await self.client.companion_move(float(cx), float(cy), float(cz), speed=1.2)
                        await asyncio.sleep(0.6)
                        await self.client.execute_command(f"playsound minecraft:block.chest.open block @a {int(cx)} {int(cy)} {int(cz)} 1.0 1.0")
        except Exception:
            pass

        # 5. Call native companion deposit endpoint backed by StorageManager.depositLoot
        try:
            dep_res = await self.client.companion_deposit(category=category, radius=48, player=player)
            chests_used_count = len(dep_res.chests_used) if dep_res.chests_used else 0
            chests_txt = f"{chests_used_count} adet sandiga" if chests_used_count > 0 else "sandiklara"

            # Follow player back
            await self.client.companion_follow(player=player, distance=2.5)

            if dep_res.deposited > 0:
                cat_desc = "tum esyalar" if category == "all" else "madenler"
                return f"Efendim, toplanan {cat_desc} ({dep_res.deposited} adet) {chests_txt} basariyla depolandi."
            else:
                return "Efendim, sandiklara aktarilacak maden veya uygun bos alan bulunamadi."
        except Exception as e:
            return f"Sandiga aktarim yapilirken bir hata olustu: {e}"

    async def _get_status_report(self, player: str) -> str:
        """Generates comprehensive Jarvis NPC telemetry and status report."""
        try:
            c_st = await self.client.companion_status()
            hp = int(c_st.health or 20)
            max_hp = int(c_st.max_health or 20)

            # Count monsters
            surr = await self.client.get_surroundings(player=player, radius=16)
            monsters = [e for e in (surr.entities or []) if getattr(e, "category", "") == "monster"]
            m_count = len(monsters)
            threat_txt = f"{m_count} tehdit tespit edildi!" if m_count > 0 else "Tehdit yok, alan guvenli"

            guard_txt = "Nobette" if self.sentry_post else ("Korumalik Aktif" if self.guard_mode else "Koruma Pasif")
            wp_count = len(self.memory.get("waypoints", {}))

            return (
                f"Durum Raporu: Saglik: {hp}/{max_hp} HP | Durum: {self.activity_state} ({guard_txt}) | "
                f"Sahip: {self.last_owner} | Radar: {threat_txt} | Hafiza: {wp_count} kayitli nokta Efendim."
            )
        except Exception as e:
            return f"Rapor alinirken hata: {e}"

    async def _show_help_menu(self, player: str, query: str = "") -> str:
        """Broadcasts a clean, categorized list of all Jarvis companion commands."""
        q = query.lower()

        # Detailed Category Help
        if any(w in q for w in ["maden", "kazi", "kaz"]):
            await self._say("=== [JARVIS MADEN PROTOKOLLERI] ===")
            await asyncio.sleep(0.22)
            await self._say("1. 'maden tara' -> 128x128x128 alandaki tum cevherleri sayip listeler.")
            await asyncio.sleep(0.22)
            await self._say("2. '[alan] butun [maden] topla' / '[adet] [maden] kaz' -> Orn: '32x32x32 butun komurleri topla', '88 demir kaz'.")
            await asyncio.sleep(0.22)
            await self._say("3. 'madene in [sayi]' -> 45 derece merdiven kazar, 5 adimda bir mesale koyar (Orn: 'madene in 8').")
            await asyncio.sleep(0.22)
            await self._say("4. 'tunel kaz [sayi]' -> Ileriye dogru 1x2 maden tuneli acar.")
            await asyncio.sleep(0.22)
            await self._say("5. 'madenleri ver' -> Yerdeki tum cevher ve bloklari envanterinize teslim eder.")
            return ""

        if any(w in q for w in ["koruma", "nobet", "savunma"]):
            await self._say("=== [JARVIS KORUMA & NOBET PROTOKOLLERI] ===")
            await asyncio.sleep(0.22)
            await self._say("1. 'nobet tut' / 'burayi koru' -> 32 blokluk alani korur, canavarlara kilicla saldirip mevzisine doner.")
            await asyncio.sleep(0.22)
            await self._say("2. 'nobeti birak' -> Nobeti sonlandirip sizi takibe doner.")
            await asyncio.sleep(0.22)
            await self._say("3. 'koruma ac' / 'koruma kapat' -> Yakin koruma refleksini ayarlar.")
            await asyncio.sleep(0.22)
            await self._say("4. Otomatik Refleks -> 10 blokta Creeper zil calar, can <= 3 kalpte sahibine siginip biftek yer.")
            return ""

        if any(w in q for w in ["rehber", "navigasyon", "hafiza", "konum"]):
            await self._say("=== [JARVIS REHBERLIK & HAFIZA PROTOKOLLERI] ===")
            await asyncio.sleep(0.22)
            await self._say("1. 'eve gidelim' -> Evinize yuruyerek onculuk eder, geride kalirsaniz bekler.")
            await asyncio.sleep(0.22)
            await self._say("2. 'cesedim nerede' -> Son vefat ettiginiz noktaya yuruyerek goturur.")
            await asyncio.sleep(0.22)
            await self._say("3. 'burayi [isim] kaydet' -> Bulundugunuz konumu kalici hafizaya kaydeder.")
            await asyncio.sleep(0.22)
            await self._say("4. '[isim] nerede' -> Mesafe ve pusula yonunu hesaplar (Orn: 'ev nerede').")
            await asyncio.sleep(0.22)
            await self._say("5. 'konumlari listele' / '[isim] sil' -> Hafizadaki noktalari yonetir.")
            return ""

        if any(w in q for w in ["lojistik", "sandik", "depo"]):
            await self._say("=== [JARVIS LOJISTIK & SANDIK PROTOKOLLERI] ===")
            await asyncio.sleep(0.22)
            await self._say("1. 'butun chestleri duzenle' / 'madenleri bir sandikta, yemekleri...' -> Maden, yemek, blok, ekipman, tarim ve malzemeleri kategorize eder, dolanlarin ustune double chest yerlestirir.")
            await asyncio.sleep(0.22)
            await self._say("2. 'sandik ara' -> 128 blok genislikteki sandik ve ficilari yonleriyle listeler.")
            await asyncio.sleep(0.22)
            await self._say("3. 'sandikta ne var' -> En yakin sandiga yurur, kapagi acip icerigi sayar.")
            await asyncio.sleep(0.22)
            await self._say("4. 'sandiga bosalt' -> Yerdeki maden ve esyalari sandiga depolar.")
            return ""

        if any(w in q for w in ["insaat", "inşaat", "yapi", "yapı", "kopru", "köprü", "siginak", "sığınak", "duvar", "barikat"]):
            await self._say("=== [JARVIS INSAAT & MIMARI PROTOKOLLERI] ===")
            await asyncio.sleep(0.22)
            await self._say("1. 'kopru yap [uzunluk]' -> Ileriye dogru korkuluklu ve mesaleli yuruyus koprusu kurar (Orn: 'kopru yap 15').")
            await asyncio.sleep(0.22)
            await self._say("2. 'siginak yap [ebat]' -> Zemin, 4 duvar, kapi, tavan ve mesaleli acil durum barinagi insa eder (Orn: 'siginak yap 5').")
            await asyncio.sleep(0.22)
            await self._say("3. 'duvar or [uzunluk]' / 'barikat kur' -> 3 blok yuksekliginde siperli savunma barikati orer.")
            await asyncio.sleep(0.22)
            await self._say("4. 'platform yap [en]x[boy]' -> Duz temel zemini dosemesi yapar.")
            await asyncio.sleep(0.22)
            await self._say("5. 'insaati durdur' / 'insaat durumu' -> Insaat calismalarini iptal eder veya ilerlemeyi raporlar.")
            return ""

        if any(w in q for w in ["ciftlik", "çiftlik", "tarim", "tarım", "ekin", "hasat", "hayvan"]):
            await self._say("=== [JARVIS CIFTLIK & HAYVANCILIK] ===")
            await asyncio.sleep(0.22)
            await self._say("1. 'tarlayi hasat et' -> Olgun ekinleri toplar ve yerine aninda tohum diker.")
            await asyncio.sleep(0.22)
            await self._say("2. 'hayvanlari besle' -> Inek, koyun, domuz ve tavuklari yem vererek ciftlestirir.")
            await asyncio.sleep(0.22)
            await self._say("3. 'koyunlari kirp' -> Makasla yunleri toplar.")
            await asyncio.sleep(0.22)
            await self._say("4. 'ciftlikle ilgilen' -> Hasat, ekim, kirpma ve beslemeyi tek seferde yapar.")
            return ""

        if any(w in q for w in ["odun", "agac", "ağaç", "orman", "lumberjack"]):
            await self._say("=== [JARVIS ODUNCULUK & AGACLANDIRMA] ===")
            await asyncio.sleep(0.22)
            await self._say("1. 'odun kes [sayi]' -> Agaclari dipten tepeye keser, cantaya alir.")
            await asyncio.sleep(0.22)
            await self._say("2. 'fidan dikme' -> Kestigi her agacin yerine topraga aninda fidan diker.")
            return ""

        if any(w in q for w in ["craft", "uret", "üret", "zanaat", "alet"]):
            await self._say("=== [JARVIS OTONOM CRAFTING] ===")
            await asyncio.sleep(0.22)
            await self._say("1. 'kendine alet yap' -> Eksik kazma, balta ve mesaleleri otomatik uretir.")
            await asyncio.sleep(0.22)
            await self._say("2. '[sayi] [esya] yap' -> Ekmek, mesale, demir kazma, kova, makas uretir.")
            return ""

        if any(w in q for w in ["tech", "makine", "mekanism", "thermal", "cogalt", "çoğalt"]):
            await self._say("=== [JARVIS MODLU MAKINE & COGALTMA] ===")
            await asyncio.sleep(0.22)
            await self._say("1. 'mekanism calistir' / 'madenleri cogalt' -> Ham madenleri Enrichment Chamber/Pulverizer'a besler.")
            await asyncio.sleep(0.22)
            await self._say("2. 'makineleri tara' -> FE enerjisini ve modlu makineleri listeler.")
            return ""

        if any(w in q for w in ["kesif", "keşif", "magara", "mağara", "spawner", "aydinlat", "aydınlat", "zindan"]):
            await self._say("=== [JARVIS KESIF & SPELUNKING] ===")
            await asyncio.sleep(0.22)
            await self._say("1. 'etrafi aydinlat' -> Karanlik zeminlere mesale koyarak canavar dogumunu onler.")
            await asyncio.sleep(0.22)
            await self._say("2. 'spawneri yok et' / 'spawneri aydinlat' -> Zindan spawnerini etkisiz kilar veya kirar.")
            return ""

        # General Complete Command Menu
        await self._say("=== [JARVIS KOMUT PROTOKOLLERI] ===")
        await asyncio.sleep(0.22)
        await self._say("1. MADEN: '32x32x32 butun komurleri topla', '88 demir kaz', 'maden tara' (128x128x128), 'madene in 8', 'madenleri ver'")
        await asyncio.sleep(0.22)
        await self._say("2. KORUMA: 'nobet tut' (32 blok alan), 'nobeti birak', 'koruma ac/kapat', 'canlan'")
        await asyncio.sleep(0.22)
        await self._say("3. REHBERLIK: 'eve gidelim', 'cesedim nerede', 'burayi [isim] kaydet', 'konumlari listele'")
        await asyncio.sleep(0.22)
        await self._say("4. LOJISTIK: 'butun chestleri duzenle', 'sandik ara', 'sandikta ne var', 'sandiga bosalt'")
        await asyncio.sleep(0.22)
        await self._say("5. INSAAT: 'kopru yap 15', 'siginak yap 5', 'duvar or 9', 'platform yap 5x5', 'insaati durdur'")
        await asyncio.sleep(0.22)
        await self._say("6. TEMEL: 'durum', 'yanima gel', 'bekle', 'yemek ye', 'sabah yap', 'gece yap'")
        await asyncio.sleep(0.22)
        await self._say("Not: Basina ! veya jarvis koymaniza gerek yoktur Efendim.")
        return ""

    async def _celebrate_jerk_a_little(self, player: str) -> str:
        """Triggers the 'jerk a little' celebration protocol: fireworks on everyone's screen,
        special particle effects, screen title/subtitle, levelup & blast sounds!"""
        # 1. Screen Titles and Actionbar for all players
        try:
            await self.client.execute_command("title @a times 10 70 20")
            await self.client.execute_command(
                'title @a title {"text":"🎉 JERK A LITTLE! 🎉","bold":true,"color":"gold"}'
            )
            await self.client.execute_command(
                'title @a subtitle {"text":"✨ Havai fişek şöleni başlatıldı! ✨","color":"yellow","italic":true}'
            )
            await self.client.execute_command(
                'title @a actionbar {"text":"💥 ♫ Let\'s jerk a little! ♫ 💥","bold":true,"color":"aqua"}'
            )
        except Exception:
            pass

        # 2. Level up fanfare sound
        try:
            await self.client.execute_command("playsound entity.player.levelup ambient @a ~ ~ ~ 1.0 1.2")
        except Exception:
            pass

        # 3. Wave 1: Immediate fireworks directly at eye level of all players + Golden Shimmer Particles
        fw1 = (
            'execute at @a run summon firework_rocket ~ ~1.8 ~ '
            '{LifeTime:0,FireworksItem:{id:"minecraft:firework_rocket",count:1,'
            'components:{"minecraft:fireworks":{explosions:[{shape:"large_ball",'
            'colors:[I;16711680,16776960,65280,16711935,65535],has_trail:1b,has_twinkle:1b}]}}}}'
        )
        totem_particles = "execute at @a run particle minecraft:totem_of_undying ~ ~1.2 ~ 0.6 0.6 0.6 0.1 70"
        try:
            await self.client.execute_command(fw1)
            await self.client.execute_command(totem_particles)
        except Exception:
            pass

        # 4. Waves 2 & 3: Delayed multi-burst fireworks and twinkles
        async def delayed_bursts():
            try:
                await asyncio.sleep(0.35)
                fw2_left = (
                    'execute at @a run summon firework_rocket ~1.5 ~2.4 ~1 '
                    '{LifeTime:0,FireworksItem:{id:"minecraft:firework_rocket",count:1,'
                    'components:{"minecraft:fireworks":{explosions:[{shape:"star",'
                    'colors:[I;16766720,16711935,65535],has_trail:1b,has_twinkle:1b}]}}}}'
                )
                fw2_right = (
                    'execute at @a run summon firework_rocket ~-1.5 ~2.4 ~-1 '
                    '{LifeTime:0,FireworksItem:{id:"minecraft:firework_rocket",count:1,'
                    'components:{"minecraft:fireworks":{explosions:[{shape:"burst",'
                    'colors:[I;65280,16776960,16711680],has_trail:1b,has_twinkle:1b}]}}}}'
                )
                sparkles = "execute at @a run particle minecraft:firework ~ ~2 ~ 1.0 1.0 1.0 0.15 120"
                await self.client.execute_command(fw2_left)
                await self.client.execute_command(fw2_right)
                await self.client.execute_command(sparkles)
                await self.client.execute_command("playsound entity.firework_rocket.blast ambient @a ~ ~ ~ 1.0 1.0")

                await asyncio.sleep(0.4)
                fw3 = (
                    'execute at @a run summon firework_rocket ~ ~3.2 ~ '
                    '{LifeTime:0,FireworksItem:{id:"minecraft:firework_rocket",count:1,'
                    'components:{"minecraft:fireworks":{explosions:[{shape:"creeper",'
                    'colors:[I;65280,16711680,16776960,16711935],has_trail:1b,has_twinkle:1b}]}}}}'
                )
                await self.client.execute_command(fw3)
                await self.client.execute_command("playsound entity.firework_rocket.twinkle ambient @a ~ ~ ~ 1.0 1.2")
            except Exception:
                pass

        asyncio.create_task(delayed_bursts())

        return "Parti protokolü devrede Efendim! *Jerk a little!* 🎉✨"

    async def _eat_food(self, silent: bool = False) -> str:
        """Executes a physical eating animation, sound, hearts, and health restoration for Jarvis."""
        try:
            # 1. Hold food in hand
            await self.client.execute_command("item replace entity @e[type=jarvis:companion,limit=1] weapon.mainhand with minecraft:cooked_beef 1")
            await asyncio.sleep(0.3)

            # 2. Play eating sound and heart particles
            await self.client.execute_command("execute at @e[type=jarvis:companion,limit=1] run playsound minecraft:entity.generic.eat player @a ~ ~1.5 ~ 1.0 1.0")
            await asyncio.sleep(0.35)
            await self.client.execute_command("execute at @e[type=jarvis:companion,limit=1] run playsound minecraft:entity.generic.eat player @a ~ ~1.5 ~ 1.0 1.0")
            await self.client.execute_command("execute at @e[type=jarvis:companion,limit=1] run particle minecraft:heart ~ ~1.5 ~ 0.5 0.5 0.5 0 6")

            # 3. Heal companion to full health
            await self.client.execute_command("effect give @e[type=jarvis:companion,limit=1] minecraft:instant_health 1 2")
            await self.client.execute_command("effect give @e[type=jarvis:companion,limit=1] minecraft:regeneration 4 1")
            await asyncio.sleep(0.3)

            # 4. Restore Diamond Pickaxe in hand
            await self.client.execute_command("item replace entity @e[type=jarvis:companion,limit=1] weapon.mainhand with minecraft:diamond_pickaxe 1")

            # 5. Resume follow
            owner = self.last_owner or "AlcyoneDX"
            try:
                await self.client.companion_follow(player=owner, distance=2.5)
            except Exception:
                pass

            if not silent:
                return "Tesekkurler Efendim, bir parca biftek atistirip canimi doldurdum."
            return ""
        except Exception as e:
            safe_print(f"[ChatAgent] Eating error: {e}")
            return f"Yemek yerken bir sorun olustu: {e}"

    async def _check_companion_health_and_respawn(self):
        """Monitors Jarvis's physical life, health, auto-eating, and emergency retreat upon danger."""
        # 1. Player Death Tracking for Auto-Waypoints
        try:
            srv_st = await self.client.get_status()
            for pl in (srv_st.players or []):
                pname = pl.name
                prev = self.players_last_known.get(pname)
                curr_hp = pl.health if pl.health is not None else 20.0
                curr_x = int(pl.x or 0)
                curr_y = int(pl.y or 0)
                curr_z = int(pl.z or 0)

                if prev and prev.get("health", 20.0) > 0 and curr_hp <= 0:
                    death_x = prev.get("x", curr_x)
                    death_y = prev.get("y", curr_y)
                    death_z = prev.get("z", curr_z)
                    self.memory.setdefault("waypoints", {})["son_olum_yeri"] = {
                        "name": f"Son Olum Yeri ({pname})",
                        "x": death_x,
                        "y": death_y,
                        "z": death_z,
                        "dimension": getattr(pl, "dimension", "minecraft:overworld"),
                        "saved_by": pname,
                        "time": int(time.time()),
                    }
                    self._save_memory()
                    safe_print(f"[ChatAgent] Player {pname} died at ({death_x}, {death_y}, {death_z})! Recorded death waypoint.")
                    try:
                        await self._say(f"UYARI: {pname} vefat etti! Olum koordinatlari hafizama kaydedildi (X: {death_x}, Y: {death_y}, Z: {death_z}). '!jarvis cesedim nerede' ile ulasabilirsiniz Efendim.")
                    except Exception:
                        pass

                self.players_last_known[pname] = {"x": curr_x, "y": curr_y, "z": curr_z, "health": curr_hp}
        except Exception:
            pass

        if self.death_recovery_in_progress or self.eating_in_progress:
            return
        try:
            st = await self.client.companion_status()
            if st.spawned:
                self.companion_was_spawned = True
                if st.target and getattr(st.target, "player", None):
                    self.last_owner = st.target.player

                # Critical Danger Retreat (Health <= 6.0 = 3 hearts or less)
                if st.health is not None and 0 < st.health <= 6.0:
                    self.eating_in_progress = True
                    safe_print(f"[ChatAgent] Jarvis in critical danger ({st.health}/20 HP)! Emergency retreat & heal...")
                    owner = self.last_owner or "AlcyoneDX"
                    try:
                        await self._say("Kritik hasar alindi Efendim! Guvenli alana cekilip kendimi tedavi ediyorum...")
                        srv_st = await self.client.get_status()
                        p_obj = next((x for x in (srv_st.players or []) if x.name.lower() == owner.lower()), None)
                        if p_obj:
                            await self.client.companion_action("teleport", x=p_obj.x + 1.0, y=p_obj.y, z=p_obj.z + 1.0)
                        await self._eat_food(silent=True)
                    except Exception as ex:
                        safe_print(f"[ChatAgent] Emergency heal error: {ex}")
                    finally:
                        self.eating_in_progress = False

                # Auto-eat when damaged (Health < 14.0 = 7 hearts or less)
                elif st.health is not None and 6.0 < st.health < 14.0:
                    now = asyncio.get_event_loop().time()
                    if now - self.last_eat_time > 20.0:  # Cooldown of 20 seconds between normal snacks
                        self.eating_in_progress = True
                        self.last_eat_time = now
                        safe_print(f"[ChatAgent] Jarvis damaged ({st.health}/20 HP). Eating food...")
                        try:
                            await self._eat_food(silent=False)
                        except Exception:
                            pass
                        finally:
                            self.eating_in_progress = False

            elif self.companion_was_spawned and not st.spawned:
                # Jarvis has died or despawned!
                self.death_recovery_in_progress = True
                safe_print("[ChatAgent] Jarvis died or despawned! Initiating auto-respawn protocol...")
                owner = self.last_owner or "AlcyoneDX"
                try:
                    await self._say("Protokoller devreye alindi. Yeniden baslatiliyorum Efendim...")
                except Exception:
                    pass

                await asyncio.sleep(2.5)

                try:
                    srv_st = await self.client.get_status()
                    p_obj = next((x for x in (srv_st.players or []) if x.name.lower() == owner.lower()), None)
                    if p_obj:
                        await self.client.companion_spawn(name="Jarvis", x=p_obj.x, y=p_obj.y, z=p_obj.z, dimension=p_obj.dimension)
                    else:
                        await self.client.companion_spawn(name="Jarvis")
                    await asyncio.sleep(0.5)
                    await self.client.companion_follow(player=owner, distance=2.5)
                    await self._say("Sistemler yeniden aktif. Yaninizdayim Efendim.")
                    safe_print(f"[ChatAgent] Auto-respawn successful beside {owner}!")
                except Exception as ex:
                    safe_print(f"[ChatAgent] Auto-respawn execution error: {ex}")
                finally:
                    self.death_recovery_in_progress = False
        except Exception:
            self.death_recovery_in_progress = False

    async def _scan_threats_and_defend(self):
        """Scans for hostile mobs, triggers Creeper alarms, and defends the player or sentry post."""
        now = asyncio.get_event_loop().time()
        if now - self.last_radar_scan_time < 2.0:
            return
        self.last_radar_scan_time = now

        owner = self.last_owner or "AlcyoneDX"
        try:
            surr = await self.client.get_surroundings(radius=14, player=owner)
            entities = surr.entities or []
            monsters = [
                e for e in entities
                if getattr(e, "category", "") == "monster"
                or "creeper" in (getattr(e, "type", "") or "").lower()
                or "zombie" in (getattr(e, "type", "") or "").lower()
                or "skeleton" in (getattr(e, "type", "") or "").lower()
                or "spider" in (getattr(e, "type", "") or "").lower()
            ]

            # 1. Creeper Early Warning Alarm
            creepers = [m for m in monsters if "creeper" in (getattr(m, "type", "") or "").lower() or "creeper" in (getattr(m, "name", "") or "").lower()]
            if creepers:
                closest_creeper = min(creepers, key=lambda c: getattr(c, "distance", 99.0) or 99.0)
                cdist = getattr(closest_creeper, "distance", 99.0) or 99.0
                if cdist <= 10.0 and (now - self.last_creeper_warn_time > 10.0):
                    self.last_creeper_warn_time = now
                    await self.client.execute_command("execute at @e[type=jarvis:companion,limit=1] run playsound minecraft:block.note_block.bell player @a ~ ~1.5 ~ 1.0 1.5")
                    await self._say(f"DIKKAT EFENDIM! {cdist:.1f} blok mesafede bir Creeper yaklasiyor!")

            # 2. Sentry Post Defense Mode (32-Block Perimeter Guard)
            if self.sentry_post:
                spx, spy, spz = self.sentry_post
                # Scan 32-block radius around sentry post
                surr_post = await self.client.get_surroundings(radius=32, player=owner)
                entities = surr_post.entities or []
                monsters = [
                    e for e in entities
                    if getattr(e, "category", "") == "monster"
                    or any(m in (getattr(e, "type", "") or "").lower() for m in ["zombie", "skeleton", "spider", "creeper", "witch", "enderman", "slime"])
                ]
                close_to_post = []
                for m in monsters:
                    mx = getattr(m, "x", None)
                    mz = getattr(m, "z", None)
                    if mx is not None and mz is not None:
                        dist = math.hypot(mx - spx, mz - spz)
                    else:
                        dist = getattr(m, "distance", 99.0) or 99.0
                    if dist <= 32.0:
                        close_to_post.append((dist, m))

                if close_to_post and (now - self.last_attack_time > 1.2):
                    self.last_attack_time = now
                    target_dist, target_mob = min(close_to_post, key=lambda x: x[0])
                    m_id = getattr(target_mob, "id", None)
                    if m_id:
                        mx = getattr(target_mob, "x", spx)
                        my = getattr(target_mob, "y", spy)
                        mz = getattr(target_mob, "z", spz)
                        await self.client.companion_move(float(mx), float(my), float(mz), speed=1.35)
                        await self.client.execute_command("item replace entity @e[type=jarvis:companion,limit=1] weapon.mainhand with minecraft:diamond_sword 1")
                        if target_dist <= 5.0:
                            await self.client.companion_attack(entity_id=int(m_id))
                        await asyncio.sleep(0.35)
                        await self.client.execute_command("item replace entity @e[type=jarvis:companion,limit=1] weapon.mainhand with minecraft:diamond_pickaxe 1")
                elif not close_to_post:
                    # Return to sentry post if displaced
                    try:
                        c_info = await self.client.companion_status()
                        pos = c_info.position
                        if pos and getattr(pos, "x", None) is not None:
                            dist_from_post = math.hypot(pos.x - spx, pos.z - spz)
                            if dist_from_post > 2.0:
                                await self.client.companion_move(float(spx), float(spy), float(spz), speed=1.1)
                    except Exception:
                        pass
                return

            # 3. Bodyguard Reflex (Attack nearest hostile mob within 4.5 blocks of player/companion)
            if self.guard_mode and monsters:
                close_monsters = [m for m in monsters if (getattr(m, "distance", 99.0) or 99.0) <= 4.5]
                if close_monsters and (now - self.last_attack_time > 1.5):
                    self.last_attack_time = now
                    target_mob = min(close_monsters, key=lambda m: getattr(m, "distance", 99.0) or 99.0)
                    m_id = getattr(target_mob, "id", None)
                    if m_id:
                        # Equip sword
                        await self.client.execute_command("item replace entity @e[type=jarvis:companion,limit=1] weapon.mainhand with minecraft:diamond_sword 1")
                        await self.client.companion_attack(entity_id=int(m_id))
                        await asyncio.sleep(0.35)
                        await self.client.execute_command("item replace entity @e[type=jarvis:companion,limit=1] weapon.mainhand with minecraft:diamond_pickaxe 1")
        except Exception:
            pass

    async def _poll_and_process(self):
        self.poll_counter += 1
        if self.poll_counter % 2 == 0:
            await self._check_companion_health_and_respawn()
        await self._scan_threats_and_defend()
        await self._guide_step()
        try:
            # Always fetch latest messages with since=0 to avoid the server
            # misinterpreting small IDs as timestamps (old JAR compatibility).
            # We use limit=10 and filter by self.last_seen_id client-side.
            chat_entries = await self.client.get_chat(limit=10, since=0)
        except Exception as e:
            safe_print(f"[ChatAgent] Chat poll network error: {e}")
            if "localhost" not in self.api_url and hasattr(self, "_is_port_open") and self._is_port_open("localhost", 25585):
                safe_print("[ChatAgent] Remote tunnel disconnected. Switching to local singleplayer (http://localhost:25585)...")
                self.api_url = "http://localhost:25585"
                self.client = JarvisClient(base_url=self.api_url)
            return

        for entry in chat_entries:
            if entry.id <= self.last_seen_id:
                continue
            self.last_seen_id = max(self.last_seen_id, entry.id)

            sender = entry.player or ""
            if sender.lower() in ("jarvis", "server", "system"):
                continue

            msg = entry.message.strip()
            if not self._is_addressed_to_jarvis(msg):
                continue

            clean_prompt = self._strip_trigger(msg)
            safe_print(f"\n[Chat] {sender}: \"{msg}\"")

            try:
                reply = await self._handle_prompt(sender, clean_prompt)
                if reply:
                    safe_print(f"[Chat] Jarvis -> {sender}: {reply}")
                    await self._say(reply)
            except Exception as e:
                safe_print(f"[Chat] Error handling prompt: {e}")
                await self._say(f"Uzgünum efendim @{sender}, bir sorun olustu.")

    def _is_addressed_to_jarvis(self, msg: str) -> bool:
        lower = sanitize_for_minecraft(msg).lower().strip()
        if lower.startswith("!jarvis") or lower.startswith("!j ") or lower.startswith("@jarvis") or lower.startswith("!"):
            return True
        if re.search(r"\bjarvis\b", lower):
            return True

        # Natural Turkish commands without exclamation mark or name
        known_command_patterns = [
            r"\b(?:nobet|nobete|nobetci)\b",
            r"\beve\s+(?:gidelim|donelim|git|don|gotur)\b",
            r"\b(?:cesedim|cesedime|oldugum\s+yer|olum\s+yerim)\b",
            r"\b(?:sand[iı][kg]\w*|f[iı]c[iı]\w*)\b",
            r"\b(?:sand[iı][kg]\w*|f[iı]c[iı]\w*)\b.*?\b(?:koy\w*|yerlestir\w*|aktar\w*|depola\w*|bosalt\w*)\b",
            r"\b(?:koy\w*|yerlestir\w*|aktar\w*|depola\w*|bosalt\w*)\b.*?\b(?:sand[iı][kg]\w*|f[iı]c[iı]\w*)\b",
            r"\b(?:topla\w*|maden\w*|esya\w*)\b.*?\b(?:sand[iı][kg]\w*|f[iı]c[iı]\w*)\b",
            r"\b(?:maden|cevher|demir|elmas|komur|bakir|altin|lapis|redstone|kukurt|kalay|kursun|osmiyum|cinko|uranyum|zumrut|ore)\b.*?\b(?:kaz\w*|kir\w*|cikar\w*|topla\w*|kas\w*|tara\w*|ara\w*|bul\w*|radar)\b",
            r"\b(?:kaz\w*|kir\w*|cikar\w*|topla\w*|kas\w*|tara\w*|radar)\b.*?\b(?:maden|cevher|demir|elmas|komur|bakir|altin|lapis|redstone|kukurt|kalay|kursun|osmiyum|cinko|uranyum|zumrut|ore)\b",
            r"\b(?:maden\s+kasmaya|madene\s+in|merdiven\s+kaz)\b",
            r"\b(?:128x128x128|128\s+blok)\b",
            r"\b(?:durum|rapor|ne\s+yapiyorsun|telemetri)\b",
            r"\b(?:yanima\s+gel|pesime\s+takil|takip\s+et|beni\s+takip)\b",
            r"\b(?:dur|bekle|kal|hareket\s+etme)\b",
            r"\b(?:yemek\s+ye|canlan|canini\s+doldur)\b",
            r"\b(?:koruma\s+ac|koruma\s+kapat|koru\s+beni)\b",
            r"\b(?:burayi\s+kaydet|konum\s+kaydet|konumlari\s+listele)\b",
            r"\b(?:madenleri\s+ver|esyalari\s+ver)\b",
            r"\b(?:komut|komutlar|komutlari|komutlarin|yardim|help|neler\s+yapabilirsin|menusu)\b",
            r"\b(?:jerk\s+a\s+little|jerk\s+alittle|jerk\s+a\s+litle)\b",
        ]
        for pat in known_command_patterns:
            if re.search(pat, lower):
                return True

        return False

    def _strip_trigger(self, msg: str) -> str:
        s = re.sub(r"^(?:!jarvis|!j|@jarvis|!)\s*", "", msg, flags=re.IGNORECASE)
        s = re.sub(r"\bjarvis\b", "", s, flags=re.IGNORECASE)
        return s.strip(" ,:.-?!")

    async def _detect_base_zone(self, player: str):
        """Identifies base/home center, protection radius, and perimeter filter.
        
        Returns:
            (base_center, base_anchors, home_radius, is_inside_base_fn)
        """
        waypoints = self.memory.get("waypoints", {})
        home_wp = None
        for k in ["ev", "base", "home", "evim", "karargah", "merkez"]:
            if k in waypoints:
                home_wp = waypoints[k]
                break
            for wp_key, wp_val in waypoints.items():
                if k in wp_key:
                    home_wp = wp_val
                    break
            if home_wp:
                break

        home_radius = 20.0
        base_center = None
        base_anchors = []

        try:
            res = await self.client.get_surroundings(player=player, radius=32)
            for b in (res.blocks or []):
                cat = getattr(b, "category", "")
                b_name = (getattr(b, "block", "") or getattr(b, "name", "")).lower()
                if cat in ("workstation", "container") or any(
                    kw in b_name
                    for kw in [
                        "bed", "crafting_table", "furnace", "smoker",
                        "blast_furnace", "anvil", "chest", "barrel", "door", "campfire"
                    ]
                ):
                    bx = int(b.x or 0)
                    by = int(b.y or 0)
                    bz = int(b.z or 0)
                    base_anchors.append((bx, by, bz))
        except Exception as e:
            safe_print(f"[ChatAgent] Base detection scan error: {e}")

        if home_wp:
            base_center = (int(home_wp["x"]), int(home_wp["y"]), int(home_wp["z"]))
        elif base_anchors:
            # Check if any bed exists (beds are primary indicator of home)
            bed_anchors = []
            try:
                for b in (res.blocks or []):
                    b_name = (getattr(b, "block", "") or getattr(b, "name", "")).lower()
                    if "bed" in b_name:
                        bed_anchors.append((int(b.x or 0), int(b.y or 0), int(b.z or 0)))
            except Exception:
                pass

            if bed_anchors:
                base_center = bed_anchors[0]
            elif len(base_anchors) >= 2:
                avg_x = sum(a[0] for a in base_anchors) // len(base_anchors)
                avg_y = sum(a[1] for a in base_anchors) // len(base_anchors)
                avg_z = sum(a[2] for a in base_anchors) // len(base_anchors)
                base_center = (avg_x, avg_y, avg_z)

        def is_inside_base(x: float, y: float, z: float) -> bool:
            if base_center is not None:
                hx, hy, hz = base_center
                horiz_dist = math.hypot(x - hx, z - hz)
                if horiz_dist <= home_radius and (hy - 8) <= y <= (hy + 16):
                    return True
            for ax, ay, az in base_anchors:
                if math.hypot(x - ax, z - az) <= 12.0 and abs(y - ay) <= 6:
                    return True
            return False

        return base_center, base_anchors, home_radius, is_inside_base

    async def _mine_ores(self, player: str, p: str) -> str:
        """Finds and mines nearest ores for player using Jarvis FakePlayer.
        Strictly protects the player's home/base and physically walks to ores without teleporting.
        """
        # Ensure companion is spawned and near player
        try:
            st = await self.client.get_status()
            p_obj = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
            c_st = await self.client.companion_status()
            if not c_st.spawned and p_obj:
                await self.client.companion_spawn(name="Jarvis", x=p_obj.x, y=p_obj.y, z=p_obj.z, dimension=p_obj.dimension)
            elif p_obj and not c_st.spawned:
                await self.client.companion_spawn(name="Jarvis")
        except Exception as e:
            safe_print(f"[ChatAgent] Spawn check error during mining: {e}")

        # Check for tunnel excavation command
        if "tunel" in p:
            m = re.search(r"(\d+)", p)
            length = min(max(int(m.group(1)), 1), 30) if m else 5
            return await self._mine_tunnel(player, length)

        # Detect specific ore filter
        ore_filter = None
        ore_name_tr = "Maden"
        ore_map = {
            "demir": ("iron", "Demir"),
            "iron": ("iron", "Demir"),
            "komur": ("coal", "Komur"),
            "coal": ("coal", "Komur"),
            "elmas": ("diamond", "Elmas"),
            "diamond": ("diamond", "Elmas"),
            "bakir": ("copper", "Bakir"),
            "copper": ("copper", "Bakir"),
            "altin": ("gold", "Altin"),
            "gold": ("gold", "Altin"),
            "kukurt": ("sulfur", "Kukurt"),
            "sulfur": ("sulfur", "Kukurt"),
            "kalay": ("tin", "Kalay"),
            "tin": ("tin", "Kalay"),
            "kursun": ("lead", "Kursun"),
            "lead": ("lead", "Kursun"),
            "kiziltas": ("redstone", "Kiziltas"),
            "redstone": ("redstone", "Kiziltas"),
            "lapis": ("lapis", "Lapis"),
            "osmiyum": ("osmium", "Osmiyum"),
            "osmium": ("osmium", "Osmiyum"),
            "uranyum": ("uranium", "Uranyum"),
            "uranium": ("uranium", "Uranyum"),
            "zumrut": ("emerald", "Zumrut"),
            "emerald": ("emerald", "Zumrut"),
            "cinko": ("zinc", "Cinko"),
            "zinc": ("zinc", "Cinko"),
            "netherit": ("debris", "Netherit"),
            "debris": ("debris", "Netherit"),
        }
        for kw, (filt, tr_name) in ore_map.items():
            if kw in p:
                ore_filter = filt
                ore_name_tr = tr_name
                break

        # Parse optional user-specified bounding volume / radius (e.g. 32x32x32, 64x64x64, 32 blok)
        dim_str = None
        user_radius = None
        m_dim = re.search(r"(\d+)\s*x\s*(\d+)(?:\s*x\s*(\d+))?", p)
        if m_dim:
            dim_val = int(m_dim.group(1))
            if dim_val >= 100:
                user_radius = 64
            else:
                user_radius = max(8, min(dim_val, 64))
            dim_str = f"{dim_val}x{dim_val}x{dim_val}"
        else:
            m_blok = re.search(r"(\d+)\s*blok", p)
            if m_blok:
                user_radius = max(8, min(int(m_blok.group(1)), 64))
                dim_str = f"{user_radius} blok"

        # Check for 'all' / 'bütün' keywords (e.g. "bütün kömürleri topla", "tüm madenleri kaz")
        is_all = any(w in p for w in ["butun", "bütün", "tum", "tüm", "hepsi", "hepsini", "tamami", "tamamını", "her seyi", "her şeyi"])

        if is_all:
            count = 9999
        else:
            # Strip dimension patterns from prompt to prevent extracting 32 from 32x32x32 as count
            p_no_dim = re.sub(r"(\d+)\s*x\s*(\d+)(?:\s*x\s*(\d+))?", "", p)
            p_no_dim = re.sub(r"(\d+)\s*blok", "", p_no_dim)
            m_cnt = re.search(r"(\d+)", p_no_dim)
            count = min(max(int(m_cnt.group(1)), 1), 128) if m_cnt else 5

        # Detect home/base protection zone
        base_center, base_anchors, home_radius, is_inside_base = await self._detect_base_zone(player)

        # Surroundings Scan: Targeted user-radius or progressive scan (24 -> 48 -> 64)
        matching = []
        raw_ores_found = 0
        scan_radius = user_radius or 24

        def filter_ores(blocks_list):
            nonlocal raw_ores_found
            all_ores = [
                b for b in blocks_list
                if (b.category == "ore" or "ore" in (b.block or "").lower() or "debris" in (b.block or "").lower())
            ]
            if ore_filter:
                all_ores = [b for b in all_ores if ore_filter in (b.block or "").lower()]
            raw_ores_found = max(raw_ores_found, len(all_ores))
            # Strict House Protection: Never mine ores inside or under the house/base!
            safe = [b for b in all_ores if not is_inside_base(float(b.x or 0), float(b.y or 0), float(b.z or 0))]
            return safe

        # 1. SCANNING: Progressive scan around companion (or player fallback)
        if user_radius:
            try:
                res = await self.client.get_surroundings(companion=True, radius=user_radius)
                matching = filter_ores(res.blocks or [])
            except Exception:
                try:
                    res = await self.client.get_surroundings(player=player, radius=user_radius)
                    matching = filter_ores(res.blocks or [])
                except Exception as e:
                    safe_print(f"[ChatAgent] Targeted scan error (radius {user_radius}): {e}")
        else:
            for r in [24, 48, 64]:
                try:
                    res = await self.client.get_surroundings(companion=True, radius=r)
                    matching = filter_ores(res.blocks or [])
                except Exception:
                    try:
                        res = await self.client.get_surroundings(player=player, radius=r)
                        matching = filter_ores(res.blocks or [])
                    except Exception as e:
                        safe_print(f"[ChatAgent] Progressive scan error (radius {r}): {e}")
                scan_radius = r
                if len(matching) >= count:
                    break

        # Fallback wider scan if needed
        if not matching and scan_radius < 64:
            for r in [48, 64]:
                try:
                    res = await self.client.get_surroundings(companion=True, radius=r)
                    matching = filter_ores(res.blocks or [])
                    if not matching:
                        res = await self.client.get_surroundings(player=player, radius=r)
                        matching = filter_ores(res.blocks or [])
                    scan_radius = r
                    if matching:
                        break
                except Exception:
                    pass

        area_label = dim_str if dim_str else f"{scan_radius} blok"
        if not matching:
            if raw_ores_found > 0 and (base_center or base_anchors):
                return f"Ev guvenlik alani ({int(home_radius)} blok) icerisindeki madenler evinize zarar vermemek adina korunuyor Efendim. {area_label} dis alanda guvenli maden bulunamadi."
            return f"{area_label} alanda {ore_name_tr} madeni bulunamadi Efendim."

        # Get Jarvis's current position to calculate distances and home return point
        c_info = await self.client.companion_status()
        c_pos = c_info.position
        cx = getattr(c_pos, "x", None) if c_pos else None
        cy = getattr(c_pos, "y", None) if c_pos else None
        cz = getattr(c_pos, "z", None) if c_pos else None

        st = await self.client.get_status()
        p_obj = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
        px = getattr(p_obj, "x", 0.0) if p_obj else 0.0
        py = getattr(p_obj, "y", 64.0) if p_obj else 64.0
        pz = getattr(p_obj, "z", 0.0) if p_obj else 0.0

        cur_x = cx if cx is not None else px
        cur_y = cy if cy is not None else py
        cur_z = cz if cz is not None else pz

        # Home / Base return target: where Jarvis lives independently from the player!
        home_target = base_center if base_center else (int(cur_x), int(cur_y), int(cur_z))

        # Sort ores by distance from companion
        matching.sort(key=lambda b: math.hypot(float(b.x or 0) - cur_x, float(b.z or 0) - cur_z) + abs(float(b.y or 0) - cur_y))
        to_mine = matching[:count]

        # Instant chat acknowledgment
        count_desc = f"tespit edilen tum ({len(to_mine)} adet)" if is_all else f"{len(to_mine)} adet"
        try:
            await self._say(f"Maden operasyonu baslatiliyor Efendim: {area_label} alandaki {count_desc} {ore_name_tr} toplanacak...")
        except Exception:
            pass

        self.activity_state = f"{ore_name_tr} Madeni Kaziyor"

        # Check if Jarvis is currently inside base -> physically step outside first!
        try:
            if is_inside_base(cur_x, cur_y, cur_z):
                first_ore = to_mine[0]
                fox = float(first_ore.x or cur_x)
                foz = float(first_ore.z or cur_z)

                hx, hy, hz = home_target
                dx = fox - hx
                dz = foz - hz
                dist = math.hypot(dx, dz)
                if dist > 0.1:
                    dx /= dist
                    dz /= dist
                else:
                    dx, dz = 1.0, 0.0

                exit_x = hx + dx * (home_radius + 4.0)
                exit_z = hz + dz * (home_radius + 4.0)
                exit_y = cur_y

                safe_print(f"[ChatAgent] Companion inside base. Exiting to exterior ({exit_x:.1f}, {exit_y:.1f}, {exit_z:.1f})...")
                try:
                    await self._say("Maden kazisi icin evin disina cikiyorum Efendim...")
                except Exception:
                    pass

                # Physically walk outside
                await self.client.companion_move(float(exit_x), float(exit_y), float(exit_z), speed=1.35)
                for _ in range(16):
                    await asyncio.sleep(0.3)
                    try:
                        c_chk = await self.client.companion_status()
                        chk_pos = c_chk.position
                        if chk_pos and getattr(chk_pos, "x", None) is not None:
                            if not is_inside_base(chk_pos.x, chk_pos.y, chk_pos.z):
                                safe_print("[ChatAgent] Successfully exited house to exterior!")
                                break
                    except Exception:
                        pass
        except Exception as e:
            safe_print(f"[ChatAgent] House exit routine warning: {e}")

        # 2. AUTONOMOUS MINER STATE MACHINE: Tunneling, Digging, Reaching, Mining & Collecting
        mined_names = []

        for idx, ore_block in enumerate(to_mine, 1):
            ox = int(ore_block.x or 0)
            oy = int(ore_block.y or 0)
            oz = int(ore_block.z or 0)
            name = format_ore_name(ore_block.name or ore_block.block or ore_block.id or "")

            # A. Check distance between companion and target ore
            cur_dist = 999.0
            try:
                c_info = await self.client.companion_status()
                pos = c_info.position
                if pos and getattr(pos, "x", None) is not None:
                    cur_dist = math.sqrt((pos.x - ox)**2 + (pos.y - oy)**2 + (pos.z - oz)**2)
            except Exception:
                pass

            # B. If not within reach (> 4.2 blocks), navigate and tunnel towards ore!
            if cur_dist > 4.2:
                # First try direct walking
                try:
                    await self.client.companion_move(float(ox), float(oy), float(oz), speed=1.35)
                except Exception:
                    pass

                # Wait up to 1.5 seconds to see if walkable
                for _ in range(5):
                    await asyncio.sleep(0.3)
                    try:
                        c_info = await self.client.companion_status()
                        pos = c_info.position
                        if pos and getattr(pos, "x", None) is not None:
                            cur_dist = math.sqrt((pos.x - ox)**2 + (pos.y - oy)**2 + (pos.z - oz)**2)
                            if cur_dist <= 4.2:
                                break
                    except Exception:
                        pass

                # If still out of reach (> 4.2 blocks), ore is blocked by solid stone/dirt!
                # CARVE A 1x2 TUNNEL / SHAFT STEP-BY-STEP TOWARDS THE ORE:
                if cur_dist > 4.2:
                    safe_print(f"[ChatAgent] Ore at ({ox}, {oy}, {oz}) is encased in rock. Carving tunnel towards it...")
                    for step in range(35):
                        try:
                            c_info = await self.client.companion_status()
                            pos = c_info.position
                            if not pos or getattr(pos, "x", None) is None:
                                break
                            d = math.sqrt((pos.x - ox)**2 + (pos.y - oy)**2 + (pos.z - oz)**2)
                            if d <= 4.0:
                                cur_dist = d
                                break  # Reached mining reach!

                            dx = ox - pos.x
                            dy = oy - pos.y
                            dz = oz - pos.z

                            # Determine horizontal step direction
                            if abs(dx) >= abs(dz):
                                sx = 1 if dx > 0 else -1
                                sz = 0
                            else:
                                sx = 0
                                sz = 1 if dz > 0 else -1

                            # Determine vertical step direction
                            if dy <= -1.2:
                                sy = -1
                            elif dy >= 1.2:
                                sy = 1
                            else:
                                sy = 0

                            nx = int(pos.x) + sx
                            ny = int(pos.y) + sy
                            nz = int(pos.z) + sz

                            # Dig out 1x2 humanoid clearance:
                            # 1. Head block (ny + 1)
                            await self.client.companion_interact("break_block", x=nx, y=ny + 1, z=nz)
                            # 2. Foot block (ny)
                            await self.client.companion_interact("break_block", x=nx, y=ny, z=nz)
                            # 3. If digging down, also clear above head for clearance
                            if sy < 0:
                                await self.client.companion_interact("break_block", x=nx, y=ny + 2, z=nz)

                            # Physically step forward into the carved tunnel
                            await self.client.companion_move(nx + 0.5, float(ny), nz + 0.5, speed=1.2)
                            await asyncio.sleep(0.3)
                        except Exception as e:
                            safe_print(f"[ChatAgent] Tunneling step error: {e}")
                            break

            # C. Now within physical reach (<= 4.2 blocks): Mine the ore block!
            try:
                break_res = await self.client.companion_interact("break_block", x=ox, y=oy, z=oz)
                if break_res.success:
                    mined_names.append(name)
                await asyncio.sleep(0.15)
            except Exception as e:
                safe_print(f"[ChatAgent] Error breaking block at ({ox}, {oy}, {oz}): {e}")

            # D. Vein Mining: Check 6 immediate neighbors around (ox, oy, oz) for connected ores
            for adj_dx, adj_dy, adj_dz in [(1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)]:
                adj_x = ox + adj_dx
                adj_y = oy + adj_dy
                adj_z = oz + adj_dz
                try:
                    adj_res = await self.client.companion_interact("break_block", x=adj_x, y=adj_y, z=adj_z)
                    if adj_res.success and adj_res.details and "dropped" in adj_res.details:
                        dropped = adj_res.details.get("dropped", "")
                        if "ore" in dropped.lower() or "debris" in dropped.lower():
                            mined_names.append(format_ore_name(dropped))
                except Exception:
                    pass

            # E. Periodic progress announcement for large orders (every 25 blocks)
            if idx % 25 == 0 and idx < len(to_mine):
                try:
                    await self._say(f"Maden operasyonu devam ediyor: {len(mined_names)}/{len(to_mine)} {ore_name_tr} kazildi Efendim...")
                except Exception:
                    pass

        # 3. RETURN TO BASE / HOME: Jarvis lives far away from player independently!
        hx, hy, hz = home_target
        try:
            await self._say(f"Maden operasyonu tamamlandi Efendim. Toplam {len(mined_names)} maden toplandi, simdi eve donuyorum...")
        except Exception:
            pass

        try:
            await self.client.companion_move(float(hx), float(hy), float(hz), speed=1.35)
            # Wait for companion to step back into home base area
            for _ in range(30):
                await asyncio.sleep(0.4)
                try:
                    c_chk = await self.client.companion_status()
                    chk_pos = c_chk.position
                    if chk_pos and getattr(chk_pos, "x", None) is not None:
                        d_home = math.hypot(chk_pos.x - hx, chk_pos.z - hz)
                        if d_home <= 4.5:
                            break
                except Exception:
                    pass
            await self.client.companion_stop()
            self.activity_state = "Evde / Karargahta"
        except Exception as e:
            safe_print(f"[ChatAgent] Return home error: {e}")

        if mined_names:
            c = Counter(mined_names)
            summary = ", ".join([f"{cnt}x {n}" for n, cnt in c.items()])
            if is_all:
                return f"Efendim, {area_label} alandaki tum {ore_name_tr} madenleri kazildi ve dahili cantamda toplandi: {summary}. Eve dondum, karargahta bekliyorum. Isterseniz 'madenleri erit' diyerek firinda pisirmemi isteyebilirsiniz."
            return f"Efendim, maden operasyonu tamamlandi! {len(mined_names)} adet maden cikarildi ve dahili cantama alindi: {summary}. Eve dondum, karargahta bekliyorum. Isterseniz 'madenleri erit' diyerek firinda pisirmemi isteyebilirsiniz."
        return "Madenler cikarilirken bir sorun olustu Efendim."

    async def _mine_tunnel(self, player: str, length: int = 5) -> str:
        """Mines a 1x2 tunnel forward step-by-step with physical walking."""
        st = await self.client.get_status()
        p_obj = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
        if not p_obj:
            return "Konumunuz alinamadigi icin tunel acilamadi Efendim."

        px = int(p_obj.x or 0)
        py = int(p_obj.y or 0)
        pz = int(p_obj.z or 0)

        # Default forward direction along primary axis
        dx, dz = 1, 0
        try:
            c_st = await self.client.companion_status()
            pos = c_st.position
            cx = int(getattr(pos, "x", px) if pos else px)
            cz = int(getattr(pos, "z", pz) if pos else pz)
            if abs(pz - cz) > abs(px - cx):
                dx = 0
                dz = 1 if pz >= cz else -1
            else:
                dx = 1 if px >= cx else -1
                dz = 0
        except Exception:
            dx, dz = 1, 0

        mined_count = 0
        for i in range(1, length + 1):
            tx = px + (i * dx)
            tz = pz + (i * dz)

            # Step forward into previous excavated block
            try:
                prev_x = px + ((i - 1) * dx)
                prev_z = pz + ((i - 1) * dz)
                await self.client.companion_move(float(prev_x), float(py), float(prev_z), speed=1.1)
            except Exception:
                pass

            # Break 1x2 slice (feet & head)
            for ty in (py, py + 1):
                try:
                    res = await self.client.companion_interact("break_block", x=tx, y=ty, z=tz)
                    if res.success:
                        mined_count += 1
                except Exception:
                    pass
                await asyncio.sleep(0.18)

        # Run back to player
        try:
            await self.client.companion_follow(player=player, distance=2.5)
        except Exception:
            pass

        return f"Efendim, {length} blok uzunlugunda tunel acildi. Cikan tum bloklar dahili cantamda toplandi (Toplam {mined_count} blok kirildi)."

    async def _mine_staircase(self, player: str, p: str) -> str:
        """Digs a 45-degree downward staircase mine, placing torches and gathering exposed ores."""
        st = await self.client.get_status()
        p_obj = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
        if not p_obj:
            return "Konumunuz alinamadigi icin madene inilemedi Efendim."

        c_st = await self.client.companion_status()
        if not c_st.spawned:
            try:
                await self.client.companion_spawn(name="Jarvis", x=p_obj.x, y=p_obj.y, z=p_obj.z, dimension=p_obj.dimension)
            except Exception:
                pass

        px = int(p_obj.x or 0)
        py = int(p_obj.y or 0)
        pz = int(p_obj.z or 0)

        # Parse requested depth / steps (default 8, max 20)
        m = re.search(r"(\d+)", p)
        depth = min(max(int(m.group(1)), 3), 20) if m else 8

        # Determine forward direction
        dx, dz = 1, 0
        try:
            pos = c_st.position
            cx = int(getattr(pos, "x", px) if pos else px)
            cz = int(getattr(pos, "z", pz) if pos else pz)
            if abs(pz - cz) > abs(px - cx):
                dx = 0
                dz = 1 if pz >= cz else -1
            else:
                dx = 1 if px >= cx else -1
                dz = 0
        except Exception:
            dx, dz = 1, 0

        try:
            await self._say(f"Maden basamaklari aciliyor Efendim. {depth} basamak asagi iniyorum...")
        except Exception:
            pass

        mined_ores = []
        total_blocks = 0
        steps_completed = 0

        for step in range(1, depth + 1):
            sx = px + (step * dx)
            sz = pz + (step * dz)
            sy = py - step

            # Bedrock limit safety
            if sy <= -59:
                break

            # Physically move Jarvis down onto previous step
            prev_x = px + ((step - 1) * dx)
            prev_z = pz + ((step - 1) * dz)
            prev_y = py - (step - 1)
            try:
                await self.client.companion_move(float(prev_x), float(prev_y), float(prev_z), speed=1.1)
            except Exception:
                pass

            # Excavate 3-block vertical column at (sx, sz) for head clearance
            for ty in (sy + 1, sy + 2, sy + 3):
                try:
                    res = await self.client.companion_interact("break_block", x=sx, y=ty, z=sz)
                    if res.success:
                        total_blocks += 1
                        dropped = res.details.get("dropped", "") if res.details else ""
                        if "ore" in dropped.lower() or "debris" in dropped.lower():
                            mined_ores.append(format_ore_name(dropped))
                except Exception:
                    pass
                await asyncio.sleep(0.18)

            # Check side walls for exposed ores and mine them
            side_offsets = [
                (-dz, 0, dx),   # left wall
                (dz, 0, -dx),   # right wall
                (0, 0, 0),      # floor
            ]
            for ox, oy, oz in side_offsets:
                check_x = sx + ox
                check_y = sy + oy
                check_z = sz + oz
                try:
                    surr = await self.client.get_surroundings(radius=2, player=player)
                    for b in (surr.blocks or []):
                        if int(b.x or 0) == check_x and int(b.y or 0) == check_y and int(b.z or 0) == check_z:
                            if b.category == "ore" or "ore" in (b.block or "").lower():
                                b_res = await self.client.companion_interact("break_block", x=check_x, y=check_y, z=check_z)
                                if b_res.success:
                                    mined_ores.append(format_ore_name(b.block or b.name or ""))
                                    total_blocks += 1
                except Exception:
                    pass

            # Every 5 steps, place a torch on the wall for illumination
            if step % 5 == 0:
                torch_x = sx - dz
                torch_y = sy + 2
                torch_z = sz + dx
                try:
                    await self.client.companion_interact("place_block", x=torch_x, y=torch_y, z=torch_z, item="minecraft:torch")
                except Exception:
                    pass

            steps_completed += 1

        # Return to follow player
        try:
            await self.client.companion_follow(player=player, distance=2.5)
        except Exception:
            pass

        ore_summary = ""
        if mined_ores:
            c = Counter(mined_ores)
            ore_summary = " Bulunan madenler: " + ", ".join([f"{cnt}x {n}" for n, cnt in c.items()]) + "."

        return (
            f"Efendim, {steps_completed} basamakli maden merdiveni tamamlandi (Toplam {total_blocks} blok kazildi)."
            f"{ore_summary} Kazilan tum materyaller dahili cantamda toplandi."
        )

    async def _smelt_ores(self, player: str, p: str) -> str:
        """Autonomous smelting: Locates nearby furnaces, loads fuel and raw ores, and collects finished ingots."""
        filt = "all"
        if "demir" in p or "iron" in p:
            filt = "iron"
        elif "bakir" in p or "bakır" in p or "copper" in p:
            filt = "copper"
        elif "altin" in p or "altın" in p or "gold" in p:
            filt = "gold"

        c_st = await self.client.companion_status()
        if not c_st.spawned:
            p_obj = await self._get_player_obj(player)
            if p_obj:
                await self.client.companion_spawn(name="Jarvis", x=p_obj.x, y=p_obj.y, z=p_obj.z, dimension=p_obj.dimension)
            else:
                await self.client.companion_spawn(name="Jarvis")
            await asyncio.sleep(0.3)

        try:
            await self._say("Firinlar kontrol ediliyor ve ham madenler eritilmek uzere hazirlaniyor Efendim...")
        except Exception:
            pass

        self.activity_state = "Madenleri Eritiyor"

        res = await self.client.companion_smelt_ores(filter=filt, radius=32)
        if not res.success or not res.details:
            err = res.details.get("error", "Bilinmeyen hata") if res.details else "Firin bulunamadi"
            if "No furnaces" in err:
                return "Cevrede (32 blok) herhangi bir firin veya yuksek firin bulunamadi Efendim. Bir firin yerlestirirseniz madenleri eritebilirim."
            return f"Eritme islemi baslatilamadi: {err}"

        det = res.details
        raw_loaded = det.get("raw_loaded_total", 0)
        fuel_loaded = det.get("fuel_loaded_total", 0)
        ingots_collected = det.get("ingots_collected_total", 0)
        furnaces_found = det.get("furnaces_found", 0)

        loaded_ores = det.get("loaded_ores", [])
        collected_ingots = det.get("collected_ingots", [])

        if raw_loaded == 0 and ingots_collected == 0:
            inv_res = await self.client.companion_inventory()
            raw_in_inv = []
            if inv_res.slots:
                for s in inv_res.slots:
                    item_name = s.item.lower()
                    if "raw_" in item_name or "_ore" in item_name:
                        raw_in_inv.append(f"{s.count}x {s.name}")

            if not raw_in_inv:
                return "Cantamda eritilecek herhangi bir ham maden bulunamadi Efendim."
            return f"Cevredeki {furnaces_found} adet firinin haznesi su an dolu veya yakit yetersiz Efendim."

        msg_parts = []
        if raw_loaded > 0:
            c = Counter([f"{it.get('count', 1)}x {it.get('name', it.get('item', ''))}" for it in loaded_ores])
            summary = ", ".join(c.keys())
            msg_parts.append(f"{raw_loaded} adet ham maden ({summary}) firinlara yerlestirildi")
        if fuel_loaded > 0:
            msg_parts.append(f"{fuel_loaded} adet yakit eklendi")
        if ingots_collected > 0:
            c_ing = Counter([f"{it.get('count', 1)}x {it.get('name', it.get('item', ''))}" for it in collected_ingots])
            summary_ing = ", ".join(c_ing.keys())
            msg_parts.append(f"firindaki tamamlanmis {ingots_collected} adet kulce ({summary_ing}) cantama alindi")

        full_summary = "; ".join(msg_parts)
        return f"Efendim, {full_summary}. Eritme islemi basariyla surduruluyor."

    async def _collect_smelted(self, player: str) -> str:
        """Collects all finished smelted ingots from nearby furnaces into companion inventory."""
        res = await self.client.companion_collect_smelted(radius=32)
        if not res.success or not res.details:
            err = res.details.get("error", "Bilinmeyen hata") if res.details else "Firin bulunamadi"
            return f"Firinlardan esyalar alinamadi: {err}"

        det = res.details
        total = det.get("ingots_collected_total", 0)
        collected = det.get("collected_ingots", [])

        if total == 0:
            return "Cevredeki firinlarda henuz tamamlanmis bir kulce veya urun bulunmuyor Efendim."

        c = Counter([f"{it.get('count', 1)}x {it.get('name', it.get('item', ''))}" for it in collected])
        summary = ", ".join(c.keys())
        return f"Efendim, firinlardan toplam {total} adet kulce ({summary}) dahili cantama toplandi."

    async def _handle_prompt(self, player: str, prompt: str) -> str:
        self.last_owner = player
        # Normalize Turkish chars in the incoming prompt for reliable keyword matching
        # (Minecraft chat often garbles İ/ı/ş/ğ/ü/ö/ç)
        normalized = sanitize_for_minecraft(prompt)
        p = normalized.lower()

        # Questions detection for routing and guarding physical commands
        is_question = bool(re.search(r"\b(?:nereden|nerden|nasil|neden|kim|hangi|ne|neler|nedir|mu|mi|m\u0131|m\u00fc)\b|\?", p))

        # ---------------------------------------------------------------------
        # 1. Deterministic Fast-Path for Physical Commands (Guaranteed Zero-Lag)
        # ---------------------------------------------------------------------

        # 0.00 Jerk A Little — Havai Fişek & Parti Protokolü
        if any(w in p for w in ["jerk a little", "jerk a litle", "jerk alittle", "jerk-a-little"]):
            return await self._celebrate_jerk_a_little(player)

        # 0.0 Komutlar & Yardım Menüsü
        if any(re.search(rf"\b{w}\b", p) for w in ["komut", "komutlar", "komutlari", "komutlarin", "komut listesi", "yardim", "help", "neler yapabilirsin", "butun komutlar"]):
            return await self._show_help_menu(player, p)

        # 0. Canlan / Yeniden Doğ / Respawn
        if not is_question and any(re.search(rf"\b{w}\b", p) for w in ["canlan", "yeniden dog", "yeniden canlan", "respawn", "dog"]):
            try:
                st = await self.client.get_status()
                p_obj = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
                if p_obj:
                    await self.client.companion_spawn(name="Jarvis", x=p_obj.x, y=p_obj.y, z=p_obj.z, dimension=p_obj.dimension)
                else:
                    await self.client.companion_spawn(name="Jarvis")
                await asyncio.sleep(0.4)
                await self.client.companion_follow(player=player, distance=2.5)
                self.companion_was_spawned = True
                return "Sistemler yeniden baslatildi Efendim. Yaninizdayim."
            except Exception as e:
                return f"Yeniden dogurma hatasi: {e}"

        # 0.4 Sesli Yanıt / Konuşma Kapat / Aç (Voice Mute / Unmute)
        if any(w in p for w in ["sesi kapat", "sesi sustur", "konusmayi kapat", "konuşmayı kapat", "sesli yaniti kapat", "sesli yanıtı kapat", "sus", "mute"]):
            self.tts_enabled = False
            self.memory["tts_enabled"] = False
            self._save_memory()
            try:
                from jarvis_bridge.tts import get_tts
                get_tts().stop()
            except Exception:
                pass
            return "Sesli yanit sistemi kapatildi Efendim. Artik yalnizca yazili olarak iletisim kuracagim."

        if any(w in p for w in ["sesi ac", "sesi aç", "konusmayi ac", "konuşmayı aç", "sesli yaniti ac", "sesli yanıtı aç", "konus", "konuş", "unmute"]):
            self.tts_enabled = True
            self.memory["tts_enabled"] = True
            self._save_memory()
            return "Sesli yanit sistemi acildi Efendim."

        # 0.5 Yemek Ye / Beslen / İyileş / Canını Doldur
        if not is_question and any(re.search(rf"\b{w}\b", p) for w in ["yemek ye", "beslen", "canini doldur", "iyiles", "et ye", "biftek ye", "ac misin"]):
            try:
                return await self._eat_food(silent=False)
            except Exception as e:
                return f"Yemek yerken bir sorun olustu: {e}"

        # 0.6 Korumalık Modu & Nöbetçi Modu
        if not is_question and any(re.search(rf"\b{w}\b", p) for w in ["nobet tut", "nobete gec", "burayi koru", "alani koru", "nobetci"]):
            return await self._start_sentry_mode(player)
        if not is_question and any(re.search(rf"\b{w}\b", p) for w in ["nobeti birak", "nobet bitti", "nobeti bitir", "nobeti sonlandir"]):
            return await self._stop_sentry_mode(player)
        if not is_question and any(re.search(rf"\b{w}\b", p) for w in ["koruma ac", "koru beni", "koruma modu", "bodyguard"]):
            self.guard_mode = True
            return "Korumalik protokolleri aktif Efendim. Sizi tum tehditlere karsi koruyacagim."
        if not is_question and any(re.search(rf"\b{w}\b", p) for w in ["koruma kapat", "korumayi kapat", "koruma pasif"]):
            self.guard_mode = False
            return "Korumalik protokolleri pasife alindi Efendim."

        # 0.7 Durum Raporu & Telemetri
        if not is_question and any(re.search(rf"\b{w}\b", p) for w in ["durum", "rapor", "ne yapiyorsun", "telemetri", "statuler"]):
            return await self._get_status_report(player)
        # 0.77 Modpack & Tarif Danismani (Pillar 4: Recipes & Modded Machines)
        is_machine_cmd = any(w in p for w in ["makineler", "makineleri tara", "makine durumu", "jeneratorler", "jeneratörler", "enerji durumu", "yakin makineler"])
        if is_machine_cmd:
            return await self._handle_machine_query(player, p)

        is_recipe_cmd = (
            any(w in p for w in ["nasil yapilir", "nasıl yapılır", "nasil uretilir", "nasıl üretilir", "tarifi ne", "nasil craftlanir", "nasıl craftlanır", "yapilisi ne", "yapılışı ne", "tarifi"])
            or (any(w in p for w in ["nasil", "nasıl"]) and any(w in p for w in ["yapilir", "yapılır", "uretir", "üretir", "craft"]))
        )
        if is_recipe_cmd:
            return await self._handle_recipe_query(player, p)

        # 0.70 Otonom Ciftcilik & Hayvancilik (Farming, Replanting, Breeding, Shearing)
        is_farm_cmd = (
            any(w in p for w in ["ciftlik", "çiftlik", "hasat", "hasat et", "ekin", "ekinleri topla", "bugday topla", "buğday topla", "patates topla", "havuc topla", "havuç topla", "koyun kirp", "koyunları kırp", "yun topla", "yün topla", "hayvanlari besle", "hayvanları besle", "ciftlestir", "çiftleştir"])
            or (any(w in p for w in ["tarla", "tarlayi", "ekin", "ekinler"]) and any(w in p for w in ["topla", "hasat", "bic", "biç"]))
        ) and not is_question
        if is_farm_cmd:
            return await self._handle_farming_command(player, p)

        # 0.71 Otonom Odunculuk & Agaclandirma (Lumberjack & Reforestation)
        is_lumber_cmd = (
            any(w in p for w in ["odun kes", "odunculuk", "agac kes", "ağaç kes", "agaclari devir", "ağaçları devir", "orman kes", "ormani temizle", "ormancı", "ormanci", "odun topla"])
            or (any(w in p for w in ["agac", "ağaç", "odun", "kutuk", "kütük"]) and any(w in p for w in ["kes", "devir", "kir", "kır"]))
        ) and not is_question
        if is_lumber_cmd:
            return await self._handle_lumberjack_command(player, p)

        # 0.72 Otonom Zanaatkarlik / Crafting (Autonomous Crafting Engine)
        is_craft_cmd = (
            any(w in p for w in ["craft yap", "craftla", "kendine kazma yap", "kendine alet yap", "aletlerini yenile", "alet ikmali", "ekmek yap", "mesale yap", "meşale yap", "demir kazma yap", "elmas kazma yap", "kilic yap", "kılıç yap", "balta yap", "kova yap", "makas yap"])
            or (any(w in p for w in ["kendine", "bana"]) and any(w in p for w in ["kazma yap", "balta yap", "kilic yap", "kılıç yap", "alet yap", "ekmek yap"]))
        ) and not is_question
        if is_craft_cmd:
            return await self._handle_craft_command(player, p)

        # 0.73 FTB Modlu Makineler & Maden Cogaltma (Mekanism / Thermal / EnderIO)
        is_tech_cmd = (
            any(w in p for w in ["mekanism", "maden cogalt", "maden çoğalt", "cevherleri cogalt", "cevherleri çoğalt", "makineleri besle", "makinelerde erit", "makinelerde isle", "makinelerde işle", "enrichment", "pulverizer", "sag mill", "modlu makineler"])
            or (any(w in p for w in ["makine", "makineler"]) and any(w in p for w in ["calistir", "çalıştır", "besle", "doldur"]))
        ) and not is_question
        if is_tech_cmd:
            return await self._handle_tech_command(player, p)

        # 0.74 Magara Kesfi, Aydinlatma & Spawner Notralizasyonu (Spelunking & Raider)
        is_spelunk_cmd = (
            any(w in p for w in ["etrafi aydinlat", "etrafı aydınlat", "magarayi aydinlat", "mağarayı aydınlat", "karanlik yerlere mesale", "karanlık yerlere meşale", "mesale koy", "meşale koy", "spawner yok et", "spawner kir", "spawnerı kır", "spawneri kir", "spawneri aydinlat", "spawnerı aydınlat", "zindani temizle", "zindanı temizle", "dungeon temizle"])
            or (any(w in p for w in ["spawner", "zindan"]) and any(w in p for w in ["kir", "kır", "yok et", "etkisiz", "aydinlat", "aydınlat"]))
            or (any(w in p for w in ["mesale", "meşale", "aydinlat", "aydınlat"]) and any(w in p for w in ["koy", "diz", "yerlestir", "etraf"]))
        ) and not is_question
        if is_spelunk_cmd:
            return await self._handle_spelunking_command(player, p)


        # 0.78 Otonom Insaat Motoru (Pillar 2: Bridges, Shelters, Walls, Platforms)
        is_build_status_or_cancel = any(w in p for w in [
            "insaati durdur", "inşaatı durdur", "yapimi durdur", "yapımı durdur", "insaati iptal et", "inşaatı iptal et",
            "insaat durumu", "inşaat durumu", "insaat status", "inşaat status", "insaat nasil", "inşaat nasıl",
            "kac blok kaldi", "kaç blok kaldı"
        ])
        is_build_cmd = (
            is_build_status_or_cancel
            or any(w in p for w in ["kopru yap", "köprü yap", "kopru kur", "köprü kur", "kopru cek", "köprü çek"])
            or any(w in p for w in ["siginak yap", "sığınak yap", "barinak yap", "barınak yap", "bunker yap", "bunker kur", "ev yap", "ev insa et", "kulube yap"])
            or any(w in p for w in ["duvar or", "duvar ör", "duvar yap", "barikat kur", "barikat yap"])
            or any(w in p for w in ["platform yap", "platform kur", "zemin yap", "zemin dose"])
            or (any(w in p for w in ["kopru", "köprü", "siginak", "sığınak", "barikat", "duvar"]) and any(w in p for w in ["yap", "inşa", "kur", "ör", "or"]))
        )
        if (not is_question or is_build_status_or_cancel) and is_build_cmd:
            return await self._handle_build_command(player, p)

        # 0.77 Hedef Sandık İşaretleme (Marker Tool & Chat)
        is_marker_cmd = any(w in p for w in [
            "burayi isaretle", "burayı işaretle", "buraya isaret koy", "buraya işaret koy",
            "hedef sandik burasi", "hedef sandık burası", "sandik yeri burasi", "sandık yeri burası",
            "sandik buraya", "sandık buraya", "baktigim yeri isaretle", "baktığım yeri işaretle",
            "isaretleri temizle", "işaretleri temizle", "isaretleri sifirla", "işaretleri sıfırla",
            "isaretleri sil", "işaretleri sil", "isaretleri goster", "işaretleri göster",
            "isaretler nerede", "işaretler nerede", "isaretleme modu", "işaretleme modu"
        ]) or bool(re.search(r"\b\d+\.\s*sand[ıi]k\s+buras[ıi]\b", p))
        if not is_question and is_marker_cmd:
            return await self._handle_marker_command(player, p)

        # 0.78 İşaretli Yerlere Sandıkları Taşıma (Marker Relocation)
        is_move_to_markers = (
            any(w in p for w in ["isaretledigim", "işaretlediğim", "isaretli", "işaretli"])
            and any(w in p for w in ["tasi", "taşı", "yerlestir", "yerleştir", "koy", "diz"])
        )
        if not is_question and is_move_to_markers:
            return await self._move_chests_to_markers(player)

        # 0.785 Alt Kattaki Depoya / Boş Sandıklara Taşıma (Hamallık & Eski Sandıkları Toplama)
        is_transfer_to_basement = (
            (any(w in p for w in ["depoya", "alt kata", "alt kattaki", "asagi kata", "aşağı kata", "mahzene", "bodruma"])
             and any(w in p for w in ["tasi", "taşı", "aktar", "bosalt", "boşalt", "indir"]))
            or any(w in p for w in [
                "sandiklari depoya tasi", "sandıkları depoya taşı",
                "yukaridaki sandiklari alt kata tasi", "yukarıdaki sandıkları alt kata taşı",
                "sandiklari alt kata tasi", "sandıkları alt kata taşı",
                "bos sandiklara tasi", "boş sandıklara taşı",
                "esyalari depoya tasi", "eşyaları depoya taşı"
            ])
        )
        if not is_question and is_transfer_to_basement:
            return await self._transfer_chests_to_basement(player)

        # 0.78 Jarvis Lojistik Asası (Wand) Talebi
        is_wand_req = any(re.search(rf"\b{re.escape(w)}\b", p) for w in [
            "wand", "asa", "lojistik asasi", "lojistik asası", "bana wand", "bana asa",
            "wand ver", "asa ver", "wand ekle", "asa ekle", "lojistik asasi ver"
        ])
        if is_wand_req:
            return await self._give_logistics_wand(player)

        # 0.785 Hangi Eşyaların Hangi Sandığa Aktarılacağı Rehberi
        if any(w in p for w in [
            "hangi esyalar hangi sandiga", "hangi eşyaların hangi sandığa",
            "hangi sandiga ne", "hangi sandığa ne", "sandiklari nasil ayarlarim", "sandıkları nasıl ayarlarım",
            "nasil sandik secerim", "nasıl sandık seçerim"
        ]):
            return (
                "Efendim, eşyaları sandıklara yönlendirmenin 2 mükemmel yolu var:\n"
                "1. 🌟 JARVIS LOJİSTİK ASASI (Önerilen - En Hızlısı):\n"
                "   • 'jarvis bana wand ver' diyerek asayı alın.\n"
                "   • Sandığa Shift + Sağ Tık: Sandıktaki eşya türlerini filtreye kopyalar.\n"
                "   • Sandığa Normal Sağ Tık: Çevredeki tüm dağınık sandıklardan ve çantanızdan o eşyaları çekip hedef sandığa doldurur!\n"
                "   • Havaya Sağ Tık: 27 yuvalı hayalet filtre menüsünü açar.\n"
                "2. 🏷️ ÖRS VE TABELA ETİKETLERİ:\n"
                "   • Bir sandığı örste '[Madenler]', '[Odunlar]', '[Yemekler]', '[Mob Loot]' olarak isimlendirirseniz veya üstüne bu isimde tabela asarsanız, Jarvis 'depoyu düzenle' dediğinizde eşyaları doğrudan bu sandıklara gruplar."
            )

        # 0.79 Akıllı Kategori Bazlı Depo Düzenleme & Otomatik Üst Kat Double Chest (Master Storage Logistics)
        is_organize_cmd = (
            bool(re.search(r"madenleri\s+bir\s+sand[ıi]kta.*yemekleri\s+bir\s+sand[ıi]kta", p))
            or bool(re.search(r"\b(?:b[uü]t[uü]n|t[uü]m)\s+(?:chestleri|sand[ıi]klar[ıi]|kasalar[ıi]|kutular[ıi])\s+(?:d[uü]zenle|organize\s+et|toparla|ay[ıi]kla|s[ıi]n[ıi]fland[ıi]r)\b", p))
            or bool(re.search(r"\b(?:chestleri|sand[ıi]klar[ıi])\s+(?:organize\s+et|kategorize\s+et|ay[ıi]kla|s[ıi]n[ıi]fland[ıi]r|b[oö]l)\b", p))
            or bool(re.search(r"\b(?:depoyu|depolar[ıi])\s+(?:d[uü]zenle|organize\s+et|toparla)\b", p))
            or bool(re.search(r"\bayr[ıi]\s+sand[ıi]klara\s+(?:topla|koy|yerlestir|aktar)\b", p))
            or "butun chestleri duzenle" in p or "tum chestleri duzenle" in p
            or "butun sandiklari duzenle" in p or "tum sandiklari duzenle" in p
            or "chestleri duzenle" in p
        )
        if not is_question and is_organize_cmd:
            return await self._organize_storage(player, p)

        # 0.8 Sandık Düzenleme & Sıralama
        is_sort_cmd = any(w in p for w in [
            "sandigi duzenle", "sandigi düzenle", "sandiklari duzenle", "sandıkları düzenle",
            "sandigi sirala", "sandığı sırala", "sandiklari sirala", "sandıkları sırala",
            "sandik duzenle", "sandik sirala", "sandiklari toparla", "sandigi toparla"
        ])
        if not is_question and is_sort_cmd:
            return await self._sort_chests(player)

        # 0.81 Sandıktan / Kasadan Eşya Getirme (Retrieve)
        is_retrieve_cmd = (
            (any(w in p for w in ["bana", "sandiktan", "sandıktan", "kasadan", "depodan", "kutudan"]) and any(w in p for w in ["getir"]))
            or bool(re.search(r"\b(?:bana|sandiktan|sandıktan|kasadan|depodan|kutudan)\s+.*?\bgetir\b", p))
        ) and not any(w in p for w in ["madenleri ver", "esyalari ver", "esyaları ver", "madenleri getir"])
        if not is_question and is_retrieve_cmd:
            return await self._retrieve_item_for_player(player, p)

        # 0.82 Sandık Taraması, İnceleme ve Depolama (Lojistik)
        if any(w in p for w in ["sandik ara", "sandiklari bul", "sandiklari tara", "yakin sandiklar"]):
            return await self._scan_chests(player)
        if any(w in p for w in ["sandikta ne var", "sandigi incele", "sandigi kontrol et", "sandik icerigi"]):
            return await self._inspect_chest(player)
        is_deposit_cmd = (
            any(w in p for w in [
                "sandiga bosalt", "sandığa boşalt", "sandiga koy", "sandığa koy", "sandiklara koy", "sandıklara koy",
                "madenleri sandiga koy", "madenleri sandığa koy", "topladigin madenleri bos sandiklara koy",
                "topladığın madenleri boş sandıklara koy", "sandiga yerlestir", "sandiklara yerlestir",
                "sandiga aktar", "sandiklara aktar", "sandiga depola", "sandiklara depola", "sandiklari doldur",
                "madenleri koy", "esyalari koy"
            ])
            or (any(w in p for w in ["sandik", "sandiga", "sandiklar", "sandiklara", "fici", "ficilara", "kutu"]) and any(w in p for w in ["koy", "yerlestir", "aktar", "depola", "bosalt"]))
        )
        if not is_question and is_deposit_cmd:
            return await self._deposit_to_chest(player, p)

        # 0.9 Ceset & Son Ölüm Yeri (Yürüyerek Yol Gösterme ve Öncülük)
        if any(w in p for w in ["oldugum yer", "cesedim", "olum noktam", "son olum yerim", "oldugum yer nerede", "cesedime gotur", "oldugum yere gotur", "cesedimi bul"]):
            return await self._start_guiding(player, "son_olum_yeri")

        # 0.95 Eve Gidelim & Eve Yürüyerek Yol Gösterme
        if not is_question and any(w in p for w in ["eve gidelim", "eve gotur", "eve donelim", "eve git", "eve don"]):
            return await self._start_guiding(player, "ev")

        # 0.10 Konumları Listele
        if any(w in p for w in ["konumlari listele", "kayitli yerler", "hafiza", "noktalar", "kayitli konumlar"]):
            return await self._list_waypoints()

        # 0.11 Konum Kaydet
        if not is_question and re.search(r"\bkaydet\b", p) and any(re.search(rf"\b{w}\b", p) for w in ["konum", "burayi", "nokta", "yer", "ev", "maden", "portal", "sandik"]):
            m = re.search(r"(?:burayi|konumu|noktayi)?\s*(.*?)\s*(?:olarak\s*)?kaydet", p)
            wp_name = m.group(1).strip() if m else ""
            if not wp_name or wp_name in ["burayi", "konum", "yeri"]:
                wp_name = "Nokta"
            return await self._save_waypoint(player, wp_name)

        # 0.12 Konum Sil
        if not is_question and any(re.search(rf"\b{w}\b", p) for w in ["sil", "kaldir"]) and any(re.search(rf"\b{w}\b", p) for w in ["konum", "nokta", "yer", "ev", "maden", "portal"]):
            m = re.search(r"\b(?:konumu|noktayi)?\s*(.*?)\s*(?:konumunu\s*)?(?:sil|kaldir)\b", p)
            wp_name = m.group(1).strip() if m else ""
            if wp_name:
                return await self._delete_waypoint(wp_name)

        # 0.13 Konum Sorgula / Nerede
        if any(w in p for w in ["nerede", "neresi", "nerde"]):
            m = re.search(r"(.*?)\s*(?:nerede|neresi|nerde)", p)
            target = m.group(1).strip() if m else ""
            if target and target not in ["sen", "ben", "jarvis"]:
                wp_res = await self._where_is_waypoint(player, target)
                if wp_res:
                    return wp_res
                elif not is_question and not self.gemini_chat:
                    return f"Hafizamda '{target}' adinda bir konum kaydi bulunamadi Efendim."

        # 0.14 Konuma Git (Işınlanma yerine yürüyerek rehberlik)
        if not is_question and any(w in p for w in ["madene git", "portala git"]):
            m = re.search(r"(.*?)(?:e|a)\s*(?:git|don)", p)
            target = m.group(1).strip() if m else "ev"
            return await self._start_guiding(player, target)

        # A. Takip et / Yanıma gel / Peşime takıl / Gel / Buraya gel / Işınlan
        if not is_question and any(re.search(rf"\b{w}\b", p) for w in ["takip", "follow", "yanima", "pesime", "gel", "buraya gel", "isinlan"]):
            self.guide_target = None
            self.sentry_post = None
            self.activity_state = "Takipte"
            try:
                st = await self.client.get_status()
                p_obj = next((x for x in (st.players or []) if x.name.lower() == player.lower()), None)
                c_st = await self.client.companion_status()
                if not c_st.spawned and p_obj:
                    await self.client.companion_spawn(name="Jarvis", x=p_obj.x, y=p_obj.y, z=p_obj.z, dimension=p_obj.dimension)
                elif p_obj:
                    await self.client.companion_action("teleport", x=p_obj.x + 1.0, y=p_obj.y, z=p_obj.z + 1.0)
                else:
                    await self.client.companion_spawn(name="Jarvis")
                await self.client.companion_follow(player=player, distance=2.5)
                return "Yaninizdayim Efendim, takipteyim."
            except Exception as e:
                safe_print(f"[ChatAgent] Follow error: {e}")
                return "Takip komutunda bir sorun olustu Efendim."

        # B. Dur / Bekle / Kal / Stop
        if any(re.search(rf"\b{w}\b", p) for w in ["dur", "stop", "bekle", "kal", "hareket etme"]):
            self.guide_target = None
            self.activity_state = "Bekliyor"
            try:
                await self.client.companion_stop()
                return "Bekliyorum Efendim."
            except Exception as e:
                safe_print(f"[ChatAgent] Stop error: {e}")
                return "Durdurma komutunda bir sorun olustu Efendim."

        # C. Eşyaları / Madenleri Ver / Getir / Teslim Et
        if any(w in p for w in ["madenleri ver", "esyalari ver", "esyaları ver", "cantayi bosalt", "cantayı bosalt", "çantayı boşalt", "çantayı bosalt", "cantadakileri ver", "çantadakileri ver", "getir", "bana ver", "esyalari topla", "madenleri topla", "itemleri ver", "teslim et", "madenleri getir"]):
            try:
                delivered_items = []
                try:
                    res = await self.client.companion_give_items(player=player)
                    if res.success and res.details and "delivered_items" in res.details:
                        delivered_items = res.details["delivered_items"]
                except Exception:
                    pass

                if delivered_items:
                    c = Counter([f"{it.get('count', 1)}x {it.get('name', it.get('item', ''))}" for it in delivered_items])
                    summary = ", ".join(c.keys())
                    return f"Efendim, cantamdaki tum esyalar ve madenler ({summary}) size teslim edildi."
                return "Cevrenizdeki ve cantamdaki tum esyalar envanterinize aktarildi Efendim."
            except Exception as e:
                return f"Esya teslim hatasi: {e}"

        # C.5 Madenleri / Ham Maddeleri Fırında Erit / Pişir (Smelting Logistics)
        is_smelt_cmd = (
            any(w in p for w in ["erit", "pisir", "pişir", "firinla", "fırınla", "kulce yap", "külçe yap", "kulceye donustur", "külçeye dönüştür"])
            or (any(w in p for w in ["maden", "demir", "bakir", "altin", "raw"]) and any(w in p for w in ["erit", "yak", "pisir", "pişir"]))
            or any(w in p for w in ["firini calistir", "fırını çalıştır", "firina koy", "fırına koy", "firini kullan", "fırını kullan"])
        ) and not is_question

        if is_smelt_cmd:
            try:
                return await self._smelt_ores(player, p)
            except Exception as e:
                safe_print(f"[ChatAgent] Smelting error: {e}")
                return f"Eritme isleminde bir sorun olustu Efendim: {e}"

        # C.6 Fırındaki Külçeleri / Eriyikleri Topla
        is_collect_smelted = (
            any(w in p for w in ["eriyenleri al", "eriyenleri topla", "kulceleri al", "külçeleri al", "firindan al", "fırından al", "firini bosalt", "fırını boşalt", "firinlari topla", "fırınları topla"])
            and not is_question
        )
        if is_collect_smelted:
            try:
                return await self._collect_smelted(player)
            except Exception as e:
                safe_print(f"[ChatAgent] Collect smelted error: {e}")
                return f"Firin urunleri toplanirken bir sorun olustu Efendim: {e}"

        # D. Madene İnme / Merdiven Kazma (Staircase Mining)
        if any(w in p for w in ["madene in", "merdiven", "asagi kaz", "asagi in", "maden ocagi", "derine in", "maden yolu"]):
            try:
                return await self._mine_staircase(player, p)
            except Exception as e:
                safe_print(f"[ChatAgent] Staircase mining error: {e}")
                return "Maden merdiveni acilirken bir sorun olustu Efendim."

        # E. Maden Kazma / Kırma / Çıkarma / Tünel / Maden Kasma (Physical Autonomous Mining)
        is_question = bool(re.search(r"\b(?:nereden|nerden|nasil|neden|kim|hangi|mu|mi|m\u0131|m\u00fc)\b|\?", p))
        is_mining_cmd = (
            bool(re.search(r"\b(?:kaz\w*|kir\w*|cikar\w*|topla\w*|kas\w*|madencilik|tunel)\b", p))
            and not is_question
        )
        if is_mining_cmd:
            try:
                return await self._mine_ores(player, p)
            except Exception as e:
                safe_print(f"[ChatAgent] Mining error: {e}")
                return "Maden kazma isleminde bir sorun olustu Efendim."

        # F. Maden / Cevher Taraması (Ore Radar & Scanning)
        is_scan_cmd = (
            bool(re.search(r"\b(?:tara\w*|radar|tespit)\b", p))
            or (any(w in p for w in ["maden", "cevher"]) and any(w in p for w in ["ara", "bul", "var mi", "var miydi"]))
        )
        if is_scan_cmd:
            try:
                # Default 128x128x128 volume is radius 64 (-64 to +64 in X, Y, Z)
                radius = 64
                # Check for explicit radius (e.g. 128x128x128, 128, 64, 32)
                m = re.search(r"(\d+)(?:x\d+)?", p)
                if m:
                    r_val = int(m.group(1))
                    if r_val >= 100:
                        radius = 64  # 128x128x128 is radius 64
                    else:
                        radius = max(8, min(r_val, 128))

                res = await self.client.get_surroundings(player=player, radius=radius)
                blocks = res.blocks or []
                ores = [
                    format_ore_name(b.name or b.block or b.id or "")
                    for b in blocks
                    if (b.category == "ore" or "ore" in (b.block or "").lower() or "debris" in (b.block or "").lower())
                ]
                dim_str = "128x128x128" if radius == 64 else f"{radius*2}x{radius*2}x{radius*2}"
                if ores:
                    counts = Counter(ores)
                    summary = ", ".join([f"{count}x {name}" for name, count in counts.most_common(8)])
                    return f"{dim_str} alanda tespit edilen madenler (Toplam {len(ores)} cevher): {summary} Efendim."
                else:
                    return f"{dim_str} alanda degerli maden bulunamadi Efendim."
            except Exception as e:
                safe_print(f"[ChatAgent] Scan error: {e}")
                return "Maden taramasinda bir sorun olustu Efendim."

        # D. Can / Sağlık / Açlık / Stat / Durum
        if not is_question and any(re.search(rf"\b{w}\b", p) for w in ["can", "saglik", "hp", "aclik", "durum", "stat", "koordinat"]):
            try:
                st = await self.client.get_status()
                p_info = next((pl for pl in (st.players or []) if pl.name.lower() == player.lower()), None)
                if p_info:
                    health = round(p_info.health or 20.0, 1)
                    food = p_info.food_level or 20
                    coords = f"X: {int(p_info.x or 0)}, Y: {int(p_info.y or 0)}, Z: {int(p_info.z or 0)}"
                    return f"Caniniz: {health}/20, Acliginiz: {food}/20, Konumunuz: {coords} Efendim."
                return "Durum bilgisi alinamadi Efendim."
            except Exception as e:
                return "Durum sorgusunda hata olustu Efendim."

        # E. Gündüz / Sabah yap
        if not is_question and any(re.search(rf"\b{w}\b", p) for w in ["gunduz", "sabah", "gunes", "day"]):
            try:
                await self.client.execute_command("time set day")
                return "Gun aydinlatildi Efendim."
            except Exception as e:
                return f"Zaman komutu hatasi: {e}"

        # F. Gece yap
        if not is_question and any(re.search(rf"\b{w}\b", p) for w in ["gece", "aksam", "karanlik", "night"]):
            try:
                await self.client.execute_command("time set 18000")
                return "Gece vakti yapildi Efendim."
            except Exception as e:
                return f"Zaman komutu hatasi: {e}"

        # G. Hava durumu / Yağmur durdur / Havayı temizle / Havayı açık yap
        if not is_question and any(re.search(rf"\b{w}\b", p) for w in ["hava", "yagmur", "firtina", "gokyuzu", "clear", "temizle", "acik yap"]):
            try:
                await self.client.execute_command("weather clear")
                return "Hava temizlendi Efendim."
            except Exception as e:
                return f"Hava komutu hatasi: {e}"

        # H. Basit Selamlama
        if not is_question and any(re.search(rf"\b{w}\b", p) for w in ["selam", "merhaba", "nasilsin", "naber", "gunaydin", "iyi aksamlar", "hey"]):
            return "Iyi gunler Efendim, emrinizdeyim."

        # I. Kimsin
        if not is_question and any(re.search(rf"\b{w}\b", p) for w in ["kimsin", "nesin"]):
            return "Ben Jarvis, Minecraft yolculugunuzdaki sadik yardimcinizim Efendim."

        # ---------------------------------------------------------------------
        # 2. Conversational / Complex Queries -> Routed to Gemini 2.0 Flash
        # ---------------------------------------------------------------------
        if self.gemini_chat:
            try:
                resp = await self.gemini_chat.send_message(f"Oyuncu {player}: {prompt}")
                if resp.text:
                    return resp.text.strip()
            except Exception as e:
                err_msg = str(e)
                if "404" in err_msg or "no longer available" in err_msg or "NOT_FOUND" in err_msg:
                    fallback_models = ["gemini-2.0-flash", "gemini-1.5-flash", "gemini-2.0-flash-lite"]
                    current_mod = getattr(self, "gemini_model", "gemini-2.0-flash")
                    next_mod = next((m for m in fallback_models if m != current_mod), "gemini-1.5-flash")
                    safe_print(f"[ChatAgent] Model {current_mod} unavailable ({e}), retrying with fallback model {next_mod}...")
                    try:
                        from google.genai import types
                        self._setup_gemini_chat(types, model_name=next_mod)
                        resp = await self.gemini_chat.send_message(f"Oyuncu {player}: {prompt}")
                        if resp.text:
                            return resp.text.strip()
                    except Exception as e2:
                        safe_print(f"[ChatAgent] Fallback model error: {e2}")
                elif "429" in err_msg or "RESOURCE_EXHAUSTED" in err_msg or "Quota" in err_msg:
                    safe_print(f"[ChatAgent] Rate limit reached (429/ResourceExhausted). Executing deterministic fallback.")
                else:
                    safe_print(f"[ChatAgent] Gemini chat error ({e}), falling back...")

        return "Emredersiniz Efendim."


async def main():
    agent = JarvisChatAgent()
    await agent.start()


if __name__ == "__main__":
    asyncio.run(main())
