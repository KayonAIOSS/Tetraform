"""Generates the 4D compass textures, models and item definition (run from the repository root).

Usage: python3 tools/gen_compass.py <path to vanilla assets/minecraft/textures/item>
"""
import json, math, os, sys
from PIL import Image

src = sys.argv[1]
RES = "src/main/resources/assets"
tex = os.path.join(RES, "tetraform/textures/item")
mod = os.path.join(RES, "tetraform/models/item")
os.makedirs(tex, exist_ok=True); os.makedirs(mod, exist_ok=True)
os.makedirs(os.path.join(RES, "minecraft/items"), exist_ok=True)

frames = [Image.open(os.path.join(src, "compass_%02d.png" % i)).convert("RGBA") for i in range(32)]
body = frames[16].copy()
face = frames[16].getpixel((5, 6))
for y in range(16):
    for x in range(16):
        if len({f.getpixel((x, y)) for f in frames}) > 1:
            body.putpixel((x, y), face)
S = 32
body = body.resize((S, S), Image.NEAREST)
body.save(os.path.join(tex, "compass_body.png"))

CX, CY, RX, RY = 15.5, 14.5, 9.0, 4.4

def needle(k, tip, tail, width, c_tip, c_tail, pivot):
    a = k / 32 * 2 * math.pi  # clockwise from "up" (straight ahead)
    dx, dy = math.sin(a) * RX, -math.cos(a) * RY
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    n = 400
    for i in range(n + 1):
        t = -tail + (tip + tail) * i / n
        px, py = CX + dx * t, CY + dy * t
        col = c_tip if t >= 0 else c_tail
        r = width / 2
        for yy in range(int(py - r - 1), int(py + r + 2)):
            for xx in range(int(px - r - 1), int(px + r + 2)):
                if 0 <= xx < S and 0 <= yy < S and (xx + 0.5 - px) ** 2 + (yy + 0.5 - py) ** 2 <= r * r + 0.05:
                    if col == c_tip or im.getpixel((xx, yy))[3] == 0:
                        im.putpixel((xx, yy), col)
    if pivot:
        for xx, yy in ((15, 14), (16, 14), (15, 15), (16, 15)):
            im.putpixel((xx, yy), (60, 60, 64, 255))
    return im

for k in range(32):
    needle(k, 1.0, 0.45, 2.2, (70, 110, 255, 255), (25, 35, 110, 255), False).save(os.path.join(tex, "compass_blue_%02d.png" % k))
    needle(k, 0.78, 0.32, 1.4, (255, 50, 50, 255), (110, 20, 20, 255), True).save(os.path.join(tex, "compass_red_%02d.png" % k))

def model(name):
    with open(os.path.join(mod, name + ".json"), "w") as f:
        json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": "tetraform:item/" + name}}, f, indent=2)

model("compass_body")
for k in range(32):
    model("compass_blue_%02d" % k); model("compass_red_%02d" % k)

def dispatch(prop, color, extra=None):
    entries = [{"threshold": 0.0, "model": {"type": "minecraft:model", "model": "tetraform:item/compass_%s_00" % color}}]
    for k in range(1, 32):
        entries.append({"threshold": k - 0.5, "model": {"type": "minecraft:model", "model": "tetraform:item/compass_%s_%02d" % (color, k)}})
    entries.append({"threshold": 31.5, "model": {"type": "minecraft:model", "model": "tetraform:item/compass_%s_00" % color}})
    d = {"type": "minecraft:range_dispatch", "property": prop, "scale": 32.0, "entries": entries}
    if extra: d.update(extra)
    return d

def compass(target):
    # body first, then the larger blue needle (where the compass points), then the red needle (zw) on top
    return {"type": "minecraft:composite", "models": [
        {"type": "minecraft:model", "model": "tetraform:item/compass_body"},
        dispatch("tetraform:needle", "blue", {"target": target}),
        dispatch("tetraform:zw", "red"),
    ]}

item = {"model": {"type": "minecraft:condition", "property": "minecraft:has_component", "component": "minecraft:lodestone_tracker",
                  "on_true": compass("lodestone"), "on_false": compass("spawn")}}
with open(os.path.join(RES, "minecraft/items/compass.json"), "w") as f:
    json.dump(item, f, indent=1)
