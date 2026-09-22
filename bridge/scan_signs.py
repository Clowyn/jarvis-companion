import asyncio
import re
import sys
from jarvis_bridge.client import JarvisClient

async def scan():
    c = JarvisClient(base_url="https://linda-yacht-admitted-rec.trycloudflare.com")
    st = await c.get_status()
    px, py, pz = 0, 0, 0
    for p in st.players:
        if p.name == "AlcyoneDX":
            px, py, pz = int(p.x), int(p.y), int(p.z)
            print(f"Player AlcyoneDX at ({px}, {py}, {pz})")

    # Get surroundings first to see all signs and containers quickly
    surr = await c.get_surroundings(radius=24)
    sign_blocks = [b for b in surr.blocks if "sign" in b.block.lower()]
    container_blocks = [b for b in surr.blocks if b.category == "container" or "chest" in b.block.lower() or "barrel" in b.block.lower()]

    print(f"Surroundings reported {len(sign_blocks)} signs and {len(container_blocks)} containers.")

    sem = asyncio.Semaphore(15)

    async def inspect_sign(b):
        async with sem:
            res = await c.execute_command(f"/data get block {int(b.x)} {int(b.y)} {int(b.z)}")
            out = " ".join(res.output)
            # Find messages in sign data
            msgs = re.findall(r'"text":\s*"([^"]+)"', out)
            if not msgs:
                # 1.21 text components might be literal string
                msgs = re.findall(r'messages:\s*\[([^\]]+)\]', out)
            return (int(b.x), int(b.y), int(b.z), b.block, msgs, out)

    async def inspect_chest(b):
        async with sem:
            res = await c.execute_command(f"/data get block {int(b.x)} {int(b.y)} {int(b.z)}")
            out = " ".join(res.output)
            cname = re.search(r'CustomName:\s*\'([^\']+)\'', out)
            return (int(b.x), int(b.y), int(b.z), b.block, cname.group(1) if cname else None)

    signs_data = await asyncio.gather(*[inspect_sign(b) for b in sign_blocks])
    chests_data = await asyncio.gather(*[inspect_chest(b) for b in container_blocks])

    print("\n=== SIGNS FOUND ===")
    for s in signs_data:
        print(f"Sign at ({s[0]}, {s[1]}, {s[2]}): {s[4]} (raw: {s[5][:200]})")

    print("\n=== CONTAINERS FOUND ===")
    for ch in chests_data:
        print(f"Container at ({ch[0]}, {ch[1]}, {ch[2]}): {ch[3]} | CustomName={ch[4]}")

    await c.close()

if __name__ == "__main__":
    asyncio.run(scan())
