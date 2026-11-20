#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Draws the line diagrams used in the documentation.

The diagrams use the same shapes as the picture in the app (see
app/src/main/kotlin/com/everysoft/paprelay/ui/FlowDiagram.kt): a machine, a
phone and a heart, joined by lines whose form says what is happening. They are
meant to be understood with very little reading.

Run from the repository root:

    python3 tools/gen_doc_diagrams.py

It rewrites docs/images/*.svg. Do not edit those files by hand.
"""

from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "docs" / "images"
WIDTH = 720

STYLE = """
  .paper { fill: #ffffff; stroke: #d0d7de; stroke-width: 1; }
  .ink { fill: none; stroke: #1f2328; stroke-width: 2.4; stroke-linecap: round; stroke-linejoin: round; }
  .thin { fill: none; stroke: #1f2328; stroke-width: 1.6; stroke-linecap: round; stroke-linejoin: round; }
  .line { fill: none; stroke: #6e7781; stroke-width: 2.4; stroke-linecap: round; stroke-linejoin: round; }
  .faint { fill: none; stroke: #afb8c1; stroke-width: 1.6; stroke-linecap: round; stroke-linejoin: round; }
  .dash { stroke-dasharray: 7 7; }
  .solid { fill: #1f2328; stroke: none; }
  .hole { fill: #ffffff; stroke: none; }
  .bad { fill: #cf222e; stroke: none; }
  .badline { fill: none; stroke: #cf222e; stroke-width: 2.4; stroke-linecap: round; }
  .onbad { fill: none; stroke: #ffffff; stroke-width: 2.4; stroke-linecap: round; }
  text { font-family: -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; fill: #1f2328; }
  .t { font-size: 16px; }
  .b { font-size: 16px; font-weight: 600; }
  .s { font-size: 13px; fill: #57606a; }
  .m { font-size: 11px; }
  .ms { font-size: 10px; fill: #57606a; }
  .num { font-size: 14px; font-weight: 700; fill: #ffffff; }
  .on { fill: #ffffff; }
  @media (prefers-color-scheme: dark) {
    .paper { fill: #0d1117; stroke: #30363d; }
    .ink, .thin { stroke: #e6edf3; }
    .line { stroke: #8b949e; }
    .faint { stroke: #484f58; }
    .solid { fill: #e6edf3; }
    .hole { fill: #0d1117; }
    .bad { fill: #f85149; }
    .badline { stroke: #f85149; }
    .onbad { stroke: #0d1117; }
    text { fill: #e6edf3; }
    .s, .ms { fill: #8b949e; }
    .num, .on { fill: #0d1117; }
  }
"""


def f(value):
    """A number as SVG wants it: short, and never in exponent form."""
    return f"{value:.1f}".rstrip("0").rstrip(".")


class Svg:
    def __init__(self, height, title):
        self.height = height
        self.title = title
        self.parts = []

    def add(self, text):
        self.parts.append(text)

    def save(self, name):
        body = "\n".join(self.parts)
        text = (
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {WIDTH} {self.height}" '
            f'width="{WIDTH}" height="{self.height}" role="img" aria-label="{self.title}">\n'
            f"<title>{self.title}</title>\n<style>{STYLE}</style>\n"
            f'<rect class="paper" x="0.5" y="0.5" width="{WIDTH - 1}" height="{self.height - 1}" rx="12"/>\n'
            f"{body}\n</svg>\n"
        )
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / name).write_text(text, encoding="utf-8")

    # ---- words

    def text(self, x, y, words, cls="t", anchor="start"):
        self.add(f'<text class="{cls}" x="{f(x)}" y="{f(y)}" text-anchor="{anchor}">{words}</text>')

    def lines(self, x, y, rows, cls="t", gap=21):
        for index, row in enumerate(rows):
            self.text(x, y + index * gap, row, cls)

    # ---- the three things in the app's picture

    def machine(self, x, y, u, cls="ink"):
        w = 1.5 * u
        self.add(f'<rect class="{cls}" x="{f(x + 1)}" y="{f(y + 0.20 * u)}" width="{f(w - 2)}" height="{f(0.64 * u)}" rx="{f(0.16 * u)}"/>')
        self.add(f'<rect class="{cls}" x="{f(x + 0.10 * w)}" y="{f(y + 0.46 * u)}" width="{f(0.30 * w)}" height="{f(0.26 * u)}" rx="{f(0.05 * u)}"/>')
        self.add(f'<circle class="{cls}" cx="{f(x + 0.25 * w)}" cy="{f(y + 0.33 * u)}" r="{f(0.065 * u)}"/>')
        seam = x + 0.70 * w
        self.add(f'<path class="{cls}" d="M{f(seam)} {f(y + 0.27 * u)}V{f(y + 0.77 * u)}M{f(seam)} {f(y + 0.60 * u)}H{f(x + w - 1)}M{f(x + 0.14 * w)} {f(y + 0.93 * u)}H{f(x + 0.86 * w)}"/>')
        return w

    def phone(self, x, y, u, cls="ink"):
        w = 0.5 * u
        self.add(f'<rect class="{cls}" x="{f(x + 1)}" y="{f(y + 0.06 * u)}" width="{f(w - 2)}" height="{f(0.88 * u)}" rx="{f(0.09 * u)}"/>')
        self.add(f'<path class="{cls}" d="M{f(x + 0.34 * w)} {f(y + 0.84 * u)}H{f(x + 0.66 * w)}"/>')
        return w

    def heart(self, x, y, u, cls="ink"):
        w = 0.84 * u
        h = u

        def p(a, b):
            return f"{f(x + w * a)} {f(y + h * b)}"

        self.add(
            f'<path class="{cls}" d="M{p(0.50, 0.86)}C{p(-0.13, 0.58)} {p(-0.08, 0.18)} {p(0.30, 0.18)}'
            f"C{p(0.42, 0.18)} {p(0.50, 0.26)} {p(0.50, 0.34)}C{p(0.50, 0.26)} {p(0.58, 0.18)} {p(0.70, 0.18)}"
            f'C{p(1.08, 0.18)} {p(1.13, 0.58)} {p(0.50, 0.86)}Z"/>'
        )
        return w

    # ---- other small drawings, in the same hand

    def page(self, x, y, w, h, rows=3):
        """A sheet of paper with a folded corner and a few lines of writing."""
        fold = w * 0.32
        self.add(f'<path class="ink" d="M{f(x)} {f(y)}H{f(x + w - fold)}L{f(x + w)} {f(y + fold)}V{f(y + h)}H{f(x)}Z"/>')
        self.add(f'<path class="thin" d="M{f(x + w - fold)} {f(y)}V{f(y + fold)}H{f(x + w)}"/>')
        for row in range(rows):
            at = y + h * (0.48 + 0.16 * row)
            self.add(f'<path class="thin" d="M{f(x + w * 0.2)} {f(at)}H{f(x + w * 0.8)}"/>')

    def share(self, x, y, size):
        """Three points joined by two lines: the usual sign for sharing."""
        r = size * 0.13
        left = (x + size * 0.2, y + size * 0.5)
        top = (x + size * 0.8, y + size * 0.18)
        bottom = (x + size * 0.8, y + size * 0.82)
        self.add(f'<path class="ink" d="M{f(left[0])} {f(left[1])}L{f(top[0])} {f(top[1])}M{f(left[0])} {f(left[1])}L{f(bottom[0])} {f(bottom[1])}"/>')
        for cx, cy in (left, top, bottom):
            self.add(f'<circle class="hole" cx="{f(cx)}" cy="{f(cy)}" r="{f(r + 2)}"/>')
            self.add(f'<circle class="ink" cx="{f(cx)}" cy="{f(cy)}" r="{f(r)}"/>')

    def cloud_off(self, x, y, w):
        """A cloud with a line through it: no internet."""
        h = w * 0.62
        self.add(
            f'<path class="line" d="M{f(x + w * 0.24)} {f(y + h)}'
            f"C{f(x - w * 0.02)} {f(y + h)} {f(x - w * 0.02)} {f(y + h * 0.48)} {f(x + w * 0.24)} {f(y + h * 0.50)}"
            f"C{f(x + w * 0.24)} {f(y + h * 0.02)} {f(x + w * 0.70)} {f(y + h * 0.02)} {f(x + w * 0.72)} {f(y + h * 0.40)}"
            f'C{f(x + w * 1.04)} {f(y + h * 0.38)} {f(x + w * 1.04)} {f(y + h)} {f(x + w * 0.76)} {f(y + h)}Z"/>'
        )
        self.add(f'<path class="badline" d="M{f(x + w * 0.08)} {f(y - 2)}L{f(x + w * 0.92)} {f(y + h + 6)}"/>')

    def clock(self, cx, cy, r):
        self.add(f'<circle class="ink" cx="{f(cx)}" cy="{f(cy)}" r="{f(r)}"/>')
        self.add(f'<path class="ink" d="M{f(cx)} {f(cy - r * 0.55)}V{f(cy)}L{f(cx + r * 0.42)} {f(cy + r * 0.28)}"/>')

    def waves(self, x, cy, size, towards=1):
        """Radio waves leaving a point at (x, cy), opening in the direction [towards]."""
        for index in range(3):
            r = size * (0.34 + 0.33 * index)
            dx = towards * r * 0.5
            self.add(f'<path class="line" d="M{f(x + dx)} {f(cy - r * 0.86)}A{f(r)} {f(r)} 0 0 {1 if towards > 0 else 0} {f(x + dx)} {f(cy + r * 0.86)}"/>')

    def button(self, x, y, w, h, words, filled=True, cls="m"):
        if filled:
            self.add(f'<rect class="solid" x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" rx="{f(h / 2)}"/>')
            self.add(f'<text class="{cls} on" x="{f(x + w / 2)}" y="{f(y + h / 2 + 4)}" text-anchor="middle">{words}</text>')
        else:
            self.add(f'<rect class="thin" x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" rx="{f(h / 2)}"/>')
            self.add(f'<text class="{cls}" x="{f(x + w / 2)}" y="{f(y + h / 2 + 4)}" text-anchor="middle">{words}</text>')

    def tap(self, cx, cy, r):
        """A touch: a dot with two rings spreading from it."""
        self.add(f'<circle class="solid" cx="{f(cx)}" cy="{f(cy)}" r="{f(r * 0.3)}"/>')
        self.add(f'<circle class="ink" cx="{f(cx)}" cy="{f(cy)}" r="{f(r * 0.62)}"/>')
        self.add(f'<circle class="faint" cx="{f(cx)}" cy="{f(cy)}" r="{f(r)}"/>')

    def switch(self, x, y, on):
        w, h = 46, 26
        if on:
            self.add(f'<rect class="solid" x="{f(x)}" y="{f(y)}" width="{w}" height="{h}" rx="13"/>')
            self.add(f'<circle class="hole" cx="{f(x + w - 13)}" cy="{f(y + 13)}" r="8.5"/>')
        else:
            self.add(f'<rect class="line" x="{f(x)}" y="{f(y)}" width="{w}" height="{h}" rx="13"/>')
            self.add(f'<circle class="line" cx="{f(x + 13)}" cy="{f(y + 13)}" r="6.5"/>')

    # ---- marks

    def tick(self, cx, cy, r=11):
        self.add(f'<circle class="hole" cx="{f(cx)}" cy="{f(cy)}" r="{f(r + 3)}"/>')
        self.add(f'<circle class="ink" cx="{f(cx)}" cy="{f(cy)}" r="{f(r)}"/>')
        k = r * 0.45
        self.add(f'<path class="ink" d="M{f(cx - k)} {f(cy)}L{f(cx - k * 0.2)} {f(cy + k * 0.8)}L{f(cx + k)} {f(cy - k * 0.7)}"/>')

    def cross(self, cx, cy, r=11):
        self.add(f'<circle class="hole" cx="{f(cx)}" cy="{f(cy)}" r="{f(r + 3)}"/>')
        self.add(f'<circle class="bad" cx="{f(cx)}" cy="{f(cy)}" r="{f(r)}"/>')
        k = r * 0.4
        self.add(f'<path class="onbad" d="M{f(cx - k)} {f(cy - k)}L{f(cx + k)} {f(cy + k)}M{f(cx + k)} {f(cy - k)}L{f(cx - k)} {f(cy + k)}"/>')

    def number(self, cx, cy, n, r=13):
        self.add(f'<circle class="solid" cx="{f(cx)}" cy="{f(cy)}" r="{f(r)}"/>')
        self.add(f'<text class="num" x="{f(cx)}" y="{f(cy + 5)}" text-anchor="middle">{n}</text>')

    def arrow_head(self, x, y, direction=1, cls="ink", reach=7):
        self.add(f'<path class="{cls}" d="M{f(x - direction * reach)} {f(y - reach)}L{f(x)} {f(y)}L{f(x - direction * reach)} {f(y + reach)}"/>')

    def link(self, x1, x2, y, state):
        """One leg of the journey, drawn as the app draws it."""
        if state == "none":
            stub = (x2 - x1) * 0.28
            self.add(f'<path class="line" d="M{f(x1)} {f(y)}H{f(x1 + stub)}M{f(x2 - stub)} {f(y)}H{f(x2)}"/>')
        elif state == "ready":
            self.add(f'<path class="line" d="M{f(x1)} {f(y)}H{f(x2)}"/>')
        elif state == "active":
            self.add(f'<path class="ink dash" d="M{f(x1)} {f(y)}H{f(x2 - 3)}"/>')
            self.arrow_head(x2, y)
        elif state == "ok":
            self.add(f'<path class="ink" d="M{f(x1)} {f(y)}H{f(x2)}"/>')
            # On a short leg the tick is drawn smaller, so that some of the line still shows.
            self.tick((x1 + x2) / 2, y, min(11, (x2 - x1) * 0.26))
        elif state == "failed":
            self.add(f'<path class="badline" d="M{f(x1)} {f(y)}H{f(x2)}"/>')
            self.cross((x1 + x2) / 2, y)

    def picture(self, x, y, u, left, right, labels=True, span=None):
        """The app's whole picture: machine, phone, heart, and the two legs between them."""
        gap = 10 if u >= 50 else 6
        machine_w = self.machine(x, y, u)
        span = span or u * 1.5
        phone_x = x + machine_w + span
        phone_w = self.phone(phone_x, y, u)
        heart_x = phone_x + phone_w + span
        heart_w = self.heart(heart_x, y, u)
        mid = y + u / 2
        self.link(x + machine_w + gap, phone_x - gap, mid, left)
        self.link(phone_x + phone_w + gap, heart_x - gap, mid, right)
        if labels:
            self.text(x + machine_w / 2, y + u + 20, "Machine", "s", "middle")
            self.text(phone_x + phone_w / 2, y + u + 20, "Phone", "s", "middle")
            self.text(heart_x + heart_w / 2, y + u + 20, "Health Connect", "s", "middle")
        return heart_x + heart_w


def overview():
    s = Svg(262, "PAPRelay copies CPAP data from the machine to the phone over Bluetooth, and from the phone to Health Connect, a file, or another app.")
    u = 76
    mw = s.machine(44, 86, u)
    s.text(44 + mw / 2, 86 + u + 22, "CPAP machine", "t", "middle")
    s.text(44 + mw / 2, 86 + u + 41, "ResMed AirSense 11", "s", "middle")
    pu = 104
    px = 278
    pw = s.phone(px, 72, pu)
    s.text(px + pw / 2, 72 + pu + 20, "Your phone", "t", "middle")
    s.link(44 + mw + 12, px - 12, 124, "active")
    s.text((44 + mw + px) / 2, 108, "Bluetooth", "s", "middle")
    # One trunk from the phone, three branches.
    trunk = 392
    s.add(f'<path class="ink" d="M{px + pw + 12} 124H{trunk}M{trunk} 52V196M{trunk} 52H452M{trunk} 124H452M{trunk} 196H452"/>')
    for y in (52, 124, 196):
        s.arrow_head(455, y)
    s.heart(474, 30, 44)
    s.text(532, 49, "Health Connect", "b")
    s.text(532, 67, "health records", "s")
    s.page(477, 103, 32, 42)
    s.text(532, 121, "A file", "b")
    s.text(532, 139, "one line for each night", "s")
    s.share(473, 176, 40)
    s.text(532, 193, "Share", "b")
    s.text(532, 211, "send as text to any app", "s")
    s.cloud_off(46, 216, 34)
    s.text(92, 236, "No internet. No account. Read-only.", "s")
    s.save("overview.svg")


def legend():
    s = Svg(338, "How to read the picture in the app: a broken line means not connected, a whole line connected, a moving dashed line data moving, a tick it worked, a red cross it did not work.")
    s.picture(160, 26, 56, "ok", "active")
    s.add('<path class="faint" d="M40 122H680"/>')
    rows = [
        ("none", "Not connected"),
        ("ready", "Connected. Nothing is happening."),
        ("active", "Data is moving now"),
        ("ok", "It worked"),
        ("failed", "It did not work"),
    ]
    for index, (state, words) in enumerate(rows):
        y = 152 + index * 40
        s.link(160, 300, y, state)
        s.text(340, y + 5, words, "t")
    s.save("picture.svg")


def setup():
    s = Svg(488, "First-time setup in five steps: remove the machine from myAir, start pairing on the machine, type the code in PAPRelay, wait for the checks, tap Synchronise now.")
    row = 92
    top = 28
    for index in range(5):
        y = top + index * row
        s.number(42, y + 34, index + 1)
        if index:
            s.add(f'<path class="faint" d="M40 {y - 12}H680"/>')
    text_x = 286

    # 1. The other app lets go of the machine.
    y = top
    s.phone(120, y, 70)
    s.add(f'<rect class="thin" x="128" y="{y + 18}" width="19" height="19" rx="5"/>')
    s.cross(160, y + 20, 9)
    s.text(180, y + 44, "myAir", "s")
    s.lines(text_x, y + 28, ["In the <tspan font-weight=\"600\">myAir</tspan> app, remove the machine.", "The machine can be paired with one app only."], "t")

    # 2. The machine shows a code.
    y = top + row
    mw = s.machine(96, y, 62)
    s.add(f'<rect class="thin" x="{96 + mw + 12}" y="{y + 12}" width="62" height="30" rx="6"/>')
    s.text(96 + mw + 43, y + 33, "1 2 3 4", "m", "middle")
    s.add(f'<path class="thin" d="M{96 + mw + 12} {y + 27}L{96 + mw + 3} {y + 33}"/>')
    s.lines(text_x, y + 28, ["On the machine: <tspan font-weight=\"600\">More › myAir App › Connect</tspan>.", "The machine shows a code."], "t")

    # 3. The code goes into the phone.
    y = top + 2 * row
    s.phone(120, y, 70)
    s.add(f'<rect class="thin" x="166" y="{y + 16}" width="74" height="30" rx="6"/>')
    s.text(203, y + 37, "1 2 3 4", "m", "middle")
    s.add(f'<path class="thin" d="M166 {y + 31}L157 {y + 36}"/>')
    s.lines(text_x, y + 28, ["In PAPRelay, tap <tspan font-weight=\"600\">Pair the machine</tspan>.", "Type the code."], "t")

    # 4. Every leg is proved.
    y = top + 3 * row
    s.picture(72, y + 14, 40, "ok", "ok", labels=False, span=44)
    s.lines(text_x, y + 28, ["Wait. PAPRelay tests every connection.", "A tick means it works."], "t")

    # 5. The first run by hand.
    y = top + 4 * row
    s.button(84, y + 20, 138, 30, "Synchronise now", cls="m")
    s.tap(236, y + 46, 15)
    s.lines(text_x, y + 28, ["Tap <tspan font-weight=\"600\">Synchronise now</tspan>.", "After this, PAPRelay works by itself."], "t")
    s.save("setup.svg")


def home():
    s = Svg(470, "The main screen of PAPRelay with its five parts: the picture, last night's figures, the status, the Synchronise now button, and the links to Nights, Sharing and Device check.")
    x, y, w, h = 36, 24, 226, 422
    s.add(f'<rect class="ink" x="{x}" y="{y}" width="{w}" height="{h}" rx="26"/>')
    s.add(f'<path class="ink" d="M{x + 88} {y + h - 14}H{x + w - 88}"/>')
    left = x + 22
    s.text(left, y + 44, "PAPRelay", "b")
    s.text(x + w - 22, y + 44, "About", "ms", "end")
    s.picture(left + 4, y + 64, 34, "ok", "ok", labels=False, span=46)
    s.text(left, y + 136, "AirSense 11 · serial ending 1234", "m")
    s.text(left, y + 168, "Night of Sat, 14 Nov 2026", "ms")
    s.text(left, y + 192, "7 h 12 min · AHI 1.4", "b")
    s.text(left, y + 211, "Leak 95%: 9.6 L/min", "ms")
    s.text(left, y + 225, "Pressure 95%: 10.2 cmH₂O", "ms")
    s.text(left, y + 258, "Up to date.", "m")
    s.text(left, y + 274, "Last synchronised 5 minutes ago.", "ms")
    s.button(left, y + 294, 132, 32, "Synchronise now", cls="m")
    s.text(left, y + 364, "Nights", "m")
    s.text(left + 56, y + 364, "Sharing", "m")
    s.text(left + 118, y + 364, "Device check", "m")

    notes = [
        (y + 82, ["<tspan font-weight=\"600\">The picture.</tspan> Machine, phone, Health Connect.", "The lines show what is happening."]),
        (y + 188, ["<tspan font-weight=\"600\">Last night.</tspan> Hours of use and AHI,", "then leak and pressure."]),
        (y + 262, ["<tspan font-weight=\"600\">Status.</tspan> What happened the last time."]),
        (y + 310, ["<tspan font-weight=\"600\">Synchronise now.</tspan> Tap to copy the data now."]),
        (y + 360, ["<tspan font-weight=\"600\">Nights</tspan>: every night. <tspan font-weight=\"600\">Sharing</tspan>: where the", "data goes. <tspan font-weight=\"600\">Device check</tspan>: test the connections."]),
    ]
    for index, (at, rows) in enumerate(notes):
        s.add(f'<path class="faint" d="M{x + w + 10} {at}H{290}"/>')
        s.number(308, at, index + 1)
        s.lines(332, at + (5 if len(rows) == 1 else -4), rows, "t", 20)
    s.save("home.svg")


def when():
    s = Svg(196, "PAPRelay synchronises when you tap the button, when the phone comes near the machine, and about once an hour.")
    centres = [130, 360, 590]
    # You ask.
    s.button(centres[0] - 76, 44, 136, 30, "Synchronise now", cls="m")
    s.tap(centres[0] + 72, 70, 15)
    # The phone comes home.
    mw = s.machine(centres[1] - 78, 28, 60)
    s.waves(centres[1] - 78 + mw + 6, 58, 24)
    s.phone(centres[1] + 54, 22, 72)
    # The clock.
    s.clock(centres[2], 58, 30)
    captions = [
        ["You tap the button"],
        ["The phone comes", "near the machine"],
        ["About every hour"],
    ]
    for cx, rows in zip(centres, captions):
        for index, row in enumerate(rows):
            s.text(cx, 128 + index * 21, row, "t", "middle")
    s.add('<path class="faint" d="M245 30V170M475 30V170"/>')
    s.save("when.svg")


def night():
    s = Svg(206, "One night runs from 12:00 noon to 12:00 noon the next day. The machine gives the night's figures after the second 12:00, and then they are on the phone.")
    x1, x2, y = 70, 500, 96
    s.add(f'<path class="ink" d="M{x1} {y}H{x2}"/>')
    for x, label, sub in ((x1, "12:00", "midday"), ((x1 + x2) / 2, "00:00", "midnight"), (x2, "12:00", "midday")):
        s.add(f'<path class="ink" d="M{f(x)} {y - 9}V{y + 9}"/>')
        s.text(x, y + 32, label, "b", "middle")
        s.text(x, y + 50, sub, "s", "middle")
    # The hours of therapy, as a thick bar on the line.
    a, b = x1 + (x2 - x1) * 0.44, x1 + (x2 - x1) * 0.78
    s.add(f'<rect class="solid" x="{f(a)}" y="{y - 7}" width="{f(b - a)}" height="14" rx="7"/>')
    s.text((a + b) / 2, y - 20, "therapy", "s", "middle")
    s.add(f'<path class="thin" d="M{x1} {y - 44}V{y - 54}H{x2}V{y - 44}"/>')
    s.text((x1 + x2) / 2, y - 62, "one night", "t", "middle")
    # After the day closes, the figures come across.
    s.add(f'<path class="ink dash" d="M{x2 + 16} {y}H{x2 + 77}"/>')
    s.arrow_head(x2 + 80, y)
    pw = s.phone(x2 + 96, y - 44, 88)
    s.tick(x2 + 96 + pw, y - 34, 10)
    s.text(x2 + 96 + pw / 2, y + 68, "after 12:00", "b", "middle")
    s.text(x2 + 96 + pw / 2, y + 86, "the figures arrive", "s", "middle")
    s.save("night.svg")


def sharing():
    s = Svg(362, "The four choices on the Sharing page: nightly figures to the health records in Health Connect, switched on; respiratory rate to Health Connect, switched off; a table of nights in a file you choose; and sharing the recent nights as text.")
    rows = [
        (True, "heart", "Nightly figures → health records", ["Hours of use, AHI, leak, pressure. In Health Connect.", "Needs Android 16 or later. On at the start."]),
        (False, "heart", "Respiratory rate → Health Connect", ["One value for each minute of therapy.", "Off at the start."]),
        (None, "page", "Table of nights → a file", ["One line for each night (CSV).", "You choose the place: the phone, or a cloud drive."]),
        (None, "share", "Share → another app", ["The recent nights as text.", "You choose the app each time."]),
    ]
    for index, (on, icon, title, subs) in enumerate(rows):
        y = 26 + index * 84
        if index:
            s.add(f'<path class="faint" d="M40 {y - 12}H680"/>')
        if on is None:
            s.button(40, y + 12, 86, 28, "Share" if icon == "share" else "Choose…", filled=False)
        else:
            s.switch(60, y + 13, on)
        if icon == "heart":
            s.heart(158, y + 4, 44)
        elif icon == "share":
            s.share(158, y + 8, 38)
        else:
            s.page(160, y + 6, 32, 42)
        s.text(222, y + 20, title, "b")
        s.lines(222, y + 40, subs, "s", 18)
    s.save("sharing.svg")


def box(s, x, y, w, h, title, rows, cls="ink"):
    s.add(f'<rect class="{cls}" x="{x}" y="{y}" width="{w}" height="{h}" rx="10"/>')
    s.text(x + 14, y + 24, title, "b")
    s.lines(x + 14, y + 44, rows, "s", 17)


def modules():
    s = Svg(392, "The three modules of PAPRelay: app (Android), as11 (the AirSense 11 protocol) and core (the model and rules). app depends on as11 and core; as11 depends on core.")
    box(s, 40, 24, 640, 124, "app  ·  Android", [
        "ui: screens and the picture  ·  sync: engine, stores, background runs",
        "ble: Bluetooth link and scanning  ·  hc: Health Connect  ·  pairing: the stored key",
        "check: the device check",
        "The only module that knows Android.",
    ])
    box(s, 40, 176, 400, 106, "as11  ·  Kotlin/JVM", [
        "The AirSense 11 protocol: pairing, encrypted session,",
        "read-only requests, downloads.",
        "decode: the stored logs  ·  plan: what to pass on",
    ])
    box(s, 40, 310, 640, 62, "core  ·  Kotlin/JVM", [
        "Units and times  ·  nights and their records  ·  the ledger of what was written",
    ])
    # Who depends on whom: an arrow points at what is used.
    s.add('<path class="ink" d="M240 148V170"/>')
    s.add('<path class="ink" d="M233 163L240 172L247 163"/>')
    s.add('<path class="ink" d="M240 282V304"/>')
    s.add('<path class="ink" d="M233 297L240 306L247 297"/>')
    s.add('<path class="ink" d="M560 148V304"/>')
    s.add('<path class="ink" d="M553 297L560 306L567 297"/>')
    s.text(574, 232, "An arrow points", "s")
    s.text(574, 249, "at what is used.", "s")
    s.save("modules.svg")


def flow():
    s = Svg(366, "How data moves through PAPRelay: from the machine over the Bluetooth link, through the protocol client, the decoders and the plans, into the sync engine, which keeps the nights on the phone and passes them to Health Connect, a file, and the screen.")
    u = 56
    mw = s.machine(40, 34, u)
    s.text(40 + mw / 2, 34 + u + 18, "Machine", "s", "middle")
    stages = [
        ("Link", "app/ble"),
        ("Protocol", "as11"),
        ("Decoders", "as11/decode"),
        ("Plans", "as11/plan"),
    ]
    x = 40 + mw + 34
    width = 112
    y = 40
    s.add(f'<path class="ink dash" d="M{40 + mw + 8} {y + 22}H{x - 6}"/>')
    s.arrow_head(x - 3, y + 22)
    for index, (title, where) in enumerate(stages):
        left = x + index * (width + 22)
        s.add(f'<rect class="ink" x="{left}" y="{y}" width="{width}" height="46" rx="10"/>')
        s.text(left + width / 2, y + 21, title, "b", "middle")
        s.text(left + width / 2, y + 37, where, "ms", "middle")
        if index < len(stages) - 1:
            s.add(f'<path class="ink" d="M{left + width + 3} {y + 23}H{left + width + 16}"/>')
            s.arrow_head(left + width + 19, y + 23, reach=5)
    last = x + 3 * (width + 22) + width / 2
    # Down into the engine.
    s.add(f'<path class="ink" d="M{f(last)} {y + 49}V{y + 92}"/>')
    s.add(f'<path class="ink" d="M{f(last - 6)} {y + 86}L{f(last)} {y + 94}L{f(last + 6)} {y + 86}"/>')
    ex, ey, ew, eh = 200, 136, 480, 70
    s.add(f'<rect class="ink" x="{ex}" y="{ey}" width="{ew}" height="{eh}" rx="10"/>')
    s.text(ex + 16, ey + 26, "Sync engine", "b")
    s.text(ex + 122, ey + 26, "app/sync", "ms")
    s.text(ex + 16, ey + 46, "Keeps the nights. Works out what is new or changed.", "s")
    s.text(ex + 16, ey + 62, "Remembers what it wrote, so nothing is written twice.", "s")
    # What it remembers.
    s.add(f'<rect class="line" x="40" y="{ey}" width="136" height="{eh}" rx="10"/>')
    s.text(108, ey + 26, "On the phone", "b", "middle")
    s.text(108, ey + 45, "pairing key · nights", "ms", "middle")
    s.text(108, ey + 60, "record of writes", "ms", "middle")
    s.add(f'<path class="line" d="M178 {ey + 35}H198"/>')
    # Where it goes.
    targets = [(250, "heart", "Health Connect", "health records, vitals"), (430, "page", "A file", "the table of nights"), (598, "phone", "The screen", "last night, the list")]
    for cx, icon, title, sub in targets:
        s.add(f'<path class="ink" d="M{cx} {ey + eh + 3}V{ey + eh + 30}"/>')
        s.add(f'<path class="ink" d="M{cx - 6} {ey + eh + 24}L{cx} {ey + eh + 32}L{cx + 6} {ey + eh + 24}"/>')
        top = ey + eh + 42
        if icon == "heart":
            s.heart(cx - 18.5, top, 44)
        elif icon == "page":
            s.page(cx - 16, top + 2, 32, 42)
        else:
            s.phone(cx - 12, top - 2, 48)
        s.text(cx, top + 66, title, "b", "middle")
        s.text(cx, top + 83, sub, "s", "middle")
    s.save("flow.svg")


def main():
    overview()
    legend()
    setup()
    home()
    when()
    night()
    sharing()
    modules()
    flow()
    print(f"wrote {len(list(OUT.glob('*.svg')))} diagrams to {OUT}")


if __name__ == "__main__":
    main()
