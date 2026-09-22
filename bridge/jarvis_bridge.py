"""
Jarvis Minecraft Bridge
Communicates with the in-game Jarvis NeoForge mod over HTTP REST API.
"""

import sys
import time
import requests
import json

BASE_URL = "http://localhost:25585"

class JarvisClient:
    def __init__(self, base_url=BASE_URL):
        self.base_url = base_url

    def get_status(self):
        try:
            r = requests.get(f"{self.base_url}/api/status", timeout=2)
            if r.status_code == 200:
                return r.json()
        except requests.exceptions.RequestException as e:
            return {"error": str(e), "status": "offline"}
        return {"status": "unknown"}

    def say(self, message, sender="Jarvis"):
        try:
            r = requests.post(f"{self.base_url}/api/say", json={"message": message, "sender": sender}, timeout=2)
            return r.status_code == 200
        except requests.exceptions.RequestException as e:
            print(f"[Bridge Error] Failed to send message: {e}")
            return False

    def execute_command(self, command):
        try:
            r = requests.post(f"{self.base_url}/api/command", json={"command": command}, timeout=2)
            return r.status_code == 200
        except requests.exceptions.RequestException as e:
            print(f"[Bridge Error] Failed to execute command: {e}")
            return False

    def get_surroundings(self, player=None, radius=8):
        try:
            params = {"radius": radius}
            if player:
                params["player"] = player
            r = requests.get(f"{self.base_url}/api/surroundings", params=params, timeout=3)
            if r.status_code == 200:
                return r.json()
        except requests.exceptions.RequestException as e:
            return {"error": str(e)}
        return {}

    def get_recent_chat(self, since=0):
        try:
            r = requests.get(f"{self.base_url}/api/chat", params={"since": since}, timeout=2)
            if r.status_code == 200:
                return r.json()
        except requests.exceptions.RequestException:
            pass
        return []

    def listen_and_respond(self, ai_callback=None):
        print(f"[*] Jarvis Bridge started. Listening to Minecraft on {self.base_url}...")
        last_timestamp = int(time.time() * 1000)
        
        while True:
            try:
                entries = self.get_recent_chat(since=last_timestamp)
                for entry in entries:
                    last_timestamp = max(last_timestamp, entry.get("timestamp", 0))
                    player = entry.get("player")
                    msg = entry.get("message", "")

                    # Check for direct trigger
                    if msg.startswith("!jarvis ") or msg.startswith("@jarvis "):
                        prompt = msg.split(" ", 1)[1]
                        print(f"[Chat] {player}: {prompt}")
                        if ai_callback:
                            reply = ai_callback(player, prompt, self)
                            if reply:
                                self.say(reply)
                        else:
                            self.say(f"Emredersiniz {player}, '{prompt}' komutunu aldim!")

                time.sleep(1)
            except KeyboardInterrupt:
                print("\n[*] Stopping Jarvis Bridge.")
                break
            except Exception as e:
                print(f"[Error] {e}")
                time.sleep(2)

if __name__ == "__main__":
    client = JarvisClient()
    if len(sys.argv) > 2 and sys.argv[1] == "say":
        msg = " ".join(sys.argv[2:])
        client.say(msg)
        print(f"Sent: {msg}")
    elif len(sys.argv) > 2 and sys.argv[1] == "cmd":
        cmd = " ".join(sys.argv[2:])
        client.execute_command(cmd)
        print(f"Executed: {cmd}")
    elif len(sys.argv) > 1 and sys.argv[1] == "status":
        print(json.dumps(client.get_status(), indent=2))
    elif len(sys.argv) > 1 and sys.argv[1] == "surroundings":
        print(json.dumps(client.get_surroundings(), indent=2))
    else:
        client.listen_and_respond()
