import asyncio
import re
from jarvis_bridge.client import JarvisClient

async def main():
    c = JarvisClient(base_url="https://questions-acknowledged-dealing-lace.trycloudflare.com")
    sem = asyncio.Semaphore(15)

    async def get_info(x, y, z):
        async with sem:
            res = await c.execute_command(f"/data get block {x} {y} {z}")
            txt = " ".join(res.output)
            if "id:" in txt:
                id_m = re.search(r'id:\s*"([^"]+)"', txt)
                item_count = len(re.findall(r'id:\s*"[^"]+",\s*count:', txt))
                cname = re.search(r'CustomName:\s*\'([^\']+)\'', txt)
                front = re.search(r'front_text:\s*\{[^}]*messages:\s*\[([^\]]+)\]', txt)
                return (x, y, z, id_m.group(1) if id_m else "?", cname.group(1) if cname else None, front.group(1) if front else None, item_count)
            return None

    tasks = []
    for z in [-1661, -1662, -1663, -1664, -1669, -1671, -1672]:
        for y in [64, 65, 66]:
            for x in [747, 748]:
                tasks.append(get_info(x, y, z))

    results = await asyncio.gather(*tasks)
    results = [r for r in results if r is not None]
    results = sorted(results, key=lambda r: (r[2], r[1], r[0]))
    for r in results:
        print(f"({r[0]}, {r[1]}, {r[2]}): {r[3]} | CustomName={r[4]} | Sign={r[5]} | Items={r[6]}")

    await c.close()

if __name__ == "__main__":
    asyncio.run(main())
