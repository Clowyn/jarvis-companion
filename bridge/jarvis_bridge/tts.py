"""
Jarvis Turkish Neural Text-to-Speech (TTS) Engine.
Utilizes Microsoft Neural Voice (tr-TR-AhmetNeural) via edge-tts with pygame.mixer
audio playback for high-fidelity, autonomous voice feedback in Minecraft.
"""

import asyncio
import io
import logging
import os
import queue
import tempfile
import threading
from typing import Optional

logger = logging.getLogger("jarvis.tts")

DEFAULT_VOICE = "tr-TR-AhmetNeural"
FALLBACK_VOICE = "tr-TR-EmelNeural"


class JarvisTTS:
    def __init__(self, voice: str = DEFAULT_VOICE):
        self.voice = voice
        self._queue: queue.Queue[str] = queue.Queue()
        self._stop_event = threading.Event()
        self._worker_thread: Optional[threading.Thread] = None
        self._mixer_initialized = False

    def _init_mixer(self) -> bool:
        if self._mixer_initialized:
            return True
        try:
            import pygame
            pygame.mixer.init()
            self._mixer_initialized = True
            return True
        except Exception as e:
            logger.error(f"[Jarvis TTS] Failed to initialize pygame mixer: {e}")
            return False

    def start(self) -> None:
        if self._worker_thread and self._worker_thread.is_alive():
            return
        self._stop_event.clear()
        self._worker_thread = threading.Thread(target=self._run_worker, daemon=True, name="JarvisTTSWorker")
        self._worker_thread.start()
        logger.info(f"[Jarvis TTS] Worker started with voice {self.voice}")

    def stop(self) -> None:
        self._stop_event.set()
        self._queue.put("")  # unblock
        try:
            import pygame
            if self._mixer_initialized and pygame.mixer.get_init():
                pygame.mixer.music.stop()
        except Exception:
            pass

    def speak(self, text: str) -> None:
        """Enqueue speech to be spoken in the background."""
        if not text or not text.strip():
            return
        cleaned = self._clean_text(text)
        if not cleaned:
            return
        if not self._worker_thread or not self._worker_thread.is_alive():
            self.start()
        self._queue.put(cleaned)

    def _clean_text(self, text: str) -> str:
        """Strip Minecraft formatting codes and markdown for clean pronunciation."""
        # Remove Minecraft section symbol color codes like §a, §6, etc.
        import re
        t = re.sub(r"§[0-9a-fk-or]", "", text)
        # Remove markdown bold/italics
        t = re.sub(r"[*_`~#]", "", t)
        return t.strip()

    def _run_worker(self) -> None:
        if not self._init_mixer():
            logger.warning("[Jarvis TTS] Mixer init failed, TTS worker exiting.")
            return

        import edge_tts
        import pygame

        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)

        while not self._stop_event.is_set():
            try:
                text = self._queue.get(timeout=1.0)
            except queue.Empty:
                continue

            if self._stop_event.is_set() or not text:
                break

            try:
                # Generate MP3 using edge-tts
                communicate = edge_tts.Communicate(text, self.voice)
                temp_file = os.path.join(tempfile.gettempdir(), f"jarvis_tts_{os.getpid()}_{id(text)}.mp3")

                loop.run_until_complete(communicate.save(temp_file))

                # Play via pygame
                if os.path.exists(temp_file):
                    pygame.mixer.music.load(temp_file)
                    pygame.mixer.music.play()
                    while pygame.mixer.music.get_busy() and not self._stop_event.is_set():
                        pygame.time.Clock().tick(10)

                    pygame.mixer.music.unload()
                    try:
                        os.remove(temp_file)
                    except OSError:
                        pass
            except Exception as e:
                logger.error(f"[Jarvis TTS] Speech synthesis error for '{text}': {e}")
            finally:
                self._queue.task_done()

        loop.close()


_tts_instance: Optional[JarvisTTS] = None


def get_tts() -> JarvisTTS:
    global _tts_instance
    if _tts_instance is None:
        _tts_instance = JarvisTTS()
        _tts_instance.start()
    return _tts_instance


def speak(text: str) -> None:
    get_tts().speak(text)
