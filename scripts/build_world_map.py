#!/usr/bin/env python3
"""Build the offline world map asset used by the "Review Map" screen.

Input:  Natural Earth 1:10m admin-0 countries (public domain), as GeoJSON:
        https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_0_countries.geojson
Output: app/src/main/assets/world_map.bin (a small custom binary format, read by WorldMap.java)

Usage (from the repo root; standard library only, no pip installs needed):

    curl -L -o /tmp/ne_10m_admin_0_countries.geojson \
        https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_0_countries.geojson
    python3 scripts/build_world_map.py /tmp/ne_10m_admin_0_countries.geojson
    # Optional: also write an SVG preview of the coarsest level of detail
    python3 scripts/build_world_map.py /tmp/ne_10m_admin_0_countries.geojson --preview /tmp/world_map.svg

What the script does:
  1. Projects every coordinate to Web Mercator, normalized to the unit square, and quantizes it to a
     2^QUANT_BITS grid.
  2. Splits a couple of map units that the puzzle treats as separate "countries"
     (French Guiana out of France, Kaliningrad out of Russia).
  3. Simplifies the polygons at several levels of detail (LODs) with Douglas-Peucker, in a
     topology-preserving way: rings are cut into arcs at junctions (TopoJSON-style), and each arc is
     simplified once and shared by both neighbouring countries, so borders never drift apart.
  4. Maps every country name used by the puzzles (the `countries` string-array in strings.xml) to one or
     more map units, and fails loudly if any name is missing.
  5. Writes everything with zigzag/varint delta encoding to keep the APK small.

Binary format (all multi-byte floats big-endian; "varint" = unsigned LEB128):
    magic        4 bytes  "SPWM"
    version      u8       1
    quant_bits   varint
    n_lods       varint   (coarsest first)
    tolerances   n_lods x f32   Douglas-Peucker tolerance of each LOD, in unit-square coordinates
    n_features   varint
    per feature:
        code     string   (varint byte length + UTF-8), e.g. "FRA", "GUF"
        label    string   game country name to draw on the map, or "" if not labelled
        label_x  f32      label anchor in unit-square Mercator coordinates
        label_y  f32
        per LOD:
            n_rings varint
            per ring: n_points varint, then n_points (dx, dy) zigzag varints, delta-coded from (0, 0)
    n_aliases    varint
    per alias:
        name     string   game country name (exactly as in strings.xml)
        n        varint
        n feature indices (varint); the first one is the "primary" unit that carries the label
"""

import argparse
import json
import math
import os
import re
import struct
import sys

QUANT_BITS = 22
Q = 1 << QUANT_BITS
MAX_LAT = 85.0

# Douglas-Peucker tolerances, in unit-square Mercator coordinates (1.0 == the whole world's width).
# For reference, at a zoom where the world is 1080px wide, 1px ~= 9.3e-4.
LOD_TOLERANCES = [4e-4, 1e-4, 3e-5]

# Map units we never draw.
SKIP_UNITS = {"ATA"}  # Antarctica

# Puzzle country name (strings.xml) -> Natural Earth ADM0_A3 codes (plus our synthetic GUF / KAL units).
# The first code is the "primary" unit which carries the label; the others are highlighted together with it.
# Several entries reflect how the puzzle's border graph was built:
#   * France borders "Kingdom of the Netherlands" via Saint-Martin / Sint Maarten (Caribbean).
#   * The United Kingdom borders Cyprus via the Akrotiri and Dhekelia sovereign base areas.
#   * French Guiana and Kaliningrad are separate nodes in the puzzle graph.
GAME_UNITS = {
    "Afghanistan": ["AFG"],
    "Albania": ["ALB"],
    "Algeria": ["DZA"],
    "Andorra": ["AND"],
    "Angola": ["AGO"],
    "Argentina": ["ARG"],
    "Armenia": ["ARM"],
    "Austria": ["AUT"],
    "Azerbaijan": ["AZE"],
    "Bangladesh": ["BGD"],
    "Belarus": ["BLR"],
    "Belgium": ["BEL"],
    "Belize": ["BLZ"],
    "Benin": ["BEN"],
    "Bhutan": ["BTN"],
    "Bolivia": ["BOL"],
    "Bosnia and Herzegovina": ["BIH"],
    "Botswana": ["BWA"],
    "Brazil": ["BRA"],
    "Brunei": ["BRN"],
    "Bulgaria": ["BGR"],
    "Burkina Faso": ["BFA"],
    "Burma": ["MMR"],
    "Burundi": ["BDI"],
    "Cambodia": ["KHM"],
    "Cameroon": ["CMR"],
    "Canada": ["CAN"],
    "Central African Republic": ["CAF"],
    "Chad": ["TCD"],
    "Chile": ["CHL"],
    "People's Republic of China": ["CHN"],
    "Colombia": ["COL"],
    "Democratic Republic of the Congo": ["COD"],
    "Republic of the Congo": ["COG"],
    "Costa Rica": ["CRI"],
    "Ivory Coast": ["CIV"],
    "Croatia": ["HRV"],
    "Cyprus": ["CYP"],
    "Czech Republic": ["CZE"],
    "Denmark": ["DNK"],
    "Djibouti": ["DJI"],
    "Dominican Republic": ["DOM"],
    "East Timor": ["TLS"],
    "Ecuador": ["ECU"],
    "Egypt": ["EGY"],
    "El Salvador": ["SLV"],
    "Equatorial Guinea": ["GNQ"],
    "Eritrea": ["ERI"],
    "Estonia": ["EST"],
    "Ethiopia": ["ETH"],
    "Finland": ["FIN"],
    "France": ["FRA", "MAF"],
    "French Guiana": ["GUF"],
    "Gabon": ["GAB"],
    "The Gambia": ["GMB"],
    "Georgia": ["GEO"],
    "Germany": ["DEU"],
    "Ghana": ["GHA"],
    "Greece": ["GRC"],
    "Guatemala": ["GTM"],
    "Guinea": ["GIN"],
    "Guinea-Bissau": ["GNB"],
    "Guyana": ["GUY"],
    "Haiti": ["HTI"],
    "Honduras": ["HND"],
    "Hungary": ["HUN"],
    "India": ["IND"],
    "Indonesia": ["IDN"],
    "Iran": ["IRN"],
    "Iraq": ["IRQ"],
    "Republic of Ireland": ["IRL"],
    "Israel": ["ISR"],
    "Italy": ["ITA"],
    "Jordan": ["JOR"],
    "Kazakhstan": ["KAZ"],
    "Kenya": ["KEN"],
    "North Korea": ["PRK"],
    "South Korea": ["KOR"],
    "Russian exclave of Kaliningrad": ["KAL"],
    "Kuwait": ["KWT"],
    "Kyrgyzstan": ["KGZ"],
    "Laos": ["LAO"],
    "Latvia": ["LVA"],
    "Lebanon": ["LBN"],
    "Lesotho": ["LSO"],
    "Liberia": ["LBR"],
    "Libya": ["LBY"],
    "Liechtenstein": ["LIE"],
    "Lithuania": ["LTU"],
    "Luxembourg": ["LUX"],
    "Republic of Macedonia": ["MKD"],
    "Malawi": ["MWI"],
    "Malaysia": ["MYS"],
    "Mali": ["MLI"],
    "Mauritania": ["MRT"],
    "Mexico": ["MEX"],
    "Moldova": ["MDA"],
    "Monaco": ["MCO"],
    "Mongolia": ["MNG"],
    "Montenegro": ["MNE"],
    "Morocco": ["MAR"],
    "Mozambique": ["MOZ"],
    "Namibia": ["NAM"],
    "Nepal": ["NPL"],
    "Netherlands": ["NLD"],
    "Kingdom of the Netherlands": ["SXM", "NLD"],
    "Nicaragua": ["NIC"],
    "Niger": ["NER"],
    "Nigeria": ["NGA"],
    "Norway": ["NOR"],
    "Oman": ["OMN"],
    "Pakistan": ["PAK"],
    "Panama": ["PAN"],
    "Papua New Guinea": ["PNG"],
    "Paraguay": ["PRY"],
    "Peru": ["PER"],
    "Poland": ["POL"],
    "Portugal": ["PRT"],
    "Qatar": ["QAT"],
    "Romania": ["ROU"],
    "Russia": ["RUS"],
    "Rwanda": ["RWA"],
    "San Marino": ["SMR"],
    "Saudi Arabia": ["SAU"],
    "Senegal": ["SEN"],
    "Serbia": ["SRB"],
    "Sierra Leone": ["SLE"],
    "Slovakia": ["SVK"],
    "Slovenia": ["SVN"],
    "Somalia": ["SOM"],
    "South Africa": ["ZAF"],
    "South Sudan": ["SDS"],
    "Spain": ["ESP"],
    "Sri Lanka": ["LKA"],
    "Sudan": ["SDN"],
    "Suriname": ["SUR"],
    "Swaziland": ["SWZ"],
    "Sweden": ["SWE"],
    "Switzerland": ["CHE"],
    "Syria": ["SYR"],
    "Tajikistan": ["TJK"],
    "Tanzania": ["TZA"],
    "Thailand": ["THA"],
    "Togo": ["TGO"],
    "Tunisia": ["TUN"],
    "Turkey": ["TUR"],
    "Turkmenistan": ["TKM"],
    "Uganda": ["UGA"],
    "Ukraine": ["UKR"],
    "United Arab Emirates": ["ARE"],
    "United Kingdom": ["GBR", "WSB", "ESB"],
    "United States": ["USA"],
    "Uruguay": ["URY"],
    "Uzbekistan": ["UZB"],
    "Vatican City": ["VAT"],
    "Venezuela": ["VEN"],
    "Vietnam": ["VNM"],
    "Yemen": ["YEM"],
    "Zambia": ["ZMB"],
    "Zimbabwe": ["ZWE"],
    "Gibraltar": ["GIB"],
}

# Polygons split out of a Natural Earth unit into a synthetic unit: (source, new code, lon range, lat range).
SPLITS = [
    ("FRA", "GUF", (-55.0, -51.0), (1.0, 7.0)),  # French Guiana
    ("RUS", "KAL", (19.0, 23.5), (54.0, 56.0)),  # Kaliningrad
]


# ---------------------------------------------------------------------------------------------------------------------
# Geometry helpers


def project(lon, lat):
    """Web Mercator into the unit square, quantized to integers."""
    lat = max(-MAX_LAT, min(MAX_LAT, lat))
    x = (lon + 180.0) / 360.0
    y = (1.0 - math.log(math.tan(math.pi / 4.0 + math.radians(lat) / 2.0)) / math.pi) / 2.0
    return (int(round(x * Q)), int(round(y * Q)))


def clean_ring(coords):
    """Project a GeoJSON ring; drop the closing point and consecutive duplicates. Returns None if degenerate."""
    pts = []
    for lon, lat in (c[:2] for c in coords):
        p = project(lon, lat)
        if not pts or pts[-1] != p:
            pts.append(p)
    while len(pts) > 1 and pts[0] == pts[-1]:
        pts.pop()
    return pts if len(set(pts)) >= 3 else None


def ring_area(pts):
    a = 0
    n = len(pts)
    for i in range(n):
        x0, y0 = pts[i]
        x1, y1 = pts[(i + 1) % n]
        a += x0 * y1 - x1 * y0
    return a / 2.0


def ring_centroid(pts):
    a = cx = cy = 0.0
    n = len(pts)
    for i in range(n):
        x0, y0 = pts[i]
        x1, y1 = pts[(i + 1) % n]
        cross = x0 * y1 - x1 * y0
        a += cross
        cx += (x0 + x1) * cross
        cy += (y0 + y1) * cross
    if a == 0:
        return (sum(p[0] for p in pts) / n, sum(p[1] for p in pts) / n)
    return (cx / (3.0 * a), cy / (3.0 * a))


def douglas_peucker(pts, tol):
    """Simplify an open polyline; the first and last points are always kept."""
    n = len(pts)
    if n <= 2:
        return list(pts)
    keep = [False] * n
    keep[0] = keep[-1] = True
    tol2 = tol * tol
    stack = [(0, n - 1)]
    while stack:
        i0, i1 = stack.pop()
        if i1 - i0 < 2:
            continue
        ax, ay = pts[i0]
        bx, by = pts[i1]
        dx, dy = bx - ax, by - ay
        seg2 = dx * dx + dy * dy
        best, best_d2 = -1, -1.0
        for i in range(i0 + 1, i1):
            px, py = pts[i]
            if seg2 == 0:
                d2 = (px - ax) ** 2 + (py - ay) ** 2
            else:
                t = ((px - ax) * dx + (py - ay) * dy) / seg2
                t = 0.0 if t < 0 else 1.0 if t > 1 else t
                d2 = (px - ax - t * dx) ** 2 + (py - ay - t * dy) ** 2
            if d2 > best_d2:
                best, best_d2 = i, d2
        if best_d2 > tol2:
            keep[best] = True
            stack.append((i0, best))
            stack.append((best, i1))
    return [p for p, k in zip(pts, keep) if k]


def simplify_closed(pts, tol):
    """Simplify a ring with no fixed points: anchor at pts[0] and at the vertex farthest from it."""
    x0, y0 = pts[0]
    far = max(range(len(pts)), key=lambda i: (pts[i][0] - x0) ** 2 + (pts[i][1] - y0) ** 2)
    first = douglas_peucker(pts[: far + 1], tol)
    second = douglas_peucker(pts[far:] + [pts[0]], tol)
    return first[:-1] + second[:-1]


# ---------------------------------------------------------------------------------------------------------------------
# Topology-preserving simplification


class Topology:
    """Cuts rings into arcs at junctions so that shared borders are simplified identically on both sides."""

    JUNCTION = object()

    def __init__(self, rings):
        neighbours = {}
        for pts in rings:
            n = len(pts)
            for i, p in enumerate(pts):
                a, b = pts[i - 1], pts[(i + 1) % n]
                pair = (a, b) if a < b else (b, a)
                seen = neighbours.get(p)
                if seen is None:
                    neighbours[p] = pair
                elif seen is not Topology.JUNCTION and seen != pair:
                    neighbours[p] = Topology.JUNCTION
        self.junctions = {p for p, v in neighbours.items() if v is Topology.JUNCTION}
        self.cache = {}

    def _arc(self, chain, tol, closed):
        c = tuple(chain)
        r = c[::-1]
        key = min(c, r)
        cache_key = (key, tol, closed)
        if cache_key not in self.cache:
            if closed:
                simplified = simplify_closed(list(key), tol)
                if len(set(simplified)) < 3:
                    simplified = list(key)
            else:
                simplified = douglas_peucker(list(key), tol)
            self.cache[cache_key] = simplified
        simplified = self.cache[cache_key]
        return simplified if c == key else simplified[::-1]

    def simplify_ring(self, pts, tol):
        n = len(pts)
        idx = [i for i, p in enumerate(pts) if p in self.junctions]
        if not idx:
            # Canonical rotation + direction so the same ring (e.g. an enclave and its host's hole) matches.
            start = min(range(n), key=lambda i: pts[i])
            rot = pts[start:] + pts[:start]
            if rot[-1] < rot[1]:
                rot = [rot[0]] + rot[1:][::-1]
            return self._arc(rot, tol, closed=True)
        rot = pts[idx[0]:] + pts[: idx[0]]
        cut = [i - idx[0] for i in idx] + [n]
        out = []
        for a, b in zip(cut, cut[1:]):
            chain = rot[a: b + 1] if b < n else rot[a:] + [rot[0]]
            out.extend(self._arc(chain, tol, closed=False)[:-1])
        return out

    def is_island(self, pts):
        return not any(p in self.junctions for p in pts)


# ---------------------------------------------------------------------------------------------------------------------
# Encoding


def write_varint(out, v):
    assert v >= 0
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return


def write_zigzag(out, v):
    write_varint(out, (v << 1) if v >= 0 else ((-v << 1) - 1))


def write_string(out, s):
    data = s.encode("utf-8")
    write_varint(out, len(data))
    out.extend(data)


def write_ring(out, pts):
    write_varint(out, len(pts))
    px = py = 0
    for x, y in pts:
        write_zigzag(out, x - px)
        write_zigzag(out, y - py)
        px, py = x, y


# ---------------------------------------------------------------------------------------------------------------------


def read_game_countries(strings_xml):
    xml = open(strings_xml, encoding="utf-8").read()
    body = re.search(r'<string-array name="countries">(.*?)</string-array>', xml, re.S).group(1)
    return [item.replace("\\'", "'") for item in re.findall(r"<item>(.*?)</item>", body)]


def load_units(geojson_path):
    data = json.load(open(geojson_path, encoding="utf-8"))
    units = {}  # code -> {"polygons": [[ring, ...], ...], "label": (lon, lat)}
    for f in data["features"]:
        props = f["properties"]
        code = props["ADM0_A3"]
        if code in SKIP_UNITS:
            continue
        geom = f["geometry"]
        polys = geom["coordinates"] if geom["type"] == "MultiPolygon" else [geom["coordinates"]]
        label = (props.get("LABEL_X"), props.get("LABEL_Y"))
        units[code] = {"polygons": polys, "label": label if None not in label else None}

    for src, new, (lon0, lon1), (lat0, lat1) in SPLITS:
        moved, kept = [], []
        for poly in units[src]["polygons"]:
            lons = [c[0] for c in poly[0]]
            lats = [c[1] for c in poly[0]]
            cx, cy = sum(lons) / len(lons), sum(lats) / len(lats)
            (moved if lon0 <= cx <= lon1 and lat0 <= cy <= lat1 else kept).append(poly)
        if not moved:
            sys.exit(f"Split {new}: no polygons of {src} in the given box")
        units[src]["polygons"] = kept
        units[new] = {"polygons": moved, "label": None}
    return units


def main():
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("geojson", help="Path to ne_10m_admin_0_countries.geojson")
    parser.add_argument("--strings", default=os.path.join(repo, "app/src/main/res/values/strings.xml"))
    parser.add_argument("--out", default=os.path.join(repo, "app/src/main/assets/world_map.bin"))
    parser.add_argument("--preview", help="Optional path for an SVG preview (coarsest LOD, game countries labelled)")
    args = parser.parse_args()

    game_countries = read_game_countries(args.strings)
    missing = sorted(set(game_countries) - set(GAME_UNITS))
    extra = sorted(set(GAME_UNITS) - set(game_countries))
    if missing or extra:
        sys.exit(f"GAME_UNITS out of sync with strings.xml. Missing: {missing}. Extra: {extra}")

    units = load_units(args.geojson)
    unknown = sorted({c for codes in GAME_UNITS.values() for c in codes} - set(units))
    if unknown:
        sys.exit(f"Unknown map unit codes: {unknown}")

    # Primary unit -> label text
    labels = {codes[0]: name for name, codes in GAME_UNITS.items()}
    # "Netherlands" and "Kingdom of the Netherlands" both include NLD; NLD is primary only for "Netherlands".

    # Project & clean
    codes = sorted(units)
    rings_by_unit = {}
    all_rings = []
    for code in codes:
        rings = []
        for poly in units[code]["polygons"]:
            for ring in poly:
                pts = clean_ring(ring)
                if pts:
                    rings.append(pts)
        rings_by_unit[code] = rings
        all_rings.extend(rings)
    topo = Topology(all_rings)

    # Simplify every LOD (coarsest first)
    lods_by_unit = {code: [] for code in codes}
    for tol_units in LOD_TOLERANCES:
        tol = tol_units * Q
        min_island_area = (2 * tol) ** 2
        for code in codes:
            out = []
            for pts in rings_by_unit[code]:
                if topo.is_island(pts) and abs(ring_area(pts)) < min_island_area:
                    continue  # Small islands (and tiny enclaves, consistently on both sides) disappear at this LOD
                simplified = topo.simplify_ring(pts, tol)
                if len(set(simplified)) >= 3:
                    out.append(simplified)
            lods_by_unit[code].append(out)
    # A unit that vanished at some LOD falls back to the next finer LOD (or to the unsimplified rings).
    for code in codes:
        lods = lods_by_unit[code]
        for i in range(len(lods) - 1, -1, -1):
            if not lods[i]:
                lods[i] = lods[i + 1] if i + 1 < len(lods) else rings_by_unit[code]

    # Label anchors
    label_xy = {}
    for code in codes:
        lab = units[code]["label"]
        if lab:
            x, y = project(*lab)
        else:
            biggest = max(rings_by_unit[code], key=lambda r: abs(ring_area(r)))
            x, y = ring_centroid(biggest)
        label_xy[code] = (x / Q, y / Q)

    # Encode
    out = bytearray(b"SPWM")
    out.append(1)
    write_varint(out, QUANT_BITS)
    write_varint(out, len(LOD_TOLERANCES))
    for tol in LOD_TOLERANCES:
        out.extend(struct.pack(">f", tol))
    write_varint(out, len(codes))
    for code in codes:
        write_string(out, code)
        write_string(out, labels.get(code, ""))
        out.extend(struct.pack(">ff", *label_xy[code]))
        for rings in lods_by_unit[code]:
            write_varint(out, len(rings))
            for pts in rings:
                write_ring(out, pts)
    index = {code: i for i, code in enumerate(codes)}
    write_varint(out, len(game_countries))
    for name in game_countries:
        write_string(out, name)
        write_varint(out, len(GAME_UNITS[name]))
        for code in GAME_UNITS[name]:
            write_varint(out, index[code])

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "wb") as fh:
        fh.write(out)

    n_orig = sum(len(r) for r in all_rings)
    print(f"{len(codes)} map units, {len(game_countries)} game countries, {n_orig} input points")
    for i, tol in enumerate(LOD_TOLERANCES):
        n = sum(len(r) for code in codes for r in lods_by_unit[code][i])
        print(f"  LOD {i}: tolerance {tol:g} -> {n} points")
    print(f"Wrote {len(out)} bytes to {args.out}")

    if args.preview:
        write_preview(args.preview, codes, lods_by_unit, labels, label_xy)


def write_preview(path, codes, lods_by_unit, labels, label_xy, lod=0, width=2000):
    """A quick SVG to eyeball the data (not used by the app)."""
    y0, y1 = 0.12, 0.83
    scale = width
    height = int((y1 - y0) * scale)
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
             f'viewBox="0 {y0 * scale:.1f} {width} {height}"><rect x="0" y="{y0 * scale}" width="{width}" '
             f'height="{height}" fill="#dfe9f0"/>']
    for code in codes:
        d = []
        for pts in lods_by_unit[code][lod]:
            d.append("M" + "L".join(f"{x / Q * scale:.1f},{y / Q * scale:.1f}" for x, y in pts) + "Z")
        fill = "#f6f3ec" if code in labels else "#e2e0da"
        parts.append(f'<path d="{"".join(d)}" fill="{fill}" fill-rule="evenodd" stroke="#666" stroke-width="0.6"/>')
    for code in codes:
        if code in labels:
            x, y = label_xy[code]
            parts.append(f'<text x="{x * scale:.1f}" y="{y * scale:.1f}" font-size="7" text-anchor="middle" '
                         f'font-family="sans-serif">{labels[code].replace("&", "&amp;")}</text>')
    parts.append("</svg>")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(parts))
    print(f"Wrote preview to {path}")


if __name__ == "__main__":
    main()
