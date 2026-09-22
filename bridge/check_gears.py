import asyncio
import re
from jarvis_bridge.client import JarvisClient

async def check_pos(c, sem, x, y, z):
    async with sem:
        res = await c.execute_command(f"/data get block {x} {y} {z}")
        txt = " ".join(res.output)
        if "Speed:" in txt:
            speed = re.search(r"Speed:\s*([0-9\.\-]+f)", txt)
            id_m = re.search(r'id:\s*"([^"]+)"', txt)
            sp_val = speed.group(1) if speed else "?"
            id_val = id_m.group(1) if id_m else "?"
            return (x, y, z, id_val, sp_val)
        return None

async def main():
    c = JarvisClient(base_url="https://linda-yacht-admitted-rec.trycloudflare.com")
    sem = asyncio.Semaphore(20)
    tasks = []
    for x in range(749, 755):
        for y in range(51, 56):
            for z in range(-1662, -1656):
                tasks.append(check_pos(c, sem, x, y, z))
    results = await asyncio.gather(*tasks)
    found = [r for r in results if r is not None]
    found = sorted(found, key=lambda f: (f[0], f[1], f[2]))
    for f in found:
        print(f"({f[0]}, {f[1]}, {f[2]}): {f[3]} -> Speed={f[4]}")
    await c.close()

if __name__ == "__main__":
    asyncio.run(main())
